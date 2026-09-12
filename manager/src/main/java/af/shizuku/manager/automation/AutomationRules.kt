package af.shizuku.manager.automation

import android.content.Context
import timber.log.Timber
import af.shizuku.manager.ShizukuSettings

/**
 * Disables the Binder Firewall on a user-designated trusted Wi-Fi network, enables it otherwise
 * (including on mobile data / no connection). Reads ShizukuSettings.getTrustedNetworksSet() live
 * on every evaluation rather than caching it, so an edit in Settings takes effect on the next
 * network event without needing the rule to be re-registered (#435).
 */
class NetworkFirewallRule : AutomationRule {
    override val name: String = "Network Firewall Rule"
    private var isSafeNetwork: Boolean = false

    override fun evaluate(event: AutomationEvent, context: Context): Boolean {
        if (event is NetworkEvent) {
            val trustedNetworks = ShizukuSettings.getTrustedNetworksSet()
            // ssid may be null when SSID detection is unavailable (e.g. no ACCESS_FINE_LOCATION).
            // A null ssid is treated as unknown/untrusted — never matches the trusted set.
            val isCurrentlySafe = event.isWifiConnected && event.ssid != null && trustedNetworks.contains(event.ssid)
            if (isCurrentlySafe != isSafeNetwork) {
                isSafeNetwork = isCurrentlySafe
                return true
            }
        }
        return false
    }

    override fun execute(context: Context) {
        Timber.i(
            if (isSafeNetwork) "Trusted network detected, disabling Binder Firewall"
            else "Untrusted (or no) network detected, enabling Binder Firewall"
        )
        ShizukuSettings.setBinderFirewallEnabled(!isSafeNetwork)
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

    override fun evaluate(event: AutomationEvent, context: Context): Boolean {
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
 * When the foreground app has a BLOCK profile entry, binder_firewall is enabled.
 * When it has an ALLOW entry, binder_firewall is disabled.
 * When there is no entry (DEFAULT), the global binder_firewall setting is restored.
 *
 * The previous global value is captured when this rule first fires and restored when a
 * DEFAULT app comes to the foreground, so the user's manual setting is never permanently
 * overwritten by the per-app rule.
 */
class AppSpecificProfileRule : AutomationRule {
    override val name: String = "App Specific Profile Rule"
    private var currentApp: String? = null
    private var savedGlobalFirewall: Boolean? = null

    override fun evaluate(event: AutomationEvent, context: Context): Boolean {
        if (event is ForegroundAppEvent && currentApp != event.packageName) {
            currentApp = event.packageName
            return true
        }
        return false
    }

    override fun execute(context: Context) {
        val app = currentApp ?: return
        val profilesJson = ShizukuSettings.getAutomationAppProfilesJson()
        try {
            val json = org.json.JSONObject(profilesJson)
            val appObj = json.optJSONObject(app)
            if (appObj != null) {
                val block = appObj.optBoolean("binder_firewall", false)
                // Save the current global setting only once before the first per-app override.
                if (savedGlobalFirewall == null) {
                    savedGlobalFirewall = ShizukuSettings.isBinderFirewallEnabled()
                }
                Timber.i("AppSpecificProfileRule: foreground=%s binder_firewall=%s", app, block)
                ShizukuSettings.setBinderFirewallEnabled(block)
                ShizukuSettings.syncAllPlusFeaturesToServer()
            } else if (savedGlobalFirewall != null) {
                // App has no profile — restore saved global value.
                Timber.i("AppSpecificProfileRule: foreground=%s (default) restoring binder_firewall=%s", app, savedGlobalFirewall)
                ShizukuSettings.setBinderFirewallEnabled(savedGlobalFirewall!!)
                ShizukuSettings.syncAllPlusFeaturesToServer()
                savedGlobalFirewall = null
            }
        } catch (_: Exception) {}
    }
}

fun registerDefaultRules() {
    AutomationEngine.registerRule(NetworkFirewallRule())
    AutomationEngine.registerRule(AppAutoHideRule())
}
