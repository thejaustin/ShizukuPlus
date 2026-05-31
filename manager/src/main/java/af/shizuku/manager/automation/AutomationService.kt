package af.shizuku.manager.automation

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import timber.log.Timber

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

    override fun onCreate() {
        super.onCreate()
        Timber.tag("AutomationService").d("Service created")
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        
        startForegroundAppMonitor()
    }

    private fun checkNetworkState() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wifiManager.connectionInfo
            val ssid = info?.ssid?.removeSurrounding("\"")
            
            val activeNetwork = connectivityManager.activeNetwork
            val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
            val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

            AutomationEngine.dispatchEvent(NetworkEvent(isWifi, if (isWifi) ssid else null), applicationContext)
        } catch (e: Exception) {
            Timber.tag("AutomationService").e(e, "Failed to check network state")
        }
    }

    private var lastForegroundApp: String? = null

    private fun startForegroundAppMonitor() {
        scope.launch {
            while (isActive) {
                try {
                    // Requires UsageStats permission. Alternatively, using Shizuku could be more robust.
                    // For now, we will poll usage stats if available.
                    val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
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
