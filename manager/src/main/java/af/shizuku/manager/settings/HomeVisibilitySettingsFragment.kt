package af.shizuku.manager.settings

import android.os.Bundle
import android.widget.Toast
import androidx.annotation.Keep
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import af.shizuku.manager.R
import af.shizuku.manager.ktx.loge
import af.shizuku.manager.utils.AppContextManager

@Keep
class HomeVisibilitySettingsFragment : BaseSettingsFragment() {

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_home_visibility, rootKey)
        val context = requireContext()

        findPreference<Preference>("update_app_database")?.setOnPreferenceClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val url = java.net.URL("https://raw.githubusercontent.com/thejaustin/ShizukuPlus/master/database/apps.json")
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    val content = try {
                        connection.requestMethod = "GET"
                        connection.connectTimeout = 10_000
                        connection.readTimeout = 10_000
                        connection.inputStream.use { it.bufferedReader().readText() }
                    } finally {
                        connection.disconnect()
                    }
                    withContext(Dispatchers.Main) {
                        AppContextManager.updateDatabase(content)
                        Toast.makeText(context, R.string.settings_update_app_database_success, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    loge("update app database failed", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.settings_update_app_database_error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            true
        }
    }
}
