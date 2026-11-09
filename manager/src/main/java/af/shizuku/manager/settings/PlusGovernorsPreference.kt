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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Material 3 Expressive System Performance Governors & Bridges module.
 * Combines standalone governor switches into an interactive, high-density system control deck
 * with spring transitions, active posture monitoring, and inline architectural details.
 */
class PlusGovernorsPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : Preference(context, attrs),
        SharedPreferences.OnSharedPreferenceChangeListener {
        data class GovernorItem(
            val key: String,
            val titleRes: Int,
            val summaryRes: Int,
            val iconRes: Int,
            val infoTitleRes: Int,
            val infoDetailRes: Int,
            val defaultVal: Boolean = true,
        )

        private val governors =
            listOf(
                GovernorItem(
                    key = "activity_manager_plus_enabled",
                    titleRes = R.string.settings_activity_manager_plus_title,
                    summaryRes = R.string.settings_activity_manager_plus_summary,
                    iconRes = R.drawable.ic_bolt_24,
                    infoTitleRes = R.string.help_activity_manager_plus_title,
                    infoDetailRes = R.string.help_activity_manager_plus_detail,
                ),
                GovernorItem(
                    key = "status_bar_governor_plus_enabled",
                    titleRes = R.string.settings_status_bar_governor_plus_title,
                    summaryRes = R.string.settings_status_bar_governor_plus_summary,
                    iconRes = R.drawable.ic_outline_expand_more_24,
                    infoTitleRes = R.string.help_status_bar_governor_plus_title,
                    infoDetailRes = R.string.help_status_bar_governor_plus_detail,
                ),
                GovernorItem(
                    key = "shell_interceptor_enabled",
                    titleRes = R.string.settings_shell_interceptor,
                    summaryRes = R.string.settings_shell_interceptor_summary,
                    iconRes = R.drawable.ic_terminal_24,
                    infoTitleRes = R.string.help_shell_interceptor_title,
                    infoDetailRes = R.string.help_shell_interceptor_detail,
                ),
                GovernorItem(
                    key = "avf_manager_enabled",
                    titleRes = R.string.settings_avf_manager_title,
                    summaryRes = R.string.settings_avf_manager_summary,
                    iconRes = R.drawable.ic_server_24,
                    infoTitleRes = R.string.help_avf_manager_title,
                    infoDetailRes = R.string.help_avf_manager_detail,
                ),
                GovernorItem(
                    key = "storage_proxy_enabled",
                    titleRes = R.string.settings_storage_proxy,
                    summaryRes = R.string.settings_storage_proxy_summary,
                    iconRes = R.drawable.ic_folder_24,
                    infoTitleRes = R.string.help_storage_proxy_title,
                    infoDetailRes = R.string.help_storage_proxy_detail,
                ),
            )

        private val states = mutableStateMapOf<String, Boolean>()

        init {
            layoutResource = R.layout.preference_compose
            isSelectable = false
            syncStates()
        }

        private fun syncStates() {
            val p = ShizukuSettings.getPreferences() ?: return
            governors.forEach { gov ->
                states[gov.key] = p.getBoolean(gov.key, gov.defaultVal)
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
            if (key in governors.map { it.key }) {
                syncStates()
            }
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            val cv =
                holder.findViewById(R.id.preference_compose_view) as? ComposeView
                    ?: holder.itemView as? ComposeView ?: return
            cv.setPreferenceContent {
                GovernorsContent(
                    governors = governors,
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
                    onShowHelp = { gov ->
                        MaterialAlertDialogBuilder(context)
                            .setTitle(gov.infoTitleRes)
                            .setMessage(gov.infoDetailRes)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    },
                )
            }
        }
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GovernorsContent(
    governors: List<PlusGovernorsPreference.GovernorItem>,
    states: Map<String, Boolean>,
    onToggle: (String, Boolean) -> Unit,
    onShowHelp: (PlusGovernorsPreference.GovernorItem) -> Unit,
) {
    val view = LocalView.current
    val activeCount = governors.count { states[it.key] ?: it.defaultVal }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Posture Status Header Card
        val headerShape = RoundedCornerShape(18.dp)
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(headerShape)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        headerShape,
                    ),
            shape = headerShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_bolt_24),
                            contentDescription = null,
                            tint =
                                if (activeCount > 0) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                },
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.settings_plus_governors_posture, activeCount, governors.size),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_plus_governors_help_hint),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }

                // Mini indicator pills
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    governors.forEach { gov ->
                        val active = states[gov.key] ?: gov.defaultVal
                        Box(
                            modifier =
                                Modifier
                                    .size(width = 10.dp, height = 6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        if (active) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHighest
                                        },
                                    ),
                        )
                    }
                }
            }
        }

        // Governor Items
        governors.forEach { gov ->
            val checked = states[gov.key] ?: gov.defaultVal
            val cornerOverride = remember { ShizukuSettings.getCornerRoundingOverride() }

            val scale by animateFloatAsState(
                targetValue = if (checked) 1.0f else 0.98f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "govScale",
            )
            val corner by animateDpAsState(
                targetValue = resolveGridCardCorner(cornerOverride, checked),
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "govCorner",
            )
            val iconCorner by animateDpAsState(
                targetValue = resolveGridIconCorner(cornerOverride, checked),
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "govIconCorner",
            )
            val bgColor by animateColorAsState(
                targetValue =
                    if (checked) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                label = "govBg",
            )
            val contentColor by animateColorAsState(
                targetValue =
                    if (checked) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                label = "govContent",
            )

            val itemShape = RoundedCornerShape(corner)
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .scale(scale)
                        .clip(itemShape)
                        .border(
                            width = if (checked) 1.5.dp else 1.dp,
                            color =
                                if (checked) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                },
                            shape = itemShape,
                        ).combinedClickable(
                            onClick = {
                                val next = !checked
                                if (next) HapticUtils.toggleOn(view) else HapticUtils.toggleOff(view)
                                onToggle(gov.key, next)
                            },
                            onLongClick = {
                                HapticUtils.tap(view)
                                onShowHelp(gov)
                            },
                        ),
                shape = itemShape,
                color = bgColor,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(iconCorner))
                                .background(
                                    if (checked) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(gov.iconRes),
                            contentDescription = null,
                            tint =
                                if (checked) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                },
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = stringResource(gov.titleRes),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (checked) FontWeight.Bold else FontWeight.Medium,
                                color = contentColor,
                                maxLines = 2,
                            )
                            // PLUS Badge
                            Box(
                                modifier =
                                    Modifier
                                        .clip(RoundedCornerShape(100))
                                        .background(
                                            if (checked) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainerHighest
                                            },
                                        ).padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = "PLUS",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                    color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                        }

                        Text(
                            text = stringResource(gov.summaryRes),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = contentColor.copy(alpha = 0.8f),
                            maxLines = 4,
                        )
                    }

                    if (checked) {
                        Box(
                            modifier =
                                Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_server_ok_24),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
