package af.shizuku.manager.receiver

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import timber.log.Timber
import androidx.core.app.NotificationCompat
import com.topjohnwu.superuser.Shell
import af.shizuku.manager.R
import af.shizuku.manager.AppConstants
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.LaunchMethod
import af.shizuku.manager.starter.Starter
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.SettingsPage
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.utils.UserHandleCompat
import af.shizuku.manager.worker.AdbStartWorker

object ShizukuReceiverStarter {

    const val NOTIFICATION_ID = 1447
    private const val CHANNEL_ID = "AdbStartWorker"

    enum class WorkerState {
        AWAITING_WIFI,
        AWAITING_RETRY,
        RUNNING,
        STOPPED
    }

    fun start(context: Context, forceStart: Boolean = false) {
        if ((UserHandleCompat.myUserId() > 0 || ShizukuStateMachine.isRunning()) && !forceStart) return

        if (ShizukuSettings.getLastLaunchMode() == LaunchMethod.ROOT) {
            rootStart(context)
        } else if (ShizukuSettings.getLastLaunchMode() == LaunchMethod.ADB) {
            if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                AdbStartWorker.enqueue(context)
                val initialState = if (EnvironmentUtils.getAdbTcpPort() > 0 && !EnvironmentUtils.isWifiRequired())
                    WorkerState.RUNNING else WorkerState.AWAITING_WIFI
                updateNotification(context, initialState)
            } else {
                showPermissionErrorNotification(context)
            }
        } else {
            Timber.tag(AppConstants.TAG).w("Background start not supported")
        }
    }

    fun buildNotification(context: Context, msg: String? = null): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.wadb_notification_title),
            NotificationManager.IMPORTANCE_LOW
        )
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)

        val cancelIntent = Intent(context, NotifCancelReceiver::class.java)
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val attemptNowIntent = Intent(context, NotifAttemptReceiver::class.java)
        val attemptNowPendingIntent = PendingIntent.getBroadcast(
            context, 0, attemptNowIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val restoreIntent = Intent(context, NotifRestoreReceiver::class.java)
        val restorePendingIntent = PendingIntent.getBroadcast(
            context, 0, restoreIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val wifiIntent = SettingsPage.InternetPanel.buildIntent(context)
        val wifiPendingIntent = PendingIntent.getActivity(
            context, 0, wifiIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nb = NotificationCompat.Builder(context, CHANNEL_ID)
        
        if (msg != null) nb.setContentText(msg)

        return nb
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(context.getString(R.string.wadb_notification_title))
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.ic_server_restart, context.getString(R.string.wadb_notification_attempt_now), attemptNowPendingIntent)
            .addAction(R.drawable.ic_close_24, context.getString(android.R.string.cancel), cancelPendingIntent)
            .setDeleteIntent(restorePendingIntent)
            .setContentIntent(wifiPendingIntent)
            .build()
    }

    fun updateNotification(context: Context, state: WorkerState) {
        if (state == WorkerState.STOPPED) return
        val msgId = when (state) {
            WorkerState.AWAITING_WIFI -> R.string.wadb_notification_wifi_required
            WorkerState.AWAITING_RETRY -> R.string.wadb_notification_retry
            else -> null
        }
        val msg = if (msgId != null) context.getString(msgId) else null
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(context, msg))
    }

    private fun rootStart(context: Context) {
        if (!Shell.getShell().isRoot) {
            //NotificationHelper.notify(context, AppConstants.NOTIFICATION_ID_STATUS, AppConstants.NOTIFICATION_CHANNEL_STATUS, R.string.notification_service_start_no_root)
            Shell.getCachedShell()?.close()
            return
        }

        try {
            ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
            Shell.cmd(Starter.internalCommand).exec()
        } catch (e: Exception) {
            Timber.tag(AppConstants.TAG).e(e, "Failed to start Shizuku with root")
            ShizukuStateMachine.update()
        }
    }

    private fun showPermissionErrorNotification(context: Context) {

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.wadb_notification_title),
            NotificationManager.IMPORTANCE_LOW
        )
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)

        val webpageIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/thejaustin/ShizukuPlus/wiki#shizuku-isnt-starting-on-boot-for-me"))
        val pendingWebpageIntent = PendingIntent.getActivity(
            context, 0, webpageIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val msg = context.getString(R.string.wadb_permission_error_notification_content)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(context.getString(R.string.wadb_permission_error_notification_title))
            .setContentText(msg)
            .setSilent(true)
            .setContentIntent(pendingWebpageIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }
}