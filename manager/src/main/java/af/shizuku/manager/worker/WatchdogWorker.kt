package af.shizuku.manager.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.service.WatchdogService
import af.shizuku.manager.utils.ShizukuStateMachine
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * A persistent worker that ensures the Shizuku+ Watchdog remains active.
 *
 * Samsung's OneUI is aggressive at killing background services.
 * This worker acts as a "Self-Healing" mechanism by checking the
 * state every 1-2 hours and restarting the Watchdog if necessary.
 *
 * Also used as an expedited one-shot "heal" from WatchdogAlarmReceiver on Android 15+
 * where startForegroundService() from a BroadcastReceiver is blocked while the device
 * is locked. WorkManager's SystemForegroundService (shortService type, declared in manifest)
 * puts the app into foreground state before calling doWork(), so WatchdogService.start()
 * succeeds even from a locked-screen alarm delivery.
 */
class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channelId = "shizuku_watchdog_heal"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(
                NotificationChannel(channelId, "Watchdog Heal", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(applicationContext.getString(R.string.watchdog_running))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(HEAL_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            ForegroundInfo(HEAL_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val WORK_NAME = "shizuku_watchdog_healer"
        private const val HEAL_WORK_NAME = "shizuku_watchdog_heal_once"
        private const val HEAL_NOTIFICATION_ID = 1003

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(2, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Enqueues a one-shot expedited heal used by WatchdogAlarmReceiver on Android 15+.
         * WorkManager promotes the app to foreground state via SystemForegroundService before
         * running doWork(), so WatchdogService.start() is allowed even while the device is locked.
         */
        fun scheduleOneTimeHeal(context: Context) {
            val request = OneTimeWorkRequestBuilder<WatchdogWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                HEAL_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        if (!ShizukuSettings.getWatchdog()) {
            Timber.tag("WatchdogWorker").d("Watchdog is disabled in settings, skipping healing.")
            return Result.success()
        }

        if (!WatchdogService.isRunning()) {
            Timber.tag("WatchdogWorker").w("WatchdogService was DEAD! Restarting...")
            WatchdogService.start(applicationContext)
        } else {
            Timber.tag("WatchdogWorker").d("WatchdogService is healthy.")
        }

        // Also trigger a state update to be sure
        ShizukuStateMachine.update()

        return Result.success()
    }
}
