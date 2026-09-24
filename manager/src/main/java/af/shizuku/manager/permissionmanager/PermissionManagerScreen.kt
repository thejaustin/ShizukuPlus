package af.shizuku.manager.permissionmanager

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import af.shizuku.manager.R
import rikka.shizuku.ShizukuPlusAPI
import timber.log.Timber

// Shell-grantable privileged permissions commonly needed by backup/root apps.
// These are PROTECTION_SIGNATURE but grantable to any package by uid=2000 (shell).
private val PRIVILEGED_PERMISSIONS = setOf(
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

    BackHandler(enabled = selectedApp != null) {
        selectedApp = null
        appPerms = emptyList()
    }

    // Load app list on open
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val packages = pm.getInstalledPackages(0)
            val list = packages.mapNotNull { pi ->
                val label = try { pm.getApplicationLabel(pi.applicationInfo ?: return@mapNotNull null).toString() } catch (_: Exception) { pi.packageName }
                AppItem(pi.packageName, label)
            }.sortedBy { it.label.lowercase() }
            allApps = list
            isLoadingApps = false
        }
    }

    val filteredApps = remember(allApps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) allApps
        else allApps.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (selectedApp != null) {
                        Text(selectedApp!!.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                            contentDescription = stringResource(R.string.nav_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        if (selectedApp != null) {
            // ── Permission detail view ────────────────────────────────────────
            PermissionDetailPane(
                app = selectedApp!!,
                perms = appPerms,
                isLoading = isLoadingPerms,
                paddingValues = paddingValues,
                onToggle = { perm, grant ->
                    val app = selectedApp ?: return@onToggle
                    scope.launch(Dispatchers.IO) {
                        val ok = if (grant) {
                            ShizukuPlusAPI.PackageGovernor.grantPermission(app.packageName, perm.name)
                        } else {
                            ShizukuPlusAPI.PackageGovernor.revokePermission(app.packageName, perm.name)
                        }
                        if (ok) {
                            withContext(Dispatchers.Main) {
                                appPerms = appPerms.map {
                                    if (it.name == perm.name) it.copy(isGranted = grant) else it
                                }
                            }
                        } else {
                            Timber.w("PermissionManager: toggle failed for ${perm.name}")
                        }
                    }
                }
            )
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
                }
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
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.permission_manager_search_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {})
        )

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (apps.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.permission_manager_no_apps),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(apps, key = { it.packageName }) { app ->
                    AppRow(app = app, onClick = { onAppClick(app) })
                    HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppItem, onClick: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    val iconBitmap = remember(app.packageName) {
        runCatching {
            pm.getApplicationIcon(app.packageName).toBitmap().asImageBitmap()
        }.getOrNull()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap,
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_default_app_icon),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.outline
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_outline_open_in_new_24),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp)
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
    val privileged = perms.filter { it.isPrivileged }
    val runtime = perms.filter { !it.isPrivileged }

    if (isLoading) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    if (perms.isEmpty()) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResource(R.string.permission_manager_no_permissions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        if (privileged.isNotEmpty()) {
            item {
                PermSectionHeader(stringResource(R.string.permission_manager_section_privileged))
            }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.permission_manager_privileged_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            items(privileged, key = { "priv_${it.name}" }) { perm ->
                PermToggleRow(perm = perm, onToggle = onToggle)
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            }
        }

        if (runtime.isNotEmpty()) {
            item {
                PermSectionHeader(stringResource(R.string.permission_manager_section_runtime))
            }
            items(runtime, key = { "rt_${it.name}" }) { perm ->
                PermToggleRow(perm = perm, onToggle = onToggle)
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun PermSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp, end = 16.dp)
    )
}

@Composable
private fun PermToggleRow(perm: PermItem, onToggle: (PermItem, Boolean) -> Unit) {
    // Row handles the click; Switch.onCheckedChange=null avoids double-firing.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(perm, !perm.isGranted) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = perm.shortName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = perm.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(checked = perm.isGranted, onCheckedChange = null)
    }
}

private fun loadPermissions(pm: PackageManager, packageName: String): List<PermItem> {
    return try {
        val info: PackageInfo = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        val declared = info.requestedPermissions ?: return emptyList()
        val flags = info.requestedPermissionsFlags ?: IntArray(declared.size)
        val result = mutableListOf<PermItem>()
        for (i in declared.indices) {
            val name = declared[i] ?: continue
            val isGranted = flags.getOrElse(i) { 0 } and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
            val isPriv = name in PRIVILEGED_PERMISSIONS
            val protection = try {
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
