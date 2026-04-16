package af.shizuku.manager.settings

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.service.AdbProxyService
import af.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/**
 * Root Integration Settings
 * 
 * Provides SU Bridge and ADB proxy features to integrate root access
 * with apps that don't natively support Shizuku.
 */
class RootIntegrationSettingsFragment : BaseSettingsFragment() {

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_root_integration, rootKey)
        val context = requireContext()

        // Sync current su_bridge state to server on fragment open
        ShizukuSettings.syncAllPlusFeaturesToServer()

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

        findPreference<Preference>("root_compatibility_hub")?.setOnPreferenceClickListener {
            startActivity(Intent(context, RootCompatibilityActivity::class.java))
            true
        }
    }
}
