package af.shizuku.manager.service

import af.shizuku.manager.MainActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.ShizukuStateMachine
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the manager process alive while the privileged server is RUNNING.
 *
 * <p>Legacy rish clients - MT Manager 2.x and any app that bundles a prebuilt rikka/rish loader -
 * do not use the modern Shizuku API handshake. They send the public
 * rikka.shizuku.intent.action.REQUEST_BINDER broadcast to the Shizuku package and wait only a few
 * hundred milliseconds before starting the shell socket, and the broadcast is answered by
 * af.shizuku.manager.receiver.BinderRequestReceiver in this process. If an OEM freezer (vivo's
 * pem_freezer, MIUI's app freezer, Samsung's sleeping apps, ...) has frozen or killed this process,
 * the broadcast cannot even start it fast enough and the client never obtains shell access.
 *
 * <p>The service is a low-priority foreground service: its only job is to keep the process (and
 * therefore the broadcast receiver) responsive while the server runs. It is started when the state
 * machine reports RUNNING and stopped when the server stops. Controlled by
 * [ShizukuSettings.isLegacyRishKeepaliveEnabled]; on for the Drop-In flavor by default.
 *
 * <p>See docs/MT_COMPAT.md.
 */
class LegacyRishKeepAliveService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        running.set(true)
        createChannel()
        if (!startForegroundSafely(buildNotification())) {
            running.set(false)
            stopSelf()
            return
        }
        // If the server is not (or no longer) running there is nothing to keep alive for.
        if (!ShizukuSettings.isLegacyRishKeepaliveEnabled() ||
            ShizukuStateMachine.get() != ShizukuStateMachine.State.RUNNING
        ) {
            stopSelf()
            return
        }
        stateJob =
            scope.launch {
                ShizukuStateMachine.asFlow()
                    .distinctUntilChanged()
                    .collectLatest { state ->
                        if (state != ShizukuStateMachine.State.RUNNING) {
                            stopSelf()
                        }
                    }
            }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stateJob?.cancel()
        scope.cancel()
        running.set(false)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.legacy_rish_keepalive_channel_name),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "Could not create keep-alive notification channel")
        }
    }

    private fun buildNotification(): Notification {
        val pi =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_visibility_24)
            .setContentTitle(getString(R.string.legacy_rish_keepalive_title))
            .setContentText(getString(R.string.legacy_rish_keepalive_text))
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    // Returns false when the platform refuses the FGS notification so callers can stopSelf()
    // gracefully rather than crash (same pattern as WatchdogService/ShizukuLiveService).
    private fun startForegroundSafely(notif: Notification): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
            true
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "startForeground refused; stopping legacy rish keep-alive service")
            false
        }

    companion object {
        private const val TAG = "LegacyRishKeepAlive"
        private const val CHANNEL_ID = "legacy_rish_keepalive"
        private const val NOTIFICATION_ID = 1466

        private val running = AtomicBoolean(false)

        fun isRunning(): Boolean = running.get()

        /** Starts the keep-alive service if the feature is enabled; safe to call from any thread. */
        fun start(context: Context) {
            if (!ShizukuSettings.isLegacyRishKeepaliveEnabled()) return
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, LegacyRishKeepAliveService::class.java),
                )
            } catch (e: Throwable) {
                // Background FGS start restrictions (or a frozen process) - the next RUNNING
                // transition or app launch retries.
                Timber.tag(TAG).w(e, "Could not start legacy rish keep-alive service")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, LegacyRishKeepAliveService::class.java))
            } catch (e: Throwable) {
                Timber.tag(TAG).w(e, "Could not stop legacy rish keep-alive service")
            }
        }
    }
}
