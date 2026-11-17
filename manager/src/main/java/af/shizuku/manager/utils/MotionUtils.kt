package af.shizuku.manager.utils

import af.shizuku.manager.R
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import androidx.annotation.AttrRes
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.R as MaterialR
import com.google.android.material.motion.MotionUtils as MaterialMotion

/**
 * The app's one source of M3 motion tokens: the theme's springs (Expressive, or Standard when
 * "Expressive animations" is off - see ThemeOverlay.Motion.Standard) and the M3 easing curves for
 * the few timed animations that remain.
 */
object MotionUtils {
    /** M3 emphasized decelerate - elements entering the screen. */
    val EMPHASIZED_DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    /** M3 emphasized accelerate - elements leaving the screen. */
    val EMPHASIZED_ACCELERATE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

    /** M3 standard - small utility movement that stays on screen. */
    val STANDARD = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** M3 standard accelerate - utility elements leaving. */
    val STANDARD_ACCELERATE = PathInterpolator(0.3f, 0f, 1f, 1f)

    /**
     * Resolves one of the theme's `motionSpring*` attrs, e.g. [MaterialR.attr.motionSpringFastSpatial].
     * Spatial springs may overshoot; use an Effects spring for alpha and colour. A context without
     * a Material3 theme gets a plain no-bounce spring instead of MDC's exception.
     */
    fun spring(
        context: Context,
        @AttrRes attr: Int,
    ): SpringForce =
        try {
            MaterialMotion.resolveThemeSpringForce(context, attr, 0)
        } catch (e: IllegalArgumentException) {
            SpringForce()
                .setStiffness(SpringForce.STIFFNESS_MEDIUM)
                .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
        }

    private class RunningSpring(
        val animation: SpringAnimation,
    ) {
        var endAction: (() -> Unit)? = null
    }

    /**
     * Springs [property] of this view to [target] on the theme spring [attr] (a Spatial spring for
     * position/scale/rotation, an Effects spring for alpha). A spring already running on the same
     * property is retargeted, keeping its velocity, and its [endAction] is replaced. Follows the
     * app's animation scale: 0 (Expressive animations off, or system animations off) jumps to
     * [target]; other scales lengthen or shorten the spring the way they scale a duration.
     */
    fun View.springTo(
        property: FloatPropertyCompat<View>,
        target: Float,
        @AttrRes attr: Int,
        endAction: (() -> Unit)? = null,
    ) {
        @Suppress("UNCHECKED_CAST")
        val springs =
            (getTag(R.id.tag_motion_springs) as? HashMap<FloatPropertyCompat<View>, RunningSpring>)
                ?: HashMap<FloatPropertyCompat<View>, RunningSpring>().also { setTag(R.id.tag_motion_springs, it) }
        val scale =
            af.shizuku.manager.ShizukuSettings
                .getAnimationDurationScale()
        if (scale <= 0f) {
            springs[property]?.let {
                it.endAction = null
                it.animation.cancel()
            }
            property.setValue(this, target)
            endAction?.invoke()
            return
        }
        val running =
            springs.getOrPut(property) {
                val animation = SpringAnimation(this, property).setSpring(SpringForce())
                RunningSpring(animation).also { entry ->
                    animation.addEndListener { _, canceled, _, _ ->
                        val action = entry.endAction
                        entry.endAction = null
                        if (!canceled) action?.invoke()
                    }
                }
            }
        val force = spring(context, attr)
        running.animation.spring
            .setStiffness(force.stiffness / (scale * scale))
            .setDampingRatio(force.dampingRatio)
        running.endAction = endAction
        running.animation.animateToFinalPosition(target)
    }

    @Suppress("UNCHECKED_CAST")
    private fun View.springs(): HashMap<FloatPropertyCompat<View>, RunningSpring>? =
        getTag(R.id.tag_motion_springs) as? HashMap<FloatPropertyCompat<View>, RunningSpring>

    /** True while any [springTo] spring is still moving this view. */
    fun View.isSpringing(): Boolean = springs()?.values?.any { it.animation.isRunning } == true

    /** Stops a spring started by [springTo] on [property] without running its end action. */
    fun View.cancelSpring(property: FloatPropertyCompat<View>) {
        springs()?.get(property)?.let {
            it.endAction = null
            it.animation.cancel()
        }
    }

    /** Stops every [springTo] spring on this view, e.g. before a recycled view is reused. */
    fun View.cancelAllSprings() {
        springs()?.values?.forEach {
            it.endAction = null
            it.animation.cancel()
        }
    }

    /**
     * Applies a spring-based scale down/up effect on touch.
     */
    fun View.applySpringTouch(scale: Float = 0.97f) {
        // Uses the view's shared springTo springs, not private ones: a Home card that's picked up
        // is lifted with springTo on the same properties, and two springs writing SCALE_X every
        // frame made the held card judder. A lifted view (tag_drag_lifted) is left to the drag.
        val press: (Float) -> Unit = { target ->
            springTo(SpringAnimation.SCALE_X, target, MaterialR.attr.motionSpringFastSpatial)
            springTo(SpringAnimation.SCALE_Y, target, MaterialR.attr.motionSpringFastSpatial)
        }
        setOnTouchListener { v, event ->
            // Unlike every other animation/haptic call site in the app, this touch feedback
            // used to fire unconditionally on every card tap, ignoring the user's Expressive
            // Animations toggle.
            if (!af.shizuku.manager.ShizukuSettings
                    .isExpressiveAnimationsEnabled() ||
                v.getTag(R.id.tag_drag_lifted) == true
            ) {
                return@setOnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> press(scale)
                MotionEvent.ACTION_MOVE -> {
                    val isInside = event.x in 0f..v.width.toFloat() && event.y in 0f..v.height.toFloat()
                    if (!isInside) press(1.0f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> press(1.0f)
            }
            false
        }
    }
}
