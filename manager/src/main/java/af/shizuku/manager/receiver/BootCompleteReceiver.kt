package af.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.service.WatchdogService

class BootCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val handled = when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> true
            else -> false
        }
        if (!handled) return

        Timber.tag("BootCompleteReceiver").i("Triggered by: $action")
        try {
            ShizukuReceiverStarter.start(context)
        } catch (e: Exception) {
            // LOCKED_BOOT_COMPLETED fires during direct boot, before credential-encrypted storage
            // is available — WorkManager can't initialize and prefs may be inaccessible. This is
            // expected; the later BOOT_COMPLETED (post-unlock) handles auto-start. Catch broadly so
            // no variant crashes the receiver, and log at warn (breadcrumb, not a billed Sentry event).
            Timber.tag("BootCompleteReceiver").w(e, "Auto-start skipped (service not ready, e.g. direct boot)")
        }
        try {
            if (ShizukuSettings.getWatchdog()) WatchdogService.start(context)
        } catch (e: Exception) {
            // startForegroundService can be refused during direct boot / from background — expected.
            Timber.tag("BootCompleteReceiver").w(e, "Watchdog start skipped")
        }
    }
}
