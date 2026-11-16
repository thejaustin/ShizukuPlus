package af.shizuku.manager.utils

/**
 * Hands state changes to listeners, always from one thread (the caller's job). A listener that
 * throws doesn't keep the others from hearing about it. Two changes made on different threads can
 * post their deliveries in the opposite order to their writes; when that leaves listeners behind
 * [current], they get [current] as well, so none ends up on a stale state.
 */
internal class StateDelivery<T>(
    private val listeners: Iterable<(T) -> Unit>,
    private val current: () -> T,
    private val onError: (Exception, T) -> Unit,
) {
    private var lastDelivered: T? = null

    fun deliver(newState: T) {
        notifyAll(newState)
        notifyAll(current())
    }

    private fun notifyAll(s: T) {
        if (s == lastDelivered) return
        lastDelivered = s
        for (listener in listeners) {
            // A listener moved the state on and the rest already heard the newer one.
            if (lastDelivered != s) return
            try {
                listener(s)
            } catch (e: Exception) {
                onError(e, s)
            }
        }
    }
}
