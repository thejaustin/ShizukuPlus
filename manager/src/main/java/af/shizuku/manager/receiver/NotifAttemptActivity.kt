package af.shizuku.manager.receiver

import android.app.Activity
import android.os.Bundle

/**
 * The start notification's "Attempt now". SystemUI collapses the shade when a notification action
 * starts an activity, and leaves it open for a broadcast; adbd's "Allow USB debugging?" dialog,
 * which this start raises, would otherwise open behind the shade and time out unseen.
 */
class NotifAttemptActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) NotifAttemptReceiver.attempt(this)
        // Theme.NoDisplay requires finishing before onResume.
        finish()
    }
}
