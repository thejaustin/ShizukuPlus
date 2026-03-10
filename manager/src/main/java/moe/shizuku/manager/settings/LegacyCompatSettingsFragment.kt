package moe.shizuku.manager.settings

import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import moe.shizuku.manager.R
import moe.shizuku.manager.service.AdbProxyService

class LegacyCompatSettingsFragment : BaseSettingsFragment() {

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_legacy_compat, rootKey)
        val context = requireContext()

        findPreference<androidx.preference.TwoStatePreference>("adb_proxy_enabled")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                val intent = Intent(context, AdbProxyService::class.java)
                if (newValue) {
                    context.startService(intent)
                } else {
                    context.stopService(intent)
                }
            }
            true
        }

        findPreference<Preference>("root_compatibility_hub")?.setOnPreferenceClickListener {
            startActivity(Intent(context, RootCompatibilityActivity::class.java))
            true
        }
    }
}
