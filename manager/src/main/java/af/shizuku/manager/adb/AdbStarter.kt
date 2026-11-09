package af.shizuku.manager.adb

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import af.shizuku.manager.starter.Starter
import af.shizuku.manager.utils.AppToast
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

    @Volatile var keyOfferInFlight: Boolean = false

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
            this is AdbAuthTimeoutException ||
            (includeIllegalState && this is IllegalStateException)

    /**
     * @param attempt the background start this call belongs to (see [AdbAuthWait.scheduleAttempt]);
     * its authorisation prompt is posted as that attempt's. Ignored when [log] is given: an
     * interactive start reports through its log and posts nothing.
     */
    suspend fun startAdb(
        context: Context,
        port: Int,
        host: String = "127.0.0.1",
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

        var stoodDown = false

        suspend fun AdbClient.runCommand(cmd: String) {
            command(cmd) { log?.invoke(String(it)) }
        }

        // Counted from here to the end of the starter command: the authorisation wait alone ends
        // before the server is deployed, and an already-authorised key never waits at all.
        AdbAuthWait.starts.begin()
        try {
            ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
            Timber.tag(TAG).i("startAdb: initiating connection on %s:%d", host, port)
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
                var activeHost = host
                // An untrusted network (#518) must not get a TCP port opened as a side effect of a
                // Wireless Debugging start, or the protection is undone one step later.
                val tcpMode =
                    ShizukuSettings.getTcpMode() &&
                        !af.shizuku.manager.utils.NetworkTrust
                            .shouldBlockTcp(context)
                val tcpPort = ShizukuSettings.getTcpPort()
                if (tcpMode && activePort != tcpPort) {
                    if (tcpPort !in 1..65535) {
                        Timber.tag(TAG).w("TCP mode enabled but stored TCP port is invalid ($tcpPort) — skipping TCP redirect")
                    } else {
                        Timber.tag(TAG).d("Switching ADB from port %d to TCP port %d", activePort, tcpPort)
                        log?.invoke("Connecting on port $activePort...")

                        connectAdaptive(activeHost, activePort, key, onPending) { client, connectedHost ->
                            log?.invoke("Successfully connected on port $activePort ($connectedHost)...")
                            log?.invoke("\nRestarting in TCP mode port: $tcpPort")

                            activePort = tcpPort
                            activeHost = "127.0.0.1"
                            runCatching {
                                client.command("tcpip:$activePort")
                            }.onFailure { if (it !is EOFException && it !is SocketException) throw it } // Expected when ADB restarts in TCP mode
                        }
                    }
                }

                Timber.tag(TAG).i("Connecting to ADB daemon at %s:%d", activeHost, activePort)
                log?.invoke("Connecting on port $activePort...")

                connectAdaptive(activeHost, activePort, key, onPending) { client, connectedHost ->
                    Timber.tag(TAG).i("Connected to ADB at %s:%d; deploying starter command", connectedHost, activePort)
                    log?.invoke("Successfully connected on port $activePort...\n")
                    client.runCommand("shell:${Starter.internalCommand}")
                    runCatching {
                        client.runCommand("shell:cmd appops set ${context.packageName} ACCESS_RESTRICTED_SETTINGS allow")
                        client.runCommand("shell:pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
                    }.onFailure { Timber.tag(TAG).w(it, "Failed to auto-elevate privileges on ADB start") }
                    if (tcpMode || ShizukuSettings.isOnDeviceAdbTcpEnabled()) {
                        val desiredTcpPort = if (tcpMode) ShizukuSettings.getTcpPort().takeIf { it in 1..65535 } ?: 5555 else 5555
                        runCatching {
                            client.runCommand("shell:setprop service.adb.tcp.port $desiredTcpPort")
                            client.runCommand("shell:setprop persist.adb.tcp.port $desiredTcpPort")
                        }.onFailure { Timber.tag(TAG).w(it, "Failed to persist TCP properties on ADB start") }
                    }
                    ShizukuSettings.setLastPort(activePort)
                    af.shizuku.manager.utils.UserStopFlag
                        .clear()
                    AdbAuthWait.clearUnanswered()
                    val msg = activityLogMessage ?: "Service started via ADB on port $activePort"
                    ActivityLogManager.log("Shizuku", context.packageName, msg)
                    ShizukuStateMachine.update()
                    Timber.tag(TAG).i("Shizuku service started successfully via ADB on %s:%d", connectedHost, activePort)
                }
            }
        } catch (e: Exception) {
            if (e is AdbAuthPendingException) stoodDown = true
            Timber.tag(TAG).e(e, "startAdb failed on port %d: %s", port, e.message)
            if (e !is CancellationException) {
                runCatching {
                    ActivityLogManager.log("Shizuku", context.packageName, "Connection: ADB start failed on port $port (${e.javaClass.simpleName}: ${e.message?.take(80)})")
                }
            }
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
                        AppToast.show(themedContext, themedContext.getString(R.string.adb_error_ssl_message), long = true)
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
        val result =
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
                    connectAdaptive("127.0.0.1", port, key) { client, _ ->
                        client.command("usb:")
                    }
                }
            }
        result.onFailure {
            if (it is CancellationException) throw it
            if (!it.isExpectedAdbError(includeIllegalState = true)) {
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
                    AppToast.show(context, context.getString(R.string.adb_error_stop_tcp) + ". ${errorMsg?.take(80)}", long = true)
                }
            }
        }
        return result.exceptionOrNull() !is AdbAuthTimeoutException
    }

    private fun getCandidateHosts(preferredHost: String): List<String> =
        AdbPortProber.getCandidateHosts(preferredHost)

    private suspend fun connectAdaptive(
        preferredHost: String,
        port: Int,
        key: AdbKey,
        onPending: (() -> Unit)? = null,
        action: suspend (client: AdbClient, connectedHost: String) -> Unit,
    ) = coroutineScope {
        val candidateHosts = getCandidateHosts(preferredHost)
        var delayTime = 400L
        val maxAttempts = 8
        var lastException: Exception? = null

        val finished = AtomicBoolean(false)
        val currentClient =
            java.util.concurrent.atomic
                .AtomicReference<AdbClient?>(null)
        val watcher =
            launch {
                try {
                    awaitCancellation()
                } finally {
                    if (!finished.get()) {
                        currentClient.get()?.close()
                    }
                }
            }

        try {
            for (attempt in 1..maxAttempts) {
                if (attempt > 1) {
                    delay(delayTime)
                    delayTime = (delayTime * 1.4).toLong().coerceAtMost(2500L)
                }

                for (targetHost in candidateHosts) {
                    val client = AdbClient(targetHost, port, key, onPending)
                    currentClient.set(client)
                    try {
                        Timber.tag(TAG).d("Connecting to ADB attempt %d/%d (host=%s, port=%d)", attempt, maxAttempts, targetHost, port)
                        client.connect()
                        Timber.tag(TAG).i("Connected to ADB at %s:%d on attempt %d", targetHost, port, attempt)
                        client.use {
                            action(it, targetHost)
                        }
                        return@coroutineScope
                    } catch (e: Exception) {
                        ensureActive()
                        client.close()
                        lastException = e
                        if (e is CancellationException) throw e
                        if (e is AdbAuthTimeoutException || e is AdbAuthPendingException) throw e
                        if (e is ConnectException || e is java.net.NoRouteToHostException) {
                            Timber.tag(TAG).d("Host %s refused port %d, checking alternate interface...", targetHost, port)
                            continue
                        } else {
                            break
                        }
                    }
                }
            }
            throw lastException ?: ConnectException("Could not connect to ADB daemon on port $port across candidate hosts $candidateHosts")
        } finally {
            finished.set(true)
            watcher.cancel()
        }
    }
}
