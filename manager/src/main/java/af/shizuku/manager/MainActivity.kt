package af.shizuku.manager

import af.shizuku.manager.home.ChangelogDialogFragment
import af.shizuku.manager.home.HomeActivity
import af.shizuku.manager.update.UpdateChecker
import af.shizuku.manager.utils.ShizukuStateMachine
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.sentry.Breadcrumb
import io.sentry.Sentry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class MainActivity : HomeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            Timber.d("Calling super.onCreate")
            Sentry.addBreadcrumb(Breadcrumb("Calling super.onCreate"))
            super.onCreate(savedInstanceState)

            // Check for previous crashes and offer to report — only for developers if Sentry is disabled.
            // Take manual reporting out of the general purpose UI for end users.
            if (af.shizuku.manager.utils.CrashHandler
                    .getLastCrashReport(this) != null
            ) {
                if (ShizukuSettings.isVectorEnabled() && BuildConfig.SENTRY_DSN.isEmpty()) {
                    showCrashReportDialog()
                }
            }

            Timber.d("Checking onboarding status")
            Sentry.addBreadcrumb(Breadcrumb("Checking onboarding status"))

            // Auto-restore settings if a force-update backup exists
            checkAndRestoreBackup()

            // Show what's new after an update. Separate from the Sentry-quota-reset version
            // tracking in ShizukuApplication.onCreate() — that one bumps its own flag before any
            // Activity runs, so this needs its own last-seen key or it would never see an advance.
            checkAndShowChangelog()

            Timber.d("MainActivity onCreate complete")
            Sentry.addBreadcrumb(Breadcrumb("MainActivity onCreate complete"))
        } catch (e: Exception) {
            Timber.e(e, "Crash in MainActivity.onCreate")
            Sentry.addBreadcrumb(Breadcrumb("MainActivity crash: ${e.message}"))
            Sentry.captureException(e)
            throw e
        }
    }

    override fun onStart() {
        try {
            super.onStart()
            // Update state machine on app start
            ShizukuStateMachine.update()
            // Self-heal the AICore+ accessibility service if an OEM power manager disabled it
            // while the app was backgrounded but the user still has the feature on (#320).
            af.shizuku.manager.automation.AICoreAccessibilityHealer
                .reenableIfNeeded(this)
        } catch (e: Exception) {
            Timber.e(e, "Error in onStart")
            Sentry.captureException(e)
            throw e
        }
    }

    private fun checkAndRestoreBackup() {
        lifecycleScope.launch(Dispatchers.IO) {
            val backupFile =
                af.shizuku.manager.update.UpdateInstaller
                    .getBackupFile(this@MainActivity)
            if (backupFile != null && backupFile.exists()) {
                try {
                    val json = backupFile.readText()
                    if (af.shizuku.manager.utils.SettingsBackupManager
                            .import(this@MainActivity, json)
                    ) {
                        Timber.i("Successfully auto-restored settings from force-update backup")
                        backupFile.delete()
                        // Notify user or refresh UI if needed
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, R.string.migration_success_message, Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to auto-restore settings")
                }
            }
        }
    }

    /**
     * Shows "What's New" once per version bump. Fetches every release since the user's previous
     * install so they never miss features — if they skipped 10 builds, they see all 10.
     *
     * Supports both the new "r{N}" tag format and the legacy "v{semver}.r{N}" format.
     */
    private fun checkAndShowChangelog() {
        val currentCode =
            try {
                packageManager.getPackageInfo(packageName, 0).versionCode
            } catch (_: Exception) {
                0
            }
        val lastSeenCode = ShizukuSettings.getLastSeenChangelogVersion()
        if (currentCode <= lastSeenCode) return

        val versionSuffix = BuildConfig.VERSION_NAME.removePrefix("Shizuku+ ").trim()
        val tagName =
            when {
                // Current format: "Shizuku+ 13.7.0.r2700" → tag is "13.7.0.r2700"
                Regex("""^\d+\.\d+\.\d+\.r\d+$""").matches(versionSuffix) -> versionSuffix
                // Transitional format: "Shizuku+ r2673" → tag is "r2673"
                versionSuffix.matches(Regex("""r\d+""")) -> versionSuffix
                // Legacy: builds r2663–r2668 were incorrectly tagged "v14.0.0.r{N}" (now
                // corrected to "13.7.0.r{N}" scheme). Map them to their published GitHub tags
                // so existing installs can still fetch their changelog entry.
                Regex("""14\.\d+\.\d+\.r\d+""").containsMatchIn(versionSuffix) ->
                    "v${Regex("""\d+\.\d+\.\d+\.r\d+""").find(versionSuffix)!!.value}"
                else -> {
                    ShizukuSettings.setLastSeenChangelogVersion(currentCode)
                    return
                }
            }

        lifecycleScope.launch {
            val releases =
                try {
                    UpdateChecker.fetchReleasesSince(sinceVersionCode = lastSeenCode, maxReleases = 25)
                } catch (e: Exception) {
                    Timber.tag("MainActivity").w(e, "Failed to fetch releases")
                    emptyList()
                }

            // Mark seen regardless of fetch outcome so offline users aren't re-prompted every launch.
            ShizukuSettings.setLastSeenChangelogVersion(currentCode)

            if (isFinishing || isDestroyed) return@launch
            try {
                ChangelogDialogFragment
                    .newInstance(releases, tagName)
                    .show(supportFragmentManager, ChangelogDialogFragment.TAG)
            } catch (e: Exception) {
                Timber.e(e, "Failed to show changelog dialog")
            }
        }
    }

    private fun showCrashReportDialog() {
        // Sentry already captured the original crash; this dialog lets users share a
        // human-readable report. It is optional — if the themed context is unavailable
        // (e.g. theme mismatch on old ROM) we silently clear the crash file and move on.
        try {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.manual_report_title)
                .setMessage(R.string.crash_detected_dialog_message)
                .setPositiveButton(R.string.manual_report_button_github) { _, _ ->
                    af.shizuku.manager.utils.CrashReporter
                        .shareAsFile(this)
                    af.shizuku.manager.utils.CrashHandler
                        .clearLastCrash(this)
                }.setNegativeButton(R.string.crash_detected_dialog_ignore) { _, _ ->
                    af.shizuku.manager.utils.CrashHandler
                        .clearLastCrash(this)
                }.show()
        } catch (e: Exception) {
            Timber.e(e, "showCrashReportDialog failed — clearing crash file silently")
            Sentry.captureException(e)
            af.shizuku.manager.utils.CrashHandler
                .clearLastCrash(this)
        }
    }
}
