package af.shizuku.manager.adb
import af.shizuku.manager.R

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.pm.PackageManager
import android.content.Context
import android.provider.Settings
import timber.log.Timber
import android.widget.Toast
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.adb.AdbClient
import af.shizuku.manager.adb.AdbKey
import af.shizuku.manager.adb.PreferenceAdbKeyStore
import af.shizuku.manager.starter.Starter
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.ShizukuStateMachine
import io.sentry.Sentry
import android.app.Activity
import android.content.ContextWrapper
import android.view.ContextThemeWrapper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import af.shizuku.manager.utils.SettingsPage

object AdbStarter {
    private const val TAG = "AdbStarter"

    private fun Context.getActivity(): Activity? {
        var context = this
        while (context is ContextWrapper) {
            if (context is Activity) return context
            context = context.baseContext
        }
        return null
    }

    /** Returns true for transient connection errors that are not bugs and should not go to Sentry. */
    private fun Throwable.isExpectedAdbError(includeIllegalState: Boolean = false) =
        this is EOFException || this is SocketException || this is SocketTimeoutException ||
        this is ConnectException || this is SSLException ||
        this is AdbKeyException || (includeIllegalState && this is IllegalStateException)

    suspend fun startAdb(context: Context, port: Int, log: ((String) -> Unit)? = null) {
        if (port !in 1..65535) {
            Timber.tag(TAG).w("startAdb called with invalid port $port — skipping")
            return
        }
        suspend fun AdbClient.runCommand(cmd: String) {
            command(cmd) { log?.invoke(String(it)) }
        }

        try {
            ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
            Timber.tag(TAG).i("startAdb: initiating connection on port %d", port)
            log?.invoke("Starting with wireless adb...\n")

            withContext(Dispatchers.IO) {
                val key = runCatching { AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku+") }
                    .getOrElse {
                        if (it is CancellationException) throw it
                        else throw AdbKeyException(it)
                    }

                var activePort = port
                val tcpMode = ShizukuSettings.getTcpMode()
                val tcpPort = ShizukuSettings.getTcpPort()
                if (tcpMode && activePort != tcpPort) {
                    if (tcpPort !in 1..65535) {
                        Timber.tag(TAG).w("TCP mode enabled but stored TCP port is invalid ($tcpPort) — skipping TCP redirect")
                    } else {
                        Timber.tag(TAG).d("Switching ADB from port %d to TCP port %d", activePort, tcpPort)
                        log?.invoke("Connecting on port $activePort...")

                        AdbClient("127.0.0.1", activePort, key).use { client ->
                            client.connect()

                            log?.invoke("Successfully connected on port $activePort...")
                            log?.invoke("\nRestarting in TCP mode port: $tcpPort")

                            activePort = tcpPort
                            runCatching {
                                client.command("tcpip:$activePort")
                            }.onFailure { if (it !is EOFException && it !is SocketException) throw it } // Expected when ADB restarts in TCP mode
                        }
                    }
                }

                Timber.tag(TAG).i("Connecting to ADB daemon at 127.0.0.1:%d", activePort)
                log?.invoke("Connecting on port $activePort...")

                AdbClient("127.0.0.1", activePort, key).use { client ->
                    connectWithRetry(client, activePort)
                    Timber.tag(TAG).i("Connected to ADB at 127.0.0.1:%d; deploying starter command", activePort)
                    log?.invoke("Successfully connected on port $activePort...\n")
                    client.runCommand("shell:${Starter.internalCommand}")
                    ShizukuSettings.setLastPort(activePort)
                    ActivityLogManager.log("Shizuku", context.packageName, "Service started via ADB on port $activePort")
                    ShizukuStateMachine.update()
                    Timber.tag(TAG).i("Shizuku service started successfully via ADB on port %d", activePort)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "startAdb failed on port %d: %s", port, e.message)
            if (e is SSLException && (e.message?.contains("protocol version") == true || e is javax.net.ssl.SSLProtocolException)) {
                withContext(Dispatchers.Main) {
                    val activity = context.getActivity()
                    if (activity != null && !activity.isFinishing) {
                        MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.adb_error_ssl_title)
                            .setMessage(R.string.adb_error_ssl_message)
                            .setPositiveButton(R.string.adb_error_ssl_button_reset) { _, _ ->
                                SettingsPage.Developer.Options.launch(activity)
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    } else {
                        // Fallback for non-activity context
                        val themedContext = ContextThemeWrapper(context, R.style.AppTheme)
                        Toast.makeText(themedContext, R.string.adb_error_ssl_message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            if (e !is CancellationException && !e.isExpectedAdbError()) {
                Sentry.captureException(e)
            }
            throw e
        } finally {
            if (ShizukuSettings.getAutoDisableUsbDebugging() && context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED)
                Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)
        }
    }

    suspend fun stopTcp(context: Context, port: Int) {
        if (port !in 1..65535) return
        runCatching {
            val cr = context.contentResolver
            if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
                Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
            }

            if (!EnvironmentUtils.isAdbEnabled()) throw IllegalStateException("ADB is not enabled")

            ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
            val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku+")
            withContext(Dispatchers.IO) {
                AdbClient("127.0.0.1", port, key).use { client ->
                    connectWithRetry(client)
                    client.command("usb:")
                }
            }
        }.onFailure {
            if (it !is CancellationException && !it.isExpectedAdbError(includeIllegalState = true)) {
                Sentry.captureException(it)
            }
            if (EnvironmentUtils.getAdbTcpPort() > 0) {
                ShizukuStateMachine.update()
                withContext(Dispatchers.Main) {
                    val errorMsg = when (it) {
                        is AdbKeyException -> context.getString(R.string.adb_error_key_store)
                        else -> it.message
                    }
                    Toast.makeText(context, context.getString(R.string.adb_error_stop_tcp) + ". ${errorMsg?.take(80)}", Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
    }

    private suspend fun connectWithRetry(client: AdbClient, port: Int) {
        var delayTime = 500L
        val maxAttempts = 8
        for (attempt in 1..maxAttempts) {
            try {
                if (attempt > 1) {
                    delay(delayTime)
                    delayTime = (delayTime * 1.5).toLong().coerceAtMost(3000L) // Exponential backoff up to 3s
                }
                Timber.tag(TAG).d("Connecting to ADB attempt %d/%d (port=%d)", attempt, maxAttempts, port)
                client.connect()
                Timber.tag(TAG).d("Connected successfully on attempt %d", attempt)
                break
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Connection attempt %d/%d failed: %s", attempt, maxAttempts, e.message)
                if (
                    attempt == maxAttempts ||
                    e is CancellationException
                ) throw e
            }
        }
    }
}
