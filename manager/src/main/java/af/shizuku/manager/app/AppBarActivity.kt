package af.shizuku.manager.app

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import androidx.annotation.RequiresApi
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.AppBarLayout
import af.shizuku.manager.R
import rikka.core.ktx.unsafeLazy
import timber.log.Timber

abstract class AppBarActivity : AppActivity() {

    protected val rootView: ViewGroup by unsafeLazy {
        findViewById<View>(R.id.coordinator_root) as? ViewGroup
            ?: throw IllegalStateException("rootView not found - make sure layout contains coordinator_root")
    }

    protected val toolbarContainer: AppBarLayout by unsafeLazy {
        findViewById<View>(R.id.toolbar_container) as? AppBarLayout
            ?: throw IllegalStateException("toolbarContainer not found - make sure layout contains toolbar_container. Check that your activity layout includes @layout/appbar or has an AppBarLayout with id toolbar_container")
    }

    protected val toolbar: Toolbar by unsafeLazy {
        findViewById<View>(R.id.toolbar) as? Toolbar
            ?: throw IllegalStateException("toolbar not found - make sure layout contains toolbar. Check that your activity layout includes @layout/appbar or has a Toolbar with id toolbar")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        super.setContentView(getLayoutId())

        // Ensure views are available before proceeding
        try {
            // Force lazy initialization to catch missing views early
            toolbarContainer
            toolbar
            
            setSupportActionBar(toolbar)

            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(toolbarContainer) { v, insets ->
                val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                v.setPadding(0, systemBars.top, 0, 0)
                insets
            }
        } catch (e: IllegalStateException) {
            Timber.tag("AppBarActivity").e(e, "Layout configuration error: ${e.message}")
            throw e
        } catch (e: Exception) {
            Timber.tag("AppBarActivity").e(e, "Failed to initialize toolbar")
            throw e
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
        
        // Ensure scrolling behavior is applied so content is placed below the AppBar
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
