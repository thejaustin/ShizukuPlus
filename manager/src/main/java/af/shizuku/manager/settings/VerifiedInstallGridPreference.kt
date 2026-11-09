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
import androidx.compose.runtime.mutableStateOf
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

/**
 * Material 3 Expressive 2x2 interactive security engines grid for APK verified installation.
 * Replaces scattered standalone switches and API key preferences with a unified, high-density,
 * tactile security module.
 */
class VerifiedInstallGridPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : Preference(context, attrs),
        SharedPreferences.OnSharedPreferenceChangeListener {
        data class EngineItem(
            val key: String,
            val titleRes: Int,
            val badgeRes: Int,
            val iconRes: Int,
            val defaultVal: Boolean,
            val isApiKeyProvider: Boolean = false,
        )

        private val engines =
            listOf(
                EngineItem(
                    key = "verify_apk_local",
                    titleRes = R.string.settings_verify_apk_local_title,
                    badgeRes = R.string.settings_verify_apk_local_badge,
                    iconRes = R.drawable.ic_shield_24,
                    // Off by default, like the online checks: the request stays as slim as stock Shizuku.
                    defaultVal = false,
                ),
                EngineItem(
                    key = "verify_apk_virustotal",
                    titleRes = R.string.settings_verify_apk_virustotal_title,
                    badgeRes = R.string.settings_verify_apk_virustotal_badge,
                    iconRes = R.drawable.ic_update_24,
                    defaultVal = false,
                    isApiKeyProvider = true,
                ),
                EngineItem(
                    key = "verify_apk_pithus",
                    titleRes = R.string.settings_verify_apk_pithus_title,
                    badgeRes = R.string.settings_verify_apk_pithus_badge,
                    iconRes = R.drawable.ic_admin_panel_settings_24,
                    defaultVal = false,
                    isApiKeyProvider = true,
                ),
            )

        private val states = mutableStateMapOf<String, Boolean>()
        private val vtKeySet = mutableStateOf(false)
        private val pithusKeySet = mutableStateOf(false)

        init {
            layoutResource = R.layout.preference_compose
            isSelectable = false
            syncStates()
        }

        private fun syncStates() {
            val p = ShizukuSettings.getPreferences() ?: return
            engines.forEach { engine ->
                states[engine.key] = p.getBoolean(engine.key, engine.defaultVal)
            }
            vtKeySet.value = ShizukuSettings.getVirusTotalApiKey().isNotBlank()
            pithusKeySet.value = ShizukuSettings.getPithusApiKey().isNotBlank()
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
            if (key in engines.map { it.key } || key == "virustotal_api_key" || key == "pithus_api_key") {
                syncStates()
            }
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            val cv =
                holder.findViewById(R.id.preference_compose_view) as? ComposeView
                    ?: holder.itemView as? ComposeView ?: return
            cv.setPreferenceContent {
                VerifiedInstallGridContent(
                    engines = engines,
                    states = states,
                    vtKeyConfigured = vtKeySet.value,
                    pithusKeyConfigured = pithusKeySet.value,
                    onToggle = { key, newChecked ->
                        if (key == "verify_apk_virustotal" && newChecked && ShizukuSettings.getVirusTotalApiKey().isBlank()) {
                            VerifiedInstallPrefs.promptVirusTotalKey(
                                context = context,
                                onSaved = {
                                    states[key] = true
                                    vtKeySet.value = true
                                    ShizukuSettings
                                        .getPreferences()
                                        ?.edit()
                                        ?.putBoolean(key, true)
                                        ?.apply()
                                },
                            )
                        } else {
                            states[key] = newChecked
                            ShizukuSettings
                                .getPreferences()
                                ?.edit()
                                ?.putBoolean(key, newChecked)
                                ?.apply()
                        }
                    },
                    onConfigureKey = { key ->
                        if (key == "verify_apk_virustotal") {
                            VerifiedInstallPrefs.promptVirusTotalKey(
                                context = context,
                                onSaved = { vtKeySet.value = it.isNotBlank() },
                            )
                        } else if (key == "verify_apk_pithus") {
                            VerifiedInstallPrefs.promptPithusKey(
                                context = context,
                                onSaved = { pithusKeySet.value = it.isNotBlank() },
                            )
                        }
                    },
                )
            }
        }
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VerifiedInstallGridContent(
    engines: List<VerifiedInstallGridPreference.EngineItem>,
    states: Map<String, Boolean>,
    vtKeyConfigured: Boolean,
    pithusKeyConfigured: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onConfigureKey: (String) -> Unit,
) {
    val view = LocalView.current
    val activeCount = engines.count { states[it.key] ?: it.defaultVal }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 2x2 Grid of security engines
        val chunked = engines.chunked(2)
        chunked.forEach { rowEngines ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowEngines.forEach { engine ->
                    val checked = states[engine.key] ?: engine.defaultVal
                    val isVT = engine.key == "verify_apk_virustotal"
                    val isPithus = engine.key == "verify_apk_pithus"
                    val keyNeeded = isVT && checked && !vtKeyConfigured
                    val cornerOverride = remember { ShizukuSettings.getCornerRoundingOverride() }

                    val scale by animateFloatAsState(
                        targetValue = if (checked) 1.0f else 0.96f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "engineScale",
                    )
                    val cardCorner by animateDpAsState(
                        targetValue = resolveGridCardCorner(cornerOverride, checked),
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "engineCorner",
                    )
                    val iconCorner by animateDpAsState(
                        targetValue = resolveGridIconCorner(cornerOverride, checked),
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "engineIconCorner",
                    )
                    val bgColor by animateColorAsState(
                        targetValue =
                            when {
                                keyNeeded -> MaterialTheme.colorScheme.errorContainer
                                checked -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        label = "engineBg",
                    )
                    val contentColor by animateColorAsState(
                        targetValue =
                            when {
                                keyNeeded -> MaterialTheme.colorScheme.onErrorContainer
                                checked -> MaterialTheme.colorScheme.onPrimaryContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        label = "engineContent",
                    )

                    val cardShape = RoundedCornerShape(cardCorner)
                    Surface(
                        modifier =
                            Modifier
                                .weight(1f)
                                .scale(scale)
                                .clip(cardShape)
                                .border(
                                    width = if (checked) 1.5.dp else 1.dp,
                                    color =
                                        when {
                                            keyNeeded -> MaterialTheme.colorScheme.error
                                            checked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                        },
                                    shape = cardShape,
                                ).combinedClickable(
                                    onClick = {
                                        val newChecked = !checked
                                        if (newChecked) HapticUtils.toggleOn(view) else HapticUtils.toggleOff(view)
                                        onToggle(engine.key, newChecked)
                                    },
                                    onLongClick = {
                                        if (engine.isApiKeyProvider) {
                                            HapticUtils.tap(view)
                                            onConfigureKey(engine.key)
                                        }
                                    },
                                ),
                        shape = cardShape,
                        color = bgColor,
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(36.dp)
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
                                        painter = painterResource(engine.iconRes),
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

                                if (checked) {
                                    Box(
                                        modifier =
                                            Modifier
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (keyNeeded) {
                                                        MaterialTheme.colorScheme.error
                                                    } else {
                                                        MaterialTheme.colorScheme.primary
                                                    },
                                                ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_server_ok_24),
                                            contentDescription = null,
                                            tint =
                                                if (keyNeeded) {
                                                    MaterialTheme.colorScheme.onError
                                                } else {
                                                    MaterialTheme.colorScheme.onPrimary
                                                },
                                            modifier = Modifier.size(13.dp),
                                        )
                                    }
                                }
                            }

                            Text(
                                text = stringResource(engine.titleRes),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (checked) FontWeight.Bold else FontWeight.Medium,
                                color = contentColor,
                                maxLines = 2,
                            )

                            val badgeText =
                                when {
                                    isVT && !vtKeyConfigured -> stringResource(R.string.settings_verify_key_required_hint)
                                    isVT && vtKeyConfigured -> stringResource(R.string.settings_verify_key_saved_hint)
                                    isPithus && pithusKeyConfigured -> stringResource(R.string.settings_verify_key_saved_hint)
                                    else -> stringResource(engine.badgeRes)
                                }

                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = contentColor.copy(alpha = 0.8f),
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }

        // Security Posture Summary Pill
        val postureShape = RoundedCornerShape(16.dp)
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(postureShape)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        postureShape,
                    ),
            shape = postureShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_shield_24),
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
                        text = stringResource(R.string.settings_verify_engines_posture, activeCount, engines.size),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // 4 mini pill indicators
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    engines.forEach { eng ->
                        val isActive = states[eng.key] ?: eng.defaultVal
                        Box(
                            modifier =
                                Modifier
                                    .size(width = 12.dp, height = 6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        if (isActive) {
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
    }
}
