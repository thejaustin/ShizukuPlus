package af.shizuku.manager.utils

import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

object MotionUtils {

    /**
     * Applies a spring-based scale down/up effect on touch.
     */
    fun View.applySpringTouch(scale: Float = 0.97f) {
        // Single spring per axis; animateToFinalPosition() composes smoothly mid-animation
        // (no ViewPropertyAnimator discontinuity when the finger lifts before the press-in completes).
        val springX = SpringAnimation(this, SpringAnimation.SCALE_X).apply {
            spring = SpringForce(1.0f).also {
                it.stiffness = SpringForce.STIFFNESS_MEDIUM
                it.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            }
        }
        val springY = SpringAnimation(this, SpringAnimation.SCALE_Y).apply {
            spring = SpringForce(1.0f).also {
                it.stiffness = SpringForce.STIFFNESS_MEDIUM
                it.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            }
        }

        setOnTouchListener { v, event ->
            // Unlike every other animation/haptic call site in the app, this touch feedback
            // used to fire unconditionally on every card tap, ignoring the user's Expressive
            // Animations toggle.
            if (!af.shizuku.manager.ShizukuSettings.isExpressiveAnimationsEnabled()) {
                return@setOnTouchListener false
            }
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v?.let { HapticUtils.tap(it) }
                    springX.animateToFinalPosition(scale)
                    springY.animateToFinalPosition(scale)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    springX.animateToFinalPosition(1.0f)
                    springY.animateToFinalPosition(1.0f)
                }
            }
            false
        }
    }
}
