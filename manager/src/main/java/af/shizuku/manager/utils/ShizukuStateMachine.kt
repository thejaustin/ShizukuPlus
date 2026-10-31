package af.shizuku.manager.utils

import af.shizuku.manager.BuildConfig
import af.shizuku.manager.ShizukuApplication
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.ActivityLogManager
import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.pm.PackageManager
import android.provider.Settings
import io.sentry.Breadcrumb
import io.sentry.Sentry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object ShizukuStateMachine {
    enum class State { STARTING, RUNNING, STOPPING, STOPPED, CRASHED }

    // Seeded from the last persisted settled state (see the persistence hook in transition()),
    // not a hardcoded STOPPED - a freshly cold-started process (e.g. WatchdogAlarmReceiver reviving
    // the app after an OEM freezer killed it, #417) otherwise has no way to tell "the server was
    // RUNNING when this process died" apart from "the user deliberately stopped it": both would
    // read as the in-memory default, and update() below would silently report STOPPED for an actual
    // crash, so the watchdog's CRASHED-triggered restart would never fire for exactly the case it
    // exists to handle.
    private var state = AtomicReference<State>(loadPersistedSettledState())
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val startingTimestamp =
        java.util.concurrent.atomic
            .AtomicLong(0L)
    private const val STARTING_TIMEOUT_MS = 90_000L
    private const val COLD_START_GRACE_MS = 2_500L

    @Volatile
    private var processStartElapsedMs = android.os.SystemClock.elapsedRealtime()
    private val binderReceivedThisProcess = AtomicBoolean(false)
    private val coldStartRecheckScheduled = AtomicBoolean(false)

    // One exemption check runs at a time in this process: two RUNNING transitions close together
    // (a crash right after the first start, then a quick restart) each launch a coroutine that could
    // read the pending flag before the other clears it. Claiming here keeps the post to one.
    private val exemptionCheckRunning = AtomicBoolean(false)

    /**
     * Tests only: signalled when the post-RUNNING coroutine (Device Hardening, privilege grant,
     * and the new-install battery-exemption check) has finished, so a scenario can wait for it.
     */
    @Volatile
    @androidx.annotation.VisibleForTesting
    var postRunningLatchForTesting: CountDownLatch? = null

    /** Tests only: what a new process would start from (the persisted settled state). */
    internal fun resetForTesting() {
        state.set(loadPersistedSettledState())
        startingTimestamp.set(0L)
        processStartElapsedMs = -COLD_START_GRACE_MS * 2
        binderReceivedThisProcess.set(false)
        coldStartRecheckScheduled.set(false)
        postRunningLatchForTesting = null
        exemptionCheckRunning.set(false)
        BatteryOptimizationGuard.resetForTesting()
        VendorBatteryRestrictions.resetForTesting()
    }

    private fun loadPersistedSettledState(): State =
        try {
            when (ShizukuSettings.getLastSettledState()) {
                State.RUNNING.name -> State.RUNNING
                State.CRASHED.name -> State.CRASHED
                else -> State.STOPPED
            }
        } catch (_: Exception) {
            State.STOPPED
        }

    init {
        Shizuku.addBinderReceivedListenerSticky(
            Shizuku.OnBinderReceivedListener {
                Sentry.addBreadcrumb(
                    Breadcrumb("Binder received - service is now RUNNING").apply {
                        category = "shizuku.service"
                    },
                )
                binderReceivedThisProcess.set(true)
                set(State.RUNNING)
                // Push the user's Plus feature flags as soon as the binder arrives - in the
                // background too (watchdog, boot), not only when Home is open - so the server never
                // runs with defaults the user changed.
                af.shizuku.manager.ShizukuSettings
                    .syncAllPlusFeaturesToServer()
            },
        )
        Shizuku.addBinderDeadListener(
            Shizuku.OnBinderDeadListener {
                Sentry.addBreadcrumb(
                    Breadcrumb("Binder dead - service connection lost").apply {
                        category = "shizuku.service"
                        level = io.sentry.SentryLevel.WARNING
                    },
                )
                setDead()
            },
        )
    }

    fun get(): State = state.get()

    private fun transition(transform: (State) -> State) {
        // Capture the value our own CAS produced: a separate state.get() after getAndUpdate can
        // observe a concurrent transition's later write, making oldState == newState and silently
        // skipping listener/broadcast side effects for a transition that did occur.
        var computed: State? = null
        val oldState = state.getAndUpdate { current -> transform(current).also { computed = it } }
        val newState = computed ?: error("getAndUpdate lambda must always execute synchronously")
        if (oldState != newState) {
            listeners.forEach { it(newState) }
            Timber.tag("ShizukuStateMachine").d(newState.toString())
            logConnectionTransition(oldState, newState)

            // Record which app build is starting this server instance. All deliberate start paths
            // (AdbStarter, ShizukuReceiverStarter, tile, StarterActivity) funnel through STARTING,
            // so this captures every fresh start centrally and lets isServerVersionSkewed() later
            // detect a running server left behind by a pre-update build.
            if (newState == State.STARTING) {
                startingTimestamp.set(System.currentTimeMillis())
                try {
                    ShizukuSettings.setServerStartedBuild(BuildConfig.VERSION_CODE)
                } catch (e: Exception) {
                    Timber.tag("ShizukuStateMachine").w(e, "Failed to record server start build")
                }
            }

            if (newState == State.RUNNING || newState == State.STOPPED || newState == State.CRASHED) {
                try {
                    val context = ShizukuApplication.appContext
                    af.shizuku.manager.automation.AutomationEngine.dispatchEvent(
                        af.shizuku.manager.automation
                            .ShizukuStateEvent(newState == State.RUNNING),
                        context,
                    )
                } catch (e: Exception) {
                    Timber.tag("ShizukuStateMachine").w(e, "Failed to dispatch automation event")
                }

                if (newState == State.RUNNING) {
                    applyWatchdogDefault()
                    try {
                        val context = ShizukuApplication.appContext
                        af.shizuku.manager.service.WatchdogService
                            .dismissCrashNotification(context)
                        af.shizuku.manager.worker.AdbStartWorker
                            .dismissNotifications(context)
                        ShizukuIssues.clearStartProblems(context)
                        val nm = context.getSystemService(android.app.NotificationManager::class.java)
                        nm?.cancel(af.shizuku.manager.receiver.ShizukuReceiverStarter.NOTIFICATION_ID)
                        nm?.cancel(af.shizuku.manager.receiver.ShizukuReceiverStarter.FOREGROUND_NOTIFICATION_ID)
                        nm?.cancel(BackgroundNudge.NOTIFICATION_ID)
                        nm?.cancel(af.shizuku.manager.adb.AdbPairingService.NOTIFICATION_ID)
                    } catch (e: Exception) {
                        Timber.tag("ShizukuStateMachine").w(e, "Failed to cancel lingering notifications on RUNNING")
                    }
                    try {
                        val context = ShizukuApplication.appContext
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                try {
                                    if (ShizukuSettings.isDeviceHardeningEnabled()) {
                                        DeviceOptimizer.applyFixes(context)
                                    }
                                    SettingsHelper.autoGrantPrivileges(context)
                                    val trigger =
                                        if (oldState == State.CRASHED ||
                                            af.shizuku.manager.service.WatchdogService
                                                .isRunning()
                                        ) {
                                            "Watchdog / service start"
                                        } else {
                                            "service start"
                                        }
                                    af.shizuku.manager.database.ScriptSnippetManager
                                        .runAutoRunSnippets(trigger)
                                } catch (e: Exception) {
                                    Timber.tag("ShizukuStateMachine").e(e, "Failed to apply optimizations and privileges on RUNNING")
                                }
                                if (ShizukuSettings.isWatchdogExemptionPending() && exemptionCheckRunning.compareAndSet(false, true)) {
                                    try {
                                        if (!ShizukuSettings.getWatchdog()) {
                                            // The user turned the Watchdog off after the default; nothing to exempt.
                                            ShizukuSettings.clearWatchdogExemptionPending()
                                        } else {
                                            // The check runs on the main thread (not this IO dispatcher): the
                                            // Robolectric unit tests serve the system services it reads reliably
                                            // only from the main looper. It stays after the Device Hardening
                                            // pass above, which is what makes it observe the pass's effect.
                                            val settled =
                                                withContext(Dispatchers.Main) {
                                                    BatteryOptimizationGuard.ensureExemption(context)
                                                }
                                            if (settled) ShizukuSettings.clearWatchdogExemptionPending()
                                        }
                                    } catch (e: Exception) {
                                        Timber.tag("ShizukuStateMachine").e(e, "Failed to check the battery exemption for the new-install Watchdog")
                                    } finally {
                                        exemptionCheckRunning.set(false)
                                    }
                                }
                                if (ShizukuSettings.getWatchdog()) {
                                    try {
                                        // Vendor battery restrictions (Samsung, Xiaomi) are re-applied by the
                                        // vendor at any time, so unlike the one-shot exemption check above this
                                        // runs at every start while the Watchdog is on. The shell work stays on this
                                        // IO dispatcher; the system-service reads inside switch to Main.
                                        VendorBatteryRestrictions.check(context)
                                    } catch (e: Exception) {
                                        Timber.tag("ShizukuStateMachine").e(e, "Failed to check vendor battery restrictions")
                                    }
                                }
                            } catch (e: Exception) {
                                Timber.tag("ShizukuStateMachine").e(e, "Failed to run the post-RUNNING work")
                            } finally {
                                postRunningLatchForTesting?.countDown()
                            }
                        }
                    } catch (e: Exception) {
                        Timber.tag("ShizukuStateMachine").w(e, "Failed to apply optimizations and privileges on RUNNING")
                    }
                }

                if (newState == State.CRASHED && oldState == State.RUNNING) {
                    runCatching { ShizukuIssues.onServerLost(ShizukuApplication.appContext) }
                }

                if ((newState == State.STOPPED || newState == State.CRASHED) && (oldState == State.RUNNING || oldState == State.STARTING)) {
                    try {
                        val context = ShizukuApplication.appContext
                        af.shizuku.manager.doctor.ServiceDoctor
                            .triggerAdaptiveAutoHealAsync(context)
                    } catch (e: Exception) {
                        Timber.tag("ShizukuStateMachine").w(e, "Failed to trigger adaptive auto-heal")
                    }
                }

                if ((newState == State.STOPPED || newState == State.CRASHED) &&
                    ShizukuSettings.isAutoCloseTcpPortEnabled()
                ) {
                    try {
                        val context = ShizukuApplication.appContext
                        if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                            // Close the ADB TCP listening port by disabling wireless debugging.
                            // This runs asynchronously so it doesn't block the state transition.
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    Settings.Global.putInt(
                                        context.contentResolver,
                                        "adb_wifi_enabled",
                                        0,
                                    )
                                    Timber.tag("ShizukuStateMachine").i("auto-closed TCP port (disabled wireless debugging)")
                                } catch (e: Exception) {
                                    Timber.tag("ShizukuStateMachine").w(e, "Failed to auto-close TCP port")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Timber.tag("ShizukuStateMachine").w(e, "Failed to initiate TCP auto-close on $newState")
                    }
                }

                // Persist so a future cold-started process (see loadPersistedSettledState() above)
                // can tell a crash from a deliberate stop instead of defaulting to "assume stopped".
                try {
                    ShizukuSettings.setLastSettledState(newState.name)
                } catch (e: Exception) {
                    Timber.tag("ShizukuStateMachine").w(e, "Failed to persist settled state")
                }
            }

            // Broadcast state change for widgets and other receivers
            try {
                val context = ShizukuApplication.appContext
                val intent =
                    android.content.Intent("af.shizuku.manager.action.STATE_CHANGED").apply {
                        setPackage(context.packageName)
                    }
                context.sendBroadcast(intent)
            } catch (_: UninitializedPropertyAccessException) {
                Timber.tag("ShizukuStateMachine").w("Skipping broadcast: appContext not initialized yet")
            }
        }
    }

    fun set(newState: State) = transition { newState }

    fun setDead() =
        transition {
            when (it) {
                State.RUNNING -> State.CRASHED
                State.STOPPING -> {
                    try {
                        val context = ShizukuApplication.appContext
                        val permissionGranted = context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
                        val shouldDisableUsbDebugging = permissionGranted && ShizukuSettings.getAutoDisableUsbDebugging()
                        if (shouldDisableUsbDebugging) {
                            Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
                        }
                    } catch (_: UninitializedPropertyAccessException) {
                        Timber.tag("ShizukuStateMachine").w("Skipping USB debugging disable: appContext not initialized yet")
                    } catch (e: Exception) {
                        Timber.tag("ShizukuStateMachine").w(e, "Failed to disable USB debugging")
                    }
                    State.STOPPED
                }
                else -> it
            }
        }

    fun update(): State {
        val span = Sentry.getSpan()?.startChild("ipc.shizuku", "pingBinder")
        val isAlive =
            try {
                Shizuku.pingBinder()
            } catch (_: Exception) {
                false
            } finally {
                span?.finish()
            }

        val currentState = get()
        val state =
            when {
                isAlive -> State.RUNNING
                currentState == State.STARTING -> {
                    // Break out of STARTING after 90 s so a failed start (server process died,
                    // ADB connection refused, etc.) never leaves the UI permanently locked.
                    val elapsed = System.currentTimeMillis() - startingTimestamp.get()
                    // A start waiting on adbd's authorisation dialog is still in progress; calling
                    // it STOPPED would invite a second start, and with it a second dialog.
                    if (elapsed > STARTING_TIMEOUT_MS &&
                        !af.shizuku.manager.adb.AdbAuthWait
                            .isWaiting()
                    ) {
                        State.STOPPED
                    } else {
                        State.STARTING
                    }
                }
                currentState == State.STOPPING -> State.STOPPING
                currentState == State.CRASHED -> State.CRASHED
                // Was RUNNING (or, thanks to loadPersistedSettledState(), a freshly cold-started
                // process that persisted RUNNING before it died) and the binder isn't answering: that's
                // a crash, not a stop. Re-verify pingBinder() to prevent a false crash transition if
                // the server attached between our initial check and reading currentState.
                currentState == State.RUNNING -> {
                    val verifiedAlive =
                        try {
                            Shizuku.pingBinder()
                        } catch (_: Exception) {
                            false
                        }
                    if (verifiedAlive) {
                        binderReceivedThisProcess.set(true)
                        State.RUNNING
                    } else {
                        val elapsedSinceProcessStart = android.os.SystemClock.elapsedRealtime() - processStartElapsedMs
                        if (!binderReceivedThisProcess.get() && elapsedSinceProcessStart in 0L until COLD_START_GRACE_MS) {
                            scheduleColdStartRecheck(COLD_START_GRACE_MS - elapsedSinceProcessStart + 50L)
                            State.RUNNING
                        } else {
                            State.CRASHED
                        }
                    }
                }
                else -> State.STOPPED
            }
        set(state)
        return state
    }

    private fun scheduleColdStartRecheck(delayMs: Long) {
        if (coldStartRecheckScheduled.compareAndSet(false, true)) {
            runCatching {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    coldStartRecheckScheduled.set(false)
                    update()
                }, delayMs)
            }.onFailure {
                coldStartRecheckScheduled.set(false)
            }
        }
    }

    /** When a drop was last observed, so the next RUNNING can report how long the gap was. */
    private val lastDropAtMs =
        java.util.concurrent.atomic
            .AtomicLong(0L)

    /**
     * Records connection-level transitions in the activity log (kept longer than ordinary entries):
     * a running server dying or being stopped, a start that never completed, and recovery. Fired
     * once per real state change, so repeated update() polling can't spam it.
     */
    private fun logConnectionTransition(
        old: State,
        new: State,
    ) {
        val message =
            when {
                old == State.RUNNING && new == State.CRASHED -> {
                    lastDropAtMs.set(System.currentTimeMillis())
                    DeactivationAdvisor.recordUnexpectedDrop()
                    "Connection: Shizuku service dropped unexpectedly (process or binder died)"
                }
                old == State.RUNNING && new == State.STOPPING -> {
                    lastDropAtMs.set(System.currentTimeMillis())
                    if (!UserStopFlag.isSet()) DeactivationAdvisor.recordUnexpectedDrop()
                    if (UserStopFlag.isSet()) "Connection: Shizuku service stopped by you" else "Connection: Shizuku service stopping"
                }
                old == State.RUNNING && new == State.STOPPED -> {
                    lastDropAtMs.set(System.currentTimeMillis())
                    if (!UserStopFlag.isSet()) DeactivationAdvisor.recordUnexpectedDrop()
                    "Connection: Shizuku service stopped"
                }
                old == State.STARTING && (new == State.STOPPED || new == State.CRASHED) ->
                    "Connection: start did not complete (${new.name.lowercase()})"
                new == State.RUNNING && lastDropAtMs.get() > 0L -> {
                    val seconds = (System.currentTimeMillis() - lastDropAtMs.getAndSet(0L)) / 1000
                    "Connection: restored after ${seconds}s"
                }
                else -> null
            } ?: return
        runCatching {
            af.shizuku.manager.database.ActivityLogManager
                .log("Shizuku", ShizukuApplication.appContext.packageName, message)
        }
    }

    fun isRunning(): Boolean = get() == State.RUNNING

    /**
     * True when the running privileged server was started by an app build older than the one now
     * installed — i.e. the app was updated but the server (a separate long-lived process) is still
     * running the old code. The binder wire protocol can differ across versions, so this can
     * silently break connections for third-party apps until the service is restarted. Returns false
     * when the starting build is unknown (0, e.g. server predates this tracking) or already current.
     */
    fun isServerVersionSkewed(): Boolean {
        if (!isRunning()) return false
        val startedBuild = ShizukuSettings.getServerStartedBuild()
        return startedBuild in 1 until BuildConfig.VERSION_CODE
    }

    /**
     * A new install's Watchdog default turns on with its first server, so that until a server has
     * run the watchdog starts nothing and shows nothing (see ShizukuSettings.decideWatchdogDefault).
     */
    private fun applyWatchdogDefault() {
        try {
            val context = ShizukuApplication.appContext
            if (ShizukuSettings.applyWatchdogDefaultOnFirstRun(context)) {
                ActivityLogManager.log("Shizuku", context.packageName, "Watchdog: turned on with the first server start (the default for a new install)")
            }
        } catch (e: Exception) {
            Timber.tag("ShizukuStateMachine").w(e, "Failed to apply the new-install Watchdog default")
        }
    }

    fun isDead(): Boolean = (get() == State.STOPPED || get() == State.CRASHED)

    fun addListener(listener: (State) -> Unit) {
        listeners.add(listener)
        listener(state.get())
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    fun asFlow(): Flow<State> =
        callbackFlow {
            val listener: (State) -> Unit = { trySend(it).isSuccess }
            addListener(listener)
            awaitClose { removeListener(listener) }
        }
}
