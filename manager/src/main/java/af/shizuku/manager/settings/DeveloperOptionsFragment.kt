package af.shizuku.manager.settings

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.Keys.*
import af.shizuku.manager.utils.CrashHandler
import af.shizuku.manager.utils.CrashReporter
import af.shizuku.manager.utils.CustomTabsHelper

class DeveloperOptionsFragment : BaseSettingsFragment() {

    override fun getTitle(): CharSequence? = getString(R.string.settings_developer_options)

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

        // Manual Crash Report
        findPreference<Preference>("manual_report")?.apply {
            val ctx = context ?: return@apply
            val hasLastCrash = CrashHandler.getLastCrashReport(ctx) != null
            if (hasLastCrash) {
                setTitle(R.string.manual_report_last_crash_title)
                setSummary(R.string.manual_report_last_crash_summary)
            }

            setOnPreferenceClickListener {
                showDialog(
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle(if (hasLastCrash) R.string.manual_report_last_crash_title else R.string.manual_report_title)
                        .setMessage(R.string.manual_report_summary)
                        .setPositiveButton(R.string.manual_report_button_github) { _, _ ->
                            val report = CrashReporter.generateReport(ctx)
                            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                                ctx.getString(R.string.manual_report_clipboard_label), report))

                            Toast.makeText(ctx, R.string.manual_report_toast_copied, Toast.LENGTH_LONG).show()

                            val url = CrashReporter.getGitHubReportUrl(ctx)
                            CustomTabsHelper.launchUrlOrCopy(ctx, url)

                            if (hasLastCrash) {
                                CrashHandler.clearLastCrash(ctx)
                            }
                        }
                        .setNeutralButton(R.string.manual_report_copied_dialog_share) { _, _ ->
                            CrashReporter.shareAsFile(ctx)
                            if (hasLastCrash) {
                                CrashHandler.clearLastCrash(ctx)
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                )
                true
            }
        }
    }
}
