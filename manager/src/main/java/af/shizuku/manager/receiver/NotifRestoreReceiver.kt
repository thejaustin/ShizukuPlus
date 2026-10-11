package af.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotifRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // Renders again from WorkManager's state, so work that is still queued gets its controls
        // back even in a process started just for this broadcast. A swiped notice stays dismissed.
        val pending = goAsync()
        ShizukuReceiverStarter.restoreNotification(
            context,
            swipedNotice = intent.getBooleanExtra(ShizukuReceiverStarter.EXTRA_SWIPED_NOTICE, false),
            noticeStamp = intent.getLongExtra(ShizukuReceiverStarter.EXTRA_NOTICE_STAMP, 0L),
        ) { pending.finish() }
    }
}
