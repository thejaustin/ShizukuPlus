package af.shizuku.manager.home.compose

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import af.shizuku.manager.R
import af.shizuku.manager.database.ActivityLogManager
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*
import coil3.compose.AsyncImage

@Composable
fun ServerMetricsScreen() {
    var uptimeText by remember { mutableStateOf("00:00:00") }
    var clientCountText by remember { mutableStateOf("0 applications connected") }
    var memoryProgress by remember { mutableFloatStateOf(0f) }
    var memoryDetails by remember { mutableStateOf("0 MB / 0 MB") }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                if (Shizuku.pingBinder()) {
                    val binder = Shizuku.getBinder()
                    val shizukuService = moe.shizuku.server.IShizukuService.Stub.asInterface(binder)
                    val ai = shizukuService.aiCorePlus
                    if (ai == null) {
                        // Null when connected to a stock/mismatched server that doesn't implement
                        // the Plus AICore extension (SHIZUKUPLUS-6J) - not an error, just unavailable.
                        delay(1000)
                        continue
                    }
                    val stats = ai.serverStats

                    val uptimeMs = stats.getLong("uptime_ms")
                    val seconds = (uptimeMs / 1000) % 60
                    val minutes = (uptimeMs / (1000 * 60)) % 60
                    val hours = (uptimeMs / (1000 * 60 * 60)) % 24
                    val days = (uptimeMs / (1000 * 60 * 60 * 24))
                    uptimeText = if (days > 0) String.format("%dd %02d:%02d:%02d", days, hours, minutes, seconds)
                    else String.format("%02d:%02d:%02d", hours, minutes, seconds)
                    
                    clientCountText = "${stats.getInt("client_count")} applications connected"
                    
                    val maxRaw = stats.getLong("mem_max")
                    val total = stats.getLong("mem_total")
                    val free = stats.getLong("mem_free")
                    val used = (total - free).coerceAtLeast(0L)
                    val max = if (maxRaw == Long.MAX_VALUE || maxRaw <= 0) total else maxRaw
                    
                    memoryProgress = if (max > 0) (used.toFloat() / max.toFloat()).coerceIn(0f, 1f) else 0f
                    
                    val formatSize = { bytes: Long ->
                        val kb = bytes / 1024
                        val mb = kb / 1024
                        val gb = mb / 1024
                        if (gb > 0) "$gb GB" else if (mb > 0) "$mb MB" else "$kb KB"
                    }
                    val maxStr = if (maxRaw == Long.MAX_VALUE) "Uncapped" else formatSize(max)
                    memoryDetails = "${formatSize(used)} / $maxStr (Max Allowed)"
                }
            } catch (e: Exception) {
                Timber.w(e)
            }
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        MetricCard(title = "Server Uptime", value = uptimeText)
        MetricCard(title = "Active Connections", value = clientCountText)
        Card(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Server Memory Usage", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { memoryProgress },
                    modifier = Modifier.fillMaxWidth().height(12.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(memoryDetails, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun MetricCard(title: String, value: String) {
    Card(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
fun ActivityLogScreen() {
    val logs by ActivityLogManager.logs.collectAsState(initial = emptyList())
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val context = LocalContext.current
    val pm = context.packageManager

    if (logs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painterResource(R.drawable.ic_empty_log_24), contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Activity Log is empty", style = MaterialTheme.typography.titleMedium)
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(logs, key = { it.timestamp.toString() + it.packageName }) { record ->
                var appName = record.appName.ifEmpty { record.packageName }
                var iconModel: Any = R.drawable.ic_system_icon
                try {
                    val ai = pm.getApplicationInfo(record.packageName, 0)
                    appName = ai.loadLabel(pm).toString()
                    iconModel = ai.loadIcon(pm)
                } catch (e: Exception) {}

                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = iconModel,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(appName, style = MaterialTheme.typography.titleMedium)
                        Text(record.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(record.action, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(dateFormat.format(Date(record.timestamp)), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
