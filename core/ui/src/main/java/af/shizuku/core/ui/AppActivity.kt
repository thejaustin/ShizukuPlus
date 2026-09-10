package af.shizuku.core.ui

import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.Resources.Theme
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window
import android.view.animation.LinearInterpolator
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import rikka.material.app.LocaleDelegate
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

    // #429: MaterialActivity (superclass) forces this activity's configuration locale to
    // LocaleDelegate.defaultLocale in ITS OWN attachBaseContext, called via super below - so
    // without this sync, a locale set through AppCompatDelegate.setApplicationLocales() (whether
    // from our own settings picker or Android 13+'s system Settings > App Info > Language screen)
    // would get silently overridden back to the legacy stored value on every single activity
    // creation. Only takes over once ShizukuApplication's one-time migration has run (read
    // directly from shared prefs, to avoid a cross-module dependency on manager's
    // ShizukuSettings - but from the SAME device-protected-storage "settings" file
    // ShizukuSettings.getPreferences() actually uses (createDeviceProtectedStorageContext(),
    // filename "settings"), not the plain default-storage "${packageName}_preferences" file;
    // those are two different files, and reading the wrong one would make this check always
    // see false. Key name must match ShizukuSettings.Keys.KEY_MIGRATED_APPCOMPAT_LOCALES) -
    // before the flag is set, AppCompatDelegate's locale list is meaningless/unset and
    // LocaleDelegate must keep using whatever ShizukuApplication.onCreate() seeded it with from
    // the legacy pref.
    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.createDeviceProtectedStorageContext()
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("migrated_appcompat_locales", false)) {
            val appLocales = AppCompatDelegate.getApplicationLocales()
            LocaleDelegate.defaultLocale = if (!appLocales.isEmpty) {
                appLocales[0] ?: LocaleDelegate.systemLocale
            } else {
                // Empty list = "follow system" (explicitly chosen, or never set) - not "leave
                // whatever LocaleDelegate currently holds", or picking "System" in our own
                // picker would silently do nothing once this sync is live.
                LocaleDelegate.systemLocale
            }
        }
        super.attachBaseContext(newBase)
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
        // Suppress the NEW window's enter animation/transition when this is a theme-change
        // recreate(). recreateWithoutTransition() already called window.setWindowAnimations(0)
        // on the OLD window to kill its exit animation, but the new window inherits the theme's
        // windowAnimationStyle — which on some API levels still plays an enter animation and
        // leaves the window black until the user navigates away. Suppressing it here on the
        // new window ensures a clean, animation-free replacement.
        if (suppressTransitionOnCreate) {
            window.enterTransition = null
            window.exitTransition = null
            window.reenterTransition = null
            window.returnTransition = null
            window.setWindowAnimations(0)
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        suppressTransitionOnCreate = false
        // Read directly from shared prefs to avoid a cross-module dependency on manager's
        // ShizukuSettings. Keys must match ShizukuSettings.Keys constants. Bug fix: this used to
        // read the plain default-storage "${packageName}_preferences" file, but
        // ShizukuSettings.getPreferences() actually stores these in the device-protected-storage
        // "settings" file (see attachBaseContext() above) - the two never contained the same
        // data, so this always silently read the fallback default regardless of what the user
        // actually set (edge-to-edge could never be turned off, and Blur UI could never be turned
        // on, since neither key was ever actually found in the file being read).
        val prefs = createDeviceProtectedStorageContext().getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        // Android 15+ (API 35) enforces edge-to-edge for targetSdk-35 apps regardless of the user
        // preference — setDecorFitsSystemWindows(true) is silently ignored by the system. Forcing
        // enableEdgeToEdge() here ensures every activity window is configured consistently before
        // the Explode enter/exit transitions run; without it the source and destination windows
        // have mismatched decor-fits-windows state during the animation, which causes a crash on
        // Android 16 (#483). On older Android the user's setting is still honoured.
        val edgeToEdge = prefs.getBoolean("edge_to_edge_enabled", true)
                || Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
        if (edgeToEdge) {
            enableEdgeToEdge()
        }
        if (prefs.getBoolean("blur_ui_enabled", false) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setBackgroundBlurRadius(30)
        }
        super.onCreate(savedInstanceState)
        // Theme-change flash fix: immediately replace the new theme's android:windowBackground with
        // the captured screenshot. The window background is drawn from the very first pixel before
        // any view content, so this eliminates the 1-2 frame white/black flash that appears between
        // the old activity tearing down and onPostCreate()'s decor-overlay being added. Without this,
        // the new theme's background color flickers visible on preference-screen theme changes.
        // The window background is restored to the real theme drawable in onPostCreate()'s animation
        // end callback, after which it no longer matters (view content covers it).
        recreateSnapshot?.let { s ->
            if (!s.isRecycled) {
                window.setBackgroundDrawable(android.graphics.drawable.BitmapDrawable(resources, s))
            }
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // Runs after the subclass's setContentView/setContent but before the first frame is drawn,
        // so the crossfade overlay is in place before any black frame can show.
        val snapshot = recreateSnapshot ?: return
        recreateSnapshot = null

        val decorView = window.decorView
        val drawable = BitmapDrawable(resources, snapshot)
        try {
            // Add to the decor view's overlay, which paints above ALL view content including
            // hardware-rendered (RenderNode-backed) Compose layers. Adding as a sibling inside
            // android.R.id.content doesn't work for Compose activities — Compose's hardware
            // rendering composites above software-canvas siblings regardless of z-order.
            //
            // Use snapshot dimensions rather than decorView.width/height: onPostCreate() fires
            // before the first Choreographer layout pass (which doesn't run until after onResume),
            // so decorView.width/height are both 0 on the freshly-created Activity instance.
            // The bitmap was captured with the correct window dimensions, so reusing them here
            // guarantees a full-screen cover regardless of when layout completes.
            drawable.setBounds(0, 0, snapshot.width, snapshot.height)
            decorView.overlay.add(drawable)
            // ObjectAnimator instead of View.animate(): we're fading a Drawable, not a View.
            // Start animator immediately so it is synchronized with the first frame rather than
            // waiting for a post() looper cycle which causes a 1-frame black/white screen flicker.
            ObjectAnimator.ofInt(drawable, "alpha", 255, 0).apply {
                duration = 220L
                interpolator = LinearInterpolator()
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        decorView.overlay.remove(drawable)
                        val ta = this@AppActivity.obtainStyledAttributes(
                            intArrayOf(android.R.attr.windowBackground)
                        )
                        window.setBackgroundDrawable(ta.getDrawable(0))
                        ta.recycle()
                        if (!snapshot.isRecycled) snapshot.recycle()
                    }
                })
                start()
            }
        } catch (_: Throwable) {
            decorView.overlay.remove(drawable)
            val ta = this.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
            window.setBackgroundDrawable(ta.getDrawable(0))
            ta.recycle()
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
     *
     * On API 26+ the snapshot is captured via [PixelCopy] which properly captures
     * hardware-accelerated (RenderNode) content — Compose UI included. [recreate] is
     * deferred until the capture completes (typically < 1 frame / ~16ms).
     */
    fun recreateWithoutTransition() {
        window.setWindowAnimations(0)
        recreateSnapshot?.let { if (!it.isRecycled) it.recycle() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val view = window.decorView
            if (view.width > 0 && view.height > 0) {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    // PixelCopy.request() can throw synchronously (e.g. when the window surface
                    // is invalid during an in-progress transition). Catch and fall through to the
                    // sync path so recreate() still happens and suppressTransitionOnCreate stays
                    // consistent — a missing snapshot means a brief black flash, not a crash.
                    android.view.PixelCopy.request(window, bitmap, { result ->
                        val captured = if (result == android.view.PixelCopy.SUCCESS) bitmap else {
                            bitmap.recycle()
                            null
                        }
                        if (!isFinishing) {
                            recreateSnapshot = captured
                            // Set flag here rather than before request(): the ~1-frame async gap
                            // between the call site and this callback is a window where any other
                            // AppActivity.onCreate() could silently consume and clear the flag,
                            // leaving the actual recreated instance with transitions enabled.
                            suppressTransitionOnCreate = true
                            recreate()
                        } else {
                            captured?.recycle()
                        }
                    }, Handler(Looper.getMainLooper()))
                    return
                } catch (_: Exception) {
                    bitmap.recycle()
                    // fall through to synchronous recreate below
                }
            }
            // View not yet laid out or PixelCopy threw — skip snapshot, do a plain recreate.
        } else {
            recreateSnapshot = captureWindowSnapshot()
        }
        suppressTransitionOnCreate = true
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
