package af.shizuku.core.ui

import android.content.res.Resources.Theme
import android.os.Bundle
import android.view.Window
import androidx.activity.enableEdgeToEdge
import rikka.material.app.MaterialActivity

abstract class AppActivity : MaterialActivity() {

    companion object {
        // recreate() doesn't go through the normal Intent/ActivityOptions handshake that
        // drives a scene transition, so the incoming instance's enter transition never gets
        // played/completed and its content stays invisible - a black screen until the user
        // backs out. setWindowAnimations(0) in recreateWithoutTransition() only suppresses
        // the legacy window-animation style, not FEATURE_ACTIVITY_TRANSITIONS, so this flag is
        // the only thing that actually skips content transitions on relaunch. Protected (not
        // private) because AppBarActivity sets its own MaterialSharedAxis transitions and must
        // honor the same suppression - see AppBarActivity.onCreate().
        @JvmStatic
        protected var suppressTransitionOnCreate = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!suppressTransitionOnCreate) {
            try {
                window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
                window.enterTransition = android.transition.Explode()
                window.exitTransition = android.transition.Explode()
            } catch (_: Exception) {
            }
        }
        suppressTransitionOnCreate = false
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun computeUserThemeKey(): String {
        return ThemeDelegateManager.getDelegate().getThemeKey(this)
    }

    override fun onApplyUserThemeResource(theme: Theme, isDecorView: Boolean) {
        ThemeDelegateManager.getDelegate().onApplyUserThemeResource(this, theme, isDecorView)
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) {
            finish()
        }
        return true
    }

    /**
     * [recreate] tears down and rebuilds the window; combined with the Explode
     * enter/exit transitions requested in onCreate, the incoming instance's enter
     * transition never resolves (recreate() skips the ActivityOptions handshake that
     * normally drives it), leaving the screen black until the user backs out. Suppressing
     * the transitions on the next onCreate, plus disabling the legacy window animation,
     * avoids it.
     */
    fun recreateWithoutTransition() {
        suppressTransitionOnCreate = true
        window.setWindowAnimations(0)
        recreate()
    }
}
