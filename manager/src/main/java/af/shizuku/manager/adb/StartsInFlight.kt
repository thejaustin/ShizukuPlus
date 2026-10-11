package af.shizuku.manager.adb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Start work in flight anywhere in the process: a start worker, an interactive start including
 * its wait for the binder, an AdbStarter call, an authorisation wait. Pure logic, no Android.
 *
 * Every begin and end bumps [Snapshot.generation], so a start that begins and ends between two
 * looks still changes the state, and [awaitStalled] restarts its grace on it rather than
 * measuring idleness from samples. [settleIfIdleSince] holds the same lock as [begin], so a
 * decision that nothing is starting cannot be overtaken by a start before it has been acted on.
 */
class StartsInFlight {
    data class Snapshot(
        val running: Int,
        val generation: Long,
    )

    private val lock = Any()
    private val _state = MutableStateFlow(Snapshot(0, 0))
    val state: StateFlow<Snapshot> get() = _state

    fun begin() =
        synchronized(lock) {
            val s = _state.value
            _state.value = Snapshot(s.running + 1, s.generation + 1)
        }

    fun end() =
        synchronized(lock) {
            val s = _state.value
            _state.value = Snapshot(s.running - 1, s.generation + 1)
        }

    /** Tests only: a new process starts with nothing in flight. */
    internal fun resetForTesting() = synchronized(lock) { _state.value = Snapshot(0, 0) }

    inline fun <T> track(block: () -> T): T {
        begin()
        try {
            return block()
        } finally {
            end()
        }
    }

    /**
     * Suspends until nothing has been in flight for [graceMs] while [starting] holds, then
     * returns the generation it has been idle at; returns null as soon as [starting] is false.
     * Any begin or end restarts the grace, so it is measured from the last start work actually
     * finishing.
     */
    suspend fun awaitStalled(
        starting: Flow<Boolean>,
        graceMs: Long,
    ): Long? =
        coroutineScope {
            val result = CompletableDeferred<Long?>()
            val watcher =
                launch {
                    combine(state, starting) { snapshot, isStarting -> snapshot to isStarting }
                        .collectLatest { (snapshot, isStarting) ->
                            if (!isStarting) {
                                result.complete(null)
                            } else if (snapshot.running == 0) {
                                delay(graceMs)
                                result.complete(snapshot.generation)
                            }
                        }
                }
            result.await().also { watcher.cancel() }
        }

    /**
     * Runs [settle] only if nothing has begun or ended since [generation], and returns whether it
     * ran. A [begin] on any thread waits until [settle] has returned, so the state transition it
     * makes is ordered before any start that follows it.
     */
    fun settleIfIdleSince(
        generation: Long,
        settle: () -> Unit,
    ): Boolean =
        synchronized(lock) {
            if (_state.value.generation != generation) return false
            settle()
            true
        }

    /**
     * [awaitStalled], then [settleIfIdleSince] at the generation it went idle at; a start that
     * began in between means waiting out a fresh grace rather than settling. Returns false once
     * [starting] is false without having settled.
     */
    suspend fun settleWhenStalled(
        starting: Flow<Boolean>,
        graceMs: Long,
        settle: () -> Unit,
    ): Boolean {
        while (true) {
            val idleAt = awaitStalled(starting, graceMs) ?: return false
            if (settleIfIdleSince(idleAt, settle)) return true
        }
    }
}
