package af.shizuku.manager.settings

import af.shizuku.manager.ShizukuSettings
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.TwoStatePreference

/**
 * Keeps switches that can't help most people out of the way: a row is shown when Developer
 * options are unlocked, or when its switch is already on. A switch that is on is never hidden,
 * so whoever turned it on can still turn it off.
 */
internal fun PreferenceFragmentCompat.hideUnlessUsed(keys: List<String>) {
    val unlocked = ShizukuSettings.isDevOptionsUnlocked()
    for (key in keys) {
        val pref = findPreference<Preference>(key) ?: continue
        val show = unlocked || (pref as? TwoStatePreference)?.isChecked == true
        val parent = pref.parent as? CollapsiblePreferenceCategory
        if (parent != null) parent.setChildAvailable(key, show) else pref.isVisible = show
    }
}
