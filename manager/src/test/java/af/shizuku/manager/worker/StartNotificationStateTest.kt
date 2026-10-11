package af.shizuku.manager.worker

import af.shizuku.manager.adb.StartNotificationState
import af.shizuku.manager.adb.StartNotificationState.Display
import af.shizuku.manager.adb.StartNotificationState.Enqueue
import af.shizuku.manager.adb.StartNotificationState.PendingReason
import af.shizuku.manager.adb.StartNotificationState.Phase
import af.shizuku.manager.adb.StartNotificationState.Step
import af.shizuku.manager.adb.StartNotificationState.Work
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The shared ADB-start notification and the enqueue decision as functions of WorkManager's
 * WorkInfo for the unique start work, the held prompt and the unanswered marker. Each case is a
 * state WorkManager can actually report, including those behind the four review findings that the
 * old in-memory shadow of the queue got wrong.
 */
class StartNotificationStateTest :
    FunSpec({

        class Case(
            val name: String,
            val works: List<Work>?,
            val expected: Display,
            val prompt: Display.Prompt? = null,
            val waitHeld: Boolean = prompt != null,
            val unmeteredAvailable: Boolean = true,
            val unanswered: Boolean = false,
        )

        val prompt = Display.Prompt()
        val queued = Work(Phase.ENQUEUED)
        val retrying = Work(Phase.ENQUEUED, runAttemptCount = 1)
        val needsWifi = Work(Phase.ENQUEUED, needsUnmetered = true)
        val starting = Work(Phase.RUNNING, runAttemptCount = 1, step = Step.STARTING)
        val foreground = Work(Phase.RUNNING, runAttemptCount = 1, step = Step.FOREGROUND)
        val justStarted = Work(Phase.RUNNING, runAttemptCount = 1)
        val succeeded = Work(Phase.SUCCEEDED, runAttemptCount = 1)
        val failed = Work(Phase.FAILED, runAttemptCount = 1)
        val cancelled = Work(Phase.CANCELLED)

        val displayCases =
            listOf(
                Case("no WorkInfo shows nothing", emptyList(), Display.None),
                Case("only finished work shows nothing", listOf(succeeded, failed, cancelled), Display.None),
                Case("only finished work and the marker shows the notice", listOf(failed), Display.Unanswered, unanswered = true),
                Case("queued and never run shows the bare title", listOf(queued), Display.Pending(PendingReason.QUEUED)),
                Case("blocked counts as queued", listOf(Work(Phase.BLOCKED)), Display.Pending(PendingReason.QUEUED)),
                Case(
                    "queued behind an unmet Wi-Fi constraint says Wi-Fi is required",
                    listOf(needsWifi),
                    Display.Pending(PendingReason.WIFI_REQUIRED),
                    unmeteredAvailable = false,
                ),
                Case("a Wi-Fi constraint that is met is not mentioned", listOf(needsWifi), Display.Pending(PendingReason.QUEUED)),
                Case("queued after a run says it will retry", listOf(retrying), Display.Pending(PendingReason.WILL_RETRY)),
                Case(
                    "an unmet Wi-Fi constraint outranks the retry text",
                    listOf(retrying.copy(needsUnmetered = true)),
                    Display.Pending(PendingReason.WIFI_REQUIRED),
                    unmeteredAvailable = false,
                ),
                Case("a queued request outranks an older notice", listOf(queued), Display.Pending(PendingReason.QUEUED), unanswered = true),
                Case("a running worker's progress", listOf(starting), Display.Progress),
                Case("a running worker outranks an older notice", listOf(starting), Display.Progress, unanswered = true),
                Case("a worker that has not published yet shows progress", listOf(justStarted), Display.Progress),
                Case(
                    "a worker that has not published while another start holds the wait is standing down",
                    listOf(justStarted),
                    Display.None,
                    waitHeld = true,
                ),
                Case(
                    "no notice while a wait is held: that dialog is still up",
                    listOf(justStarted),
                    Display.None,
                    waitHeld = true,
                    unanswered = true,
                ),
                Case("a foreground worker is shown by its own notification", listOf(foreground), Display.None),
                Case("a foreground worker makes an older notice stale", listOf(foreground), Display.None, unanswered = true),
                Case("the prompt shows over progress", listOf(starting), prompt, prompt = prompt),
                Case("the prompt shows over a foreground worker", listOf(foreground), prompt, prompt = prompt),
                Case("the prompt shows over a queued request", listOf(needsWifi), prompt, prompt = prompt, unmeteredAvailable = false),
                Case("the prompt shows over the notice", listOf(failed), prompt, prompt = prompt, unanswered = true),
                Case("the prompt shows even when WorkInfo cannot be read", null, prompt, prompt = prompt),
                Case("unreadable WorkInfo leaves what is shown", null, Display.Unknown, unanswered = true),
                // Finding 1: the run returned failure (it stood down), but a system stop won the
                // race and WorkManager put the same request back in the queue.
                Case(
                    "finding 1: a request a system stop re-enqueued after its run returned keeps its controls",
                    listOf(retrying),
                    Display.Pending(PendingReason.WILL_RETRY),
                ),
                Case(
                    "finding 1: the re-enqueued request's next run shows its progress",
                    listOf(starting.copy(runAttemptCount = 2)),
                    Display.Progress,
                ),
                Case(
                    "finding 1: and a transient failure of that run leaves it queued with its controls",
                    listOf(retrying.copy(runAttemptCount = 2)),
                    Display.Pending(PendingReason.WILL_RETRY),
                ),
                // Finding 2: REPLACE committed (A cancelled, B inserted), then scheduling threw and the
                // Operation failed. The database, not the Operation, is what is read.
                Case(
                    "finding 2: a request whose enqueue Operation failed after commit still shows",
                    listOf(Work(Phase.CANCELLED, runAttemptCount = 1), queued),
                    Display.Pending(PendingReason.QUEUED),
                ),
                Case(
                    "finding 2: the replaced request's backoff text does not survive it",
                    listOf(Work(Phase.CANCELLED, runAttemptCount = 3), needsWifi),
                    Display.Pending(PendingReason.WIFI_REQUIRED),
                    unmeteredAvailable = false,
                ),
                // Finding 3: a new process (no prompt, nothing rendered yet) reads WorkInfo on the
                // swipe's broadcast; WorkManager has reset an interrupted RUNNING run to ENQUEUED.
                Case(
                    "finding 3: after process recreation a request still waiting for Wi-Fi gets its controls back",
                    listOf(needsWifi),
                    Display.Pending(PendingReason.WIFI_REQUIRED),
                    unmeteredAvailable = false,
                ),
                Case(
                    "finding 3: a run interrupted by process death comes back as queued for retry",
                    listOf(retrying),
                    Display.Pending(PendingReason.WILL_RETRY),
                ),
                // Finding 4: the notice is the marker, which every verified success clears, and a
                // timeout that finds the server running never sets.
                Case(
                    "finding 4: an interactive success after an unanswered timeout removes the notice",
                    listOf(failed),
                    Display.None,
                    unanswered = false,
                ),
                Case(
                    "finding 4: a late worker timeout after an interactive success shows nothing",
                    listOf(succeeded, failed),
                    Display.None,
                    unanswered = false,
                ),
            )

        displayCases.forEach { c ->
            test(c.name) {
                StartNotificationState.display(
                    works = c.works,
                    prompt = c.prompt,
                    waitHeld = c.waitHeld,
                    unmeteredAvailable = c.unmeteredAvailable,
                    unanswered = c.unanswered,
                ) shouldBe c.expected
            }
        }

        class EnqueueCase(
            val name: String,
            val works: List<Work>,
            val waitHeld: Boolean,
            val expected: Enqueue,
        )

        val enqueueCases =
            listOf(
                EnqueueCase("nothing queued: enqueue", emptyList(), false, Enqueue.REPLACE),
                EnqueueCase("only finished work: enqueue", listOf(succeeded, failed, cancelled), false, Enqueue.REPLACE),
                EnqueueCase("queued: replace it, so Attempt now is now", listOf(needsWifi), false, Enqueue.REPLACE),
                EnqueueCase("in backoff: replace it", listOf(retrying), false, Enqueue.REPLACE),
                EnqueueCase("blocked: replace it", listOf(Work(Phase.BLOCKED)), false, Enqueue.REPLACE),
                EnqueueCase("running: leave the start in flight alone", listOf(starting), false, Enqueue.ALREADY_RUNNING),
                EnqueueCase("running in the foreground: leave it alone", listOf(foreground), false, Enqueue.ALREADY_RUNNING),
                EnqueueCase("a held wait with nothing queued: do nothing", emptyList(), true, Enqueue.WAIT_HELD),
                EnqueueCase("a held wait with a queued request: do nothing", listOf(queued), true, Enqueue.WAIT_HELD),
                EnqueueCase("a held wait and a running worker: do nothing", listOf(starting), true, Enqueue.WAIT_HELD),
                EnqueueCase(
                    "finding 2: after a late-failed REPLACE the committed request is replaced again, not duplicated",
                    listOf(Work(Phase.CANCELLED, runAttemptCount = 1), queued),
                    false,
                    Enqueue.REPLACE,
                ),
            )

        enqueueCases.forEach { c ->
            test("enqueue: ${c.name}") {
                StartNotificationState.enqueue(c.works, c.waitHeld) shouldBe c.expected
            }
        }
    })
