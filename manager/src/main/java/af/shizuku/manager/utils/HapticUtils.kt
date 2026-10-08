package af.shizuku.manager.utils

import af.shizuku.manager.ShizukuSettings
import android.os.Build
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
    //
    // The dedicated haptic-feedback setting is checked once here so every call
    // site is governed by a single flag, instead of ad-hoc per-call-site checks
    // of unrelated toggles (e.g. expressive animations).
    private inline fun safeHaptic(
        view: View,
        constant: Int,
    ) {
        if (!ShizukuSettings.isHapticFeedbackEnabled()) return
        try {
            view.performHapticFeedback(constant)
        } catch (_: SecurityException) {
            // Swallow - see comment above.
        }
    }

    /**
     * Standard click feedback (vibration)
     */
    fun tap(view: View) {
        safeHaptic(view, HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /**
     * "Impact" feedback for success actions
     */
    fun success(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            safeHaptic(view, HapticFeedbackConstants.CONFIRM)
        } else {
            safeHaptic(view, HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /**
     * "Impact" feedback for error/warning actions
     */
    fun error(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            safeHaptic(view, HapticFeedbackConstants.REJECT)
        } else {
            safeHaptic(view, HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /**
     * Subtle "tick" feedback for scrolling or minor increments
     */
    fun tick(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            safeHaptic(view, HapticFeedbackConstants.CLOCK_TICK)
        } else {
            safeHaptic(view, HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    /**
     * Feedback for the start of a gesture (e.g. drag start)
     */
    fun gestureStart(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            safeHaptic(view, HapticFeedbackConstants.GESTURE_START)
        } else {
            safeHaptic(view, HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /**
     * Feedback for toggling a switch or feature ON
     */
    fun toggleOn(view: View) {
        if (Build.VERSION.SDK_INT >= 34) {
            safeHaptic(view, 21) // HapticFeedbackConstants.TOGGLE_ON
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            safeHaptic(view, HapticFeedbackConstants.CONFIRM)
        } else {
            safeHaptic(view, HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    /**
     * Feedback for toggling a switch or feature OFF
     */
    fun toggleOff(view: View) {
        if (Build.VERSION.SDK_INT >= 34) {
            safeHaptic(view, 22) // HapticFeedbackConstants.TOGGLE_OFF
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            safeHaptic(view, HapticFeedbackConstants.CLOCK_TICK)
        } else {
            safeHaptic(view, HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    /**
     * Subtle tactile tick for sliding segmented filters, chips, or detents
     */
    fun segmentTick(view: View) {
        if (Build.VERSION.SDK_INT >= 34) {
            safeHaptic(view, 26) // HapticFeedbackConstants.SEGMENT_TICK
        } else {
            tick(view)
        }
    }

    /**
     * Feedback for reaching a threshold during a gesture
     */
    fun gestureThreshold(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            safeHaptic(view, HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
        } else {
            tick(view)
        }
    }

    /**
     * Feedback for the end of a gesture (e.g. drag drop)
     */
    fun gestureEnd(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            safeHaptic(view, HapticFeedbackConstants.GESTURE_END)
        } else {
            safeHaptic(view, HapticFeedbackConstants.LONG_PRESS)
        }
    }
}
