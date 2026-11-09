package af.shizuku.manager.utils

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.UserManager
import timber.log.Timber

/**
 * Re-attempts the startup connection when Wi-Fi comes back and Shizuku isn't running.
 *
 * Covers the common "phone booted without Wi-Fi, joined it a minute later" case, where the
 * boot-time attempt ran too early for Wireless Debugging. Strictly opt-in via the user's existing
 * "start on boot" choice, never after a deliberate stop ([UserStopFlag]), and it never replaces
 * work already in flight (the start is enqueued with KEEP, see [ShizukuReceiverStarter.start]).
 */
object ReconnectTrigger {
    private const val TAG = "ReconnectTrigger"
    private const val DEBOUNCE_MS = 15_000L

    @Volatile private var lastAttemptAt = 0L

    @Volatile private var registered = false

    /** The Wi-Fi/Ethernet network last seen, so onLost can tell whether it was the LAN that dropped. */
    @Volatile private var lanNetwork: Network? = null

    @Synchronized
    fun install(context: Context) {
        if (registered) return
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(
                        network: Network,
                        caps: NetworkCapabilities,
                    ) {
                        val lan =
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                        if (lan) lanNetwork = network
                        if (lan && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) maybeReconnect(app)
                    }

                    override fun onLost(network: Network) {
                        if (network != lanNetwork) return
                        lanNetwork = null
                        runCatching {
                            af.shizuku.manager.database.ActivityLogManager.log(
                                "Shizuku",
                                app.packageName,
                                "Connection: Wi-Fi lost" + if (ShizukuStateMachine.isRunning()) " while Shizuku was running" else "",
                            )
                        }
                    }
                },
            )
            registered = true
        }.onFailure { Timber.tag(TAG).w(it, "Could not register reconnect trigger") }
    }

    private fun maybeReconnect(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAttemptAt < DEBOUNCE_MS) return
        val um = context.getSystemService(UserManager::class.java)
        val eligible =
            runCatching {
                ShizukuSettings.getStartOnBoot(context) &&
                    !UserStopFlag.isSet() &&
                    !ShizukuStateMachine.isRunning() &&
                    ShizukuStateMachine.get() != ShizukuStateMachine.State.STARTING &&
                    (um == null || um.isUserUnlocked) &&
                    (
                        ShizukuSettings.getLastLaunchMode() != ShizukuSettings.LaunchMethod.ADB ||
                            ShizukuSettings.getTcpMode() ||
                            af.shizuku.manager.utils.EnvironmentUtils
                                .getAdbTcpPort() > 0 ||
                            context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    )
            }.getOrDefault(false)
        if (!eligible) return
        lastAttemptAt = now
        Timber.tag(TAG).i("Wi-Fi available while Shizuku is not running - retrying start")
        runCatching {
            af.shizuku.manager.database.ActivityLogManager
                .log("Shizuku", context.packageName, "Connection: Wi-Fi available, retrying start")
        }
        runCatching { ShizukuReceiverStarter.start(context, forceStart = false) }
            .onFailure { Timber.tag(TAG).w(it, "Reconnect start failed") }
    }
}
