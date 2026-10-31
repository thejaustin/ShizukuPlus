package af.shizuku.manager.receiver

import af.shizuku.manager.utils.ShizukuIssues
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The shared problem notification was swiped away; see [ShizukuIssues.onSharedDismissed]. */
class IssueDismissReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        ShizukuIssues.onSharedDismissed(intent.getStringExtra(EXTRA_KEY), intent.getLongExtra(EXTRA_NUMBER, -1L))
    }

    companion object {
        const val EXTRA_KEY = "key"
        const val EXTRA_NUMBER = "number"
    }
}
