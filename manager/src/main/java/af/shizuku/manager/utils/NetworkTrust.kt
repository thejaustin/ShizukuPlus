package af.shizuku.manager.utils

import af.shizuku.manager.ShizukuSettings
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Build

/**
 * Classifies the current network for TCP-mode protection (#518).
 *
 * Only Wi-Fi/Ethernet LAN exposure matters here: a device on cellular (or its own hotspot) isn't
 * reachable by strangers scanning a shared LAN, so those count as trusted. Open-network and
 * captive-portal detection need no location permission; matching a saved SSID does, and degrades
 * to "unknown SSID" when the platform redacts it.
 */
object NetworkTrust {
    const val MODE_OFF = "off"
    const val MODE_OPEN = "open"
    const val MODE_TRUSTED = "trusted"

    const val KEY_MODE = "tcp_network_protection"
    const val KEY_AUTO_CLOSE = "tcp_protection_auto_close"
    const val KEY_TRUSTED_SSIDS = "tcp_trusted_ssids"

    enum class Verdict { TRUSTED, UNTRUSTED }

    data class Assessment(
        val verdict: Verdict,
        /** Human-readable reason for logs/notifications; null when trusted. */
        val reason: Reason?,
        val ssid: String?,
    )

    enum class Reason { OPEN_NETWORK, CAPTIVE_PORTAL, NOT_TRUSTED }

    @JvmStatic
    fun mode(): String = ShizukuSettings.getPreferences().getString(KEY_MODE, MODE_OFF) ?: MODE_OFF

    @JvmStatic
    fun isAutoCloseEnabled(): Boolean = ShizukuSettings.getPreferences().getBoolean(KEY_AUTO_CLOSE, false)

    @JvmStatic
    fun trustedSsids(): Set<String> = ShizukuSettings.getPreferences().getStringSet(KEY_TRUSTED_SSIDS, emptySet()) ?: emptySet()

    fun setTrustedSsids(ssids: Set<String>) {
        ShizukuSettings
            .getPreferences()
            .edit()
            .putStringSet(KEY_TRUSTED_SSIDS, ssids)
            .apply()
    }

    /** True when Wi-Fi security type can be read (API 31+); below that only captive portals are detectable. */
    @JvmStatic
    fun canDetectOpenNetworks(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** The SSID of the current Wi-Fi network, or null when not on Wi-Fi or the platform redacts it. */
    @JvmStatic
    fun currentSsid(context: Context): String? {
        val fromTransport = wifiInfo(context)?.let { cleanSsid(it.ssid) }
        if (fromTransport != null) return fromTransport
        // On API 31+ the NetworkCapabilities copy is redacted; WifiManager still returns the name
        // to an app holding location access (deprecated, but the only no-callback route).
        @Suppress("DEPRECATION")
        val fromManager =
            runCatching {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                cleanSsid(wm?.connectionInfo?.ssid)
            }.getOrNull()
        timber.log.Timber
            .tag("NetworkTrust")
            .d("SSID via transportInfo=%s, via WifiManager=%s", fromTransport != null, fromManager != null)
        return fromManager
    }

    /**
     * The current SSID through location access, or, off the main thread, through the shell when
     * that access is missing. Networks picked from the device's list must match here too, and
     * nothing asks for location, so without the shell route "Trusted only" never trusted anything.
     */
    @JvmStatic
    fun resolveSsid(context: Context): String? {
        currentSsid(context)?.let { return it }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return null
        // Capability callbacks fire on every signal change; ask the shell once per network.
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val handle = cm.activeNetwork?.networkHandle ?: return null
        val now = android.os.SystemClock.elapsedRealtime()
        shellSsidCache?.let { (cachedHandle, ssid, at) ->
            if (cachedHandle == handle && (ssid != null || now - at < SHELL_MISS_RETRY_MS)) return ssid
        }
        return WifiNetworks.currentViaShellBlocking().also { shellSsidCache = Triple(handle, it, now) }
    }

    /** Network handle, its SSID from the shell (null when it couldn't be read), and when. */
    @Volatile
    private var shellSsidCache: Triple<Long, String?, Long>? = null

    // A failed read (e.g. the service wasn't up yet) is retried after this long.
    private const val SHELL_MISS_RETRY_MS = 30_000L

    @JvmStatic
    fun assess(context: Context): Assessment {
        val caps = activeCapabilities(context) ?: return Assessment(Verdict.TRUSTED, null, null)
        val onLan =
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!onLan) return Assessment(Verdict.TRUSTED, null, null)

        val info = wifiInfo(context)
        val ssid = resolveSsid(context)
        val protectionMode = mode()
        if (ssid != null && ssid in trustedSsids()) return Assessment(Verdict.TRUSTED, null, ssid)

        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
            return Assessment(Verdict.UNTRUSTED, Reason.CAPTIVE_PORTAL, ssid)
        }
        if (isOpenNetwork(info)) return Assessment(Verdict.UNTRUSTED, Reason.OPEN_NETWORK, ssid)
        if (protectionMode == MODE_TRUSTED) return Assessment(Verdict.UNTRUSTED, Reason.NOT_TRUSTED, ssid)
        return Assessment(Verdict.TRUSTED, null, ssid)
    }

    /** True when the current network should not carry an exposed ADB TCP port under the user's policy. */
    @JvmStatic
    fun shouldBlockTcp(context: Context): Boolean = mode() != MODE_OFF && assess(context).verdict == Verdict.UNTRUSTED

    private fun isOpenNetwork(info: WifiInfo?): Boolean {
        if (info == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return info.currentSecurityType == WifiInfo.SECURITY_TYPE_OPEN
    }

    private fun activeCapabilities(context: Context): NetworkCapabilities? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        // Some Android 11 ROMs throw "Package android does not belong to <uid>" here; an unknown
        // network then counts as trusted rather than aborting the start (SHIZUKUPLUS-AD).
        return try {
            cm.getNetworkCapabilities(cm.activeNetwork ?: return null)
        } catch (e: SecurityException) {
            timber.log.Timber.w("getNetworkCapabilities refused: %s", e.message)
            null
        }
    }

    private fun wifiInfo(context: Context): WifiInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return activeCapabilities(context)?.transportInfo as? WifiInfo
    }

    private fun cleanSsid(raw: String?): String? {
        val s = raw?.trim('"') ?: return null
        return s.takeIf { it.isNotEmpty() && s != "<unknown ssid>" }
    }
}
