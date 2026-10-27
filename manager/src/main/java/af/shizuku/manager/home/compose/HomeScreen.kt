package af.shizuku.manager.home.compose

import af.shizuku.core.ui.compose.Button
import af.shizuku.core.ui.compose.ButtonSize
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.EnvironmentUtils
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    isEditMode: Boolean,
    isOneHanded: Boolean,
    showEmptyState: Boolean,
    isOneUi: Boolean = ShizukuSettings.isOneUiThemeEnabled(),
    isServerRunning: Boolean = false,
    onStopClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onHelpClick: () -> Unit,
    onDoneClick: () -> Unit,
    onRestoreHomeCards: () -> Unit,
    recyclerViewProvider: (Context, PaddingValues) -> RecyclerView,
) {
    val configuration = LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp.dp
    val context = LocalContext.current
    val appDisplayName = remember(context) { ShizukuSettings.getCustomAppName(context) }
    val isDropIn = remember(context) { EnvironmentUtils.isDropIn(context) }
    val animationsEnabled = ShizukuSettings.isExpressiveAnimationsEnabled()
    // Header motion runs on the theme's MotionScheme springs; 0 animation scale (Expressive
    // animations or system animations off) snaps.
    val motionOn = ShizukuSettings.scaledAnimationDuration(200) > 0
    val motionScheme = MaterialTheme.motionScheme

    // Status bar height — must be included in the Surface height so content sits below it,
    // not behind it. The inner Box gets windowInsetsPadding(statusBars) to push all content
    // (icons, title) below the status bar without shrinking the available content area.
    // Use safeDrawing (= systemBars ∪ displayCutout) so OEMs like Xiaomi HyperOS that report
    // statusBars as 0 still give us the correct top offset and the icons remain clickable.
    val statusBarPadding = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()

    // When one-handed or One UI: expanded viewing area occupies ~38% of screen height,
    // letting the thumb reach the interaction zone below. Otherwise stay at a flat 64dp bar.
    val expandedHeight =
        if (isOneHanded || isOneUi) {
            (screenHeightDp * 0.38f).coerceIn(240.dp, 360.dp)
        } else {
            64.dp
        }
    val collapsedHeight = 64.dp

    val density = LocalDensity.current
    // Include status bar in the px values so the offset limit is calculated against the full
    // on-screen bar height (content + status bar), keeping collapse math correct.
    val expandedHeightPx = with(density) { (expandedHeight + statusBarPadding).toPx() }
    val collapsedHeightPx = with(density) { (collapsedHeight + statusBarPadding).toPx() }
    val heightOffsetLimit = -(expandedHeightPx - collapsedHeightPx)

    val topAppBarState = rememberTopAppBarState()
    // Override the limit each frame so the custom expanded height drives collapse instead of
    // LargeTopAppBar's fixed internal size.
    SideEffect {
        if (topAppBarState.heightOffsetLimit != heightOffsetLimit) {
            topAppBarState.heightOffsetLimit = heightOffsetLimit
            // Re-coerce the existing offset to the new limit via the setter.
            // heightOffset's getter returns the raw stored float without coercion; only the
            // setter enforces [heightOffsetLimit, 0f]. Without this reassignment, switching
            // from one-handed (large bar) to flat mode while scrolled down leaves heightOffset
            // far below the new limit, making the next Compose frame produce a negative Surface
            // height that throws "Padding must be non-negative".
            topAppBarState.heightOffset = topAppBarState.heightOffset
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
                    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
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
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            (expandedHeight + statusBarPadding + with(density) { scrollBehavior.state.heightOffset.toDp() })
                                .coerceAtLeast(collapsedHeight + statusBarPadding),
                        ),
                color =
                    run {
                        val barAlpha = ((fraction - 0.6f) / 0.35f).coerceIn(0f, 1f)
                        if (ShizukuSettings.isBlurUiEnabled()) {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.82f * barAlpha)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = barAlpha)
                        }
                    },
            ) {
                // Use pre-computed statusBarPadding (captured before the Scaffold) rather than
                // windowInsetsPadding(WindowInsets.statusBars) inside the topBar slot. Scaffold
                // in Material3 1.4+ consumes contentWindowInsets before composing its slots, so
                // WindowInsets.statusBars inside the topBar returns 0, hiding the action buttons
                // behind the status bar on Android 17+ (#528).
                val layoutDirection = LocalLayoutDirection.current
                val safeHorizontalInsets = WindowInsets.safeDrawing.asPaddingValues()
                val startPaddingInset = safeHorizontalInsets.calculateStartPadding(layoutDirection)
                val endPaddingInset = safeHorizontalInsets.calculateEndPadding(layoutDirection)
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(
                                top = statusBarPadding,
                                start = startPaddingInset,
                                end = endPaddingInset,
                            ),
                ) {
                    // Action icons pinned at top-end inside the 64dp collapsed row

                    AnimatedContent(
                        targetState = isEditMode,
                        transitionSpec = {
                            val enter =
                                if (motionOn) {
                                    fadeIn(motionScheme.fastEffectsSpec())
                                } else {
                                    EnterTransition.None
                                }
                            val exit =
                                if (motionOn) {
                                    fadeOut(motionScheme.fastEffectsSpec())
                                } else {
                                    ExitTransition.None
                                }
                            (enter togetherWith exit) using
                                SizeTransform(
                                    clip = false,
                                    sizeAnimationSpec = { _, _ -> if (motionOn) motionScheme.defaultSpatialSpec() else snap() },
                                )
                        },
                        contentAlignment = Alignment.CenterEnd,
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                // No backdrop: a transparent->surface gradient drew a grey block on
                                // black/AMOLED pages, and the title already reserves end padding.
                                .height(collapsedHeight),
                        label = "topBarActions",
                    ) { editMode ->
                        Row(
                            modifier = Modifier.padding(end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (editMode) {
                                IconButton(onClick = onRestoreHomeCards) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_restore_24),
                                        contentDescription = stringResource(id = R.string.home_restore_default),
                                    )
                                }
                                TextButton(onClick = onDoneClick) {
                                    Text(
                                        text = stringResource(R.string.home_edit_mode_done),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            } else {
                                // Top corner icons adapt contextually:
                                // Stop is only shown when the service is actively running.
                                // Help / Learn More is shown when stopped.
                                // Settings is always available.
                                if (isServerRunning) {
                                    IconButton(onClick = onStopClick) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_close_24),
                                            contentDescription = stringResource(id = R.string.action_stop),
                                        )
                                    }
                                } else {
                                    IconButton(onClick = onHelpClick) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_help_outline_24),
                                            contentDescription = stringResource(id = R.string.settings_plus_learn_more),
                                        )
                                    }
                                }
                                IconButton(onClick = onSettingsClick) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_settings_outline_24),
                                        contentDescription = stringResource(id = R.string.settings_title),
                                    )
                                }
                            }
                        }
                    }

                    // Title: slides from center (expanded) → start (collapsed) via BiasAlignment.
                    // horizontalBias: 0 = center, -1 = start.
                    val horizontalBias = -curvedFraction
                    val startPadding = lerp(start = 24.dp, stop = 20.dp, fraction = curvedFraction)
                    val endPaddingStop by animateDpAsState(
                        targetValue = if (isEditMode) 120.dp else 96.dp,
                        animationSpec = if (motionOn) motionScheme.defaultSpatialSpec() else snap(),
                        label = "titleEndPaddingStop",
                    )
                    val endPadding = lerp(start = 24.dp, stop = endPaddingStop, fraction = curvedFraction)
                    val titleFontSize =
                        lerp(
                            start = if (isOneUi) 36.sp else 28.sp,
                            stop = 20.sp,
                            fraction = curvedFraction,
                        )
                    val titleFontWeight =
                        if (isOneUi) {
                            if (curvedFraction > 0.65f) FontWeight.Bold else FontWeight.ExtraBold
                        } else {
                            if (curvedFraction > 0.65f) FontWeight.SemiBold else FontWeight.Normal
                        }
                    val titleLetterSpacing =
                        if (isOneUi) {
                            lerp((-0.6).sp, (-0.2).sp, curvedFraction)
                        } else {
                            0.sp
                        }

                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(
                                    start = startPadding,
                                    end = endPadding,
                                    top = lerp(start = if (isOneUi) 32.dp else 24.dp, stop = 0.dp, fraction = curvedFraction),
                                ),
                        contentAlignment = BiasAlignment(horizontalBias, 0f),
                    ) {
                        Column(
                            horizontalAlignment = if (curvedFraction > 0.5f) Alignment.Start else Alignment.CenterHorizontally,
                        ) {
                            Crossfade(
                                targetState = isEditMode,
                                animationSpec = if (motionOn) motionScheme.defaultEffectsSpec() else snap(),
                                label = "titleContent",
                            ) { editMode ->
                                if (editMode) {
                                    Text(
                                        text = stringResource(R.string.home_edit_mode_title),
                                        style =
                                            MaterialTheme.typography.headlineLarge.copy(
                                                fontWeight = titleFontWeight,
                                                fontSize = titleFontSize,
                                                letterSpacing = titleLetterSpacing,
                                            ),
                                        textAlign = if (curvedFraction > 0.5f) TextAlign.Start else TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                } else if (isDropIn) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = if (curvedFraction > 0.5f) Arrangement.Start else Arrangement.Center,
                                    ) {
                                        Text(
                                            text = if (appDisplayName != stringResource(R.string.app_name)) appDisplayName else stringResource(R.string.brand_shizuku_plus),
                                            modifier = Modifier.weight(1f, fill = false),
                                            style =
                                                MaterialTheme.typography.headlineLarge.copy(
                                                    fontWeight = titleFontWeight,
                                                    fontSize = titleFontSize,
                                                    letterSpacing = titleLetterSpacing,
                                                ),
                                            textAlign = if (curvedFraction > 0.5f) TextAlign.Start else TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(modifier = Modifier.width(lerp(8.dp, 6.dp, curvedFraction)))
                                        Surface(
                                            shape = RoundedCornerShape(100),
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                            tonalElevation = 1.dp,
                                        ) {
                                            Text(
                                                text = stringResource(R.string.brand_drop_in),
                                                style =
                                                    MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        letterSpacing = 0.4.sp,
                                                        fontSize = 11.sp,
                                                    ),
                                                modifier =
                                                    Modifier.padding(
                                                        horizontal = lerp(8.dp, 6.dp, curvedFraction),
                                                        vertical = lerp(3.dp, 2.dp, curvedFraction),
                                                    ),
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        text = appDisplayName,
                                        style =
                                            MaterialTheme.typography.headlineLarge.copy(
                                                fontWeight = titleFontWeight,
                                                fontSize = titleFontSize,
                                                letterSpacing = titleLetterSpacing,
                                            ),
                                        textAlign = if (curvedFraction > 0.5f) TextAlign.Start else TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            } // end Crossfade

                            // No status subtitle here: the server status card directly below already says
                            // whether Shizuku is running, so the header repeated it word for word.
                        }
                    }

                    // Hairline divider fades in as the bar collapses
                    if (curvedFraction > 0.4f) {
                        val dividerAlpha = ((curvedFraction - 0.4f) / 0.6f).coerceIn(0f, 1f)
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(0.8.dp)
                                    .graphicsLayer { alpha = dividerAlpha }
                                    .background(
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                                    ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        // innerPadding.top decreases as the bar collapses during scroll. Using it directly
        // causes recyclerViewProvider to call setPadding with shrinking values every frame,
        // which fights the RecyclerView's own scroll and pulls content upward mid-scroll.
        // Fix: track the maximum top seen — that's the fully-expanded bar height + insets,
        // which is the correct permanent RecyclerView top padding. It only rises (never drops
        // during scroll), so the RecyclerView padding is stable once the bar is first rendered.
        val stableTop = remember(expandedHeight) { mutableStateOf(0.dp) }
        SideEffect {
            val t = innerPadding.calculateTopPadding()
            if (t > stableTop.value) stableTop.value = t
        }
        val navClearance =
            if (ShizukuSettings.isFloatingNavBarEnabled()) {
                160.dp
            } else {
                72.dp
            }
        val adjustedPadding =
            PaddingValues(
                top = stableTop.value,
                bottom = innerPadding.calculateBottomPadding() + navClearance,
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
                                (rv.getTag(R.id.tag_home_scroll_listener) as? RecyclerView.OnScrollListener)?.let { oldListener ->
                                    rv.removeOnScrollListener(oldListener)
                                }
                                val newListener =
                                    object : RecyclerView.OnScrollListener() {
                                        override fun onScrolled(
                                            recyclerView: RecyclerView,
                                            dx: Int,
                                            dy: Int,
                                        ) {
                                            val state = scrollBehavior.state
                                            val limit = state.heightOffsetLimit
                                            when {
                                                dy > 0 ->
                                                    state.heightOffset =
                                                        (state.heightOffset - dy).coerceAtLeast(limit)
                                                dy < 0 && !recyclerView.canScrollVertically(-1) ->
                                                    state.heightOffset =
                                                        (state.heightOffset - dy).coerceAtMost(0f)
                                            }
                                            state.contentOffset -= dy
                                        }

                                        override fun onScrollStateChanged(
                                            recyclerView: RecyclerView,
                                            newState: Int,
                                        ) {
                                            isScrollIdle.value = (newState == RecyclerView.SCROLL_STATE_IDLE)
                                        }
                                    }
                                rv.addOnScrollListener(newListener)
                                rv.setTag(R.id.tag_home_scroll_listener, newListener)
                            }
                        },
                        update = { view -> recyclerViewProvider(view.context, adjustedPadding) },
                        onRelease = { rv ->
                            (rv.getTag(R.id.tag_home_scroll_listener) as? RecyclerView.OnScrollListener)?.let {
                                rv.removeOnScrollListener(it)
                                rv.setTag(R.id.tag_home_scroll_listener, null)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
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
        animationSpec =
            infiniteRepeatable(
                animation = tween(15000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
    )

    val color1 = MaterialTheme.colorScheme.primary.copy(alpha = 0.03f + 0.05f * alpha)
    val color2 = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.03f + 0.05f * (1f - alpha))
    val color3 = MaterialTheme.colorScheme.secondary.copy(alpha = 0.03f)

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Brush.sweepGradient(
                        colors = listOf(color1, color2, color3, color1),
                        center = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                    ),
                ),
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
        animationSpec =
            infiniteRepeatable(
                animation = tween(2500, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse,
            ),
    )

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_empty_home_24),
            contentDescription = null,
            modifier =
                Modifier
                    .size(72.dp)
                    .offset(y = floatAnim.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.empty_state_title_no_home_cards),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.empty_state_description_no_home_cards),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            size = ButtonSize.Medium,
            onClick = onRestoreHomeCards,
        ) {
            Text(stringResource(R.string.empty_state_action_restore_home_cards))
        }
    }
}
