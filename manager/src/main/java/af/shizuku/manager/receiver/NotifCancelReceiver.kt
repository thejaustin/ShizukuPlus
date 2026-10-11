package af.shizuku.manager.receiver

import af.shizuku.manager.worker.AdbStartWorker
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

class NotifCancelReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // Cancelled on the thread every enqueue decision runs on, so a start being enqueued
        // concurrently is either cancelled too or comes after the cancel.
        val pending = goAsync()
        try {
            AdbStartWorker.cancel(context) { pending.finish() }
        } catch (e: Throwable) {
            // WorkManager failures (direct boot, a process created only for this receiver) are
            // handled where it is called; this only guarantees the broadcast is finished.
            Timber.tag("NotifCancelReceiver").w("cancel not scheduled: ${e.message}")
            pending.finish()
        }
    }
}
