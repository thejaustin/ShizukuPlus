package moe.shizuku.manager.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import moe.shizuku.manager.ShizukuSettings

class AdbProxyService : Service() {

    companion object {
        private const val TAG = "AdbProxyService"
        const val PROXY_PORT = 15555
    }

    private var isProxyRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ShizukuSettings.isAdbProxyEnabled() && !isProxyRunning) {
            startAdbProxy()
        } else if (!ShizukuSettings.isAdbProxyEnabled() && isProxyRunning) {
            stopAdbProxy()
            stopSelf()
        }
        return START_STICKY
    }

    private fun startAdbProxy() {
        Log.i(TAG, "Starting Local ADB Proxy on port $PROXY_PORT")
        isProxyRunning = true
        // Architectural hook: Here we would initialize a ServerSocket on 127.0.0.1:15555
        // When a connection is received from a legacy app, we parse the ADB packet
        // If it's a 'shell:command', we pass 'command' directly to Shizuku.newProcess()
        // This entirely bypasses the need to have the system Wireless ADB feature on.
    }

    private fun stopAdbProxy() {
        Log.i(TAG, "Stopping Local ADB Proxy")
        isProxyRunning = false
        // Close ServerSocket here
    }

    override fun onDestroy() {
        stopAdbProxy()
        super.onDestroy()
    }
}
