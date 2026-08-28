package af.shizuku.manager.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import timber.log.Timber
import af.shizuku.manager.R

class AutomationService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private var connectivityManager: ConnectivityManager? = null
    private var callbackRegistered = false
    private var isForeground = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            checkNetworkState()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            checkNetworkState()
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
        // Promote to foreground before any work so the 5-second startForegroundService() deadline
        // is met. If the platform refuses (background-start restriction, FGS time-limit, type
        // validation), bail out gracefully instead of crashing — see SHIZUKUPLUS-6H/6M/6G/6V.
        if (!ensureForeground()) {
            stopSelf()
            return
        }

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

        startForegroundAppMonitor()
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

    private fun checkNetworkState() {
        val cm = connectivityManager ?: return
        try {
            val activeNetwork = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(activeNetwork)
            // Count Ethernet (RNDIS/USB tethering, common on Samsung XCover, Zebra, Honeywell
            // ruggedized devices) as a valid ADB transport alongside WiFi (#403).
            // WifiManager.getConnectionInfo() is deprecated since API 31 and throws
            // SecurityException on some OEM builds even with ACCESS_WIFI_STATE declared
            // (SHIZUKUPLUS-50). SSID is intentionally omitted; getNetworkCapabilities needs
            // no wifi permission.
            val isWifi = caps != null &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                     caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            AutomationEngine.dispatchEvent(NetworkEvent(isWifi, null), applicationContext)
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
