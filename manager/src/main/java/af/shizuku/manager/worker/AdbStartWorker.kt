package af.shizuku.manager.worker

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.asFlow
import androidx.work.*
import java.io.EOFException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.AdbMdns
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.adb.AdbStarter
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import af.shizuku.manager.receiver.ShizukuReceiverStarter.WorkerState
import af.shizuku.manager.receiver.ShizukuReceiverStarter.updateNotification
import af.shizuku.manager.settings.BugReportDialogActivity
import af.shizuku.manager.starter.Starter
import af.shizuku.manager.adb.AdbPortProber
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.ShizukuStateMachine

class AdbStartWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            updateNotification(
                applicationContext,
                WorkerState.RUNNING
            )

            val cr = applicationContext.contentResolver

            // Give the system ~1.5 s to finish initializing after reboot before toggling ADB.
            // On first attempt with ADB currently disabled, this avoids an immediate connect failure.
            if (runAttemptCount == 0) {
                val adbCurrentlyEnabled = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0)
                if (adbCurrentlyEnabled == 0) delay(1500L)
            }

            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
            Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)

            // Fast path: if TCP mode is on and the port is already listening, connect directly —
            // no Wireless Debugging or Wi-Fi required.
            if (ShizukuSettings.getTcpMode()) {
                val desiredPort = ShizukuSettings.getTcpPort()
                if (desiredPort in 1..65535) {
                    if (AdbPortProber.isPortOpen(desiredPort, 600)) {
                        AdbStarter.startAdb(applicationContext, desiredPort)
                        Starter.waitForBinder()
                        ActivityLogManager.log("Shizuku", applicationContext.packageName,
                            "Service started via direct TCP port $desiredPort (no Wi-Fi required)")
                        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                        nm.cancel(ShizukuReceiverStarter.NOTIFICATION_ID)
                        return Result.success()
                    }
                }
            }

            val tcpPort = EnvironmentUtils.getAdbTcpPort()
            if (tcpPort > 0 && !ShizukuSettings.getTcpMode()) {
                AdbStarter.stopTcp(applicationContext, tcpPort)
            }

            val savedPort = ShizukuSettings.getLastPort()
            val isWifiOk = !EnvironmentUtils.isWifiRequired() || ShizukuSettings.isForceStartWadbEnabled()
            val port = when {
                tcpPort > 0 && isWifiOk -> tcpPort
                savedPort > 0 && isWifiOk && runAttemptCount == 0 -> savedPort
                else -> callbackFlow {
                val adbMdns = AdbMdns(applicationContext, AdbMdns.TLS_CONNECT) { p ->
                    if (p > 0) trySend(p)
                }

                var awaitingAuth = false
                var timeoutJob: Job? = null
                var unlockReceiver: BroadcastReceiver? = null

                fun startDiscoveryWithTimeout() {
                    adbMdns.start()
                    timeoutJob?.cancel()
                    timeoutJob = launch {
                        delay(15_000)
                        close(TimeoutException("Timed out during mDNS port discovery"))
                    }
                }

                fun handleAuth() {
                    val km = applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                    if (km.isKeyguardLocked) {
                        val notification = ShizukuReceiverStarter.buildNotification(
                            applicationContext,
                            null
                        )
                        // On Android 14+ (API 34), ForegroundInfo must declare a foreground
                        // service type or the OS throws InvalidForegroundServiceTypeException
                        val foregroundInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            ForegroundInfo(
                                ShizukuReceiverStarter.NOTIFICATION_ID,
                                notification,
                                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
                            )
                        } else {
                            ForegroundInfo(ShizukuReceiverStarter.NOTIFICATION_ID, notification)
                        }
                        setForegroundAsync(foregroundInfo)

                        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
                        unlockReceiver = object : BroadcastReceiver() {
                            override fun onReceive(context: Context, intent: Intent) {
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
                            ContextCompat.RECEIVER_NOT_EXPORTED
                        )
                    } else awaitingAuth = true
                    timeoutJob?.cancel()
                    adbMdns.stop()
                }

                val observer = object : ContentObserver(null) {
                    override fun onChange(selfChange: Boolean) {
                        when (Settings.Global.getInt(cr, "adb_wifi_enabled", 0)) {
                            0 -> if (awaitingAuth) {
                                close(SecurityException("Network is not authorized for wireless debugging"))
                            } else handleAuth()
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

            AdbStarter.startAdb(applicationContext, port)
            Starter.waitForBinder()
            ActivityLogManager.log("Shizuku", applicationContext.packageName, "Service started via background ADB worker on port $port")

            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(ShizukuReceiverStarter.NOTIFICATION_ID)

            return Result.success()
        } catch (e: CancellationException) {
            val state = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                WorkerState.AWAITING_RETRY
            } else {
                when (stopReason) {
                    WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> WorkerState.AWAITING_WIFI
                    WorkInfo.STOP_REASON_CANCELLED_BY_APP -> WorkerState.STOPPED
                    else -> WorkerState.AWAITING_RETRY
                }
            }
            updateNotification(applicationContext, state)

            throw e
        } catch (e: Exception) {
            val ignored = listOf(
                EOFException::class,
                SecurityException::class,
                TimeoutException::class,
                java.net.ConnectException::class,
                java.net.SocketException::class,
                java.net.SocketTimeoutException::class
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
                return Result.success()
            } else {
                // Show a more informative message when mDNS discovery timed out so users
                // aren't left wondering why the "waiting for WiFi" message appears on WiFi.
                val retryState = if (e is TimeoutException) WorkerState.AWAITING_DISCOVERY else WorkerState.AWAITING_RETRY
                updateNotification(applicationContext, retryState)
                return Result.retry()
            }
        }
    }

    private fun Throwable.toUserMessage(context: Context): String = when {
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

    private fun showErrorNotification(context: Context, e: Exception) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.wadb_notification_title),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val nb = NotificationCompat.Builder(context, CHANNEL_ID)

        val shortMsg = e.toUserMessage(context)
        val devDetail = e.message?.take(120)
        val bigText = if (devDetail != null) "$shortMsg\n\n$devDetail" else shortMsg

        val intent = Intent(context, BugReportDialogActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = nb
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
        fun enqueue(context: Context) {
            // WorkManager uses credential-encrypted storage which is unavailable during direct boot.
            // Skip enqueueing until the user has unlocked their device.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val um = context.getSystemService(android.os.UserManager::class.java)
                if (um != null && !um.isUserUnlocked) return
            }

            val cb = Constraints.Builder()
            if (EnvironmentUtils.isWifiRequired() && !ShizukuSettings.isForceStartWadbEnabled())
                cb.setRequiredNetworkType(NetworkType.UNMETERED)
            val constraints = cb.build()

            val request = OneTimeWorkRequestBuilder<AdbStartWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "adb_start_worker",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
        const val CHANNEL_ID = "AdbStartWorker"
        const val NOTIFICATION_ID = 1448
    }
}
