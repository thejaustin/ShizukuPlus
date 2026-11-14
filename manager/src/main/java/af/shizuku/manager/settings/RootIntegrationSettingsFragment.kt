package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.Keys.KEY_COMPANION_FALLBACK
import af.shizuku.manager.ShizukuSettings.Keys.KEY_DHIZUKU_MODE
import af.shizuku.manager.database.RootCompatHelper
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.StockShizukuCompat
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.TwoStatePreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Root Integration Settings
 *
 * Provides SU Bridge and ADB proxy features to integrate root access
 * with apps that don't natively support Shizuku.
 */
class RootIntegrationSettingsFragment : BaseSettingsFragment() {
    override fun getTitle(): CharSequence? = getString(R.string.settings_main_nav_root_compat_title)

    override fun onCreateSettingsPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.settings_root_integration, rootKey)
        val context = requireContext()

        // Sync current su_bridge state to server on fragment open
        ShizukuSettings.syncAllPlusFeaturesToServer()

        val bootloaderUnlocked = isBootloaderUnlocked()
        val bootloaderCategory = findPreference<androidx.preference.PreferenceGroup>("category_unlocked_bootloader")
        if (bootloaderCategory != null) {
            bootloaderCategory.isVisible = bootloaderUnlocked
            if (!bootloaderUnlocked) {
                // Optionally remove it completely
                preferenceScreen.removePreference(bootloaderCategory)
            }
        }

        findPreference<TwoStatePreference>(KEY_COMPANION_FALLBACK)?.apply {
            isChecked = ShizukuSettings.isCompanionFallbackEnabled()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) ShizukuSettings.setCompanionFallbackEnabled(newValue)
                true
            }
        }
        findPreference<CollapsiblePreferenceCategory>("category_stock_shizuku_compat")
            ?.setChildAvailable("launch_stock_shizuku", StockShizukuCompat.isInstalled(requireContext()))
        findPreference<Preference>("launch_stock_shizuku")?.setOnPreferenceClickListener {
            StockShizukuCompat.launch(it.context)
            true
        }

        val dhizukuPref = findPreference<TwoStatePreference>(KEY_DHIZUKU_MODE)
        if (dhizukuPref != null) {
            dhizukuPref.isChecked = ShizukuSettings.isDhizukuModeEnabled()
            updateDhizukuDeviceOwnerStatus(dhizukuPref)
            dhizukuPref.setOnPreferenceClickListener {
                val ctx = context ?: return@setOnPreferenceClickListener true
                if (!isDeviceOwnerActive(ctx)) {
                    showDhizukuSetupDialog(ctx)
                    return@setOnPreferenceClickListener true
                }
                false
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
        }

        findPreference<Preference>("install_dhizuku_compat")?.setOnPreferenceClickListener { pref ->
            installDhizukuCompat(pref)
            true
        }

        val clearDoPref = findPreference<Preference>("clear_device_owner")
        clearDoPref?.let { setChildAvailable(it, isDeviceOwnerActive(requireContext())) }
        clearDoPref?.setOnPreferenceClickListener {
            val ctx = context ?: return@setOnPreferenceClickListener true
            showClearDeviceOwnerDialog(ctx)
            true
        }

        findPreference<TwoStatePreference>("dpm_freeze_enabled")?.apply {
            isChecked = ShizukuSettings.isDpmFreezeEnabled()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) ShizukuSettings.setDpmFreezeEnabled(newValue)
                true
            }
        }

        findPreference<TwoStatePreference>("dhizuku_disable_screencap")?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean ?: false
            val ctx = context ?: return@setOnPreferenceChangeListener false
            try {
                val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(ctx, af.shizuku.manager.admin.DhizukuAdminReceiver::class.java)
                dpm.setScreenCaptureDisabled(admin, enabled)
                notifyUser(if (enabled) R.string.dpm_screen_capture_disabled else R.string.dpm_screen_capture_enabled)
                true
            } catch (_: Exception) {
                notifyUser(R.string.dpm_requires_device_owner, long = true)
                false
            }
        }

        findPreference<TwoStatePreference>("dhizuku_disallow_usb")?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean ?: false
            val ctx = context ?: return@setOnPreferenceChangeListener false
            try {
                val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(ctx, af.shizuku.manager.admin.DhizukuAdminReceiver::class.java)
                if (enabled) {
                    dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_USB_FILE_TRANSFER)
                    notifyUser(R.string.dpm_usb_locked)
                } else {
                    dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_USB_FILE_TRANSFER)
                    notifyUser(R.string.dpm_usb_unlocked)
                }
                true
            } catch (_: Exception) {
                notifyUser(R.string.dpm_requires_device_owner, long = true)
                false
            }
        }

        findPreference<Preference>("dhizuku_suspended_packages")?.setOnPreferenceChangeListener { _, newValue ->
            val packagesStr = newValue as? String ?: ""
            val packagesList = if (packagesStr.isBlank()) emptyList() else packagesStr.split(",").map { it.trim() }
            val ctx = context ?: return@setOnPreferenceChangeListener false
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val admin = ComponentName(ctx, af.shizuku.manager.admin.DhizukuAdminReceiver::class.java)
                    val pm = ctx.packageManager
                    val installed = pm.getInstalledPackages(0).map { it.packageName }.toSet()

                    val toUnsuspend = installed.toMutableList()
                    dpm.setPackagesSuspended(admin, toUnsuspend.toTypedArray(), false)

                    var message: String? = null
                    if (packagesList.isNotEmpty()) {
                        val toSuspend = packagesList.filter { installed.contains(it) }
                        val failed = dpm.setPackagesSuspended(admin, toSuspend.toTypedArray(), true)
                        message =
                            if (failed.isNotEmpty()) {
                                ctx.getString(R.string.dpm_freeze_failed, failed.joinToString())
                            } else {
                                ctx.getString(R.string.dpm_freeze_success, toSuspend.size)
                            }
                    }
                    withContext(Dispatchers.Main) {
                        message?.let { notifyUser(it) }
                    }
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) {
                        notifyUser(R.string.dpm_requires_device_owner, long = true)
                    }
                }
            }
            true
        }

        findPreference<TwoStatePreference>("samsung_system_uid_escalation_enabled")?.let { pref ->
            // Only show on Samsung OneUI 1–6; OneUI 7+ patched the system-UID elevation path.
            val samsungSupported = EnvironmentUtils.isSamsung() && EnvironmentUtils.getOneUiVersion() in 1..6
            setChildAvailable(pref, samsungSupported)
            if (samsungSupported) {
                pref.setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as? Boolean ?: false
                    if (enabled) {
                        val ctx = context ?: return@setOnPreferenceChangeListener false
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle(R.string.settings_samsung_uid_escalation)
                            .setMessage(R.string.settings_samsung_uid_escalation_summary)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                preferenceManager.sharedPreferences
                                    ?.edit()
                                    ?.putBoolean("samsung_system_uid_escalation_enabled", true)
                                    ?.apply()
                                ShizukuSettings.syncAllPlusFeaturesToServer()
                            }.setNegativeButton(android.R.string.cancel, null)
                            .show()
                        false
                    } else {
                        preferenceManager.sharedPreferences
                            ?.edit()
                            ?.putBoolean("samsung_system_uid_escalation_enabled", false)
                            ?.apply()
                        ShizukuSettings.syncAllPlusFeaturesToServer()
                        true
                    }
                }
            }
        }

        findPreference<TwoStatePreference>("su_bridge_enabled")?.setOnPreferenceChangeListener { pref, newValue ->
            if (newValue is Boolean) {
                if (newValue) {
                    val ctx = context ?: return@setOnPreferenceChangeListener false
                    com.google.android.material.dialog
                        .MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.su_bridge_warning_wallet_title)
                        .setMessage(R.string.su_bridge_warning_wallet_message)
                        .setPositiveButton(R.string.action_continue) { _, _ ->
                            preferenceManager.sharedPreferences
                                ?.edit()
                                ?.putBoolean("su_bridge_enabled", true)
                                ?.apply()
                            (pref as? TwoStatePreference)?.isChecked = true
                            ShizukuSettings.syncAllPlusFeaturesToServer()
                            val appCtx = context?.applicationContext ?: return@setPositiveButton
                            lifecycleScope.launch(Dispatchers.IO) {
                                RootCompatHelper.deployBridgeToTmp(appCtx)
                            }
                        }.setNegativeButton(android.R.string.cancel, null)
                        .show()
                    return@setOnPreferenceChangeListener false
                } else {
                    preferenceManager.sharedPreferences
                        ?.edit()
                        ?.putBoolean("su_bridge_enabled", false)
                        ?.apply()
                    ShizukuSettings.syncAllPlusFeaturesToServer()
                    val appCtx = context?.applicationContext
                    lifecycleScope.launch(Dispatchers.IO) {
                        if (appCtx != null) {
                            RootCompatHelper.cleanupBridgeFromTmp(appCtx)
                        }
                    }
                    return@setOnPreferenceChangeListener true
                }
            }
            true
        }

        val rootModules =
            listOf(
                "stealth_mode",
                "root_build_prop_redirect_enabled",
                "root_iptables_mocking_enabled",
                "root_magisk_mocking_enabled",
                "root_auto_grant_enabled",
                "root_file_interceptor_enabled",
                "root_busybox_mocking_enabled",
                "overlay_fs_proxy_enabled",
                "root_kernel_ghosting_enabled",
                "root_partition_ghosting_enabled",
                "root_power_ghosting_enabled",
                "bootloader_flash_ota_enabled",
                "bootloader_fastbootd_reboot_enabled",
            )
        rootModules.forEach { key ->
            val pref = findPreference<TwoStatePreference>(key)
            pref?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) {
                    if (newValue && key == "bootloader_flash_ota_enabled") {
                        val ctx = context ?: return@setOnPreferenceChangeListener false
                        com.google.android.material.dialog
                            .MaterialAlertDialogBuilder(ctx)
                            .setTitle(R.string.ota_flash_danger_title)
                            .setMessage(R.string.ota_flash_danger_message)
                            .setPositiveButton(R.string.ota_flash_danger_confirm) { _, _ ->
                                preferenceManager.sharedPreferences
                                    ?.edit()
                                    ?.putBoolean(key, true)
                                    ?.apply()
                                pref.isChecked = true
                                ShizukuSettings.syncAllPlusFeaturesToServer()
                            }.setNegativeButton(android.R.string.cancel, null)
                            .show()
                        return@setOnPreferenceChangeListener false
                    } else if (newValue && key == "root_magisk_mocking_enabled") {
                        val ctx = context ?: return@setOnPreferenceChangeListener false
                        com.google.android.material.dialog
                            .MaterialAlertDialogBuilder(ctx)
                            .setTitle(R.string.su_bridge_warning_wallet_title)
                            .setMessage(R.string.su_bridge_warning_wallet_message)
                            .setPositiveButton(R.string.action_continue) { _, _ ->
                                preferenceManager.sharedPreferences
                                    ?.edit()
                                    ?.putBoolean(key, true)
                                    ?.apply()
                                pref.isChecked = true
                                ShizukuSettings.syncAllPlusFeaturesToServer()
                            }.setNegativeButton(android.R.string.cancel, null)
                            .show()
                        return@setOnPreferenceChangeListener false
                    } else {
                        preferenceManager.sharedPreferences
                            ?.edit()
                            ?.putBoolean(key, newValue)
                            ?.apply()
                        ShizukuSettings.syncAllPlusFeaturesToServer()
                        return@setOnPreferenceChangeListener true
                    }
                }
                true
            }
        }

        findPreference<Preference>("root_compatibility_hub")?.setOnPreferenceClickListener {
            startActivity(Intent(context, RootCompatibilityActivity::class.java))
            true
        }

        // Preset SU Path Picker helper
        val suPathPref = findPreference<androidx.preference.EditTextPreference>("custom_su_path")
        suPathPref?.setOnPreferenceChangeListener { _, newValue ->
            val path = (newValue as? String).orEmpty().trim()
            // Empty = reset to default (always valid). Non-empty must be an absolute path
            // using only safe characters to prevent shell metacharacter injection.
            if (path.isNotEmpty() && !path.matches(Regex("^/[a-zA-Z0-9_./\\-]+$"))) {
                Toast
                    .makeText(
                        context,
                        getString(R.string.su_path_invalid),
                        Toast.LENGTH_LONG,
                    ).show()
                return@setOnPreferenceChangeListener false
            }
            ShizukuSettings.syncAllPlusFeaturesToServer()
            true
        }
        suPathPref?.setOnPreferenceClickListener {
            val presets =
                arrayOf(
                    getString(R.string.su_path_preset_default),
                    "/system/bin/su (Standard AOSP)",
                    "/system/xbin/su (SuperSU Legacy)",
                    "/sbin/su (Magisk/Custom ROMs)",
                    "/data/adb/ksu/bin/su (KernelSU)",
                    "/data/adb/ap/bin/su (APatch)",
                    getString(R.string.su_path_preset_custom),
                )
            val presetValues =
                arrayOf(
                    "",
                    "/system/bin/su",
                    "/system/xbin/su",
                    "/sbin/su",
                    "/data/adb/ksu/bin/su",
                    "/data/adb/ap/bin/su",
                    "custom",
                )

            com.google.android.material.dialog
                .MaterialAlertDialogBuilder(context)
                .setTitle(R.string.su_path_preset_dialog_title)
                .setItems(presets) { _, which ->
                    val chosen = presetValues[which]
                    if (chosen == "custom") {
                        // Let the default EditTextDialog handle custom text entry
                        (this@RootIntegrationSettingsFragment).onDisplayPreferenceDialog(suPathPref)
                    } else {
                        // Setting .text programmatically does NOT fire the change listener,
                        // so push the new path to the server explicitly.
                        suPathPref.text = chosen
                        ShizukuSettings.syncAllPlusFeaturesToServer()
                        notifyUser(context?.getString(R.string.su_path_preset_applied, presets[which]))
                    }
                }.show()
            true // Intercept click to show presets dialog first
        }
    }

    override fun onResume() {
        super.onResume()
        applyModeConstraints()
    }

    /**
     * In ADB mode (uid 2000) the bootloader integration needs an unlocked bootloader, so it's
     * greyed out on a locked one. Every other category works in any mode. The uid is a binder
     * call, so it's read off the main thread.
     */
    private fun applyModeConstraints() {
        viewLifecycleOwnerLiveData.value?.lifecycleScope?.launch {
            val adbMode = withContext(Dispatchers.IO) { runCatching { Shizuku.getUid() }.getOrDefault(-1) == 2000 }
            if (adbMode) {
                findPreference<PreferenceGroup>("category_unlocked_bootloader")?.isEnabled = isBootloaderUnlocked()
            }
        }
    }

    private fun isDeviceOwnerActive(ctx: Context): Boolean =
        try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isDeviceOwnerApp(ctx.packageName) || dpm.isProfileOwnerApp(ctx.packageName)
        } catch (_: Exception) {
            false
        }

    private fun updateDhizukuDeviceOwnerStatus(pref: TwoStatePreference) {
        val ctx = context ?: return
        val active = isDeviceOwnerActive(ctx)
        val statusLine =
            if (active) {
                getString(R.string.dhizuku_status_active)
            } else {
                getString(R.string.dhizuku_status_not_set)
            }
        val baseSummary = getString(R.string.settings_dhizuku_mode_summary)
        pref.setCustomSummary("$statusLine\n\n$baseSummary")
        findPreference<Preference>("clear_device_owner")?.let { setChildAvailable(it, active) }
        findPreference<Preference>("install_dhizuku_compat")?.let { setChildAvailable(it, active && shouldOfferDhizukuCompat(ctx)) }
        findPreference<PreferenceGroup>("category_dhizuku_admin")?.apply {
            isEnabled = active
            setCustomSummary(if (!active) getString(R.string.dpm_requires_device_owner) else null)
        }
    }

    // The add-on answers apps on the older Dhizuku library; it claims the real Dhizuku app's
    // provider authority, so it can't install next to that app (#670).
    private fun shouldOfferDhizukuCompat(ctx: Context): Boolean =
        af.shizuku.manager.utils.BundledApkInstaller
            .isBundled(ctx, DHIZUKU_COMPAT_ASSET) &&
            !af.shizuku.manager.utils.BundledApkInstaller
                .isInstalled(ctx, "com.rosan.dhizuku") &&
            !af.shizuku.manager.utils.BundledApkInstaller
                .isInstalled(ctx, DHIZUKU_COMPAT_PACKAGE)

    private fun installDhizukuCompat(pref: Preference) {
        val ctx = pref.context
        pref.isEnabled = false
        notifyUser(R.string.dhizuku_compat_installing)
        lifecycleScope.launch {
            val error =
                af.shizuku.manager.utils.BundledApkInstaller
                    .install(ctx.applicationContext, DHIZUKU_COMPAT_ASSET)
            if (!isAdded) return@launch
            pref.isEnabled = true
            if (error == null) {
                listView?.let {
                    af.shizuku.manager.utils.HapticUtils
                        .success(it)
                }
                notifyUser(R.string.dhizuku_compat_installed)
                findPreference<TwoStatePreference>(KEY_DHIZUKU_MODE)?.let { updateDhizukuDeviceOwnerStatus(it) }
            } else {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.dhizuku_compat_install_failed)
                    .setMessage(error)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun showClearDeviceOwnerDialog(ctx: Context) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dhizuku_clear_owner_title)
            .setMessage(R.string.dhizuku_clear_owner_message)
            .setPositiveButton(R.string.dhizuku_clear_owner_confirm) { _, _ ->
                clearDeviceOwner(ctx)
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearDeviceOwner(ctx: Context) {
        try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isDeviceOwnerApp(ctx.packageName)) {
                dpm.clearDeviceOwnerApp(ctx.packageName)
            } else if (dpm.isProfileOwnerApp(ctx.packageName)) {
                val admin = ComponentName(ctx, af.shizuku.manager.admin.DhizukuAdminReceiver::class.java)
                dpm.clearProfileOwner(admin)
            }
            notifyUser(R.string.dhizuku_clear_owner_success, long = true)
            val dhizukuPref = findPreference<TwoStatePreference>(KEY_DHIZUKU_MODE)
            if (dhizukuPref != null) {
                ShizukuSettings.setDhizukuModeEnabled(false)
                dhizukuPref.isChecked = false
                updateDhizukuDeviceOwnerStatus(dhizukuPref)
            }
        } catch (_: Exception) {
            notifyUser(R.string.dhizuku_clear_owner_failure, long = true)
        }
    }

    private fun showDhizukuSetupDialog(ctx: Context) {
        val component = "${ctx.packageName}/af.shizuku.manager.admin.DhizukuAdminReceiver"
        val command = "adb shell dpm set-device-owner $component"
        val builder =
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.dhizuku_setup_title)
                .setMessage(getString(R.string.dhizuku_setup_message, command))
                .setPositiveButton(R.string.dhizuku_setup_copy) { _, _ ->
                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("dpm command", command))
                    notifyUser(R.string.dhizuku_setup_copied)
                }.setNegativeButton(android.R.string.cancel, null)
        // The running server is already the ADB shell (or root), so it can run the same command
        // on the phone; no PC needed (#674).
        if (serverCanSetDeviceOwner()) {
            builder.setNeutralButton(R.string.dhizuku_setup_activate_now) { _, _ -> activateDeviceOwner(ctx, component) }
        }
        builder.show()
    }

    private fun serverCanSetDeviceOwner(): Boolean =
        runCatching { Shizuku.pingBinder() && Shizuku.getUid().let { it == 0 || it == 2000 } }.getOrDefault(false)

    private fun activateDeviceOwner(
        ctx: Context,
        component: String,
    ) {
        val appContext = ctx.applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val output = withSetupIncomplete { runServerShell("dpm set-device-owner $component").second }
            val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val isOwner = runCatching { dpm.isDeviceOwnerApp(appContext.packageName) }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (isOwner) {
                    notifyUser(R.string.dhizuku_activate_success, long = true)
                    findPreference<TwoStatePreference>(KEY_DHIZUKU_MODE)?.let { updateDhizukuDeviceOwnerStatus(it) }
                } else {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.dhizuku_activate_failed_title)
                        .setMessage(getString(R.string.dhizuku_activate_failed_message, output.ifBlank { "-" }))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    // Android refuses a Device Owner while accounts are signed in only once setup has completed. As
    // the Dhizuku app does, mark this user's setup as not complete for the moment the owner is set,
    // make the device-policy service re-read it (it caches the value from Android 12), and always
    // put it back. Without this, activating meant removing every account first (#674).
    private fun <T> withSetupIncomplete(block: () -> T): T {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return block()
        val user = android.os.Process.myUid() / 100000
        val (_, current) = runServerShell("settings --user $user get secure user_setup_complete")
        if (current.trim() != "1") return block()
        if (runServerShell("settings --user $user put secure user_setup_complete 0").first != 0) return block()
        forceUpdateUserSetupComplete(user)
        try {
            return block()
        } finally {
            runServerShell("settings --user $user put secure user_setup_complete 1")
            forceUpdateUserSetupComplete(user)
        }
    }

    // Hidden IDevicePolicyManager.forceUpdateUserSetupComplete, called as the server (shell holds
    // the permission it needs) through a Shizuku binder wrapper.
    private fun forceUpdateUserSetupComplete(user: Int) {
        runCatching {
            val binder = rikka.shizuku.SystemServiceHelper.getSystemService(Context.DEVICE_POLICY_SERVICE) ?: return
            val service =
                Class
                    .forName("android.app.admin.IDevicePolicyManager\$Stub")
                    .getMethod("asInterface", android.os.IBinder::class.java)
                    .invoke(null, rikka.shizuku.ShizukuBinderWrapper(binder))
            val iface = Class.forName("android.app.admin.IDevicePolicyManager")
            runCatching { iface.getMethod("forceUpdateUserSetupComplete", Int::class.javaPrimitiveType).invoke(service, user) }
                .getOrElse { iface.getMethod("forceUpdateUserSetupComplete").invoke(service) }
        }.onFailure { timber.log.Timber.w(it, "forceUpdateUserSetupComplete failed") }
    }

    // Runs [command] as the server and returns its exit code and combined output (dpm's own message
    // is the useful part when it refuses).
    @Suppress("DEPRECATION")
    private fun runServerShell(command: String): Pair<Int, String> =
        try {
            val proc = Shizuku.newProcess(arrayOf("sh", "-c", "$command 2>&1"), null, null)
            val out = StringBuilder()
            val reader =
                Thread {
                    runCatching { out.append(proc.inputStream.bufferedReader().use { it.readText() }) }
                }.apply {
                    isDaemon = true
                    start()
                }
            val finished = proc.waitForTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) proc.destroy()
            reader.join(2_000)
            (if (finished) proc.exitValue() else -1) to out.toString().trim()
        } catch (e: Exception) {
            -1 to (e.message ?: e.javaClass.simpleName)
        }

    private fun isBootloaderUnlocked(): Boolean {
        fun readProp(key: String): String? =
            try {
                Class
                    .forName("android.os.SystemProperties")
                    .getMethod("get", String::class.java, String::class.java)
                    .invoke(null, key, "") as? String
            } catch (_: Exception) {
                null
            }
        return readProp("ro.boot.flash.locked") == "0" ||
            readProp("ro.boot.verifiedbootstate") == "orange"
    }

    private fun setChildAvailable(
        pref: Preference,
        available: Boolean,
    ) {
        val key = pref.key ?: return
        (pref.parent as? CollapsiblePreferenceCategory)?.setChildAvailable(key, available)
            ?: run { pref.isVisible = available }
    }
}

/** The Dhizuku Compat add-on, bundled by copyDhizukuCompatApk*. */
private const val DHIZUKU_COMPAT_ASSET = "dhizuku_compat.apk"
private const val DHIZUKU_COMPAT_PACKAGE = "af.shizuku.plus.dhizuku"
