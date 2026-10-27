package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.HapticUtils
import android.content.Context
import android.content.SharedPreferences
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

/**
 * Material 3 Expressive structured toggle grid for long-press contextual actions.
 * Presents a balanced 2x2 grid of tactile action tiles with real-time feedback.
 */
class MultiToggleGroupPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : Preference(context, attrs),
        SharedPreferences.OnSharedPreferenceChangeListener {
        data class ToggleItem(
            val key: String,
            val titleRes: Int,
            val iconRes: Int,
            val defaultVal: Boolean = true,
        )

        private val items =
            listOf(
                ToggleItem("lp_open_app", R.string.settings_lp_open_app, R.drawable.ic_outline_play_arrow_24),
                ToggleItem("lp_app_info", R.string.settings_lp_app_info, R.drawable.ic_outline_info_24),
                ToggleItem("lp_toggle_permission", R.string.settings_lp_toggle_permission, R.drawable.ic_shield_24),
                ToggleItem("lp_hide_from_list", R.string.settings_lp_hide_from_list, R.drawable.ic_visibility_off_24),
            )

        private val states = mutableStateMapOf<String, Boolean>()

        init {
            layoutResource = R.layout.preference_compose
            isSelectable = false
            syncStates()
        }

        private fun syncStates() {
            val p = ShizukuSettings.getPreferences() ?: return
            items.forEach { item ->
                states[item.key] = p.getBoolean(item.key, item.defaultVal)
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
            if (items.any { it.key == key }) {
                syncStates()
            }
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            val cv =
                holder.findViewById(R.id.preference_compose_view) as? ComposeView
                    ?: holder.itemView as? ComposeView ?: return
            cv.setPreferenceContent {
                MultiToggleContent(
                    items = items,
                    states = states,
                    onToggle = { key, newChecked ->
                        states[key] = newChecked
                        ShizukuSettings
                            .getPreferences()
                            ?.edit()
                            ?.putBoolean(key, newChecked)
                            ?.apply()
                        ShizukuSettings.syncAllPlusFeaturesToServer()
                    },
                )
            }
        }
    }

@Composable
private fun MultiToggleContent(
    items: List<MultiToggleGroupPreference.ToggleItem>,
    states: Map<String, Boolean>,
    onToggle: (String, Boolean) -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(24.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_lp_category_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Balanced 2x2 Grid
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Row 1
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (items.isNotEmpty()) {
                        Box(modifier = Modifier.weight(1f)) {
                            ToggleTile(
                                item = items[0],
                                checked = states[items[0].key] ?: items[0].defaultVal,
                                onToggle = onToggle,
                            )
                        }
                    }
                    if (items.size > 1) {
                        Box(modifier = Modifier.weight(1f)) {
                            ToggleTile(
                                item = items[1],
                                checked = states[items[1].key] ?: items[1].defaultVal,
                                onToggle = onToggle,
                            )
                        }
                    }
                }

                // Row 2
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (items.size > 2) {
                        Box(modifier = Modifier.weight(1f)) {
                            ToggleTile(
                                item = items[2],
                                checked = states[items[2].key] ?: items[2].defaultVal,
                                onToggle = onToggle,
                            )
                        }
                    }
                    if (items.size > 3) {
                        Box(modifier = Modifier.weight(1f)) {
                            ToggleTile(
                                item = items[3],
                                checked = states[items[3].key] ?: items[3].defaultVal,
                                onToggle = onToggle,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleTile(
    item: MultiToggleGroupPreference.ToggleItem,
    checked: Boolean,
    onToggle: (String, Boolean) -> Unit,
) {
    val view = LocalView.current

    val scale by animateFloatAsState(
        targetValue = if (checked) 1.0f else 0.97f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "tileScale",
    )
    val cornerRadius by animateDpAsState(
        targetValue = if (checked) 16.dp else 12.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "tileCorner",
    )
    val bgColor by animateColorAsState(
        targetValue =
            if (checked) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)
            },
        label = "tileBg",
    )
    val contentColor by animateColorAsState(
        targetValue =
            if (checked) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            },
        label = "tileContent",
    )

    val shape = RoundedCornerShape(cornerRadius)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .scale(scale)
                .clip(shape)
                .background(bgColor)
                .border(
                    width = 1.dp,
                    color =
                        if (checked) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        } else {
                            Color.Transparent
                        },
                    shape = shape,
                ).clickable {
                    val next = !checked
                    if (next) {
                        HapticUtils.toggleOn(view)
                    } else {
                        HapticUtils.toggleOff(view)
                    }
                    onToggle(item.key, next)
                }.padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(contentColor.copy(alpha = if (checked) 0.16f else 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(item.iconRes),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp),
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = stringResource(item.titleRes),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (checked) FontWeight.Bold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (checked) {
            Text(
                text = "✓",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor,
            )
        }
    }
}
