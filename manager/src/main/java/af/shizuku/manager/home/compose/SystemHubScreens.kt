package af.shizuku.manager.home.compose

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.activitylog.ActivityLogText
import af.shizuku.manager.database.ActivityEventType
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.database.ActivityLogRecord
import af.shizuku.manager.utils.AppIconCache
import af.shizuku.manager.utils.HapticUtils
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

private fun queryLiveServerPatchVersion(): Int {
    return try {
        val binder = Shizuku.getBinder() ?: return -1
        val data = android.os.Parcel.obtain()
        val reply = android.os.Parcel.obtain()
        try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService")
            if (binder.transact(10004, data, reply, 0)) {
                reply.readException()
                reply.readInt()
            } else {
                -1
            }
        } finally {
            reply.recycle()
            data.recycle()
        }
    } catch (_: Throwable) {
        -1
    }
}

@Composable
fun ServerMetricsScreen(
    bottomInset: Dp = 0.dp,
    reachSpacer: Boolean = true,
    onConnectedAppsClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var isServerRunning by remember { mutableStateOf<Boolean?>(null) }
    var isStockServer by remember { mutableStateOf(false) }
    var uptimeText by remember { mutableStateOf("00:00:00") }
    var clientCountText by remember { mutableStateOf(context.getString(R.string.server_clients_connected, 0)) }
    var memoryProgress by remember { mutableFloatStateOf(0f) }
    var memoryDetails by remember { mutableStateOf("0 MB / 0 MB") }
    var serverPatchVersion by remember { mutableIntStateOf(-1) }
    var serverUid by remember { mutableIntStateOf(-1) }
    var seContext by remember { mutableStateOf<String?>(null) }

    // Uptime ticks locally every second from the last sampled value, so the server is only polled
    // every 2 s - and all binder work runs off the main thread (it used to make up to six IPCs a
    // second on Main, including ones whose answers never change while the server is up).
    var uptimeBaseMs by remember { mutableStateOf(-1L) }
    var uptimeSampledAt by remember { mutableStateOf(0L) }
    val memoryHistory = remember { androidx.compose.runtime.mutableStateListOf<Float>() }
    LaunchedEffect(Unit) {
        var staticInfoLoaded = false
        while (true) {
            val sample =
                withContext(Dispatchers.IO) {
                    runCatching { collectServerSample(fetchStaticInfo = !staticInfoLoaded) }
                        .onFailure { Timber.w(it) }
                        .getOrNull()
                }
            if (sample == null || !sample.alive) {
                isServerRunning = sample?.alive ?: isServerRunning
                staticInfoLoaded = false
                uptimeBaseMs = -1L
                memoryHistory.clear()
            } else {
                isServerRunning = true
                if (sample.uid != null) {
                    serverUid = sample.uid
                    serverPatchVersion = sample.patch ?: serverPatchVersion
                    seContext = sample.seContext
                    staticInfoLoaded = true
                }
                val stats = sample.stats
                if (stats != null) {
                    isStockServer = false
                    uptimeBaseMs = stats.getLong("uptime_ms")
                    uptimeSampledAt = android.os.SystemClock.elapsedRealtime()
                    clientCountText = context.getString(R.string.server_clients_connected, stats.getInt("client_count"))

                    val maxRaw = stats.getLong("mem_max")
                    val total = stats.getLong("mem_total")
                    val used = (total - stats.getLong("mem_free")).coerceAtLeast(0L)
                    val max = if (maxRaw == Long.MAX_VALUE || maxRaw <= 0) total else maxRaw
                    memoryProgress = if (max > 0) (used.toFloat() / max.toFloat()).coerceIn(0f, 1f) else 0f
                    memoryHistory.add(memoryProgress)
                    while (memoryHistory.size > 60) memoryHistory.removeAt(0)
                    val maxStr =
                        if (maxRaw == Long.MAX_VALUE) {
                            context.getString(R.string.server_memory_uncapped)
                        } else {
                            android.text.format.Formatter
                                .formatShortFileSize(context, max)
                        }
                    memoryDetails =
                        context.getString(
                            R.string.server_memory_used_of,
                            android.text.format.Formatter
                                .formatShortFileSize(context, used),
                            maxStr,
                        )
                } else {
                    isStockServer = (serverPatchVersion <= 0)
                }
            }
            delay(2000)
        }
    }
    LaunchedEffect(uptimeBaseMs) {
        while (uptimeBaseMs >= 0) {
            val ms = uptimeBaseMs + (android.os.SystemClock.elapsedRealtime() - uptimeSampledAt)
            uptimeText = formatUptime(ms)
            delay(1000)
        }
    }

    val isOneHanded = ShizukuSettings.isOneHandedModeEnabled()
    val isOneUi = ShizukuSettings.isOneUiThemeEnabled()
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val oneHandedTop = if (reachSpacer && (isOneHanded || isOneUi)) (screenHeightDp * 0.22f).dp else 0.dp

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .padding(bottom = bottomInset),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (oneHandedTop > 0.dp) {
            Spacer(modifier = Modifier.height(oneHandedTop))
        }
        // Server State Banner
        when {
            isServerRunning == false -> {
                Card(
                    border =
                        af.shizuku.manager.utils
                            .cardBorder(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_server_error_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.server_metrics_server_not_running),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.server_metrics_server_not_running_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
            isServerRunning == true -> {
                Card(
                    border =
                        af.shizuku.manager.utils
                            .cardBorder(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors =
                        CardDefaults.cardColors(
                            // Live status: same role as the Settings status banner.
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.ic_server_ok_24),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.server_metrics_server_running),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        ) {
                            val modeLabel = if (serverUid == 0) "Root" else "ADB"
                            StatusBadge(modeLabel, MaterialTheme.colorScheme.primary)

                            if (serverPatchVersion >= 0) {
                                StatusBadge(stringResource(R.string.server_metrics_patch_label, serverPatchVersion), MaterialTheme.colorScheme.secondary)
                            }
                            if (serverUid >= 0) {
                                StatusBadge("uid=$serverUid", MaterialTheme.colorScheme.tertiary)
                            }
                            if (!seContext.isNullOrEmpty()) {
                                StatusBadge(seContext!!, MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
        }

        if (isStockServer) {
            Card(
                border =
                    af.shizuku.manager.utils
                        .cardBorder(),
                shape = MaterialTheme.shapes.extraLarge,
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_outline_info_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.server_metrics_stock_server),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.server_metrics_stock_server_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        } else if (isServerRunning == true) {
            // Metrics Diagnostic Cards
            // Two glanceable tiles side by side instead of two full-width rows.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile(
                    title = stringResource(R.string.server_uptime),
                    value = uptimeText,
                    iconRes = R.drawable.ic_history_24,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    title = stringResource(R.string.active_connections),
                    value = clientCountText,
                    iconRes = R.drawable.ic_server_24,
                    modifier = Modifier.weight(1f),
                    onClick = onConnectedAppsClick,
                )
            }

            Card(
                border =
                    af.shizuku.manager.utils
                        .cardBorder(),
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.server_memory_usage),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        ) {
                            Text(
                                text = "${(memoryProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { memoryProgress },
                        modifier = Modifier.fillMaxWidth().height(12.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(memoryDetails, style = MaterialTheme.typography.labelLarge)
                    if (memoryHistory.size >= 2) {
                        Spacer(modifier = Modifier.height(12.dp))
                        MemorySparkline(
                            values = memoryHistory,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(
    text: String,
    color: Color,
) {
    val (container, onContainer) = containerPair(color)
    Surface(
        shape = CircleShape,
        color = container,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.4.sp),
            fontWeight = FontWeight.Bold,
            color = onContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private enum class ActivityLogSort(
    val labelRes: Int,
) {
    NEWEST(R.string.changelog_sort_newest),
    OLDEST(R.string.changelog_sort_oldest),
    BY_APP(R.string.activity_log_sort_by_app),
    BY_TYPE(R.string.activity_log_sort_by_type),
    MOST_ACTIVE(R.string.activity_log_sort_most_active),
}

private enum class ActivityLogGrouping(
    val labelRes: Int,
) {
    DATE(R.string.activity_log_group_by_date),
    APP(R.string.activity_log_group_by_app),
    TYPE(R.string.activity_log_group_by_type),
    NONE(R.string.activity_log_group_none),
}

private data class LogGroup(
    val key: String,
    val primaryRecord: ActivityLogRecord,
    val occurrences: List<ActivityLogRecord>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityLogScreen(
    showTopBar: Boolean = false,
    onBackClick: (() -> Unit)? = null,
    bottomInset: Dp = 0.dp,
    reachSpacer: Boolean = true,
    initialFilterConnected: Boolean = false,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val rawLogs by ActivityLogManager.logs.collectAsState(initial = emptyList())

    var isConnectedAppsOnly by remember { mutableStateOf(initialFilterConnected) }
    var selectedTypes by remember { mutableStateOf<Set<ActivityEventType>>(emptySet()) }
    var currentSort by remember { mutableStateOf(if (initialFilterConnected) ActivityLogSort.MOST_ACTIVE else ActivityLogSort.NEWEST) }
    var currentGrouping by remember { mutableStateOf(if (initialFilterConnected) ActivityLogGrouping.APP else ActivityLogGrouping.DATE) }
    var stackRepetitive by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    LaunchedEffect(initialFilterConnected) {
        if (initialFilterConnected) {
            isConnectedAppsOnly = true
            currentGrouping = ActivityLogGrouping.APP
            currentSort = ActivityLogSort.MOST_ACTIVE
        }
    }

    var showSortMenu by remember { mutableStateOf(false) }
    var showGroupingMenu by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    val expandedKeys = remember { mutableStateListOf<String>() }

    // Pre-resolve app labels for filtering / sorting
    // Published as a new immutable map once resolved: the filter/sort/group steps below are
    // remember()-keyed on it, and mutating one map in place never changed the key, so labels that
    // arrived later were never used for search, sort-by-app or group-by-app.
    var appLabels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(rawLogs) {
        val pm = context.packageManager
        val known = appLabels
        val missing =
            rawLogs
                .asSequence()
                .map { it.packageName }
                .distinct()
                .filter { it !in known }
                .toList()
        if (missing.isEmpty()) return@LaunchedEffect
        val resolved =
            withContext(Dispatchers.IO) {
                missing.associateWith { pkg ->
                    try {
                        AppIconCache.getLabel(context, pm.getApplicationInfo(pkg, 0))
                    } catch (_: Exception) {
                        pkg
                    }
                }
            }
        appLabels = known + resolved
    }

    // Process logs: Filter -> Group/Stack -> Sort
    val processedGroups =
        remember(rawLogs, selectedTypes, currentSort, stackRepetitive, searchQuery, appLabels, isConnectedAppsOnly) {
            // 1. Filter by event type
            var filtered =
                if (selectedTypes.isEmpty()) {
                    rawLogs
                } else {
                    rawLogs.filter { it.eventType in selectedTypes }
                }

            // 1b. Filter connected client apps only
            if (isConnectedAppsOnly) {
                filtered = filtered.filter { it.packageName != context.packageName }
            }

            // 2. Filter by search query
            if (searchQuery.isNotBlank()) {
                val q = searchQuery.trim().lowercase()
                filtered =
                    filtered.filter { record ->
                        record.packageName.lowercase().contains(q) ||
                            record.action.lowercase().contains(q) ||
                            ActivityLogText.localize(context, record.action).lowercase().contains(q) ||
                            record.appName.lowercase().contains(q) ||
                            (appLabels[record.packageName]?.lowercase()?.contains(q) == true)
                    }
            }

            // 3. Stacking
            val groups: List<LogGroup> =
                if (stackRepetitive) {
                    val map = linkedMapOf<String, MutableList<ActivityLogRecord>>()
                    for (record in filtered) {
                        val key = "${record.packageName}|${record.eventType}|${record.action}"
                        map.getOrPut(key) { mutableListOf() }.add(record)
                    }
                    map.map { (key, records) ->
                        val primary = records.maxByOrNull { it.timestamp } ?: records.first()
                        val others = records.filter { it != primary }.sortedByDescending { it.timestamp }
                        LogGroup(
                            key = key,
                            primaryRecord = primary,
                            occurrences = others,
                        )
                    }
                } else {
                    filtered.mapIndexed { index, record ->
                        LogGroup(
                            key = "${record.timestamp}_${record.packageName}_$index",
                            primaryRecord = record,
                            occurrences = emptyList(),
                        )
                    }
                }

            // 4. Sort
            when (currentSort) {
                ActivityLogSort.NEWEST -> groups.sortedByDescending { it.primaryRecord.timestamp }
                ActivityLogSort.OLDEST -> groups.sortedBy { it.primaryRecord.timestamp }
                ActivityLogSort.BY_APP ->
                    groups.sortedWith(
                        compareBy(
                            { appLabels[it.primaryRecord.packageName]?.lowercase() ?: it.primaryRecord.appName.lowercase() },
                            { -it.primaryRecord.timestamp },
                        ),
                    )
                ActivityLogSort.BY_TYPE ->
                    groups.sortedWith(
                        compareBy(
                            { it.primaryRecord.eventType.ordinal },
                            { -it.primaryRecord.timestamp },
                        ),
                    )
                ActivityLogSort.MOST_ACTIVE ->
                    groups.sortedWith(
                        compareBy(
                            { -(it.occurrences.size + 1) },
                            { -it.primaryRecord.timestamp },
                        ),
                    )
            }
        }

    val groupedSections: Map<String, List<LogGroup>> =
        remember(processedGroups, currentGrouping, appLabels) {
            when (currentGrouping) {
                ActivityLogGrouping.DATE -> {
                    processedGroups.groupBy { getDateSectionTitle(context, it.primaryRecord.timestamp) }
                }
                ActivityLogGrouping.APP -> {
                    processedGroups.groupBy {
                        appLabels[it.primaryRecord.packageName]
                            ?: it.primaryRecord.appName.ifEmpty { it.primaryRecord.packageName }
                    }
                }
                ActivityLogGrouping.TYPE -> {
                    processedGroups.groupBy {
                        val labelRes =
                            when (it.primaryRecord.eventType) {
                                ActivityEventType.SERVICE_START -> R.string.activity_log_type_start
                                ActivityEventType.SERVICE_STOP -> R.string.activity_log_type_stop
                                ActivityEventType.CONNECTION -> R.string.activity_log_type_connection
                                ActivityEventType.PERMISSION -> R.string.activity_log_type_permission
                                ActivityEventType.APP_MANAGEMENT -> R.string.activity_log_type_app_mgmt
                                ActivityEventType.WATCHDOG -> R.string.activity_log_type_watchdog
                                ActivityEventType.SYSTEM -> R.string.activity_log_type_system
                                ActivityEventType.OTHER -> R.string.activity_log_type_other
                            }
                        context.getString(labelRes)
                    }
                }
                ActivityLogGrouping.NONE -> {
                    mapOf("" to processedGroups)
                }
            }
        }

    val dateFormat =
        remember {
            DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM,
                DateFormat.SHORT,
                Locale.getDefault(),
            )
        }

    val isOneHanded = ShizukuSettings.isOneHandedModeEnabled()
    val isOneUi = ShizukuSettings.isOneUiThemeEnabled()
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val oneHandedTop = if (reachSpacer && (isOneHanded || isOneUi)) (screenHeightDp * 0.18f).dp else 0.dp

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.activity_log_clear_confirm_title)) },
            text = { Text(stringResource(R.string.activity_log_clear_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        ActivityLogManager.clear()
                        expandedKeys.clear()
                        showClearDialog = false
                    },
                ) {
                    Text(stringResource(R.string.settings_activity_log_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    val content = @Composable {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(top = if (showTopBar) 0.dp else oneHandedTop),
        ) {
            // Search Bar
            AnimatedVisibility(visible = isSearchActive) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    placeholder = { Text(stringResource(R.string.activity_log_search_hint)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_search_24),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close_24),
                                    contentDescription = stringResource(R.string.cd_settings_search_clear),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = CircleShape,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                )
            }

            // Filter & Control Chips Row (Morphe style)
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Search toggle chip
                FilterChip(
                    selected = isSearchActive,
                    onClick = {
                        HapticUtils.segmentTick(view)
                        isSearchActive = !isSearchActive
                        if (!isSearchActive) searchQuery = ""
                    },
                    label = { Text(stringResource(R.string.cd_settings_search)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_search_24),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )

                // Connected Apps Filter Pill
                FilterChip(
                    selected = isConnectedAppsOnly,
                    onClick = {
                        HapticUtils.segmentTick(view)
                        isConnectedAppsOnly = !isConnectedAppsOnly
                        if (isConnectedAppsOnly) {
                            currentGrouping = ActivityLogGrouping.APP
                            currentSort = ActivityLogSort.MOST_ACTIVE
                        }
                    },
                    label = { Text(stringResource(R.string.active_connections)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_server_24),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )

                // Grouping Dropdown Chip
                Box {
                    FilterChip(
                        selected = currentGrouping != ActivityLogGrouping.NONE,
                        onClick = {
                            HapticUtils.tap(view)
                            showGroupingMenu = true
                        },
                        label = { Text(stringResource(currentGrouping.labelRes)) },
                        trailingIcon = {
                            Icon(
                                painterResource(R.drawable.ic_outline_expand_more_24),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                    DropdownMenu(
                        expanded = showGroupingMenu,
                        onDismissRequest = { showGroupingMenu = false },
                    ) {
                        ActivityLogGrouping.values().forEach { grouping ->
                            DropdownMenuItem(
                                text = { Text(stringResource(grouping.labelRes)) },
                                onClick = {
                                    HapticUtils.segmentTick(view)
                                    currentGrouping = grouping
                                    showGroupingMenu = false
                                },
                            )
                        }
                    }
                }

                // Sort Dropdown Chip
                Box {
                    FilterChip(
                        selected = false,
                        onClick = {
                            HapticUtils.tap(view)
                            showSortMenu = true
                        },
                        label = { Text(stringResource(currentSort.labelRes)) },
                        trailingIcon = {
                            Icon(
                                painterResource(R.drawable.ic_outline_expand_more_24),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                    ) {
                        ActivityLogSort.values().forEach { sort ->
                            DropdownMenuItem(
                                text = { Text(stringResource(sort.labelRes)) },
                                onClick = {
                                    HapticUtils.segmentTick(view)
                                    currentSort = sort
                                    showSortMenu = false
                                },
                            )
                        }
                    }
                }

                // Stacking Chip (Morphe style)
                FilterChip(
                    selected = stackRepetitive,
                    onClick = {
                        HapticUtils.segmentTick(view)
                        stackRepetitive = !stackRepetitive
                    },
                    label = { Text(stringResource(R.string.activity_log_stack_activities)) },
                )

                // Export Logs Chip
                FilterChip(
                    selected = false,
                    onClick = {
                        HapticUtils.tap(view)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text =
                            rawLogs.joinToString("\n") { r ->
                                "[${dateFormat.format(Date(r.timestamp))}] [${r.eventType}] ${r.appName} (${r.packageName}): ${r.action}"
                            }
                        clipboard.setPrimaryClip(
                            af.shizuku.manager.utils
                                .sensitivePlainText("Activity Log", text),
                        )
                        Toast
                            .makeText(
                                context,
                                context.getString(R.string.activity_log_export_copied, rawLogs.size),
                                Toast.LENGTH_SHORT,
                            ).show()
                    },
                    label = { Text(stringResource(R.string.activity_log_export)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_copy),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )

                // Clear Logs Chip
                FilterChip(
                    selected = false,
                    onClick = {
                        HapticUtils.tap(view)
                        showClearDialog = true
                    },
                    label = { Text(stringResource(R.string.settings_activity_log_clear)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete_24),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                )

                // "All" Chip
                FilterChip(
                    selected = selectedTypes.isEmpty(),
                    onClick = {
                        HapticUtils.segmentTick(view)
                        selectedTypes = emptySet()
                    },
                    label = { Text(stringResource(R.string.activity_log_filter_all)) },
                )

                // Multi-select Event Type Chips
                ActivityEventType.values().forEach { type ->
                    val labelRes =
                        when (type) {
                            ActivityEventType.SERVICE_START -> R.string.activity_log_type_start
                            ActivityEventType.SERVICE_STOP -> R.string.activity_log_type_stop
                            ActivityEventType.CONNECTION -> R.string.activity_log_type_connection
                            ActivityEventType.PERMISSION -> R.string.activity_log_type_permission
                            ActivityEventType.APP_MANAGEMENT -> R.string.activity_log_type_app_mgmt
                            ActivityEventType.WATCHDOG -> R.string.activity_log_type_watchdog
                            ActivityEventType.SYSTEM -> R.string.activity_log_type_system
                            ActivityEventType.OTHER -> R.string.activity_log_type_other
                        }
                    val isSelected = type in selectedTypes
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            HapticUtils.segmentTick(view)
                            selectedTypes =
                                if (isSelected) {
                                    selectedTypes - type
                                } else {
                                    selectedTypes + type
                                }
                        },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }

            // Main Timeline Content
            if (processedGroups.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painter = painterResource(R.drawable.ic_history_24),
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.empty_state_title_activity_log_empty),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.empty_state_description_activity_log_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 32.dp + bottomInset),
                ) {
                    groupedSections.forEach { (sectionTitle, sectionGroups) ->
                        if (currentGrouping != ActivityLogGrouping.NONE && sectionTitle.isNotEmpty()) {
                            val totalEvents = sectionGroups.sumOf { it.occurrences.size + 1 }
                            item(key = "header_${currentGrouping.name}_$sectionTitle") {
                                TimelineDateHeader(
                                    title = "$sectionTitle ($totalEvents)",
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }

                        itemsIndexed(
                            items = sectionGroups,
                            key = { _, group -> group.key },
                        ) { index, group ->
                            val isExpanded = expandedKeys.contains(group.key)
                            ActivityLogTimelineRow(
                                group = group,
                                dateFormat = dateFormat,
                                isFirst = index == 0,
                                isLast = index == sectionGroups.lastIndex,
                                isLatest = index == 0 && currentSort == ActivityLogSort.NEWEST && currentGrouping == ActivityLogGrouping.NONE,
                                isExpanded = isExpanded,
                                onToggleExpand = {
                                    if (isExpanded) {
                                        expandedKeys.remove(group.key)
                                    } else {
                                        expandedKeys.add(group.key)
                                    }
                                },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showTopBar) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_show_activity_log_home)) },
                    navigationIcon = {
                        if (onBackClick != null) {
                            IconButton(onClick = onBackClick) {
                                Icon(painterResource(R.drawable.ic_back_24), contentDescription = stringResource(R.string.cd_navigate_back))
                            }
                        }
                    },
                    actions = {
                        if (rawLogs.isNotEmpty()) {
                            // Export logs action
                            IconButton(
                                onClick = {
                                    HapticUtils.tap(view)
                                    val exported = exportLogsToText(rawLogs, dateFormat)
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(
                                        af.shizuku.manager.utils
                                            .sensitivePlainText("Activity Log", exported),
                                    )
                                    Toast
                                        .makeText(
                                            context,
                                            context.getString(R.string.activity_log_export_copied, rawLogs.size),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_copy),
                                    contentDescription = stringResource(R.string.activity_log_export),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }

                            // Clear logs action
                            IconButton(onClick = { showClearDialog = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_delete_24),
                                    contentDescription = stringResource(R.string.settings_activity_log_clear),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                )
            },
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                content()
            }
        }
    } else {
        content()
    }
}

@Composable
private fun TimelineDateHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 6.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            thickness = 0.7.dp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ActivityLogTimelineRow(
    group: LogGroup,
    dateFormat: DateFormat,
    isFirst: Boolean,
    isLast: Boolean,
    isLatest: Boolean,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val record = group.primaryRecord
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current

    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = if (ShizukuSettings.isExpressiveAnimationsEnabled()) MaterialTheme.motionScheme.defaultSpatialSpec() else snap(),
        label = "chevronRotation",
    )

    var appName by remember(record.packageName) {
        mutableStateOf(record.appName.ifEmpty { record.packageName })
    }
    var iconBitmap by remember(record.packageName) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }

    LaunchedEffect(record.packageName) {
        val pm = context.packageManager
        val sizePx = with(density) { 36.dp.roundToPx() }
        val ai =
            withContext(Dispatchers.IO) {
                try {
                    pm.getApplicationInfo(record.packageName, 0)
                } catch (_: Exception) {
                    null
                }
            } ?: return@LaunchedEffect
        appName = AppIconCache.getLabel(context, ai)
        val bitmap =
            withContext(Dispatchers.IO) {
                AppIconCache.getOrLoadBitmap(context, ai, ai.uid / 100000, sizePx)
            }
        if (bitmap != null) iconBitmap = bitmap.asImageBitmap()
    }

    val typeColor =
        when (record.eventType) {
            ActivityEventType.SERVICE_START -> MaterialTheme.colorScheme.primary
            ActivityEventType.SERVICE_STOP -> MaterialTheme.colorScheme.error
            ActivityEventType.CONNECTION -> MaterialTheme.colorScheme.secondary
            ActivityEventType.PERMISSION -> MaterialTheme.colorScheme.tertiary
            ActivityEventType.APP_MANAGEMENT -> MaterialTheme.colorScheme.secondary
            ActivityEventType.WATCHDOG -> MaterialTheme.colorScheme.error
            ActivityEventType.SYSTEM -> MaterialTheme.colorScheme.outline
            ActivityEventType.OTHER -> MaterialTheme.colorScheme.outline
        }

    val typeLabel =
        when (record.eventType) {
            ActivityEventType.SERVICE_START -> stringResource(R.string.activity_log_type_start)
            ActivityEventType.SERVICE_STOP -> stringResource(R.string.activity_log_type_stop)
            ActivityEventType.CONNECTION -> stringResource(R.string.activity_log_type_connection)
            ActivityEventType.PERMISSION -> stringResource(R.string.activity_log_type_permission)
            ActivityEventType.APP_MANAGEMENT -> stringResource(R.string.activity_log_type_app_mgmt)
            ActivityEventType.WATCHDOG -> stringResource(R.string.activity_log_type_watchdog)
            ActivityEventType.SYSTEM -> stringResource(R.string.activity_log_type_system)
            ActivityEventType.OTHER -> stringResource(R.string.activity_log_type_other)
        }

    val railColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val isExpressive = ShizukuSettings.isExpressiveAnimationsEnabled()
    val haloTransition = rememberInfiniteTransition(label = "haloTransition")
    val pulseScale by
        if (isLatest && isExpressive) {
            haloTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.5f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(1400),
                        repeatMode = RepeatMode.Reverse,
                    ),
                label = "haloScale",
            )
        } else {
            remember { mutableFloatStateOf(1f) }
        }

    val totalCount = group.occurrences.size + 1
    val isStacked = group.occurrences.isNotEmpty()

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
    ) {
        // Vertical connecting timeline rail matching Morphe timeline design
        Canvas(
            modifier =
                Modifier
                    .width(28.dp)
                    .defaultMinSize(minHeight = 76.dp),
        ) {
            val cx = size.width / 2f
            val nodeY = 22.dp.toPx()
            val railStroke = 2.dp.toPx()

            // Continuous connecting rail
            val startY = if (isFirst) nodeY else 0f
            val endY = if (isLast) nodeY else size.height
            if (endY > startY) {
                drawLine(
                    color = railColor,
                    start = Offset(cx, startY),
                    end = Offset(cx, endY),
                    strokeWidth = railStroke,
                )
            }

            // Outer glowing halo (matching Changelog TimelineRailView 10dp * scale)
            val haloRadius = 10.dp.toPx() * if (isLatest) pulseScale else 1f
            drawCircle(
                color = typeColor.copy(alpha = if (isLatest) 0.32f else 0.16f),
                radius = haloRadius,
                center = Offset(cx, nodeY),
            )

            // Outer colored border / ring (6dp outer ring)
            drawCircle(
                color = typeColor,
                radius = 6.dp.toPx(),
                center = Offset(cx, nodeY),
            )

            // Inner center dot / nested indicator for stacked events
            if (isStacked) {
                // Nested concentric indicator for repetitive activity stack
                drawCircle(
                    color = Color.White.copy(alpha = 0.85f),
                    radius = 3.dp.toPx(),
                    center = Offset(cx, nodeY),
                )
            } else if (!isLatest) {
                drawCircle(
                    color = railColor,
                    radius = 2.5.dp.toPx(),
                    center = Offset(cx, nodeY),
                )
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        // M3E expressive Card with interactive elevation & animated expansion
        Card(
            border =
                af.shizuku.manager.utils
                    .cardBorder(),
            shape = MaterialTheme.shapes.large,
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            modifier =
                Modifier
                    .weight(1f)
                    .padding(bottom = 10.dp)
                    .animateContentSize(
                        animationSpec = if (isExpressive) MaterialTheme.motionScheme.defaultSpatialSpec() else snap(),
                    ).clickable {
                        HapticUtils.segmentTick(view)
                        onToggleExpand()
                    },
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val bitmap = iconBitmap
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_system_icon),
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = typeColor,
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = appName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            text = formatRelativeTime(record.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))

                    // Occurrence badge if stacked (e.g. 5x)
                    if (isStacked) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                text = "${totalCount}x",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    // Event type status badge
                    Surface(
                        shape = CircleShape,
                        color = containerPair(typeColor).first,
                    ) {
                        Text(
                            text = typeLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.4.sp),
                            fontWeight = FontWeight.Bold,
                            color = containerPair(typeColor).second,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))

                    // Interactive chevron indicator
                    Icon(
                        painter = painterResource(R.drawable.ic_outline_expand_more_24),
                        contentDescription = null,
                        modifier =
                            Modifier
                                .size(22.dp)
                                .graphicsLayer(rotationZ = chevronRotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = ActivityLogText.localize(LocalContext.current, record.action),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                // Expandable details (package name, copy button, stacked occurrences)
                AnimatedVisibility(visible = isExpanded) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                    ) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = 0.5.dp,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = record.packageName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    HapticUtils.tap(view)
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Package Name", record.packageName))
                                    Toast
                                        .makeText(
                                            context,
                                            context.getString(R.string.activity_log_copied, record.packageName),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_copy),
                                    contentDescription = stringResource(R.string.home_adb_dialog_view_command_copy_button),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }

                        // Exact time
                        Text(
                            text = dateFormat.format(Date(record.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            modifier = Modifier.padding(top = 2.dp),
                        )

                        // If stacked repetitive events: show accordion sub-timeline of occurrences
                        if (isStacked) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.activity_log_stacked_count, totalCount),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                group.occurrences.forEachIndexed { idx, occ ->
                                    Row(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(start = 4.dp, top = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "#${totalCount - 1 - idx}: ${dateFormat.format(Date(occ.timestamp))}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = formatRelativeTime(occ.timestamp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatRelativeTime(timestamp: Long): String =
    // Platform-localized ("5 min. ago", "Yesterday", …) instead of hardcoded English.
    android.text.format.DateUtils
        .getRelativeTimeSpanString(
            timestamp,
            System.currentTimeMillis(),
            android.text.format.DateUtils.MINUTE_IN_MILLIS,
            android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()

private fun getDateSectionTitle(
    context: Context,
    timestamp: Long,
): String {
    val cal = Calendar.getInstance()
    val currentDay = cal.get(Calendar.DAY_OF_YEAR)
    val currentYear = cal.get(Calendar.YEAR)

    cal.timeInMillis = timestamp
    val targetDay = cal.get(Calendar.DAY_OF_YEAR)
    val targetYear = cal.get(Calendar.YEAR)

    return when {
        currentYear == targetYear && currentDay == targetDay ->
            context.getString(R.string.activity_log_date_today)
        currentYear == targetYear && currentDay - targetDay == 1 ->
            context.getString(R.string.activity_log_date_yesterday)
        currentYear == targetYear && currentDay - targetDay in 2..6 ->
            context.getString(R.string.activity_log_date_earlier_week)
        else ->
            SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}

private fun exportLogsToText(
    records: List<ActivityLogRecord>,
    dateFormat: DateFormat,
): String {
    val sb = StringBuilder()
    sb.appendLine("=== Shizuku+ Activity Log Export (${records.size} events) ===")
    records.sortedByDescending { it.timestamp }.forEach { record ->
        val timeStr = dateFormat.format(Date(record.timestamp))
        val typeStr = record.eventType.name
        val app = record.appName.ifEmpty { record.packageName }
        sb.appendLine("[$timeStr] [$typeStr] $app (${record.packageName}) - ${record.action}")
    }
    return sb.toString()
}

private class ServerSample(
    val alive: Boolean,
    val uid: Int? = null,
    val patch: Int? = null,
    val seContext: String? = null,
    val stats: android.os.Bundle? = null,
)

/** One poll of the server; call on a background dispatcher. */
private fun collectServerSample(fetchStaticInfo: Boolean): ServerSample {
    if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return ServerSample(alive = false)
    // uid / patch / SELinux context can't change while this server instance is up: fetch once.
    var uid: Int? = null
    var patch: Int? = null
    var se: String? = null
    if (fetchStaticInfo) {
        uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
        val cached = runCatching { Shizuku.getServerPatchVersion() }.getOrDefault(-1)
        patch = if (cached >= 0) cached else queryLiveServerPatchVersion()
        se = runCatching { Shizuku.getSELinuxContext() }.getOrNull()
    }
    val binder = Shizuku.getBinder() ?: return ServerSample(true, uid, patch, se)
    val service =
        moe.shizuku.server.IShizukuService.Stub
            .asInterface(binder)
    // Read-only: never flip server feature flags from here. The server exempts the manager from
    // the ai_core_plus gate; an older server that still returns null just shows no stats.
    val ai: af.shizuku.server.IAICorePlus? = runCatching { service.aiCorePlus }.getOrNull()
    return ServerSample(true, uid, patch, se, runCatching { ai?.serverStats }.getOrNull())
}

private fun formatUptime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val days = totalSeconds / 86_400
    val hours = (totalSeconds / 3_600) % 24
    val minutes = (totalSeconds / 60) % 60
    val seconds = totalSeconds % 60
    return if (days > 0) {
        String.format(java.util.Locale.getDefault(), "%dd %02d:%02d:%02d", days, hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    }
}

@Composable
private fun StatTile(
    title: String,
    value: String,
    iconRes: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val cardModifier =
        if (onClick != null) {
            modifier.clickable(onClick = onClick)
        } else {
            modifier
        }
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        border =
            af.shizuku.manager.utils
                .cardBorder(),
        modifier = cardModifier,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(iconRes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                if (onClick != null) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
            )
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

/** Heap usage over the last ~2 minutes (one point per 2 s poll), scaled 0-100%. */
@Composable
private fun MemorySparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val step = size.width / (values.size - 1)
        val line =
            androidx.compose.ui.graphics
                .Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = size.height * (1f - v.coerceIn(0f, 1f))
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val fill =
            androidx.compose.ui.graphics.Path().apply {
                addPath(line)
                lineTo((values.size - 1) * step, size.height)
                lineTo(0f, size.height)
                close()
            }
        drawPath(fill, color.copy(alpha = 0.15f))
        drawPath(
            line,
            color,
            style =
                androidx.compose.ui.graphics.drawscope
                    .Stroke(width = 2.dp.toPx()),
        )
    }
}

/**
 * The container / on-container pair for a scheme role used as an accent (badges, type chips),
 * instead of the role at low alpha, which drifts with whatever sits underneath.
 */
@Composable
private fun containerPair(role: Color): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    return when (role) {
        cs.primary -> cs.primaryContainer to cs.onPrimaryContainer
        cs.secondary -> cs.secondaryContainer to cs.onSecondaryContainer
        cs.tertiary -> cs.tertiaryContainer to cs.onTertiaryContainer
        cs.error -> cs.errorContainer to cs.onErrorContainer
        else -> cs.surfaceContainerHighest to cs.onSurfaceVariant
    }
}
