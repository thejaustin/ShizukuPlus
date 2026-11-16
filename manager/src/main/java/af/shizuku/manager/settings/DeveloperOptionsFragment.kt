package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.Keys.*
import af.shizuku.manager.utils.CrashHandler
import af.shizuku.manager.utils.CrashReporter
import af.shizuku.manager.utils.CustomTabsHelper
import af.shizuku.manager.utils.LoggedCrash
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DeveloperOptionsFragment : BaseSettingsFragment() {
    override fun getTitle(): CharSequence? = getString(R.string.settings_developer_options)

    override fun onCreateSettingsPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.settings_developer_options, rootKey)

        setUpPlusLogAccess()

        // Hides the Backup & Restore category in Advanced (#461), which re-reads it on resume.
        findPreference<TwoStatePreference>("hide_backup_settings")?.apply {
            isChecked = ShizukuSettings.isHideBackupSettingsEnabled()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) ShizukuSettings.setHideBackupSettingsEnabled(newValue)
                true
            }
        }

        // Notification system master switch
        findPreference<TwoStatePreference>(KEY_NOTIFICATION_SYSTEM_ENABLED)?.apply {
            isChecked = ShizukuSettings.isNotificationSystemEnabled()
            setOnPreferenceChangeListener { _, v ->
                val enabled = v as? Boolean ?: return@setOnPreferenceChangeListener false
                ShizukuSettings.setNotificationSystemEnabled(enabled)
                if (!enabled) {
                    val ctx = context ?: return@setOnPreferenceChangeListener true
                    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                    nm?.cancel(af.shizuku.manager.service.WatchdogService.NOTIFICATION_ID_CRASH)
                    nm?.cancel(af.shizuku.manager.worker.AdbStartWorker.NOTIFICATION_ID)
                    nm?.cancel(af.shizuku.manager.worker.AdbStartWorker.NOTIFICATION_ID_MDNS_BLOCKED)
                    nm?.cancel(af.shizuku.manager.receiver.ShizukuReceiverStarter.NOTIFICATION_ID)
                    nm?.cancel(af.shizuku.manager.receiver.ShizukuReceiverStarter.FOREGROUND_NOTIFICATION_ID)
                    nm?.cancel(af.shizuku.manager.utils.BackgroundNudge.NOTIFICATION_ID)
                    nm?.cancel(af.shizuku.manager.utils.BatteryOptimizationGuard.NOTIFICATION_ID)
                    nm?.cancel(af.shizuku.manager.utils.VendorBatteryRestrictions.NOTIFICATION_ID)
                    nm?.cancel(af.shizuku.manager.adb.AdbPairingService.NOTIFICATION_ID)
                    nm?.cancel(af.shizuku.manager.utils.TcpNetworkGuard.NOTIFICATION_ID)
                    nm?.cancel(af.shizuku.manager.utils.LiveActivityNotificationManager.NOTIFICATION_ID_WARNING)
                    nm?.cancel(af.shizuku.manager.utils.ShizukuIssues.NOTIFICATION_ID)
                }
                true
            }
        }

        findPreference<TwoStatePreference>(KEY_SETTING_DISABLING_OPTIONAL)?.apply {
            isChecked = ShizukuSettings.isSettingDisablingOptional()
            setOnPreferenceChangeListener { _, v ->
                val enabled = v as? Boolean ?: return@setOnPreferenceChangeListener false
                ShizukuSettings.setSettingDisablingOptional(enabled)
                if (enabled) {
                    preferenceScreen?.let { bypassSettingDisabling(it) }
                }
                true
            }
        }

        findPreference<Preference>("reset_recommendations")?.setOnPreferenceClickListener {
            af.shizuku.manager.utils.AdaptiveRecommendationManager
                .resetAll()
            context?.let { ctx ->
                Toast
                    .makeText(
                        ctx,
                        R.string.settings_reset_recommendations_toast,
                        Toast.LENGTH_SHORT,
                    ).show()
            }
            true
        }

        // Unrestricted third-party access: confirm before turning on. Both are Plus rows, which
        // sync themselves once the new value is saved.
        listOf(KEY_UNRESTRICTED_APP_DATA, KEY_UNRESTRICTED_PERSONAL_DATA).forEach { key ->
            findPreference<TwoStatePreference>(key)?.setOnPreferenceChangeListener { pref, v ->
                val enabled = v as? Boolean ?: return@setOnPreferenceChangeListener false
                if (!enabled) return@setOnPreferenceChangeListener true
                val ctx = context ?: return@setOnPreferenceChangeListener false
                showDialog(
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.settings_unrestricted_confirm_title)
                        .setMessage(R.string.settings_unrestricted_confirm_message)
                        .setPositiveButton(R.string.settings_unrestricted_confirm_positive) { _, _ ->
                            (pref as? TwoStatePreference)?.isChecked = true
                        }.setNegativeButton(android.R.string.cancel, null),
                )
                false
            }
        }

        // Manual Crash Report
        findPreference<Preference>("manual_report")?.apply {
            val ctx = context ?: return@apply
            val hasLastCrash = CrashHandler.getLastCrashReport(ctx) != null
            if (hasLastCrash) {
                setTitle(R.string.manual_report_last_crash_title)
                setCustomSummary(R.string.manual_report_last_crash_summary)
            }

            setOnPreferenceClickListener {
                showDialog(
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle(if (hasLastCrash) R.string.manual_report_last_crash_title else R.string.manual_report_title)
                        .setMessage(R.string.manual_report_summary)
                        .setPositiveButton(R.string.manual_report_button_github) { _, _ ->
                            pickCrashes(ctx) { crashes -> sendToGitHub(ctx, crashes, hasLastCrash) }
                        }.setNeutralButton(R.string.manual_report_copied_dialog_share) { _, _ ->
                            pickCrashes(ctx) { crashes ->
                                viewLifecycleOwner.lifecycleScope.launch {
                                    CrashReporter.shareAsFile(ctx, crashes)
                                    if (hasLastCrash) {
                                        CrashHandler.clearLastCrash(ctx)
                                    }
                                }
                            }
                        }.setNegativeButton(android.R.string.cancel, null),
                )
                true
            }
        }
    }

    private fun sendToGitHub(
        ctx: Context,
        crashes: List<LoggedCrash>?,
        hasLastCrash: Boolean,
    ) = viewLifecycleOwner.lifecycleScope.launch {
        // Reading logcat and scrubbing the whole report is slow: not on the main thread.
        val report = withContext(Dispatchers.IO) { CrashReporter.generateReport(ctx, crashes) }
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            af.shizuku.manager.utils.sensitivePlainText(
                ctx.getString(R.string.manual_report_clipboard_label),
                report,
            ),
        )

        Toast.makeText(ctx, R.string.manual_report_toast_copied, Toast.LENGTH_LONG).show()

        val url = CrashReporter.getGitHubReportUrl(report)
        CustomTabsHelper.launchUrlOrCopy(ctx, url)

        if (hasLastCrash) {
            CrashHandler.clearLastCrash(ctx)
        }
    }

    /**
     * Lets the user choose which logged crashes go into the report (#628); the newest is checked.
     * With no history (crashes from before it existed) it falls back to the last crash file.
     */
    private fun pickCrashes(
        ctx: Context,
        onPicked: (List<LoggedCrash>?) -> Unit,
    ) {
        val history = CrashHandler.getCrashHistory(ctx)
        if (history.isEmpty()) {
            onPicked(null)
            return
        }
        val format = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        val labels = history.map { "${format.format(java.util.Date(it.timestamp))} · ${it.exception}" }.toTypedArray()
        val checked = BooleanArray(history.size) { it == 0 }
        showDialog(
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.manual_report_pick_crashes_title)
                .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton(R.string.action_continue) { _, _ ->
                    onPicked(history.filterIndexed { i, _ -> checked[i] })
                }.setNegativeButton(android.R.string.cancel, null),
        )
    }

    /** Widening what `plus log` shows asks first; narrowing never does. The service enforces both. */
    private fun setUpPlusLogAccess() {
        val showCommands = findPreference<TwoStatePreference>(KEY_PLUS_LOG_SHOW_COMMANDS)
        val access = findPreference<ConnectedButtonGroupPreference>(KEY_PLUS_LOG_ACCESS) ?: return
        showCommands?.isEnabled = access.value != "off"
        access.setOnPreferenceChangeListener { _, newValue ->
            fun apply(mode: String) {
                access.value = mode
                showCommands?.isEnabled = mode != "off"
                ShizukuSettings.syncAllPlusFeaturesToServer()
            }
            if (newValue == "all") {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.plus_log_all_warning_title)
                    .setMessage(R.string.plus_log_all_warning_message)
                    .setPositiveButton(R.string.action_continue) { _, _ -> apply("all") }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return@setOnPreferenceChangeListener false
            }
            apply(newValue as String)
            false
        }
        showCommands?.setOnPreferenceChangeListener { pref, newValue ->
            if (newValue != true) {
                ShizukuSettings.syncAllPlusFeaturesToServer()
                return@setOnPreferenceChangeListener true
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_plus_log_show_commands_title)
                .setMessage(R.string.plus_log_commands_warning_message)
                .setPositiveButton(R.string.action_continue) { _, _ ->
                    (pref as TwoStatePreference).isChecked = true
                    ShizukuSettings.syncAllPlusFeaturesToServer()
                }.setNegativeButton(android.R.string.cancel, null)
                .show()
            false
        }
    }
}
