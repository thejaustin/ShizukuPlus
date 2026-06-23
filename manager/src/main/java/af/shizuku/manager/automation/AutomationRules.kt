package af.shizuku.manager.automation

import android.content.Context
import timber.log.Timber
import af.shizuku.manager.ShizukuSettings

class NetworkFirewallRule : AutomationRule {
    override val name: String = "Network Firewall Rule"
    private var isSafeNetwork: Boolean = false

    override fun evaluate(event: AutomationEvent, context: Context): Boolean {
        if (event is NetworkEvent) {
            val safeNetworks = setOf("HomeWiFi", "WorkNetwork")
            // ssid may be null when SSID detection is unavailable (e.g. no ACCESS_FINE_LOCATION).
            // A null ssid is treated as unknown/untrusted — never matches the safe-network set.
            val isCurrentlySafe = event.isWifiConnected && event.ssid != null && safeNetworks.contains(event.ssid)
            if (isCurrentlySafe != isSafeNetwork) {
                isSafeNetwork = isCurrentlySafe
                return true
            }
        }
        return false
    }

    override fun execute(context: Context) {
        // Toggle sensitive features
        if (isSafeNetwork) {
            Timber.i("Safe network detected, disabling Binder Firewall")
            // ShizukuSettings.setBinderFirewallEnabled(false)
        } else {
            Timber.i("Untrusted network detected, enabling Binder Firewall")
            // ShizukuSettings.setBinderFirewallEnabled(true)
        }
    }
}

class AppSpecificProfileRule : AutomationRule {
    override val name: String = "App Profile Rule"
    private var currentApp: String? = null

    override fun evaluate(event: AutomationEvent, context: Context): Boolean {
        if (event is ForegroundAppEvent) {
            if (currentApp != event.packageName) {
                currentApp = event.packageName
                return true
            }
        }
        return false
    }

    override fun execute(context: Context) {
        val app = currentApp ?: return
        Timber.i("Foreground app changed to $app, applying profile")

        when (app) {
            "com.banking.app" -> {
                Timber.i("Applying secure profile for banking app")
                // Enable ShadowBinder, etc.
            }
            "com.games.app" -> {
                Timber.i("Applying performance profile for game")
                // Disable background proxies, etc.
            }
            else -> {
                Timber.i("Restoring default profile")
            }
        }
    }
}

fun registerDefaultRules() {
    AutomationEngine.registerRule(NetworkFirewallRule())
    AutomationEngine.registerRule(AppSpecificProfileRule())
}
