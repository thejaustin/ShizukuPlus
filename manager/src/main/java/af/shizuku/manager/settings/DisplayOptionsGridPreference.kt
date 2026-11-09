package af.shizuku.manager.settings

import af.shizuku.core.ui.AppActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ktx.findActivity
import af.shizuku.manager.utils.HapticUtils
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.AttributeSet
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

/**
 * Material 3 Expressive 2-column quick toggles grid for display and layout options.
 * Replaces stacked individual full-width rows with compact, tactile cards.
 */
class DisplayOptionsGridPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : Preference(context, attrs),
        SharedPreferences.OnSharedPreferenceChangeListener {
        data class DisplayOption(
            val key: String,
            val titleRes: Int,
            val iconRes: Int,
            val defaultVal: Boolean,
            val isAvailable: () -> Boolean = { true },
        )

        private val allOptions =
            listOf(
                DisplayOption("bold_typography", R.string.settings_bold_typography, R.drawable.ic_format_bold_24, false),
                DisplayOption("expanded_headers_enabled", R.string.settings_expanded_headers, R.drawable.ic_outline_expand_more_24, false) {
                    !ShizukuSettings.isOneUiThemeEnabled() && !ShizukuSettings.isOneHandedModeEnabled()
                },
                DisplayOption("blur_ui_enabled", R.string.settings_blur_ui, R.drawable.ic_opacity_24, false) {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                },
                DisplayOption("edge_to_edge_enabled", R.string.settings_edge_to_edge, R.drawable.ic_fullscreen_24, true) {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM
                },
            )

        private val states = mutableStateMapOf<String, Boolean>()

        init {
            layoutResource = R.layout.preference_compose
            isSelectable = false
            syncStates()
        }

        private fun syncStates() {
            val p = ShizukuSettings.getPreferences() ?: return
            allOptions.forEach { opt ->
                states[opt.key] = p.getBoolean(opt.key, opt.defaultVal)
            }
        }

        override fun onAttached() {
            super.onAttached()
            ShizukuSettings.getPreferences()?.registerOnSharedPreferenceChangeListener(this)
            syncStates()
        }

        override fun onDetached() {
            super.onDetached()
            ShizukuSettings.getPreferences()?.unregisterOnSharedPreferenceChangeListener(this)
        }

        override fun onSharedPreferenceChanged(
            prefs: SharedPreferences?,
            key: String?,
        ) {
            if (allOptions.any { it.key == key }) {
                syncStates()
            }
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            val cv =
                holder.findViewById(R.id.preference_compose_view) as? ComposeView
                    ?: holder.itemView as? ComposeView ?: return
            val availableOptions = allOptions.filter { it.isAvailable() }
            cv.setPreferenceContent {
                DisplayGridContent(
                    options = availableOptions,
                    states = states,
                    onToggle = { key, newChecked ->
                        states[key] = newChecked
                        ShizukuSettings
                            .getPreferences()
                            ?.edit()
                            ?.putBoolean(key, newChecked)
                            ?.apply()
                        val act = context.findActivity<AppActivity>()
                        if (key == "blur_ui_enabled" ||
                            key == "bold_typography" ||
                            key == "expanded_headers_enabled" ||
                            key == "oneui_theme_enabled" ||
                            key == "one_handed_mode"
                        ) {
                            act?.recreateWithoutTransition()
                        } else {
                            (act as? SettingsActivity)?.onThemeChanged()
                        }
                    },
                )
            }
        }
    }

@Composable
private fun DisplayGridContent(
    options: List<DisplayOptionsGridPreference.DisplayOption>,
    states: Map<String, Boolean>,
    onToggle: (String, Boolean) -> Unit,
) {
    val view = LocalView.current

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Render 2 items per row
        val chunked = options.chunked(2)
        chunked.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems.forEach { option ->
                    val checked = states[option.key] ?: option.defaultVal
                    val cornerOverride = remember { ShizukuSettings.getCornerRoundingOverride() }

                    val scale by animateFloatAsState(
                        targetValue = if (checked) 1.0f else 0.96f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "cardScale",
                    )
                    val cardCorner by animateDpAsState(
                        targetValue = resolveGridCardCorner(cornerOverride, checked),
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "cardCorner",
                    )
                    val iconCorner by animateDpAsState(
                        targetValue = resolveGridIconCorner(cornerOverride, checked),
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "iconCorner",
                    )
                    val bgColor by animateColorAsState(
                        targetValue =
                            if (checked) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        label = "cardBg",
                    )
                    val contentColor by animateColorAsState(
                        targetValue =
                            if (checked) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        label = "cardContent",
                    )

                    val cardShape = RoundedCornerShape(cardCorner)
                    Surface(
                        modifier =
                            Modifier
                                .weight(1f)
                                .scale(scale)
                                .clip(cardShape)
                                .border(
                                    width = 1.5.dp,
                                    color =
                                        if (checked) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                        } else {
                                            Color.Transparent
                                        },
                                    shape = cardShape,
                                ).clickable {
                                    val next = !checked
                                    if (next) {
                                        HapticUtils.toggleOn(view)
                                    } else {
                                        HapticUtils.toggleOff(view)
                                    }
                                    onToggle(option.key, next)
                                },
                        shape = cardShape,
                        color = bgColor,
                        tonalElevation = if (checked) 3.dp else 1.dp,
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(iconCorner))
                                        .background(contentColor.copy(alpha = if (checked) 0.16f else 0.09f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(option.iconRes),
                                    contentDescription = null,
                                    tint = contentColor,
                                    modifier = Modifier.size(17.dp),
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(option.titleRes),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (checked) FontWeight.Bold else FontWeight.Medium,
                                    color = contentColor,
                                    maxLines = 3,
                                )
                                Surface(
                                    shape = RoundedCornerShape(if (checked) 8.dp else 4.dp),
                                    color =
                                        if (checked) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        } else {
                                            Color.Transparent
                                        },
                                ) {
                                    Text(
                                        text = if (checked) "✓ ON" else "OFF",
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        fontWeight = if (checked) FontWeight.Bold else FontWeight.Medium,
                                        color =
                                            if (checked) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            },
                                    )
                                }
                            }
                        }
                    }
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
