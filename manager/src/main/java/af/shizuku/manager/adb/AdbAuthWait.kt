package af.shizuku.manager.adb

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide marker for "an AdbClient has sent its public key and is holding its one connection
 * open until the user answers adbd's "Allow USB debugging?" dialog".
 *
 * adbd raises a new dialog for every connection that offers an unknown key, so while this is set
 * nothing may open another connection: [af.shizuku.manager.receiver.ShizukuReceiverStarter.start],
 * [af.shizuku.manager.worker.AdbStartWorker.enqueue] (which would otherwise REPLACE, i.e. cancel,
 * the waiting worker) and the notification's "Attempt now" all check [isWaiting]. Those checks are
 * advisory early-outs; the binding claim is [tryBegin], a single compare-and-set taken immediately
 * before the key is offered, so two starts racing past the advisory checks still cannot raise two
 * dialogs.
 */
object AdbAuthWait {
    /**
     * How long a single connection waits for the dialog to be answered, from each offer. Five
     * minutes: on a Samsung S24 an Allow came 16 s after a 150 s deadline and that start failed.
     */
    const val TIMEOUT_MS = 300_000

    // The wait AdbClient uses. adbd never tells the client that a dialog was denied (it only moves
    // on to its next prompt and leaves the connection open), so a rejection, like an unanswered
    // dialog, ends only at this deadline. Tests shorten it.
    @Volatile
    internal var timeoutMs: Int = TIMEOUT_MS

    private val waiting = AtomicInteger(0)

    fun isWaiting(): Boolean = waiting.get() > 0

    /**
     * Atomically claims the one authorisation-wait slot. Check and claim are one compare-and-set,
     * so of two connections racing here exactly one wins; the loser must abandon its start
     * without offering a key (see [AdbAuthPendingException]).
     */
    internal fun tryBegin(): Boolean =
        waiting.compareAndSet(0, 1).also { if (it) starts.begin() }

    internal fun end() {
        // Cleared before the slot is released, so it can never clear the next holder's prompt.
        heldPrompt = null
        if (waiting.compareAndSet(1, 0)) starts.end()
        ShizukuReceiverStarter.refreshNotification()
    }

    /** Start work in flight in this process; what the quick-settings tile supervises. */
    val starts = StartsInFlight()

    // Attempts are no longer numbered; only AdbStarter.startAdb's `attempt` parameter, which is
    // ignored, still names this.
    const val NO_ATTEMPT = 0L

    // The dialog the held wait is showing, for the shared start notification. In memory only: it
    // is true exactly while this process holds the connection, and a dead process holds none.
    @Volatile
    private var heldPrompt: StartNotificationState.Display.Prompt? = null

    internal fun prompt(): StartNotificationState.Display.Prompt? = heldPrompt

    /** The key has been offered and adbd's dialog is up; it shows over everything until [end]. */
    internal fun postAuthPrompt() {
        if (!isWaiting()) return
        heldPrompt = StartNotificationState.Display.Prompt
        ShizukuReceiverStarter.refreshNotification()
    }

    private const val PREF_UNANSWERED_AT = "adb_auth_unanswered_at"

    // The wall clock the marker and start requests are stamped with; tests replace it to order
    // stamps deterministically.
    @Volatile
    internal var clockMs: () -> Long = System::currentTimeMillis

    // The PREF_UNANSWERED_AT value whose notice the user dismissed. Durable, so a notice swiped
    // away (or cancelled) stays away across process restarts until a new dialog goes unanswered.
    private const val PREF_UNANSWERED_DISMISSED = "adb_auth_unanswered_dismissed"

    /**
     * Set just before this manager's ADB key is offered (AdbClient) and cleared only when adbd
     * accepts it, so it stands for "a key offer has not been accepted", however that wait ended.
     * Every background (non-forced) start stops while it is set, so an unattended device gets one
     * dialog per boot or explicit start rather than one per retry. Besides acceptance it is cleared
     * at BOOT_COMPLETED, by the notification's "Attempt now" and by the token-authenticated start
     * broadcast. A running server does not clear it. While set, not
     * dismissed, no wait held and no server running, it is also the shared start notification's
     * "not answered" notice.
     */
    // Storage that cannot be read counts as a marker, like unansweredStamp(): every guard then
    // stands down rather than offering the key unrecorded.
    fun isUnanswered(): Boolean = runCatching { ShizukuSettings.getPreferences().contains(PREF_UNANSWERED_AT) }.getOrDefault(true)

    internal fun isUnansweredNoticeDue(): Boolean =
        runCatching {
            val prefs = ShizukuSettings.getPreferences()
            prefs.contains(PREF_UNANSWERED_AT) &&
                prefs.getLong(PREF_UNANSWERED_AT, 0L) != prefs.getLong(PREF_UNANSWERED_DISMISSED, Long.MIN_VALUE)
        }.getOrDefault(false)

    @Synchronized
    fun markUnanswered(): Boolean {
        val written =
            runCatching { ShizukuSettings.getPreferences().edit().putLong(PREF_UNANSWERED_AT, clockMs()).commit() }
                .getOrDefault(false)
        ShizukuReceiverStarter.refreshNotification()
        return written
    }

    // 0 means no marker. Storage that cannot be read counts as a marker newer than any request,
    // so the guards that compare against it refuse rather than offer the key unrecorded.
    internal fun unansweredStamp(): Long =
        runCatching { ShizukuSettings.getPreferences().getLong(PREF_UNANSWERED_AT, 0L) }.getOrDefault(Long.MAX_VALUE)

    @Synchronized
    fun clearUnanswered() {
        runCatching {
            ShizukuSettings
                .getPreferences()
                .edit()
                .remove(PREF_UNANSWERED_AT)
                .remove(PREF_UNANSWERED_DISMISSED)
                // As durable as the set in markUnanswered: an apply() lost to a process death
                // would leave a marker for a key adbd had already accepted.
                .commit()
        }
        ShizukuReceiverStarter.refreshNotification()
    }

    /** Tests only: the in-memory state a process death loses (the durable marker stays). */
    internal fun resetForTesting() {
        waiting.set(0)
        heldPrompt = null
        starts.resetForTesting()
    }

    /** The user dismissed the "not answered" notice; the marker itself, and its start guard, stay. */
    @Synchronized
    internal fun dismissUnansweredNotice(stamp: Long? = null) {
        runCatching {
            val prefs = ShizukuSettings.getPreferences()
            if (prefs.contains(PREF_UNANSWERED_AT) && (stamp == null || stamp == prefs.getLong(PREF_UNANSWERED_AT, 0L))) {
                prefs.edit().putLong(PREF_UNANSWERED_DISMISSED, prefs.getLong(PREF_UNANSWERED_AT, 0L)).apply()
            }
        }
    }
}

/**
 * The key offered to adbd was not accepted: the "Allow USB debugging?" dialog was not answered within
 * [AdbAuthWait.TIMEOUT_MS], was rejected, or the connection dropped while it was showing.
 */
class AdbAuthTimeoutException(
    message: String,
) : SocketTimeoutException(message)

/**
 * Another connection already holds the one authorisation wait, so this start stood down before
 * offering a key — no second dialog was raised and nothing about the pending start changed.
 * Callers report it (or fail quietly) instead of retrying, marking the dialog unanswered, or
 * touching the state machine: all of those belong to the start that holds the wait.
 */
class AdbAuthPendingException(
    message: String,
) : IOException(message)
