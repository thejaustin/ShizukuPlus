package af.shizuku.manager.service

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
import android.os.IBinder
import android.provider.Settings
import timber.log.Timber
import androidx.core.app.NotificationCompat
import af.shizuku.manager.R
import af.shizuku.manager.MainActivity
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import af.shizuku.manager.utils.ActivityLogManager
import af.shizuku.manager.utils.SettingsPage
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.utils.StockShizukuCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.util.concurrent.atomic.AtomicBoolean

class WatchdogService : Service() {

    private var lastRestartMs = 0L
    private var consecutiveCrashes = 0
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var job: Job? = null

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        // Create notification channel before startForeground() to avoid InvalidForegroundServiceTypeException
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(WATCHDOG_CHANNEL_ID, "Watchdog", NotificationManager.IMPORTANCE_LOW)
            )
        }

        job = scope.launch {
            ShizukuStateMachine.asFlow().collectLatest { state ->
                if (state == ShizukuStateMachine.State.CRASHED) {
                    val now = System.currentTimeMillis()
                    val cooldown = backoffMs(consecutiveCrashes)
                    if (now - lastRestartMs > cooldown) {
                        consecutiveCrashes++
                        lastRestartMs = now
                        showCrashNotification()
                        ActivityLogManager.log("Shizuku", applicationContext.packageName, "Watchdog: restarting after crash #$consecutiveCrashes")
                        ShizukuReceiverStarter.start(applicationContext)
                        Timber.tag(TAG).d("Watchdog: restart #$consecutiveCrashes (cooldown was ${cooldown}ms)")
                    } else {
                        Timber.tag(TAG).d("Watchdog: restart suppressed (cooldown active, ${now - lastRestartMs}ms / ${cooldown}ms)")
                    }
                } else if (state == ShizukuStateMachine.State.RUNNING) {
                    // Reset backoff counter once service is confirmed stable
                    consecutiveCrashes = 0
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID_WATCHDOG,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(
                NOTIFICATION_ID_WATCHDOG,
                buildNotification()
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        isRunning.set(false)
        ShizukuSettings.setWatchdog(applicationContext, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        // Channel created in onCreate(); reference it by constant here
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or 
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        val launchPendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WatchdogService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, WATCHDOG_CHANNEL_ID)
            .setContentTitle(getString(R.string.watchdog_running))
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentIntent(launchPendingIntent)
            .addAction(
                R.drawable.ic_close_24,
                getString(R.string.watchdog_turn_off),
                stopPendingIntent
            )
            .setOngoing(true)
            .build()
    }

    private fun showCrashNotification() {
        val channelId = CRASH_CHANNEL_ID
        val channelName = "Crash Reports"

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(channel)
        }

        val learnMoreIntent = Intent(Intent.ACTION_VIEW).apply {
                                    setData(Uri.parse("https://github.com/thejaustin/ShizukuPlus/wiki#shizuku-keeps-stopping-randomly"))        }
        val learnMorePendingIntent = PendingIntent.getActivity(this, 0, learnMoreIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val disableIntent = SettingsPage.Notifications.NotificationChannel.buildIntent(applicationContext)
        val disablePendingIntent = PendingIntent.getActivity(this, 0, disableIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // Manual crash report via BugReportDialogActivity or similar
        val reportIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/thejaustin/ShizukuPlus/issues/new"))
        val reportPendingIntent = PendingIntent.getActivity(this, 0, reportIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.watchdog_shizuku_crashed_title))
            .setContentText(getString(R.string.watchdog_shizuku_crashed_text))
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentIntent(learnMorePendingIntent)
            .setAutoCancel(true)
            .addAction(0, getString(R.string.watchdog_shizuku_crashed_action_report_manually), reportPendingIntent)
            .addAction(0, getString(R.string.watchdog_shizuku_crashed_action_turn_off_alerts), disablePendingIntent)

        if (consecutiveCrashes >= 3
            && ShizukuSettings.isCompanionFallbackEnabled()
            && StockShizukuCompat.isInstalled(applicationContext)
        ) {
            val companionIntent = applicationContext.packageManager
                .getLaunchIntentForPackage(StockShizukuCompat.PACKAGE)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (companionIntent != null) {
                val companionPendingIntent = PendingIntent.getActivity(
                    this, 3, companionIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                builder.addAction(0, getString(R.string.watchdog_open_stock_shizuku), companionPendingIntent)
            }
        }

        val notification = builder.build()

        nm.notify(NOTIFICATION_ID_CRASH, notification)
    }

    companion object {
        private const val TAG = "ShizukuWatchdog"
        private const val NOTIFICATION_ID_WATCHDOG = 1001
        private const val NOTIFICATION_ID_CRASH = 1002
        private const val ACTION_STOP_SERVICE = "action.stop_service"
        const val WATCHDOG_CHANNEL_ID = "shizuku_watchdog"
        const val CRASH_CHANNEL_ID = "crash_reports"

        private const val BASE_COOLDOWN_MS = 5_000L
        private const val MAX_COOLDOWN_MS = 300_000L   // 5 min cap
        private val isRunning = AtomicBoolean(false)

        fun backoffMs(crashes: Int): Long =
            minOf(BASE_COOLDOWN_MS * (1L shl crashes.coerceAtMost(10)), MAX_COOLDOWN_MS)

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
                Timber.tag("ShizukuApplication").e("Failed to start WatchdogService: ${e.message}" )
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            context.stopService(Intent(context, WatchdogService::class.java))
        }

        @JvmStatic
        fun isRunning(): Boolean = isRunning.get()
    }
}