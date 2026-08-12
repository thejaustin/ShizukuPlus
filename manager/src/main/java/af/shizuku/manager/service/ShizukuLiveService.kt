package af.shizuku.manager.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import af.shizuku.manager.R
import af.shizuku.manager.utils.LiveActivityNotificationManager
import af.shizuku.manager.utils.ShizukuStateMachine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class ShizukuLiveService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        serviceScope.launch {
            ShizukuStateMachine.asFlow().collect { state ->
                val isRunning = state == ShizukuStateMachine.State.RUNNING
                if (isRunning) {
                    val notif = LiveActivityNotificationManager.buildNotification(
                        this@ShizukuLiveService,
                        getString(R.string.live_activity_status_system_bridge_active)
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(LiveActivityNotificationManager.NOTIFICATION_ID, notif,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    } else {
                        startForeground(LiveActivityNotificationManager.NOTIFICATION_ID, notif)
                    }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground() must be called within 5s of startForegroundService().
        // Use current state for the initial notification; the flow in onCreate() will keep it updated.
        val isRunning = ShizukuStateMachine.get() == ShizukuStateMachine.State.RUNNING
        val notif = LiveActivityNotificationManager.buildNotification(
            this,
            getString(
                if (isRunning) {
                    R.string.live_activity_status_system_bridge_active
                } else {
                    R.string.live_activity_status_monitoring
                }
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(LiveActivityNotificationManager.NOTIFICATION_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(LiveActivityNotificationManager.NOTIFICATION_ID, notif)
        }
        // If not running, remove the foreground notification immediately — service stays alive silently.
        if (!isRunning) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        LiveActivityNotificationManager.dismiss(this)
        super.onDestroy()
    }
}
