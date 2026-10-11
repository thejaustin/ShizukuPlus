package af.shizuku.manager.worker

import af.shizuku.manager.MainActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.AdbAuthPendingException
import af.shizuku.manager.adb.AdbAuthTimeoutException
import af.shizuku.manager.adb.AdbAuthWait
import af.shizuku.manager.adb.AdbMdns
import af.shizuku.manager.adb.AdbPortProber
import af.shizuku.manager.adb.AdbStarter
import af.shizuku.manager.adb.StartNotificationState
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import af.shizuku.manager.settings.BugReportDialogActivity
import af.shizuku.manager.starter.Starter
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.ShizukuStateMachine
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.EOFException
import java.util.concurrent.TimeoutException

class AdbStartWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    // Counted as start work for its whole run, so the tile never settles a start this worker is
    // still making (mDNS discovery, waiting for an unlock, the binder wait).
    override suspend fun doWork(): Result = AdbAuthWait.starts.track { attemptStart() }

    private suspend fun attemptStart(): Result {
        try {
            // Decide before publishing progress, so a start that stands down never shows itself
            // over the start that holds the dialog.
            if (AdbAuthWait.isWaiting()) {
                throw AdbAuthPendingException("another start is waiting for the adbd authorisation dialog to be answered")
            }
            // One dialog per boot or explicit start, enforced where every background connection
            // begins, so it also covers WorkManager's own re-runs and requests admitted long ago.
            // A request nobody made by hand (the watchdog, Home auto-reconnect, a Tasker trigger)
            // stops at any unanswered marker. An explicit one may pass a marker that was already
            // there when it was made (the user asked in spite of it) and keeps that right across
            // retries, but stops at a newer one: its own dialog, or a later one, went unanswered.
            // Read once: 0 means no marker.
            val unansweredAt = AdbAuthWait.unansweredStamp()
            if (unansweredAt != 0L &&
                (!inputData.getBoolean(KEY_EXPLICIT, false) || unansweredAt > inputData.getLong(KEY_REQUESTED_AT, 0L))
            ) {
                throw AdbAuthPendingException("the adbd authorisation dialog went unanswered; waiting for an explicit start")
            }
            timber.log.Timber.tag("AdbStartWorker").i(
                "doWork: runAttempt=%d, isAdbEnabled=%s, tcpMode=%s",
                runAttemptCount,
                EnvironmentUtils.isAdbEnabled(),
                ShizukuSettings.getTcpMode(),
            )
            // Progress travels with this request's WorkInfo, which the shared notification is
            // rendered from; the refresh makes sure this process (perhaps started just for this run)
            // is rendering it.
            setProgress(workDataOf(KEY_STEP to StartNotificationState.Step.STARTING.name))
            ShizukuReceiverStarter.refreshNotification(applicationContext)

            val cr = applicationContext.contentResolver

            // Give the system time to finish initializing after reboot before toggling ADB.
            // Samsung One UI firmware takes longer to stabilize — give it an extra 1.5 s on top
            // of the base 1.5 s delay to reduce the chance the firmware immediately resets
            // adb_wifi_enabled after our first write.
            if (runAttemptCount == 0) {
                if (!EnvironmentUtils.isAdbEnabled()) {
                    val baseDelay =
                        if (
                            Build.MANUFACTURER.equals("samsung", ignoreCase = true)
                        ) {
                            3000L
                        } else {
                            1500L
                        }
                    delay(baseDelay)
                }
            }

            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
            Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)

            // Fast path: if TCP mode is on and the port is already listening, connect directly —
            // no Wireless Debugging or Wi-Fi required.
            if (ShizukuSettings.getTcpMode()) {
                val desiredPort = ShizukuSettings.getTcpPort()
                if (desiredPort in 1..65535) {
                    if (AdbPortProber.isPortOpen(desiredPort, 600)) {
                        AdbStarter.startAdb(
                            applicationContext,
                            desiredPort,
                            activityLogMessage = "Service started via direct TCP port $desiredPort (no Wi-Fi required)",
                        )
                        Starter.waitForBinder()
                        return Result.success()
                    }
                }
            }

            val tcpPort = EnvironmentUtils.getAdbTcpPort()
            if (tcpPort > 0 && !ShizukuSettings.getTcpMode()) {
                if (!AdbStarter.stopTcp(applicationContext, tcpPort)) {
                    // Connecting again for the start would raise the dialog a second time.
                    throw AdbAuthTimeoutException("adbd authorisation was not accepted while leaving TCP mode")
                }
            }

            val savedPort = ShizukuSettings.getLastPort()
            val isWifiOk = !EnvironmentUtils.isWifiRequired() || ShizukuSettings.isForceStartWadbEnabled()

            // force_start_wadb fast-path: probe the default ADB TCP port when no port is already
            // known from the system. Handles devices (e.g. Vivo/FunTouchOS) that block mDNS
            // multicast — if adbd is already listening via persist.adb.tcp.port from a prior
            // session, we connect directly without going through mDNS discovery.
            if (ShizukuSettings.isForceStartWadbEnabled() && tcpPort <= 0 && savedPort <= 0) {
                val probePort = ShizukuSettings.getTcpPort().takeIf { it in 1..65535 } ?: 5555
                if (AdbPortProber.isPortOpen(probePort, 400)) {
                    AdbStarter.startAdb(
                        applicationContext,
                        probePort,
                        activityLogMessage = "Service started via force_start_wadb TCP probe on port $probePort",
                    )
                    Starter.waitForBinder()
                    return Result.success()
                }
            }

            val port =
                when {
                    tcpPort > 0 && isWifiOk -> tcpPort
                    savedPort > 0 && isWifiOk && runAttemptCount == 0 -> savedPort
                    else ->
                        callbackFlow {
                            val adbMdns =
                                AdbMdns(applicationContext, AdbMdns.TLS_CONNECT) { p ->
                                    if (p > 0) trySend(p)
                                }

                            var awaitingAuth = false
                            var timeoutJob: Job? = null
                            var unlockReceiver: BroadcastReceiver? = null
                            // Samsung firmware aggressively resets adb_wifi_enabled to 0 on boot
                            // (observed on One UI 6/7/8). Track how many times we've re-enabled it
                            // so we don't loop forever — after MAX_SAMSUNG_RESETS we fall through to
                            // the normal handleAuth() path.
                            var samsungResetCount = 0
                            val maxSamsungResets =
                                if (
                                    Build.MANUFACTURER.equals("samsung", ignoreCase = true)
                                ) {
                                    4
                                } else {
                                    0
                                }

                            fun startDiscoveryWithTimeout() {
                                adbMdns.start()
                                timeoutJob?.cancel()
                                timeoutJob =
                                    launch {
                                        delay(15_000)
                                        close(TimeoutException("Timed out during mDNS port discovery"))
                                    }
                            }

                            fun handleAuth() {
                                val km = applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                                if (km.isKeyguardLocked) {
                                    val notification = ShizukuReceiverStarter.buildForegroundNotification(applicationContext)
                                    // On Android 14+ (API 34), ForegroundInfo must declare a foreground
                                    // service type (one the manifest lists for SystemForegroundService) or
                                    // the OS throws InvalidForegroundServiceTypeException. specialUse, not
                                    // shortService: the OS stops a shortService after about 3 minutes, and
                                    // this run may wait for the unlock and then for the adbd dialog.
                                    val foregroundInfo =
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                            ForegroundInfo(
                                                ShizukuReceiverStarter.FOREGROUND_NOTIFICATION_ID,
                                                notification,
                                                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                                            )
                                        } else {
                                            ForegroundInfo(ShizukuReceiverStarter.FOREGROUND_NOTIFICATION_ID, notification)
                                        }
                                    // Only once the foreground notification is really up does this
                                    // run's identical progress step aside; if promotion fails, the
                                    // progress stays the one visible sign of the start.
                                    launch {
                                        try {
                                            setForeground(foregroundInfo)
                                            setProgress(workDataOf(KEY_STEP to StartNotificationState.Step.FOREGROUND.name))
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            timber.log.Timber.tag("AdbStartWorker").w(e, "doWork: foreground promotion failed")
                                        }
                                    }

                                    val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
                                    unlockReceiver =
                                        object : BroadcastReceiver() {
                                            override fun onReceive(
                                                context: Context,
                                                intent: Intent,
                                            ) {
                                                if (intent.action == Intent.ACTION_USER_PRESENT) {
                                                    context.unregisterReceiver(this)
                                                    unlockReceiver = null
                                                    Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
                                                }
                                            }
                                        }
                                    ContextCompat.registerReceiver(
                                        applicationContext,
                                        unlockReceiver,
                                        filter,
                                        ContextCompat.RECEIVER_NOT_EXPORTED,
                                    )
                                } else {
                                    awaitingAuth = true
                                }
                                timeoutJob?.cancel()
                                adbMdns.stop()
                            }

                            val observer =
                                object : ContentObserver(null) {
                                    override fun onChange(selfChange: Boolean) {
                                        when (Settings.Global.getInt(cr, "adb_wifi_enabled", 0)) {
                                            0 ->
                                                if (awaitingAuth) {
                                                    close(SecurityException("Network is not authorized for wireless debugging"))
                                                } else if (samsungResetCount < maxSamsungResets) {
                                                    // Samsung firmware reset detected — re-enable wireless
                                                    // debugging with brief exponential backoff rather than
                                                    // falling through to handleAuth() (which stops discovery).
                                                    samsungResetCount++
                                                    launch {
                                                        delay(300L * samsungResetCount)
                                                        Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
                                                    }
                                                } else {
                                                    handleAuth()
                                                }
                                            1 -> startDiscoveryWithTimeout()
                                        }
                                    }
                                }

                            Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
                            cr.registerContentObserver(Settings.Global.getUriFor("adb_wifi_enabled"), false, observer)
                            startDiscoveryWithTimeout()

                            awaitClose {
                                adbMdns.stop()
                                timeoutJob?.cancel()
                                cr.unregisterContentObserver(observer)
                                unlockReceiver?.let { applicationContext.unregisterReceiver(it) }
                            }
                        }.first()
                }

            timber.log.Timber
                .tag("AdbStartWorker")
                .i("doWork: resolved port %d, starting ADB client", port)
            AdbStarter.startAdb(applicationContext, port)
            Starter.waitForBinder()
            timber.log.Timber
                .tag("AdbStartWorker")
                .i("doWork: Shizuku service successfully started and binder ready on port %d", port)

            return Result.success()
        } catch (e: CancellationException) {
            // Whether WorkManager runs this request again (a stop by the system) or not (Cancel,
            // REPLACE) is its decision, and the notification follows it from WorkInfo.
            val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) stopReason else -1
            timber.log.Timber
                .tag("AdbStartWorker")
                .w("doWork: job cancelled (stopReason=%d)", reason)
            throw e
        } catch (e: AdbAuthPendingException) {
            // Another start holds the one authorisation dialog. It owns the state machine and the
            // unanswered marker; stand down without touching either (and without retrying, which
            // would just stand down again).
            timber.log.Timber.tag("AdbStartWorker").i("doWork: stood down: %s", e.message)
            return Result.failure()
        } catch (e: AdbAuthTimeoutException) {
            // Retrying (WorkManager backoff) would open a new connection and raise a new dialog.
            // Stop here; the notification's "Attempt now" or the next explicit start tries again.
            timber.log.Timber.tag("AdbStartWorker").w(e, "doWork: authorisation dialog not answered, not retrying")
            if (ShizukuStateMachine.get() == ShizukuStateMachine.State.STARTING) {
                ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
            }
            // The marker was recorded when the key was offered (AdbClient) and stays: a server
            // that happens to be running did not come from this offer, and its key is still
            // not authorised.
            return Result.failure()
        } catch (e: Exception) {
            timber.log.Timber
                .tag("AdbStartWorker")
                .e(e, "doWork: failed on runAttempt %d: %s", runAttemptCount, e.message)
            val ignored =
                listOf(
                    EOFException::class,
                    SecurityException::class,
                    TimeoutException::class,
                    java.net.ConnectException::class,
                    java.net.SocketException::class,
                    java.net.SocketTimeoutException::class,
                )
            // Only show error notification if it's not a common transient error,
            // or if we've already tried several times and it's still failing.
            if (ignored.none { it.isInstance(e) } || runAttemptCount >= 5) {
                if (e !is SecurityException && e !is TimeoutException) {
                    showErrorNotification(applicationContext, e)
                }
            }

            // Reset STARTING → STOPPED so update() can re-detect the real state.
            // Without this, update() perpetually preserves STARTING (binder never
            // arrived) and every subsequent button click shows "already starting".
            if (ShizukuStateMachine.get() == ShizukuStateMachine.State.STARTING) {
                ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
            }
            if (ShizukuStateMachine.update() == ShizukuStateMachine.State.RUNNING) {
                // A server is up despite the exception (e.g. the connection dropped after the
                // starter command ran). That does not show the key was accepted, so the
                // unanswered marker is left to AdbClient, which clears it on acceptance.
                return Result.success()
            } else {
                // After repeated mDNS timeouts, suggest TCP Mode — the device may be
                // blocking multicast (common on Vivo/FunTouchOS and some corporate Wi-Fi).
                if (e is TimeoutException && runAttemptCount >= 2) {
                    showMdnsBlockedSuggestion(applicationContext)
                }
                return Result.retry()
            }
        }
    }

    private fun Throwable.toUserMessage(context: Context): String =
        when {
            this is java.net.ConnectException || this is java.net.SocketTimeoutException ->
                context.getString(R.string.wadb_error_cannot_connect)
            this is java.util.concurrent.TimeoutException ->
                context.getString(R.string.wadb_error_discovery_timeout)
            this is javax.net.ssl.SSLException ->
                context.getString(R.string.wadb_error_ssl_mismatch)
            this is SecurityException ->
                context.getString(R.string.wadb_error_not_authorized)
            else -> context.getString(R.string.wadb_error_generic_short)
        }

    private fun showMdnsBlockedSuggestion(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.wadb_notification_title),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val openAppIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        val pi =
            PendingIntent.getActivity(
                context,
                20,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(context.getString(R.string.wadb_mdns_blocked_title))
                .setContentText(context.getString(R.string.wadb_mdns_blocked_text))
                .setStyle(
                    NotificationCompat
                        .BigTextStyle()
                        .bigText(context.getString(R.string.wadb_mdns_blocked_text)),
                ).setContentIntent(pi)
                .addAction(0, context.getString(R.string.wadb_mdns_switch_tcp_action), pi)
                .setAutoCancel(true)
                .build()
        nm.notify(NOTIFICATION_ID_MDNS_BLOCKED, notification)
    }

    private fun showErrorNotification(
        context: Context,
        e: Exception,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.wadb_notification_title),
                    NotificationManager.IMPORTANCE_LOW,
                )
            nm.createNotificationChannel(channel)
        }

        val nb = NotificationCompat.Builder(context, CHANNEL_ID)

        val shortMsg = e.toUserMessage(context)
        val devDetail = e.message?.take(120)
        val bigText = if (devDetail != null) "$shortMsg\n\n$devDetail" else shortMsg

        val intent =
            Intent(context, BugReportDialogActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            nb
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(context.getString(R.string.wadb_error_title))
                .setContentText(shortMsg)
                .setContentIntent(pendingIntent)
                .setSilent(true)
                .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
                .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        /**
         * Asks for a background start. Returns at once: whether to enqueue is decided off the main
         * thread from WorkManager's state (nothing happens while a worker is already running), and
         * the notification then shows whatever WorkManager holds.
         */
        fun enqueue(
            context: Context,
            explicit: Boolean = false,
        ) {
            // An early out only: enqueueStart decides again, as one step with the enqueue.
            if (AdbAuthWait.isWaiting()) {
                timber.log.Timber.tag("AdbStartWorker").i("enqueue skipped: waiting for the adbd authorisation dialog")
                return
            }
            // WorkManager uses credential-encrypted storage which is unavailable during direct boot.
            // Skip enqueueing until the user has unlocked their device.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val um = context.getSystemService(android.os.UserManager::class.java)
                if (um != null && !um.isUserUnlocked) return
            }

            val waitsForWifi = EnvironmentUtils.isWifiRequired() && !ShizukuSettings.isForceStartWadbEnabled()
            val cb = Constraints.Builder()
            if (waitsForWifi) {
                cb.setRequiredNetworkType(NetworkType.UNMETERED)
            }
            val request =
                OneTimeWorkRequestBuilder<AdbStartWorker>()
                    .setConstraints(cb.build())
                    .setInputData(workDataOf(KEY_EXPLICIT to explicit, KEY_REQUESTED_AT to AdbAuthWait.clockMs()))
                    .build()
            ShizukuReceiverStarter.enqueueStart(context, request, explicit)
        }

        /** Cancels the start work, queued or running, and dismisses its "not answered" notice. */
        fun cancel(
            context: Context,
            then: (() -> Unit)? = null,
        ) = ShizukuReceiverStarter.cancelStarts(context, then)

        const val UNIQUE_WORK_NAME = "adb_start_worker"

        // The progress key a running worker publishes its StartNotificationState.Step under.
        internal const val KEY_STEP = "step"

        // The request came from the user's own hand (or a path that deliberately allows a new dialog).
        private const val KEY_EXPLICIT = "explicit"
        private const val KEY_REQUESTED_AT = "requested_at"
        const val CHANNEL_ID = "AdbStartWorker"
        const val NOTIFICATION_ID = 1448
        private const val NOTIFICATION_ID_MDNS_BLOCKED = 1449
    }
}
