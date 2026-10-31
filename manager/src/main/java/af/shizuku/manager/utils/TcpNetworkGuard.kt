package af.shizuku.manager.utils

import af.shizuku.manager.R
import af.shizuku.manager.adb.AdbStarter
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.receiver.TcpGuardReceiver
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Enforces the user's TCP network-protection policy (#518).
 *
 * Default is warn-only: on an untrusted network with the ADB TCP port open, post a notification
 * with a "Close port" action. Closing restarts adbd, so doing it unprompted is a separate opt-in
 * ([NetworkTrust.isAutoCloseEnabled]). [enforce] is cheap and idempotent; it runs from the network
 * callback while the process is alive and from the 15-minute watchdog alarm after a freeze.
 */
object TcpNetworkGuard {
    private const val TAG = "TcpNetworkGuard"
    const val NOTIFICATION_ID = 7518

    @Volatile private var registered = false

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities,
            ) = onNetworkChanged()

            override fun onLost(network: Network) = onNetworkChanged()
        }

    private var appContext: Context? = null

    /** Registers the default-network callback once, only when protection is enabled. */
    @Synchronized
    fun sync(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        val wanted = NetworkTrust.mode() != NetworkTrust.MODE_OFF
        if (wanted && !registered) {
            runCatching {
                cm.registerDefaultNetworkCallback(callback)
                registered = true
            }.onFailure { Timber.tag(TAG).w(it, "Failed to register network callback") }
        } else if (!wanted && registered) {
            runCatching { cm.unregisterNetworkCallback(callback) }
            registered = false
            dismiss(ctx)
        }
    }

    private fun onNetworkChanged() {
        appContext?.let { enforce(it) }
    }

    fun enforce(context: Context) {
        val ctx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            // Off the main thread: reading the network name may go through the shell.
            if (!NetworkTrust.shouldBlockTcp(ctx)) {
                dismiss(ctx)
                return@launch
            }
            val port = EnvironmentUtils.getAdbTcpPort()
            val isWirelessAdbOn =
                try {
                    android.provider.Settings.Global
                        .getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1
                } catch (_: Exception) {
                    false
                }
            if (port <= 0 && !isWirelessAdbOn) {
                dismiss(ctx)
                return@launch
            }
            val assessment = NetworkTrust.assess(ctx)
            val effectivePort = if (port > 0) port else 5555
            if (NetworkTrust.isAutoCloseEnabled()) {
                closePort(ctx, port)
            } else {
                notifyOpenPort(ctx, effectivePort, assessment)
            }
        }
    }

    suspend fun closePort(
        context: Context,
        port: Int,
    ) {
        Timber.tag(TAG).i("Closing ADB TCP/wireless on untrusted network")
        ActivityLogManager.log("Shizuku", context.packageName, "Closed ADB port/wireless debugging on untrusted network")
        if (port > 0) {
            AdbStarter.stopTcp(context, port)
        }
        try {
            android.provider.Settings.Global
                .putInt(context.contentResolver, "adb_wifi_enabled", 0)
        } catch (_: Exception) {
        }
        dismiss(context)
    }

    // Older versions posted this as a pop-up notification of its own; that one is removed too.
    private fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        ShizukuIssues.clear(context, ShizukuIssues.KEY_TCP_OPEN)
    }

    private fun notifyOpenPort(
        context: Context,
        port: Int,
        assessment: NetworkTrust.Assessment,
    ) {
        val closeIntent =
            PendingIntent.getBroadcast(
                context,
                NOTIFICATION_ID,
                Intent(context, TcpGuardReceiver::class.java).setAction(TcpGuardReceiver.ACTION_CLOSE).putExtra(TcpGuardReceiver.EXTRA_PORT, port),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val reason =
            context.getString(
                when (assessment.reason) {
                    NetworkTrust.Reason.OPEN_NETWORK -> R.string.tcp_guard_reason_open
                    NetworkTrust.Reason.CAPTIVE_PORTAL -> R.string.tcp_guard_reason_captive
                    else -> R.string.tcp_guard_reason_untrusted
                },
            )
        // Urgent: a debugging port open to strangers is worth an alert, though never a pop-up.
        ShizukuIssues.report(
            context,
            ShizukuIssues.Issue(
                ShizukuIssues.KEY_TCP_OPEN,
                ShizukuIssues.Severity.URGENT,
                context.getString(R.string.tcp_guard_title),
                context.getString(R.string.tcp_guard_text, port, reason),
                listOf(ShizukuIssues.Action(context.getString(R.string.tcp_guard_close), closeIntent)),
            ),
        )
    }
}
