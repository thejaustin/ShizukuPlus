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

        // Root modules are Plus rows, which sync themselves once saved. Two of them confirm first:
        // flashing OTAs, and Magisk mocking (it can break wallet apps, like the SU bridge).
        mapOf(
            "bootloader_flash_ota_enabled" to (R.string.ota_flash_danger_title to R.string.ota_flash_danger_message),
            "root_magisk_mocking_enabled" to (R.string.su_bridge_warning_wallet_title to R.string.su_bridge_warning_wallet_message),
        ).forEach { (key, texts) ->
            val pref = findPreference<TwoStatePreference>(key)
            pref?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue != true) return@setOnPreferenceChangeListener true
                val ctx = context ?: return@setOnPreferenceChangeListener false
                com.google.android.material.dialog
                    .MaterialAlertDialogBuilder(ctx)
                    .setTitle(texts.first)
                    .setMessage(texts.second)
                    .setPositiveButton(
                        if (key == "bootloader_flash_ota_enabled") R.string.ota_flash_danger_confirm else R.string.action_continue,
                    ) { _, _ -> pref.isChecked = true }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                false
            }
        }

        setUpMockingGroup()

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

    override fun onDestroy() {
        mockingListener?.let { preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(it) }
        super.onDestroy()
    }

    private var mockingListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    /**
     * Expressive controls preview: iptables, Magisk and BusyBox mocking as one multi-select group.
     * Taps drive the real rows (hidden, not removed), so Magisk's Wallet warning, the server sync
     * and backup rules all stay exactly as they are.
     */
    private fun setUpMockingGroup() {
        val group = findPreference<ConnectedToggleGroupPreference>("root_mocking_group") ?: return
        // The category sets every child's visibility when it expands, so hide through it.
        val category = findPreference<CollapsiblePreferenceCategory>("category_ghost_bridge")
        val rows =
            listOf("root_iptables_mocking_enabled", "root_magisk_mocking_enabled", "root_busybox_mocking_enabled")
                .mapNotNull { findPreference<TwoStatePreference>(it) }
        val preview = ShizukuSettings.isExpressiveControlsPreviewEnabled() && rows.size == 3 && category != null
        category?.setChildAvailable(group.key, preview)
        if (!preview) {
            group.isVisible = false
            return
        }
        group.labels =
            listOf(
                getString(R.string.settings_root_mocking_group_iptables),
                getString(R.string.settings_root_mocking_group_magisk),
                getString(R.string.settings_root_mocking_group_busybox),
            )
        group.isChecked = { rows[it].isChecked }
        group.onToggle = { index, checked ->
            val row = rows[index]
            if (row.callChangeListener(checked)) row.isChecked = checked
            group.refresh()
        }
        rows.forEach { category?.setChildAvailable(it.key, false) }
        val keys = rows.map { it.key }.toSet()
        mockingListener =
            android.content.SharedPreferences
                .OnSharedPreferenceChangeListener { _, key ->
                    if (key in keys) group.refresh()
                }.also { preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(it) }
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
                val isProfileOwner =
                    runCatching {
                        (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isProfileOwnerApp(ctx.packageName)
                    }.getOrDefault(false)
                getString(if (isProfileOwner) R.string.dhizuku_status_active_limited else R.string.dhizuku_status_active)
            } else {
                getString(R.string.dhizuku_status_not_set)
            }
        val baseSummary = getString(R.string.settings_dhizuku_mode_summary)
        pref.setCustomSummary("$statusLine\n\n$baseSummary")
        findPreference<Preference>("clear_device_owner")?.let { setChildAvailable(it, active) }
        findPreference<Preference>("install_dhizuku_compat")?.let { setChildAvailable(it, active && shouldOfferDhizukuCompat(ctx)) }
        updateDhizukuAllowedApps(active)
        findPreference<PreferenceGroup>("category_dhizuku_admin")?.apply {
            isEnabled = active
            setCustomSummary(if (!active) getString(R.string.dpm_requires_device_owner) else null)
        }
    }

    private fun updateDhizukuAllowedApps(active: Boolean) {
        val pref = findPreference<Preference>("dhizuku_allowed_apps") ?: return
        // Runs from onCreatePreferences too, before there's a view, so this uses the fragment's
        // own scope: the view's one would silently skip it, leaving a blank row that did nothing
        // (#683). Hidden until the grants are read, so expanding the category can't show it early.
        setChildAvailable(pref, false)
        lifecycleScope.launch {
            val grants =
                withContext(Dispatchers.IO) {
                    af.shizuku.manager.authorization.DhizukuGrants
                        .all()
                }
            val ctx = context ?: return@launch
            // Shown while there's something to see or revoke, even if Device Owner is gone.
            setChildAvailable(pref, active || grants.isNotEmpty())
            pref.summary =
                if (grants.isEmpty()) {
                    getString(R.string.settings_dhizuku_allowed_apps_none)
                } else {
                    resources.getQuantityString(R.plurals.settings_dhizuku_allowed_apps_count, grants.size, grants.size)
                }
            // Nothing to open with no apps yet; the summary says so.
            pref.isSelectable = grants.isNotEmpty()
            pref.setOnPreferenceClickListener {
                if (grants.isNotEmpty()) showDhizukuAllowedApps(ctx, grants)
                true
            }
        }
    }

    private fun showDhizukuAllowedApps(
        ctx: Context,
        grants: List<Pair<String, Int>>,
    ) {
        val pm = ctx.packageManager
        val labels =
            grants
                .map { (pkg, _) ->
                    runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrDefault(pkg)
                }.toTypedArray()
        val keep = BooleanArray(grants.size) { true }
        com.google.android.material.dialog
            .MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.settings_dhizuku_allowed_apps)
            .setMultiChoiceItems(labels, keep) { _, which, checked -> keep[which] = checked }
            .setPositiveButton(R.string.settings_dhizuku_allowed_apps_save) { _, _ ->
                val removed = grants.filterIndexed { i, _ -> !keep[i] }
                if (removed.isEmpty()) return@setPositiveButton
                viewLifecycleOwnerLiveData.value?.lifecycleScope?.launch {
                    withContext(Dispatchers.IO) {
                        removed.forEach { (pkg, uid) ->
                            // Dhizuku access is dropped even when Shizuku can't change its own grant.
                            runCatching {
                                af.shizuku.manager.authorization.AuthorizationManager
                                    .revoke(pkg, uid)
                            }
                        }
                    }
                    updateDhizukuAllowedApps(isDeviceOwnerActive(ctx))
                }
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
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

    // Android lifts the "no new profiles" restrictions when the owner goes, but Samsung's Knox
    // flags may stay and keep Secure Folder from being created; say so instead of letting the user
    // find out from Secure Folder's own vague error. Only what wasn't set before activating counts,
    // checked after a moment since the policy service may apply the change asynchronously.
    private fun reportOwnerLeftovers(ctx: Context) {
        val appContext = ctx.applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(2_000)
            val left =
                af.shizuku.manager.admin.OwnerActivation
                    .leftoverLimits(appContext)
            if (left.isEmpty()) {
                af.shizuku.manager.admin.OwnerActivation
                    .clearBaseline()
            }
            withContext(Dispatchers.Main) {
                if (!isAdded || left.isEmpty()) return@withContext
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.dhizuku_clear_owner_leftovers_title)
                    .setMessage(getString(R.string.dhizuku_clear_owner_leftovers_message, left.joinToString("\n") { "• $it" }))
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
            reportOwnerLeftovers(ctx)
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
        af.shizuku.manager.admin.OwnerActivation
            .serverCanActivate()

    private fun activateDeviceOwner(
        ctx: Context,
        @Suppress("UNUSED_PARAMETER") component: String,
    ) {
        val appContext = ctx.applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            // Never with other users or profiles present (see OwnerActivation). Unknown counts as
            // present.
            val others =
                af.shizuku.manager.admin.OwnerActivation
                    .otherUsers()
            if (others == null || others.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    if (isAdded) showOtherUsersBlocked(others)
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                // What a Device Owner blocks for as long as it's active, said before it happens.
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.dhizuku_activate_confirm_title)
                    .setMessage(R.string.dhizuku_activate_confirm_message)
                    .setPositiveButton(R.string.dhizuku_setup_activate_now) { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            runActivation(appContext, af.shizuku.manager.admin.OwnerActivation.Mode.DEVICE_OWNER)
                        }
                    }.setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    // Shown instead of activating; with the experimental limited mode on it offers that instead,
    // which Android allows next to other profiles.
    private fun showOtherUsersBlocked(others: List<String>?) {
        val builder =
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dhizuku_activate_other_users_title)
                .setMessage(
                    getString(
                        R.string.dhizuku_activate_other_users_message,
                        others?.joinToString("\n") { "• $it" } ?: "-",
                    ),
                ).setPositiveButton(android.R.string.ok, null)
        if (others != null && ShizukuSettings.isDhizukuLimitedModeEnabled()) {
            builder.setNeutralButton(R.string.dhizuku_limited_mode_use) { _, _ -> confirmLimitedMode() }
        }
        builder.show()
    }

    private fun confirmLimitedMode() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dhizuku_limited_mode_title)
            .setMessage(R.string.dhizuku_limited_mode_message)
            .setPositiveButton(R.string.dhizuku_limited_mode_activate) { _, _ ->
                val appContext = requireContext().applicationContext
                lifecycleScope.launch(Dispatchers.IO) {
                    runActivation(appContext, af.shizuku.manager.admin.OwnerActivation.Mode.PROFILE_OWNER)
                }
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private suspend fun runActivation(
        appContext: Context,
        mode: af.shizuku.manager.admin.OwnerActivation.Mode,
    ) {
        val result =
            af.shizuku.manager.admin.OwnerActivation
                .activate(appContext, mode)
        val output = result.output
        val isOwner = result.backupOn != null
        withContext(Dispatchers.Main) {
            if (!isAdded) return@withContext
            if (isOwner) {
                notifyUser(
                    if (mode == af.shizuku.manager.admin.OwnerActivation.Mode.DEVICE_OWNER) {
                        R.string.dhizuku_activate_success
                    } else {
                        R.string.dhizuku_limited_mode_success
                    },
                    long = true,
                )
                findPreference<TwoStatePreference>(KEY_DHIZUKU_MODE)?.let { updateDhizukuDeviceOwnerStatus(it) }
                if (result.backupOn == false) notifyUser(R.string.dhizuku_activate_backup_off, long = true)
            } else {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.dhizuku_activate_failed_title)
                    .setMessage(getString(R.string.dhizuku_activate_failed_message, output.ifBlank { "-" }))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
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
