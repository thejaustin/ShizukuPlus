package af.shizuku.manager.home.compose

import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.RecyclerView
import af.shizuku.core.ui.compose.Button
import af.shizuku.core.ui.compose.ButtonSize
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    isEditMode: Boolean,
    isOneHanded: Boolean,
    showEmptyState: Boolean,
    isOneUi: Boolean = ShizukuSettings.isOneUiThemeEnabled(),
    onStopClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onHelpClick: () -> Unit,
    onDoneClick: () -> Unit,
    onRestoreHomeCards: () -> Unit,
    recyclerViewProvider: (Context, PaddingValues) -> RecyclerView
) {
    val configuration = LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp.dp

    // When one-handed or One UI: expanded viewing area occupies ~36% of screen height,
    // letting the thumb reach the interaction zone below. Otherwise stay at a flat 64dp bar.
    val expandedHeight = if (isOneHanded || isOneUi) {
        (screenHeightDp * 0.36f).coerceIn(220.dp, 300.dp)
    } else {
        64.dp
    }
    val collapsedHeight = 64.dp

    val density = LocalDensity.current
    val expandedHeightPx = with(density) { expandedHeight.toPx() }
    val collapsedHeightPx = with(density) { collapsedHeight.toPx() }
    val heightOffsetLimit = -(expandedHeightPx - collapsedHeightPx)

    val topAppBarState = rememberTopAppBarState()
    // Override the limit each frame so the custom expanded height drives collapse instead of
    // LargeTopAppBar's fixed internal size.
    SideEffect {
        if (topAppBarState.heightOffsetLimit != heightOffsetLimit) {
            topAppBarState.heightOffsetLimit = heightOffsetLimit
        }
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topAppBarState)

    // Snap fully open or fully closed when the user lifts their finger.
    val isScrollIdle = remember { mutableStateOf(true) }
    LaunchedEffect(isScrollIdle.value) {
        if (isScrollIdle.value) {
            val state = scrollBehavior.state
            val fraction = state.collapsedFraction
            if (fraction > 0.001f && fraction < 0.999f) {
                val target = if (fraction >= 0.5f) state.heightOffsetLimit else 0f
                Animatable(state.heightOffset).animateTo(
                    target,
                    spring(stiffness = Spring.StiffnessMediumLow)
                ) { state.heightOffset = value }
            }
        }
    }

    // In flat (non-one-handed, non-OneUI) mode treat the bar as always collapsed so the
    // title never partially animates.
    val fraction = if (isOneHanded || isOneUi) scrollBehavior.state.collapsedFraction else 1f
    val curvedFraction = FastOutSlowInEasing.transform(fraction)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(expandedHeight + with(density) { scrollBehavior.state.heightOffset.toDp() }),
                color = if (fraction > 0.85f) {
                    if (ShizukuSettings.isBlurUiEnabled())
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
                    else
                        MaterialTheme.colorScheme.surfaceContainer
                } else {
                    Color.Transparent
                }
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Action icons pinned at top-end inside the 64dp collapsed row
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .height(collapsedHeight)
                            .padding(end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isEditMode) {
                            TextButton(onClick = onDoneClick) {
                                Text(
                                    text = stringResource(R.string.home_edit_mode_done),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            IconButton(onClick = onStopClick) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_close_24),
                                    contentDescription = stringResource(id = R.string.action_stop)
                                )
                            }
                            IconButton(onClick = onSettingsClick) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_settings_outline_24),
                                    contentDescription = stringResource(id = R.string.settings_title)
                                )
                            }
                            IconButton(onClick = onHelpClick) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_help_outline_24),
                                    contentDescription = stringResource(id = R.string.settings_plus_learn_more)
                                )
                            }
                        }
                    }

                    // Title: slides from center (expanded) → start (collapsed) via BiasAlignment.
                    // horizontalBias: 0 = center, -1 = start.
                    val horizontalBias = -curvedFraction
                    val startPadding = lerp(start = 24.dp, stop = 20.dp, fraction = curvedFraction)
                    val endPadding = lerp(start = 24.dp, stop = 140.dp, fraction = curvedFraction)
                    val titleFontSize = lerp(
                        start = if (isOneUi) 32.sp else 28.sp,
                        stop = 20.sp,
                        fraction = curvedFraction
                    )
                    val titleFontWeight = if (isOneUi) {
                        if (curvedFraction > 0.65f) FontWeight.Bold else FontWeight.ExtraBold
                    } else {
                        if (curvedFraction > 0.65f) FontWeight.SemiBold else FontWeight.Normal
                    }
                    val titleLetterSpacing = if (isOneUi) {
                        lerp((-0.6).sp, (-0.2).sp, curvedFraction)
                    } else {
                        0.sp
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = startPadding,
                                end = endPadding,
                                top = if (curvedFraction > 0.8f) 0.dp else 24.dp
                            ),
                        contentAlignment = BiasAlignment(horizontalBias, 0f)
                    ) {
                        Text(
                            text = if (isEditMode) stringResource(R.string.home_edit_mode_title)
                            else stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = titleFontWeight,
                                fontSize = titleFontSize,
                                letterSpacing = titleLetterSpacing
                            ),
                            textAlign = if (curvedFraction > 0.5f) TextAlign.Start else TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Hairline divider fades in as the bar collapses
                    if (curvedFraction > 0.4f) {
                        val dividerAlpha = ((curvedFraction - 0.4f) / 0.6f).coerceIn(0f, 1f)
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(0.8.dp)
                                .graphicsLayer { alpha = dividerAlpha }
                                .background(
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                                )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        val adjustedPadding = PaddingValues(
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding() + 72.dp
        )
        AnimatedGradientBackground {
            Box(modifier = Modifier.fillMaxSize()) {
                if (showEmptyState) {
                    Box(modifier = Modifier.padding(adjustedPadding)) {
                        HomeEmptyState(onRestoreHomeCards)
                    }
                } else {
                    AndroidView(
                        factory = { context ->
                            recyclerViewProvider(context, adjustedPadding).also { rv ->
                                (rv.parent as? android.view.ViewGroup)?.removeView(rv)
                                rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                        val state = scrollBehavior.state
                                        val limit = state.heightOffsetLimit
                                        when {
                                            dy > 0 -> state.heightOffset =
                                                (state.heightOffset - dy).coerceAtLeast(limit)
                                            dy < 0 && !recyclerView.canScrollVertically(-1) ->
                                                state.heightOffset =
                                                    (state.heightOffset - dy).coerceAtMost(0f)
                                        }
                                        state.contentOffset -= dy
                                    }
                                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                                        isScrollIdle.value = (newState == RecyclerView.SCROLL_STATE_IDLE)
                                    }
                                })
                            }
                        },
                        update = { view -> recyclerViewProvider(view.context, adjustedPadding) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
fun AnimatedGradientBackground(content: @Composable () -> Unit) {
    val animationsEnabled = ShizukuSettings.isExpressiveAnimationsEnabled()
    val infiniteTransition = rememberInfiniteTransition()
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (animationsEnabled) 1f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(15000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    val color1 = MaterialTheme.colorScheme.primary.copy(alpha = 0.03f + 0.05f * alpha)
    val color2 = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.03f + 0.05f * (1f - alpha))
    val color3 = MaterialTheme.colorScheme.secondary.copy(alpha = 0.03f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.sweepGradient(
                    colors = listOf(color1, color2, color3, color1),
                    center = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                )
            )
    ) {
        content()
    }
}

@Composable
fun HomeEmptyState(onRestoreHomeCards: () -> Unit) {
    val animationsEnabled = ShizukuSettings.isExpressiveAnimationsEnabled()
    val infiniteTransition = rememberInfiniteTransition()
    val floatAnim by infiniteTransition.animateFloat(
        initialValue = -8f,
        targetValue = if (animationsEnabled) 8f else -8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_empty_home_24),
            contentDescription = null,
            modifier = Modifier
                .size(72.dp)
                .offset(y = floatAnim.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.empty_state_title_no_home_cards),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.empty_state_description_no_home_cards),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            size = ButtonSize.Medium,
            onClick = onRestoreHomeCards
        ) {
            Text(stringResource(R.string.empty_state_action_restore_home_cards))
        }
    }
}
