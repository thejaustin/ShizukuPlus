package af.shizuku.core.ui

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.transition.platform.MaterialSharedAxis
import rikka.core.ktx.unsafeLazy
import timber.log.Timber

abstract class AppBarActivity : AppActivity() {

    /** Axis for window enter/exit transitions. Z = forward/back (root→detail). X = lateral (sibling screens). */
    protected open val transitionAxis: Int = MaterialSharedAxis.Z

    protected val rootView: ViewGroup by unsafeLazy {
        findViewById<View>(R.id.coordinator_root) as? ViewGroup
            ?: throw IllegalStateException("rootView not found - make sure layout contains coordinator_root")
    }

    protected var toolbarContainer: AppBarLayout? = null
    protected var toolbar: Toolbar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Same recreate()-vs-content-transition hazard as AppActivity's Explode() transitions
        // (see its companion object comment) - must honor the same suppression flag or a
        // recreateWithoutTransition() call from any AppBarActivity subclass gets a black
        // screen stuck behind these MaterialSharedAxis transitions instead.
        if (!suppressTransitionOnCreate) {
            val axis = transitionAxis
            window.enterTransition = MaterialSharedAxis(axis, true)
            window.exitTransition = MaterialSharedAxis(axis, false)
            window.reenterTransition = MaterialSharedAxis(axis, false)
            window.returnTransition = MaterialSharedAxis(axis, true)
        } else {
            window.enterTransition = null
            window.exitTransition = null
            window.reenterTransition = null
            window.returnTransition = null
        }

        super.onCreate(savedInstanceState)
        super.setContentView(getLayoutId())
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        try {
            val container = findViewById<View>(R.id.toolbar_container) as? AppBarLayout
            val bar = findViewById<View>(R.id.toolbar) as? Toolbar

            if (container != null && bar != null) {
                toolbarContainer = container
                toolbar = bar
                setSupportActionBar(toolbar)

                androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
                    val bars = insets.getInsets(
                        androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                        androidx.core.view.WindowInsetsCompat.Type.displayCutout()
                    )
                    v.setPadding(bars.left, bars.top, bars.right, 0)
                    insets
                }

                // When blur is enabled the window already has setBackgroundBlurRadius applied
                // (AppActivity.onCreate). The AppBar's opaque colorBackground blocks it. On API
                // 31+ make it semi-transparent so the frosted-glass effect is visible (#449).
                val prefs = createDeviceProtectedStorageContext()
                    .getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                if (prefs.getBoolean("blur_ui_enabled", false) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    container.background?.mutate()?.alpha = 230 // ~90% opacity
                }
            } else {
                Timber.tag("AppBarActivity").w("Toolbar or container not found in layout.")
            }
        } catch (e: Exception) {
            Timber.tag("AppBarActivity").w(e, "Failed to initialize toolbar")
        }
    }

    @LayoutRes
    open fun getLayoutId(): Int {
        return R.layout.appbar_activity
    }

    override fun setContentView(layoutResID: Int) {
        val view = layoutInflater.inflate(layoutResID, rootView, false)
        setContentView(view)
    }

    override fun setContentView(view: View?) {
        setContentView(view, CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {
        val p = if (params is CoordinatorLayout.LayoutParams) {
            params
        } else {
            CoordinatorLayout.LayoutParams(params ?: ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        if (p.behavior == null) {
            p.behavior = AppBarLayout.ScrollingViewBehavior()
        }

        rootView.addView(view, p)
    }

}

abstract class AppBarFragmentActivity : AppBarActivity() {

    abstract fun createFragment(): Fragment

    override fun getLayoutId(): Int = R.layout.appbar_fragment_activity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, createFragment())
                .commit()
        }
    }

}
