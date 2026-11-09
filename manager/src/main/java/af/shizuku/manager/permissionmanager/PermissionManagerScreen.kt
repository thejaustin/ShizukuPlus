@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package af.shizuku.manager.permissionmanager

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.HapticUtils
import af.shizuku.manager.widget.AppSwitch
import af.shizuku.manager.widget.GuardHeightOffset
import af.shizuku.manager.widget.nonNegative
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.ShizukuPlusAPI
import timber.log.Timber

// Shell-grantable privileged permissions commonly needed by backup/root apps.
// These are PROTECTION_SIGNATURE but grantable to any package by uid=2000 (shell).
private val PRIVILEGED_PERMISSIONS =
    setOf(
        "android.permission.READ_LOGS",
        "android.permission.DUMP",
        "android.permission.PACKAGE_USAGE_STATS",
        "android.permission.WRITE_SECURE_SETTINGS",
        "android.permission.READ_FRAME_BUFFER",
        "android.permission.INTERACT_ACROSS_USERS",
        "android.permission.INTERACT_ACROSS_USERS_FULL",
        "android.permission.MANAGE_USB",
        "android.permission.BATTERY_STATS",
        "android.permission.MOUNT_UNMOUNT_FILESYSTEMS",
        "android.permission.INSTALL_PACKAGES",
        "android.permission.DELETE_PACKAGES",
        "android.permission.CHANGE_NETWORK_STATE",
        "android.permission.CHANGE_WIFI_STATE",
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.MANAGE_NETWORK_POLICY",
        "android.permission.CONNECTIVITY_INTERNAL",
        "android.permission.OBSERVE_APP_USAGE",
        "android.permission.GET_APP_OPS_STATS",
        "android.permission.MANAGE_APP_OPS_MODES",
        "android.permission.CHANGE_COMPONENT_ENABLED_STATE",
        "android.permission.FORCE_STOP_PACKAGES",
    )

data class AppItem(
    val packageName: String,
    val label: String,
)

data class PermItem(
    val name: String,
    val shortName: String,
    val isGranted: Boolean,
    val isPrivileged: Boolean,
)

private enum class PermFilter(
    val labelRes: Int,
) {
    ALL(R.string.permission_manager_filter_all),
    PRIVILEGED(R.string.permission_manager_filter_privileged),
    RUNTIME(R.string.permission_manager_filter_runtime),
    GRANTED(R.string.permission_manager_filter_granted),
    REVOKED(R.string.permission_manager_filter_revoked),
}

private enum class PermSortOrder(
    val labelRes: Int,
) {
    DEFAULT(R.string.permission_manager_sort_type),
    NAME(R.string.permission_manager_sort_name),
    STATUS(R.string.permission_manager_sort_status),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionManagerScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pm = context.packageManager

    var query by remember { mutableStateOf("") }
    var allApps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(true) }

    var selectedApp by remember { mutableStateOf<AppItem?>(null) }
    var appPerms by remember { mutableStateOf<List<PermItem>>(emptyList()) }
    var isLoadingPerms by remember { mutableStateOf(false) }

    // Predictive back out of an app's permissions: the pane shrinks and fades with the gesture
    // (same feel as Home's edit mode) and snaps back if the gesture is cancelled.
    var backProgress by remember { mutableStateOf(0f) }
    PredictiveBackHandler(enabled = selectedApp != null) { events ->
        try {
            events.collect { backProgress = it.progress }
            selectedApp = null
            appPerms = emptyList()
        } finally {
            backProgress = 0f
        }
    }

    // Load app list on open
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val packages = pm.getInstalledPackages(0)
            val list =
                packages
                    .mapNotNull { pi ->
                        val label =
                            try {
                                pm.getApplicationLabel(pi.applicationInfo ?: return@mapNotNull null).toString()
                            } catch (_: Exception) {
                                pi.packageName
                            }
                        AppItem(pi.packageName, label)
                    }.sortedBy { it.label.lowercase() }
            allApps = list
            isLoadingApps = false
        }
    }

    val filteredApps =
        remember(allApps, query) {
            val q = query.trim().lowercase()
            if (q.isEmpty()) {
                allApps
            } else {
                allApps.filter {
                    it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
                }
            }
        }

    // M3 app-bar contract: a flexible medium bar that compresses on scroll, title wraps.
    val appBarScroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    appBarScroll.GuardHeightOffset()
    Scaffold(
        modifier = Modifier.nestedScroll(appBarScroll.nestedScrollConnection),
        topBar = {
            @OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
            androidx.compose.material3.MediumFlexibleTopAppBar(
                title = {
                    if (selectedApp != null) {
                        Text(selectedApp!!.label, maxLines = 3)
                    } else {
                        Text(stringResource(R.string.home_permission_manager_title))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedApp != null) {
                            selectedApp = null
                            appPerms = emptyList()
                        } else {
                            onBackClick()
                        }
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back_24),
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
                scrollBehavior = appBarScroll,
            )
        },
    ) { rawPadding ->
        val paddingValues = rawPadding.nonNegative()
        if (selectedApp != null) {
            // ── Permission detail view ────────────────────────────────────────
            Box(
                modifier =
                    Modifier.graphicsLayer {
                        val scale = 1f - 0.08f * backProgress * backProgress
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - 0.15f * backProgress
                    },
            ) {
                PermissionDetailPane(
                    app = selectedApp!!,
                    perms = appPerms,
                    isLoading = isLoadingPerms,
                    paddingValues = paddingValues,
                    onToggle = { perm, grant ->
                        val app = selectedApp
                        if (app != null) {
                            scope.launch(Dispatchers.IO) {
                                val ok =
                                    if (grant) {
                                        ShizukuPlusAPI.PackageGovernor.grantPermission(app.packageName, perm.name)
                                    } else {
                                        ShizukuPlusAPI.PackageGovernor.revokePermission(app.packageName, perm.name)
                                    }
                                if (ok) {
                                    withContext(Dispatchers.Main) {
                                        appPerms =
                                            appPerms.map {
                                                if (it.name == perm.name) it.copy(isGranted = grant) else it
                                            }
                                    }
                                } else {
                                    Timber.w("PermissionManager: toggle failed for ${perm.name}")
                                }
                            }
                        }
                    },
                )
            }
        } else {
            // ── App list view ─────────────────────────────────────────────────
            AppListPane(
                apps = filteredApps,
                isLoading = isLoadingApps,
                query = query,
                onQueryChange = { query = it },
                paddingValues = paddingValues,
                onAppClick = { app ->
                    selectedApp = app
                    isLoadingPerms = true
                    appPerms = emptyList()
                    scope.launch(Dispatchers.IO) {
                        val perms = loadPermissions(pm, app.packageName)
                        withContext(Dispatchers.Main) {
                            appPerms = perms
                            isLoadingPerms = false
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun AppListPane(
    apps: List<AppItem>,
    isLoading: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    paddingValues: PaddingValues,
    onAppClick: (AppItem) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(paddingValues),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.permission_manager_search_hint)) },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_search_24),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close_24),
                            contentDescription = stringResource(R.string.cd_settings_search_clear),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {}),
        )

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.LoadingIndicator()
            }
        } else if (apps.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.permission_manager_no_apps),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val isOneHanded = ShizukuSettings.isOneHandedModeEnabled()
            val isOneUi = ShizukuSettings.isOneUiThemeEnabled()
            val screenHeightDp = LocalConfiguration.current.screenHeightDp
            val oneHandedTop = if (isOneHanded || isOneUi) (screenHeightDp * 0.16f).dp else 0.dp

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (oneHandedTop > 0.dp) {
                    item { Spacer(Modifier.height(oneHandedTop)) }
                }
                items(apps, key = { it.packageName }) { app ->
                    AppRow(app = app, onClick = { onAppClick(app) })
                    HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppItem,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val pm = context.packageManager
    val iconBitmap =
        remember(app.packageName) {
            runCatching {
                pm.getApplicationIcon(app.packageName).toBitmap().asImageBitmap()
            }.getOrNull()
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_nav_apps_24),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_outline_open_in_new_24),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun PermissionDetailPane(
    app: AppItem,
    perms: List<PermItem>,
    isLoading: Boolean,
    paddingValues: PaddingValues,
    onToggle: (PermItem, Boolean) -> Unit,
) {
    if (isLoading) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.LoadingIndicator()
        }
        return
    }

    if (perms.isEmpty()) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.permission_manager_no_permissions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    var filter by remember { mutableStateOf(PermFilter.ALL) }
    var sortOrder by remember { mutableStateOf(PermSortOrder.DEFAULT) }
    var showSortMenu by remember { mutableStateOf(false) }
    val view = LocalView.current

    val displayedPerms =
        remember(perms, filter, sortOrder) {
            val list =
                when (filter) {
                    PermFilter.ALL -> perms
                    PermFilter.PRIVILEGED -> perms.filter { it.isPrivileged }
                    PermFilter.RUNTIME -> perms.filter { !it.isPrivileged }
                    PermFilter.GRANTED -> perms.filter { it.isGranted }
                    PermFilter.REVOKED -> perms.filter { !it.isGranted }
                }
            when (sortOrder) {
                PermSortOrder.DEFAULT ->
                    list.sortedWith(
                        compareBy({ !it.isPrivileged }, { it.shortName.lowercase() }),
                    )
                PermSortOrder.NAME -> list.sortedBy { it.shortName.lowercase() }
                PermSortOrder.STATUS ->
                    list.sortedWith(
                        compareBy({ !it.isGranted }, { it.shortName.lowercase() }),
                    )
            }
        }

    val isOneHanded = ShizukuSettings.isOneHandedModeEnabled()
    val isOneUi = ShizukuSettings.isOneUiThemeEnabled()
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val oneHandedTop = if (isOneHanded || isOneUi) (screenHeightDp * 0.16f).dp else 0.dp

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(paddingValues),
    ) {
        // Filter & Sort Row matching Application Management & Activity Log ergonomics
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Scrollable filter chips on the left
            Row(
                modifier =
                    Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PermFilter.values().forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = {
                            HapticUtils.segmentTick(view)
                            filter = f
                        },
                        label = { Text(stringResource(f.labelRes)) },
                    )
                }
            }

            // Vertical divider line
            Box(
                modifier =
                    Modifier
                        .padding(horizontal = 8.dp)
                        .width(1.dp)
                        .height(24.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
            )

            // Static right-pinned Sort Dropdown Chip
            Box {
                FilterChip(
                    selected = false,
                    onClick = {
                        HapticUtils.tap(view)
                        showSortMenu = true
                    },
                    label = { Text(stringResource(sortOrder.labelRes)) },
                    trailingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_outline_expand_more_24),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false },
                ) {
                    PermSortOrder.values().forEach { order ->
                        DropdownMenuItem(
                            text = { Text(stringResource(order.labelRes)) },
                            onClick = {
                                HapticUtils.segmentTick(view)
                                sortOrder = order
                                showSortMenu = false
                            },
                        )
                    }
                }
            }
        }

        if (displayedPerms.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.empty_state_title_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (oneHandedTop > 0.dp) {
                    item { Spacer(Modifier.height(oneHandedTop)) }
                }

                if (sortOrder == PermSortOrder.DEFAULT && filter == PermFilter.ALL) {
                    val priv = displayedPerms.filter { it.isPrivileged }
                    val rt = displayedPerms.filter { !it.isPrivileged }
                    if (priv.isNotEmpty()) {
                        item {
                            PermSectionHeader(stringResource(R.string.permission_manager_section_privileged))
                        }
                        item {
                            Card(
                                border =
                                    af.shizuku.manager.utils
                                        .cardBorder(),
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.permission_manager_privileged_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                        items(priv, key = { "priv_${it.name}" }) { perm ->
                            PermToggleRow(perm = perm, onToggle = onToggle)
                            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                        }
                    }

                    if (rt.isNotEmpty()) {
                        item {
                            PermSectionHeader(stringResource(R.string.permission_manager_section_runtime))
                        }
                        items(rt, key = { "rt_${it.name}" }) { perm ->
                            PermToggleRow(perm = perm, onToggle = onToggle)
                            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                } else {
                    items(displayedPerms, key = { "${it.isPrivileged}_${it.name}" }) { perm ->
                        PermToggleRow(perm = perm, onToggle = onToggle)
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun PermSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp, end = 16.dp),
    )
}

@Composable
private fun PermToggleRow(
    perm: PermItem,
    onToggle: (PermItem, Boolean) -> Unit,
) {
    // Row handles the click; Switch.onCheckedChange=null avoids double-firing.
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onToggle(perm, !perm.isGranted) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = perm.shortName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
            )
            Text(
                text = perm.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
            )
        }
        AppSwitch(checked = perm.isGranted, onCheckedChange = null)
    }
}

private fun loadPermissions(
    pm: PackageManager,
    packageName: String,
): List<PermItem> {
    return try {
        val info: PackageInfo = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        val declared = info.requestedPermissions ?: return emptyList()
        val flags = info.requestedPermissionsFlags ?: IntArray(declared.size)
        val result = mutableListOf<PermItem>()
        for (i in declared.indices) {
            val name = declared[i] ?: continue
            val isGranted = flags.getOrElse(i) { 0 } and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
            val isPriv = name in PRIVILEGED_PERMISSIONS
            val protection =
                try {
                    val pi = pm.getPermissionInfo(name, 0)
                    pi.protection
                } catch (_: Exception) {
                    // Unknown permission — include if it's in our privileged set, otherwise skip
                    if (isPriv) PermissionInfo.PROTECTION_SIGNATURE else continue
                }
            when {
                isPriv -> result.add(PermItem(name, name.substringAfterLast('.'), isGranted, isPrivileged = true))
                protection == PermissionInfo.PROTECTION_DANGEROUS ->
                    result.add(PermItem(name, name.substringAfterLast('.'), isGranted, isPrivileged = false))
            }
        }
        // Sort: privileged first (by name), then runtime (by name)
        result.sortWith(compareBy({ !it.isPrivileged }, { it.shortName }))
        result
    } catch (e: Exception) {
        Timber.w(e, "loadPermissions failed for $packageName")
        emptyList()
    }
}
