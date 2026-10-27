package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.HapticUtils
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.AttributeSet
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import kotlin.math.roundToInt

/**
 * Combined Material 3 Expressive motion card.
 * Integrates the master animation toggle, live discrete slider, and real-time pulse preview.
 */
class MotionControlsPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : Preference(context, attrs),
        SharedPreferences.OnSharedPreferenceChangeListener {
        private val masterEnabled = mutableStateOf(true)
        private val intensity = mutableFloatStateOf(2f)

        init {
            layoutResource = R.layout.preference_compose
            isSelectable = false
            syncPrefs()
        }

        private fun syncPrefs() {
            masterEnabled.value = ShizukuSettings.isExpressiveAnimationsEnabled()
            intensity.floatValue = ShizukuSettings.getAnimationIntensity().toFloat().coerceIn(0f, 3f)
        }

        override fun onAttached() {
            super.onAttached()
            ShizukuSettings.getPreferences()?.registerOnSharedPreferenceChangeListener(this)
            syncPrefs()
        }

        override fun onDetached() {
            super.onDetached()
            ShizukuSettings.getPreferences()?.unregisterOnSharedPreferenceChangeListener(this)
        }

        override fun onSharedPreferenceChanged(
            prefs: SharedPreferences?,
            key: String?,
        ) {
            if (key == "expressive_animations" || key == "animation_intensity") {
                syncPrefs()
            }
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            val cv =
                holder.findViewById(R.id.preference_compose_view) as? ComposeView
                    ?: holder.itemView as? ComposeView ?: return
            cv.setPreferenceContent {
                MotionCardContent(
                    enabled = masterEnabled.value,
                    intensity = intensity.floatValue,
                    onToggleMaster = { newOn ->
                        masterEnabled.value = newOn
                        ShizukuSettings
                            .getPreferences()
                            ?.edit()
                            ?.putBoolean("expressive_animations", newOn)
                            ?.apply()
                        context.findSettingsActivity()?.onThemeChanged()
                    },
                    onIntensityChanged = { newInt ->
                        intensity.floatValue = newInt.toFloat()
                        ShizukuSettings
                            .getPreferences()
                            ?.edit()
                            ?.putString("animation_intensity", newInt.toString())
                            ?.apply()
                        context.findSettingsActivity()?.onThemeChanged()
                    },
                )
            }
        }
    }

private fun Context.findSettingsActivity(): SettingsActivity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is SettingsActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
private fun MotionCardContent(
    enabled: Boolean,
    intensity: Float,
    onToggleMaster: (Boolean) -> Unit,
    onIntensityChanged: (Int) -> Unit,
) {
    val view = LocalView.current

    val masterBorderColor by animateColorAsState(
        targetValue =
            if (enabled) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            } else {
                androidx.compose.ui.graphics.Color.Transparent
            },
        label = "masterBorder",
    )

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .border(
                    width = 1.dp,
                    color = masterBorderColor,
                    shape = RoundedCornerShape(22.dp),
                ),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = if (enabled) 2.dp else 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            // Master Switch Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_animation_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_expressive_animations),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.settings_expressive_animations_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Switch(
                    checked = enabled,
                    onCheckedChange = { checked ->
                        if (checked) {
                            HapticUtils.toggleOn(view)
                        } else {
                            HapticUtils.toggleOff(view)
                        }
                        onToggleMaster(checked)
                    },
                )
            }

            // Expandable Intensity Slider & Live Preview
            AnimatedVisibility(
                visible = enabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                ) {
                    val currentStep = intensity.roundToInt().coerceIn(0, 3)
                    val intensityLabels = stringArrayResource(R.array.animation_intensities)
                    val fallbackName =
                        when (currentStep) {
                            0 -> "Reduced"
                            1 -> "Subtle"
                            3 -> "Expressive"
                            else -> "Standard"
                        }
                    val label = intensityLabels.getOrNull(currentStep) ?: fallbackName
                    val speed =
                        when (currentStep) {
                            0 -> "0.5x"
                            1 -> "0.8x"
                            3 -> "1.2x"
                            else -> "1.0x"
                        }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_animation_intensity),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        // Live speed badge
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                text = "$label ($speed)",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Slider(
                        value = intensity,
                        onValueChange = { newVal ->
                            val rounded = newVal.roundToInt()
                            if (rounded != intensity.roundToInt()) {
                                HapticUtils.segmentTick(view)
                            }
                            onIntensityChanged(rounded)
                        },
                        valueRange = 0f..3f,
                        steps = 2,
                        colors =
                            SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            ),
                    )

                    // Real-time pulse preview
                    val durationMs =
                        when (currentStep) {
                            0 -> 1600
                            1 -> 1200
                            3 -> 700
                            else -> 950
                        }
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val pulseScale by infiniteTransition.animateFloat(
                        initialValue = 0.85f,
                        targetValue = 1.15f,
                        animationSpec =
                            infiniteRepeatable(
                                animation = tween(durationMs, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse,
                            ),
                        label = "pulseScale",
                    )
                    val pulseCorner by infiniteTransition.animateFloat(
                        initialValue = 4f,
                        targetValue = 12f,
                        animationSpec =
                            infiniteRepeatable(
                                animation = tween(durationMs, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse,
                            ),
                        label = "pulseCorner",
                    )

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(24.dp)
                                    .scale(pulseScale)
                                    .clip(RoundedCornerShape((pulseCorner * 1.5f).dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(10.dp)
                                        .clip(RoundedCornerShape(pulseCorner.dp))
                                        .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Live motion & shape morph preview",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
