package af.shizuku.manager.settings
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.Keys.*
import af.shizuku.manager.activitylog.ActivityLogActivity
import af.shizuku.manager.backup.BackupKeyUnavailableException
import af.shizuku.manager.backup.BackupRestoreManager
import af.shizuku.manager.backup.CryptoUtils
import af.shizuku.manager.ktx.startWithSceneTransition
import af.shizuku.manager.security.BiometricLock
import af.shizuku.manager.utils.EnvironmentUtils
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import javax.crypto.AEADBadTagException

class AdvancedSettingsFragment : BaseSettingsFragment() {
    override fun getTitle(): CharSequence? = getString(R.string.settings_main_nav_advanced_diagnostics_title)

    private fun backupErrorMessage(
        prefix: String,
        e: Exception,
    ): String =
        when (e) {
            is KeyPermanentlyInvalidatedException ->
                "$prefix: your device's screen lock or biometrics changed since this backup's " +
                    "encryption key was created, which permanently invalidates it by design. " +
                    if (prefix == "Restore failed") {
                        "This backup can no longer be decrypted."
                    } else {
                        "Please try again to generate a new key."
                    }
            is BackupKeyUnavailableException -> "$prefix: ${e.message}"
            is AEADBadTagException ->
                "$prefix: this backup could not be decrypted. It was most likely created by a different " +
                    "installation of Shizuku+ — backups are encrypted per-install and can't be restored " +
                    "after reinstalling or clearing the app's data."
            else -> "$prefix: ${e.message ?: e.javaClass.simpleName}"
        }

    private val createPlainBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            val ctx = requireContext()
            try {
                val payload = BackupRestoreManager.createPlainBackupPayload(ctx)
                ctx.contentResolver.openOutputStream(uri)?.use { os ->
                    OutputStreamWriter(os, Charsets.UTF_8).use { it.write(payload) }
                }
                notifyUser(R.string.backup_plain_exported, long = true)
            } catch (e: Exception) {
                notifyUser(ctx.getString(R.string.backup_failed_generic, e.message), long = true)
            }
        }

    private val createBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
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
                    notifyUser(R.string.backup_exported_success)
                } catch (e: Exception) {
                    notifyUser(backupErrorMessage("Backup failed", e), long = true)
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
                        notifyUser(R.string.backup_exported_success)
                    } catch (e: Exception) {
                        notifyUser(backupErrorMessage("Backup failed", e), long = true)
                    }
                }, onError = { errCode ->
                    notifyUser(ctx.getString(R.string.backup_auth_failed, errCode))
                }, crypto = BiometricPrompt.CryptoObject(cipher))
            } catch (e: Exception) {
                notifyUser(ctx.getString(R.string.backup_failed_generic, e.message), long = true)
            }
        }

    private val restoreBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val ctx = requireContext()
            val lock = BiometricLock(requireActivity())
            val useAuth = lock.canAuthenticate(ctx)

            try {
                val payload =
                    ctx.contentResolver.openInputStream(uri)?.use { `is` ->
                        InputStreamReader(`is`, Charsets.UTF_8).readText()
                    } ?: return@registerForActivityResult

                if (!BackupRestoreManager.isEncrypted(payload)) {
                    try {
                        BackupRestoreManager.restoreFromPlainPayload(ctx, payload)
                        onRestoreSuccess()
                    } catch (e: Exception) {
                        notifyUser(ctx.getString(R.string.restore_failed_generic, e.message), long = true)
                    }
                    return@registerForActivityResult
                }

                val iv = BackupRestoreManager.extractIv(payload)

                if (!useAuth) {
                    try {
                        val cipher = CryptoUtils.getCipherForDecryption(iv, userAuthRequired = false)
                        BackupRestoreManager.restoreFromPayload(ctx, payload, cipher)
                        onRestoreSuccess()
                    } catch (e: Exception) {
                        notifyUser(backupErrorMessage("Restore failed", e), long = true)
                    }
                    return@registerForActivityResult
                }

                val cipher = CryptoUtils.getCipherForDecryption(iv, userAuthRequired = true)
                lock.authenticate(onSuccess = { crypto ->
                    try {
                        BackupRestoreManager.restoreFromPayload(ctx, payload, crypto?.cipher ?: cipher)
                        onRestoreSuccess()
                    } catch (e: Exception) {
                        notifyUser(backupErrorMessage("Restore failed", e), long = true)
                    }
                }, onError = { errCode ->
                    notifyUser(ctx.getString(R.string.backup_auth_failed, errCode))
                }, crypto = BiometricPrompt.CryptoObject(cipher))
            } catch (e: Exception) {
                notifyUser(ctx.getString(R.string.restore_failed_generic, e.message), long = true)
            }
        }

    override fun onCreateSettingsPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.settings_advanced, rootKey)
        val context = requireContext()

        findPreference<androidx.preference.TwoStatePreference>(ShizukuSettings.Keys.KEY_CRASH_REPORTING)?.apply {
            isChecked = ShizukuSettings.isCrashReportingEnabled()
            setOnPreferenceChangeListener { _, newValue ->
                val on = newValue == true
                ShizukuSettings.setCrashReportingEnabled(on)
                if (on) {
                    (requireActivity().application as? af.shizuku.manager.ShizukuApplication)?.initializeSentryEarly()
                } else {
                    io.sentry.Sentry.close()
                }
                true
            }
        }

        findPreference<Preference>("service_doctor")?.setOnPreferenceClickListener {
            val act = activity
            if (act != null) {
                act.startWithSceneTransition(Intent(context, ServiceDoctorActivity::class.java))
            } else {
                startActivity(Intent(context, ServiceDoctorActivity::class.java))
            }
            true
        }

        findPreference<Preference>("activity_log")?.setOnPreferenceClickListener {
            val act = activity
            if (act != null) {
                act.startWithSceneTransition(Intent(context, ActivityLogActivity::class.java))
            } else {
                startActivity(Intent(context, ActivityLogActivity::class.java))
            }
            true
        }

        findPreference<TwoStatePreference>(KEY_ENABLE_ACTIVITY_LOG)?.setOnPreferenceChangeListener { _, _ ->
            ShizukuSettings.syncAllPlusFeaturesToServer()
            true
        }

        findPreference<Preference>("scripting")?.setOnPreferenceClickListener {
            startActivity(Intent(context, af.shizuku.manager.scripting.ScriptingActivity::class.java))
            true
        }

        findPreference<CollapsiblePreferenceCategory>("category_adb_tools")
            ?.setChildAvailable(KEY_LEGACY_PAIRING, !EnvironmentUtils.isTelevision())

        findPreference<Preference>("reset_adb_keys")?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.settings_reset_adb_keys)
                .setMessage(R.string.settings_reset_adb_keys_summary)
                .setPositiveButton(R.string.settings_reset_adb_keys) { _, _ ->
                    try {
                        ShizukuSettings
                            .getPreferences()
                            .edit()
                            .remove("adbkey")
                            .apply()
                        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
                        keyStore.load(null)
                        keyStore.deleteEntry("_adbkey_encryption_key_")
                        notifyUser(R.string.settings_reset_adb_keys_success)
                        (activity as? SettingsActivity)?.onThemeChanged()
                    } catch (e: Exception) {
                        Timber.tag("AdvancedSettings").e(e, "Failed to reset ADB keys")
                        notifyUser(R.string.settings_reset_adb_keys_error, long = true)
                    }
                }.setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        // Feature 2 (#461): hide_backup_settings toggle — hides the Backup & Restore category
        // from the About screen (AboutSettingsFragment). The toggle is effective on next
        // entry into About (no recreate needed; the fragment re-reads the setting on resume).
        findPreference<TwoStatePreference>("hide_backup_settings")?.apply {
            isChecked = ShizukuSettings.isHideBackupSettingsEnabled()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) ShizukuSettings.setHideBackupSettingsEnabled(newValue)
                true
            }
        }

        // Feature 1 (#469): Package identity info — shows current package name and explains
        // the roadmap for true package name randomization (requires custom build).
        // TODO(#469): Replace this info preference with an EditText + "Randomize" action
        // once the Build-Your-Own CI workflow is available to generate custom-named APKs.
        // The runtime label stored in KEY_CUSTOM_APP_LABEL can be wired into the About screen
        // and the automation intent viewer (HomeActivity) once the UI is fleshed out.
        findPreference<Preference>("package_identity_info")?.apply {
            setCustomSummary(getString(R.string.settings_package_identity_current, context.packageName))
            setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.package_identity_info_title)
                    .setMessage(R.string.package_identity_info_detail)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.toast_copied_to_clipboard) { _, _ ->
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("package", context.packageName))
                        notifyUser(R.string.app_management_package_copied)
                    }.show()
                true
            }
        }

        // The manifest's namespace (af.shizuku.manager) differs from the per-flavor applicationId
        // (context.packageName), so ".LauncherAlias" must resolve against the namespace, not the
        // package name, or ComponentName construction throws "Component class ... does not exist"
        // (SHIZUKUPLUS-7R).
        findPreference<TwoStatePreference>("hide_launcher_icon")?.apply {
            isChecked = ShizukuSettings.isHideLauncherIconEnabled()
            setOnPreferenceChangeListener { pref, newValue ->
                val enable = newValue as? Boolean ?: return@setOnPreferenceChangeListener false
                if (enable) {
                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.stealth_mode_enable_title)
                        .setMessage(getString(R.string.stealth_mode_enable_message, context.packageName))
                        .setPositiveButton(R.string.stealth_mode_enable_button) { _, _ ->
                            ShizukuSettings.setHideLauncherIconEnabled(true)
                            af.shizuku.manager.utils.AppAliasManager
                                .applyAlias(context, "")
                            (pref as? TwoStatePreference)?.isChecked = true
                        }.setNegativeButton(android.R.string.cancel) { _, _ ->
                            (pref as? TwoStatePreference)?.isChecked = false
                        }.show()
                    false
                } else {
                    ShizukuSettings.setHideLauncherIconEnabled(false)
                    af.shizuku.manager.utils.AppAliasManager
                        .applyAlias(context, ShizukuSettings.getAppIconAlias())
                    true
                }
            }
        }

        // Capabilities: Custom API and System Governors
        findPreference<TwoStatePreference>(KEY_CUSTOM_API_ENABLED)?.apply {
            isChecked = ShizukuSettings.isCustomApiEnabled()
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as? Boolean ?: return@setOnPreferenceChangeListener false
                maybePromptRestart(KEY_CUSTOM_API_ENABLED, enabled) {
                    ShizukuSettings.setCustomApiEnabled(enabled)
                    isChecked = enabled
                    ShizukuSettings.syncAllPlusFeaturesToServer()
                }
                false
            }
        }

        // Backup & Restore
        val backupSettingsPref = findPreference<Preference>("backup_settings")
        backupSettingsPref?.setOnPreferenceClickListener {
            val dateStr = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.backup_export_title)
                .setItems(
                    arrayOf(
                        getString(R.string.backup_type_encrypted),
                        getString(R.string.backup_type_plain),
                    ),
                ) { _, which ->
                    try {
                        when (which) {
                            0 -> createBackupLauncher.launch("ShizukuPlus_Settings_$dateStr.json")
                            1 -> createPlainBackupLauncher.launch("ShizukuPlus_Settings_plain_$dateStr.json")
                        }
                    } catch (_: android.content.ActivityNotFoundException) {
                        notifyUser(R.string.backup_no_file_manager_save, long = true)
                    }
                }.show()
            true
        }

        val restoreSettingsPref = findPreference<Preference>("restore_settings")
        restoreSettingsPref?.setOnPreferenceClickListener {
            try {
                restoreBackupLauncher.launch(arrayOf("application/json", "*/*"))
            } catch (_: android.content.ActivityNotFoundException) {
                notifyUser(R.string.backup_no_file_manager_open, long = true)
            }
            true
        }

        val syncPeerPref = findPreference<Preference>("sync_peer_settings")
        val autoSyncPref = findPreference<TwoStatePreference>("auto_sync_peer_settings")
        val peerLabel = SettingsShareManager.getPeerLabel(requireContext())
        val isPeerInstalled = SettingsShareManager.isPeerInstalled(requireContext())

        syncPeerPref?.setAvailable(isPeerInstalled)
        autoSyncPref?.setAvailable(isPeerInstalled)

        if (isPeerInstalled) {
            syncPeerPref?.title = getString(R.string.settings_sync_peer_title, peerLabel)
            syncPeerPref?.setCustomSummary(getString(R.string.settings_sync_peer_summary_installed, peerLabel))

            syncPeerPref?.setOnPreferenceClickListener {
                val ctx = requireContext()
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(getString(R.string.settings_sync_peer_title, peerLabel))
                    .setItems(
                        arrayOf(
                            getString(R.string.settings_sync_peer_import, peerLabel),
                            getString(R.string.settings_sync_peer_export, peerLabel),
                        ),
                    ) { _, which ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            when (which) {
                                0 -> {
                                    val success = SettingsShareManager.importFromPeer(ctx)
                                    withContext(Dispatchers.Main) {
                                        if (success) {
                                            Toast
                                                .makeText(
                                                    ctx,
                                                    ctx.getString(R.string.settings_sync_peer_import_success, peerLabel),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            onRestoreSuccess()
                                        } else {
                                            Toast
                                                .makeText(
                                                    ctx,
                                                    ctx.getString(R.string.settings_sync_peer_failed, peerLabel),
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                        }
                                    }
                                }
                                1 -> {
                                    val success = SettingsShareManager.exportToPeer(ctx)
                                    withContext(Dispatchers.Main) {
                                        Toast
                                            .makeText(
                                                ctx,
                                                if (success) {
                                                    ctx.getString(R.string.settings_sync_peer_export_success, peerLabel)
                                                } else {
                                                    ctx.getString(R.string.settings_sync_peer_failed, peerLabel)
                                                },
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                    }
                                }
                            }
                        }
                    }.show()
                true
            }

            autoSyncPref?.title = getString(R.string.settings_auto_sync_peer_title, peerLabel)
            autoSyncPref?.setCustomSummary(getString(R.string.settings_auto_sync_peer_summary, peerLabel))
            autoSyncPref?.isChecked = ShizukuSettings.isAutoSyncPeerSettingsEnabled()
            autoSyncPref?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) {
                    ShizukuSettings.setAutoSyncPeerSettingsEnabled(newValue)
                }
                true
            }
        }

        applyBackupCategoryVisibility()
    }

    override fun onResume() {
        super.onResume()
        applyBackupCategoryVisibility()
    }

    private fun applyBackupCategoryVisibility() {
        val hide = ShizukuSettings.isHideBackupSettingsEnabled()
        findPreference<af.shizuku.manager.settings.CollapsiblePreferenceCategory>("category_backup")
            ?.isVisible = !hide
    }

    // The restore applied everything except sensitive settings it would have switched on: list
    // those and let the user decide, instead of a backup silently re-enabling them.
    private fun confirmHeldBackPrivilegedSettings() {
        val held = af.shizuku.manager.utils.SettingsBackupManager.heldBack
        if (held.isEmpty()) return
        val ctx = requireContext()
        val names =
            held.keys.joinToString("\n") { key ->
                "\u2022 " +
                    (
                        af.shizuku.manager.utils.SettingsBackupManager.PRIVILEGED_KEYS[key]
                            ?.let { ctx.getString(it) } ?: key
                    )
            }
        com.google.android.material.dialog
            .MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.restore_privileged_title)
            .setMessage(ctx.getString(R.string.restore_privileged_message, names))
            .setNegativeButton(R.string.restore_privileged_keep_off) { _, _ ->
                af.shizuku.manager.utils.SettingsBackupManager
                    .discardHeldBack()
            }.setPositiveButton(R.string.restore_privileged_turn_on) { _, _ ->
                af.shizuku.manager.utils.SettingsBackupManager
                    .applyHeldBack()
            }.setCancelable(false)
            .show()
    }

    private fun onRestoreSuccess() {
        if (!isAdded) return
        af.shizuku.manager.utils.showRestoreSummary(requireContext()) {
            if (isAdded) confirmHeldBackPrivilegedSettings()
        }
        Snackbar
            .make(
                requireView(),
                R.string.backup_restored_restart_hint,
                Snackbar.LENGTH_LONG,
            ).setAction(R.string.backup_restored_restart_now) {
                requireActivity().recreate()
            }.show()
    }
}
