package af.shizuku.manager.receiver

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.ShizukuIssues
import af.shizuku.manager.utils.TcpNetworkGuard
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles the "Close port" action on the untrusted-network TCP problem (#518) and "Turn on TCP
 * Mode" on the port-discovery one.
 */
class TcpGuardReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        if (intent?.action == ACTION_ENABLE_TCP_MODE) {
            ShizukuSettings.setTcpMode(true)
            ShizukuIssues.clear(context, ShizukuIssues.KEY_PORT_DISCOVERY)
            // A tap on the problem, like its Retry: an explicit start.
            NotifAttemptReceiver.attempt(context.applicationContext)
            return
        }
        if (intent?.action != ACTION_CLOSE) return
        val port = intent.getIntExtra(EXTRA_PORT, -1)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                TcpNetworkGuard.closePort(context.applicationContext, port)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CLOSE = "af.shizuku.manager.TCP_GUARD_CLOSE"
        const val ACTION_ENABLE_TCP_MODE = "af.shizuku.manager.TCP_GUARD_ENABLE_TCP_MODE"
        const val EXTRA_PORT = "port"
    }
}
