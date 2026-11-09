package af.shizuku.manager.ktx

import af.shizuku.manager.ShizukuApplication
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.util.Pair
import android.util.TypedValue
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt

fun Activity.startWithSceneTransition(
    intent: Intent,
    sharedView: View? = null,
    transitionName: String? = null,
) {
    val bundle =
        if (sharedView != null && !transitionName.isNullOrEmpty()) {
            androidx.core.app.ActivityOptionsCompat
                .makeSceneTransitionAnimation(
                    this,
                    androidx.core.util.Pair(sharedView, transitionName),
                ).toBundle()
        } else if (sharedView != null && sharedView.width > 0 && sharedView.height > 0) {
            androidx.core.app.ActivityOptionsCompat
                .makeScaleUpAnimation(sharedView, 0, 0, sharedView.width, sharedView.height)
                .toBundle()
        } else {
            androidx.core.app.ActivityOptionsCompat
                .makeCustomAnimation(
                    this,
                    af.shizuku.manager.R.anim.activity_app_launch_enter,
                    af.shizuku.manager.R.anim.activity_app_launch_exit,
                ).toBundle()
        }
    startActivity(intent, bundle)
    if (android.os.Build.VERSION.SDK_INT >= 34) {
        overrideActivityTransition(
            Activity.OVERRIDE_TRANSITION_OPEN,
            af.shizuku.manager.R.anim.activity_app_launch_enter,
            af.shizuku.manager.R.anim.activity_app_launch_exit,
        )
        overrideActivityTransition(
            Activity.OVERRIDE_TRANSITION_CLOSE,
            af.shizuku.manager.R.anim.activity_app_close_enter,
            af.shizuku.manager.R.anim.activity_app_close_exit,
        )
    } else {
        @Suppress("DEPRECATION")
        overridePendingTransition(
            af.shizuku.manager.R.anim.activity_app_launch_enter,
            af.shizuku.manager.R.anim.activity_app_launch_exit,
        )
    }
}

fun Activity.startWithSceneTransition(intent: Intent) {
    startWithSceneTransition(intent, null, null)
}

fun Activity.overrideAppCloseTransition() {
    if (android.os.Build.VERSION.SDK_INT >= 34) {
        overrideActivityTransition(
            Activity.OVERRIDE_TRANSITION_CLOSE,
            af.shizuku.manager.R.anim.activity_app_close_enter,
            af.shizuku.manager.R.anim.activity_app_close_exit,
        )
    } else {
        @Suppress("DEPRECATION")
        overridePendingTransition(
            af.shizuku.manager.R.anim.activity_app_close_enter,
            af.shizuku.manager.R.anim.activity_app_close_exit,
        )
    }
}

@ColorInt
fun Context.themeColor(
    @AttrRes attr: Int,
): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}

/**
 * Resolves a `?attr/shapeAppearanceCorner*` theme attribute to its absolute corner size in
 * pixels, so Canvas-drawn (non-MaterialCardView) shapes can follow the Shape Style setting too.
 * Assumes the referenced ShapeAppearance uses an absolute cornerSize (true for every
 * ThemeOverlay.Shape.* variant in this app) — a percentage-based corner would need real bounds.
 */
fun Context.themeCornerSizePx(
    @AttrRes attr: Int,
): Float {
    val fallback = 28f * resources.displayMetrics.density
    val tv = TypedValue()
    if (!theme.resolveAttribute(attr, tv, true) || tv.resourceId == 0) {
        return fallback
    }
    return runCatching {
        val shapeAppearanceModel =
            com.google.android.material.shape.ShapeAppearanceModel
                .builder(this, tv.resourceId, 0)
                .build()
        shapeAppearanceModel.topLeftCornerSize.getCornerSize(android.graphics.RectF())
    }.getOrDefault(fallback)
}

val Context.application: ShizukuApplication
    get() {
        return applicationContext as ShizukuApplication
    }

/** The activity of type [T] behind this context; view and preference contexts are often wrappers. */
inline fun <reified T : Activity> Context.findActivity(): T? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is T) return c
        c = c.baseContext
    }
    return null
}
