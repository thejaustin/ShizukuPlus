package moe.shizuku.manager.settings

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.TwoStatePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.Keys.*
import rikka.shizuku.Shizuku
import moe.shizuku.server.IShizukuService

class ShizukuPlusSettingsFragment : BaseSettingsFragment() {

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_shizuku_plus, rootKey)

        syncAllFeaturesToServer()

        val dhizukuPref = findPreference<TwoStatePreference>(KEY_DHIZUKU_MODE)!!
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

        val customApiPref = findPreference<TwoStatePreference>(KEY_CUSTOM_API_ENABLED)!!
        customApiPref.isChecked = ShizukuSettings.isCustomApiEnabled()
        customApiPref.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                maybePromptRestart(KEY_CUSTOM_API_ENABLED, newValue) {
                    ShizukuSettings.setCustomApiEnabled(newValue)
                    customApiPref.isChecked = newValue
                    notifyServerFeatureUpdate("custom_api", newValue)
                }
            }
            false
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
            "activity_manager_plus_enabled" to "activity_manager_plus"
        )
        plusKeys.forEach { (prefKey, serverKey) ->
            findPreference<TwoStatePreference>(prefKey)?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) notifyServerFeatureUpdate(serverKey, newValue)
                true
            }
        }
    }

    private fun notifyServerFeatureUpdate(key: String, enabled: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val binder = Shizuku.getBinder() as? android.os.IBinder
                if (binder != null) {
                    IShizukuService.Stub.asInterface(binder).updatePlusFeatureEnabled(key, enabled)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun syncAllFeaturesToServer() {
        if (!Shizuku.pingBinder()) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val binder = Shizuku.getBinder() as? android.os.IBinder ?: return@launch
                val service = IShizukuService.Stub.asInterface(binder)
                service.updatePlusFeatureEnabled("custom_api", ShizukuSettings.isCustomApiEnabled())
                service.updatePlusFeatureEnabled("shell_interceptor", ShizukuSettings.isShellInterceptorEnabled())
                service.updatePlusFeatureEnabled("avf_manager", ShizukuSettings.isAvfManagerEnabled())
                service.updatePlusFeatureEnabled("storage_proxy", ShizukuSettings.isStorageProxyEnabled())
                service.updatePlusFeatureEnabled("continuity_bridge", ShizukuSettings.isContinuityBridgeEnabled())
                service.updatePlusFeatureEnabled("ai_core_plus", ShizukuSettings.isAICorePlusEnabled())
                service.updatePlusFeatureEnabled("window_manager_plus", ShizukuSettings.isWindowManagerPlusEnabled())
                service.updatePlusFeatureEnabled("overlay_manager_plus", ShizukuSettings.isOverlayManagerPlusEnabled())
                service.updatePlusFeatureEnabled("network_governor_plus", ShizukuSettings.isNetworkGovernorPlusEnabled())
                service.updatePlusFeatureEnabled("activity_manager_plus", ShizukuSettings.isActivityManagerPlusEnabled())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
