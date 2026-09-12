package af.shizuku.manager.adb

import android.content.Context
import af.shizuku.manager.ShizukuSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

object AdbPortProber {

    /**
     * Rapidly probes whether a given port is actively accepting TCP connections on 127.0.0.1.
     * Uses a short timeout (150ms) to ensure UI responsiveness.
     */
    fun isPortOpen(port: Int, timeoutMs: Int = 150): Boolean {
        if (port !in 1..65535) return false
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                true
            }
        } catch (_: IOException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks candidate ports on loopback (127.0.0.1) in order of likelihood:
     * 1. 5555 (Standard ADB TCP/IP port - works offline / over 5G)
     * 2. ShizukuSettings.getLastPort() (Previously successful session port)
     * 3. ShizukuSettings.getTcpPort() (Configured custom TCP port)
     *
     * Returns the first port that responds to a TCP socket connection, or -1 if none.
     */
    suspend fun findActiveLoopbackPort(context: Context? = null): Int = withContext(Dispatchers.IO) {
        val candidates = LinkedHashSet<Int>()

        // 1. Standard ADB TCP port
        candidates.add(5555)

        // 2. Last known port
        val lastPort = ShizukuSettings.getLastPort()
        if (lastPort in 1..65535) {
            candidates.add(lastPort)
        }

        // 3. User configured TCP port
        val tcpPort = ShizukuSettings.getTcpPort()
        if (tcpPort in 1..65535) {
            candidates.add(tcpPort)
        }

        for (port in candidates) {
            if (isPortOpen(port, 150)) {
                return@withContext port
            }
        }

        -1
    }
}
