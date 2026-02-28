package moe.shizuku.manager.service

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
        if (state == ShizukuStateMachine.State.RUNNING) {
            // If running, open the app to allow manual stop or management
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ requires pending intent for starting activity from tile
                // But TileService.startActivityAndCollapse is usually enough if it's a UI action
                startActivityAndCollapse(intent)
            } else {
                startActivityAndCollapse(intent)
            }
        } else {
            // If not running, attempt to start
            val intent = Intent(this, StarterActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val state = ShizukuStateMachine.get()
        
        if (state == ShizukuStateMachine.State.RUNNING) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = getString(R.string.app_name)
            tile.icon = Icon.createWithResource(this, R.drawable.ic_server_ok_24dp)
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.app_name)
            tile.icon = Icon.createWithResource(this, R.drawable.ic_server_error_24dp)
        }
        
        tile.updateTile()
    }
}
