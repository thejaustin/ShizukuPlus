package af.shizuku.manager.adb
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import af.shizuku.manager.starter.Starter
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.SettingsPage
import af.shizuku.manager.utils.ShizukuStateMachine
import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.sentry.Sentry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

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
        this is EOFException ||
            this is SocketException ||
            this is SocketTimeoutException ||
            this is ConnectException ||
            this is SSLException ||
            this is AdbKeyException ||
            this is AdbAuthPendingException ||
            (includeIllegalState && this is IllegalStateException)

    /**
     * @param attempt the background start this call belongs to (see [AdbAuthWait.scheduleAttempt]);
     * its authorisation prompt is posted as that attempt's. Ignored when [log] is given: an
     * interactive start reports through its log and posts nothing.
     */
    suspend fun startAdb(
        context: Context,
        port: Int,
        log: ((String) -> Unit)? = null,
        activityLogMessage: String? = null,
        attempt: Long = AdbAuthWait.NO_ATTEMPT,
    ) {
        if (port !in 1..65535) {
            Timber.tag(TAG).w("startAdb called with invalid port $port — skipping")
            return
        }

        // Another start is holding adbd's authorisation dialog open; a second connection would
        // raise a second dialog. Throw rather than return: a silent return looked like success,
        // and the caller's next step (a 20 s waitForBinder) then timed out while the pending
        // start was still legitimately waiting its 300 s.
        if (AdbAuthWait.isWaiting()) {
            Timber.tag(TAG).i("startAdb stood down: waiting for the adbd authorisation dialog")
            log?.invoke(context.getString(R.string.wadb_notification_awaiting_auth) + "\n")
            throw AdbAuthPendingException("another start is waiting for the adbd authorisation dialog to be answered")
        }

        // Set when this call stood down because another start owns the authorisation wait; the
        // finally block must then leave wireless debugging alone — it is the owner's transport.
        var stoodDown = false

        suspend fun AdbClient.runCommand(cmd: String) {
            command(cmd) { log?.invoke(String(it)) }
        }

        // Counted from here to the end of the starter command: the authorisation wait alone ends
        // before the server is deployed, and an already-authorised key never waits at all.
        AdbAuthWait.starts.begin()
        try {
            ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
            Timber.tag(TAG).i("startAdb: initiating connection on port %d", port)
            log?.invoke("Starting with wireless adb...\n")

            withContext(Dispatchers.IO) {
                val key =
                    runCatching { AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku+") }
                        .getOrElse {
                            if (it is CancellationException) {
                                throw it
                            } else {
                                throw AdbKeyException(it)
                            }
                        }

                // The background worker owns (and later clears) the ongoing notification; an
                // interactive start reports through its log instead and must not leave one behind.
                val onPending = {
                    if (log != null) {
                        log.invoke(context.getString(R.string.wadb_notification_awaiting_auth) + "\n")
                    } else {
                        ShizukuReceiverStarter.postAuthPrompt(context, attempt)
                    }
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

                        AdbClient("127.0.0.1", activePort, key, onPending).use { client ->
                            connectWithRetry(client, activePort)

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

                AdbClient("127.0.0.1", activePort, key, onPending).use { client ->
                    connectWithRetry(client, activePort)
                    Timber.tag(TAG).i("Connected to ADB at 127.0.0.1:%d; deploying starter command", activePort)
                    log?.invoke("Successfully connected on port $activePort...\n")
                    client.runCommand("shell:${Starter.internalCommand}")
                    runCatching {
                        client.runCommand("shell:cmd appops set ${context.packageName} ACCESS_RESTRICTED_SETTINGS allow")
                        client.runCommand("shell:pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
                    }.onFailure { Timber.tag(TAG).w(it, "Failed to auto-elevate privileges on ADB start") }
                    ShizukuSettings.setLastPort(activePort)
                    AdbAuthWait.clearUnanswered()
                    val msg = activityLogMessage ?: "Service started via ADB on port $activePort"
                    ActivityLogManager.log("Shizuku", context.packageName, msg)
                    ShizukuStateMachine.update()
                    Timber.tag(TAG).i("Shizuku service started successfully via ADB on port %d", activePort)
                }
            }
        } catch (e: Exception) {
            if (e is AdbAuthPendingException) stoodDown = true
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
                            }.setNegativeButton(android.R.string.cancel, null)
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
            AdbAuthWait.starts.end()
            if (!stoodDown && ShizukuSettings.getAutoDisableUsbDebugging() && context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)
            }
        }
    }

    /** @return false only if adbd's authorisation dialog was raised and not accepted. */
    suspend fun stopTcp(
        context: Context,
        port: Int,
    ): Boolean {
        if (port !in 1..65535 || AdbAuthWait.isWaiting()) return true
        val result = runCatching {
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
                    connectWithRetry(client, port)
                    client.command("usb:")
                }
            }
        }
        result.onFailure {
            if (it is CancellationException) throw it
            if (it !is CancellationException && !it.isExpectedAdbError(includeIllegalState = true)) {
                Sentry.captureException(it)
            }
            if (EnvironmentUtils.getAdbTcpPort() > 0) {
                ShizukuStateMachine.update()
                withContext(Dispatchers.Main) {
                    val errorMsg =
                        when (it) {
                            is AdbKeyException -> context.getString(R.string.adb_error_key_store)
                            else -> it.message
                        }
                    Toast
                        .makeText(context, context.getString(R.string.adb_error_stop_tcp) + ". ${errorMsg?.take(80)}", Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
        return result.exceptionOrNull() !is AdbAuthTimeoutException
    }

    private suspend fun connectWithRetry(
        client: AdbClient,
        port: Int,
    ) = coroutineScope {
        // connect() blocks in a socket read that coroutine cancellation cannot interrupt, and it can
        // now wait minutes for the authorisation dialog. Close the socket when this scope is
        // cancelled so a cancelled start does not leave a connection (and the AdbAuthWait gate) held.
        val finished = AtomicBoolean(false)
        val watcher =
            launch {
                try {
                    awaitCancellation()
                } finally {
                    if (!finished.get()) client.close()
                }
            }
        try {
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
                    // A cancelled start closes the socket (see the watcher above), which surfaces
                    // here as an I/O failure; report it as the cancellation it is.
                    ensureActive()
                    Timber.tag(TAG).w(e, "Connection attempt %d/%d failed: %s", attempt, maxAttempts, e.message)
                    if (
                        attempt == maxAttempts ||
                        e is CancellationException ||
                        // Reconnecting would raise another "Allow USB debugging?" dialog.
                        e is AdbAuthTimeoutException ||
                        // A CAS loser must stand down, not reconnect: a retry could claim the
                        // slot the instant its owner releases it and offer a second key, or —
                        // once the owner's key is accepted — start a second server in parallel.
                        e is AdbAuthPendingException
                    ) {
                        throw e
                    }
                }
            }
        } finally {
            finished.set(true)
            watcher.cancel()
        }
    }
}
