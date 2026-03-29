package moe.shizuku.manager.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.starter.StarterActivity
import moe.shizuku.manager.utils.ShizukuStateMachine

@RequiresApi(Build.VERSION_CODES.N)
class ShizukuTileService : TileService() {

    private val stateListener: (ShizukuStateMachine.State) -> Unit = {
        updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        ShizukuStateMachine.addListener(stateListener)
        updateTile()
    }

    override fun onStopListening() {
        super.onStopListening()
        ShizukuStateMachine.removeListener(stateListener)
    }

    override fun onClick() {
        val state = ShizukuStateMachine.get()
        val intent = if (state == ShizukuStateMachine.State.RUNNING) {
            Intent(this, MainActivity::class.java)
        } else {
            Intent(this, StarterActivity::class.java)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val state = ShizukuStateMachine.get()
        
        if (state == ShizukuStateMachine.State.RUNNING) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = getString(R.string.app_name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.home_status_service_running_tile_sublabel)
            }
            tile.icon = Icon.createWithResource(this, R.drawable.ic_server_ok_24dp)
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.app_name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.home_status_service_stopped_tile_sublabel)
            }
            tile.icon = Icon.createWithResource(this, R.drawable.ic_server_error_24dp)
        }
        
        tile.updateTile()
    }
}
