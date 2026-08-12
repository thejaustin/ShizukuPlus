package af.shizuku.manager.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import af.shizuku.manager.MainActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings

abstract class AuthenticatedReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "auth_errors"
        private const val NOTIFICATION_ID = 1450
    }

    override fun onReceive(context: Context, intent: Intent) {
        val rawToken = intent.getStringExtra("auth")
        val authToken = if (rawToken != null) af.shizuku.manager.utils.IntentCrypto.decrypt(rawToken) else null
        val expectedToken = ShizukuSettings.getAuthToken()

        if (authToken.isNullOrEmpty()) {
            context.notify(
                R.string.notification_auth_missing_title,
                R.string.notification_auth_missing_message
            )
        } else if (!java.security.MessageDigest.isEqual(authToken.toByteArray(), expectedToken.toByteArray())) {
            context.notify(
                R.string.notification_auth_invalid_title,
                R.string.notification_auth_invalid_message
            )
        } else {
            onAuthenticated(context, intent)
        }
    }

    private fun Context.notify(title: Int, message: Int) {
        val titleStr = getString(title)
        val messageStr = getString(message)

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_auth_errors_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(channel)
        }

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

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(titleStr)
            .setContentText(messageStr)
            .setContentIntent(launchPendingIntent)
            .setAutoCancel(true)
            .setSmallIcon(R.drawable.ic_system_icon)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    abstract fun onAuthenticated(context: Context, intent: Intent)
}
