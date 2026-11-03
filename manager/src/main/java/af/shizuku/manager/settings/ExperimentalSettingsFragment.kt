package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import android.os.Bundle
import androidx.preference.TwoStatePreference

/** Experimental features anyone can try - the low-risk counterpart to Developer options. */
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

        // Floating navigation bar: its options only matter while it's on - hide, don't grey out.
        val navToggle = findPreference<TwoStatePreference>(af.shizuku.manager.widget.FloatingNavBar.KEY_ENABLED)
        val navChildren =
            listOf(
                af.shizuku.manager.widget.FloatingNavBar.KEY_STYLE,
                af.shizuku.manager.widget.FloatingNavBar.KEY_TABS,
                ShizukuSettings.Keys.KEY_FLOATING_NAV_LABEL_MODE,
                ShizukuSettings.Keys.KEY_FLOATING_NAV_AUTOHIDE,
            ).mapNotNull { findPreference<androidx.preference.Preference>(it) }

        fun syncNavChildren(on: Boolean) = navChildren.forEach { it.setAvailable(on) }
        syncNavChildren(navToggle?.isChecked == true)
        navToggle?.setOnPreferenceChangeListener { _, v ->
            syncNavChildren((v as? Boolean) == true)
            true
        }
        findPreference<androidx.preference.MultiSelectListPreference>(af.shizuku.manager.widget.FloatingNavBar.KEY_TABS)?.apply {
            fun describe(values: Set<String>) {
                val names =
                    entryValues
                        .zip(entries)
                        .filter { (v, _) -> v.toString() in values }
                        .joinToString(", ") { it.second }
                summary = getString(R.string.settings_floating_nav_tabs_summary, names)
            }
            describe(values)
            setOnPreferenceChangeListener { _, v ->
                @Suppress("UNCHECKED_CAST")
                val tabs = v as Set<String>
                // M3: a navigation bar has at least 3 destinations (Home and Settings always show).
                if (tabs.isEmpty()) return@setOnPreferenceChangeListener false
                describe(tabs)
                true
            }
        }

        // Device control home card: a Plus feature, so the server needs the new value. The change
        // listener runs before the switch persists it - sync on the next frame, once it has.
        findPreference<TwoStatePreference>(ShizukuSettings.Keys.KEY_DEVICE_CONTROL_HOME_ENABLED)?.setOnPreferenceChangeListener { _, _ ->
            view?.post { ShizukuSettings.syncAllPlusFeaturesToServer() }
            true
        }
    }
}
