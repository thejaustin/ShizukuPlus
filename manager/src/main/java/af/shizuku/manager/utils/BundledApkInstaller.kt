package af.shizuku.manager.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber

/**
 * Installs an APK bundled in assets through the Shizuku+ server, the way the Compat Hub card does:
 * the bytes are piped to the server's stdin and written to /data/local/tmp, because the shell user
 * can't read this app's own storage (#446).
 */
object BundledApkInstaller {
    /** Returns null on success, or what went wrong (pm's own message when it refuses). */
    @Suppress("DEPRECATION")
    suspend fun install(
        context: Context,
        asset: String,
    ): String? =
        withContext(Dispatchers.IO) {
            val bytes =
                try {
                    context.assets.open(asset).use { it.readBytes() }
                } catch (e: Exception) {
                    Timber.w(e, "%s isn't bundled", asset)
                    return@withContext e.message ?: "missing $asset"
                }
            if (!Shizuku.pingBinder()) return@withContext "Shizuku+ isn't running"
            val tmp = "/data/local/tmp/$asset"
            // rm first: a failed earlier attempt can leave a file the redirect can't overwrite.
            val script = "rm -f $tmp; cat > $tmp && chmod 644 $tmp && pm install -r $tmp 2>&1; echo EXIT:\$?; rm -f $tmp"
            val process =
                try {
                    Shizuku.newProcess(arrayOf("sh", "-c", script), null, null)
                } catch (e: Exception) {
                    return@withContext e.message ?: e.javaClass.simpleName
                }
            try {
                process.outputStream.use { it.write(bytes) }
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                if (output.contains("EXIT:0")) {
                    null
                } else {
                    output
                        .lineSequence()
                        .firstOrNull { it.startsWith("Failure") || it.contains("INSTALL_FAILED") }
                        ?: output.substringBefore("EXIT:").trim().ifBlank { "install failed" }
                }
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            } finally {
                runCatching { process.destroy() }
            }
        }

    fun isInstalled(
        context: Context,
        packageName: String,
    ): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)

    fun isBundled(
        context: Context,
        asset: String,
    ): Boolean = runCatching { context.assets.open(asset).close() }.isSuccess
}
