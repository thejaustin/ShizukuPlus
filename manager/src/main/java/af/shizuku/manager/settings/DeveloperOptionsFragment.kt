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
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class DeveloperOptionsFragment : BaseSettingsFragment() {
    override fun getTitle(): CharSequence? = getString(R.string.settings_developer_options)

    override fun onCreateSettingsPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.settings_developer_options, rootKey)

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

        // Vector / AVF Manager
        findPreference<TwoStatePreference>(KEY_VECTOR_ENABLED)?.apply {
            isChecked = ShizukuSettings.isVectorEnabled()
            setOnPreferenceChangeListener { _, v ->
                val enabled = v as? Boolean ?: return@setOnPreferenceChangeListener false
                ShizukuSettings.setVectorEnabled(enabled)
                ShizukuSettings.syncAllPlusFeaturesToServer()
                true
            }
        }

        // Experimental Root Compatibility
        findPreference<TwoStatePreference>(KEY_EXPERIMENTAL_ROOT_COMPAT)?.apply {
            isChecked = ShizukuSettings.isExperimentalRootCompatEnabled()
            setOnPreferenceChangeListener { _, v ->
                val enabled = v as? Boolean ?: return@setOnPreferenceChangeListener false
                ShizukuSettings.setExperimentalRootCompatEnabled(enabled)
                ShizukuSettings.syncAllPlusFeaturesToServer()
                true
            }
        }

        // Device Identity Spoofing
        findPreference<TwoStatePreference>(KEY_SPOOF_DEVICE_ENABLED)?.apply {
            isChecked = ShizukuSettings.isSpoofDeviceEnabled()
            setOnPreferenceChangeListener { _, v ->
                val enabled = v as? Boolean ?: return@setOnPreferenceChangeListener false
                ShizukuSettings.setSpoofDeviceEnabled(enabled)
                ShizukuSettings.syncAllPlusFeaturesToServer()
                true
            }
        }

        // Spoof Target
        findPreference<rikka.preference.SimpleMenuPreference>(KEY_SPOOF_TARGET)?.setOnPreferenceChangeListener { _, v ->
            val target = v as? String ?: return@setOnPreferenceChangeListener false
            ShizukuSettings.setSpoofTarget(if (target == "auto") "auto" else target)
            ShizukuSettings.syncAllPlusFeaturesToServer()
            true
        }

        val expKeys =
            listOf(
                "window_manager_plus_enabled",
                "overlay_manager_plus_enabled",
                "ai_core_plus_enabled",
                "ai_core_master_enabled",
                "npu_acceleration_enabled",
                "native_window_crawler_enabled",
                "ai_core_experimental_enabled",
                "display_tuner_plus_enabled",
            )
        expKeys.forEach { key ->
            findPreference<TwoStatePreference>(key)?.setOnPreferenceChangeListener { _, _ ->
                // The listener runs before the switch persists its value; sync once it has, or the
                // server gets the old state.
                view?.post { ShizukuSettings.syncAllPlusFeaturesToServer() }
                true
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
                                CrashReporter.shareAsFile(ctx, crashes)
                                if (hasLastCrash) {
                                    CrashHandler.clearLastCrash(ctx)
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
    ) {
        val report = CrashReporter.generateReport(ctx, crashes)
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText(
                ctx.getString(R.string.manual_report_clipboard_label),
                report,
            ),
        )

        Toast.makeText(ctx, R.string.manual_report_toast_copied, Toast.LENGTH_LONG).show()

        val url = CrashReporter.getGitHubReportUrl(ctx, crashes)
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
}
