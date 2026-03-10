package moe.shizuku.manager.settings

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.service.AdbProxyService
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

class LegacyCompatSettingsFragment : BaseSettingsFragment() {

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_legacy_compat, rootKey)
        val context = requireContext()

        // Sync current fake_su state to server on fragment open
        if (Shizuku.pingBinder()) {
            notifyServerFeatureUpdate("fake_su", ShizukuSettings.isFakeSuEnabled())
        }

        findPreference<TwoStatePreference>("adb_proxy_enabled")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                val intent = Intent(context, AdbProxyService::class.java)
                if (newValue) context.startService(intent) else context.stopService(intent)
            }
            true
        }

        findPreference<TwoStatePreference>("fake_su_enabled")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                notifyServerFeatureUpdate("fake_su", newValue)
            }
            true
        }

        findPreference<Preference>("root_compatibility_hub")?.setOnPreferenceClickListener {
            startActivity(Intent(context, RootCompatibilityActivity::class.java))
            true
        }
    }

    private fun notifyServerFeatureUpdate(key: String, enabled: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val binder = Shizuku.getBinder() ?: return@launch
                IShizukuService.Stub.asInterface(binder).updatePlusFeatureEnabled(key, enabled)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
