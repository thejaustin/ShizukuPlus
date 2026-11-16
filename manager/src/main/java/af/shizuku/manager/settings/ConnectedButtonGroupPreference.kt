package af.shizuku.manager.settings

import af.shizuku.core.ui.compose.controlContainer
import af.shizuku.core.ui.compose.onControlContainer
import af.shizuku.manager.R
import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

/**
 * Single-choice preference rendered as an M3 Expressive connected button group (the replacement
 * for the segmented button). Stores its value as a String,
 * exactly like ListPreference, so it is a drop-in replacement for short (2-4 option) lists.
 *
 * XML:
 * ```
 * <af.shizuku.manager.settings.ConnectedButtonGroupPreference
 *     android:key="..."
 *     android:title="..."
 *     android:entries="@array/..."
 *     android:entryValues="@array/..."
 *     android:defaultValue="..." />
 * ```
 * Listen for changes with setOnPreferenceChangeListener; return false to veto.
 */
class ConnectedButtonGroupPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : Preference(context, attrs) {
        var entries: Array<CharSequence> = emptyArray()
        var entryValues: Array<CharSequence> = emptyArray()

        private val selected = mutableStateOf<String?>(null)

        /**
         * Optional colour feedback per option value: the colour the option would give its target
         * now (an outline on the button, and its fill once picked), plus colours to run through
         * when it gets picked, so a choice shows what it does. Null = plain button.
         */
        var optionColors: ((String) -> OptionColors?)? = null

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
            context.obtainStyledAttributes(attrs, R.styleable.ConnectedButtonGroupPreference).apply {
                getTextArray(R.styleable.ConnectedButtonGroupPreference_android_entries)?.let { entries = it }
                getTextArray(R.styleable.ConnectedButtonGroupPreference_android_entryValues)?.let { entryValues = it }
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
                timber.log.Timber.w("ConnectedButtonGroupPreference: ComposeView not found in holder (itemView is %s)", holder.itemView.javaClass.name)
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
                    val colorsFor = optionColors
                    ConnectedButtonGroup(
                        labels = labels,
                        selectedIndex = values.indexOf(selected.value),
                        enabled = enabled,
                        optionColors = colorsFor?.let { f -> values.map { f(it) } },
                        onSelect = { index ->
                            val newValue = values.getOrNull(index) ?: return@ConnectedButtonGroup
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
 * Reusable single-choice row (shared by ConnectedButtonGroupPreference and compound cards), drawn as an
 * M3 Expressive connected button group: the selected button morphs to a full pill and pressed
 * buttons squish on the theme's expressive spring, with a segment-tick haptic on every change.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectedButtonGroup(
    labels: List<String>,
    selectedIndex: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    optionColors: List<OptionColors?>? = null,
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
                val accent = optionColors?.getOrNull(index)
                val checked = index == selectedIndex
                val accentColor = accent?.let { animatedAccent(it, checked) }
                val scheme = androidx.compose.material3.MaterialTheme.colorScheme
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
                        if (accentColor != null) {
                            androidx.compose.material3.ToggleButtonDefaults.colors(
                                containerColor = scheme.controlContainer,
                                contentColor = scheme.onControlContainer,
                                checkedContainerColor = accentColor,
                                checkedContentColor = contentColorOn(accentColor),
                            )
                        } else {
                            androidx.compose.material3.ToggleButtonDefaults.colors(
                                containerColor = scheme.controlContainer,
                                contentColor = scheme.onControlContainer,
                            )
                        },
                    // Unpicked, the option still shows its colour as an outline.
                    border =
                        accentColor?.takeIf { !checked }?.let {
                            androidx.compose.foundation.BorderStroke(2.dp, it)
                        },
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

/** Colour feedback for one option of a [ConnectedButtonGroup]; see [ConnectedButtonGroupPreference.optionColors]. */
data class OptionColors(
    val current: androidx.compose.ui.graphics.Color,
    val showcase: List<androidx.compose.ui.graphics.Color> = emptyList(),
)

/**
 * The option's colour, springing in when it changes. When the option gets picked it first runs
 * through [OptionColors.showcase] (e.g. the running green, then the stopped red), then settles.
 */
@Composable
private fun animatedAccent(
    colors: OptionColors,
    checked: Boolean,
): androidx.compose.ui.graphics.Color {
    val anim = androidx.compose.runtime.remember { androidx.compose.animation.Animatable(colors.current) }
    val wasChecked = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(checked) }
    val scale =
        af.shizuku.manager.ShizukuSettings
            .getAnimationDurationScale()
    androidx.compose.runtime.LaunchedEffect(colors, checked) {
        val justPicked = checked && !wasChecked.value
        wasChecked.value = checked
        if (scale <= 0f) {
            anim.snapTo(colors.current)
            return@LaunchedEffect
        }
        val hold = (360 * scale).toLong()
        if (justPicked) {
            for (c in colors.showcase) {
                anim.animateTo(
                    c,
                    androidx.compose.animation.core
                        .tween((220 * scale).toInt()),
                )
                kotlinx.coroutines.delay(hold)
            }
        }
        anim.animateTo(
            colors.current,
            androidx.compose.animation.core
                .tween((220 * scale).toInt()),
        )
    }
    return anim.value
}

/** Black or white text, whichever reads better on [background]. */
private fun contentColorOn(background: androidx.compose.ui.graphics.Color) =
    if (androidx.core.graphics.ColorUtils.calculateLuminance(
            background.toArgb(),
        ) > 0.4
    ) {
        androidx.compose.ui.graphics.Color.Black
    } else {
        androidx.compose.ui.graphics.Color.White
    }

/**
 * Multi-select connected button group: each button turns its own option on or off, for a few
 * related switches that read better side by side. Not persisted itself; the screen supplies the
 * labels, reads the current state through [isChecked] and applies taps in [onToggle], usually by
 * driving the real (hidden) switch rows so their warnings, sync and backup rules still apply.
 */
class ConnectedToggleGroupPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : Preference(context, attrs) {
        var labels: List<String> = emptyList()
        var isChecked: (Int) -> Boolean = { false }
        var onToggle: (index: Int, checked: Boolean) -> Unit = { _, _ -> }

        private val checked = androidx.compose.runtime.mutableStateListOf<Boolean>()

        init {
            layoutResource = R.layout.preference_compose
            isSelectable = false
            isPersistent = false
        }

        /** Re-reads [isChecked], e.g. after a confirmation dialog changed one of the options. */
        fun refresh() {
            val now = labels.indices.map(isChecked)
            if (now != checked.toList()) {
                checked.clear()
                checked.addAll(now)
            }
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            val cv =
                (holder.itemView as? ComposeView)
                    ?: (holder.findViewById(R.id.preference_compose_view) as? ComposeView)
                    ?: return
            refresh()
            val enabled = isEnabled
            val prefIcon = icon
            val names = labels
            cv.setPreferenceContent {
                ComposePreferenceRow(
                    title = title,
                    summary = summary,
                    enabled = enabled,
                    icon = prefIcon,
                ) {
                    ConnectedToggleGroup(
                        labels = names,
                        checked = checked.toList(),
                        enabled = enabled,
                        onToggle = { index, value -> onToggle(index, value) },
                    )
                }
            }
        }
    }

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectedToggleGroup(
    labels: List<String>,
    checked: List<Boolean>,
    enabled: Boolean,
    onToggle: (index: Int, checked: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.key(labels) {
        androidx.compose.foundation.layout.Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement =
                androidx.compose.foundation.layout.Arrangement.spacedBy(
                    androidx.compose.material3.ButtonGroupDefaults.ConnectedSpaceBetween,
                ),
        ) {
            labels.forEachIndexed { index, label ->
                val on = checked.getOrElse(index) { false }
                androidx.compose.material3.ToggleButton(
                    checked = on,
                    onCheckedChange = { value ->
                        af.shizuku.manager.utils.HapticUtils
                            .segmentTick(view)
                        onToggle(index, value)
                    },
                    enabled = enabled,
                    colors =
                        androidx.compose.material3.ToggleButtonDefaults.colors(
                            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.controlContainer,
                            contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onControlContainer,
                        ),
                    shapes = connectedMorphShapes(index, labels.size),
                    contentPadding =
                        androidx.compose.foundation.layout
                            .PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .semantics { role = androidx.compose.ui.semantics.Role.Checkbox },
                ) {
                    // A check on each selected option, so "on" never depends on colour alone.
                    androidx.compose.animation.AnimatedVisibility(visible = on) {
                        androidx.compose.material3.Icon(
                            painter =
                                androidx.compose.ui.res
                                    .painterResource(R.drawable.ic_switch_check_16),
                            contentDescription = null,
                            modifier =
                                Modifier
                                    .padding(end = 4.dp)
                                    .size(16.dp),
                        )
                    }
                    Text(
                        label,
                        maxLines = 2,
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
    // md.comp.button-group.connected.small: inner corner = corner.small (8dp), pressed inner
    // corner = corner.extra-small (4dp), selected = 50%.
    val inner =
        androidx.compose.foundation.shape
            .CornerSize(8.dp)
    val innerPressed =
        androidx.compose.foundation.shape
            .CornerSize(4.dp)
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
