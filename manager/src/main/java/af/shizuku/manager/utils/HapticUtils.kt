package af.shizuku.manager.utils

import af.shizuku.manager.ShizukuSettings
import android.content.Context
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Utility for providing "expressive" haptic feedback,
 * common in modern Material 3 Enhanced (M3E) applications.
 */
object HapticUtils {
    // Some OEM skins (confirmed on HyperOS/MIUI - #SHIZUKUPLUS-8E) route
    // View.performHapticFeedback through their own vibrator stack instead of the
    // normally VIBRATE-exempt system haptic path, and throw a SecurityException
    // if the VIBRATE permission isn't held. Haptics are decorative, never worth
    // crashing over, so every call goes through this guard.
    private fun safeHaptic(
        view: View,
        constant: Int,
        fallbackEffect: Int? = null,
    ) {
        if (!ShizukuSettings.isHapticFeedbackEnabled()) return

        // Respect user preference: only ignore view setting if "Always On" is selected
        val flags =
            if (ShizukuSettings.isFollowSystemHapticsEnabled()) {
                0
            } else {
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            }

        val performed =
            try {
                view.performHapticFeedback(constant, flags)
            } catch (_: SecurityException) {
                false
            }

        if (!performed && fallbackEffect != null) {
            performVibration(view.context, fallbackEffect)
        }
    }

    private fun performVibration(
        context: Context,
        effectId: Int,
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val effect = VibrationEffect.createPredefined(effectId)
                vm?.vibrate(CombinedVibration.createParallel(effect))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createPredefined(effectId))
            }
        } catch (_: Throwable) {
            // Best effort only
        }
    }

    /**
     * Standard click feedback (vibration)
     */
    fun tap(view: View) {
        safeHaptic(view, HapticFeedbackConstants.KEYBOARD_TAP, VibrationEffect.EFFECT_CLICK)
    }

    /**
     * "Impact" feedback for success actions
     */
    fun success(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            safeHaptic(view, HapticFeedbackConstants.CONFIRM, VibrationEffect.EFFECT_CLICK)
        } else {
            safeHaptic(view, HapticFeedbackConstants.LONG_PRESS, VibrationEffect.EFFECT_HEAVY_CLICK)
        }
    }

    /**
     * "Impact" feedback for error/warning actions
     */
    fun error(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            safeHaptic(view, HapticFeedbackConstants.REJECT, VibrationEffect.EFFECT_DOUBLE_CLICK)
        } else {
            safeHaptic(view, HapticFeedbackConstants.LONG_PRESS, VibrationEffect.EFFECT_HEAVY_CLICK)
        }
    }

    /**
     * Subtle "tick" feedback for scrolling or minor increments
     */
    fun tick(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            safeHaptic(view, HapticFeedbackConstants.CLOCK_TICK, VibrationEffect.EFFECT_TICK)
        } else {
            safeHaptic(view, HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    /**
     * Feedback for the start of a gesture (e.g. drag start)
     */
    fun gestureStart(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            safeHaptic(view, HapticFeedbackConstants.GESTURE_START, VibrationEffect.EFFECT_CLICK)
        } else {
            safeHaptic(view, HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /**
     * Feedback for toggling a switch or feature ON
     */
    fun toggleOn(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            safeHaptic(view, HapticFeedbackConstants.TOGGLE_ON, VibrationEffect.EFFECT_CLICK)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            safeHaptic(view, HapticFeedbackConstants.CONFIRM, VibrationEffect.EFFECT_CLICK)
        } else {
            safeHaptic(view, HapticFeedbackConstants.KEYBOARD_TAP, VibrationEffect.EFFECT_CLICK)
        }
    }

    /**
     * Feedback for toggling a switch or feature OFF
     */
    fun toggleOff(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            safeHaptic(view, HapticFeedbackConstants.TOGGLE_OFF, VibrationEffect.EFFECT_TICK)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            safeHaptic(view, HapticFeedbackConstants.CLOCK_TICK, VibrationEffect.EFFECT_TICK)
        } else {
            safeHaptic(view, HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    /**
     * Subtle tactile tick for sliding segmented filters, chips, or detents
     */
    fun segmentTick(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            safeHaptic(view, HapticFeedbackConstants.SEGMENT_TICK, VibrationEffect.EFFECT_TICK)
        } else {
            tick(view)
        }
    }

    /**
     * Feedback for reaching a threshold during a gesture
     */
    fun gestureThreshold(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            safeHaptic(view, HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE, VibrationEffect.EFFECT_HEAVY_CLICK)
        } else {
            tick(view)
        }
    }

    /** Feedback for backing out past a threshold during a gesture (the undo of [gestureThreshold]). */
    fun gestureThresholdRelease(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            safeHaptic(view, HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE, VibrationEffect.EFFECT_TICK)
        } else {
            tick(view)
        }
    }

    /** Feedback for picking an item up to drag it. */
    fun dragStart(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            safeHaptic(view, HapticFeedbackConstants.DRAG_START, VibrationEffect.EFFECT_HEAVY_CLICK)
        } else {
            gestureStart(view)
        }
    }

    /**
     * Feedback for the end of a gesture (e.g. drag drop)
     */
    fun gestureEnd(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            safeHaptic(view, HapticFeedbackConstants.GESTURE_END, VibrationEffect.EFFECT_CLICK)
        } else {
            safeHaptic(view, HapticFeedbackConstants.LONG_PRESS)
        }
    }
}
