package moe.shizuku.manager.settings

import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings.Keys.*
import moe.shizuku.manager.utils.CustomTabsHelper
import moe.shizuku.manager.utils.EnvironmentUtils

class AdvancedSettingsFragment : BaseSettingsFragment() {

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_advanced, rootKey)
        val context = requireContext()

        findPreference<Preference>("service_doctor")?.setOnPreferenceClickListener {
            startActivity(Intent(context, ServiceDoctorActivity::class.java))
            true
        }

        findPreference<Preference>("activity_log")?.setOnPreferenceClickListener {
            startActivity(Intent(context, ActivityLogActivity::class.java))
            true
        }

        findPreference<androidx.preference.TwoStatePreference>(KEY_LEGACY_PAIRING)?.apply {
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
    }
}
