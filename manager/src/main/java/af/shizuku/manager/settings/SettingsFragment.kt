package af.shizuku.manager.settings

import android.os.Bundle
import af.shizuku.manager.R

class SettingsFragment : BaseSettingsFragment() {

    override fun getTitle(): CharSequence? = getString(R.string.settings_title)

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_main, rootKey)
    }
}
