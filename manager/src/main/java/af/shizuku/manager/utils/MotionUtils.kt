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
        val springAnimX = SpringAnimation(this, SpringAnimation.SCALE_X, 1.0f).apply {
            spring.stiffness = SpringForce.STIFFNESS_LOW
            spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
        }
        val springAnimY = SpringAnimation(this, SpringAnimation.SCALE_Y, 1.0f).apply {
            spring.stiffness = SpringForce.STIFFNESS_LOW
            spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
        }

        setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    HapticUtils.tap(v)
                    springAnimX.cancel()
                    springAnimY.cancel()
                    v.animate().scaleX(scale).scaleY(scale).setDuration(100).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    springAnimX.start()
                    springAnimY.start()
                }
            }
            false
        }
    }
}
