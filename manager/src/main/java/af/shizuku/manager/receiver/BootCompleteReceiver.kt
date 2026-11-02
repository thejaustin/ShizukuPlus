package af.shizuku.manager.receiver

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.RootCompatHelper
import af.shizuku.manager.service.WatchdogService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

class BootCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        val handled =
            when (action) {
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_USER_PRESENT,
                "android.intent.action.QUICKBOOT_POWERON",
                "com.htc.intent.action.QUICKBOOT_POWERON",
                -> true
                else -> false
            }
        if (!handled) return

        Timber.tag("BootCompleteReceiver").i("Triggered by: $action")

        if (action == Intent.ACTION_USER_PRESENT) {
            val um = context.getSystemService(android.os.UserManager::class.java)
            if (um != null && !um.isUserUnlocked) return

            if (ShizukuSettings.getWatchdog()) {
                try {
                    if (!WatchdogService.isRunning()) {
                        WatchdogService.start(context)
                    }
                    WatchdogAlarmReceiver.schedule(context)
                } catch (e: Exception) {
                    Timber.tag("BootCompleteReceiver").w(e, "Watchdog start on USER_PRESENT skipped")
                }
            }

            if (!ShizukuSettings.getStartOnBoot(context) ||
                af.shizuku.manager.utils.UserStopFlag
                    .isSet()
            ) {
                return
            }
            if (af.shizuku.manager.utils.ShizukuStateMachine
                    .isRunning() ||
                af.shizuku.manager.utils.ShizukuStateMachine
                    .get() == af.shizuku.manager.utils.ShizukuStateMachine.State.STARTING
            ) {
                return
            }
            Timber.tag("BootCompleteReceiver").i("User unlocked device while Shizuku is stopped — triggering start on boot")
            try {
                ShizukuReceiverStarter.start(context)
            } catch (e: Exception) {
                Timber.tag("BootCompleteReceiver").w(e, "USER_PRESENT auto-start failed")
            }
            return
        }
        // LOCKED_BOOT_COMPLETED fires before credential-encrypted storage is unlocked, so prefs
        // (launch mode, shell commands, etc.) are unavailable. Attempting to start here would either
        // crash or spawn a server using stale defaults, and BOOT_COMPLETED fires seconds later once
        // the user unlocks and handles the actual start (#504). Skip auto-start for LOCKED_BOOT_COMPLETED.
        if (action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            try {
                // A real boot may raise adbd's authorisation dialog once more; the QUICKBOOT
                // look-alikes do not reset that.
                if (action == Intent.ACTION_BOOT_COMPLETED) {
                    af.shizuku.manager.adb.AdbAuthWait
                        .clearUnanswered()
                }
                ShizukuReceiverStarter.start(context)
            } catch (e: Exception) {
                Timber.tag("BootCompleteReceiver").w(e, "Auto-start skipped (service not ready, e.g. direct boot)")
            }
        } else {
            Timber.tag("BootCompleteReceiver").d("LOCKED_BOOT_COMPLETED — deferring start to BOOT_COMPLETED")
        }
        try {
            if (ShizukuSettings.getWatchdog()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    af.shizuku.manager.worker.WatchdogWorker
                        .scheduleOneTimeHeal(context.applicationContext)
                } else {
                    WatchdogService.start(context)
                }
                // Exact alarms don't survive a reboot; re-arm the external watchdog (#415, #417).
                WatchdogAlarmReceiver.schedule(context)
            }
        } catch (e: Exception) {
            // startForegroundService can be refused during direct boot / from background — expected.
            Timber.tag("BootCompleteReceiver").w(e, "Watchdog start skipped")
        }
        // Restart AutomationService if any automation rules were configured before the reboot.
        if (ShizukuSettings.hasAnyAutomationRulesConfigured()) {
            try {
                af.shizuku.manager.automation.AutomationService
                    .startIfNeeded(context)
            } catch (e: Exception) {
                Timber.tag("BootCompleteReceiver").w(e, "AutomationService start skipped")
            }
        }
        if (ShizukuSettings.isAdbProxyEnabled()) {
            try {
                context.startService(Intent(context, af.shizuku.manager.service.AdbProxyService::class.java))
            } catch (e: Exception) {
                Timber.tag("BootCompleteReceiver").w(e, "AdbProxyService auto-start skipped")
            }
        }
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED && ShizukuSettings.isSuBridgeEnabled()) {
            // The su/rish_shizuku.dex bridge deployed to /data/local/tmp is version-specific
            // bytecode compiled against this exact APK build; previously it was only ever
            // refreshed when the user manually reopened Root Compatibility settings, so an app
            // update left every existing SU Bridge user running a stale dex against the new
            // server until they happened to revisit that screen (#423 - "script" callers started
            // getting a null newProcess() result after updating). Re-deploy on every self-update
            // for users who already opted into the bridge; deployBridgeToTmp() no-ops safely if
            // Shizuku isn't up yet (best-effort, same as the starter/watchdog calls above).
            val appContext = context.applicationContext
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    RootCompatHelper.deployBridgeToTmp(appContext)
                } catch (e: Exception) {
                    Timber.tag("BootCompleteReceiver").w(e, "SU bridge redeploy skipped")
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
