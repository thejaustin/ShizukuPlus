package af.shizuku.manager.settings

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.Keys.*
import af.shizuku.manager.utils.SettingsBackupManager

class DeveloperOptionsFragment : BaseSettingsFragment() {

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            val ctx = context ?: return@launch
            try {
                val json = SettingsBackupManager.export(ctx)
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                launch(Dispatchers.Main) {
                    Toast.makeText(ctx, R.string.settings_export_success, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    Toast.makeText(ctx, R.string.settings_export_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            val ctx = context ?: return@launch
            try {
                val json = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                val ok = SettingsBackupManager.import(ctx, json)
                launch(Dispatchers.Main) {
                    if (ok) {
                        Toast.makeText(ctx, R.string.settings_import_success, Toast.LENGTH_LONG).show()
                        ShizukuSettings.syncAllPlusFeaturesToServer()
                    } else {
                        Toast.makeText(ctx, R.string.settings_import_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    Toast.makeText(ctx, R.string.settings_import_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_developer_options, rootKey)

        // Vector / AVF Manager
        findPreference<TwoStatePreference>(KEY_VECTOR_ENABLED)?.apply {
            isChecked = ShizukuSettings.isVectorEnabled()
            setOnPreferenceChangeListener { _, v ->
                ShizukuSettings.setVectorEnabled(v as Boolean)
                ShizukuSettings.syncAllPlusFeaturesToServer()
                true
            }
        }

        // Experimental Root Compatibility
        findPreference<TwoStatePreference>(KEY_EXPERIMENTAL_ROOT_COMPAT)?.apply {
            isChecked = ShizukuSettings.isExperimentalRootCompatEnabled()
            setOnPreferenceChangeListener { _, v ->
                ShizukuSettings.setExperimentalRootCompatEnabled(v as Boolean)
                ShizukuSettings.syncAllPlusFeaturesToServer()
                true
            }
        }

        // Device Identity Spoofing
        findPreference<TwoStatePreference>(KEY_SPOOF_DEVICE_ENABLED)?.apply {
            isChecked = ShizukuSettings.isSpoofDeviceEnabled()
            setOnPreferenceChangeListener { _, v ->
                ShizukuSettings.setSpoofDeviceEnabled(v as Boolean)
                ShizukuSettings.syncAllPlusFeaturesToServer()
                true
            }
        }

        // Spoof Target
        findPreference<rikka.preference.SimpleMenuPreference>(KEY_SPOOF_TARGET)?.setOnPreferenceChangeListener { _, v ->
            ShizukuSettings.setSpoofTarget(if (v == "auto") "auto" else v as String)
            ShizukuSettings.syncAllPlusFeaturesToServer()
            true
        }

        // Export Settings
        findPreference<Preference>("export_settings")?.setOnPreferenceClickListener {
            exportLauncher.launch("shizuku_plus_settings.json")
            true
        }

        // Import Settings
        findPreference<Preference>("import_settings")?.setOnPreferenceClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain"))
            true
        }
    }
}
