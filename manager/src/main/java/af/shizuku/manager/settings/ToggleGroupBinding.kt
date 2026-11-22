package af.shizuku.manager.settings

import af.shizuku.manager.ShizukuSettings
import android.content.SharedPreferences
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.preference.TwoStatePreference

/**
 * Expressive controls preview: shows several switch rows as one connected multi-select button group.
 *
 * Taps drive the real rows (hidden, not removed), so each row's change listener, veto, dependents,
 * backup and sync rules all stay exactly as they are; only the presentation changes. Without the
 * preview, or if any row is missing, the group stays hidden and the plain rows show.
 *
 * @param labels one or two words per row, in the same order as [rowKeys].
 */
fun BaseSettingsFragment.bindToggleGroup(
    groupKey: String,
    categoryKey: String,
    rowKeys: List<String>,
    labels: List<String>,
) {
    val group = findPreference<ConnectedToggleGroupPreference>(groupKey) ?: return
    // The category sets every child's visibility when it expands, so hide through it.
    val category = findPreference<CollapsiblePreferenceCategory>(categoryKey)
    val rows = rowKeys.mapNotNull { findPreference<TwoStatePreference>(it) }
    val preview = ShizukuSettings.isExpressiveControlsPreviewEnabled() && rows.size == rowKeys.size && category != null
    category?.setChildAvailable(groupKey, preview)
    if (!preview) {
        group.isVisible = false
        return
    }
    group.labels = labels
    group.isChecked = { rows[it].isChecked }
    group.onToggle = { index, checked ->
        val row = rows[index]
        if (row.callChangeListener(checked)) row.isChecked = checked
        group.refresh()
    }
    rows.forEach { category?.setChildAvailable(it.key, false) }
    val keys = rowKeys.toSet()
    val prefs = preferenceManager.sharedPreferences ?: return
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key in keys) group.refresh() }
    prefs.registerOnSharedPreferenceChangeListener(listener)
    lifecycle.addObserver(
        object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                prefs.unregisterOnSharedPreferenceChangeListener(listener)
            }
        },
    )
}
