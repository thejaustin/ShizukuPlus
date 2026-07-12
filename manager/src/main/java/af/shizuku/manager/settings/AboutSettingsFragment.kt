package af.shizuku.manager.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import af.shizuku.manager.BuildConfig
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.CustomTabsHelper
import af.shizuku.manager.update.UpdateChecker
import af.shizuku.manager.update.UpdateManager
import org.koin.android.ext.android.inject
import timber.log.Timber

class AboutSettingsFragment : BaseSettingsFragment() {

    private var versionClickCount = 0
    private val updateManager: UpdateManager by inject()

    companion object {
        private const val TAG = "AboutSettingsFragment"
        private const val KEY_AUTO_UPDATE = "auto_update_enabled"
        private const val KEY_AUTO_INSTALL = "auto_install_enabled"
        private const val KEY_UPDATE_CHANNEL = "update_channel"
        private const val KEY_CHECK_FOR_UPDATE = "check_for_update"
        private const val KEY_LAST_CHECK = "last_check_time"
        private const val RELEASES_URL = "https://github.com/thejaustin/ShizukuPlus/releases"
    }

    override fun getTitle(): CharSequence? = getString(R.string.settings_about)

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_about, rootKey)
        val context = requireContext()

        // Show the developer options entry only when already unlocked.
        findPreference<Preference>("nav_developer_options")?.isVisible = ShizukuSettings.isVectorEnabled()

        // 1. Setup Version with developer mode Easter Egg
        findPreference<Preference>("version")?.apply {
            summary = BuildConfig.VERSION_NAME
            setOnPreferenceClickListener {
                if (ShizukuSettings.isVectorEnabled()) {
                    Toast.makeText(context, R.string.settings_developer_options_revealed, Toast.LENGTH_SHORT).show()
                    return@setOnPreferenceClickListener true
                }

                versionClickCount++
                if (versionClickCount >= 7) {
                    ShizukuSettings.setVectorEnabled(true)
                    SettingsSearchEngine.reset()
                    findPreference<Preference>("nav_developer_options")?.isVisible = true
                    Toast.makeText(context, R.string.settings_developer_options_revealed, Toast.LENGTH_SHORT).show()
                    versionClickCount = 0
                } else if (versionClickCount > 2) {
                    Toast.makeText(context, context.getString(R.string.settings_developer_options_click_more, 7 - versionClickCount), Toast.LENGTH_SHORT).show()
                }
                true
            }
        }

        // 2. Setup standard links
        findPreference<Preference>("source_code")?.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(context, "https://github.com/thejaustin/ShizukuPlus")
            true
        }

        findPreference<Preference>("open_source_licenses")?.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(requireContext(), "https://github.com/thejaustin/ShizukuPlus/blob/main/OPEN_SOURCE_LICENSES.md")
            true
        }

        // 3. Setup Auto Updates
        setupAutoUpdatePreference()
        setupAutoInstallPreference()
        setupChannelPreference()
        setupCheckForUpdatePreference()
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
        Toast.makeText(context, R.string.update_checking, Toast.LENGTH_SHORT).show()
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
        try {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
            startActivity(intent)
        } catch (e: Exception) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.update_permission_required_title)
                .setMessage(R.string.update_permission_required_message)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    private fun openReleasesPage() {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
