package af.shizuku.manager.service

import af.shizuku.manager.MainActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import af.shizuku.manager.utils.LiveActivityNotificationManager
import af.shizuku.manager.utils.ShizukuIssues
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.utils.StockShizukuCompat
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class WatchdogService : Service() {
    private var lastRestartMs = 0L
    private var consecutiveCrashes = 0
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var job: Job? = null
    private var pendingRecoveryJob: Job? = null
    private var stabilityJob: Job? = null
    private var cooldownRetryJob: Job? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun acquireTemporaryWakeLock(durationMs: Long = 10_000L) {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                wakeLock = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ShizukuPlus:WatchdogRecovery")
                wakeLock?.setReferenceCounted(false)
            }
            wakeLock?.acquire(durationMs)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to acquire temporary wake lock")
        }
    }

    private val powerReceiver =
        object : android.content.BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent?,
            ) {
                when (intent?.action) {
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        Timber.tag(TAG).i("Power disconnected (USB unplugged)")
                        acquireTemporaryWakeLock(5_000L)
                        if (ShizukuSettings.getTcpMode() || ShizukuSettings.isOnDeviceAdbTcpEnabled()) {
                            val port = if (ShizukuSettings.getTcpMode()) ShizukuSettings.getTcpPort().takeIf { it in 1..65535 } ?: 5555 else 5555
                            AdbProxyService.enableAdbTcp(port)
                        }
                        ShizukuStateMachine.update()
                    }
                    android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                        Timber.tag(TAG).i("Power save mode changed")
                        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                        if (pm?.isPowerSaveMode == true) {
                            scope.launch(Dispatchers.IO) {
                                af.shizuku.manager.utils.DeviceOptimizer
                                    .applyFixes(context)
                            }
                        }
                    }
                }
            }
        }

    // Only true when the user explicitly taps "Turn off" from the notification — keeps
    // onDestroy() from clearing the watchdog setting when the service self-stops due to a
    // transient foreground-start rejection (background restrictions, FGS time limit, etc.).
    private var intentionalStop = false

    // Returns false on failure so callers can bail out via stopSelf() instead of crashing
    // (RemoteServiceException$CannotPostForegroundServiceNotificationException, background-start
    // restrictions, etc. — same pattern as AutomationService.ensureForeground(), SHIZUKUPLUS-5P).
    private fun startForegroundSafely(): Boolean =
        try {
            val notif = buildNotification(this, currentLiveStatus)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID_WATCHDOG,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(
                    NOTIFICATION_ID_WATCHDOG,
                    notif,
                )
            }
            true
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "startForeground refused; stopping watchdog service")
            false
        }

    override fun onCreate() {
        super.onCreate()
        setRunning(true)

        // Stop legacy ShizukuLiveService and dismiss notification 9999 if active
        try {
            stopService(Intent(this, ShizukuLiveService::class.java))
            LiveActivityNotificationManager.dismiss(this)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to stop legacy live service on watchdog start")
        }

        // Create notification channel before startForeground() to avoid
        // InvalidForegroundServiceTypeException. Wrapped in try-catch so that
        // a null/restricted NotificationManager never prevents startForeground()
        // from being called (which would cause ForegroundServiceDidNotStartInTimeException).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.createNotificationChannel(
                    NotificationChannel(WATCHDOG_CHANNEL_ID, "Watchdog", NotificationManager.IMPORTANCE_LOW),
                )
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to create watchdog notification channel")
            }
        }

        // Must be called within 5s of startForegroundService(); do it before any
        // coroutine work so that a heavy initialisation path can never delay it.
        if (!startForegroundSafely()) {
            setRunning(false)
            stopSelf()
            return
        }
        // Problems shown in their own notification until now move into this one.
        ShizukuIssues.render(applicationContext)

        val powerFilter =
            android.content.IntentFilter().apply {
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(powerReceiver, powerFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(powerReceiver, powerFilter)
        }

        job =
            scope.launch {
                ShizukuStateMachine.asFlow().collectLatest { state ->
                    // The title says whether the server is up.
                    refreshNotification(applicationContext)
                    if (state == ShizukuStateMachine.State.CRASHED) {
                        acquireTemporaryWakeLock(15_000L)
                        // Suppress false crash alert if disconnected from Wi-Fi on Wireless ADB
                        if (ShizukuIssues.droppedWithNetwork(applicationContext)) {
                            Timber.tag(TAG).d("Watchdog: server dropped because Wi-Fi/network is disconnected; suppressing crash alert")
                            stabilityJob?.cancel()
                            pendingRecoveryJob?.cancel()
                            return@collectLatest
                        }

                        // The user stopped it; a crash reported in the middle of their stop must
                        // not bring it back.
                        if (af.shizuku.manager.utils.UserStopFlag
                                .isSet()
                        ) {
                            stabilityJob?.cancel()
                            pendingRecoveryJob?.cancel()
                            return@collectLatest
                        }
                        // Restarting is off: only the status shows here, and the stop is
                        // reported by ShizukuIssues.onServerLost like with no Watchdog at all.
                        if (!ShizukuSettings.isWatchdogAutoRestartEnabled()) {
                            stabilityJob?.cancel()
                            pendingRecoveryJob?.cancel()
                            return@collectLatest
                        }

                        // The server is no longer RUNNING, so the stability window ends here:
                        // left armed, it would fire while the server is dead and reset
                        // consecutiveCrashes, making the next crash look like a first restart.
                        stabilityJob?.cancel()
                        // A recovery job armed by an earlier first restart is stale too: the
                        // server just died again, so what that job waits for is a later
                        // restart's business now. Left armed, its timeout would post a
                        // duplicate heads-up for this same outage, or a later restart's
                        // server would be logged as "came back after restart #1".
                        pendingRecoveryJob?.cancel()
                        val now = System.currentTimeMillis()
                        val cooldown = backoffMs(consecutiveCrashes)
                        if (now - lastRestartMs > cooldown) {
                            val wasFirstRestart = (consecutiveCrashes == 0)
                            consecutiveCrashes++
                            lastRestartMs = now
                            if (!wasFirstRestart) {
                                reportCrash(stopped = false)
                            }
                            // Repeated crashes with no OS exemption usually mean an OEM battery manager
                            // is killing it: send the user to the Service Doctor once (#476).
                            if (consecutiveCrashes >= 2) {
                                af.shizuku.manager.utils.BackgroundNudge
                                    .maybePost(applicationContext)
                            }
                            ActivityLogManager.log("Shizuku", applicationContext.packageName, "Watchdog: restarting after crash #$consecutiveCrashes")
                            if (wasFirstRestart) {
                                pendingRecoveryJob?.cancel()
                                pendingRecoveryJob =
                                    scope.launch {
                                        val cameBack =
                                            withTimeoutOrNull(RECOVERY_WAIT_MS) {
                                                if (ShizukuStateMachine.get() == ShizukuStateMachine.State.RUNNING) {
                                                    true
                                                } else {
                                                    ShizukuStateMachine.asFlow().first { it == ShizukuStateMachine.State.RUNNING }
                                                    true
                                                }
                                            } ?: false
                                        if (cameBack) {
                                            // Auto-run snippets run from the RUNNING transition.
                                            ActivityLogManager.log("Shizuku", applicationContext.packageName, "Watchdog: the server came back after restart #1")
                                        } else {
                                            reportCrash(stopped = true)
                                        }
                                    }
                            }
                            // Short grace first: whoever killed the server (adb or a PC starting
                            // it again, automation, the starter) is often already starting a new
                            // one. Starting ours on top raced it: both starters killed the old
                            // server and two servers ran. Skip if a binder arrived meanwhile.
                            scope.launch {
                                delay(RESTART_GRACE_MS)
                                if (ShizukuStateMachine.update() == ShizukuStateMachine.State.RUNNING) {
                                    Timber.tag(TAG).d("Watchdog: a server came back by itself; restart skipped")
                                } else {
                                    ShizukuReceiverStarter.start(applicationContext)
                                }
                            }
                            Timber.tag(TAG).d("Watchdog: restart #$consecutiveCrashes (cooldown was ${cooldown}ms)")
                        } else {
                            Timber.tag(TAG).d("Watchdog: restart suppressed (cooldown active, ${now - lastRestartMs}ms / ${cooldown}ms)")
                            // Held back, not given up: try again once the cooldown has passed, and
                            // say it's stopped meanwhile. Nothing used to follow, so a server that
                            // died again inside the window stayed down without a word.
                            reportCrash(stopped = true)
                            cooldownRetryJob?.cancel()
                            cooldownRetryJob =
                                scope.launch {
                                    delay(cooldown - (now - lastRestartMs) + RESTART_GRACE_MS)
                                    if (ShizukuStateMachine.update() != ShizukuStateMachine.State.RUNNING &&
                                        !af.shizuku.manager.utils.UserStopFlag
                                            .isSet() &&
                                        ShizukuSettings.isWatchdogAutoRestartEnabled()
                                    ) {
                                        lastRestartMs = System.currentTimeMillis()
                                        consecutiveCrashes++
                                        ShizukuReceiverStarter.start(applicationContext)
                                    }
                                }
                        }
                    } else if (state == ShizukuStateMachine.State.RUNNING) {
                        // Not pendingRecoveryJob: RUNNING is exactly what the recovery check
                        // waits for, and both run on the main dispatcher, so cancelling here
                        // would kill that check before it logs the recovery.
                        stabilityJob?.cancel()
                        stabilityJob =
                            scope.launch {
                                delay(STABILITY_WINDOW_MS)
                                consecutiveCrashes = 0
                                // Running steadily again: the crashes are history.
                                ShizukuIssues.clear(applicationContext, KEY_CRASHED)
                            }
                        dismissCrashNotification(applicationContext)
                    }
                }
            }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (!startForegroundSafely()) {
            setRunning(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_SERVICE) {
            intentionalStop = true
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(powerReceiver)
        } catch (_: Exception) {
        }
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null

        stabilityJob?.cancel()
        cooldownRetryJob?.cancel()
        pendingRecoveryJob?.cancel()
        job?.cancel()
        scope.cancel()
        setRunning(false)
        currentLiveStatus = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(NOTIFICATION_ID_WATCHDOG)
        // With this notification gone, problems go back to their own (if the user wants them).
        ShizukuIssues.render(applicationContext)
        // Only clear the user's preference when the service was deliberately stopped via the
        // notification action. Transient stops (failed foreground-start, background-start
        // restrictions) must not wipe the setting — the watchdog should re-arm on next boot.
        if (intentionalStop) {
            ShizukuSettings.setWatchdog(applicationContext, false)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification = buildNotification(this, currentLiveStatus)

    /**
     * The server crashed again ([stopped] false: the watchdog is restarting it) or did not come
     * back after a restart. Either shows in this notification; one that stayed down also alerts if
     * the user's "Helper notifications" setting allows it.
     */
    private fun reportCrash(stopped: Boolean) {
        val ctx = applicationContext
        val actions = mutableListOf(ShizukuIssues.retryAction(ctx))
        if (consecutiveCrashes >= 3 &&
            ShizukuSettings.isCompanionFallbackEnabled() &&
            StockShizukuCompat.isInstalled(ctx)
        ) {
            ctx.packageManager
                .getLaunchIntentForPackage(StockShizukuCompat.PACKAGE)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let {
                    val pi = PendingIntent.getActivity(this, 3, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    actions += ShizukuIssues.Action(getString(R.string.watchdog_open_stock_shizuku), pi)
                }
        }
        // Last: the Watchdog notification has room for two, and the stock Shizuku fallback matters more.
        actions += ShizukuIssues.openDoctorAction(ctx)
        val learnMore =
            PendingIntent.getActivity(
                this,
                10,
                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/thejaustin/ShizukuPlus/wiki#shizuku-keeps-stopping-randomly"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        ShizukuIssues.report(
            ctx,
            if (stopped) {
                ShizukuIssues.stoppedIssue(ctx, actions, learnMore)
            } else {
                ShizukuIssues.Issue(
                    KEY_CRASHED,
                    ShizukuIssues.Severity.PROBLEM,
                    getString(R.string.watchdog_shizuku_crashed_title),
                    getString(R.string.watchdog_shizuku_crashed_text),
                    actions,
                    learnMore,
                )
            },
        )
    }

    companion object {
        private const val TAG = "ShizukuWatchdog"
        private const val NOTIFICATION_ID_WATCHDOG = 1001
        const val NOTIFICATION_ID_CRASH = 1002
        private const val ACTION_STOP_SERVICE = "action.stop_service"
        const val WATCHDOG_CHANNEL_ID = "shizuku_watchdog"
        private const val KEY_CRASHED = "crashed"

        /** How long a first restart waits for RUNNING before the crash heads-up is posted: a successful restart took ~11 s on an API 34 emulator, a loaded device's first start 32 s. A restart slower than this, or one that raises a fresh authorisation dialog answered later, still gets the heads-up — by design for now. */
        const val RECOVERY_WAIT_MS = 60_000L
        private const val RESTART_GRACE_MS = 1_500L

        /** How long the server must remain RUNNING before consecutive crash count resets. */
        const val STABILITY_WINDOW_MS = 60_000L

        private const val BASE_COOLDOWN_MS = 5_000L
        private const val MAX_COOLDOWN_MS = 300_000L // 5 min cap

        @JvmStatic
        fun dismissCrashNotification(context: Context) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                nm.cancel(NOTIFICATION_ID_CRASH)
            } catch (_: Exception) {
            }
        }

        private val isRunning = AtomicBoolean(false)
        private val runningListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

        @Volatile private var currentLiveStatus: String? = null

        fun backoffMs(crashes: Int): Long =
            minOf(BASE_COOLDOWN_MS * (1L shl crashes.coerceAtMost(10)), MAX_COOLDOWN_MS)

        @JvmStatic
        fun addRunningListener(listener: (Boolean) -> Unit) {
            runningListeners.add(listener)
        }

        @JvmStatic
        fun removeRunningListener(listener: (Boolean) -> Unit) {
            runningListeners.remove(listener)
        }

        private fun setRunning(running: Boolean) {
            if (isRunning.getAndSet(running) != running) {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    runningListeners.forEach { it(running) }
                } else {
                    Handler(Looper.getMainLooper()).post {
                        runningListeners.forEach { it(running) }
                    }
                }
            }
        }

        @JvmStatic
        fun updateLiveStatus(
            context: Context,
            status: String?,
        ) {
            currentLiveStatus = status
            refreshNotification(context)
        }

        /** Redraws the ongoing notification from the server state, current problem and status. */
        @JvmStatic
        fun refreshNotification(context: Context) {
            if (!isRunning()) return
            runCatching {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.notify(NOTIFICATION_ID_WATCHDOG, buildNotification(context))
            }.onFailure { Timber.tag(TAG).w(it, "could not refresh the watchdog notification") }
        }

        @JvmStatic
        fun buildNotification(
            context: Context,
            status: String? = currentLiveStatus,
        ): Notification {
            val launchIntent =
                Intent(context, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                }
            val launchPendingIntent =
                PendingIntent.getActivity(
                    context,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val stopIntent =
                Intent(context, WatchdogService::class.java).apply {
                    action = ACTION_STOP_SERVICE
                }
            val stopPendingIntent =
                PendingIntent.getService(
                    context,
                    1,
                    stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            // A problem takes the notification over; otherwise it says whether the server is up,
            // with the live activity line under that.
            val issue = ShizukuIssues.top()
            val title =
                issue?.title ?: context.getString(
                    if (ShizukuStateMachine.isRunning()) R.string.watchdog_status_running else R.string.watchdog_status_stopped,
                )
            val text = issue?.text ?: status
            val builder =
                NotificationCompat
                    .Builder(context, WATCHDOG_CHANNEL_ID)
                    .setContentTitle(title)
                    .setSmallIcon(R.drawable.ic_notification_icon)
                    // Always the app: an issue's own target (a help page) would surprise here.
                    .setContentIntent(launchPendingIntent)
                    .setOnlyAlertOnce(true)
                    .setOngoing(true)
            issue?.actions?.take(2)?.forEach { builder.addAction(0, it.label, it.intent) }
            builder.addAction(0, context.getString(R.string.watchdog_turn_off), stopPendingIntent)
            if (!text.isNullOrEmpty()) {
                builder.setContentText(text)
                if (issue != null) builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
            }
            return builder.build()
        }

        @JvmStatic
        fun start(context: Context) {
            try {
                val intent = Intent(context, WatchdogService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Timber.tag("ShizukuApplication").w(e, "Failed to start WatchdogService: ${e.message}")
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            context.stopService(Intent(context, WatchdogService::class.java))
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID_WATCHDOG)
        }

        @JvmStatic
        fun isRunning(): Boolean = isRunning.get()

        /** Running and allowed to restart the server, so it is the one that reports a stop. */
        fun isRestarting(): Boolean = isRunning() && ShizukuSettings.isWatchdogAutoRestartEnabled()
    }
}
