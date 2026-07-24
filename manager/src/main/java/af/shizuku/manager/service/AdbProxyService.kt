package af.shizuku.manager.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.EnvironmentUtils
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * Local ADB-bypass proxy that accepts connections on 127.0.0.1:15555.
 *
 * This provides a simple line-based command protocol (NOT the ADB wire protocol)
 * for on-device apps to send privileged shell commands via Shizuku.
 * Each line sent is treated as a shell command; output lines are sent back.
 * Connection ends when client closes the socket or sends "exit".
 *
 * For real ADB tool compatibility without WiFi, use [enableAdbTcp] which
 * configures adbd to listen on TCP/IP via Shizuku's privileged shell.
 */
class AdbProxyService : Service() {

    companion object {
        private const val TAG = "AdbProxyService"
        const val PROXY_PORT = 15555
        private const val MAX_CMD_LEN = 8192
        private const val TIMEOUT_MS = 30_000 // 30s per command

        private fun execShellCommand(cmd: Array<String>): Boolean {
            // Entire body in try-catch: pingBinder() can throw IllegalStateException on some
            // vendor binder states (race between connected check and actual call).
            return try {
                if (Shizuku.pingBinder()) {
                    val p = Shizuku.newProcess(cmd, null, null)
                    try {
                        p.waitFor() == 0
                    } finally {
                        // waitFor() can throw if the binder dies mid-command; destroy in finally
                        // so the process handle doesn't leak on that path.
                        try { p.destroy() } catch (_: Exception) {}
                    }
                } else if (com.topjohnwu.superuser.Shell.getShell().isRoot) {
                    com.topjohnwu.superuser.Shell.cmd(*cmd).exec().isSuccess
                } else {
                    false
                }
            } catch (e: Exception) { false }
        }

        /** Configures adbd TCP mode via Shizuku or Root. */
        fun enableAdbTcp(port: Int = 5555): Boolean {
            if (!Shizuku.pingBinder() && !com.topjohnwu.superuser.Shell.getShell().isRoot) {
                Timber.tag(TAG).w("Shizuku service is not running and no root - cannot enable adbd TCP mode")
                return false
            }
            return try {
                // Step 1: Set the TCP port property
                execShellCommand(arrayOf("setprop", "service.adb.tcp.port", port.toString()))

                // Also set the persistent property so TCP mode survives reboots
                execShellCommand(arrayOf("setprop", "persist.adb.tcp.port", port.toString()))

                // Step 2: Restart adbd — try multiple approaches for vendor compatibility
                // Primary: ctl.restart is the most compatible init signal (works on AOSP, Samsung, Xiaomi)
                val restartViaCtl = execShellCommand(arrayOf("setprop", "ctl.restart", "adbd"))

                if (!restartViaCtl || EnvironmentUtils.isSamsung()) {
                    // Samsung specific: sometimes ctl.restart is ignored, toggling the property forces a restart
                    execShellCommand(arrayOf("setprop", "adb.network.port", port.toString()))

                    // Fallback A: explicit stop/start (AOSP init services)
                    val stopped = execShellCommand(arrayOf("stop", "adbd"))
                    if (stopped) {
                        execShellCommand(arrayOf("start", "adbd"))
                    } else {
                        // Fallback B: pkill lets init auto-restart the daemon
                        execShellCommand(arrayOf("pkill", "adbd"))
                    }
                }

                Timber.tag(TAG).i("adbd TCP mode enabled on port $port")
                true
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to enable adbd TCP mode")
                false
            }
        }

        /** Disables adbd TCP mode, reverting to USB only. */
        fun disableAdbTcp(): Boolean {
            if (!Shizuku.pingBinder() && !com.topjohnwu.superuser.Shell.getShell().isRoot) {
                Timber.tag(TAG).w("Shizuku service is not running and no root - cannot disable adbd TCP mode")
                return false
            }
            return try {
                // Set port to -1 (disabled) and restart adbd
                execShellCommand(arrayOf("setprop", "service.adb.tcp.port", "-1"))

                // Clear the persistent property too
                execShellCommand(arrayOf("setprop", "persist.adb.tcp.port", ""))

                // Use same multi-fallback restart
                val restarted = execShellCommand(arrayOf("setprop", "ctl.restart", "adbd"))
                if (!restarted) {
                    execShellCommand(arrayOf("stop", "adbd"))
                    execShellCommand(arrayOf("start", "adbd"))
                }
                Timber.tag(TAG).i("adbd TCP mode disabled")
                true
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to disable adbd TCP mode")
                false
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
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
        Timber.tag(TAG).i("Starting Local Command Proxy on 127.0.0.1:$PROXY_PORT")
        isProxyRunning = true
        serverJob = serviceScope.launch {
            runCatching {
                // Bind only to loopback — never exposed to network
                val socket = ServerSocket(PROXY_PORT, 8, InetAddress.getByName("127.0.0.1"))
                socket.soTimeout = 0 // Block indefinitely waiting for connections
                serverSocket = socket
                Timber.tag(TAG).i("Proxy listening on 127.0.0.1:$PROXY_PORT")
                while (isActive) {
                    try {
                        val client = socket.accept()
                        launch { handleClient(client) }
                    } catch (e: SocketException) {
                        if (isActive) Timber.tag(TAG).e(e, "Server socket error")
                        break
                    }
                }
            }.onFailure { e ->
                Timber.tag(TAG).e(e, "Proxy failed to start")
                isProxyRunning = false
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use {
            socket.soTimeout = 0
            try {
                val handler = af.shizuku.manager.adb.FakeAdbClientHandler(this@AdbProxyService, socket)
                handler.loop()
            } catch (e: SocketException) {
                // Client disconnected — normal
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Client error")
            }
        }
    }

    private fun stopAdbProxy() {
        Timber.tag(TAG).i("Stopping Local Command Proxy")
        isProxyRunning = false
        serverJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    override fun onDestroy() {
        stopAdbProxy()
        serviceScope.cancel()
        super.onDestroy()
    }
}
