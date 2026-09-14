package af.shizuku.manager.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import timber.log.Timber
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import af.shizuku.manager.utils.EnvironmentUtils

class NotifCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork("adb_start_worker")
        } catch (e: Throwable) {
            // WorkManager may throw NoSuchMethodError / NoSuchMethodException / LinkageError or IllegalStateException when
            // called from a BroadcastReceiver context before the app process is fully
            // initialized (e.g. direct boot, process re-creation for receiver only).
            Timber.tag("NotifCancelReceiver").w("WorkManager unavailable: ${e.message}")
        }
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(ShizukuReceiverStarter.NOTIFICATION_ID)
        } catch (e: Throwable) {
            Timber.tag("NotifCancelReceiver").w("Failed to cancel notification: ${e.message}")
        }
    }
}
