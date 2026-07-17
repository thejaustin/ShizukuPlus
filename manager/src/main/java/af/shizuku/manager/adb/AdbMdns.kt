package af.shizuku.manager.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import timber.log.Timber
import kotlinx.coroutines.*
import androidx.annotation.RequiresApi
import af.shizuku.manager.ShizukuSettings
import androidx.lifecycle.Observer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket

@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(
    context: Context, private val serviceType: String,
    private val observer: Observer<Int>
) {

    private var registered = false
    private var running = false
    private var serviceName: String? = null

    /**
     * Host the discovered adb service resolved to, for consumers to connect to. Normalized so
     * loopback stays "127.0.0.1" (byte-identical to the previous hard-coded behavior for the common
     * on-device case); only a non-loopback *local-interface* address — as can happen under Android
     * 16+ Local Network Protection, where the adb daemon may advertise a real interface rather than
     * loopback — is surfaced, so pairing connects to the actual host instead of failing on 127.0.0.1.
     */
    @Volatile
    var resolvedHost: String = "127.0.0.1"
        private set
    private val listener = DiscoveryListener(this)
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)
    private val mdnsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var restartJob: Job? = null
    private var restartScheduled = false
    private var attempts = 0

    fun start() {
        if (running) return
        running = true
        if (!registered) {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        restartJob?.cancel()
        mdnsScope.cancel()
        if (registered) {
            nsdManager.stopServiceDiscovery(listener)
        }
    }

    private fun onDiscoveryStart() {
        registered = true
    }

    private fun onDiscoveryStop() {
        registered = false
    }

    private fun onServiceFound(info: NsdServiceInfo) {
        nsdManager.resolveService(info, ResolveListener(this))
    }

    private fun onServiceLost(info: NsdServiceInfo) {
        if (info.serviceName == serviceName) observer.onChanged(-1)
    }

    private fun onServiceResolved(resolvedService: NsdServiceInfo) {
        val hostAddress = resolvedService.host.hostAddress
        val isLocal = hostAddress == "127.0.0.1" || hostAddress == "::1" || resolvedService.host.isLoopbackAddress

        if (running && (isLocal || NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .any { networkInterface ->
                    networkInterface.inetAddresses
                        .asSequence()
                        .any { hostAddress == it.hostAddress }
                })
            && isPortAvailable(resolvedService.port)
        ) {
            serviceName = resolvedService.serviceName
            resolvedHost = if (isLocal || hostAddress == null) "127.0.0.1" else hostAddress
            observer.onChanged(resolvedService.port)
        } else if (running && ShizukuSettings.isAutoReconnectMdnsEnabled() && attempts < 5 && !restartScheduled) {
            attempts++
            restartScheduled = true
            val delayMs = attempts * 1000L
            restartJob = mdnsScope.launch {
                delay(delayMs)
                if (registered) nsdManager.stopServiceDiscovery(listener)
                delay(100L)
                if (!registered) nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                restartScheduled = false
            }
        }
    }

    private fun isPortAvailable(port: Int) = try {
        ServerSocket().use {
            it.bind(InetSocketAddress("127.0.0.1", port), 1)
            false
        }
    } catch (e: IOException) {
        true
    }

    internal class DiscoveryListener(private val adbMdns: AdbMdns) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Timber.tag(TAG).v("onDiscoveryStarted: $serviceType")

            adbMdns.onDiscoveryStart()
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Timber.tag(TAG).v("onStartDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Timber.tag(TAG).v("onDiscoveryStopped: $serviceType")

            adbMdns.onDiscoveryStop()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Timber.tag(TAG).v("onStopDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Timber.tag(TAG).v("onServiceFound: ${serviceInfo.serviceName}")

            adbMdns.onServiceFound(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Timber.tag(TAG).v("onServiceLost: ${serviceInfo.serviceName}")

            adbMdns.onServiceLost(serviceInfo)
        }
    }

    internal class ResolveListener(private val adbMdns: AdbMdns) : NsdManager.ResolveListener {
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, i: Int) {}

        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) {
            adbMdns.onServiceResolved(nsdServiceInfo)
        }

    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TAG = "AdbMdns"
    }
}
