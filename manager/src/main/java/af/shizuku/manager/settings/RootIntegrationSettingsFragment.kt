package af.shizuku.manager.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.Keys.KEY_COMPANION_FALLBACK
import af.shizuku.manager.service.AdbProxyService
import af.shizuku.manager.utils.StockShizukuCompat
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/**
 * Root Integration Settings
 *
 * Provides SU Bridge and ADB proxy features to integrate root access
 * with apps that don't natively support Shizuku.
 */
class RootIntegrationSettingsFragment : BaseSettingsFragment() {

    override fun getTitle(): CharSequence? = getString(R.string.settings_root_compatibility_title)

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
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
        findPreference<Preference>("launch_stock_shizuku")?.apply {
            isVisible = StockShizukuCompat.isInstalled(requireContext())
            setOnPreferenceClickListener {
                StockShizukuCompat.launch(it.context)
                true
            }
        }

        findPreference<TwoStatePreference>("adb_proxy_enabled")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                val intent = Intent(context, AdbProxyService::class.java)
                if (newValue) context.startService(intent) else context.stopService(intent)
            }
            true
        }

        findPreference<TwoStatePreference>("on_device_adb_tcp")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                preferenceManager.sharedPreferences?.edit()?.putBoolean("on_device_adb_tcp", newValue)?.apply()
                lifecycleScope.launch(Dispatchers.IO) {
                    if (newValue) {
                        af.shizuku.manager.service.AdbProxyService.enableAdbTcp()
                    } else {
                        af.shizuku.manager.service.AdbProxyService.disableAdbTcp()
                    }
                }
            }
            true
        }

        findPreference<TwoStatePreference>("force_start_wadb")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                preferenceManager.sharedPreferences?.edit()?.putBoolean("force_start_wadb", newValue)?.apply()
                ShizukuSettings.syncAllPlusFeaturesToServer()
            }
            true
        }

        findPreference<TwoStatePreference>("su_bridge_enabled")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                preferenceManager.sharedPreferences?.edit()?.putBoolean("su_bridge_enabled", newValue)?.apply()
                ShizukuSettings.syncAllPlusFeaturesToServer()
            }
            true
        }

        val rootModules = listOf(
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
            "bootloader_fastbootd_reboot_enabled"
        )
        rootModules.forEach { key ->
            val pref = findPreference<TwoStatePreference>(key)
            pref?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) {
                    if (newValue && key == "bootloader_flash_ota_enabled") {
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                            .setTitle(R.string.root_ota_danger_title)
                            .setMessage(R.string.root_ota_danger_message)
                            .setPositiveButton(R.string.root_ota_enable) { _, _ ->
                                preferenceManager.sharedPreferences?.edit()?.putBoolean(key, true)?.apply()
                                pref.isChecked = true
                                ShizukuSettings.syncAllPlusFeaturesToServer()
                            }
                            .setNegativeButton(R.string.action_cancel, null)
                            .show()
                        return@setOnPreferenceChangeListener false
                    } else {
                        preferenceManager.sharedPreferences?.edit()?.putBoolean(key, newValue)?.apply()
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
        suPathPref?.setOnPreferenceChangeListener { _, _ ->
            ShizukuSettings.syncAllPlusFeaturesToServer()
            true
        }
        suPathPref?.setOnPreferenceClickListener {
            val presets = arrayOf(
                context.getString(R.string.root_su_path_default),
                context.getString(R.string.root_su_path_aosp),
                context.getString(R.string.root_su_path_supersu),
                context.getString(R.string.root_su_path_magisk),
                context.getString(R.string.root_su_path_kernelsu),
                context.getString(R.string.root_su_path_apatch),
                context.getString(R.string.root_su_path_custom)
            )
            val presetValues = arrayOf(
                "",
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/data/adb/ksu/bin/su",
                "/data/adb/ap/bin/su",
                "custom"
            )

            com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle(R.string.root_select_su_path_title)
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
                        Toast.makeText(
                            context,
                            context.getString(R.string.root_su_path_preset_applied, presets[which]),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .show()
            true // Intercept click to show presets dialog first
        }
    }

    private fun isBootloaderUnlocked(): Boolean {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", "ro.boot.flash.locked"))
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            val locked = reader.readLine()
            if (locked == "0") return true

            val process2 = Runtime.getRuntime().exec(arrayOf("getprop", "ro.boot.verifiedbootstate"))
            val reader2 = java.io.BufferedReader(java.io.InputStreamReader(process2.inputStream))
            val state = reader2.readLine()
            if (state == "orange") return true
        } catch (e: Exception) {
            // Ignore
        }
        return false
    }
}
