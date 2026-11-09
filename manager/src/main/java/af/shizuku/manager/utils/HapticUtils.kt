package af.shizuku.manager.utils

import af.shizuku.manager.ShizukuSettings
import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Advanced expressive haptics engine optimized for flagship Linear Resonant Actuators (LRAs)
 * such as Samsung Galaxy S26 Ultra / S25 / S24 (One UI Haptics) and Google Pixel devices,
 * with multi-tiered graceful fallbacks across all Android versions.
 *
 * Capabilities utilized:
 * 1. Hardware Composed Primitives (API 30+ / 34+):
 *    [VibrationEffect.Composition] using [VibrationEffect.Composition.PRIMITIVE_CLICK],
 *    [VibrationEffect.Composition.PRIMITIVE_TICK], [VibrationEffect.Composition.PRIMITIVE_LOW_TICK],
 *    [VibrationEffect.Composition.PRIMITIVE_QUICK_RISE], [VibrationEffect.Composition.PRIMITIVE_QUICK_FALL],
 *    and [VibrationEffect.Composition.PRIMITIVE_THUD].
 * 2. Samsung One UI & Android Touch Attributes (API 33+):
 *    [VibrationAttributes.USAGE_TOUCH] routes vibration through the system's calibrated
 *    "Touch interaction" vibration intensity slider.
 * 3. Granular Amplitude Envelope Waveforms (API 26+):
 *    Sharp, non-mushy micro-pulses (8-24ms) for devices with amplitude control when
 *    certain primitives are unsupported.
 * 4. Predefined & View Fallbacks:
 *    [VibrationEffect.createPredefined] and [View.performHapticFeedback].
 */
object HapticUtils {
    enum class Pattern {
        TAP,
        SUCCESS,
        ERROR,
        TICK,
        GESTURE_START,
        GESTURE_END,
        TOGGLE_ON,
        TOGGLE_OFF,
        SEGMENT_TICK,
        GESTURE_THRESHOLD,
        GESTURE_THRESHOLD_RELEASE,
        DRAG_START,
        CARD_MORPH,
    }

    private fun getVibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /**
     * Dispatches vibration through [VibrationAttributes.USAGE_TOUCH] so Samsung One UI
     * and system touch sliders scale the amplitude according to user preferences.
     */
    private fun vibrate(
        context: Context,
        effect: VibrationEffect,
    ) {
        val vibrator = getVibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val attrs =
                    VibrationAttributes
                        .Builder()
                        .setUsage(VibrationAttributes.USAGE_TOUCH)
                        .build()
                vibrator.vibrate(effect, attrs)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(effect)
            }
        } catch (_: Throwable) {
            // Best effort only
        }
    }

    /**
     * Tier 1: Hardware Composed Primitives for S26 Ultra and modern flagships.
     */
    private fun createComposedEffect(
        vibrator: Vibrator,
        pattern: Pattern,
    ): VibrationEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        return when (pattern) {
            Pattern.TOGGLE_ON -> {
                if (vibrator.areAllPrimitivesSupported(
                        VibrationEffect.Composition.PRIMITIVE_TICK,
                        VibrationEffect.Composition.PRIMITIVE_CLICK,
                    )
                ) {
                    VibrationEffect
                        .startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.45f)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.90f, 12)
                        .compose()
                } else {
                    null
                }
            }
            Pattern.TOGGLE_OFF -> {
                if (vibrator.areAllPrimitivesSupported(
                        VibrationEffect.Composition.PRIMITIVE_CLICK,
                        VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
                    )
                ) {
                    VibrationEffect
                        .startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.60f)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.45f, 10)
                        .compose()
                } else if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
                    VibrationEffect
                        .startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.55f)
                        .compose()
                } else {
                    null
                }
            }
            Pattern.GESTURE_THRESHOLD -> {
                if (vibrator.areAllPrimitivesSupported(
                        VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
                        VibrationEffect.Composition.PRIMITIVE_CLICK,
                    )
                ) {
                    VibrationEffect
                        .startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.65f)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.95f, 8)
                        .compose()
                } else if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                    VibrationEffect
                        .startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.90f)
                        .compose()
                } else {
                    null
                }
            }
            Pattern.GESTURE_THRESHOLD_RELEASE -> {
                if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL)) {
                    VibrationEffect
                        .startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.50f)
                        .compose()
                } else if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
                    VibrationEffect
                        .startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.40f)
                        .compose()
                } else {
                    null
                }
            }
            Pattern.SUCCESS -> {
                if (vibrator.areAllPrimitivesSupported(
                        VibrationEffect.Composition.PRIMITIVE_CLICK,
                        VibrationEffect.Composition.PRIMITIVE_THUD,
                    )
                ) {
                    VibrationEffect
                        .startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.75f)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f, 35)
                        .compose()
                } else if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                    VibrationEffect
                        .startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.80f)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 30)
                        .compose()
                } else {
                    null
                }
            }
            Pattern.ERROR -> {
                if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD)) {
                    VibrationEffect
                        .startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.70f)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.95f, 40)
                        .compose()
                } else {
                    null
                }
            }
            Pattern.SEGMENT_TICK -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
                ) {
                    VibrationEffect
                        .startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.45f)
                        .compose()
                } else if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
                    VibrationEffect
                        .startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.40f)
                        .compose()
                } else {
                    null
                }
            }
            Pattern.DRAG_START -> {
                if (vibrator.areAllPrimitivesSupported(
                        VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
                        VibrationEffect.Composition.PRIMITIVE_CLICK,
                    )
                ) {
                    VibrationEffect
                        .startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.55f)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.80f, 6)
                        .compose()
                } else {
                    null
                }
            }
            Pattern.GESTURE_END -> {
                if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD)) {
                    VibrationEffect
                        .startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.75f)
                        .compose()
                } else {
                    null
                }
            }
            Pattern.TAP -> {
                if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                    VibrationEffect
                        .startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.75f)
                        .compose()
                } else {
                    null
                }
            }
            Pattern.TICK -> {
                if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
                    VibrationEffect
                        .startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.45f)
                        .compose()
                } else {
                    null
                }
            }
            Pattern.CARD_MORPH -> {
                if (vibrator.areAllPrimitivesSupported(
                        VibrationEffect.Composition.PRIMITIVE_SLOW_RISE,
                        VibrationEffect.Composition.PRIMITIVE_CLICK,
                    )
                ) {
                    VibrationEffect
                        .startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 0.40f)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.70f, 15)
                        .compose()
                } else {
                    null
                }
            }
            else -> null
        }
    }

    /**
     * Tier 2: Custom Amplitude Envelope Waveforms for precise micro-pulses on linear motors.
     */
    private fun createWaveformEffect(
        vibrator: Vibrator,
        pattern: Pattern,
    ): VibrationEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !vibrator.hasAmplitudeControl()) return null

        return when (pattern) {
            Pattern.TOGGLE_ON -> {
                val timings = longArrayOf(0, 10, 15, 12)
                val amplitudes = intArrayOf(0, 80, 0, 220)
                VibrationEffect.createWaveform(timings, amplitudes, -1)
            }
            Pattern.TOGGLE_OFF -> {
                val timings = longArrayOf(0, 14, 10, 8)
                val amplitudes = intArrayOf(0, 160, 0, 70)
                VibrationEffect.createWaveform(timings, amplitudes, -1)
            }
            Pattern.GESTURE_THRESHOLD -> {
                val timings = longArrayOf(0, 12, 10, 16)
                val amplitudes = intArrayOf(0, 100, 0, 240)
                VibrationEffect.createWaveform(timings, amplitudes, -1)
            }
            Pattern.GESTURE_THRESHOLD_RELEASE -> {
                val timings = longArrayOf(0, 10)
                val amplitudes = intArrayOf(0, 90)
                VibrationEffect.createWaveform(timings, amplitudes, -1)
            }
            Pattern.SUCCESS -> {
                val timings = longArrayOf(0, 14, 30, 22)
                val amplitudes = intArrayOf(0, 190, 0, 240)
                VibrationEffect.createWaveform(timings, amplitudes, -1)
            }
            Pattern.ERROR -> {
                val timings = longArrayOf(0, 18, 35, 24)
                val amplitudes = intArrayOf(0, 200, 0, 255)
                VibrationEffect.createWaveform(timings, amplitudes, -1)
            }
            Pattern.SEGMENT_TICK, Pattern.TICK -> {
                val timings = longArrayOf(0, 8)
                val amplitudes = intArrayOf(0, 85)
                VibrationEffect.createWaveform(timings, amplitudes, -1)
            }
            Pattern.TAP -> {
                val timings = longArrayOf(0, 12)
                val amplitudes = intArrayOf(0, 180)
                VibrationEffect.createWaveform(timings, amplitudes, -1)
            }
            Pattern.CARD_MORPH -> {
                val timings = longArrayOf(0, 20, 15, 12)
                val amplitudes = intArrayOf(0, 60, 0, 180)
                VibrationEffect.createWaveform(timings, amplitudes, -1)
            }
            else -> null
        }
    }

    /**
     * Executes advanced multi-tiered vibration for a given pattern.
     */
    fun performAdvancedVibration(
        context: Context,
        pattern: Pattern,
        fallbackEffectId: Int? = null,
    ) {
        if (!ShizukuSettings.isHapticFeedbackEnabled()) return
        val vibrator = getVibrator(context) ?: return
        if (!vibrator.hasVibrator()) return

        // 1. Try Hardware Composed Primitives
        val composed = createComposedEffect(vibrator, pattern)
        if (composed != null) {
            vibrate(context, composed)
            return
        }

        // 2. Try Envelope Waveform
        val waveform = createWaveformEffect(vibrator, pattern)
        if (waveform != null) {
            vibrate(context, waveform)
            return
        }

        // 3. Try Predefined Effect
        if (fallbackEffectId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val predefined = VibrationEffect.createPredefined(fallbackEffectId)
                vibrate(context, predefined)
                return
            } catch (_: Throwable) {
                // Fall through
            }
        }

        // 4. Fallback One-Shot
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                vibrate(context, VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (_: Throwable) {
                // Best effort
            }
        }
    }

    private fun safeHaptic(
        view: View,
        constant: Int,
        pattern: Pattern,
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

        if (!performed) {
            performAdvancedVibration(view.context, pattern, fallbackEffect)
        }
    }

    // --- Public API with dual View and Context signatures ---

    fun tap(view: View) = safeHaptic(view, HapticFeedbackConstants.KEYBOARD_TAP, Pattern.TAP, VibrationEffect.EFFECT_CLICK)

    fun tap(context: Context) = performAdvancedVibration(context, Pattern.TAP, VibrationEffect.EFFECT_CLICK)

    fun success(view: View) {
        val constant =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }
        safeHaptic(view, constant, Pattern.SUCCESS, VibrationEffect.EFFECT_CLICK)
    }

    fun success(context: Context) = performAdvancedVibration(context, Pattern.SUCCESS, VibrationEffect.EFFECT_CLICK)

    fun error(view: View) {
        val constant =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.REJECT
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }
        safeHaptic(view, constant, Pattern.ERROR, VibrationEffect.EFFECT_DOUBLE_CLICK)
    }

    fun error(context: Context) = performAdvancedVibration(context, Pattern.ERROR, VibrationEffect.EFFECT_DOUBLE_CLICK)

    fun tick(view: View) {
        val constant =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                HapticFeedbackConstants.CLOCK_TICK
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            }
        safeHaptic(view, constant, Pattern.TICK, VibrationEffect.EFFECT_TICK)
    }

    fun tick(context: Context) = performAdvancedVibration(context, Pattern.TICK, VibrationEffect.EFFECT_TICK)

    fun toggleOn(view: View) {
        val constant =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                HapticFeedbackConstants.TOGGLE_ON
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.KEYBOARD_TAP
            }
        safeHaptic(view, constant, Pattern.TOGGLE_ON, VibrationEffect.EFFECT_CLICK)
    }

    fun toggleOn(context: Context) = performAdvancedVibration(context, Pattern.TOGGLE_ON, VibrationEffect.EFFECT_CLICK)

    fun toggleOff(view: View) {
        val constant =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                HapticFeedbackConstants.TOGGLE_OFF
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                HapticFeedbackConstants.CLOCK_TICK
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            }
        safeHaptic(view, constant, Pattern.TOGGLE_OFF, VibrationEffect.EFFECT_TICK)
    }

    fun toggleOff(context: Context) = performAdvancedVibration(context, Pattern.TOGGLE_OFF, VibrationEffect.EFFECT_TICK)

    fun segmentTick(view: View) {
        val constant =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                HapticFeedbackConstants.SEGMENT_TICK
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            }
        safeHaptic(view, constant, Pattern.SEGMENT_TICK, VibrationEffect.EFFECT_TICK)
    }

    fun segmentTick(context: Context) = performAdvancedVibration(context, Pattern.SEGMENT_TICK, VibrationEffect.EFFECT_TICK)

    fun gestureThreshold(view: View) {
        val constant =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
            } else {
                HapticFeedbackConstants.CONFIRM
            }
        safeHaptic(view, constant, Pattern.GESTURE_THRESHOLD, VibrationEffect.EFFECT_HEAVY_CLICK)
    }

    fun gestureThreshold(context: Context) = performAdvancedVibration(context, Pattern.GESTURE_THRESHOLD, VibrationEffect.EFFECT_HEAVY_CLICK)

    fun gestureThresholdRelease(view: View) {
        val constant =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            }
        safeHaptic(view, constant, Pattern.GESTURE_THRESHOLD_RELEASE, VibrationEffect.EFFECT_TICK)
    }

    fun gestureThresholdRelease(context: Context) = performAdvancedVibration(context, Pattern.GESTURE_THRESHOLD_RELEASE, VibrationEffect.EFFECT_TICK)

    fun dragStart(view: View) {
        val constant =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                HapticFeedbackConstants.DRAG_START
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }
        safeHaptic(view, constant, Pattern.DRAG_START, VibrationEffect.EFFECT_HEAVY_CLICK)
    }

    fun dragStart(context: Context) = performAdvancedVibration(context, Pattern.DRAG_START, VibrationEffect.EFFECT_HEAVY_CLICK)

    fun gestureStart(view: View) {
        val constant =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.GESTURE_START
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }
        safeHaptic(view, constant, Pattern.GESTURE_START, VibrationEffect.EFFECT_CLICK)
    }

    fun gestureStart(context: Context) = performAdvancedVibration(context, Pattern.GESTURE_START, VibrationEffect.EFFECT_CLICK)

    fun gestureEnd(view: View) {
        val constant =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.GESTURE_END
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }
        safeHaptic(view, constant, Pattern.GESTURE_END, VibrationEffect.EFFECT_CLICK)
    }

    fun gestureEnd(context: Context) = performAdvancedVibration(context, Pattern.GESTURE_END, VibrationEffect.EFFECT_CLICK)

    fun cardMorph(view: View) {
        val constant =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                HapticFeedbackConstants.SEGMENT_TICK
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            }
        safeHaptic(view, constant, Pattern.CARD_MORPH, VibrationEffect.EFFECT_CLICK)
    }

    fun cardMorph(context: Context) = performAdvancedVibration(context, Pattern.CARD_MORPH, VibrationEffect.EFFECT_CLICK)
}
