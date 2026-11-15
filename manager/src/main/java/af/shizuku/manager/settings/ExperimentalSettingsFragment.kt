package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import android.os.Bundle
import androidx.preference.TwoStatePreference

/**
 * Experimental features: the one home for features still in development. The "Developer previews"
 * category only shows once Developer options are unlocked.
 */
class ExperimentalSettingsFragment : BaseSettingsFragment() {
    override fun getTitle(): CharSequence? = getString(R.string.settings_experimental_title)

    override fun onCreateSettingsPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.settings_experimental, rootKey)

        // Launcher icon / app name / Icon Studio. Turning it off puts the standard icon and name
        // back, so a camouflaged launcher entry can't outlive the options that set it.
        findPreference<TwoStatePreference>(ShizukuSettings.Keys.KEY_EXPERIMENTAL_APP_IDENTITY)?.apply {
            isChecked = ShizukuSettings.isExperimentalAppIdentityEnabled()
            setOnPreferenceChangeListener { _, v ->
                val enabled = v as? Boolean ?: return@setOnPreferenceChangeListener false
                ShizukuSettings.setExperimentalAppIdentityEnabled(enabled)
                if (!enabled) {
                    ShizukuSettings.setAppIconAlias("default")
                    ShizukuSettings.setCustomAppName("")
                    af.shizuku.manager.utils.AppAliasManager
                        .applyAlias(requireContext(), "default")
                }
                true
            }
        }

        // Full colour schemes re-themes everything: recreate once the new value is saved.
        findPreference<TwoStatePreference>(ShizukuSettings.Keys.KEY_EXPERIMENTAL_COLOR_SCHEMES)?.setOnPreferenceChangeListener { _, _ ->
            view?.post { (activity as? af.shizuku.core.ui.AppActivity)?.recreateWithoutTransition() }
            true
        }

        // Switch row design: recreate to apply custom switch thumb / icon design immediately.
        findPreference<androidx.preference.Preference>("switch_row_design")?.setOnPreferenceChangeListener { _, _ ->
            view?.post { (activity as? af.shizuku.core.ui.AppActivity)?.recreateWithoutTransition() }
            true
        }

        // Developer previews: the riskier in-development features, kept behind the Developer
        // options unlock (About > tap the version 7 times), as before they moved here.
        findPreference<androidx.preference.Preference>("category_experimental")?.isVisible =
            ShizukuSettings.isDevOptionsUnlocked()

        // Validated by setSpoofTarget; not a Plus row, so sync it here.
        findPreference<rikka.preference.SimpleMenuPreference>(ShizukuSettings.Keys.KEY_SPOOF_TARGET)?.setOnPreferenceChangeListener { _, v ->
            val target = v as? String ?: return@setOnPreferenceChangeListener false
            ShizukuSettings.setSpoofTarget(target)
            ShizukuSettings.syncAllPlusFeaturesToServer()
            true
        }
    }
}
