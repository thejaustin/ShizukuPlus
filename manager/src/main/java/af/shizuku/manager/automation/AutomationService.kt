package af.shizuku.manager.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
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

    private lateinit var connectivityManager: ConnectivityManager
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
    }

    override fun onCreate() {
        super.onCreate()
        Timber.tag("AutomationService").d("Service created")
        // Call startForeground() before any work so the 5-second foreground-service deadline
        // imposed by startForegroundService() is met even if network callback registration is slow.
        startForegroundCompat()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)

        startForegroundAppMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground() is idempotent; calling it here too keeps the notification current
        // if the service is restarted (START_STICKY) after being killed.
        startForegroundCompat()
        return START_STICKY
    }

    private fun startForegroundCompat() {
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
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(getString(R.string.notification_automation_title))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun checkNetworkState() {
        try {
            val activeNetwork = connectivityManager.activeNetwork
            val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
            val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            // WifiManager.getConnectionInfo() is deprecated since API 31 and throws SecurityException
            // on some OEM builds even with ACCESS_WIFI_STATE declared. SSID is intentionally omitted.
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
                        ?: continue
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
        connectivityManager.unregisterNetworkCallback(networkCallback)
        job.cancel()
    }
}
