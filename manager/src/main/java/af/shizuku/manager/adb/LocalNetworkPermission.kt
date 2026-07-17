package af.shizuku.manager.adb

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Android 16+ "Local Network Protection" gates access to the local network — including the mDNS
 * (NsdManager) discovery of the wireless-debugging service and the loopback socket that ADB
 * pairing/connect rely on. On Android 17 this is enforced, so an app that hasn't been granted
 * local-network access can't find or reach the adb service and Shizuku silently fails to
 * start/connect (no authorized apps, privileged actions fail — see #317, mirrors thedjchi's fork).
 *
 * Tiering matches thedjchi's proven fork: Android 17 (SDK 37) gates on ACCESS_LOCAL_NETWORK,
 * Android 16 (SDK 36) on NEARBY_WIFI_DEVICES. ACCESS_LOCAL_NETWORK is an API 36 permission not in
 * the SDK 35 constants, so it's referenced as a string literal.
 */
object LocalNetworkPermission {

    private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    const val REQUEST_CODE = 0x10CA1 // "LOCAl" network

    /** The single runtime permission that gates local-network / mDNS access on this OS, or null. */
    fun required(): String? = when {
        Build.VERSION.SDK_INT >= 37 -> ACCESS_LOCAL_NETWORK
        Build.VERSION.SDK_INT >= 36 -> Manifest.permission.NEARBY_WIFI_DEVICES
        else -> null
    }

    fun granted(context: Context): Boolean {
        val permission = required() ?: return true
        return try {
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        } catch (ignored: Throwable) {
            true
        }
    }

    /**
     * Best-effort request for flows without a result callback (e.g. the ADB-start screen). No-op
     * when the permission is already granted or not applicable to this OS version. Never throws,
     * so it can't break the ADB flow it's trying to enable.
     */
    fun request(activity: Activity, requestCode: Int = REQUEST_CODE) {
        try {
            val permission = required() ?: return
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(arrayOf(permission), requestCode)
            }
        } catch (ignored: Throwable) {
        }
    }
}
