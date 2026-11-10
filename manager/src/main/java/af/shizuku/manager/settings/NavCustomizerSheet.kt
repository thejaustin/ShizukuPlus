package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.utils.HapticUtils
import af.shizuku.manager.widget.FloatingNavBar
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Bottom sheet allowing users to customize and reorder navigation bar destinations (2 to 5 items),
 * with real-time live preview, position swap controls, and quick presets (e.g. Home in the Center).
 */
class NavCustomizerSheet : BottomSheetDialogFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                PreferenceComposeTheme {
                    NavCustomizerContent()
                }
            }
        }

    @Composable
    private fun NavCustomizerContent() {
        val view = LocalView.current
        val activeKeys =
            remember {
                mutableStateListOf<String>().apply {
                    addAll(FloatingNavBar.getActiveTabs().map { it.key })
                }
            }

        var currentStyle by remember {
            mutableStateOf(FloatingNavBar.getStyle())
        }

        fun selectStyle(style: String) {
            HapticUtils.segmentTick(view)
            currentStyle = style
            FloatingNavBar.setStyle(style)
        }

        fun persist() {
            FloatingNavBar.setActiveTabOrder(activeKeys.toList())
        }

        fun moveUp(index: Int) {
            if (index > 0) {
                HapticUtils.segmentTick(view)
                val item = activeKeys.removeAt(index)
                activeKeys.add(index - 1, item)
                persist()
            }
        }

        fun moveDown(index: Int) {
            if (index < activeKeys.size - 1) {
                HapticUtils.segmentTick(view)
                val item = activeKeys.removeAt(index)
                activeKeys.add(index + 1, item)
                persist()
            }
        }

        fun removeTab(key: String) {
            if (activeKeys.size > 2) {
                HapticUtils.tap(view)
                activeKeys.remove(key)
                persist()
            }
        }

        fun addTab(key: String) {
            if (activeKeys.size < 5) {
                HapticUtils.tap(view)
                activeKeys.add(key)
                persist()
            }
        }

        fun applyPreset(preset: List<String>) {
            HapticUtils.segmentTick(view)
            activeKeys.clear()
            activeKeys.addAll(preset)
            persist()
        }

        val allTabs = FloatingNavBar.ALL_TABS
        val availableTabs = allTabs.filter { it.key !in activeKeys }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
        ) {
            SheetHeader(
                title = stringResource(R.string.nav_customizer_title),
                subtitle = stringResource(R.string.nav_customizer_subtitle),
            )

            // Preset Chips Row
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PresetChip(
                    label = stringResource(R.string.nav_customizer_preset_center_5),
                    selected = activeKeys == FloatingNavBar.PRESET_CENTER_5,
                    onClick = { applyPreset(FloatingNavBar.PRESET_CENTER_5) },
                )
                PresetChip(
                    label = stringResource(R.string.nav_customizer_preset_center_3),
                    selected = activeKeys == FloatingNavBar.PRESET_CENTER_3,
                    onClick = { applyPreset(FloatingNavBar.PRESET_CENTER_3) },
                )
                PresetChip(
                    label = stringResource(R.string.nav_customizer_preset_default),
                    selected = activeKeys == FloatingNavBar.PRESET_DEFAULT_4,
                    onClick = { applyPreset(FloatingNavBar.PRESET_DEFAULT_4) },
                )
                PresetChip(
                    label = stringResource(R.string.nav_customizer_preset_all),
                    selected = activeKeys == FloatingNavBar.PRESET_ALL_5,
                    onClick = { applyPreset(FloatingNavBar.PRESET_ALL_5) },
                )
                PresetChip(
                    label = stringResource(R.string.nav_customizer_preset_terminal),
                    selected = activeKeys == FloatingNavBar.PRESET_TERMINAL_5,
                    onClick = { applyPreset(FloatingNavBar.PRESET_TERMINAL_5) },
                )
            }

            Spacer(Modifier.height(8.dp))
            SheetSectionLabel(stringResource(R.string.settings_floating_nav_style_title))

            // Bar Style Chips Row
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PresetChip(
                    label = stringResource(R.string.settings_floating_nav_style_floating),
                    selected = currentStyle == FloatingNavBar.STYLE_FLOATING,
                    onClick = { selectStyle(FloatingNavBar.STYLE_FLOATING) },
                )
                PresetChip(
                    label = stringResource(R.string.settings_floating_nav_style_docked),
                    selected = currentStyle == FloatingNavBar.STYLE_DOCKED,
                    onClick = { selectStyle(FloatingNavBar.STYLE_DOCKED) },
                )
                PresetChip(
                    label = stringResource(R.string.settings_floating_nav_style_compact),
                    selected = currentStyle == FloatingNavBar.STYLE_COMPACT,
                    onClick = { selectStyle(FloatingNavBar.STYLE_COMPACT) },
                )
                PresetChip(
                    label = stringResource(R.string.settings_floating_nav_style_capsule),
                    selected = currentStyle == FloatingNavBar.STYLE_CAPSULE,
                    onClick = { selectStyle(FloatingNavBar.STYLE_CAPSULE) },
                )
            }

            Spacer(Modifier.height(8.dp))

            // Live Preview Card
            LiveBarPreview(keys = activeKeys.toList(), style = currentStyle)

            Spacer(Modifier.height(12.dp))

            // Active Tabs Section
            SheetSectionLabel(stringResource(R.string.nav_customizer_active_tabs, activeKeys.size))

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                activeKeys.forEachIndexed { index, key ->
                    val tab = FloatingNavBar.tabForKey(key) ?: return@forEachIndexed
                    val isCenter = activeKeys.size % 2 == 1 && index == activeKeys.size / 2

                    ActiveTabItem(
                        index = index,
                        totalCount = activeKeys.size,
                        isCenter = isCenter,
                        tab = tab,
                        canRemove = activeKeys.size > 2,
                        canMoveUp = index > 0,
                        canMoveDown = index < activeKeys.size - 1,
                        onMoveUp = { moveUp(index) },
                        onMoveDown = { moveDown(index) },
                        onRemove = { removeTab(key) },
                    )
                }
            }

            // Available Tabs Section
            if (availableTabs.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SheetSectionLabel(stringResource(R.string.nav_customizer_available_tabs))

                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    availableTabs.forEach { tab ->
                        AvailableTabItem(
                            tab = tab,
                            canAdd = activeKeys.size < 5,
                            onAdd = { addTab(tab.key) },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun PresetChip(
        label: String,
        selected: Boolean,
        onClick: () -> Unit,
    ) {
        AssistChip(
            onClick = onClick,
            label = {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            },
            colors =
                AssistChipDefaults.assistChipColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    labelColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                ),
            border =
                if (selected) {
                    AssistChipDefaults.assistChipBorder(
                        enabled = true,
                        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    )
                } else {
                    null
                },
            shape = RoundedCornerShape(12.dp),
        )
    }

    @Composable
    private fun LiveBarPreview(
        keys: List<String>,
        style: String,
    ) {
        val previewShape =
            when (style) {
                FloatingNavBar.STYLE_DOCKED ->
                    RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 20.dp,
                        bottomStart = 0.dp,
                        bottomEnd = 0.dp,
                    )
                FloatingNavBar.STYLE_CAPSULE -> CircleShape
                FloatingNavBar.STYLE_COMPACT -> RoundedCornerShape(18.dp)
                else -> RoundedCornerShape(26.dp)
            }

        val previewModifier =
            when (style) {
                FloatingNavBar.STYLE_DOCKED ->
                    Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                FloatingNavBar.STYLE_CAPSULE ->
                    Modifier
                        .width(300.dp)
                        .height(56.dp)
                FloatingNavBar.STYLE_COMPACT ->
                    Modifier
                        .width(260.dp)
                        .height(54.dp)
                else ->
                    Modifier
                        .fillMaxWidth(0.92f)
                        .height(60.dp)
            }

        val tabShape =
            when (style) {
                FloatingNavBar.STYLE_CAPSULE -> CircleShape
                FloatingNavBar.STYLE_COMPACT -> RoundedCornerShape(12.dp)
                else -> RoundedCornerShape(18.dp)
            }
        val iconSize = if (style == FloatingNavBar.STYLE_COMPACT) 18.dp else 20.dp

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = previewModifier,
                shape = previewShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                border =
                    androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    ),
                tonalElevation = 2.dp,
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    keys.forEach { key ->
                        val tab = FloatingNavBar.tabForKey(key) ?: return@forEach
                        val isSelected = key == "home" || (keys.none { it == "home" } && key == keys.firstOrNull())

                        Box(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(tabShape)
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    painter = painterResource(tab.iconRes),
                                    contentDescription = null,
                                    tint =
                                        if (isSelected) {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                        },
                                    modifier = Modifier.size(iconSize),
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = stringResource(tab.titleRes),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color =
                                        if (isSelected) {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ActiveTabItem(
        index: Int,
        totalCount: Int,
        isCenter: Boolean,
        tab: FloatingNavBar.TabSpec,
        canRemove: Boolean,
        canMoveUp: Boolean,
        canMoveDown: Boolean,
        onMoveUp: () -> Unit,
        onMoveDown: () -> Unit,
        onRemove: () -> Unit,
    ) {
        val view = LocalView.current
        var accumulatedDragY by remember { mutableFloatStateOf(0f) }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border =
                if (isCenter) {
                    androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    )
                } else {
                    null
                },
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Drag handle for smooth vertical drag-to-reorder
                Box(
                    modifier =
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .pointerInput(tab.key) {
                                detectDragGestures(
                                    onDragStart = {
                                        accumulatedDragY = 0f
                                        HapticUtils.gestureStart(view)
                                    },
                                    onDragEnd = {
                                        accumulatedDragY = 0f
                                        HapticUtils.gestureEnd(view)
                                    },
                                    onDragCancel = {
                                        accumulatedDragY = 0f
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        accumulatedDragY += dragAmount.y
                                        val threshold = 38.dp.toPx()
                                        if (accumulatedDragY > threshold && index < totalCount - 1) {
                                            onMoveDown()
                                            accumulatedDragY -= threshold
                                        } else if (accumulatedDragY < -threshold && index > 0) {
                                            onMoveUp()
                                            accumulatedDragY += threshold
                                        }
                                    },
                                )
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_drag_handle),
                        contentDescription = "Drag to reorder",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp),
                    )
                }

                // Index or Center badge
                Box(
                    modifier =
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                if (isCenter) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = (index + 1).toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isCenter) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Tab Icon container
                Box(
                    modifier =
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(tab.iconRes),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }

                // Title & Description
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(tab.titleRes),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (isCenter) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            ) {
                                Text(
                                    text = stringResource(R.string.nav_customizer_center_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    Text(
                        text = stringResource(tab.descRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Move Up
                IconButton(
                    onClick = onMoveUp,
                    enabled = canMoveUp,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_outline_arrow_upward_24),
                        contentDescription = "Move up",
                        tint = if (canMoveUp) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                        modifier = Modifier.size(18.dp),
                    )
                }

                // Move Down
                IconButton(
                    onClick = onMoveDown,
                    enabled = canMoveDown,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_outline_arrow_downward_24),
                        contentDescription = "Move down",
                        tint = if (canMoveDown) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                        modifier = Modifier.size(18.dp),
                    )
                }

                // Remove Tab
                IconButton(
                    onClick = onRemove,
                    enabled = canRemove,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close_24),
                        contentDescription = "Remove tab",
                        tint = if (canRemove) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.25f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun AvailableTabItem(
        tab: FloatingNavBar.TabSpec,
        canAdd: Boolean,
        onAdd: () -> Unit,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Tab Icon container
                Box(
                    modifier =
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(tab.iconRes),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }

                // Title & Description
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(tab.titleRes),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(tab.descRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Add button
                IconButton(
                    onClick = onAdd,
                    enabled = canAdd,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add_24),
                        contentDescription = "Add tab",
                        tint = if (canAdd) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "nav_customizer_sheet"

        fun show(fm: FragmentManager) {
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            NavCustomizerSheet().show(fm, TAG)
        }
    }
}
