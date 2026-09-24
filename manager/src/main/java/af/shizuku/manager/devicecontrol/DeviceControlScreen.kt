package af.shizuku.manager.devicecontrol

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import af.shizuku.manager.R
import rikka.shizuku.ShizukuPlusAPI
import timber.log.Timber

private const val STREAM_RING = 2
private const val STREAM_MUSIC = 3
private const val STREAM_ALARM = 4

// Most Android devices top out at index 15; used as slider ceiling when no max API is present.
private const val VOLUME_MAX = 15

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceControlScreen(onBackClick: () -> Unit) {
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }

    // ── Connectivity state ────────────────────────────────────────────────────
    var airplane by remember { mutableStateOf(false) }
    var wifi by remember { mutableStateOf(false) }
    var bluetooth by remember { mutableStateOf(false) }
    var mobileData by remember { mutableStateOf(false) }
    var nfc by remember { mutableStateOf(false) }
    // private_dns_mode: "off" | "opportunistic" | "hostname"
    var dnsMode by remember { mutableStateOf("opportunistic") }
    var dnsHostname by remember { mutableStateOf("") }

    // ── Display state ─────────────────────────────────────────────────────────
    var autoBrightness by remember { mutableStateOf(true) }
    var brightness by remember { mutableIntStateOf(128) }
    var autoRotate by remember { mutableStateOf(false) }
    // screen_off_timeout in ms; -1 = index 0 (Never) in TIMEOUT_OPTIONS
    var screenTimeoutMs by remember { mutableIntStateOf(60000) }

    // ── Audio state ───────────────────────────────────────────────────────────
    var volumeMedia by remember { mutableIntStateOf(8) }
    var volumeRing by remember { mutableIntStateOf(8) }
    var volumeAlarm by remember { mutableIntStateOf(8) }

    // ── System appearance state ───────────────────────────────────────────────
    var animations by remember { mutableStateOf(true) }
    var fontScale by remember { mutableFloatStateOf(1.0f) }

    // ── Power confirmation dialogs ─────────────────────────────────────────────
    var showRebootDialog by remember { mutableStateOf(false) }
    var showShutdownDialog by remember { mutableStateOf(false) }

    // Load initial state from device. Reads happen on IO; the Compose state is applied
    // back on the main thread (matching the other screens) to avoid off-main mutation.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val airplaneV = ShizukuPlusAPI.DeviceControl.getSetting("global", "airplane_mode_on") == "1"
                // wifi_on: 0=off, 1=on (Settings.Global)
                val wifiV = ShizukuPlusAPI.DeviceControl.getSetting("global", "wifi_on") == "1"
                // bluetooth_on: 0=off, 1=on
                val bluetoothV = ShizukuPlusAPI.DeviceControl.getSetting("global", "bluetooth_on") == "1"
                // mobile_data: 0=off, 1=on
                val mobileDataV = ShizukuPlusAPI.DeviceControl.getSetting("global", "mobile_data") == "1"
                // nfc_on is in secure namespace on most Android versions
                val nfcV = ShizukuPlusAPI.DeviceControl.getSetting("secure", "nfc_on") == "1"
                val dnsModeV = ShizukuPlusAPI.DeviceControl.getSetting("global", "private_dns_mode") ?: "opportunistic"
                val dnsHostnameV = ShizukuPlusAPI.DeviceControl.getSetting("global", "private_dns_specifier") ?: ""

                val autoBrightnessV = ShizukuPlusAPI.DeviceControl.getSetting("system", "screen_brightness_mode") == "1"
                val brightnessV = ShizukuPlusAPI.DeviceControl.getSetting("system", "screen_brightness")?.toIntOrNull() ?: 128
                val screenTimeoutMsV = ShizukuPlusAPI.DeviceControl.getSetting("system", "screen_off_timeout")?.toIntOrNull() ?: 60000
                val autoRotateV = ShizukuPlusAPI.DeviceControl.getSetting("system", "accelerometer_rotation") == "1"
                val animationsV = ShizukuPlusAPI.DeviceControl.getSetting("global", "window_animation_scale") != "0.0"
                val fontScaleV = ShizukuPlusAPI.DeviceControl.getSetting("system", "font_scale")?.toFloatOrNull() ?: 1.0f

                val volumeMediaV = ShizukuPlusAPI.DeviceControl.getStreamVolume(STREAM_MUSIC).coerceIn(0, VOLUME_MAX)
                val volumeRingV = ShizukuPlusAPI.DeviceControl.getStreamVolume(STREAM_RING).coerceIn(0, VOLUME_MAX)
                val volumeAlarmV = ShizukuPlusAPI.DeviceControl.getStreamVolume(STREAM_ALARM).coerceIn(0, VOLUME_MAX)

                withContext(Dispatchers.Main) {
                    airplane = airplaneV
                    wifi = wifiV
                    bluetooth = bluetoothV
                    mobileData = mobileDataV
                    nfc = nfcV
                    dnsMode = dnsModeV
                    dnsHostname = dnsHostnameV
                    autoBrightness = autoBrightnessV
                    brightness = brightnessV
                    screenTimeoutMs = screenTimeoutMsV
                    autoRotate = autoRotateV
                    animations = animationsV
                    fontScale = fontScaleV
                    volumeMedia = volumeMediaV
                    volumeRing = volumeRingV
                    volumeAlarm = volumeAlarmV
                }
            } catch (e: Exception) {
                Timber.w(e, "DeviceControl: failed to read initial state")
            } finally {
                withContext(Dispatchers.Main) { isLoading = false }
            }
        }
    }

    if (showRebootDialog) {
        AlertDialog(
            onDismissRequest = { showRebootDialog = false },
            title = { Text(stringResource(R.string.device_control_reboot_title)) },
            text = { Text(stringResource(R.string.device_control_reboot_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showRebootDialog = false
                    scope.launch(Dispatchers.IO) {
                        try { ShizukuPlusAPI.DeviceControl.reboot(null) }
                        catch (e: Exception) { Timber.e(e, "reboot failed") }
                    }
                }) { Text(stringResource(R.string.device_control_reboot)) }
            },
            dismissButton = {
                TextButton(onClick = { showRebootDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (showShutdownDialog) {
        AlertDialog(
            onDismissRequest = { showShutdownDialog = false },
            title = { Text(stringResource(R.string.device_control_shutdown_title)) },
            text = { Text(stringResource(R.string.device_control_shutdown_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showShutdownDialog = false
                    scope.launch(Dispatchers.IO) {
                        try { ShizukuPlusAPI.DeviceControl.shutdown() }
                        catch (e: Exception) { Timber.e(e, "shutdown failed") }
                    }
                }) { Text(stringResource(R.string.device_control_shutdown)) }
            },
            dismissButton = {
                TextButton(onClick = { showShutdownDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_device_control_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painterResource(R.drawable.ic_back_24),
                            contentDescription = stringResource(R.string.nav_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // ── Connectivity ──────────────────────────────────────────────────
            item { SectionHeader(stringResource(R.string.device_control_section_connectivity)) }

            item {
                ControlToggleRow(
                    label = stringResource(R.string.device_control_airplane_mode),
                    checked = airplane,
                    onCheckedChange = { v ->
                        airplane = v
                        scope.launch(Dispatchers.IO) {
                            runCatching { ShizukuPlusAPI.DeviceControl.setAirplaneModeEnabled(v) }
                        }
                    }
                )
            }
            item {
                ControlToggleRow(
                    label = stringResource(R.string.device_control_wifi),
                    checked = wifi,
                    onCheckedChange = { v ->
                        wifi = v
                        scope.launch(Dispatchers.IO) {
                            runCatching { ShizukuPlusAPI.DeviceControl.setWifiEnabled(v) }
                        }
                    }
                )
            }
            item {
                ControlToggleRow(
                    label = stringResource(R.string.device_control_bluetooth),
                    checked = bluetooth,
                    onCheckedChange = { v ->
                        bluetooth = v
                        scope.launch(Dispatchers.IO) {
                            runCatching { ShizukuPlusAPI.DeviceControl.setBluetoothEnabled(v) }
                        }
                    }
                )
            }
            item {
                ControlToggleRow(
                    label = stringResource(R.string.device_control_mobile_data),
                    checked = mobileData,
                    onCheckedChange = { v ->
                        mobileData = v
                        scope.launch(Dispatchers.IO) {
                            runCatching { ShizukuPlusAPI.DeviceControl.setMobileDataEnabled(v) }
                        }
                    }
                )
            }
            item {
                ControlToggleRow(
                    label = stringResource(R.string.device_control_nfc),
                    checked = nfc,
                    onCheckedChange = { v ->
                        nfc = v
                        scope.launch(Dispatchers.IO) {
                            runCatching { ShizukuPlusAPI.DeviceControl.setNfcEnabled(v) }
                        }
                    }
                )
            }
            item {
                PrivateDnsRow(
                    mode = dnsMode,
                    hostname = dnsHostname,
                    onApply = { mode, host ->
                        dnsMode = mode
                        dnsHostname = host
                        scope.launch(Dispatchers.IO) {
                            runCatching { ShizukuPlusAPI.NetworkGovernor.setPrivateDns(mode, host) }
                        }
                    }
                )
            }

            // ── Display ───────────────────────────────────────────────────────
            item { Spacer(Modifier.height(8.dp)) }
            item { SectionHeader(stringResource(R.string.device_control_section_display)) }

            item {
                ControlToggleRow(
                    label = stringResource(R.string.device_control_auto_brightness),
                    checked = autoBrightness,
                    onCheckedChange = { v ->
                        autoBrightness = v
                        scope.launch(Dispatchers.IO) {
                            runCatching { ShizukuPlusAPI.DeviceControl.setAutoBrightnessEnabled(v) }
                        }
                    }
                )
            }

            if (!autoBrightness) {
                item {
                    ControlSliderRow(
                        label = stringResource(R.string.device_control_brightness, brightness),
                        value = brightness.toFloat(),
                        valueRange = 0f..255f,
                        onValueChangeFinished = { v ->
                            brightness = v.toInt()
                            scope.launch(Dispatchers.IO) {
                                runCatching { ShizukuPlusAPI.DeviceControl.setScreenBrightness(v.toInt()) }
                            }
                        }
                    )
                }
            }

            item {
                ControlToggleRow(
                    label = stringResource(R.string.device_control_auto_rotate),
                    checked = autoRotate,
                    onCheckedChange = { v ->
                        autoRotate = v
                        scope.launch(Dispatchers.IO) {
                            runCatching { ShizukuPlusAPI.DeviceControl.setAutoRotateEnabled(v) }
                        }
                    }
                )
            }
            item {
                ScreenTimeoutRow(
                    currentMs = screenTimeoutMs,
                    onSelect = { ms ->
                        screenTimeoutMs = ms
                        scope.launch(Dispatchers.IO) {
                            runCatching { ShizukuPlusAPI.DeviceControl.setScreenTimeout(ms) }
                        }
                    }
                )
            }

            // ── Audio ──────────────────────────────────────────────────────────
            item { Spacer(Modifier.height(8.dp)) }
            item { SectionHeader(stringResource(R.string.device_control_section_audio)) }

            item {
                ControlSliderRow(
                    label = stringResource(R.string.device_control_volume_media, volumeMedia),
                    value = volumeMedia.toFloat(),
                    valueRange = 0f..VOLUME_MAX.toFloat(),
                    steps = VOLUME_MAX - 1,
                    onValueChangeFinished = { v ->
                        volumeMedia = v.toInt()
                        scope.launch(Dispatchers.IO) {
                            runCatching { ShizukuPlusAPI.DeviceControl.setStreamVolume(STREAM_MUSIC, v.toInt()) }
                        }
                    }
                )
            }
            item {
                ControlSliderRow(
                    label = stringResource(R.string.device_control_volume_ring, volumeRing),
                    value = volumeRing.toFloat(),
                    valueRange = 0f..VOLUME_MAX.toFloat(),
                    steps = VOLUME_MAX - 1,
                    onValueChangeFinished = { v ->
                        volumeRing = v.toInt()
                        scope.launch(Dispatchers.IO) {
                            runCatching { ShizukuPlusAPI.DeviceControl.setStreamVolume(STREAM_RING, v.toInt()) }
                        }
                    }
                )
            }
            item {
                ControlSliderRow(
                    label = stringResource(R.string.device_control_volume_alarm, volumeAlarm),
                    value = volumeAlarm.toFloat(),
                    valueRange = 0f..VOLUME_MAX.toFloat(),
                    steps = VOLUME_MAX - 1,
                    onValueChangeFinished = { v ->
                        volumeAlarm = v.toInt()
                        scope.launch(Dispatchers.IO) {
                            runCatching { ShizukuPlusAPI.DeviceControl.setStreamVolume(STREAM_ALARM, v.toInt()) }
                        }
                    }
                )
            }

            // ── System Appearance ─────────────────────────────────────────────
            item { Spacer(Modifier.height(8.dp)) }
            item { SectionHeader(stringResource(R.string.device_control_section_system)) }

            item {
                ControlToggleRow(
                    label = stringResource(R.string.device_control_animations),
                    checked = animations,
                    onCheckedChange = { v ->
                        animations = v
                        scope.launch(Dispatchers.IO) {
                            runCatching { ShizukuPlusAPI.DeviceControl.setAnimationsEnabled(v) }
                        }
                    }
                )
            }

            item {
                ControlSliderRow(
                    label = stringResource(R.string.device_control_font_scale, "%.2f".format(fontScale)),
                    value = fontScale,
                    valueRange = 0.70f..2.00f,
                    onValueChangeFinished = { v ->
                        fontScale = v
                        scope.launch(Dispatchers.IO) {
                            runCatching { ShizukuPlusAPI.DeviceControl.setFontScale(v) }
                        }
                    }
                )
            }

            // ── Power ─────────────────────────────────────────────────────────
            item { Spacer(Modifier.height(8.dp)) }
            item { SectionHeader(stringResource(R.string.device_control_section_power)) }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { showRebootDialog = true }
                    ) {
                        Text(stringResource(R.string.device_control_reboot))
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { showShutdownDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.device_control_shutdown))
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun ControlToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ControlSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChangeFinished: (Float) -> Unit
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp)
        )
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onValueChangeFinished(sliderValue) },
            valueRange = valueRange,
            steps = steps
        )
    }
}

private data class TimeoutOption(val ms: Int, val label: String)

private val TIMEOUT_OPTIONS = listOf(
    TimeoutOption(15_000,   "15 seconds"),
    TimeoutOption(30_000,   "30 seconds"),
    TimeoutOption(60_000,   "1 minute"),
    TimeoutOption(120_000,  "2 minutes"),
    TimeoutOption(300_000,  "5 minutes"),
    TimeoutOption(600_000,  "10 minutes"),
    TimeoutOption(1_800_000,"30 minutes"),
)

@Composable
private fun ScreenTimeoutRow(currentMs: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = TIMEOUT_OPTIONS.firstOrNull { it.ms == currentMs }?.label
        ?: "${currentMs / 1000}s"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.device_control_screen_timeout),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text(label) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                TIMEOUT_OPTIONS.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            expanded = false
                            onSelect(option.ms)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivateDnsRow(
    mode: String,
    hostname: String,
    onApply: (mode: String, hostname: String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var editHostname by remember(hostname) { mutableStateOf(hostname) }

    val modeLabel = when (mode) {
        "off" -> stringResource(R.string.device_control_dns_off)
        "hostname" -> stringResource(R.string.device_control_dns_custom)
        else -> stringResource(R.string.device_control_dns_auto)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.device_control_private_dns),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Box {
                OutlinedButton(onClick = { expanded = true }) { Text(modeLabel) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.device_control_dns_off)) },
                        onClick = { expanded = false; onApply("off", "") }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.device_control_dns_auto)) },
                        onClick = { expanded = false; onApply("opportunistic", "") }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.device_control_dns_custom)) },
                        onClick = { expanded = false; onApply("hostname", editHostname) }
                    )
                }
            }
        }

        if (mode == "hostname") {
            OutlinedTextField(
                value = editHostname,
                onValueChange = { editHostname = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.device_control_dns_hostname_label)) },
                singleLine = true,
                trailingIcon = {
                    TextButton(onClick = { onApply("hostname", editHostname) }) {
                        Text(stringResource(R.string.device_control_dns_apply))
                    }
                }
            )
        }
    }
}
