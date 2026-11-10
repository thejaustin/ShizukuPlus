package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.AppAliasManager
import af.shizuku.manager.utils.HapticUtils
import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatImageView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

/**
 * Material 3 Expressive visual app icon & camouflage alias picker.
 * Replaces plain text popup dialogs with an interactive, spring-animated icon swatch strip.
 */
class AppIconTilePickerPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : Preference(context, attrs),
        SharedPreferences.OnSharedPreferenceChangeListener {
        data class IconOption(
            val key: String,
            val label: String,
            val iconRes: Int,
        )

        private fun getIconOptions(): List<IconOption> {
            val entries = runCatching { context.resources.getStringArray(R.array.app_icon_alias_entries) }.getOrNull()
            return listOf(
                IconOption("default", entries?.getOrNull(0)?.substringBefore(" (") ?: "Shizuku+", R.mipmap.ic_launcher),
                IconOption("classic", entries?.getOrNull(1)?.substringBefore(" (") ?: "Classic", R.mipmap.ic_launcher_classic),
                IconOption("system", entries?.getOrNull(2)?.substringBefore(" (") ?: "System", R.mipmap.ic_launcher_system),
                IconOption("tools", entries?.getOrNull(3)?.substringBefore(" (") ?: "Tools", R.mipmap.ic_launcher_tools),
                IconOption("files", entries?.getOrNull(4)?.substringBefore(" (") ?: "Files", R.mipmap.ic_launcher_files),
                IconOption("notes", entries?.getOrNull(5)?.substringBefore(" (") ?: "Notes", R.mipmap.ic_launcher_notes),
            )
        }

        private val selectedAlias = mutableStateOf("default")

        init {
            layoutResource = R.layout.preference_compose
            isSelectable = false
            syncAlias()
        }

        private fun syncAlias() {
            selectedAlias.value = ShizukuSettings.getAppIconAlias()
        }

        override fun onAttached() {
            super.onAttached()
            ShizukuSettings.getPreferences()?.registerOnSharedPreferenceChangeListener(this)
            syncAlias()
        }

        override fun onDetached() {
            super.onDetached()
            ShizukuSettings.getPreferences()?.unregisterOnSharedPreferenceChangeListener(this)
        }

        override fun onSharedPreferenceChanged(
            prefs: SharedPreferences?,
            key: String?,
        ) {
            if (key == ShizukuSettings.Keys.KEY_APP_ICON_ALIAS) {
                syncAlias()
            }
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            val cv =
                holder.findViewById(R.id.preference_compose_view) as? ComposeView
                    ?: holder.itemView as? ComposeView ?: return
            cv.setPreferenceContent {
                AppIconPickerContent(
                    options = getIconOptions(),
                    selectedKey = selectedAlias.value,
                    onSelect = { newKey ->
                        selectedAlias.value = newKey
                        ShizukuSettings.setAppIconAlias(newKey)
                        AppAliasManager.applyAlias(context, newKey)
                    },
                )
            }
        }
    }

@Composable
private fun AppIconPickerContent(
    options: List<AppIconTilePickerPreference.IconOption>,
    selectedKey: String,
    onSelect: (String) -> Unit,
) {
    val view = LocalView.current

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
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
                        painter = painterResource(R.drawable.ic_sparkle_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_app_icon_name_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.settings_app_icon_name_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Horizontal Swatch Strip
            val scrollState = rememberScrollState()
            val aliasEntries = stringArrayResource(R.array.app_icon_alias_entries)
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                options.forEachIndexed { index, option ->
                    val isSelected = option.key == selectedKey
                    val displayLabel = aliasEntries.getOrNull(index)?.substringBefore(" (") ?: option.label

                    val scale by animateFloatAsState(
                        targetValue = if (isSelected) 1.0f else 0.94f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "tileScale",
                    )
                    val cardCorner by animateDpAsState(
                        targetValue = if (isSelected) 20.dp else 12.dp,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "tileCorner",
                    )
                    val iconCorner by animateDpAsState(
                        targetValue = if (isSelected) 10.dp else 16.dp,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "tileIconCorner",
                    )
                    val bgColor by animateColorAsState(
                        targetValue =
                            if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        label = "tileBg",
                    )
                    val contentColor by animateColorAsState(
                        targetValue =
                            if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        label = "tileContent",
                    )

                    val tileShape = RoundedCornerShape(cardCorner)
                    Surface(
                        modifier =
                            Modifier
                                .scale(scale)
                                .clip(tileShape)
                                .border(
                                    width = 1.5.dp,
                                    color =
                                        if (isSelected) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                        } else {
                                            Color.Transparent
                                        },
                                    shape = tileShape,
                                ).clickable {
                                    if (!isSelected) {
                                        HapticUtils.segmentTick(view)
                                        onSelect(option.key)
                                    }
                                },
                        shape = tileShape,
                        color = bgColor,
                        tonalElevation = if (isSelected) 3.dp else 1.dp,
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(iconCorner)),
                                contentAlignment = Alignment.Center,
                            ) {
                                AndroidView(
                                    factory = { ctx ->
                                        AppCompatImageView(ctx).apply {
                                            scaleType = ImageView.ScaleType.FIT_CENTER
                                        }
                                    },
                                    update = { imageView ->
                                        imageView.contentDescription = option.label
                                        if (option.iconRes != 0) {
                                            runCatching {
                                                imageView.setImageDrawable(
                                                    AppCompatResources.getDrawable(imageView.context, option.iconRes),
                                                )
                                            }.onFailure {
                                                imageView.setImageDrawable(null)
                                            }
                                        } else {
                                            imageView.setImageDrawable(null)
                                        }
                                    },
                                    modifier = Modifier.size(40.dp),
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = displayLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = contentColor,
                                    maxLines = 2,
                                )
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "✓",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
