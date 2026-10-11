package af.shizuku.manager.adb

/**
 * What the one notification background ADB starts share (ShizukuReceiverStarter.NOTIFICATION_ID)
 * shows, and whether a new start may be enqueued, as functions of state this class does not keep:
 * WorkManager's WorkInfo for the unique start work, the authorisation wait this process holds, and
 * the durable unanswered marker. WorkInfo is the queue itself (durable, ordered, and the same
 * after a REPLACE that failed late, a stop by the system or a process restart), so nothing here can
 * disagree with what WorkManager will actually run. No Android types: ShizukuReceiverStarter maps
 * WorkInfo onto [Work] and renders the result.
 */
internal object StartNotificationState {
    enum class Phase { ENQUEUED, BLOCKED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    /** What a running worker has published with setProgress. */
    enum class Step {
        /** Under way; its progress is the notification. */
        STARTING,

        /** Promoted to a foreground worker, whose own notification shows it for now. */
        FOREGROUND,
    }

    /** One WorkInfo of the unique start work. */
    data class Work(
        val phase: Phase,
        val runAttemptCount: Int = 0,
        val needsUnmetered: Boolean = false,
        val step: Step? = null,
    )

    enum class PendingReason { WIFI_REQUIRED, WILL_RETRY, QUEUED }

    sealed interface Display {
        /** adbd's dialog is up for a connection this process holds. */
        object Prompt : Display

        object Progress : Display

        data class Pending(
            val reason: PendingReason,
        ) : Display

        /** The last dialog went unanswered and nothing newer is under way. */
        object Unanswered : Display

        object None : Display

        /** WorkInfo could not be read: what is shown stays, rather than guessing the queue empty. */
        object Unknown : Display
    }

    /**
     * [works] is null when WorkInfo could not be read. [prompt] is the dialog this process's wait is
     * holding, if any; [waitHeld] is whether any start (interactive ones post no prompt) holds the
     * wait. [unanswered] is the marker, net of the user having dismissed its notice.
     */
    fun display(
        works: List<Work>?,
        prompt: Display.Prompt?,
        waitHeld: Boolean,
        unmeteredAvailable: Boolean,
        unanswered: Boolean,
        serverRunning: Boolean = false,
    ): Display {
        if (prompt != null) return prompt
        if (works == null) return Display.Unknown
        val running = works.firstOrNull { it.phase == Phase.RUNNING }
        if (running != null) {
            when (running.step) {
                Step.STARTING -> return Display.Progress
                // A newer run is under way, so an older notice is no longer news.
                Step.FOREGROUND -> return Display.None
                // Not yet published: either about to publish, or about to stand down because
                // another start holds the wait, in which case it must not flash over that start.
                null -> if (!waitHeld) return Display.Progress
            }
        }
        val pending = works.firstOrNull { it.phase == Phase.ENQUEUED || it.phase == Phase.BLOCKED }
        if (pending != null) {
            val reason =
                when {
                    pending.needsUnmetered && !unmeteredAvailable -> PendingReason.WIFI_REQUIRED
                    pending.runAttemptCount > 0 -> PendingReason.WILL_RETRY
                    else -> PendingReason.QUEUED
                }
            return Display.Pending(reason)
        }
        // While a wait is held the dialog is still up: not yet "not answered".
        // Nor is it news while a server is running: nothing needs starting. The marker itself
        // stays and still stops unattended starts once that server is gone.
        return if (unanswered && !waitHeld && !serverRunning) Display.Unanswered else Display.None
    }

    enum class Enqueue {
        /** Nothing running: REPLACE whatever is queued, so "Attempt now" really is now. */
        REPLACE,

        /** A worker is already doing what was asked; REPLACE would cancel it mid-start. */
        ALREADY_RUNNING,

        /** A connection is holding adbd's dialog; a new worker would only stand down. */
        WAIT_HELD,
    }

    fun enqueue(
        works: List<Work>,
        waitHeld: Boolean,
    ): Enqueue =
        when {
            waitHeld -> Enqueue.WAIT_HELD
            works.any { it.phase == Phase.RUNNING } -> Enqueue.ALREADY_RUNNING
            else -> Enqueue.REPLACE
        }
}
