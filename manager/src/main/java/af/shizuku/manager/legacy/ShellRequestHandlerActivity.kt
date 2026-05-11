package af.shizuku.manager.legacy

import android.os.Bundle
import android.widget.Toast
import af.shizuku.manager.app.AppActivity
import af.shizuku.manager.shell.ShellBinderRequestHandler

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import androidx.core.app.NotificationCompat
import af.shizuku.manager.MainActivity

class ShellRequestHandlerActivity : AppActivity() {

    companion object {
        private const val CHANNEL_ID = "auth_errors"
        private const val CHANNEL_NAME = "Authentication Errors"
        private const val NOTIFICATION_ID = 1450
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val authToken = intent.getStringExtra("auth")
        val expectedToken = ShizukuSettings.getAuthToken()

        if (authToken.isNullOrEmpty()) {
            notify(
                R.string.notification_auth_missing_title,
                R.string.notification_auth_missing_message
            )
        } else if (authToken != expectedToken) {
            notify(
                R.string.notification_auth_invalid_title,
                R.string.notification_auth_invalid_message
            )
        } else {
            ShellBinderRequestHandler.handleRequest(this, intent, true)
        }
        finish()
    }

    private fun notify(title: Int, message: Int) {
        val titleStr = getString(title)
        val messageStr = getString(message)

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
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
}
