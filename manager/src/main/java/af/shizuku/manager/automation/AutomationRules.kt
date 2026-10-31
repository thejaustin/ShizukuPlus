package af.shizuku.manager.automation

import af.shizuku.manager.ShizukuSettings
import android.content.Context
import timber.log.Timber

/**
 * Relaxes the Binder Firewall on a user-designated trusted Wi-Fi network and applies the user's
 * switch everywhere else (including mobile data / no connection). The switch itself is never
 * written: the server gets ShizukuSettings.isBinderFirewallEffective(). Reads the trusted set live
 * on every evaluation, so an edit in Settings takes effect on the next network event (#435).
 */
class NetworkFirewallRule : AutomationRule {
    override val name: String = "Network Firewall Rule"
    private var trusted: Boolean = false

    // Compared with what ShizukuSettings holds rather than this rule's last result, which
    // AutomationService clears when it stops, so a restart on the same network applies it again.
    override fun evaluate(
        event: AutomationEvent,
        context: Context,
    ): Boolean {
        if (event !is NetworkEvent) return false
        // ssid may be null when SSID detection is unavailable; an unknown network is untrusted.
        trusted = event.isWifiConnected && event.ssid != null && event.ssid in ShizukuSettings.getTrustedNetworksSet()
        return trusted != ShizukuSettings.isOnTrustedNetwork()
    }

    override fun execute(context: Context) {
        Timber.i(if (trusted) "Trusted network detected, relaxing Binder Firewall" else "Untrusted (or no) network detected")
        ShizukuSettings.setOnTrustedNetwork(trusted)
        ShizukuSettings.syncAllPlusFeaturesToServer()
    }
}

/**
 * Adds the current foreground app to ShadowBinder's effective hidden-packages list while it's in
 * the foreground, if it's in the user's auto-hide list (ShizukuSettings.getAutoHidePackagesSet())
 * - separate from the user's manually-managed static hidden list, never mutates it (#435).
 */
class AppAutoHideRule : AutomationRule {
    override val name: String = "App Auto-Hide Rule"
    private var currentApp: String? = null

    override fun evaluate(
        event: AutomationEvent,
        context: Context,
    ): Boolean {
        if (event is ForegroundAppEvent && currentApp != event.packageName) {
            currentApp = event.packageName
            return true
        }
        return false
    }

    override fun execute(context: Context) {
        val app = currentApp ?: return
        Timber.i("Foreground app changed to %s, recomputing effective ShadowBinder hidden packages", app)
        ShizukuSettings.pushShadowHiddenPackagesToServer(app)
    }
}

/**
 * Applies per-app Binder Firewall overrides when the foreground app changes.
 *
 * Profile JSON format (stored in ShizukuSettings.getAutomationAppProfilesJson()):
 *   {"com.pkg": {"binder_firewall": true}}
 *
 * A BLOCK entry turns the firewall on while that app is in the foreground and an ALLOW entry turns
 * it off. With no entry the override is dropped and the user's own setting applies again; that
 * setting is never written, so nothing has to be saved and restored.
 */
class AppSpecificProfileRule : AutomationRule {
    override val name: String = "App Specific Profile Rule"
    private var override: Boolean? = null

    override fun evaluate(
        event: AutomationEvent,
        context: Context,
    ): Boolean {
        if (event !is ForegroundAppEvent) return false
        override =
            runCatching {
                org.json
                    .JSONObject(ShizukuSettings.getAutomationAppProfilesJson())
                    .optJSONObject(event.packageName)
                    ?.optBoolean("binder_firewall", false)
            }.getOrNull()
        return override != ShizukuSettings.getAppFirewallOverride()
    }

    override fun execute(context: Context) {
        Timber.i("AppSpecificProfileRule: binder_firewall override=%s", override)
        ShizukuSettings.setAppFirewallOverride(override)
        ShizukuSettings.syncAllPlusFeaturesToServer()
    }
}

fun registerDefaultRules() {
    AutomationEngine.registerRule(NetworkFirewallRule())
    AutomationEngine.registerRule(AppAutoHideRule())
    AutomationEngine.registerRule(AppSpecificProfileRule())
}
