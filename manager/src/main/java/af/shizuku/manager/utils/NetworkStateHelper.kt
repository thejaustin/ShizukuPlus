package af.shizuku.manager.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

object NetworkStateHelper {

    /**
     * Checks if the device is currently connected to an active Wi-Fi or Ethernet network.
     */
    @JvmStatic
    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Checks if the device has Mobile Hotspot (Tethering AP) enabled.
     * When hotspot is active on Android, the local Wi-Fi interface (e.g. ap0/wlan0) is up,
     * which satisfies Android's requirement for Wireless Debugging even on 5G/cellular data.
     */
    @JvmStatic
    fun isHotspotEnabled(context: Context): Boolean {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val method = wm?.javaClass?.getDeclaredMethod("isWifiApEnabled")
            method?.isAccessible = true
            (method?.invoke(wm) as? Boolean) == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns true if network conditions allow standard Android mDNS Wireless Debugging
     * (either active Wi-Fi connection or local Mobile Hotspot on non-Samsung devices).
     */
    @JvmStatic
    fun isWirelessAdbSupportedNetwork(context: Context): Boolean {
        // On Samsung One UI, Mobile Hotspot disables/blocks Wireless Debugging.
        if (EnvironmentUtils.isSamsung()) {
            return isWifiConnected(context)
        }
        return isWifiConnected(context) || isHotspotEnabled(context)
    }
}
