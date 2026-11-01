package af.shizuku.manager.settings

import af.shizuku.core.ui.compose.controlContainer
import af.shizuku.manager.R
import af.shizuku.manager.utils.ExpressiveShapes
import af.shizuku.manager.utils.HapticUtils
import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

/**
 * Picks the M3 Expressive icon-container shape. Each tile draws the real shape; the selected tile
 * morphs from a circle into its shape, previewing the toggle morph used on switch rows.
 */
class ShapePickerPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : Preference(context, attrs) {
        private val selected = mutableStateOf(ExpressiveShapes.OFF)

        init {
            layoutResource = R.layout.preference_compose
            isSelectable = false
        }

        override fun onGetDefaultValue(
            a: TypedArray,
            index: Int,
        ): Any? = a.getString(index)

        override fun onSetInitialValue(defaultValue: Any?) {
            selected.value = getPersistedString(defaultValue as? String ?: ExpressiveShapes.OFF)
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            val cv = holder.findViewById(R.id.preference_compose_view) as? ComposeView ?: holder.itemView as? ComposeView ?: return
            val enabled = isEnabled
            val prefIcon = icon
            cv.setPreferenceContent {
                ComposePreferenceRow(title = title, summary = summary, enabled = enabled, icon = prefIcon) {
                    ShapeTiles(selected.value, enabled) { key ->
                        if (key != selected.value && callChangeListener(key)) {
                            selected.value = key
                            persistString(key)
                        }
                    }
                }
            }
        }
    }

@Composable
private fun ShapeTiles(
    selectedKey: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val view = LocalView.current
    val names =
        mapOf(
            ExpressiveShapes.OFF to R.string.settings_icon_morph_shape_off,
            "cookie" to R.string.custom_icon_studio_shape_cookie,
            "clover" to R.string.settings_icon_morph_shape_clover,
            "sunny" to R.string.settings_icon_morph_shape_sunny,
            "gem" to R.string.settings_icon_morph_shape_gem,
            "flower" to R.string.settings_icon_morph_shape_flower,
            "star" to R.string.settings_icon_morph_shape_star,
            "burst" to R.string.settings_icon_morph_shape_burst,
        )
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(top = 8.dp)
                .alpha(if (enabled) 1f else 0.38f)
                .selectableGroup(),
    ) {
        ExpressiveShapes.KEYS.forEach { key ->
            val isSelected = key == selectedKey
            // Every option shows its real shape; a part-morphed preview read as a blob. The
            // selected one is told apart by color and size.
            val scale by animateFloatAsState(
                if (isSelected) 1f else 0.86f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                label = "tileScale",
            )
            val fill by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.controlContainer,
                label = "tileFill",
            )
            val base = key.removeSuffix(ExpressiveShapes.CLASSIC_SUFFIX)
            val label =
                if (base != key) {
                    stringResource(R.string.settings_icon_morph_shape_classic, stringResource(names.getValue(base)))
                } else {
                    stringResource(names.getValue(key))
                }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton) {
                            HapticUtils.segmentTick(view)
                            onSelect(key)
                        }.semantics { contentDescription = label }
                        .padding(6.dp),
            ) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(40.dp)) {
                        val shapeKey = if (key == ExpressiveShapes.OFF) "circle" else key
                        val path = ExpressiveShapes.path(shapeKey, size.width, size.height)
                        scale(scale) { drawPath(path.asComposePath(), fill) }
                    }
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
