package af.shizuku.manager.utils

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import com.google.android.material.R as MaterialR

/**
 * How an icon says "off" across the app (a switch row that's off, an app without access): it
 * shrinks a little and fades, and with Grayscale off icons (experimental) it also loses its
 * colour. Colour drains and returns with the same spring as the rest, so the icon previews the
 * state while it changes.
 *
 * The greyscale is a hardware layer with a saturation filter on the whole view (icon and its
 * shape behind it), so it never fights the icon's own tints; the layer is dropped again once the
 * icon is fully coloured.
 */
object OffStateIcon {
    const val OFF_SCALE = 0.9f
    const val OFF_ALPHA = 0.6f

    private val Saturation =
        object : FloatPropertyCompat<View>("saturation") {
            override fun getValue(view: View) = (view.getTag(R.id.tag_icon_saturation) as? Float) ?: 1f

            override fun setValue(
                view: View,
                value: Float,
            ) = applySaturation(view, value)
        }

    /** Sets the icon's on/off look, springing to it when [animate] (the setting's own animations decide the rest). */
    fun apply(
        icon: View,
        on: Boolean,
        animate: Boolean,
    ) {
        val scale = if (on) 1f else OFF_SCALE
        val alpha = if (on) 1f else OFF_ALPHA
        val saturation = if (on || !ShizukuSettings.isGrayscaleOffIconsEnabled()) 1f else 0f
        val target = Triple(scale, alpha, saturation)
        val sameTarget = icon.getTag(R.id.tag_icon_off_state) == target
        icon.setTag(R.id.tag_icon_off_state, target)
        with(MotionUtils) {
            // Flipping a switch animates the icon and then rebinds its row a frame later: a rebind
            // to the state already being animated to leaves the animation running.
            if (!animate && sameTarget && icon.hasRunningSprings()) return
            if (!animate) {
                // A spring still running from a recycled row's previous state would overwrite these.
                icon.cancelAllSprings()
                icon.scaleX = scale
                icon.scaleY = scale
                icon.alpha = alpha
                applySaturation(icon, saturation)
                return
            }
            icon.springTo(SpringAnimation.SCALE_X, scale, MaterialR.attr.motionSpringFastSpatial)
            icon.springTo(SpringAnimation.SCALE_Y, scale, MaterialR.attr.motionSpringFastSpatial)
            // Fades and colour use the effects spring: no overshoot past grey or full colour.
            icon.springTo(SpringAnimation.ALPHA, alpha, MaterialR.attr.motionSpringFastEffects)
            icon.springTo(Saturation, saturation, MaterialR.attr.motionSpringFastEffects)
        }
    }

    private fun applySaturation(
        view: View,
        value: Float,
    ) {
        val s = value.coerceIn(0f, 1f)
        view.setTag(R.id.tag_icon_saturation, s)
        if (s >= 0.999f) {
            if (view.layerType != View.LAYER_TYPE_NONE) view.setLayerType(View.LAYER_TYPE_NONE, null)
            return
        }
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(s) }) }
        if (view.layerType == View.LAYER_TYPE_HARDWARE) view.setLayerPaint(paint) else view.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
    }
}
