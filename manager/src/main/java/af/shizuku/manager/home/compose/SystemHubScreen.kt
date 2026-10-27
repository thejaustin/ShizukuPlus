package af.shizuku.manager.home.compose

import af.shizuku.manager.R
import af.shizuku.manager.widget.GuardHeightOffset
import af.shizuku.manager.widget.nonNegative
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemHubScreen(
    onBackClick: () -> Unit,
    // M3: no back arrow while System Hub is a tab on the floating nav bar.
    showBack: Boolean = true,
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()
    // One UI / one-handed: a real large title that collapses on scroll (title in thumb reach),
    // instead of a small bar plus a blank 18-22% spacer inside each page.
    val reachMode =
        af.shizuku.manager.ShizukuSettings
            .isOneUiThemeEnabled() ||
            af.shizuku.manager.ShizukuSettings
                .isOneHandedModeEnabled()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // Outside reach mode the small bar still fills its container when content scrolls under it.
    val pinnedBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    scrollBehavior.GuardHeightOffset()
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val title = @Composable { Text(stringResource(R.string.system_hub_title), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    val nav =
        @Composable {
            if (showBack) {
                IconButton(onClick = onBackClick) {
                    Icon(painterResource(id = R.drawable.ic_back_24), contentDescription = stringResource(R.string.cd_navigate_back))
                }
            }
        }

    Scaffold(
        modifier = Modifier.nestedScroll(if (reachMode) scrollBehavior.nestedScrollConnection else pinnedBehavior.nestedScrollConnection),
        topBar = {
            if (reachMode) {
                LargeTopAppBar(
                    title = title,
                    navigationIcon = nav,
                    expandedHeight =
                        af.shizuku.manager.settings
                            .expandedHeaderHeightDp(screenHeightDp)
                            .dp,
                    scrollBehavior = scrollBehavior,
                )
            } else {
                TopAppBar(title = title, navigationIcon = nav, scrollBehavior = pinnedBehavior)
            }
        },
    ) { rawPadding ->
        val innerPadding = rawPadding.nonNegative()
        val navClearance =
            // Bar attaches only while Hub is one of its tabs.
            if (!showBack) {
                160.dp
            } else {
                0.dp
            }
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding()),
        ) {
            TabRow(selectedTabIndex = pagerState.currentPage) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } },
                    text = { Text(stringResource(R.string.system_hub_tab_metrics)) },
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } },
                    text = { Text(stringResource(R.string.settings_category_activity_log)) },
                )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    // Bottom clearance goes inside each page's scroll content, not on this
                    // Column: padding here leaves an empty band that shows the bare window
                    // background (a black bar in AMOLED) and stops content short of the nav bar.
                    0 -> ServerMetricsScreen(bottomInset = innerPadding.calculateBottomPadding() + navClearance, reachSpacer = !reachMode)
                    1 -> ActivityLogScreen(bottomInset = innerPadding.calculateBottomPadding() + navClearance, reachSpacer = !reachMode)
                }
            }
        }
    }
}
