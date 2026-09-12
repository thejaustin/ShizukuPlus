package af.shizuku.manager.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.IBinder
import android.content.Context
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import timber.log.Timber
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings

class AutomationService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private var connectivityManager: ConnectivityManager? = null
    private var callbackRegistered = false
    private var isForeground = false

    // Rule instances kept as fields so state (isSafeNetwork, currentApp) persists across events.
    private val networkFirewallRule = NetworkFirewallRule()
    private val appProfileRule = AppSpecificProfileRule()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            checkNetworkState()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            checkNetworkState(networkCapabilities)
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            checkNetworkState()
        }
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "automation_service"
        // 1001/1002 are taken by WatchdogService — use a distinct ID to avoid foreground-token conflicts
        private const val NOTIFICATION_ID = 1003

        /** Starts the service (and its notification/polling) - only call when the caller has
         *  already confirmed the user actually configured something (#435); this itself does not
         *  re-check af.shizuku.manager.ShizukuSettings.isAnyAutomationConfigured(), since callers
         *  that already know they're reacting to a list becoming non-empty shouldn't need to. */
        @JvmStatic
        fun startIfNeeded(context: Context) {
            try {
                val intent = Intent(context, AutomationService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Timber.tag("AutomationService").w(e, "Failed to start AutomationService")
            }
        }

        /** Symmetric with [startIfNeeded] - called when the last configured trusted network or
         *  auto-hide package is removed, so the service (and its notification/polling) actually
         *  stop instead of continuing to run for lists that are now empty. */
        @JvmStatic
        fun stopIfRunning(context: Context) {
            try {
                context.stopService(Intent(context, AutomationService::class.java))
            } catch (e: Exception) {
                Timber.tag("AutomationService").w(e, "Failed to stop AutomationService")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Timber.tag("AutomationService").d("Service created")

        // Don't run if no rules are configured — avoids a permanent foreground notification
        // for users who have never touched the automation settings.
        if (!ShizukuSettings.hasAnyAutomationRulesConfigured()) {
            Timber.tag("AutomationService").d("No automation rules configured; stopping")
            stopSelf()
            return
        }

        // Promote to foreground before any work so the 5-second startForegroundService() deadline
        // is met. If the platform refuses (background-start restriction, FGS time-limit, type
        // validation), bail out gracefully instead of crashing — see SHIZUKUPLUS-6H/6M/6G/6V.
        if (!ensureForeground()) {
            stopSelf()
            return
        }

        AutomationEngine.registerRule(networkFirewallRule)
        AutomationEngine.registerRule(appProfileRule)

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        connectivityManager = cm
        if (cm != null) {
            try {
                // TRANSPORT_WIFI covers normal wireless ADB; TRANSPORT_ETHERNET covers RNDIS/USB-
                // tethering connections used on devices like Samsung XCover 7 (#403). Both trigger
                // checkNetworkState() so NetworkFirewallRule and AdbStartWorker react to either.
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .build()
                cm.registerNetworkCallback(request, networkCallback)
                callbackRegistered = true
            } catch (e: Exception) {
                Timber.tag("AutomationService").w(e, "Failed to register network callback")
            }
        }

        // App profile monitor polls UsageStats every 2 seconds — skip it entirely when no
        // per-app profiles are configured. The network callback alone is sufficient for users
        // who only use Trusted Networks automation.
        val appProfilesJson = ShizukuSettings.getAutomationAppProfilesJson()
        if (appProfilesJson != "{}" && appProfilesJson.length > 2) {
            startForegroundAppMonitor()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Keep the notification current if the service is restarted (START_STICKY) after being
        // killed. If foregrounding is refused now, stop rather than risk a "did not start in
        // time" system crash.
        if (!isForeground && !ensureForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun ensureForeground(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_automation),
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(getString(R.string.notification_automation_title))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
        return try {
            // specialUse (not dataSync): this is an indefinite context monitor. dataSync FGS has a
            // 6h/day time budget and a must-stop-in-time requirement on Android 15+, which crashed
            // the service with ForegroundServiceStartNotAllowedException / DidNotStopInTime
            // (SHIZUKUPLUS-6H/6G) and failed type validation (SHIZUKUPLUS-6M). specialUse has no
            // such limit; the type is declared in the manifest and backed by
            // FOREGROUND_SERVICE_SPECIAL_USE.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
            true
        } catch (e: Throwable) {
            // ForegroundServiceStartNotAllowedException (started from background) and friends are
            // not fatal here — the monitor simply won't run this session.
            Timber.tag("AutomationService").w(e, "startForeground refused; stopping service")
            false
        }
    }

    // Called when no caps are available (onLost / onAvailable before caps arrive).
    private fun checkNetworkState() {
        val cm = connectivityManager ?: return
        try {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            checkNetworkState(caps)
        } catch (e: Exception) {
            Timber.tag("AutomationService").w(e, "Failed to check network state")
        }
    }

    private fun checkNetworkState(caps: NetworkCapabilities?) {
        try {
            val isWifi = caps != null &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                     caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))

            // On API 29+ the SSID is available from NetworkCapabilities.getTransportInfo() within
            // a network callback without requiring ACCESS_FINE_LOCATION, which was revoked in Android 10.
            // WifiManager.getConnectionInfo() (deprecated API 31) throws SecurityException on some OEM
            // builds even with ACCESS_WIFI_STATE declared (SHIZUKUPLUS-50).
            val ssid: String? = if (Build.VERSION.SDK_INT >= 29 && isWifi && caps != null) {
                (caps.transportInfo as? WifiInfo)?.ssid?.let { raw ->
                    // WifiInfo.getSSID() wraps SSIDs in double-quotes: "\"MyNetwork\""
                    // Strip them; "<unknown ssid>" means the platform declined to share it.
                    if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2) {
                        raw.substring(1, raw.length - 1)
                    } else if (raw == "<unknown ssid>") {
                        null
                    } else {
                        raw
                    }
                }
            } else {
                null
            }

            AutomationEngine.dispatchEvent(NetworkEvent(isWifi, ssid), applicationContext)
        } catch (e: Exception) {
            Timber.tag("AutomationService").w(e, "Failed to check network state")
        }
    }

    private var lastForegroundApp: String? = null

    private fun startForegroundAppMonitor() {
        scope.launch {
            while (isActive) {
                try {
                    // Requires UsageStats permission. Alternatively, using Shizuku could be more robust.
                    // For now, we will poll usage stats if available.
                    val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
                    if (usageStatsManager == null) {
                        // continue alone would skip the delay(2000) below and busy-spin this loop
                        // indefinitely on Dispatchers.IO if the system service is ever unavailable.
                        delay(2000)
                        continue
                    }
                    val endTime = System.currentTimeMillis()
                    val startTime = endTime - 10000 // 10 seconds ago
                    val events = usageStatsManager.queryEvents(startTime, endTime)
                    var currentApp: String? = null

                    val event = android.app.usage.UsageEvents.Event()
                    while (events.hasNextEvent()) {
                        events.getNextEvent(event)
                        if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                            currentApp = event.packageName
                        }
                    }

                    if (currentApp != null && currentApp != lastForegroundApp) {
                        lastForegroundApp = currentApp
                        AutomationEngine.dispatchEvent(ForegroundAppEvent(currentApp), applicationContext)
                    }
                } catch (e: Exception) {
                    // Ignore, maybe missing permissions
                }
                delay(2000) // Poll every 2 seconds
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        AutomationEngine.unregisterRule(networkFirewallRule)
        AutomationEngine.unregisterRule(appProfileRule)
        if (callbackRegistered) {
            try {
                connectivityManager?.unregisterNetworkCallback(networkCallback)
            } catch (e: Exception) {
                Timber.tag("AutomationService").w(e, "Failed to unregister network callback")
            }
        }
        job.cancel()
    }
}
