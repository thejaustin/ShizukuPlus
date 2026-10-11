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
            // A connection already holds the one authorisation wait (the button reads "Ask
            // again"). adbd never reports a denied dialog, so offer its key again there: one new
            // dialog, once per wait and only after the user has had time to answer the first
            // (AdbAuthWait.reoffer). Otherwise say the dialog is awaited rather than silently
            // doing nothing: a toast, and, since some devices (a Samsung S24) suppress app toasts,
            // the notification posted again, whose text says when "Ask again" works or that the
            // dialog showing is the last. Silent and alert-once, so the re-post makes no sound.
            if (AdbAuthWait.isWaiting()) {
                if (!AdbAuthWait.reoffer()) {
                    Toast.makeText(context.applicationContext, R.string.wadb_notification_awaiting_auth, Toast.LENGTH_SHORT).show()
                    ShizukuReceiverStarter.refreshNotification(context, force = true)
                }
                return
            }
            // "Attempt now" is an explicit user start: it may raise one new dialog even if a
            // previous one went unanswered.
            AdbAuthWait.clearUnanswered()
            AdbStartWorker.enqueue(context, explicit = true)
        }
    }
}
