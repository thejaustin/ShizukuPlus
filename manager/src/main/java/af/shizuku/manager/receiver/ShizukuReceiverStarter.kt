package af.shizuku.manager.receiver

import af.shizuku.common.util.UserHandleCompat
import af.shizuku.manager.AppConstants
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuApplication
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.LaunchMethod
import af.shizuku.manager.adb.AdbAuthWait
import af.shizuku.manager.adb.StartNotificationState
import af.shizuku.manager.adb.StartNotificationState.AskAgain
import af.shizuku.manager.adb.StartNotificationState.Display
import af.shizuku.manager.adb.StartNotificationState.PendingReason
import af.shizuku.manager.starter.Starter
import af.shizuku.manager.utils.SettingsPage
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.worker.AdbStartWorker
import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.topjohnwu.superuser.Shell
import io.sentry.Breadcrumb
import io.sentry.Sentry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object ShizukuReceiverStarter {
    private const val TAG = "AdbStartNotification"
    const val NOTIFICATION_ID = 1447

    // The start worker's foreground notification. WorkManager posts it and removes it again on its
    // own schedule, asynchronously, so it must never be NOTIFICATION_ID: nothing could order those
    // writes against the ownership scheme's, and a late one would replace or remove its content.
    const val FOREGROUND_NOTIFICATION_ID = 1451

    // A missing permission is a fact about the install, not about the start work NOTIFICATION_ID
    // is rendered from, so it has an id of its own that no render replaces or removes.
    private const val PERMISSION_NOTIFICATION_ID = 1452
    private const val CHANNEL_ID = "AdbStartWorker"

    /** On the swipe of a "not answered" notice, which then stays dismissed. */
    internal const val EXTRA_SWIPED_NOTICE = "af.shizuku.manager.extra.SWIPED_NOTICE"
    internal const val EXTRA_NOTICE_STAMP = "af.shizuku.manager.extra.NOTICE_STAMP"

    private const val WORK_TIMEOUT_S = 10L
    private const val RECEIVER_DEADLINE_MS = 8_000L

    fun start(
        context: Context,
        forceStart: Boolean = false,
    ) {
        if (!forceStart && (
                UserHandleCompat.myUserId() > 0 ||
                    ShizukuStateMachine.isRunning() ||
                    ShizukuStateMachine.get() == ShizukuStateMachine.State.STARTING
            )
        ) {
            return
        }

        // A connection is already holding adbd's "Allow USB debugging?" dialog open; any new
        // connection would queue another dialog (and enqueue() would cancel the waiting worker).
        if (ShizukuSettings.getLastLaunchMode() != LaunchMethod.ROOT && AdbAuthWait.isWaiting()) {
            Timber.tag(AppConstants.TAG).i("Start skipped: waiting for the adbd authorisation dialog to be answered")
            return
        }

        // One dialog per boot or explicit start: once a dialog has gone unanswered, background
        // triggers (QUICKBOOT_POWERON, the Tasker/Locale plugin) and the
        // watchdog must not be able to raise a fresh dialog every 300 s. Explicit paths pass
        // forceStart or clear the marker first (boot, token-authenticated broadcast,
        // the notification's "Attempt now").
        if (!forceStart && ShizukuSettings.getLastLaunchMode() != LaunchMethod.ROOT && AdbAuthWait.isUnanswered()) {
            Timber.tag(AppConstants.TAG).i("Start skipped: adbd authorisation dialog went unanswered; waiting for an explicit start")
            refreshNotification(context)
            return
        }

        if (ShizukuSettings.getLastLaunchMode() == LaunchMethod.ROOT) {
            rootStart(context)
        } else if (ShizukuSettings.getLastLaunchMode() == LaunchMethod.ADB) {
            if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                AdbStartWorker.enqueue(context, explicit = forceStart)
            } else {
                showPermissionErrorNotification(context)
            }
        } else {
            Timber.tag(AppConstants.TAG).w("Background start not supported")
        }
    }

    /** Symmetric with [start]: stops a running service. Shared by the manual STOP broadcast
     *  receiver and the Tasker/Locale plugin's fire receiver. */
    fun stop() {
        if (!ShizukuStateMachine.isRunning()) return
        ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
        runCatching { Shizuku.exit() }
            .onFailure { Timber.tag(AppConstants.TAG).w(it, "Shizuku.exit failed") }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.wadb_notification_title),
                    NotificationManager.IMPORTANCE_LOW,
                )
            nm.createNotificationChannel(channel)
        }
    }

    private fun cancelPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, NotifCancelReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * For [FOREGROUND_NOTIFICATION_ID] only. The prompt and notice stay in [NOTIFICATION_ID]; this
     * one just keeps the worker alive while it waits for an unlock, so it has no restore or
     * "Attempt now" of its own.
     */
    fun buildForegroundNotification(context: Context): Notification {
        ensureChannel(context)
        return NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(context.getString(R.string.wadb_notification_title))
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.ic_notification_close_24, context.getString(android.R.string.cancel), cancelPendingIntent(context))
            .build()
    }

    private fun buildNotification(
        context: Context,
        msg: String? = null,
        notice: Boolean = false,
        noticeStamp: Long = 0L,
        attemptLabel: Int = R.string.wadb_notification_attempt_now,
    ): Notification {
        ensureChannel(context)
        val cancelPendingIntent = cancelPendingIntent(context)

        // An activity, not a broadcast, so that tapping it collapses the shade and the dialog this
        // start raises is not hidden behind it. Cancel raises nothing and stays a broadcast; the
        // content tap already opens an activity.
        val attemptNowIntent =
            Intent(context, NotifAttemptActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        val attemptNowPendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                attemptNowIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        // A request code of its own, so a notice's swipe can never carry another rendering's extra.
        // A notice's swipe names the marker it was shown for (in the data URI, so each marker has
        // an intent of its own that a later notice cannot rewrite), and dismisses only that one.
        val restoreIntent = Intent(context, NotifRestoreReceiver::class.java).putExtra(EXTRA_SWIPED_NOTICE, notice)
        if (notice) {
            restoreIntent.setData(android.net.Uri.parse("shizuku-start-notice:$noticeStamp")).putExtra(EXTRA_NOTICE_STAMP, noticeStamp)
        }
        val restorePendingIntent =
            PendingIntent.getBroadcast(
                context,
                if (notice) 1 else 0,
                restoreIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val wifiIntent = SettingsPage.InternetPanel.buildIntent(context)
        val wifiPendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                wifiIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val nb = NotificationCompat.Builder(context, CHANNEL_ID)

        // BigTextStyle so a long message is readable in full when expanded.
        if (msg != null) nb.setContentText(msg).setStyle(NotificationCompat.BigTextStyle().bigText(msg))

        return nb
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(context.getString(R.string.wadb_notification_title))
            .setOngoing(true)
            .setSilent(true)
            // Re-posted as its text changes (the prompt's "Ask again" times): never alert again.
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_notification_server_restart, context.getString(attemptLabel), attemptNowPendingIntent)
            .addAction(R.drawable.ic_notification_close_24, context.getString(android.R.string.cancel), cancelPendingIntent)
            .setDeleteIntent(restorePendingIntent)
            .setContentIntent(wifiPendingIntent)
            .build()
    }

    // NOTIFICATION_ID is rendered from state and never written by events: WorkManager's WorkInfo
    // for the unique start work (what is queued or running, and the running worker's progress),
    // AdbAuthWait's held prompt, and its durable unanswered marker. Every render and every enqueue
    // decision runs on this one thread, which is the lock that orders them; it blocks on WorkManager
    // futures, which the main thread must never do.
    private val serial =
        Executors.newSingleThreadExecutor { r -> Thread(r, "adb-start-notification").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val observing = AtomicBoolean(false)

    // One plain refresh waiting on [serial] stands for any number of requests: each render reads
    // current state, so queueing more would only make a broadcast wait behind redundant reads.
    private val refreshQueued = AtomicBoolean(false)
    private val awaitingUnlock = AtomicBoolean(false)
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    // WorkManager's database is credential-encrypted. Touching WorkManager before the first
    // unlock initialises it against storage it cannot open, and it never repeats the start-up
    // cleanup it skipped, so an interrupted request would look RUNNING for the life of the process.
    private fun unlocked(app: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
        val um = app.getSystemService(android.os.UserManager::class.java)
        if (um == null || um.isUserUnlocked) return true
        if (awaitingUnlock.compareAndSet(false, true)) {
            runCatching {
                val receiver =
                    object : android.content.BroadcastReceiver() {
                        override fun onReceive(
                            context: Context,
                            intent: android.content.Intent,
                        ) {
                            runCatching { app.unregisterReceiver(this) }
                            awaitingUnlock.set(false)
                            refreshNotification(app)
                        }
                    }
                androidx.core.content.ContextCompat.registerReceiver(
                    app,
                    receiver,
                    android.content.IntentFilter(android.content.Intent.ACTION_USER_UNLOCKED),
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }.onFailure { awaitingUnlock.set(false) }
        }
        return false
    }

    // A receiver's goAsync() result must be finished within the broadcast's time limit however
    // long [serial] is waiting on WorkManager, so [then] also runs, once, after a deadline.
    private fun bounded(then: (() -> Unit)?): (() -> Unit)? {
        if (then == null) return null
        val done = AtomicBoolean(false)
        val once = { if (done.compareAndSet(false, true)) then() }
        mainHandler.postDelayed(once, RECEIVER_DEADLINE_MS)
        return once
    }

    @Volatile
    private var knownContext: Context? = null

    // What this process last rendered; touched only on [serial]. Null in a new process, so its first
    // render always writes, replacing or removing whatever a dead process left showing.
    private var rendered: Display? = null

    // Which unanswered marker the rendered notice names; a newer marker needs a new delete intent.
    private var renderedStamp = 0L

    /** Tests only: the in-memory notification state a process death loses. */
    internal fun resetForTesting() {
        serial.submit {
            rendered = null
            renderedStamp = 0L
        }.get()
        refreshQueued.set(false)
        awaitingUnlock.set(false)
        observing.set(false)
        knownContext = null
    }

    /** Tests only: waits until every render and enqueue decision queued so far has run. */
    internal fun awaitIdleForTesting(timeoutMs: Long): Boolean =
        runCatching { serial.submit {}.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }.isSuccess

    private fun appContext(context: Context?): Context? =
        context?.applicationContext?.also { knownContext = it }
            ?: knownContext
            ?: runCatching { ShizukuApplication.appContext }.getOrNull()

    /**
     * Renders the shared start notification again from current state. Safe from any thread and in a
     * process that has only just started: it also begins following the start work's WorkInfo, so
     * whatever WorkManager still has queued gets its notification and controls back. [force]
     * re-posts an unchanged rendering (the user swiped it away); [then] runs after the render.
     */
    fun refreshNotification(
        context: Context? = null,
        force: Boolean = false,
        then: (() -> Unit)? = null,
    ) {
        val app = appContext(context)
        if (app == null) {
            then?.invoke()
            return
        }
        val plain = !force && then == null
        if (plain && !refreshQueued.compareAndSet(false, true)) return
        val finish = bounded(then)
        serial.execute {
            try {
                if (plain) refreshQueued.set(false)
                render(app, force)
            } finally {
                finish?.invoke()
            }
        }
    }

    /**
     * Enqueues [request] as the unique start work, with REPLACE so that "Attempt now" really is now,
     * unless a worker is already running it or a connection holds adbd's dialog (REPLACE would
     * cancel that start, and its replacement could raise a second dialog). The decision is read
     * from WorkManager and acted on as one step against every other enqueue, cancel and render.
     */
    internal fun enqueueStart(
        context: Context,
        request: OneTimeWorkRequest,
        explicit: Boolean = false,
    ) {
        val app = appContext(context) ?: return
        serial.execute {
            if (!unlocked(app)) return@execute
            // Checked again here: the marker may have been set since the caller was admitted.
            if (!explicit && AdbAuthWait.isUnanswered()) {
                Timber.tag(TAG).i("enqueue skipped: adbd authorisation dialog went unanswered")
                render(app, force = false)
                return@execute
            }
            runCatching {
                val wm = WorkManager.getInstance(app)
                val works = wm.getWorkInfosForUniqueWork(AdbStartWorker.UNIQUE_WORK_NAME).get(WORK_TIMEOUT_S, TimeUnit.SECONDS).map { it.toWork() }
                when (val decision = StartNotificationState.enqueue(works, AdbAuthWait.isWaiting())) {
                    StartNotificationState.Enqueue.REPLACE ->
                        // A failure can come after the REPLACE committed (scheduling failed), so it
                        // says nothing about the queue; the render below shows what WorkManager holds.
                        runCatching {
                            wm
                                .enqueueUniqueWork(AdbStartWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
                                .result
                                .get(WORK_TIMEOUT_S, TimeUnit.SECONDS)
                        }.onFailure { Timber.tag(TAG).w(it, "enqueue reported failure") }
                    else -> Timber.tag(TAG).i("enqueue skipped: %s", decision)
                }
            }.onFailure { Timber.tag(TAG).w(it, "enqueue skipped: the start work's state could not be read") }
            render(app, force = false)
        }
    }

    /**
     * The user's Cancel: cancels the start work, queued or running, and dismisses the "not answered"
     * notice, as one step against enqueues. A held prompt stays until its wait ends, which cancelling
     * the worker that holds it brings about by closing its connection.
     */
    internal fun cancelStarts(
        context: Context,
        then: (() -> Unit)? = null,
    ) {
        val app = appContext(context)
        if (app == null) {
            then?.invoke()
            return
        }
        val finish = bounded(then)
        serial.execute {
            try {
                AdbAuthWait.dismissUnansweredNotice()
                if (!unlocked(app)) return@execute
                runCatching {
                    WorkManager.getInstance(app).cancelUniqueWork(AdbStartWorker.UNIQUE_WORK_NAME).result.get(WORK_TIMEOUT_S, TimeUnit.SECONDS)
                }.onFailure { Timber.tag(TAG).w(it, "cancel: WorkManager unavailable") }
                runCatching { app.getSystemService(NotificationManager::class.java)?.cancel(PERMISSION_NOTIFICATION_ID) }
                render(app, force = false)
            } finally {
                finish?.invoke()
            }
        }
    }

    /** The user swiped the notification away: a notice stays dismissed, anything still true comes back. */
    fun restoreNotification(
        context: Context,
        swipedNotice: Boolean,
        noticeStamp: Long = 0L,
        then: (() -> Unit)? = null,
    ) {
        if (swipedNotice) AdbAuthWait.dismissUnansweredNotice(noticeStamp)
        refreshNotification(context, force = true, then = then)
    }

    /** adbd's dialog is up for a background start. [attempt] is no longer used. */
    @Suppress("UNUSED_PARAMETER")
    fun postAuthPrompt(
        context: Context,
        attempt: Long,
    ) {
        appContext(context)
        AdbAuthWait.postAuthPrompt()
    }

    // Started once per process, from the first render. Every emission re-renders from a fresh read,
    // so an emission and an on-demand render can never apply an older state over a newer one.
    private fun observe(app: Context) {
        if (!observing.compareAndSet(false, true)) return
        val flow =
            runCatching { WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(AdbStartWorker.UNIQUE_WORK_NAME) }
                .getOrElse {
                    observing.set(false)
                    Timber.tag(TAG).w(it, "cannot follow the start work yet")
                    return
                }
        // The notice is hidden while a server runs, so it must be re-rendered when one arrives
        // and, above all, when it dies: the marker then stops the watchdog, and the notice with
        // "Attempt now" is the user's way to start again.
        scope.launch {
            runCatching { ShizukuStateMachine.asFlow().collect { refreshNotification(app) } }
                .onFailure { Timber.tag(TAG).w(it, "stopped following the service state") }
        }
        scope.launch {
            flow
                .catch { Timber.tag(TAG).w(it, "stopped following the start work") }
                .onCompletion { observing.set(false) }
                .collect { refreshNotification(app) }
        }
    }

    // The only code that posts or removes NOTIFICATION_ID. Runs only on [serial].
    private fun render(
        app: Context,
        force: Boolean,
    ) {
        if (!unlocked(app)) return
        observe(app)
        val display =
            StartNotificationState.display(
                works = queryWork(app),
                prompt = AdbAuthWait.prompt(),
                waitHeld = AdbAuthWait.isWaiting(),
                unmeteredAvailable = unmeteredAvailable(app),
                unanswered = AdbAuthWait.isUnansweredNoticeDue(),
                serverRunning = ShizukuStateMachine.isRunning(),
            )
        if (display == Display.Unknown) return
        runCatching {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // The missing-permission notice stops being true once the permission is held.
            if (app.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                nm.cancel(PERMISSION_NOTIFICATION_ID)
            }
            // "Unchanged" must mean it is still in the shade: the system removes a notification
            // without telling us when its channel is blocked, and drops a notify while it is.
            val showing = runCatching { nm.activeNotifications.any { it.id == NOTIFICATION_ID } }.getOrNull()
            val wanted = display != Display.None
            val stamp = if (display == Display.Unanswered) AdbAuthWait.unansweredStamp() else 0L
            if (!force && display == rendered && stamp == renderedStamp && (showing == null || showing == wanted)) return@runCatching
            when (display) {
                is Display.Prompt ->
                    // The same button, while this wait is held, offers the key again on it
                    // (NotifAttemptReceiver.attempt), so it says so.
                    nm.notify(
                        NOTIFICATION_ID,
                        buildNotification(app, promptText(app, display), attemptLabel = R.string.wadb_notification_ask_again),
                    )
                Display.Progress -> nm.notify(NOTIFICATION_ID, buildNotification(app))
                is Display.Pending -> {
                    val msg =
                        when (display.reason) {
                            PendingReason.WIFI_REQUIRED -> app.getString(R.string.wadb_notification_wifi_required)
                            PendingReason.WILL_RETRY -> app.getString(R.string.wadb_notification_retry)
                            PendingReason.QUEUED -> null
                        }
                    nm.notify(NOTIFICATION_ID, buildNotification(app, msg))
                }
                Display.Unanswered ->
                    nm.notify(
                        NOTIFICATION_ID,
                        buildNotification(
                            app,
                            app.getString(R.string.wadb_notification_auth_timed_out),
                            notice = true,
                            noticeStamp = stamp,
                        ),
                    )
                Display.None, Display.Unknown -> nm.cancel(NOTIFICATION_ID)
            }
            rendered = display
            renderedStamp = stamp
        }.onFailure { Timber.tag(TAG).w(it, "could not render the start notification") }
    }

    /**
     * The held dialog's text. Toasts may be suppressed (seen on a Samsung S24), so it says
     * where the user stands with "Ask again" and when the wait ends, as times
     * of day: Display.Prompt carries both, so a change in either renders again.
     */
    private fun promptText(
        app: Context,
        prompt: Display.Prompt,
    ): String {
        val ends = prompt.endsAtMs?.let { clockText(app, it) }
        val askAgain =
            when (val a = prompt.askAgain) {
                is AskAgain.From -> app.getString(R.string.wadb_notification_ask_again_from, clockText(app, a.atMs))
                AskAgain.Ready -> app.getString(R.string.wadb_notification_ask_again_ready)
                AskAgain.Used ->
                    ends?.let { app.getString(R.string.wadb_notification_ask_again_used, it) }
                        ?: app.getString(R.string.wadb_notification_ask_again_used_untimed)
                null -> null
            }
        // The "last prompt" sentence already gives the end.
        val endsSentence = if (prompt.askAgain == AskAgain.Used) null else ends?.let { app.getString(R.string.wadb_notification_wait_ends, it) }
        return listOfNotNull(app.getString(R.string.wadb_notification_awaiting_auth), askAgain, endsSentence)
            .joinToString(". ")
    }

    /** [wallMs] as a time of day with seconds, in the device's locale and 12/24-hour setting. */
    private fun clockText(
        context: Context,
        wallMs: Long,
    ): String {
        val skeleton = if (DateFormat.is24HourFormat(context)) "Hms" else "hms"
        return DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton), wallMs).toString()
    }

    // Null when WorkManager cannot be read, which renders nothing new rather than an empty queue.
    private fun queryWork(app: Context): List<StartNotificationState.Work>? =
        runCatching {
            WorkManager
                .getInstance(app)
                .getWorkInfosForUniqueWork(AdbStartWorker.UNIQUE_WORK_NAME)
                .get(WORK_TIMEOUT_S, TimeUnit.SECONDS)
                .map { it.toWork() }
        }.onFailure { Timber.tag(TAG).w(it, "could not read the start work's state") }
            .getOrNull()

    private fun WorkInfo.toWork(): StartNotificationState.Work {
        val step = progress.getString(AdbStartWorker.KEY_STEP)
        return StartNotificationState.Work(
            phase = StartNotificationState.Phase.valueOf(state.name),
            runAttemptCount = runAttemptCount,
            needsUnmetered = constraints.requiredNetworkType == NetworkType.UNMETERED,
            step = StartNotificationState.Step.values().firstOrNull { it.name == step },
        )
    }

    // WorkManager's own unmetered-network test, near enough for choosing the text.
    private fun unmeteredAvailable(app: Context): Boolean =
        runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            cm != null && cm.activeNetwork != null && !cm.isActiveNetworkMetered
        }.getOrDefault(false)

    private fun rootStart(context: Context) {
        Sentry.addBreadcrumb(Breadcrumb("Background Root start initiated").apply { category = "shizuku.starter" })
        if (!Shell.getShell().isRoot) {
            Sentry.addBreadcrumb(
                Breadcrumb("Background Root start failed - no root").apply {
                    category = "shizuku.starter"
                    level = io.sentry.SentryLevel.WARNING
                },
            )
            // NotificationHelper.notify(context, AppConstants.NOTIFICATION_ID_STATUS, AppConstants.NOTIFICATION_CHANNEL_STATUS, R.string.notification_service_start_no_root)
            Shell.getCachedShell()?.close()
            return
        }

        try {
            ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
            val result = Shell.cmd(Starter.internalCommand).exec()
            if (!result.isSuccess) {
                // libsu doesn't throw on a non-zero exit. Without this check the state machine is
                // left in STARTING, which update() preserves indefinitely while the binder is dead —
                // so the watchdog (which only reacts to CRASHED) never retries and the UI shows a
                // perpetual "Starting…".
                Sentry.addBreadcrumb(
                    Breadcrumb("Background Root start failed: starter exited ${result.code}").apply {
                        category = "shizuku.starter"
                        level = io.sentry.SentryLevel.ERROR
                    },
                )
                Timber.tag(AppConstants.TAG).e("Root starter exited with code ${result.code}")
                recoverFromFailedStart()
            }
        } catch (e: Exception) {
            Sentry.addBreadcrumb(
                Breadcrumb("Background Root start failed: ${e.message}").apply {
                    category = "shizuku.starter"
                    level = io.sentry.SentryLevel.ERROR
                },
            )
            Timber.tag(AppConstants.TAG).e(e, "Failed to start Shizuku with root")
            recoverFromFailedStart()
        }
    }

    /** Clears a stuck STARTING state after a failed start attempt, then re-detects: if a previous
     *  server instance is actually still alive, update() flips the state back to RUNNING. */
    private fun recoverFromFailedStart() {
        ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
        ShizukuStateMachine.update()
    }

    private fun showPermissionErrorNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(context)

        val webpageIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/thejaustin/ShizukuPlus/wiki#shizuku-isnt-starting-on-boot-for-me"))
        val pendingWebpageIntent =
            PendingIntent.getActivity(
                context,
                0,
                webpageIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val msg = context.getString(R.string.wadb_permission_error_notification_content)

        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(context.getString(R.string.wadb_permission_error_notification_title))
                .setContentText(msg)
                .setSilent(true)
                .setContentIntent(pendingWebpageIntent)
                .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
                .build()

        nm.notify(PERMISSION_NOTIFICATION_ID, notification)
    }
}
