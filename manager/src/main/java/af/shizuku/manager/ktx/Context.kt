package af.shizuku.manager.ktx

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Pair
import android.util.TypedValue
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import af.shizuku.manager.ShizukuApplication

fun Activity.startWithSceneTransition(intent: Intent, sharedView: View, transitionName: String) {
    val options = ActivityOptions.makeSceneTransitionAnimation(this, Pair.create(sharedView, transitionName))
    startActivity(intent, options.toBundle())
}

@ColorInt
fun Context.themeColor(@AttrRes attr: Int): Int {
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
fun Context.themeCornerSizePx(@AttrRes attr: Int): Float {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    val shapeAppearanceModel = com.google.android.material.shape.ShapeAppearanceModel
        .builder(this, 0, tv.resourceId)
        .build()
    return shapeAppearanceModel.topLeftCornerSize.getCornerSize(android.graphics.RectF())
}

val Context.application: ShizukuApplication
    get() {
        return applicationContext as ShizukuApplication
    }

fun Context.createDeviceProtectedStorageContextCompat(): Context {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        createDeviceProtectedStorageContext()
    } else {
        this
    }
}

fun Context.createDeviceProtectedStorageContextCompatWhenLocked(): Context {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && getSystemService(UserManager::class.java)?.isUserUnlocked != true) {
        createDeviceProtectedStorageContext()
    } else {
        this
    }
}
