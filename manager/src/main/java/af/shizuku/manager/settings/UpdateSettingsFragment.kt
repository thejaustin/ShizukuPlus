package af.shizuku.manager.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import timber.log.Timber
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import af.shizuku.manager.BuildConfig
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.update.UpdateChecker
import af.shizuku.manager.update.UpdateManager
import org.koin.android.ext.android.inject

class UpdateSettingsFragment : BaseSettingsFragment() {

    companion object {
        private const val TAG = "UpdateSettingsFragment"
        private const val KEY_AUTO_UPDATE = "auto_update_enabled"
        private const val KEY_AUTO_INSTALL = "auto_install_enabled"
        private const val KEY_UPDATE_CHANNEL = "update_channel"
        private const val KEY_CHECK_FOR_UPDATE = "check_for_update"
        private const val KEY_CURRENT_VERSION = "current_version"
        private const val KEY_LAST_CHECK = "last_check_time"
        private const val RELEASES_URL = "https://github.com/thejaustin/ShizukuPlus/releases"
    }

    private val updateManager: UpdateManager by inject()

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_update, rootKey)

        setupAutoUpdatePreference()
        setupAutoInstallPreference()
        setupChannelPreference()
        setupCheckForUpdatePreference()
        setupCurrentVersionPreference()
        updateLastCheckSummary()
    }

    private fun setupAutoUpdatePreference() {
        val pref = findPreference<TwoStatePreference>(KEY_AUTO_UPDATE) ?: return
        pref.isChecked = ShizukuSettings.isAutoUpdateEnabled()
        pref.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            ShizukuSettings.setAutoUpdateEnabled(enabled)
            pref.isChecked = enabled
            if (!enabled) {
                findPreference<TwoStatePreference>(KEY_AUTO_INSTALL)?.isChecked = false
                ShizukuSettings.setAutoInstallEnabled(false)
            }
            false
        }
    }

    private fun setupAutoInstallPreference() {
        val pref = findPreference<TwoStatePreference>(KEY_AUTO_INSTALL) ?: return
        pref.isChecked = ShizukuSettings.isAutoInstallEnabled()
        pref.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            if (enabled && !updateManager.canRequestPackageInstalls()) {
                showPermissionRequiredDialog()
                return@setOnPreferenceChangeListener false
            }
            ShizukuSettings.setAutoInstallEnabled(enabled)
            pref.isChecked = enabled
            false
        }
    }

    private fun setupChannelPreference() {
        val pref = findPreference<ListPreference>(KEY_UPDATE_CHANNEL) ?: return
        pref.value = ShizukuSettings.getUpdateChannel()
        pref.setOnPreferenceChangeListener { _, newValue ->
            val channel = newValue as String
            if (channel == "dev") {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.update_channel_dev_warning_title)
                    .setMessage(R.string.update_channel_dev_warning_message)
                    .setPositiveButton(R.string.update_channel_dev) { _, _ ->
                        ShizukuSettings.setUpdateChannel("dev")
                        pref.value = "dev"
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                false
            } else {
                ShizukuSettings.setUpdateChannel("stable")
                true
            }
        }
    }

    private fun setupCheckForUpdatePreference() {
        findPreference<Preference>(KEY_CHECK_FOR_UPDATE)?.setOnPreferenceClickListener {
            checkForUpdate()
            true
        }
    }

    private fun setupCurrentVersionPreference() {
        findPreference<Preference>(KEY_CURRENT_VERSION)?.summary = BuildConfig.VERSION_NAME
    }

    private fun updateLastCheckSummary() {
        val pref = findPreference<Preference>(KEY_LAST_CHECK) ?: return
        val lastCheck = ShizukuSettings.getLastUpdateCheckTime()
        pref.summary = when {
            ShizukuSettings.wasLastUpdateCheckFailed() && lastCheck > 0 -> {
                val date = UpdateChecker.formatPublishedDate(
                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                        .format(java.util.Date(lastCheck))
                )
                "$date · ${getString(R.string.update_last_check_failed)}"
            }
            ShizukuSettings.wasLastUpdateCheckFailed() ->
                getString(R.string.update_last_check_failed)
            lastCheck > 0 ->
                UpdateChecker.formatPublishedDate(
                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                        .format(java.util.Date(lastCheck))
                )
            else ->
                getString(R.string.update_never_checked)
        }
    }

    private fun checkForUpdate() {
        val context = context ?: return
        android.widget.Toast.makeText(context, R.string.update_checking, android.widget.Toast.LENGTH_SHORT).show()
        val channel = ShizukuSettings.getUpdateChannel()
        lifecycleScope.launch {
            try {
                when (val result = UpdateChecker.checkForUpdate(channel)) {
                    is UpdateChecker.CheckResult.UpdateAvailable -> {
                        if (isAdded) {
                            ShizukuSettings.setLastUpdateCheckTime(System.currentTimeMillis())
                            ShizukuSettings.setLastUpdateCheckFailed(false)
                            updateLastCheckSummary()
                            showUpdateAvailableDialog(result.info)
                        }
                    }
                    is UpdateChecker.CheckResult.UpToDate -> {
                        if (isAdded) {
                            ShizukuSettings.setLastUpdateCheckTime(System.currentTimeMillis())
                            ShizukuSettings.setLastUpdateCheckFailed(false)
                            updateLastCheckSummary()
                            showUpToDateDialog()
                        }
                    }
                    is UpdateChecker.CheckResult.NetworkError -> {
                        if (isAdded) {
                            ShizukuSettings.setLastUpdateCheckFailed(true)
                            updateLastCheckSummary()
                            showErrorDialog()
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Unexpected error checking for update")
                if (isAdded) {
                    ShizukuSettings.setLastUpdateCheckFailed(true)
                    updateLastCheckSummary()
                    showErrorDialog()
                }
            }
        }
    }

    private fun showUpdateAvailableDialog(info: UpdateChecker.UpdateInfo) {
        val context = context ?: return
        val devBadge = if (info.isPrerelease) " ⚠ Dev" else ""
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.update_available_title) + devBadge)
            .setNegativeButton(R.string.update_later, null)
            .setNeutralButton(R.string.update_release_notes) { _, _ ->
                openReleasesPage()
            }

        if (info.requiresManualDownload) {
            builder
                .setMessage(getString(R.string.update_available_manual_message, info.versionName))
                .setPositiveButton(R.string.update_view_on_github) { _, _ -> openReleasesPage() }
        } else {
            builder
                .setMessage(getString(R.string.update_available_message, info.versionName))
                .setPositiveButton(R.string.update_download) { _, _ ->
                    updateManager.downloadUpdate(info.downloadUrl, info.versionName)
                }
        }

        builder.show()
    }

    private fun showUpToDateDialog() {
        val context = context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.update_up_to_date_title)
            .setMessage(getString(R.string.update_up_to_date_message, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showErrorDialog() {
        val context = context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.update_error_title)
            .setMessage(R.string.update_error_message)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.update_view_on_github) { _, _ -> openReleasesPage() }
            .show()
    }

    private fun showPermissionRequiredDialog() {
        val context = context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.update_permission_required_title)
            .setMessage(R.string.update_permission_required_message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun openReleasesPage() {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun triggerUpdateCheck() = checkForUpdate()
}
