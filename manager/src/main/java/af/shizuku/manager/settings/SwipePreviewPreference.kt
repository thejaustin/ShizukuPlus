package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.management.SwipeActionStyle
import af.shizuku.manager.utils.HapticUtils
import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Top of the Swipe actions settings: a row from the app list that swipes one way and then the
 * other on its own, drawn with the colours and icons a real swipe shows. With the developer
 * option on, it becomes a sandbox: drag the row yourself and pick what that swipe does.
 */
class SwipePreviewPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : Preference(context, attrs),
        SharedPreferences.OnSharedPreferenceChangeListener {
        private val right = mutableStateOf(ShizukuSettings.getSwipeRightAction())
        private val left = mutableStateOf(ShizukuSettings.getSwipeLeftAction())
        private val sandbox = mutableStateOf(false)
        private val enabled = mutableStateOf(ShizukuSettings.isSwipeGesturesEnabled())

        init {
            layoutResource = R.layout.preference_compose
            isSelectable = false
            sync()
        }

        private fun sync() {
            right.value = ShizukuSettings.getSwipeRightAction()
            left.value = ShizukuSettings.getSwipeLeftAction()
            sandbox.value = ShizukuSettings.isSwipeSettingsSandboxEnabled()
            enabled.value = ShizukuSettings.isSwipeGesturesEnabled()
        }

        override fun onAttached() {
            super.onAttached()
            ShizukuSettings.getPreferences()?.registerOnSharedPreferenceChangeListener(this)
            sync()
        }

        override fun onDetached() {
            super.onDetached()
            ShizukuSettings.getPreferences()?.unregisterOnSharedPreferenceChangeListener(this)
        }

        override fun onSharedPreferenceChanged(
            prefs: SharedPreferences?,
            key: String?,
        ) {
            when (key) {
                ShizukuSettings.Keys.KEY_SWIPE_RIGHT_ACTION,
                ShizukuSettings.Keys.KEY_SWIPE_LEFT_ACTION,
                ShizukuSettings.Keys.KEY_SWIPE_SETTINGS_SANDBOX,
                ShizukuSettings.Keys.KEY_SWIPE_GESTURES_ENABLED,
                -> sync()
            }
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            val cv =
                holder.findViewById(R.id.preference_compose_view) as? ComposeView
                    ?: holder.itemView as? ComposeView ?: return
            cv.setPreferenceContent {
                if (!enabled.value) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.swipe_actions_disabled_summary),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                } else if (sandbox.value) {
                    SwipeSandbox(
                        right = right.value,
                        left = left.value,
                        onPick = { direction, value ->
                            val key =
                                if (direction > 0) {
                                    ShizukuSettings.Keys.KEY_SWIPE_RIGHT_ACTION
                                } else {
                                    ShizukuSettings.Keys.KEY_SWIPE_LEFT_ACTION
                                }
                            ShizukuSettings
                                .getPreferences()
                                ?.edit()
                                ?.putString(key, value)
                                ?.apply()
                        },
                    )
                } else {
                    SwipeAutoPreview(right = right.value, left = left.value)
                }
            }
        }
    }

/**
 * One app-list row with the swipe background behind it, laid out like `app_list_item` and drawn
 * like `ApplicationManagementActivity.drawSwipeBackground`. [dx] is the row's offset in pixels.
 * It's read only while laying out and drawing, so a running swipe doesn't recompose the row on
 * every frame; only the direction and crossing the threshold do.
 */
@Composable
internal fun SwipeDemoRow(
    dx: () -> Float,
    widthPx: Float,
    right: String,
    left: String,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 72.dp,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val appIcon =
        remember(context) {
            runCatching {
                context.packageManager
                    .getApplicationIcon(context.packageName)
                    .toBitmap(144, 144)
                    .asImageBitmap()
            }.getOrNull()
        }
    val appLabel = remember(context) { context.applicationInfo.loadLabel(context.packageManager).toString() }
    val threshold = SwipeActionStyle.THRESHOLD * widthPx
    // The latest lambda: a caller may pass a new one holding a fixed value (the picker sheet),
    // which the remembered derived states would otherwise never see.
    val currentDx by rememberUpdatedState(dx)
    val toRight by remember { derivedStateOf { currentDx() >= 0f } }
    val past by remember(threshold) { derivedStateOf { threshold > 0f && abs(currentDx()) >= threshold } }
    // Pops on a spring as the swipe crosses the line, as the list's badge does.
    val pop by animateFloatAsState(if (past) BADGE_POP else 1f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "badgePop")

    fun progress() = if (threshold > 0f) (abs(dx()) / threshold).coerceIn(0f, 1f) else 0f

    Box(modifier = modifier.fillMaxWidth().height(rowHeight)) {
        val spec = SwipeActionStyle.of(if (toRight) right else left)
        val on = spec.role.onContainer(cs)
        Box(
            modifier =
                Modifier
                    .align(if (toRight) Alignment.CenterStart else Alignment.CenterEnd)
                    .layout { measurable, constraints ->
                        val w = abs(dx()).roundToInt().coerceAtMost(constraints.maxWidth)
                        val placeable = measurable.measure(Constraints.fixed(w, constraints.maxHeight))
                        layout(w, constraints.maxHeight) { placeable.place(0, 0) }
                    }.graphicsLayer { alpha = if (abs(dx()) >= 4f && spec.value != SwipeActionStyle.NONE) 1f else 0f }
                    .fillMaxHeight()
                    .padding(vertical = 2.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(spec.role.container(cs)),
        ) {
            Row(
                modifier =
                    Modifier
                        .align(if (toRight) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val label: @Composable () -> Unit = {
                    if (past) {
                        Text(
                            text = stringResource(spec.labelRes),
                            color = on,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                    }
                }
                if (!toRight) label()
                Box(
                    modifier =
                        Modifier
                            .size(44.dp)
                            .graphicsLayer {
                                val scale = (0.75f + 0.25f * progress()) * pop
                                scaleX = scale
                                scaleY = scale
                            }.clip(CircleShape)
                            .background(on.copy(alpha = if (past) 0.2f else 0.1f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(spec.iconRes),
                        contentDescription = null,
                        tint = on,
                        modifier = Modifier.size(24.dp).graphicsLayer { alpha = 0.35f + 0.65f * progress() },
                    )
                }
                if (toRight) label()
            }
        }

        Row(
            modifier =
                Modifier
                    .offset { IntOffset(dx().roundToInt(), 0) }
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (appIcon != null) {
                Image(bitmap = appIcon, contentDescription = null, modifier = Modifier.size(48.dp))
            } else {
                Box(Modifier.size(48.dp).clip(CircleShape).background(cs.primaryContainer))
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(appLabel, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1)
                Text(
                    context.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SwipeAutoPreview(
    right: String,
    left: String,
) {
    val animate = remember { ShizukuSettings.getAnimationDurationScale() > 0f }
    val motion = MaterialTheme.motionScheme
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val widthPx = constraints.maxWidth.toFloat()
            val offset = remember { Animatable(0f) }
            LaunchedEffect(right, left, widthPx, animate) {
                val directions =
                    listOf(1f to right, -1f to left).filter { it.second != SwipeActionStyle.NONE }
                if (directions.isEmpty()) {
                    offset.snapTo(0f)
                    awaitCancellation()
                }
                if (!animate) {
                    // Still frame for reduced motion: the first enabled direction, past the line.
                    offset.snapTo(directions.first().first * widthPx * 0.5f)
                    awaitCancellation()
                }
                offset.snapTo(0f)
                while (true) {
                    for ((sign, _) in directions) {
                        delay(700)
                        offset.animateTo(sign * widthPx * 0.5f, motion.slowSpatialSpec())
                        delay(900)
                        offset.animateTo(0f, motion.defaultSpatialSpec())
                    }
                    delay(500)
                }
            }
            SwipeDemoRow(dx = { offset.value }, widthPx = widthPx, right = right, left = left)
        }
        Text(
            text = stringResource(R.string.swipe_preview_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwipeSandbox(
    right: String,
    left: String,
    onPick: (direction: Int, value: String) -> Unit,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val motion = MaterialTheme.motionScheme
    var picking by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
        Text(
            text = stringResource(R.string.swipe_sandbox_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val widthPx = constraints.maxWidth.toFloat()
            val threshold = SwipeActionStyle.THRESHOLD * widthPx
            val offset = remember { Animatable(0f) }
            SwipeDemoRow(
                dx = { offset.value },
                widthPx = widthPx,
                right = right,
                left = left,
                modifier =
                    Modifier.pointerInput(widthPx) {
                        // Tracked here, not read back from the Animatable: several drag events
                        // can arrive before a launched snapTo has run.
                        var x = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { x = offset.value },
                            onDragEnd = {
                                if (abs(x) >= threshold) {
                                    HapticUtils.success(view)
                                    picking = if (x > 0) 1 else -1
                                }
                                x = 0f
                                scope.launch { offset.animateTo(0f, motion.defaultSpatialSpec()) }
                            },
                            onDragCancel = {
                                x = 0f
                                scope.launch { offset.animateTo(0f, motion.fastSpatialSpec()) }
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                val next = (x + dragAmount).coerceIn(-widthPx * 0.7f, widthPx * 0.7f)
                                val wasPast = abs(x) >= threshold
                                val isPast = abs(next) >= threshold
                                if (isPast && !wasPast) HapticUtils.gestureThreshold(view)
                                if (!isPast && wasPast) HapticUtils.gestureThresholdRelease(view)
                                x = next
                                scope.launch { offset.snapTo(next) }
                            },
                        )
                    },
            )
        }
        AnimatedVisibility(
            visible = picking != 0,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            val current = if (picking > 0) right else left
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(if (picking > 0) R.string.swipe_sandbox_right_will else R.string.swipe_sandbox_left_will),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SwipeActionStyle.all.forEach { spec ->
                        val selected = spec.value == current
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    HapticUtils.segmentTick(view)
                                    onPick(picking, spec.value)
                                }
                            },
                            label = { Text(stringResource(spec.labelRes)) },
                            leadingIcon = {
                                Icon(painterResource(spec.iconRes), contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                        )
                    }
                }
            }
        }
    }
}

/** How far the swipe badge grows once the swipe will trigger, matching the app list. */
private const val BADGE_POP = 1.15f
