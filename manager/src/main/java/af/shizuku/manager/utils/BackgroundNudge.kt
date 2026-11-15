package af.shizuku.manager.utils

import af.shizuku.manager.R
import af.shizuku.manager.settings.ServiceDoctorActivity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * When Shizuku keeps getting stopped and the OS/OEM is not exempting it, point the user at the
 * Service Doctor (whose Fix buttons apply the exemptions) instead of letting it fail silently (#476).
 * Shown at most once per snooze window.
 */
object BackgroundNudge {
    private const val CHANNEL_ID = "BackgroundNudge"
    const val NOTIFICATION_ID = 7476
    private const val SNOOZE_DAYS = 3

    fun maybePost(context: Context) {
        if (!af.shizuku.manager.ShizukuSettings
                .isNotificationSystemEnabled()
        ) {
            return
        }
        if (DeviceOptimizer.isNagSnoozed()) return
        if (DeviceOptimizer.backgroundState(context) == DeviceOptimizer.BackgroundState.PROTECTED) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // Channels are API 26+; Android 7 has none and posts without one.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.background_nudge_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val open =
            PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                Intent(context, ServiceDoctorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(context.getString(R.string.background_nudge_title))
                .setContentText(context.getString(R.string.background_nudge_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.background_nudge_text)))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
        DeviceOptimizer.snoozeNag(SNOOZE_DAYS)
    }
}
