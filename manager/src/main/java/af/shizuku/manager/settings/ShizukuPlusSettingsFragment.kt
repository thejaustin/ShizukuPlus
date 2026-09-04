package af.shizuku.manager.settings

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.html.text.toHtml
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.automation.AutomationService
import af.shizuku.manager.security.BiometricLock
import androidx.biometric.BiometricPrompt
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
import af.shizuku.manager.backup.BackupKeyUnavailableException
import af.shizuku.manager.backup.CryptoUtils
import android.security.keystore.KeyPermanentlyInvalidatedException
import javax.crypto.AEADBadTagException
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class ShizukuPlusSettingsFragment : BaseSettingsFragment() {

    override fun getTitle(): CharSequence? = "Feature Hub"

    // e.message is often null for keystore/cipher exceptions (#315's "Backup failed: null"), and
    // KeyPermanentlyInvalidatedException needs a message explaining it's unrecoverable rather
    // than a raw exception string (#332) - encryption self-heals from this in CryptoUtils, but
    // decryption of an existing backup genuinely can't.
    private fun backupErrorMessage(prefix: String, e: Exception): String = when (e) {
        is KeyPermanentlyInvalidatedException ->
            "$prefix: your device's screen lock or biometrics changed since this backup's " +
                "encryption key was created, which permanently invalidates it by design. " +
                if (prefix == "Restore failed") "This backup can no longer be decrypted."
                else "Please try again to generate a new key."
        // The key was destroyed (uninstall/reinstall or cleared data) — explain, don't show a raw error (#370).
        is BackupKeyUnavailableException -> "$prefix: ${e.message}"
        // A valid key exists but can't authenticate this ciphertext: the backup was made by a
        // different install, is corrupt, or was tampered with. GCM's tag check is exactly what
        // catches that — surface it as a clear cause instead of "AEADBadTagException" (#370).
        is AEADBadTagException ->
            "$prefix: this backup could not be decrypted. It was most likely created by a different " +
                "installation of Shizuku+ — backups are encrypted per-install and can't be restored " +
                "after reinstalling or clearing the app's data."
        else -> "$prefix: ${e.message ?: e.javaClass.simpleName}"
    }

    private val createPlainBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        val ctx = requireContext()
        try {
            val payload = BackupRestoreManager.createPlainBackupPayload(ctx)
            ctx.contentResolver.openOutputStream(uri)?.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { it.write(payload) }
            }
            Toast.makeText(ctx, R.string.backup_plain_exported, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, ctx.getString(R.string.backup_failed_generic, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private val createBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        val ctx = requireContext()
        val lock = BiometricLock(requireActivity())
        val useAuth = lock.canAuthenticate(ctx)

        if (!useAuth) {
            try {
                val cipher = CryptoUtils.getCipherForEncryption(userAuthRequired = false)
                val payload = BackupRestoreManager.createBackupPayload(ctx, cipher)
                ctx.contentResolver.openOutputStream(uri)?.use { os ->
                    OutputStreamWriter(os, Charsets.UTF_8).use { it.write(payload) }
                }
                Toast.makeText(ctx, R.string.backup_exported_success, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, backupErrorMessage("Backup failed", e), Toast.LENGTH_LONG).show()
            }
            return@registerForActivityResult
        }

        try {
            val cipher = CryptoUtils.getCipherForEncryption(userAuthRequired = true)
            lock.authenticate(onSuccess = { crypto ->
                try {
                    val payload = BackupRestoreManager.createBackupPayload(ctx, crypto?.cipher ?: cipher)
                    ctx.contentResolver.openOutputStream(uri)?.use { os ->
                        OutputStreamWriter(os, Charsets.UTF_8).use { it.write(payload) }
                    }
                    Toast.makeText(ctx, R.string.backup_exported_success, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(ctx, backupErrorMessage("Backup failed", e), Toast.LENGTH_LONG).show()
                }
            }, onError = { errCode ->
                Toast.makeText(ctx, ctx.getString(R.string.backup_auth_failed, errCode), Toast.LENGTH_SHORT).show()
            }, crypto = BiometricPrompt.CryptoObject(cipher))
        } catch (e: Exception) {
            Toast.makeText(ctx, ctx.getString(R.string.backup_failed_generic, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private val restoreBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val ctx = requireContext()
        val lock = BiometricLock(requireActivity())
        val useAuth = lock.canAuthenticate(ctx)

        try {
            val payload = ctx.contentResolver.openInputStream(uri)?.use { `is` ->
                InputStreamReader(`is`, Charsets.UTF_8).readText()
            } ?: return@registerForActivityResult

            // Auto-detect format: plain (v2) backups skip encryption entirely.
            if (!BackupRestoreManager.isEncrypted(payload)) {
                try {
                    BackupRestoreManager.restoreFromPlainPayload(ctx, payload)
                    Toast.makeText(ctx, R.string.backup_restored_success, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(ctx, ctx.getString(R.string.restore_failed_generic, e.message), Toast.LENGTH_LONG).show()
                }
                return@registerForActivityResult
            }

            val iv = BackupRestoreManager.extractIv(payload)

            if (!useAuth) {
                try {
                    val cipher = CryptoUtils.getCipherForDecryption(iv, userAuthRequired = false)
                    BackupRestoreManager.restoreFromPayload(ctx, payload, cipher)
                    Toast.makeText(ctx, R.string.backup_restored_success, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(ctx, backupErrorMessage("Restore failed", e), Toast.LENGTH_LONG).show()
                }
                return@registerForActivityResult
            }

            val cipher = CryptoUtils.getCipherForDecryption(iv, userAuthRequired = true)
            lock.authenticate(onSuccess = { crypto ->
                try {
                    BackupRestoreManager.restoreFromPayload(ctx, payload, crypto?.cipher ?: cipher)
                    Toast.makeText(ctx, R.string.backup_restored_success, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(ctx, backupErrorMessage("Restore failed", e), Toast.LENGTH_LONG).show()
                }
            }, onError = { errCode ->
                Toast.makeText(ctx, ctx.getString(R.string.backup_auth_failed, errCode), Toast.LENGTH_SHORT).show()
            }, crypto = BiometricPrompt.CryptoObject(cipher))
        } catch (e: Exception) {
            Toast.makeText(ctx, ctx.getString(R.string.restore_failed_generic, e.message), Toast.LENGTH_LONG).show()
        }
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
                ShizukuSettings.setDhizukuModeEnabled(newValue)
                maybePromptRestart(KEY_DHIZUKU_MODE, newValue) {
                    dhizukuPref.isChecked = newValue
                }
            }
            true
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
                Toast.makeText(ctx, if (enabled) R.string.dpm_screen_capture_disabled else R.string.dpm_screen_capture_enabled, Toast.LENGTH_SHORT).show()
                true
            } catch (e: Exception) {
                Toast.makeText(ctx, R.string.dpm_requires_device_owner, Toast.LENGTH_LONG).show()
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
                    Toast.makeText(ctx, R.string.dpm_usb_locked, Toast.LENGTH_SHORT).show()
                } else {
                    dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_USB_FILE_TRANSFER)
                    Toast.makeText(ctx, R.string.dpm_usb_unlocked, Toast.LENGTH_SHORT).show()
                }
                true
            } catch (e: Exception) {
                Toast.makeText(ctx, R.string.dpm_requires_device_owner, Toast.LENGTH_LONG).show()
                false
            }
        }

        // Device Owner Tools - App Freezing
        findPreference<Preference>("dhizuku_suspended_packages")?.setOnPreferenceChangeListener { _, newValue ->
            val packagesStr = newValue as? String ?: ""
            val packagesList = if (packagesStr.isBlank()) emptyList() else packagesStr.split(",").map { it.trim() }
            val ctx = context ?: return@setOnPreferenceChangeListener false
            // getInstalledPackages() + setPackagesSuspended() are both real IPCs (PackageManager /
            // DevicePolicyManager); do them off the main thread rather than blocking this
            // preference-change callback like the now-fixed RootCompatibilityActivity.buildItems().
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val admin = ComponentName(ctx, af.shizuku.manager.admin.DhizukuAdminReceiver::class.java)
                    val pm = ctx.packageManager
                    val installed = pm.getInstalledPackages(0).map { it.packageName }.toSet()

                    // Clear any existing suspensions first
                    val toUnsuspend = installed.toMutableList()
                    dpm.setPackagesSuspended(admin, toUnsuspend.toTypedArray(), false)

                    // Set chosen suspensions
                    var message: String? = null
                    if (packagesList.isNotEmpty()) {
                        val toSuspend = packagesList.filter { installed.contains(it) }
                        val failed = dpm.setPackagesSuspended(admin, toSuspend.toTypedArray(), true)
                        message = if (failed.isNotEmpty()) {
                            ctx.getString(R.string.dpm_freeze_failed, failed.joinToString())
                        } else {
                            ctx.getString(R.string.dpm_freeze_success, toSuspend.size)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        message?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, R.string.dpm_requires_device_owner, Toast.LENGTH_LONG).show()
                    }
                }
            }
            true
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
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.backup_export_title)
                .setItems(arrayOf(
                    getString(R.string.backup_type_encrypted),
                    getString(R.string.backup_type_plain)
                )) { _, which ->
                    try {
                        when (which) {
                            0 -> createBackupLauncher.launch("ShizukuPlus_Settings_$dateStr.json")
                            1 -> createPlainBackupLauncher.launch("ShizukuPlus_Settings_plain_$dateStr.json")
                        }
                    } catch (e: android.content.ActivityNotFoundException) {
                        Toast.makeText(requireContext(), R.string.backup_no_file_manager_save, Toast.LENGTH_LONG).show()
                    }
                }
                .show()
            true
        }

        val restoreSettingsPref = findPreference<Preference>("restore_settings")
        restoreSettingsPref?.setOnPreferenceClickListener {
            try {
                restoreBackupLauncher.launch(arrayOf("application/json", "*/*"))
            } catch (e: android.content.ActivityNotFoundException) {
                Toast.makeText(requireContext(), R.string.backup_no_file_manager_open, Toast.LENGTH_LONG).show()
            }
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
            "status_bar_governor_plus_enabled" to "status_bar_governor_plus",
            "package_governor_plus_enabled" to "package_governor_plus",
            "display_tuner_plus_enabled" to "display_tuner_plus",
            "activity_manager_plus_enabled" to "activity_manager_plus",
            "shadow_binder_enabled" to "shadow_binder",
            "binder_firewall_enabled" to "binder_firewall",
            "binder_logging_enabled" to "binder_logging",
            "samsung_system_uid_escalation_enabled" to "samsung_system_uid_escalation",
            "software_keystore_fallback_enabled" to "software_keystore_fallback"
        )
        val experimentalKeys = setOf(
            "avf_manager_enabled",
            "ai_core_master_enabled",
            "npu_acceleration_enabled",
            "native_window_crawler_enabled",
            "ai_core_experimental_enabled",
            "display_tuner_plus_enabled",
            "vector_enabled",
            "experimental_root_compat",
            "spoof_device_enabled",
            "samsung_system_uid_escalation_enabled"
        )

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
                    }
                    false // Handle manually after dialog
                } else {
                    preferenceManager.sharedPreferences?.edit()?.putBoolean(prefKey, enabled)?.apply()
                    updatePlusFeatureDependency(prefKey, enabled)
                    ShizukuSettings.syncAllPlusFeaturesToServer()
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

        findPreference<Preference>("binder_firewall_trusted_networks")?.let { pref ->
            updateTrustedNetworksSummary(pref)
            pref.setOnPreferenceClickListener {
                showTrustedNetworksDialog()
                true
            }
        }

        findPreference<Preference>("binder_firewall_app_profiles")?.let { pref ->
            updateAppProfilesSummary(pref)
            pref.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), af.shizuku.manager.automation.AppProfilesActivity::class.java))
                true
            }
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
            true
        }

        // Initialize all preference dependencies
        updateAllPlusFeatureDependencies()

        // Check for integrated apps and update summaries
        checkAppIntegrations()

        applyModeConstraints()
    }

    // Disable or annotate preferences that can't fully function in the current privilege mode.
    // Only restricts when the server is running (uid != -1) — if it's not attached yet,
    // leave everything editable so the user can configure before starting Shizuku.
    private fun applyModeConstraints() {
        val uid = try { Shizuku.getUid() } catch (_: Exception) { -1 }
        if (uid == -1) return // server not running — no restrictions

        if (uid != 0) {
            // Samsung UID escalation uses a Samsung-specific root exploit; ADB UID 2000 can't use it.
            findPreference<Preference>("samsung_system_uid_escalation_enabled")?.apply {
                isEnabled = false
                summary = "Root mode only — this exploit requires the server to run as UID 0."
            }
            // Storage Bridge uses `run-as` in ADB mode (uid 2000), which only works for
            // debuggable apps. Production app data (/data/data) is inaccessible — backup
            // tools like Neo Backup will silently see no app data.
            findPreference<Preference>("storage_proxy_enabled")?.let { pref ->
                val note = "ADB mode: /data/data limited to debuggable apps — backup tools may not see app data."
                pref.summary = "$note\n\n${pref.summary}"
            }
        }
    }

    private fun isDeviceOwnerActive(ctx: Context): Boolean {
        return try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isDeviceOwnerApp(ctx.packageName) || dpm.isProfileOwnerApp(ctx.packageName)
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
        // Show/hide the Clear Owner button based on active status
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
            if (dpm.isDeviceOwnerApp(ctx.packageName)) {
                dpm.clearDeviceOwnerApp(ctx.packageName)
            } else if (dpm.isProfileOwnerApp(ctx.packageName)) {
                val admin = android.content.ComponentName(ctx, af.shizuku.manager.admin.DhizukuAdminReceiver::class.java)
                dpm.clearProfileOwner(admin)
            }
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
        updatePreferenceDependency("status_bar_governor_plus_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("package_governor_plus_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("display_tuner_plus_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("activity_manager_plus_enabled", customApiEnabled, hideDisabled)

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

    override fun onResume() {
        super.onResume()
        // Refresh the App Profiles summary when returning from AppProfilesActivity.
        findPreference<Preference>("binder_firewall_app_profiles")?.let { updateAppProfilesSummary(it) }
    }

    private fun updateTrustedNetworksSummary(pref: Preference) {
        val count = ShizukuSettings.getAutomationTrustedNetworks().size
        pref.summary = when (count) {
            0 -> getString(R.string.settings_binder_firewall_trusted_networks_summary_none)
            1 -> getString(R.string.settings_binder_firewall_trusted_networks_summary_one)
            else -> getString(R.string.settings_binder_firewall_trusted_networks_summary_many, count)
        }
    }

    private fun updateAppProfilesSummary(pref: Preference) {
        val json = ShizukuSettings.getAutomationAppProfilesJson()
        val count = if (json == "{}" || json.length <= 2) 0
        else try {
            org.json.JSONObject(json).length()
        } catch (_: Exception) { 0 }
        pref.summary = if (count == 0) {
            getString(R.string.app_profiles_summary_none)
        } else {
            getString(R.string.app_profiles_summary_count, count)
        }
    }

    private fun getCurrentSsid(): String? {
        val ctx = context ?: return null
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null
            if (Build.VERSION.SDK_INT >= 29) {
                val ssid = (caps.transportInfo as? WifiInfo)?.ssid ?: return null
                // Strip surrounding quotes that WifiInfo adds; null out "<unknown ssid>"
                when {
                    ssid == "<unknown ssid>" -> null
                    ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length >= 2 ->
                        ssid.substring(1, ssid.length - 1)
                    else -> ssid
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun showTrustedNetworksDialog(initialLines: Set<String>? = null) {
        val ctx = context ?: return
        val current = initialLines ?: ShizukuSettings.getAutomationTrustedNetworks()
        val currentSsid = getCurrentSsid()

        val input = EditText(ctx).apply {
            hint = getString(R.string.binder_firewall_trusted_networks_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 8
            setText(current.joinToString("\n"))
            setPadding(
                (16 * resources.displayMetrics.density).toInt(),
                (8 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt(),
                (8 * resources.displayMetrics.density).toInt(),
            )
        }

        val builder = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.binder_firewall_trusted_networks_title)
            .setMessage(R.string.binder_firewall_trusted_networks_detail)
            .setView(input)
            .setPositiveButton(R.string.binder_firewall_trusted_networks_save) { _, _ ->
                val lines = input.text.toString()
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                ShizukuSettings.setAutomationTrustedNetworks(lines)
                findPreference<Preference>("binder_firewall_trusted_networks")
                    ?.let { updateTrustedNetworksSummary(it) }
                // Start AutomationService when rules exist; let it self-stop when empty.
                val intent = Intent(ctx, AutomationService::class.java)
                if (ShizukuSettings.hasAnyAutomationRulesConfigured()) {
                    ctx.startService(intent)
                } else {
                    ctx.stopService(intent)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)

        if (currentSsid != null) {
            builder.setNeutralButton(getString(R.string.binder_firewall_trusted_networks_add_current)) { _, _ ->
                val existing = input.text.toString()
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (currentSsid in existing) {
                    Toast.makeText(
                        ctx,
                        getString(R.string.binder_firewall_trusted_networks_already_added, currentSsid),
                        Toast.LENGTH_SHORT
                    ).show()
                    // Re-open so the user can still Save or edit.
                    input.post { showTrustedNetworksDialog(existing.toSet()) }
                } else {
                    val withNew = (existing + currentSsid).toSet()
                    // Re-open with the SSID appended so the user sees it before saving.
                    input.post { showTrustedNetworksDialog(withNew) }
                }
            }
        }

        builder.show()
    }
}
