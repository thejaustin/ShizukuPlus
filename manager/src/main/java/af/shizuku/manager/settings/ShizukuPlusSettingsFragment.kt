package af.shizuku.manager.settings

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import rikka.html.text.toHtml
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.security.BiometricLock
import af.shizuku.manager.ShizukuSettings.Keys.*
import rikka.shizuku.Shizuku
import moe.shizuku.server.IShizukuService

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.activity.result.contract.ActivityResultContracts
import af.shizuku.manager.backup.BackupRestoreManager
import af.shizuku.manager.backup.CryptoUtils
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class ShizukuPlusSettingsFragment : BaseSettingsFragment() {

    private val createBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        val ctx = requireContext()
        val lock = BiometricLock(requireActivity())
        lock.authenticate(onSuccess = {
            try {
                val cipher = CryptoUtils.getCipherForEncryption()
                val payload = BackupRestoreManager.createBackupPayload(ctx, cipher)
                ctx.contentResolver.openOutputStream(uri)?.use { os ->
                    OutputStreamWriter(os, Charsets.UTF_8).use { it.write(payload) }
                }
                Toast.makeText(ctx, "Backup exported successfully", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "Backup failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, onError = { errCode ->
            Toast.makeText(ctx, "Authentication failed ($errCode)", Toast.LENGTH_SHORT).show()
        })
    }

    private val restoreBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val ctx = requireContext()
        val lock = BiometricLock(requireActivity())
        lock.authenticate(onSuccess = {
            try {
                val payload = ctx.contentResolver.openInputStream(uri)?.use { `is` ->
                    InputStreamReader(`is`, Charsets.UTF_8).readText()
                } ?: return@authenticate
                BackupRestoreManager.restoreFromPayload(ctx, payload) { iv ->
                    CryptoUtils.getCipherForDecryption(iv)
                }
                Toast.makeText(ctx, "Backup restored successfully. Please restart the app.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, onError = { errCode ->
            Toast.makeText(ctx, "Authentication failed ($errCode)", Toast.LENGTH_SHORT).show()
        })
    }

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        if (!isAdded) return
        setPreferencesFromResource(R.xml.settings_shizuku_plus, rootKey)

        ShizukuSettings.syncAllPlusFeaturesToServer()

        // Setup menu for 'Learn more' icon
        activity?.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                if (!isAdded) return
                menu.clear()
                menuInflater.inflate(R.menu.plus_settings_menu, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                if (!isAdded) return false
                if (menuItem.itemId == R.id.action_plus_help) {
                    showGeneralHelpDialog()
                    return true
                }
                return false
            }
        }, this)

        val dhizukuPref = requireNotNull(findPreference<TwoStatePreference>(KEY_DHIZUKU_MODE))
        dhizukuPref.isChecked = ShizukuSettings.isDhizukuModeEnabled()
        updateDhizukuDeviceOwnerStatus(dhizukuPref)
        dhizukuPref.setOnPreferenceClickListener {
            val ctx = context ?: return@setOnPreferenceClickListener true
            if (!isDeviceOwnerActive(ctx)) {
                showDhizukuSetupDialog(ctx)
                return@setOnPreferenceClickListener true
            }
            false // let the normal toggle happen
        }
        dhizukuPref.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                maybePromptRestart(KEY_DHIZUKU_MODE, newValue) {
                    ShizukuSettings.setDhizukuModeEnabled(newValue)
                    dhizukuPref.isChecked = newValue
                }
            }
            false
        }

        // Clear Device Owner button — only visible when app holds DO status
        val clearDoPref = findPreference<Preference>("clear_device_owner")
        clearDoPref?.isVisible = isDeviceOwnerActive(requireContext())
        clearDoPref?.setOnPreferenceClickListener {
            val ctx = context ?: return@setOnPreferenceClickListener true
            showClearDeviceOwnerDialog(ctx)
            true
        }

        // Device Owner Tools - Screen Capture Lockdown
        findPreference<TwoStatePreference>("dhizuku_disable_screencap")?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean ?: false
            val ctx = context ?: return@setOnPreferenceChangeListener false
            try {
                val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(ctx, af.shizuku.manager.admin.DhizukuAdminReceiver::class.java)
                dpm.setScreenCaptureDisabled(admin, enabled)
                Toast.makeText(ctx, if (enabled) "Screen Capture Disabled Globally" else "Screen Capture Enabled", Toast.LENGTH_SHORT).show()
                true
            } catch (e: Exception) {
                Toast.makeText(ctx, "Failed: Device Owner privileges required", Toast.LENGTH_LONG).show()
                false
            }
        }

        // Device Owner Tools - USB Data Lockdown
        findPreference<TwoStatePreference>("dhizuku_disallow_usb")?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean ?: false
            val ctx = context ?: return@setOnPreferenceChangeListener false
            try {
                val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(ctx, af.shizuku.manager.admin.DhizukuAdminReceiver::class.java)
                if (enabled) {
                    dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_USB_FILE_TRANSFER)
                    Toast.makeText(ctx, "USB Data Locked Down", Toast.LENGTH_SHORT).show()
                } else {
                    dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_USB_FILE_TRANSFER)
                    Toast.makeText(ctx, "USB Data Unlocked", Toast.LENGTH_SHORT).show()
                }
                true
            } catch (e: Exception) {
                Toast.makeText(ctx, "Failed: Device Owner privileges required", Toast.LENGTH_LONG).show()
                false
            }
        }

        // Device Owner Tools - App Freezing
        findPreference<Preference>("dhizuku_suspended_packages")?.setOnPreferenceChangeListener { _, newValue ->
            val packagesStr = newValue as? String ?: ""
            val packagesList = if (packagesStr.isBlank()) emptyList() else packagesStr.split(",").map { it.trim() }
            val ctx = context ?: return@setOnPreferenceChangeListener false
            try {
                val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(ctx, af.shizuku.manager.admin.DhizukuAdminReceiver::class.java)
                val pm = ctx.packageManager
                val installed = pm.getInstalledPackages(0).map { it.packageName }.toSet()

                // Clear any existing suspensions first
                val toUnsuspend = installed.toMutableList()
                dpm.setPackagesSuspended(admin, toUnsuspend.toTypedArray(), false)

                // Set chosen suspensions
                if (packagesList.isNotEmpty()) {
                    val toSuspend = packagesList.filter { installed.contains(it) }
                    val failed = dpm.setPackagesSuspended(admin, toSuspend.toTypedArray(), true)
                    if (failed.isNotEmpty()) {
                        Toast.makeText(ctx, "Failed to freeze: ${failed.joinToString()}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(ctx, "Frozen ${toSuspend.size} applications", Toast.LENGTH_SHORT).show()
                    }
                }
                true
            } catch (e: Exception) {
                Toast.makeText(ctx, "Failed: Device Owner privileges required", Toast.LENGTH_LONG).show()
                false
            }
        }

        val customApiPref = requireNotNull(findPreference<TwoStatePreference>(KEY_CUSTOM_API_ENABLED))
        customApiPref.isChecked = ShizukuSettings.isCustomApiEnabled()
        customApiPref.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                maybePromptRestart(KEY_CUSTOM_API_ENABLED, newValue) {
                    ShizukuSettings.setCustomApiEnabled(newValue)
                    customApiPref.isChecked = newValue
                    ShizukuSettings.syncAllPlusFeaturesToServer()
                    updateAllPlusFeatureDependencies()
                }
            }
            false
        }

        val backupSettingsPref = findPreference<Preference>("backup_settings")
        backupSettingsPref?.setOnPreferenceClickListener {
            val dateStr = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            createBackupLauncher.launch("ShizukuPlus_Settings_$dateStr.json")
            true
        }

        val restoreSettingsPref = findPreference<Preference>("restore_settings")
        restoreSettingsPref?.setOnPreferenceClickListener {
            restoreBackupLauncher.launch(arrayOf("application/json", "*/*"))
            true
        }

        val hideDisabledPref = findPreference<TwoStatePreference>("hide_disabled_plus_features")
        hideDisabledPref?.isChecked = ShizukuSettings.isHideDisabledPlusFeaturesEnabled()
        hideDisabledPref?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                ShizukuSettings.setHideDisabledPlusFeaturesEnabled(newValue)
                updateAllPlusFeatureDependencies()
            }
            true
        }

        val plusKeys = listOf(
            "shell_interceptor_enabled" to "shell_interceptor",
            "avf_manager_enabled" to "avf_manager",
            "storage_proxy_enabled" to "storage_proxy",
            "continuity_bridge_enabled" to "continuity_bridge",
            "ai_core_plus_enabled" to "ai_core_plus",
            "ai_core_master_enabled" to "ai_core_master",
            "npu_acceleration_enabled" to "npu_acceleration",
            "native_window_crawler_enabled" to "native_window_crawler",
            "ai_core_experimental_enabled" to "ai_core_experimental",
            "window_manager_plus_enabled" to "window_manager_plus",
            "overlay_manager_plus_enabled" to "overlay_manager_plus",
            "network_governor_plus_enabled" to "network_governor_plus",
            "activity_manager_plus_enabled" to "activity_manager_plus",
            "root_magisk_mocking_enabled" to "root_magisk_mocking",
            "root_auto_grant_enabled" to "root_auto_grant",
            "root_file_interceptor_enabled" to "root_file_interceptor",
            "root_busybox_mocking_enabled" to "root_busybox_mocking",
            "vector_enabled" to "vector",
            "experimental_root_compat" to "experimental_root",
            "spoof_device_enabled" to "spoof_device",
            "shadow_binder_enabled" to "shadow_binder",
            "binder_firewall_enabled" to "binder_firewall",
            "binder_logging_enabled" to "binder_logging",
            "samsung_system_uid_escalation_enabled" to "samsung_system_uid_escalation",
            "software_keystore_fallback_enabled" to "software_keystore_fallback"
        )
        val experimentalKeys = setOf(
            "avf_manager_enabled",
            "ai_core_plus_enabled",
            "ai_core_master_enabled",
            "npu_acceleration_enabled",
            "native_window_crawler_enabled",
            "ai_core_experimental_enabled",
            "vector_enabled",
            "experimental_root_compat",
            "spoof_device_enabled",
            "samsung_system_uid_escalation_enabled"
        )

        fun notifyDiagramForKey(prefKey: String) {
            val diagramKey = when (prefKey) {
                "storage_proxy_enabled" -> "storage_proxy_diagram"
                "shadow_binder_enabled" -> "shadow_binder_diagram"
                "binder_firewall_enabled" -> "binder_firewall_diagram"
                "avf_manager_enabled" -> "vm_manager_diagram"
                "ai_core_plus_enabled" -> "ai_core_plus_diagram"
                "continuity_bridge_enabled" -> "continuity_bridge_diagram"
                "network_governor_plus_enabled" -> "network_governor_plus_diagram"
                "overlay_manager_plus_enabled" -> "overlay_manager_plus_diagram"
                else -> null
            }
            if (diagramKey != null) {
                findPreference<Preference>(diagramKey)?.let { it.isVisible = false; it.isVisible = true }
            }
        }

        plusKeys.forEach { (prefKey, featureName) ->
            findPreference<TwoStatePreference>(prefKey)?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as? Boolean ?: false
                if (enabled && experimentalKeys.contains(prefKey)) {
                    showExperimentalWarning(prefKey) {
                        preferenceManager.sharedPreferences?.edit()?.putBoolean(prefKey, true)?.apply()
                        // Cascade child state BEFORE syncing so the server sees a consistent
                        // parent+child snapshot (disabling a parent force-unchecks children).
                        updatePlusFeatureDependency(prefKey, true)
                        ShizukuSettings.syncAllPlusFeaturesToServer()
                        notifyDiagramForKey(prefKey)
                        findPreference<Preference>("plus_status_dashboard")?.let { it.isVisible = false; it.isVisible = true }
                    }
                    false // Handle manually after dialog
                } else {
                    preferenceManager.sharedPreferences?.edit()?.putBoolean(prefKey, enabled)?.apply()
                    updatePlusFeatureDependency(prefKey, enabled)
                    ShizukuSettings.syncAllPlusFeaturesToServer()
                    notifyDiagramForKey(prefKey)
                    findPreference<Preference>("plus_status_dashboard")?.let { it.isVisible = false; it.isVisible = true }
                    true
                }
            }
        }

        findPreference<Preference>("spoof_target")?.setOnPreferenceChangeListener { _, newValue ->
            preferenceManager.sharedPreferences?.edit()?.putString("spoof_target", newValue as String)?.apply()
            ShizukuSettings.syncAllPlusFeaturesToServer()
            true
        }

        findPreference<Preference>(KEY_SHADOW_BINDER_HIDDEN_PACKAGES)?.setOnPreferenceChangeListener { _, _ ->
            ShizukuSettings.syncAllPlusFeaturesToServer()
            true
        }

        findPreference<Preference>("ai_core_plus_enabled")?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean ?: false
            if (enabled) {
                val lock = BiometricLock(requireActivity())
                if (lock.canAuthenticate(requireContext())) {
                    lock.authenticate({
                        ShizukuSettings.setAICorePlusEnabled(true)
                        ShizukuSettings.syncAllPlusFeaturesToServer()
                        activity?.runOnUiThread {
                            findPreference<TwoStatePreference>("ai_core_plus_enabled")?.isChecked = true
                            updatePlusFeatureDependency("ai_core_plus_enabled", true)
                            notifyDiagramForKey("ai_core_plus_enabled")
                            findPreference<Preference>("plus_status_dashboard")?.let { it.isVisible = false; it.isVisible = true }
                        }
                    }, { _ -> /* Ignore or show toast */ })
                    return@setOnPreferenceChangeListener false
                }
            }
            // fallback / standard or disabling
            preferenceManager.sharedPreferences?.edit()?.putBoolean("ai_core_plus_enabled", enabled)?.apply()
            // Cascade child state BEFORE syncing: disabling ai_core_plus force-unchecks the
            // AI sub-features, and the server gates NPU/window/automation on those child flags
            // (not on ai_core_plus), so they must be false in prefs before the sync runs.
            updatePlusFeatureDependency("ai_core_plus_enabled", enabled)
            ShizukuSettings.syncAllPlusFeaturesToServer()
            notifyDiagramForKey("ai_core_plus_enabled")
            findPreference<Preference>("plus_status_dashboard")?.let { it.isVisible = false; it.isVisible = true }
            true
        }

        // Initialize all preference dependencies
        updateAllPlusFeatureDependencies()

        // Check for integrated apps and update summaries
        checkAppIntegrations()
    }

    private fun isDeviceOwnerActive(ctx: Context): Boolean {
        return try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isDeviceOwnerApp(ctx.packageName)
        } catch (e: Exception) {
            false
        }
    }

    private fun updateDhizukuDeviceOwnerStatus(pref: TwoStatePreference) {
        val ctx = context ?: return
        val active = isDeviceOwnerActive(ctx)
        val statusLine = if (active)
            getString(R.string.dhizuku_status_active)
        else
            getString(R.string.dhizuku_status_not_set)
        val baseSummary = getString(R.string.settings_dhizuku_mode_summary)
        pref.summary = "$statusLine\n\n$baseSummary"
        // Show/hide the Clear Device Owner button based on active status
        findPreference<Preference>("clear_device_owner")?.isVisible = active
    }

    private fun showClearDeviceOwnerDialog(ctx: Context) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dhizuku_clear_owner_title)
            .setMessage(R.string.dhizuku_clear_owner_message)
            .setPositiveButton(R.string.dhizuku_clear_owner_confirm) { _, _ ->
                clearDeviceOwner(ctx)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearDeviceOwner(ctx: Context) {
        try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.clearDeviceOwnerApp(ctx.packageName)
            Toast.makeText(ctx, R.string.dhizuku_clear_owner_success, Toast.LENGTH_LONG).show()
            // Refresh the UI to reflect the change
            val dhizukuPref = findPreference<TwoStatePreference>(KEY_DHIZUKU_MODE)
            if (dhizukuPref != null) {
                ShizukuSettings.setDhizukuModeEnabled(false)
                dhizukuPref.isChecked = false
                updateDhizukuDeviceOwnerStatus(dhizukuPref)
            }
        } catch (e: Exception) {
            Toast.makeText(ctx, R.string.dhizuku_clear_owner_failure, Toast.LENGTH_LONG).show()
        }
    }

    private fun showDhizukuSetupDialog(ctx: Context) {
        // applicationId (af.shizuku.plus.api) differs from namespace (af.shizuku.manager),
        // so the full class name must be explicit rather than using the shorthand dot notation.
        val command = "adb shell dpm set-device-owner " +
            "${ctx.packageName}/af.shizuku.manager.admin.DhizukuAdminReceiver"
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dhizuku_setup_title)
            .setMessage(getString(R.string.dhizuku_setup_message, command))
            .setPositiveButton(R.string.dhizuku_setup_copy) { _, _ ->
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("dpm command", command))
                Toast.makeText(ctx, R.string.dhizuku_setup_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showGeneralHelpDialog() {
        val context = context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_shizuku_plus_features)
            .setMessage(getString(R.string.help_general_plus_summary).toHtml())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showExperimentalWarning(prefKey: String, onConfirm: () -> Unit) {
        val context = context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_experimental_warning_title)
            .setMessage(R.string.settings_experimental_warning_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pref = findPreference<TwoStatePreference>(prefKey)
                pref?.isChecked = true
                onConfirm()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun checkAppIntegrations() {
        val integrations = mapOf(
            "continuity_bridge_enabled" to listOf(
                "com.arlosoft.macrodroid" to "MacroDroid"
            ),
            "activity_manager_plus_enabled" to listOf(
                "com.arlosoft.macrodroid" to "MacroDroid",
                "net.dinglisch.android.taskerm" to "Tasker"
            ),
            "window_manager_plus_enabled" to listOf(
                "com.arlosoft.macrodroid" to "MacroDroid",
                "com.isaiasmatewos.taskbar" to "Taskbar"
            ),
            "overlay_manager_plus_enabled" to listOf(
                "project.vivid.hex.nx" to "Hex Installer",
                "tk.wasdennnoch.substratumlite" to "Substratum Lite"
            ),
            "network_governor_plus_enabled" to listOf(
                "dev.ukanth.ufirewall" to "AFWall+"
            ),
            "storage_proxy_enabled" to listOf(
                "com.machiav3lli.neo_backup" to "Neo Backup",
                "eu.darken.sdm" to "SD Maid",
                "eu.darken.sdmse" to "SD Maid SE"
            ),
            "root_magisk_mocking_enabled" to listOf(
                "com.topjohnwu.magisk" to "Magisk Manager"
            )
        )

        val pm = (context ?: return).packageManager
        lifecycleScope.launch(Dispatchers.IO) {
            val found = integrations.mapValues { (_, apps) ->
                apps.find { (pkg, _) ->
                    try { pm.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
                }
            }
            launch(Dispatchers.Main) {
                found.forEach { (prefKey, foundApp) ->
                    if (foundApp != null) {
                        findPreference<PlusFeaturePreference>(prefKey)?.apply {
                            setIntegration(foundApp.first, foundApp.second)
                            val originalSummary = summary
                            summary = getString(R.string.settings_plus_app_found, foundApp.second) + "\n\n" + originalSummary
                        }
                    }
                }
            }
        }
    }

    private fun updateAllPlusFeatureDependencies() {
        val customApiEnabled = ShizukuSettings.isCustomApiEnabled()
        val hideDisabled = ShizukuSettings.isHideDisabledPlusFeaturesEnabled()

        // Update all preferences that depend on custom_api_enabled
        updatePreferenceDependency("shell_interceptor_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("avf_manager_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("storage_proxy_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("continuity_bridge_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("ai_core_plus_enabled", customApiEnabled, hideDisabled)
        val aiCorePlusEnabled = ShizukuSettings.isAICorePlusEnabled() && customApiEnabled
        updatePreferenceDependency("ai_core_master_enabled", aiCorePlusEnabled, hideDisabled)
        updatePreferenceDependency("ai_core_experimental_enabled", aiCorePlusEnabled, hideDisabled)
        val aiCoreMasterEnabled = ShizukuSettings.isAiCoreMasterEnabled() && aiCorePlusEnabled
        updatePreferenceDependency("npu_acceleration_enabled", aiCoreMasterEnabled, hideDisabled)
        updatePreferenceDependency("native_window_crawler_enabled", aiCoreMasterEnabled, hideDisabled)
        updatePreferenceDependency("window_manager_plus_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("network_governor_plus_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("activity_manager_plus_enabled", customApiEnabled, hideDisabled)

        // Root Compat modules
        updatePreferenceDependency("root_magisk_mocking_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("root_auto_grant_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("root_file_interceptor_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("root_busybox_mocking_enabled", customApiEnabled, hideDisabled)

        // Category visibility
        findPreference<Preference>("category_root_compat")?.isVisible = customApiEnabled || !hideDisabled

        // These also depend on window_manager_plus_enabled
        val windowManagerPlusEnabled = ShizukuSettings.isWindowManagerPlusEnabled() && customApiEnabled
        updatePreferenceDependency("overlay_manager_plus_enabled", windowManagerPlusEnabled, hideDisabled)

        // Force RecyclerView to recalculate layout after hiding/showing items.
        // Guard: PreferenceFragmentCompat.getListView() throws (not returns null) before
        // onCreateView completes, so the safe-call (?.) does NOT protect us — use
        // Fragment.getView() which correctly returns null when the view isn't ready.
        view?.post {
            if (isAdded && view != null) {
                try {
                    listView.requestLayout()
                    listView.invalidate()
                } catch (e: IllegalStateException) {
                    // Fragment view was destroyed between post() scheduling and execution
                }
            }
        }
    }

    private fun updatePreferenceDependency(prefKey: String, parentEnabled: Boolean, hideIfDisabled: Boolean = false) {
        findPreference<Preference>(prefKey)?.apply {
            isEnabled = parentEnabled
            if (this is TwoStatePreference && !parentEnabled) {
                isChecked = false
            }
            isVisible = if (hideIfDisabled) parentEnabled else true
        }
    }

    private fun updatePlusFeatureDependency(prefKey: String, newValue: Boolean) {
        val hideDisabled = ShizukuSettings.isHideDisabledPlusFeaturesEnabled()
        val customApiEnabled = ShizukuSettings.isCustomApiEnabled()
        when (prefKey) {
            "window_manager_plus_enabled" -> {
                updatePreferenceDependency("overlay_manager_plus_enabled", newValue && customApiEnabled, hideDisabled)
            }
            "ai_core_plus_enabled" -> {
                val active = newValue && customApiEnabled
                updatePreferenceDependency("ai_core_master_enabled", active, hideDisabled)
                updatePreferenceDependency("ai_core_experimental_enabled", active, hideDisabled)
                val masterActive = ShizukuSettings.isAiCoreMasterEnabled() && active
                updatePreferenceDependency("npu_acceleration_enabled", masterActive, hideDisabled)
                updatePreferenceDependency("native_window_crawler_enabled", masterActive, hideDisabled)
            }
            "ai_core_master_enabled" -> {
                val aiCorePlusActive = ShizukuSettings.isAICorePlusEnabled() && customApiEnabled
                val active = newValue && aiCorePlusActive
                updatePreferenceDependency("npu_acceleration_enabled", active, hideDisabled)
                updatePreferenceDependency("native_window_crawler_enabled", active, hideDisabled)
            }
        }
    }
}
