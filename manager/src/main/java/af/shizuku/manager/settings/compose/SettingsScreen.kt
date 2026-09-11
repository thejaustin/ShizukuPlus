package af.shizuku.manager.settings.compose

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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import af.shizuku.manager.R
import af.shizuku.manager.settings.SettingsSearchEngine
import android.widget.FrameLayout
import androidx.compose.ui.Alignment

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
    onScrollStateCreated: (TopAppBarState) -> Unit = {}
) {
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    BackHandler(enabled = isSearchActive) {
        isSearchActive = false
        searchQuery = ""
        onSearchQueryChanged("")
    }

    val isOneUi = af.shizuku.manager.ShizukuSettings.isOneUiThemeEnabled()
    val isOneHanded = af.shizuku.manager.ShizukuSettings.isOneHandedModeEnabled()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(Unit) { onScrollStateCreated(scrollBehavior.state) }
    LaunchedEffect(isScrollIdle) {
        if (isScrollIdle) {
            val state = scrollBehavior.state
            val fraction = state.collapsedFraction
            if (fraction > 0.001f && fraction < 0.999f) {
                val target = if (fraction >= 0.5f) state.heightOffsetLimit else 0f
                Animatable(state.heightOffset).animateTo(
                    target, spring(stiffness = Spring.StiffnessMediumLow)
                ) { state.heightOffset = value }
            }
        }
    }


    Scaffold(
        topBar = {
            if (!isSearchActive) {
              if (isOneUi) {
                LargeTopAppBar(
                    title = {
                        Text(
                            text = title,
                            // Samsung OneUI 6/7 uses W800 (ExtraBold) for the large expanded header
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 28.sp,
                                letterSpacing = (-0.5).sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { onNavigateUp() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_back_24),
                                contentDescription = stringResource(R.string.cd_navigate_back)
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_search_24),
                                contentDescription = stringResource(R.string.cd_settings_search)
                            )
                        }
                    },
                    // Samsung OneUI: transparent container until scrolled, then subtle surface tint
                    colors = TopAppBarDefaults.largeTopAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = if (af.shizuku.manager.ShizukuSettings.isBlurUiEnabled())
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                        else
                            MaterialTheme.colorScheme.surfaceContainer,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    scrollBehavior = scrollBehavior
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
                                contentDescription = stringResource(R.string.cd_navigate_back)
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_search_24),
                                contentDescription = stringResource(R.string.cd_settings_search)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = if (af.shizuku.manager.ShizukuSettings.isBlurUiEnabled())
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
                        else
                            MaterialTheme.colorScheme.surfaceContainer
                    )
                )
              }
            }
        }
    ) { innerPadding ->
        // Samsung OneUI one-handed mode: shift content down into the thumb-reachable zone (38% screen height).
        // Subtract innerPadding.top so we don't double-pad on top of the TopAppBar.
        val screenHeightDp = LocalConfiguration.current.screenHeightDp
        val targetThumbTop = (screenHeightDp * 0.38f).dp
        val extraOneHanded = (targetThumbTop - innerPadding.calculateTopPadding()).coerceAtLeast(0.dp)
        val oneHandedOffset by animateDpAsState(
            targetValue = if (isOneHanded) extraOneHanded else 0.dp,
            animationSpec = if (!af.shizuku.manager.ShizukuSettings.isExpressiveAnimationsEnabled()) {
                snap()
            } else {
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium / af.shizuku.manager.ShizukuSettings.getAnimationDurationScale().coerceAtLeast(0.1f)
                )
            },
            label = "settingsOneHandedOffset"
        )

        Box(modifier = Modifier.fillMaxSize()) {
            if (isOneHanded && oneHandedOffset > 16.dp) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(oneHandedOffset + innerPadding.calculateTopPadding())
                        .padding(top = innerPadding.calculateTopPadding() + 8.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .background(
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
                            )
                    )
                }
            }
            // Fragment Container for Preferences — shift down in one-handed mode via layout padding
            // so touch targets remain aligned with the visual position.
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = oneHandedOffset)
                    .padding(
                        top = innerPadding.calculateTopPadding(),
                        bottom = innerPadding.calculateBottomPadding()
                    )
            )

            // M3 SearchBar — full-screen, manages its own status-bar insets when the
            // top bar is hidden. The topBar slot is empty when isSearchActive = true so
            // this SearchBar starts from the very top of the window.
            val searchFadeMs = af.shizuku.manager.ShizukuSettings.scaledAnimationDuration(300L).toInt()
            AnimatedVisibility(
                visible = isSearchActive,
                enter = fadeIn(tween(searchFadeMs)),
                exit = fadeOut(tween(searchFadeMs)),
                modifier = Modifier.fillMaxSize()
            ) {
                SearchBar(
                    query = searchQuery,
                    onQueryChange = { q -> searchQuery = q; onSearchQueryChanged(q) },
                    onSearch = {},
                    active = true,
                    onActiveChange = {
                        if (!it) { isSearchActive = false; searchQuery = ""; onSearchQueryChanged("") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = SearchBarDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    leadingIcon = {
                        IconButton(onClick = {
                            isSearchActive = false; searchQuery = ""; onSearchQueryChanged("")
                        }) {
                            Icon(painterResource(R.drawable.ic_back_24), stringResource(R.string.cd_navigate_back))
                        }
                    },
                    trailingIcon = if (searchQuery.isEmpty()) null else ({
                        IconButton(onClick = { searchQuery = ""; onSearchQueryChanged("") }) {
                            Icon(painterResource(R.drawable.ic_close_24), null)
                        }
                    }),
                    placeholder = { Text(stringResource(R.string.settings_search_hint)) },
                    windowInsets = SearchBarDefaults.windowInsets
                ) {
                    if (searchQuery.isNotBlank()) {
                        if (searchResults.isEmpty()) {
                            Text(
                                text = stringResource(R.string.settings_search_empty_state),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleMedium
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(searchResults) { item ->
                                    SearchResultItem(item = item, onClick = {
                                        isSearchActive = false
                                        searchQuery = ""
                                        onSearchQueryChanged("")
                                        onNavigateToSetting(item)
                                    })
                                }
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
    onClick: () -> Unit
) {
    // Use Material3's clickable Card overload rather than Modifier.clickable: the latter reads
    // LocalIndication, and on Android 16 / Compose Foundation 1.7+ that threw
    // "clickable only supports IndicationNodeFactory instances provided to LocalIndication"
    // when a legacy Indication was in scope, crashing settings search (#309 / SHIZUKUPLUS-72).
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = item.category ?: "Settings",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            if (!item.summary.isNullOrEmpty()) {
                Text(
                    text = item.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
