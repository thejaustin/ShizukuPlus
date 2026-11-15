package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * App safety check rows (Startup & behavior): the local check, the VirusTotal and Pithus lookups and
 * their API key rows. The online checks do nothing without a key, so they ask for one before they
 * turn on, and clearing a key turns its check off. RequestPermissionActivity reads the same keys.
 */
object VerifiedInstallPrefs {
    const val VIRUSTOTAL_KEY_URL = "https://www.virustotal.com/gui/my-apikey"
    const val PITHUS_KEY_URL = "https://beta.pithus.org"

    private const val VT_SWITCH = "verify_apk_virustotal"
    private const val VT_MANAGE = "virustotal_api_key_manage"
    private const val PITHUS_SWITCH = "verify_apk_pithus"
    private const val PITHUS_MANAGE = "pithus_api_key_manage"

    /** Prefs whose change should refresh the key rows (see [refresh]). */
    val WATCHED_KEYS =
        setOf(VT_SWITCH, PITHUS_SWITCH, ShizukuSettings.Keys.KEY_VIRUSTOTAL_API_KEY, ShizukuSettings.Keys.KEY_PITHUS_API_KEY)

    fun attach(fragment: BaseSettingsFragment) {
        wire(fragment, VT_SWITCH, VT_MANAGE, ShizukuSettings::getVirusTotalApiKey, ::promptVirusTotalKey)
        wire(fragment, PITHUS_SWITCH, PITHUS_MANAGE, ShizukuSettings::getPithusApiKey, ::promptPithusKey)
        refresh(fragment)
    }

    private fun wire(
        fragment: BaseSettingsFragment,
        switchKey: String,
        manageKey: String,
        apiKey: () -> String,
        prompt: (Context, (String) -> Unit, () -> Unit, (() -> Unit)?) -> Unit,
    ) {
        val switch = fragment.findPreference<TwoStatePreference>(switchKey)
        switch?.setOnPreferenceChangeListener { _, newValue ->
            val enabling = newValue as? Boolean ?: return@setOnPreferenceChangeListener false
            if (enabling && apiKey().isBlank()) {
                prompt(fragment.requireContext(), { switch.isChecked = true }, {}, null)
                false
            } else {
                true
            }
        }
        fragment.findPreference<Preference>(manageKey)?.setOnPreferenceClickListener {
            prompt(fragment.requireContext(), {}, {}) { switch?.isChecked = false }
            true
        }
    }

    /** Key rows: saved, missing while their check is on, or not set. */
    fun refresh(fragment: BaseSettingsFragment) {
        fun summary(
            switchKey: String,
            apiKey: String,
        ) = fragment.getString(
            when {
                apiKey.isNotBlank() -> R.string.verify_api_key_configured
                fragment.findPreference<TwoStatePreference>(switchKey)?.isChecked == true -> R.string.settings_verify_key_missing
                else -> R.string.settings_verify_key_not_set
            },
        )
        fragment.findPreference<Preference>(VT_MANAGE)?.setCustomSummary(summary(VT_SWITCH, ShizukuSettings.getVirusTotalApiKey()))
        fragment.findPreference<Preference>(PITHUS_MANAGE)?.setCustomSummary(summary(PITHUS_SWITCH, ShizukuSettings.getPithusApiKey()))
    }

    /** With [onCleared], a blank entry removes a saved key; otherwise a key is required. */
    fun promptVirusTotalKey(
        context: Context,
        onSaved: (String) -> Unit,
        onCancel: () -> Unit = {},
        onCleared: (() -> Unit)? = null,
    ) = promptKey(
        context,
        context.getString(R.string.verify_virustotal_key_title),
        ShizukuSettings.getVirusTotalApiKey(),
        VIRUSTOTAL_KEY_URL,
        R.string.verify_virustotal_key_required,
        ShizukuSettings::setVirusTotalApiKey,
        onSaved,
        onCancel,
        onCleared,
    )

    fun promptPithusKey(
        context: Context,
        onSaved: (String) -> Unit,
        onCancel: () -> Unit = {},
        onCleared: (() -> Unit)? = null,
    ) = promptKey(
        context,
        context.getString(R.string.settings_verify_pithus_key_row),
        ShizukuSettings.getPithusApiKey(),
        PITHUS_KEY_URL,
        R.string.verify_pithus_key_required,
        ShizukuSettings::setPithusApiKey,
        onSaved,
        onCancel,
        onCleared,
    )

    private fun promptKey(
        context: Context,
        title: String,
        currentKey: String,
        getKeyUrl: String,
        requiredMessage: Int,
        store: (String) -> Unit,
        onSaved: (String) -> Unit,
        onCancel: () -> Unit,
        onCleared: (() -> Unit)?,
    ) {
        showApiKeyDialog(
            context = context,
            title = title,
            hint = context.getString(R.string.verify_api_key_hint),
            currentKey = currentKey,
            getKeyUrl = getKeyUrl,
            onSave = { key ->
                when {
                    key.isNotBlank() -> {
                        store(key)
                        onSaved(key)
                    }
                    onCleared != null && currentKey.isNotBlank() -> {
                        store("")
                        onCleared()
                        Toast.makeText(context, R.string.settings_verify_key_cleared, Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        Toast.makeText(context, requiredMessage, Toast.LENGTH_SHORT).show()
                        onCancel()
                    }
                }
            },
            onCancel = onCancel,
        )
    }

    fun showApiKeyDialog(
        context: Context,
        title: String,
        hint: String,
        currentKey: String,
        getKeyUrl: String? = null,
        onSave: (String) -> Unit,
        onCancel: () -> Unit = {},
    ) {
        val dp16 = (16 * context.resources.displayMetrics.density).toInt()
        val input =
            EditText(context).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                this.hint = hint
                if (currentKey.isNotBlank()) setText(currentKey)
            }
        val container =
            FrameLayout(context).apply {
                setPadding(dp16, dp16 / 2, dp16, 0)
                addView(input)
            }
        val builder =
            MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(container)
                .setPositiveButton(android.R.string.ok) { _, _ -> onSave(input.text.toString().trim()) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
                .setOnCancelListener { onCancel() }
        if (getKeyUrl != null) {
            builder.setNeutralButton(R.string.verify_get_api_key) { _, _ ->
                runCatching {
                    context.startActivity(
                        android.content
                            .Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(getKeyUrl))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
        builder.show()
    }
}
