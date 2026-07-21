package af.shizuku.manager.settings

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings

class AppManagementSettingsFragment : BaseSettingsFragment() {

    companion object {
        private const val VIRUSTOTAL_KEY_URL = "https://www.virustotal.com/gui/my-apikey"
        private const val PITHUS_KEY_URL = "https://beta.pithus.org"
    }

    override fun getTitle(): CharSequence? = "App Interactions"

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_app_management, rootKey)

        // Swipe gestures — auto-persist via SharedPrefs; notify server of change
        findPreference<rikka.preference.SimpleMenuPreference>("swipe_right_action")
            ?.setOnPreferenceChangeListener { _, _ -> ShizukuSettings.syncAllPlusFeaturesToServer(); true }
        findPreference<rikka.preference.SimpleMenuPreference>("swipe_left_action")
            ?.setOnPreferenceChangeListener { _, _ -> ShizukuSettings.syncAllPlusFeaturesToServer(); true }

        // Long-press action toggles — auto-persist via SharedPrefs; notify server
        listOf("lp_open_app", "lp_app_info", "lp_toggle_permission", "lp_hide_from_list").forEach { key ->
            findPreference<TwoStatePreference>(key)
                ?.setOnPreferenceChangeListener { _, _ -> ShizukuSettings.syncAllPlusFeaturesToServer(); true }
        }

        // Local signature matching and F-Droid verification — pure local, auto-persist is sufficient

        // VirusTotal — requires API key before enabling
        findPreference<TwoStatePreference>("verify_apk_virustotal")?.setOnPreferenceChangeListener { pref, newValue ->
            val enabling = newValue as Boolean
            if (enabling && ShizukuSettings.getVirusTotalApiKey().isBlank()) {
                showApiKeyDialog(
                    title = getString(R.string.verify_virustotal_key_title),
                    hint = getString(R.string.verify_api_key_hint),
                    currentKey = "",
                    getKeyUrl = VIRUSTOTAL_KEY_URL,
                    onSave = { key ->
                        if (key.isBlank()) {
                            (pref as TwoStatePreference).isChecked = false
                            Toast.makeText(context, R.string.verify_virustotal_key_required, Toast.LENGTH_SHORT).show()
                        } else {
                            ShizukuSettings.setVirusTotalApiKey(key)
                            (pref as TwoStatePreference).isChecked = true
                        }
                    },
                    onCancel = { (pref as TwoStatePreference).isChecked = false }
                )
                false
            } else {
                true
            }
        }

        // VirusTotal API key management preference (shown when VT is enabled)
        updateApiKeyManageSummary("virustotal_api_key_manage", ShizukuSettings.getVirusTotalApiKey())
        findPreference<Preference>("virustotal_api_key_manage")?.setOnPreferenceClickListener {
            showApiKeyDialog(
                title = getString(R.string.verify_virustotal_key_title),
                hint = getString(R.string.verify_api_key_hint),
                currentKey = ShizukuSettings.getVirusTotalApiKey(),
                getKeyUrl = VIRUSTOTAL_KEY_URL,
                onSave = { key ->
                    ShizukuSettings.setVirusTotalApiKey(key)
                    updateApiKeyManageSummary("virustotal_api_key_manage", key)
                },
                onCancel = {}
            )
            true
        }

        // Pithus — free public API; toggle works without a key
        findPreference<TwoStatePreference>("verify_apk_pithus")
            ?.setOnPreferenceChangeListener { _, _ -> true }

        // Pithus optional API key management
        updateApiKeyManageSummary("pithus_api_key_manage", ShizukuSettings.getPithusApiKey())
        findPreference<Preference>("pithus_api_key_manage")?.setOnPreferenceClickListener {
            showApiKeyDialog(
                title = getString(R.string.verify_pithus_key_title),
                hint = getString(R.string.verify_api_key_hint),
                currentKey = ShizukuSettings.getPithusApiKey(),
                getKeyUrl = PITHUS_KEY_URL,
                onSave = { key ->
                    ShizukuSettings.setPithusApiKey(key)
                    updateApiKeyManageSummary("pithus_api_key_manage", key)
                },
                onCancel = {}
            )
            true
        }
    }

    private fun updateApiKeyManageSummary(key: String, apiKey: String) {
        val summary = if (apiKey.isNotBlank()) getString(R.string.verify_api_key_configured)
                      else getString(R.string.verify_api_key_hint)
        findPreference<Preference>(key)?.summary = summary
    }

    private fun showApiKeyDialog(
        title: String,
        hint: String,
        currentKey: String,
        getKeyUrl: String? = null,
        onSave: (String) -> Unit,
        onCancel: () -> Unit
    ) {
        val ctx = context ?: return
        val dp16 = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            this.hint = hint
            if (currentKey.isNotBlank()) setText(currentKey)
        }
        val container = FrameLayout(ctx).apply {
            setPadding(dp16, dp16 / 2, dp16, 0)
            addView(input)
        }
        val builder = MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ -> onSave(input.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
        if (getKeyUrl != null) {
            builder.setNeutralButton(R.string.verify_get_api_key) { _, _ ->
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(getKeyUrl)))
            }
        }
        builder.show()
    }
}
