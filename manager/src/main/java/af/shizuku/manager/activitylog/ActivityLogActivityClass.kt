package af.shizuku.manager.activitylog

import af.shizuku.core.ui.AppBarFragmentActivity
import androidx.fragment.app.Fragment

class ActivityLogActivity : AppBarFragmentActivity() {
    override fun createFragment(): Fragment = ActivityLogFragment()
}
