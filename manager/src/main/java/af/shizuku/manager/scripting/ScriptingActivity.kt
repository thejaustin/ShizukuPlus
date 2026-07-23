package af.shizuku.manager.scripting

import androidx.fragment.app.Fragment
import af.shizuku.core.ui.AppBarFragmentActivity

class ScriptingActivity : AppBarFragmentActivity() {
    override fun createFragment(): Fragment = ScriptingFragment()
}
