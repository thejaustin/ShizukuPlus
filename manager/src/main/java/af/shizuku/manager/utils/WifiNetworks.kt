package af.shizuku.manager.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import timber.log.Timber

/**
 * Wi-Fi network names for the trusted-network pickers. Android only gives an app the current SSID
 * with location access, and never the saved list; the shell user can read both through
 * `cmd wifi`, so with the service running nothing has to be typed or granted.
 */
object WifiNetworks {
    data class Snapshot(
        /** The connected network, or null when not on Wi-Fi or it can't be read. */
        val current: String?,
        /** Networks saved on the device, sorted; empty when they can't be read. */
        val saved: List<String>,
        /** False when the service isn't running, so only [current] (via location) was tried. */
        val viaShell: Boolean,
    )

    suspend fun load(context: Context): Snapshot =
        withContext(Dispatchers.IO) {
            val shell = shellAvailable()
            val saved = if (shell) shell("cmd wifi list-networks")?.let(::parseSavedNetworks).orEmpty() else emptyList()
            Snapshot(current(context), saved, shell)
        }

    /** The connected network's name: through location access if granted, else through the shell. */
    suspend fun current(context: Context): String? =
        withContext(Dispatchers.IO) {
            NetworkTrust.currentSsid(context) ?: if (shellAvailable()) shell("cmd wifi status")?.let(::parseStatus) else null
        }

    /** [current]'s shell route for callers already on a background thread. */
    @androidx.annotation.WorkerThread
    fun currentViaShellBlocking(): String? =
        if (shellAvailable()) kotlinx.coroutines.runBlocking { shell("cmd wifi status")?.let(::parseStatus) } else null

    private fun shellAvailable() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** `Wifi is connected to "Home"` (older builds print `SSID: "Home", BSSID: …` instead). */
    internal fun parseStatus(output: String): String? {
        val match =
            Regex("""connected to "(.*)"""").find(output)
                ?: Regex("""SSID: "(.*?)",""").find(output)
        return match
            ?.groupValues
            ?.get(1)
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }

    /**
     * Rows of `Network Id  SSID  Security type`: the id is the first word and the security type
     * the last, so whatever is between them is the name, spaces included.
     */
    internal fun parseSavedNetworks(output: String): List<String> =
        output
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.first().isDigit() }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 3) return@mapNotNull null
                line
                    .substringAfter(parts.first())
                    .substringBeforeLast(parts.last())
                    .trim()
                    .removeSurrounding("\"")
                    .takeIf { it.isNotEmpty() }
            }.distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .toList()

    private suspend fun shell(command: String): String? {
        @Suppress("DEPRECATION")
        val process =
            runCatching { Shizuku.newProcess(arrayOf("sh", "-c", command), null, null) }
                .onFailure { Timber.w(it, "WifiNetworks: %s failed to start", command) }
                .getOrNull() ?: return null
        return coroutineScope {
            // On IO so the timeout can fire even when the caller is runBlocking on its own thread.
            val output = async(Dispatchers.IO) { runCatching { process.inputStream.bufferedReader().use { it.readText() } }.getOrNull() }
            try {
                withTimeoutOrNull(SHELL_TIMEOUT_MS) { output.await() }
            } finally {
                // Inside the scope: destroying the process closes the pipe, which ends the read
                // the scope would otherwise wait on after a timeout or cancellation.
                runCatching { process.destroy() }
            }
        }
    }

    private const val SHELL_TIMEOUT_MS = 5_000L
}
