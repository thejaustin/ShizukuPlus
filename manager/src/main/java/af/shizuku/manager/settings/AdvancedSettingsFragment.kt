package af.shizuku.manager.settings
import af.shizuku.manager.activitylog.ActivityLogActivity

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings.Keys.*
import af.shizuku.manager.utils.CustomTabsHelper
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ktx.setComponentEnabled
import android.widget.Toast
import timber.log.Timber
import af.shizuku.manager.ktx.toHtml
import af.shizuku.manager.BuildConfig
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import af.shizuku.manager.database.AppContextManager
import androidx.preference.TwoStatePreference

class AdvancedSettingsFragment : BaseSettingsFragment() {

    override fun getTitle(): CharSequence? = "Advanced & Diagnostics"

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_advanced, rootKey)
        val context = requireContext()

        findPreference<Preference>("update_app_database")?.setOnPreferenceClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val url = java.net.URL("https://raw.githubusercontent.com/thejaustin/ShizukuPlus/master/database/apps.json")
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    val content = try {
                        connection.instanceFollowRedirects = true
                        connection.requestMethod = "GET"
                        connection.connectTimeout = 10_000
                        connection.readTimeout = 10_000

                        val responseCode = connection.responseCode
                        if (responseCode != java.net.HttpURLConnection.HTTP_OK) {
                            throw java.io.IOException("HTTP $responseCode from GitHub")
                        }

                        connection.inputStream.use { it.bufferedReader().readText() }
                    } finally {
                        connection.disconnect()
                    }
                    withContext(Dispatchers.Main) {
                        AppContextManager.updateDatabase(content)
                        Toast.makeText(context, R.string.settings_update_app_database_success, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Timber.w("update app database failed", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.settings_update_app_database_error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            true
        }

        findPreference<Preference>("service_doctor")?.setOnPreferenceClickListener {
            startActivity(Intent(context, ServiceDoctorActivity::class.java))
            true
        }

        findPreference<Preference>("activity_log")?.setOnPreferenceClickListener {
            startActivity(Intent(context, ActivityLogActivity::class.java))
            true
        }

        findPreference<Preference>("scripting")?.setOnPreferenceClickListener {
            startActivity(Intent(context, af.shizuku.manager.scripting.ScriptingActivity::class.java))
            true
        }

        findPreference<TwoStatePreference>(KEY_LEGACY_PAIRING)?.apply {
            isVisible = !EnvironmentUtils.isTelevision()
        }

        findPreference<Preference>(KEY_HELP)?.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(context, context.getString(R.string.help_url))
            true
        }

        findPreference<Preference>(KEY_REPORT_BUG)?.setOnPreferenceClickListener {
            BugReportDialog().show(parentFragmentManager, "BugReportDialog")
            true
        }

        findPreference<Preference>("reset_adb_keys")?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.settings_reset_adb_keys)
                .setMessage(R.string.settings_reset_adb_keys_summary)
                .setPositiveButton(R.string.settings_reset_adb_keys) { _, _ ->
                    try {
                        ShizukuSettings.getPreferences().edit().remove("adbkey").apply()
                        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
                        keyStore.load(null)
                        keyStore.deleteEntry("_adbkey_encryption_key_")
                        Toast.makeText(context, R.string.settings_reset_adb_keys_success, Toast.LENGTH_SHORT).show()
                        (activity as? af.shizuku.core.ui.AppActivity)?.recreateWithoutTransition()
                    } catch (e: Exception) {
                        Timber.tag("AdvancedSettings").e(e, "Failed to reset ADB keys")
                        Toast.makeText(context, R.string.settings_reset_adb_keys_error, Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        // The manifest's namespace (af.shizuku.manager) differs from the per-flavor applicationId
        // (context.packageName), so ".LauncherAlias" must resolve against the namespace, not the
        // package name, or ComponentName construction throws "Component class ... does not exist"
        // (SHIZUKUPLUS-7R).
        val launcherAlias = ComponentName(context, "af.shizuku.manager.LauncherAlias")
        findPreference<TwoStatePreference>("stealth_mode")?.apply {
            isChecked = ShizukuSettings.isStealthModeEnabled()
            setOnPreferenceChangeListener { pref, newValue ->
                val enable = newValue as Boolean
                if (enable) {
                    MaterialAlertDialogBuilder(context)
                        .setTitle("Enable Stealth Mode?")
                        .setMessage(
                            "The app icon will be removed from your launcher.\n\n" +
                            "You can still open the app from the Shizuku notification. " +
                            "Disable stealth mode via ADB to restore the icon:\n\n" +
                            "adb shell pm enable ${context.packageName}/af.shizuku.manager.LauncherAlias"
                        )
                        .setPositiveButton("Enable") { _, _ ->
                            context.packageManager.setComponentEnabled(launcherAlias, false)
                            ShizukuSettings.setStealthModeEnabled(true)
                            (pref as? TwoStatePreference)?.isChecked = true
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ ->
                            (pref as? TwoStatePreference)?.isChecked = false
                        }
                        .show()
                    false
                } else {
                    context.packageManager.setComponentEnabled(launcherAlias, true)
                    ShizukuSettings.setStealthModeEnabled(false)
                    true
                }
            }
        }
    }
}
