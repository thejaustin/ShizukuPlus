package af.shizuku.manager.worker

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.home.HomeActivity
import af.shizuku.manager.update.UpdateChecker
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Checks for a new release in the background every few hours (Settings → About, off by default)
 * and posts one notification per new version, so users don't have to open the app to hear about
 * it (#673). Opening Home still does its own once-a-day check as before.
 */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!ShizukuSettings.isAutoUpdateEnabled() || ShizukuSettings.getUpdateCheckIntervalHours() <= 0) {
            return Result.success()
        }
        return when (val result = UpdateChecker.checkForUpdate(ShizukuSettings.getUpdateChannel())) {
            is UpdateChecker.CheckResult.UpdateAvailable -> {
                ShizukuSettings.setLastUpdateCheckFailed(false)
                val info = result.info
                if (info.versionCode > ShizukuSettings.getLastNotifiedUpdateCode()) {
                    notify(applicationContext, info)
                    ShizukuSettings.setLastNotifiedUpdateCode(info.versionCode)
                }
                Result.success()
            }
            is UpdateChecker.CheckResult.UpToDate -> {
                ShizukuSettings.setLastUpdateCheckFailed(false)
                Result.success()
            }
            is UpdateChecker.CheckResult.NetworkError -> Result.retry()
        }
    }

    /** "Later" on the notification: just dismiss it; the next new version notifies again. */
    class LaterReceiver : BroadcastReceiver() {
        override fun onReceive(
            context: Context,
            intent: Intent,
        ) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }

    companion object {
        private const val WORK_NAME = "background_update_check"
        private const val CHANNEL_ID = "update_available"
        private const val NOTIFICATION_ID = 1004
        private const val RELEASES_URL = "https://github.com/thejaustin/ShizukuPlus/releases"

        /** Starts, changes or cancels the periodic check to match the setting. */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            val hours = ShizukuSettings.getUpdateCheckIntervalHours()
            if (hours <= 0 || !ShizukuSettings.isAutoUpdateEnabled()) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val request =
                PeriodicWorkRequestBuilder<UpdateCheckWorker>(hours.toLong(), TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            // UPDATE keeps the schedule when nothing changed and applies a new interval in place.
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        private fun notify(
            context: Context,
            info: UpdateChecker.UpdateInfo,
        ) {
            val nm = NotificationManagerCompat.from(context)
            if (!nm.areNotificationsEnabled()) return
            nm.createNotificationChannel(
                NotificationChannelCompat
                    .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.update_available_channel))
                    .build(),
            )
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val open =
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, HomeActivity::class.java)
                        .setAction(HomeActivity.ACTION_SHOW_UPDATE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    flags,
                )
            val notes =
                PendingIntent.getActivity(
                    context,
                    1,
                    Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)),
                    flags,
                )
            val later =
                PendingIntent.getBroadcast(context, 2, Intent(context, LaterReceiver::class.java), flags)
            val title =
                context.getString(
                    if (info.isPrerelease) R.string.update_available_notification_title_pre else R.string.update_available_notification_title,
                    info.versionName,
                )
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification_icon)
                    .setContentTitle(title)
                    .setContentText(context.getString(R.string.update_available_notification_text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .addAction(0, context.getString(R.string.update_download), open)
                    .addAction(0, context.getString(R.string.update_release_notes), notes)
                    .addAction(0, context.getString(R.string.update_later), later)
                    .build()
            try {
                nm.notify(NOTIFICATION_ID, notification)
            } catch (e: SecurityException) {
                Timber.w(e, "update notification refused")
            }
        }
    }
}
