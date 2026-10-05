package af.shizuku.manager.settings.compose

import af.shizuku.manager.R
import af.shizuku.manager.settings.SettingsSearchEngine
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    title: String,
    onNavigateUp: () -> Unit,
    onNavigateToSetting: (SettingsSearchEngine.SettingItem) -> Unit,
    searchResults: List<SettingsSearchEngine.SettingItem>,
    onSearchQueryChanged: (String) -> Unit,
    onContainerCreated: () -> Unit,
    isScrollIdle: Boolean = true,
    onScrollStateCreated: (TopAppBarState) -> Unit = {},
) {
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    fun exitSearch() {
        isSearchActive = false
        searchQuery = ""
        onSearchQueryChanged("")
        keyboardController?.hide()
    }

    BackHandler(enabled = isSearchActive) { exitSearch() }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            searchFocusRequester.requestFocus()
        }
    }

    val isOneUi =
        af.shizuku.manager.ShizukuSettings
            .isOneUiThemeEnabled()
    val isOneHanded =
        af.shizuku.manager.ShizukuSettings
            .isOneHandedModeEnabled()
    val isExpandedHeaders =
        isOneUi ||
            af.shizuku.manager.ShizukuSettings
                .isExpandedHeadersEnabled()
    val context = LocalContext.current
    val isDarkTheme = isSystemInDarkTheme()
    val isBlackTheme =
        isDarkTheme &&
            af.shizuku.manager.app.ThemeHelper
                .isBlackNightTheme(context)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    LaunchedEffect(Unit) { onScrollStateCreated(scrollBehavior.state) }
    // Re-coerce heightOffset whenever LargeTopAppBar remeasures and updates heightOffsetLimit
    // (e.g. font-scale change, display-size change, rotation). The LargeTopAppBar sets
    // heightOffsetLimit during its layout pass, AFTER composition; until the next frame the
    // stale heightOffset can sit below the new limit, making Scaffold report a negative top
    // padding and throwing "Padding must be non-negative" (#569, same root cause as HomeScreen's
    // SideEffect re-coerce on line 87 of HomeScreen.kt). heightOffset's setter enforces
    // [heightOffsetLimit, 0f], so reassigning it to itself is sufficient.
    LaunchedEffect(scrollBehavior.state.heightOffsetLimit) {
        scrollBehavior.state.heightOffset = scrollBehavior.state.heightOffset
    }
    LaunchedEffect(isScrollIdle) {
        if (isScrollIdle) {
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

    Scaffold(
        topBar = {
            if (isSearchActive) {
                // Search mode: back button + inline text field + optional clear button
                TopAppBar(
                    title = {
                        TextField(
                            value = searchQuery,
                            onValueChange = { q ->
                                searchQuery = q
                                onSearchQueryChanged(q)
                            },
                            placeholder = { Text(stringResource(R.string.settings_search_hint)) },
                            singleLine = true,
                            colors =
                                TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .focusRequester(searchFocusRequester),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { exitSearch() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_back_24),
                                contentDescription = stringResource(R.string.cd_navigate_back),
                            )
                        }
                    },
                    actions = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = {
                                searchQuery = ""
                                onSearchQueryChanged("")
                            }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close_24),
                                    contentDescription = stringResource(R.string.cd_settings_search_clear),
                                )
                            }
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor =
                                if (isBlackTheme) {
                                    Color.Black
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                        ),
                )
            } else {
                if (isExpandedHeaders) {
                    LargeTopAppBar(
                        title = {
                            val fraction = scrollBehavior.state.collapsedFraction
                            val currentFontSize =
                                lerp(
                                    start = 28.sp,
                                    stop = 20.sp,
                                    fraction = fraction,
                                )
                            val currentFontWeight = if (fraction > 0.65f) FontWeight.Bold else FontWeight.ExtraBold
                            val currentLetterSpacing = lerp((-0.5).sp, (-0.2).sp, fraction)
                            Text(
                                text = title,
                                style =
                                    MaterialTheme.typography.headlineLarge.copy(
                                        fontWeight = currentFontWeight,
                                        fontSize = currentFontSize,
                                        letterSpacing = currentLetterSpacing,
                                    ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { onNavigateUp() }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_back_24),
                                    contentDescription = stringResource(R.string.cd_navigate_back),
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { isSearchActive = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_search_24),
                                    contentDescription = stringResource(R.string.cd_settings_search),
                                )
                            }
                        },
                        colors =
                            TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent,
                                scrolledContainerColor =
                                    if (af.shizuku.manager.ShizukuSettings
                                            .isBlurUiEnabled()
                                    ) {
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainer
                                    },
                                titleContentColor = MaterialTheme.colorScheme.onSurface,
                                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        scrollBehavior = scrollBehavior,
                    )
                } else {
                    TopAppBar(
                        title = {
                            Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        navigationIcon = {
                            IconButton(onClick = { onNavigateUp() }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_back_24),
                                    contentDescription = stringResource(R.string.cd_navigate_back),
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { isSearchActive = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_search_24),
                                    contentDescription = stringResource(R.string.cd_settings_search),
                                )
                            }
                        },
                        colors =
                            TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent,
                                scrolledContainerColor =
                                    if (af.shizuku.manager.ShizukuSettings
                                            .isBlurUiEnabled()
                                    ) {
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainer
                                    },
                            ),
                    )
                }
            }
        },
    ) { innerPadding ->
        val screenHeightDp = LocalConfiguration.current.screenHeightDp
        val targetThumbTop = (screenHeightDp * 0.38f).dp
        val extraOneHanded = (targetThumbTop - innerPadding.calculateTopPadding()).coerceAtLeast(0.dp)
        val oneHandedOffset by animateDpAsState(
            targetValue = if (isOneHanded) extraOneHanded else 0.dp,
            animationSpec =
                if (!af.shizuku.manager.ShizukuSettings
                        .isExpressiveAnimationsEnabled()
                ) {
                    snap()
                } else {
                    spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness =
                            Spring.StiffnessMedium /
                                af.shizuku.manager.ShizukuSettings
                                    .getAnimationDurationScale()
                                    .coerceAtLeast(0.1f),
                    )
                },
            label = "settingsOneHandedOffset",
        )

        Box(modifier = Modifier.fillMaxSize()) {
            if (isOneHanded && oneHandedOffset > 16.dp) {
                val handleAlpha = (1f - (scrollBehavior.state.collapsedFraction * 2.5f)).coerceIn(0f, 1f)
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height((oneHandedOffset + innerPadding.calculateTopPadding()).coerceAtLeast(0.dp))
                            .padding(top = (innerPadding.calculateTopPadding() + 8.dp).coerceAtLeast(0.dp))
                            .graphicsLayer { alpha = handleAlpha },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .width(36.dp)
                                .height(4.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f),
                                    shape =
                                        androidx.compose.foundation.shape
                                            .RoundedCornerShape(2.dp),
                                ),
                    )
                }
            }

            // Fragment container — hidden while search is active
            AndroidView(
                factory = { context ->
                    FrameLayout(context).apply {
                        id = R.id.fragment_container
                        clipToPadding = false
                        post { onContainerCreated() }
                    }
                },
                update = { view ->
                    view.visibility = if (isSearchActive) android.view.View.GONE else android.view.View.VISIBLE
                },
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(
                            top = innerPadding.calculateTopPadding().coerceAtLeast(0.dp),
                            bottom = innerPadding.calculateBottomPadding().coerceAtLeast(0.dp),
                        ),
            )

            // Inline search results — appears below the search TopAppBar
            val searchFadeMs =
                af.shizuku.manager.ShizukuSettings
                    .scaledAnimationDuration(200L)
                    .toInt()
            AnimatedVisibility(
                visible = isSearchActive,
                enter = fadeIn(tween(searchFadeMs)),
                exit = fadeOut(tween(searchFadeMs)),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding().coerceAtLeast(0.dp)),
            ) {
                val bgColor = if (isBlackTheme) Color.Black else MaterialTheme.colorScheme.surface
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(bgColor),
                ) {
                    if (searchQuery.isBlank()) {
                        // Empty query: just show the background so the fragment is covered
                    } else if (searchResults.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_search_empty_state),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(searchResults) { item ->
                                SearchResultItem(
                                    item = item,
                                    isBlackTheme = isBlackTheme,
                                    onClick = {
                                        exitSearch()
                                        onNavigateToSetting(item)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultItem(
    item: SettingsSearchEngine.SettingItem,
    isBlackTheme: Boolean = false,
    onClick: () -> Unit,
) {
    // Use Material3's clickable Card overload rather than Modifier.clickable: the latter reads
    // LocalIndication, and on Android 16 / Compose Foundation 1.7+ that threw
    // "clickable only supports IndicationNodeFactory instances provided to LocalIndication"
    // when a legacy Indication was in scope, crashing settings search (#309 / SHIZUKUPLUS-72).
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = if (isBlackTheme) Color(0xFF141414) else MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.iconResId != 0) {
                Icon(
                    painter = painterResource(item.iconResId),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .size(24.dp)
                            .padding(end = 0.dp),
                )
                Spacer(modifier = Modifier.width(16.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.category ?: "Settings",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
                if (!item.summary.isNullOrEmpty()) {
                    Text(
                        text = item.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
