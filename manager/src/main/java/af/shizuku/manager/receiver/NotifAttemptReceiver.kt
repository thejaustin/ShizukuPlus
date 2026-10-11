package af.shizuku.manager.receiver

import af.shizuku.manager.R
import af.shizuku.manager.adb.AdbAuthWait
import af.shizuku.manager.worker.AdbStartWorker
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** The start notification now uses [NotifAttemptActivity]; this serves notifications posted before. */
class NotifAttemptReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        attempt(context)
    }

    companion object {
        fun attempt(context: Context) {
            // The one authorisation dialog is already waiting; a tap cannot act, so say so instead
            // of silently doing nothing (enqueue() would skip without any visible response).
            if (AdbAuthWait.isWaiting()) {
                Toast.makeText(context.applicationContext, R.string.wadb_notification_awaiting_auth, Toast.LENGTH_SHORT).show()
                return
            }
            // "Attempt now" is an explicit user start: it may raise one new dialog even if a
            // previous one went unanswered.
            AdbAuthWait.clearUnanswered()
            AdbStartWorker.enqueue(context, explicit = true)
        }
    }
}
