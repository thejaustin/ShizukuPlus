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

        /** Configures adbd TCP mode via Shizuku. Requires Shizuku with root. */
        fun enableAdbTcp(port: Int = 5555): Boolean {
            if (!Shizuku.pingBinder()) {
                Timber.tag(TAG).w("Shizuku service is not running - cannot enable adbd TCP mode")
                return false
            }
            return try {
                // Step 1: Set the TCP port property
                Shizuku.newProcess(
                    arrayOf("setprop", "service.adb.tcp.port", port.toString()), null, null
                ).waitFor()

                // Also set the persistent property so TCP mode survives reboots
                runCatching {
                    Shizuku.newProcess(
                        arrayOf("setprop", "persist.adb.tcp.port", port.toString()), null, null
                    ).waitFor()
                }

                // Step 2: Restart adbd — try multiple approaches for vendor compatibility
                // Primary: ctl.restart is the most compatible init signal (works on AOSP, Samsung, Xiaomi)
                val restartViaCtl = runCatching {
                    Shizuku.newProcess(
                        arrayOf("setprop", "ctl.restart", "adbd"), null, null
                    ).waitFor()
                }.isSuccess

                if (!restartViaCtl || EnvironmentUtils.isSamsung()) {
                    // Samsung specific: sometimes ctl.restart is ignored, toggling the property forces a restart
                    runCatching {
                        Shizuku.newProcess(arrayOf("setprop", "adb.network.port", port.toString()), null, null).waitFor()
                    }
                    
                    // Fallback A: explicit stop/start (AOSP init services)
                    val stopped = runCatching {
                        Shizuku.newProcess(arrayOf("stop", "adbd"), null, null).waitFor()
                    }.isSuccess
                    if (stopped) {
                        Shizuku.newProcess(arrayOf("start", "adbd"), null, null).waitFor()
                    } else {
                        // Fallback B: pkill lets init auto-restart the daemon
                        Shizuku.newProcess(arrayOf("pkill", "adbd"), null, null).waitFor()
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
            if (!Shizuku.pingBinder()) {
                Timber.tag(TAG).w("Shizuku service is not running - cannot disable adbd TCP mode")
                return false
            }
            return try {
                // Set port to -1 (disabled) and restart adbd
                Shizuku.newProcess(
                    arrayOf("setprop", "service.adb.tcp.port", "-1"), null, null
                ).waitFor()

                // Clear the persistent property too
                runCatching {
                    Shizuku.newProcess(
                        arrayOf("setprop", "persist.adb.tcp.port", ""), null, null
                    ).waitFor()
                }

                // Use same multi-fallback restart
                val restarted = runCatching {
                    Shizuku.newProcess(
                        arrayOf("setprop", "ctl.restart", "adbd"), null, null
                    ).waitFor()
                }.isSuccess
                if (!restarted) {
                    runCatching { Shizuku.newProcess(arrayOf("stop", "adbd"), null, null).waitFor() }
                    runCatching { Shizuku.newProcess(arrayOf("start", "adbd"), null, null).waitFor() }
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
        val coroutineContext = currentCoroutineContext()
        socket.use {
            socket.soTimeout = TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = PrintWriter(java.io.OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
            writer.println("SHIZUKU_PROXY/1.0 READY")
            try {
                while (coroutineContext.isActive) {
                    // Check if ready to read to allow cancellation to interrupt the blocking read
                    while (coroutineContext.isActive && !reader.ready()) {
                        kotlinx.coroutines.delay(50)
                    }
                    if (!coroutineContext.isActive) break
                    
                    val currentLine = reader.readLine() ?: break
                    val cmd = currentLine.trim()
                    if (cmd.isEmpty()) continue
                    if (cmd == "exit" || cmd == "quit") break
                    if (cmd.length > MAX_CMD_LEN) {
                        writer.println("ERROR: command too long")
                        continue
                    }
                    runCommand(cmd, writer)
                }
            } catch (e: SocketException) {
                // Client disconnected — normal
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Client error")
            }
        }
    }

    private suspend fun runCommand(cmd: String, writer: PrintWriter) {
        val coroutineContext = currentCoroutineContext()
        var process: java.lang.Process? = null
        try {
            process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val stdout = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            val stderr = BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8))
            
            // Launch concurrent readers so we don't block on just stdout
            kotlinx.coroutines.coroutineScope {
                launch {
                    stdout.forEachLine { if (isActive) writer.println(it) }
                }
                launch {
                    stderr.forEachLine { if (isActive) writer.println("ERR: $it") }
                }
            }
            
            val exit = process.waitFor()
            if (coroutineContext.isActive) writer.println("EXIT:$exit")
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                Timber.tag(TAG).e(e, "Command failed: $cmd")
                writer.println("ERROR: ${e.message}")
                writer.println("EXIT:1")
            } else {
                throw e
            }
        } finally {
            process?.destroy()
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
