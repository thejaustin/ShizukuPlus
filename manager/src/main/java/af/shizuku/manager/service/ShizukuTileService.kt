package af.shizuku.manager.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import af.shizuku.manager.MainActivity
import af.shizuku.manager.utils.ShizukuStateMachine

import androidx.work.WorkManager

class ShizukuTileService : TileService() {

    private val stateListener: (ShizukuStateMachine.State) -> Unit = {
        updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        ShizukuStateMachine.addListener(stateListener)
    }

    override fun onStopListening() {
        super.onStopListening()
        ShizukuStateMachine.removeListener(stateListener)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val state = ShizukuStateMachine.get()
        val isRunning = state == ShizukuStateMachine.State.RUNNING
        val isStarting = state == ShizukuStateMachine.State.STARTING

        tile.state = when {
            isRunning -> Tile.STATE_ACTIVE
            isStarting -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = when {
            isRunning -> "Shizuku: Active"
            isStarting -> "Starting..."
            else -> "Shizuku: Off"
        }
        tile.subtitle = when {
            isRunning -> "Running"
            isStarting -> "Please wait"
            else -> "Tap to Start"
        }
        tile.updateTile()
    }

    override fun onClick() {
        val state = ShizukuStateMachine.get()
        val isRunning = state == ShizukuStateMachine.State.RUNNING
        val isStarting = state == ShizukuStateMachine.State.STARTING

        try {
            if (isRunning || isStarting) {
                // Stop Shizuku / cancel WADB worker
                ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
                updateTile()

                // Cancel worker
                WorkManager.getInstance(this).cancelUniqueWork("adb_start_worker")

                // Stop server if running
                kotlin.runCatching { rikka.shizuku.Shizuku.exit() }

                ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
                updateTile()
            } else {
                // Attempt to start silently if root is available
                if (com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true) {
                    ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
                    updateTile()
                    com.topjohnwu.superuser.Shell.cmd(af.shizuku.manager.starter.Starter.internalCommand)
                        .submit {
                            ShizukuStateMachine.update()
                            updateTile()
                        }
                } else {
                    // Attempt to start WADB via Worker
                    ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
                    updateTile()
                    af.shizuku.manager.worker.AdbStartWorker.enqueue(this)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to update Shizuku state: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }
}
