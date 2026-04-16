package af.shizuku.manager.settings

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.Keys.*
import rikka.shizuku.Shizuku
import af.shizuku.server.IShizukuService

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ShizukuPlusSettingsFragment : BaseSettingsFragment() {

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
        dhizukuPref.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                maybePromptRestart(KEY_DHIZUKU_MODE, newValue) {
                    ShizukuSettings.setDhizukuModeEnabled(newValue)
                    dhizukuPref.isChecked = newValue
                }
            }
            false
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
            "window_manager_plus_enabled" to "window_manager_plus",
            "overlay_manager_plus_enabled" to "overlay_manager_plus",
            "network_governor_plus_enabled" to "network_governor_plus",
            "activity_manager_plus_enabled" to "activity_manager_plus",
            "root_adaway_bridge_enabled" to "root_adaway_bridge",
            "root_magisk_mocking_enabled" to "root_magisk_mocking",
            "root_auto_grant_enabled" to "root_auto_grant",
            "root_file_interceptor_enabled" to "root_file_interceptor",
            "root_busybox_mocking_enabled" to "root_busybox_mocking",
            "vector_enabled" to "vector",
            "experimental_root_compat" to "experimental_root",
            "spoof_device_enabled" to "spoof_device"
        )
        val experimentalKeys = setOf(
            "avf_manager_enabled",
            "ai_core_plus_enabled",
            "vector_enabled",
            "experimental_root_compat",
            "spoof_device_enabled"
        )

        plusKeys.forEach { (prefKey, featureName) ->
            findPreference<TwoStatePreference>(prefKey)?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as? Boolean ?: false
                if (enabled && experimentalKeys.contains(prefKey)) {
                    showExperimentalWarning(prefKey) {
                        preferenceManager.sharedPreferences?.edit()?.putBoolean(prefKey, true)?.apply()
                        ShizukuSettings.syncAllPlusFeaturesToServer()
                        updatePlusFeatureDependency(prefKey, true)
                    }
                    false // Handle manually after dialog
                } else {
                    preferenceManager.sharedPreferences?.edit()?.putBoolean(prefKey, enabled)?.apply()
                    ShizukuSettings.syncAllPlusFeaturesToServer()
                    updatePlusFeatureDependency(prefKey, enabled)
                    true
                }
            }
        }

        findPreference<Preference>("spoof_target")?.setOnPreferenceChangeListener { _, newValue ->
            preferenceManager.sharedPreferences?.edit()?.putString("spoof_target", newValue as String)?.apply()
            ShizukuSettings.syncAllPlusFeaturesToServer()
            true
        }

        // Initialize all preference dependencies
        updateAllPlusFeatureDependencies()
        
        // Check for integrated apps and update summaries
        checkAppIntegrations()
    }

    private fun showGeneralHelpDialog() {
        val context = context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_shizuku_plus_features)
            .setMessage(R.string.help_general_plus_summary)
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
                "org.adaway" to "AdAway",
                "dev.ukanth.ufirewall" to "AFWall+"
            ),
            "storage_proxy_enabled" to listOf(
                "com.machiav3lli.neo_backup" to "Neo Backup",
                "eu.darken.sdm" to "SD Maid",
                "eu.darken.sdmse" to "SD Maid SE"
            ),
            "root_adaway_bridge_enabled" to listOf(
                "org.adaway" to "AdAway"
            ),
            "root_magisk_mocking_enabled" to listOf(
                "com.topjohnwu.magisk" to "Magisk Manager"
            )
        )

        val pm = (context ?: return).packageManager
        integrations.forEach { (prefKey, apps) ->
            val foundApp = apps.find { (pkg, _) ->
                try {
                    pm.getPackageInfo(pkg, 0)
                    true
                } catch (e: Exception) {
                    false
                }
            }

            if (foundApp != null) {
                findPreference<PlusFeaturePreference>(prefKey)?.apply {
                    setIntegration(foundApp.first, foundApp.second)
                    val originalSummary = summary
                    summary = getString(R.string.settings_plus_app_found, foundApp.second) + "\n\n" + originalSummary
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
        updatePreferenceDependency("window_manager_plus_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("network_governor_plus_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("activity_manager_plus_enabled", customApiEnabled, hideDisabled)
        
        // Root Compat modules
        updatePreferenceDependency("root_adaway_bridge_enabled", customApiEnabled, hideDisabled)
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
        when (prefKey) {
            "window_manager_plus_enabled" -> {
                // overlay_manager_plus_enabled depends on window_manager_plus_enabled
                updatePreferenceDependency("overlay_manager_plus_enabled", newValue && ShizukuSettings.isCustomApiEnabled(), hideDisabled)
            }
        }
    }
}
