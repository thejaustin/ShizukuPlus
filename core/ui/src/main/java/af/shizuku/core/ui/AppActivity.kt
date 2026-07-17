package af.shizuku.core.ui

import android.content.res.Resources.Theme
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
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

        // A snapshot of the outgoing UI, captured just before a themed recreate() and shown as a
        // full-screen overlay on the incoming instance, then crossfaded out. This masks the brief
        // black frame the window shows while recreate() tears down and rebuilds the surface, so a
        // theme/accent/night-mode change fades smoothly instead of flashing black. Static so it
        // survives across the recreate() (which keeps the process alive).
        @JvmStatic
        private var recreateSnapshot: Bitmap? = null
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

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // Runs after the subclass's setContentView but before the first frame is drawn, so the
        // crossfade overlay is in place before any black frame can show.
        val snapshot = recreateSnapshot ?: return
        recreateSnapshot = null
        try {
            val content = findViewById<ViewGroup>(android.R.id.content) ?: run { snapshot.recycle(); return }
            val overlay = ImageView(this).apply {
                setImageBitmap(snapshot)
                scaleType = ImageView.ScaleType.FIT_XY
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            content.addView(overlay)
            overlay.post {
                overlay.animate()
                    .alpha(0f)
                    .setDuration(220L)
                    .withEndAction {
                        (overlay.parent as? ViewGroup)?.removeView(overlay)
                        if (!snapshot.isRecycled) snapshot.recycle()
                    }
                    .start()
            }
        } catch (_: Throwable) {
            if (!snapshot.isRecycled) snapshot.recycle()
        }
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
     * avoids the stuck-black screen; capturing a snapshot to crossfade over the rebuild
     * (see [onPostCreate]) hides the brief black flash the surface swap otherwise shows.
     */
    fun recreateWithoutTransition() {
        suppressTransitionOnCreate = true
        window.setWindowAnimations(0)
        recreateSnapshot?.let { if (!it.isRecycled) it.recycle() }
        recreateSnapshot = captureWindowSnapshot()
        recreate()
    }

    private fun captureWindowSnapshot(): Bitmap? {
        return try {
            val view: View = window.decorView
            if (view.width <= 0 || view.height <= 0) return null
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            bitmap
        } catch (_: Throwable) {
            null
        }
    }
}
