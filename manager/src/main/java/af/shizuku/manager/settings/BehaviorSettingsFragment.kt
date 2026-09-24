package af.shizuku.manager.settings

import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.Keys.*
import af.shizuku.manager.app.SnackbarHelper
import af.shizuku.manager.service.ShizukuLiveService
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.utils.DeviceOptimizer
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.net.Uri
import androidx.preference.ListPreference
import af.shizuku.manager.BuildConfig
import af.shizuku.manager.update.UpdateChecker
import af.shizuku.manager.update.UpdateManager
import org.koin.android.ext.android.inject
import timber.log.Timber

class BehaviorSettingsFragment : BaseSettingsFragment(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val TAG = "BehaviorSettingsFragment"
        private const val KEY_AUTO_UPDATE = "auto_update_enabled"
        private const val KEY_AUTO_INSTALL = "auto_install_enabled"
        private const val KEY_CHECK_FOR_UPDATE = "check_for_update"
        private const val KEY_LAST_CHECK = "last_check_time"
        private const val RELEASES_URL = "https://github.com/thejaustin/ShizukuPlus/releases"
    }

    private val updateManager: UpdateManager by inject()

    override fun getTitle(): CharSequence? = getString(R.string.settings_main_nav_startup_behavior_title)

    private lateinit var startOnBootPreference: TwoStatePreference
    private lateinit var watchdogPreference: TwoStatePreference
    private lateinit var deviceHardeningPreference: TwoStatePreference
    private lateinit var tcpModePreference: TwoStatePreference
    private lateinit var tcpPortPreference: EditTextPreference
    private lateinit var networkCategory: CollapsiblePreferenceCategory
    private lateinit var startupCategory: CollapsiblePreferenceCategory
    // True when tcp_mode is logically available on this device (TLS-capable or TV).
    // Used to gate syncTcpPortVisibility() so root-mode hides are not undone.
    private var tcpModeAvailable = false

    private val stateListener: (ShizukuStateMachine.State) -> Unit = {
        if (ShizukuStateMachine.isRunning()) {
            tcpModePreference.icon = maybeGetRestartIcon(KEY_TCP_MODE)
            tcpPortPreference.icon = maybeGetRestartIcon(KEY_TCP_PORT)
        }
    }

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_behavior, rootKey)
        val context = requireContext()

        startOnBootPreference = requireNotNull(findPreference(KEY_START_ON_BOOT))
        watchdogPreference = requireNotNull(findPreference(KEY_WATCHDOG))
        deviceHardeningPreference = requireNotNull(findPreference(KEY_DEVICE_HARDENING_ENABLED))
        tcpModePreference = requireNotNull(findPreference(KEY_TCP_MODE))
        tcpPortPreference = requireNotNull(findPreference(KEY_TCP_PORT))
        networkCategory = requireNotNull(findPreference("category_network_activity"))
        startupCategory = requireNotNull(findPreference("category_startup"))

        startOnBootPreference.apply {
            isChecked = ShizukuSettings.getStartOnBoot(context)
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) {
                    val doToggle = {
                        maybeToggleSecureSetting(newValue) { secureResult ->
                            if (secureResult) {
                                maybeToggleBatterySensitiveSetting(newValue) { batteryResult ->
                                    if (batteryResult) {
                                        ShizukuSettings.setStartOnBoot(context, newValue)
                                        isChecked = ShizukuSettings.getStartOnBoot(context)
                                    }
                                }
                            }
                        }
                    }
                    // https://r.android.com/2128832
                        if (newValue &&
                            !EnvironmentUtils.isTelevision() &&
                            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                        ) {
                            showDialog(
                                MaterialAlertDialogBuilder(context)
                                    .setTitle(android.R.string.dialog_alert_title)
                                    .setMessage(R.string.settings_start_on_boot_bug)
                                    .setPositiveButton(android.R.string.ok) { _, _ -> doToggle() }
                                    .setNegativeButton(android.R.string.cancel) { _, _ -> isChecked = !newValue }
                            )
                        } else {
                            doToggle()
                        }
                    }
                    false
                }
        }

        watchdogPreference.apply {
            isChecked = ShizukuSettings.isWatchdogRunning()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) {
                    maybeToggleBatterySensitiveSetting(newValue) { result ->
                        if (result) {
                            ShizukuSettings.setWatchdog(context, newValue)
                            isChecked = newValue
                        }
                    }
                }
                false
            }
        }

        deviceHardeningPreference.apply {
            isChecked = ShizukuSettings.isDeviceHardeningEnabled()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) {
                    ShizukuSettings.setDeviceHardeningEnabled(newValue)
                    isChecked = newValue
                    if (newValue) {
                        lifecycleScope.launch {
                            val success = DeviceOptimizer.applyFixes(context)
                            if (success) {
                                Toast.makeText(context, R.string.device_hardening_applied, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                true
            }
        }

        tcpModePreference.apply {
            if (EnvironmentUtils.isTlsSupported()) {
                tcpModeAvailable = true
                summary = context.getString(R.string.settings_tcp_mode_summary)
                icon = maybeGetRestartIcon(KEY_TCP_MODE)
                setOnPreferenceChangeListener { _, newValue ->
                    if (newValue is Boolean) {
                        val applyChange: () -> Unit = {
                            ShizukuSettings.setTcpMode(newValue)
                            isChecked = newValue
                            isEnabled = true
                            summary = context.getString(R.string.settings_tcp_mode_summary)
                            icon = maybeGetRestartIcon(KEY_TCP_MODE)
                            syncTcpPortVisibility()
                        }
                        if (!newValue && !ShizukuStateMachine.isRunning() && needsRestart(KEY_TCP_MODE, newValue)) {
                            promptStopTcp(tcpModePreference) { applyChange() }
                        } else {
                            maybePromptRestart(KEY_TCP_MODE, newValue) { applyChange() }
                        }
                    }
                    false
                }
            } else if (EnvironmentUtils.isTelevision()) {
                tcpModeAvailable = true
                isEnabled = false
                isChecked = true
            } else {
                // Non-TLS device: hide tcp_mode and tcp_port through the category so
                // expand/collapse cycles don't accidentally restore them.
                networkCategory.setChildAvailable(KEY_TCP_MODE, false)
                networkCategory.setChildAvailable(KEY_TCP_PORT, false)
            }
        }

        tcpPortPreference.apply {
            syncTcpPortVisibility()
            icon = maybeGetRestartIcon(KEY_TCP_PORT)
            setOnBindEditTextListener { editText ->
                editText.hint = context.getString(R.string.settings_tcp_port_hint)
                editText.inputType = InputType.TYPE_CLASS_NUMBER
                editText.setSelection(editText.text.length)
            }
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                val text = pref.text
                if (text.isNullOrEmpty()) context.getString(R.string.settings_tcp_port_default) else text
            }
            setOnPreferenceChangeListener { _, newValue ->
                val port = (newValue as? String)?.toIntOrNull()
                if (port == null || port in 1..65535) {
                    val applyChange: () -> Unit = {
                        ShizukuSettings.setTcpPort(port)
                        text = port?.toString()
                        icon = maybeGetRestartIcon(KEY_TCP_PORT)
                    }
                    maybePromptRestart(KEY_TCP_PORT, port ?: 5555) { applyChange() }
                } else {
                    SnackbarHelper.show(context, requireView(), context.getString(R.string.snackbar_invalid_port))
                }
                false
            }
        }

        findPreference<TwoStatePreference>(KEY_AUTO_DISABLE_USB_DEBUGGING)?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) {
                    maybeToggleSecureSetting(newValue) { result ->
                        if (result) {
                            isChecked = newValue
                        }
                    }
                }
                false
            }
        }

        findPreference<TwoStatePreference>(KEY_LIVE_ACTIVITY_ENABLED)?.setOnPreferenceChangeListener { _, newValue ->
            val enable = newValue as Boolean
            val ctx = requireContext()
            val svcIntent = Intent(ctx, ShizukuLiveService::class.java)
            if (enable) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(svcIntent)
                else ctx.startService(svcIntent)
            } else {
                ctx.stopService(svcIntent)
            }
            true
        }

        syncModeVisibility()
        setupAutoUpdatePreference()
        setupAutoInstallPreference()
        setupChannelPreference()
        setupCheckForUpdatePreference()
        updateLastCheckSummary()
    }

    /**
     * Show/hide preferences that are only relevant to the current connection mode (#433).
     *
     * ADB-only: tcp_mode, tcp_port, auto_reconnect_mdns -- meaningless in root mode since root
     *   starts the server directly without a TCP/wireless ADB connection.
     *
     * When in root mode the "Network" and "Startup & Recovery" category headers gain an
     * explanatory summary so users understand why those options are absent.
     * Mode is refreshed on every onResume() so it updates if the user switches modes.
     */
    private fun syncModeVisibility() {
        val isRootMode = EnvironmentUtils.isRooted() ||
            ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ROOT
        val isAdbMode = !isRootMode

        // Route through setChildAvailable so the category's expand/collapse cycle
        // does not restore preferences that should stay hidden in root mode.
        if (isRootMode) {
            networkCategory.setChildAvailable(KEY_TCP_MODE, false)
            networkCategory.setChildAvailable(KEY_TCP_PORT, false)
        } else if (tcpModeAvailable) {
            networkCategory.setChildAvailable(KEY_TCP_MODE, true)
            syncTcpPortVisibility()
        }
        startupCategory.setChildAvailable(KEY_AUTO_RECONNECT_MDNS, isAdbMode)

        // Category summaries as mode indicators
        networkCategory.summary = if (isRootMode) getString(R.string.settings_mode_indicator_root) else null
        startupCategory.summary = if (isRootMode) getString(R.string.settings_mode_indicator_root) else null
    }

    private fun syncTcpPortVisibility() {
        val isRootMode = EnvironmentUtils.isRooted() ||
            ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ROOT
        if (tcpModeAvailable && !isRootMode) {
            networkCategory.setChildAvailable(KEY_TCP_PORT, tcpModePreference.isChecked)
        }
    }

    override fun onResume() {
        super.onResume()
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        ShizukuStateMachine.addListener(stateListener)
        syncModeVisibility()
    }

    override fun onPause() {
        ShizukuStateMachine.removeListener(stateListener)
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == KEY_WATCHDOG) watchdogPreference.isChecked = ShizukuSettings.isWatchdogRunning()
    }

    private fun setupAutoUpdatePreference() {
        val pref = findPreference<TwoStatePreference>(KEY_AUTO_UPDATE) ?: return
        pref.isChecked = ShizukuSettings.isAutoUpdateEnabled()
        pref.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            ShizukuSettings.setAutoUpdateEnabled(enabled)
            pref.isChecked = enabled
            if (!enabled) {
                findPreference<TwoStatePreference>(KEY_AUTO_INSTALL)?.isChecked = false
                ShizukuSettings.setAutoInstallEnabled(false)
            }
            false
        }
    }

    private fun setupAutoInstallPreference() {
        val pref = findPreference<TwoStatePreference>(KEY_AUTO_INSTALL) ?: return
        pref.isChecked = ShizukuSettings.isAutoInstallEnabled()
        pref.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            if (enabled && !updateManager.canRequestPackageInstalls()) {
                showPermissionRequiredDialog()
                return@setOnPreferenceChangeListener false
            }
            ShizukuSettings.setAutoInstallEnabled(enabled)
            pref.isChecked = enabled
            false
        }
    }

    private fun setupChannelPreference() {
        val pref = findPreference<ListPreference>(KEY_UPDATE_CHANNEL) ?: return
        pref.value = ShizukuSettings.getUpdateChannel()
        pref.setOnPreferenceChangeListener { _, newValue ->
            val channel = newValue as String
            if (channel == "dev") {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.update_channel_dev_warning_title)
                    .setMessage(R.string.update_channel_dev_warning_message)
                    .setPositiveButton(R.string.update_channel_dev) { _, _ ->
                        ShizukuSettings.setUpdateChannel("dev")
                        pref.value = "dev"
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                false
            } else {
                ShizukuSettings.setUpdateChannel("stable")
                true
            }
        }
    }

    private fun setupCheckForUpdatePreference() {
        findPreference<Preference>(KEY_CHECK_FOR_UPDATE)?.setOnPreferenceClickListener {
            checkForUpdate()
            true
        }
    }

    private fun updateLastCheckSummary() {
        val pref = findPreference<Preference>(KEY_LAST_CHECK) ?: return
        val lastCheck = ShizukuSettings.getLastUpdateCheckTime()
        pref.summary = when {
            ShizukuSettings.wasLastUpdateCheckFailed() && lastCheck > 0 -> {
                val date = UpdateChecker.formatPublishedDate(
                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                        .format(java.util.Date(lastCheck))
                )
                "$date · ${getString(R.string.update_last_check_failed)}"
            }
            ShizukuSettings.wasLastUpdateCheckFailed() ->
                getString(R.string.update_last_check_failed)
            lastCheck > 0 ->
                UpdateChecker.formatPublishedDate(
                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                        .format(java.util.Date(lastCheck))
                )
            else ->
                getString(R.string.update_never_checked)
        }
    }

    private fun checkForUpdate() {
        val context = context ?: return
        Toast.makeText(context, R.string.update_checking, Toast.LENGTH_SHORT).show()
        val channel = ShizukuSettings.getUpdateChannel()
        lifecycleScope.launch {
            try {
                when (val result = UpdateChecker.checkForUpdate(channel)) {
                    is UpdateChecker.CheckResult.UpdateAvailable -> {
                        if (isAdded) {
                            ShizukuSettings.setLastUpdateCheckTime(System.currentTimeMillis())
                            ShizukuSettings.setLastUpdateCheckFailed(false)
                            updateLastCheckSummary()
                            showUpdateAvailableDialog(result.info)
                        }
                    }
                    is UpdateChecker.CheckResult.UpToDate -> {
                        if (isAdded) {
                            ShizukuSettings.setLastUpdateCheckTime(System.currentTimeMillis())
                            ShizukuSettings.setLastUpdateCheckFailed(false)
                            updateLastCheckSummary()
                            showUpToDateDialog()
                        }
                    }
                    is UpdateChecker.CheckResult.NetworkError -> {
                        if (isAdded) {
                            ShizukuSettings.setLastUpdateCheckFailed(true)
                            updateLastCheckSummary()
                            showErrorDialog()
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Unexpected error checking for update")
                if (isAdded) {
                    ShizukuSettings.setLastUpdateCheckFailed(true)
                    updateLastCheckSummary()
                    showErrorDialog()
                }
            }
        }
    }

    private fun showUpdateAvailableDialog(info: UpdateChecker.UpdateInfo) {
        val context = context ?: return
        val devBadge = if (info.isPrerelease) " ⚠ Dev" else ""
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.update_available_title) + devBadge)
            .setNegativeButton(R.string.update_later, null)
            .setNeutralButton(R.string.update_release_notes) { _, _ ->
                openReleasesPage()
            }

        if (info.requiresManualDownload) {
            builder
                .setMessage(getString(R.string.update_available_manual_message, info.versionName))
                .setPositiveButton(R.string.update_view_on_github) { _, _ -> openReleasesPage() }
        } else {
            builder
                .setMessage(getString(R.string.update_available_message, info.versionName))
                .setPositiveButton(R.string.update_download) { _, _ ->
                    updateManager.downloadUpdate(info.downloadUrl, info.versionName, manual = true)
                }
        }

        builder.show()
    }

    private fun showUpToDateDialog() {
        val context = context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.update_up_to_date_title)
            .setMessage(getString(R.string.update_up_to_date_message, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showErrorDialog() {
        val context = context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.update_error_title)
            .setMessage(R.string.update_error_message)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.update_view_on_github) { _, _ -> openReleasesPage() }
            .show()
    }

    private fun showPermissionRequiredDialog() {
        val context = context ?: return
        try {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
            startActivity(intent)
        } catch (_: Exception) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.update_permission_required_title)
                .setMessage(R.string.update_permission_required_message)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    private fun openReleasesPage() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: android.content.ActivityNotFoundException) {
            Timber.w(e, "No activity found to handle releases URL")
        }
    }
}
