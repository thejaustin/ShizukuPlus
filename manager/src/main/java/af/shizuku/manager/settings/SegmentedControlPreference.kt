package af.shizuku.manager.settings

import af.shizuku.core.ui.compose.controlContainer
import af.shizuku.core.ui.compose.onControlContainer
import af.shizuku.manager.R
import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

/**
 * Single-choice preference rendered as an M3 segmented button row. Stores its value as a String,
 * exactly like ListPreference, so it is a drop-in replacement for short (2-4 option) lists.
 *
 * XML:
 * ```
 * <af.shizuku.manager.settings.SegmentedControlPreference
 *     android:key="..."
 *     android:title="..."
 *     android:entries="@array/..."
 *     android:entryValues="@array/..."
 *     android:defaultValue="..." />
 * ```
 * Listen for changes with setOnPreferenceChangeListener; return false to veto.
 */
class SegmentedControlPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : Preference(context, attrs) {
        var entries: Array<CharSequence> = emptyArray()
        var entryValues: Array<CharSequence> = emptyArray()

        private val selected = mutableStateOf<String?>(null)

        var value: String?
            get() = selected.value
            set(newValue) {
                val changed = selected.value != newValue
                selected.value = newValue
                if (changed) {
                    persistString(newValue)
                    notifyDependencyChange(shouldDisableDependents())
                }
            }

        init {
            layoutResource = R.layout.preference_compose
            isSelectable = false
            context.obtainStyledAttributes(attrs, R.styleable.SegmentedControlPreference).apply {
                getTextArray(R.styleable.SegmentedControlPreference_android_entries)?.let { entries = it }
                getTextArray(R.styleable.SegmentedControlPreference_android_entryValues)?.let { entryValues = it }
                recycle()
            }
        }

        override fun onGetDefaultValue(
            a: TypedArray,
            index: Int,
        ): Any? = a.getString(index)

        override fun onSetInitialValue(defaultValue: Any?) {
            value = getPersistedString(defaultValue as? String)
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            val cv =
                (holder.itemView as? ComposeView)
                    ?: (holder.findViewById(R.id.preference_compose_view) as? ComposeView)
            if (cv == null) {
                timber.log.Timber.w("SegmentedControlPreference: ComposeView not found in holder (itemView is %s)", holder.itemView.javaClass.name)
                return
            }
            val labels = entries.map { it.toString() }
            val values = entryValues.map { it.toString() }
            val enabled = isEnabled
            val prefIcon = icon
            cv.setPreferenceContent {
                ComposePreferenceRow(
                    title = title,
                    summary = summary,
                    enabled = enabled,
                    icon = prefIcon,
                ) {
                    SegmentedChoiceRow(
                        labels = labels,
                        selectedIndex = values.indexOf(selected.value),
                        enabled = enabled,
                        onSelect = { index ->
                            val newValue = values.getOrNull(index) ?: return@SegmentedChoiceRow
                            if (newValue != selected.value && callChangeListener(newValue)) {
                                value = newValue
                            }
                        },
                    )
                }
            }
        }
    }

/**
 * Reusable single-choice row (shared by SegmentedControlPreference and compound cards), drawn as an
 * M3 Expressive connected button group: the selected button morphs to a full pill and pressed
 * buttons squish on the theme's expressive spring, with a segment-tick haptic on every change.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SegmentedChoiceRow(
    labels: List<String>,
    selectedIndex: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = androidx.compose.ui.platform.LocalView.current
    // Settings rows recycle their ComposeView between preferences. Keyed on the options, the
    // buttons start from this row's own checked/unchecked shapes instead of carrying the morph
    // state of whichever row used the view before (the selected one sometimes kept a segment
    // shape until tapped).
    androidx.compose.runtime.key(labels) {
        androidx.compose.foundation.layout.Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement =
                androidx.compose.foundation.layout.Arrangement.spacedBy(
                    androidx.compose.material3.ButtonGroupDefaults.ConnectedSpaceBetween,
                ),
        ) {
            labels.forEachIndexed { index, label ->
                androidx.compose.material3.ToggleButton(
                    checked = index == selectedIndex,
                    onCheckedChange = {
                        if (index != selectedIndex) {
                            af.shizuku.manager.utils.HapticUtils
                                .segmentTick(view)
                            onSelect(index)
                        }
                    },
                    enabled = enabled,
                    // The default unchecked container is near the card color and vanishes on dark /
                    // AMOLED cards; a tonal container keeps every option visibly tappable.
                    colors =
                        androidx.compose.material3.ToggleButtonDefaults.toggleButtonColors(
                            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.controlContainer,
                            contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onControlContainer,
                        ),
                    shapes = connectedMorphShapes(index, labels.size),
                    // Tighter than the M3E default so 3-way rows like "Always light / Always dark /
                    // Follow system" fit; long labels wrap to two centred lines instead of "Follo…".
                    contentPadding =
                        androidx.compose.foundation.layout
                            .PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .semantics { role = androidx.compose.ui.semantics.Role.RadioButton },
                ) {
                    Text(
                        label,
                        maxLines = 3,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

/**
 * Explicit M3 Expressive connected-group shapes so the morph is unmistakable: unselected buttons
 * keep rounded outer ends but tight inner corners (a middle button reads as a segment, not a pill),
 * pressing squeezes the inner corners further, and the selected button springs to a full pill.
 * RoundedCornerShape -> RoundedCornerShape lets the theme's expressive motion scheme animate it.
 */
@Composable
private fun connectedMorphShapes(
    index: Int,
    count: Int,
): androidx.compose.material3.ToggleButtonShapes {
    val outer =
        androidx.compose.foundation.shape
            .CornerSize(50)
    val inner =
        androidx.compose.foundation.shape
            .CornerSize(6.dp)
    val innerPressed =
        androidx.compose.foundation.shape
            .CornerSize(3.dp)
    val first = index == 0
    val last = index == count - 1

    fun shapeWith(innerCorner: androidx.compose.foundation.shape.CornerSize) =
        androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = if (first) outer else innerCorner,
            bottomStart = if (first) outer else innerCorner,
            topEnd = if (last) outer else innerCorner,
            bottomEnd = if (last) outer else innerCorner,
        )
    return androidx.compose.material3.ToggleButtonShapes(
        shape = shapeWith(inner),
        pressedShape = shapeWith(innerPressed),
        checkedShape =
            androidx.compose.foundation.shape
                .RoundedCornerShape(50),
    )
}
