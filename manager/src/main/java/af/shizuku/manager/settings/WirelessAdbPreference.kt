package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ktx.findActivity
import af.shizuku.manager.utils.HapticUtils
import af.shizuku.manager.utils.NetworkTrust
import af.shizuku.manager.utils.WifiNetworks
import af.shizuku.manager.widget.AppSwitch
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

/**
 * Consolidated Wireless ADB (TCP mode) card: master switch, port, auto-close, network protection
 * and trusted SSIDs in one Compose-rendered row. Replaces the seven individual tcp_* preferences.
 *
 * All values are read from / written to the shared settings prefs directly (the same keys the old
 * rows used), so TcpNetworkGuard and the fragment's OnSharedPreferenceChangeListener keep working.
 * Changes that may need a server restart (mode, port) are routed through [onTcpModeChangeRequest]
 * and [onPortChangeRequest] so the host fragment can prompt; the card only updates once the
 * supplied `apply` callback runs.
 */
class WirelessAdbPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : Preference(context, attrs) {
        /** Request to change TCP mode. Call `apply` to commit; default commits immediately. */
        var onTcpModeChangeRequest: ((enable: Boolean, apply: () -> Unit) -> Unit)? = null

        /** Request to change the port (null = default 5555). Call `apply` to commit. */
        var onPortChangeRequest: ((port: Int?, randomized: Boolean, apply: () -> Unit) -> Unit)? = null

        /** Called when the user enters a port outside 1..65535. */
        var onInvalidPort: (() -> Unit)? = null

        /** TV: TCP mode is always on and the switch can't be toggled. */
        var tcpModeLocked: Boolean = false
            set(value) {
                field = value
                notifyChanged()
            }

        private val tcpMode = mutableStateOf(true)
        private val port = mutableStateOf("")
        private val autoClosePort = mutableStateOf(false)
        private val protectionMode = mutableStateOf(NetworkTrust.MODE_OFF)
        private val trustedSsids = mutableStateOf<Set<String>>(emptySet())
        private val autoCloseUntrusted = mutableStateOf(false)
        private val closingPort = mutableStateOf(false)

        init {
            layoutResource = R.layout.preference_wireless_adb
            isSelectable = false
            isPersistent = false
        }

        /** Shows/hides the "closing port" progress state while the fragment closes the live port. */
        fun setClosingPort(closing: Boolean) {
            closingPort.value = closing
        }

        /** Re-reads every value from prefs (e.g. after an external change). */
        fun reload() {
            val prefs = ShizukuSettings.getPreferences()
            tcpMode.value = tcpModeLocked || ShizukuSettings.getTcpMode()
            port.value = prefs.getString(ShizukuSettings.Keys.KEY_TCP_PORT, null).orEmpty()
            autoClosePort.value = ShizukuSettings.isAutoCloseTcpPortEnabled()
            protectionMode.value = NetworkTrust.mode()
            trustedSsids.value = NetworkTrust.trustedSsids()
            autoCloseUntrusted.value = NetworkTrust.isAutoCloseEnabled()
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            reload()
            // preference_wireless_adb.xml has ComposeView as the root, so holder.itemView IS it.
            // Guard against stale recycled ViewHolders from a mismatched type in the pool.
            val cv = holder.itemView as? ComposeView
            if (cv == null) {
                Log.e("WirelessAdbPreference", "holder.itemView is ${holder.itemView.javaClass.name}, not ComposeView — skipping bind")
                return
            }
            val enabled = isEnabled
            val cardTitle = title?.toString() ?: context.getString(R.string.settings_wireless_adb_title)
            cv.setPreferenceContent { WirelessAdbCard(cardTitle, enabled) }
        }

        private fun requestTcpMode(enable: Boolean) {
            val apply = {
                ShizukuSettings.setTcpMode(enable)
                tcpMode.value = enable
            }
            onTcpModeChangeRequest?.invoke(enable, apply) ?: apply()
        }

        /** Returns false (and reports) when [text] is not a valid port; blank means default. */
        private fun commitPort(
            text: String,
            randomized: Boolean = false,
        ): Boolean {
            val trimmed = text.trim()
            val newPort = trimmed.toIntOrNull()
            if (trimmed.isNotEmpty() && (newPort == null || newPort !in 1..65535)) {
                onInvalidPort?.invoke()
                return false
            }
            val newText = newPort?.toString().orEmpty()
            if (newText == port.value) return true
            val apply = {
                ShizukuSettings.setTcpPort(newPort)
                port.value = newText
            }
            onPortChangeRequest?.invoke(newPort, randomized, apply) ?: apply()
            return true
        }

        private fun setAutoClosePort(value: Boolean) {
            ShizukuSettings
                .getPreferences()
                .edit()
                .putBoolean(ShizukuSettings.Keys.KEY_TCP_AUTO_CLOSE_PORT, value)
                .apply()
            autoClosePort.value = value
        }

        private fun setProtectionMode(mode: String) {
            ShizukuSettings
                .getPreferences()
                .edit()
                .putString(NetworkTrust.KEY_MODE, mode)
                .apply()
            protectionMode.value = mode
        }

        private fun setAutoCloseUntrusted(value: Boolean) {
            ShizukuSettings
                .getPreferences()
                .edit()
                .putBoolean(NetworkTrust.KEY_AUTO_CLOSE, value)
                .apply()
            autoCloseUntrusted.value = value
        }

        private fun removeSsid(ssid: String) {
            TrustedNetworksSheet.Target.TCP.write(context, NetworkTrust.trustedSsids() - ssid)
            trustedSsids.value = NetworkTrust.trustedSsids()
        }

        private fun trustSsid(ssid: String) {
            TrustedNetworksSheet.Target.TCP.write(context, NetworkTrust.trustedSsids() + ssid)
            trustedSsids.value = NetworkTrust.trustedSsids()
        }

        // The picker sheet writes the list; keep the chips in step with it.
        private val trustedListener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == NetworkTrust.KEY_TRUSTED_SSIDS) trustedSsids.value = NetworkTrust.trustedSsids()
            }

        override fun onAttached() {
            super.onAttached()
            ShizukuSettings.getPreferences()?.registerOnSharedPreferenceChangeListener(trustedListener)
        }

        override fun onDetached() {
            super.onDetached()
            ShizukuSettings.getPreferences()?.unregisterOnSharedPreferenceChangeListener(trustedListener)
        }

        @OptIn(ExperimentalLayoutApi::class)
        @Composable
        private fun WirelessAdbCard(
            cardTitle: String,
            enabled: Boolean,
        ) {
            val colors = MaterialTheme.colorScheme
            val isOn = tcpMode.value
            val closing = closingPort.value
            val mode = protectionMode.value

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = androidx.compose.ui.graphics.Color.Transparent,
                contentColor = colors.onSurface,
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    // Header: icon + title/summary + master switch
                    val switchEnabled = enabled && !tcpModeLocked && !closing
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = isOn,
                                    enabled = switchEnabled,
                                    role = Role.Switch,
                                    onValueChange = {
                                        if (it) HapticUtils.toggleOn(view) else HapticUtils.toggleOff(view)
                                        requestTcpMode(it)
                                    },
                                ),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_wadb_24),
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = cardTitle,
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.onSurface,
                            )
                            Text(
                                text =
                                    stringResource(
                                        if (closing) R.string.settings_tcp_mode_closing_port else R.string.settings_tcp_mode_summary,
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        // Row-level toggleable owns the click; the switch is visual only.
                        AppSwitch(checked = isOn, onCheckedChange = null, enabled = switchEnabled)
                    }

                    AnimatedVisibility(
                        visible = isOn,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Column {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 12.dp),
                                color = colors.outlineVariant,
                            )
                            PortSection(enabled && !closing)
                            CheckboxRow(
                                text = stringResource(R.string.settings_tcp_auto_close_port),
                                checked = autoClosePort.value,
                                enabled = enabled,
                                onCheckedChange = ::setAutoClosePort,
                            )

                            Spacer(Modifier.padding(top = 12.dp))
                            SectionLabel(stringResource(R.string.settings_tcp_protection_label))
                            val modes = listOf(NetworkTrust.MODE_OFF, NetworkTrust.MODE_OPEN, NetworkTrust.MODE_TRUSTED)
                            SegmentedChoiceRow(
                                labels =
                                    listOf(
                                        stringResource(R.string.settings_tcp_protection_off),
                                        stringResource(R.string.settings_tcp_protection_open),
                                        stringResource(R.string.settings_tcp_protection_trusted),
                                    ),
                                selectedIndex = modes.indexOf(mode).coerceAtLeast(0),
                                enabled = enabled,
                                onSelect = { setProtectionMode(modes[it]) },
                            )
                            if (mode != NetworkTrust.MODE_OFF && !NetworkTrust.canDetectOpenNetworks()) {
                                Text(
                                    text = stringResource(R.string.tcp_protection_limited_summary),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }

                            AnimatedVisibility(
                                visible = mode == NetworkTrust.MODE_TRUSTED,
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically(),
                            ) {
                                Column(modifier = Modifier.padding(top = 12.dp)) {
                                    SectionLabel(stringResource(R.string.settings_tcp_trusted_networks_label))
                                    TrustedNetworks(enabled)
                                }
                            }

                            // Auto-close applies to both "open" and "trusted" protection modes.
                            AnimatedVisibility(
                                visible = mode != NetworkTrust.MODE_OFF,
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically(),
                            ) {
                                CheckboxRow(
                                    text = stringResource(R.string.settings_tcp_auto_close_untrusted),
                                    checked = autoCloseUntrusted.value,
                                    enabled = enabled,
                                    onCheckedChange = ::setAutoCloseUntrusted,
                                )
                            }
                        }
                    }
                }
            }
        }

        @Composable
        private fun PortSection(enabled: Boolean) {
            val committed = port.value
            var text by remember(committed) { mutableStateOf(committed) }
            val focusManager = LocalFocusManager.current
            val invalid =
                text.isNotBlank() && text.trim().toIntOrNull().let { it == null || it !in 1..65535 }

            fun save() {
                if (text != committed) {
                    if (!commitPort(text)) {
                        text = committed
                    }
                }
            }

            DisposableEffect(Unit) {
                onDispose {
                    try {
                        save()
                    } catch (_: Throwable) {
                    }
                }
            }

            SectionLabel(stringResource(R.string.settings_tcp_port_label))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { new -> text = new.filter { it.isDigit() }.take(5) },
                    enabled = enabled,
                    singleLine = true,
                    isError = invalid,
                    placeholder = { Text("5555") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions =
                        KeyboardActions(
                            onDone = {
                                save()
                                focusManager.clearFocus()
                            },
                        ),
                    modifier =
                        Modifier
                            .weight(1f)
                            .onFocusChanged { state ->
                                if (!state.isFocused) {
                                    save()
                                }
                            },
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    enabled = enabled,
                    onClick = {
                        focusManager.clearFocus()
                        val randomPort = (49152..65535).random().toString()
                        text = randomPort
                        commitPort(randomPort, randomized = true)
                    },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh_24),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_tcp_randomize))
                }
            }
        }

        @OptIn(ExperimentalLayoutApi::class)
        @Composable
        private fun TrustedNetworks(enabled: Boolean) {
            val context = LocalContext.current
            val view = LocalView.current
            // One tap to trust the network you're on, without opening the picker.
            val current by produceState<String?>(null) { value = WifiNetworks.current(context.applicationContext) }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().animateContentSize(),
            ) {
                trustedSsids.value.sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { ssid ->
                    val removeLabel = stringResource(R.string.settings_tcp_remove_network, ssid)
                    InputChip(
                        selected = false,
                        enabled = enabled,
                        onClick = {
                            HapticUtils.toggleOff(view)
                            removeSsid(ssid)
                        },
                        label = { Text(ssid) },
                        trailingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_close_24),
                                contentDescription = removeLabel,
                                modifier = Modifier.size(InputChipDefaults.IconSize),
                            )
                        },
                    )
                }
                current?.takeIf { it !in trustedSsids.value }?.let { ssid ->
                    SuggestionChip(
                        enabled = enabled,
                        onClick = {
                            HapticUtils.toggleOn(view)
                            trustSsid(ssid)
                        },
                        label = { Text(stringResource(R.string.trusted_networks_trust_current, ssid)) },
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_wifi_24),
                                contentDescription = null,
                                modifier = Modifier.size(SuggestionChipDefaults.IconSize),
                            )
                        },
                    )
                }
                AssistChip(
                    enabled = enabled,
                    onClick = {
                        val activity = context.findActivity<androidx.fragment.app.FragmentActivity>() ?: return@AssistChip
                        TrustedNetworksSheet.show(activity.supportFragmentManager, TrustedNetworksSheet.Target.TCP)
                    },
                    label = { Text(stringResource(R.string.trusted_networks_choose)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_add_24),
                            contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize),
                        )
                    },
                )
            }
        }

        @Composable
        private fun SectionLabel(text: String) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        @Composable
        private fun CheckboxRow(
            text: String,
            checked: Boolean,
            enabled: Boolean,
            onCheckedChange: (Boolean) -> Unit,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .toggleable(
                            value = checked,
                            enabled = enabled,
                            role = Role.Checkbox,
                            onValueChange = onCheckedChange,
                        ),
            ) {
                Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
