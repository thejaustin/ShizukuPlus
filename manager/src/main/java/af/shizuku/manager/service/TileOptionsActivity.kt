package af.shizuku.manager.service

import af.shizuku.manager.MainActivity
import af.shizuku.manager.R
import af.shizuku.manager.starter.Starter
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.worker.AdbStartWorker
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.Shell

/**
 * Transparent dialog activity opened when the user long-presses the Shizuku QS tile.
 * Presents context-sensitive options (Start / Stop / Restart / Open App) based on current state.
 */
class TileOptionsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOptions()
    }

    private fun showOptions() {
        val state = ShizukuStateMachine.get()
        val isRunning = state == ShizukuStateMachine.State.RUNNING
        val isStopped =
            state == ShizukuStateMachine.State.STOPPED ||
                state == ShizukuStateMachine.State.CRASHED
        val isTransient =
            state == ShizukuStateMachine.State.STARTING ||
                state == ShizukuStateMachine.State.STOPPING

        val items: Array<String>
        val actions: List<() -> Unit>

        when {
            isRunning -> {
                items =
                    arrayOf(
                        getString(R.string.tile_action_restart),
                        getString(R.string.tile_action_stop),
                        getString(R.string.tile_action_open_app),
                    )
                actions =
                    listOf(
                        {
                            stopShizuku()
                            startShizuku()
                        },
                        { stopShizuku() },
                        { openApp() },
                    )
            }
            isStopped -> {
                items =
                    arrayOf(
                        getString(R.string.tile_action_start),
                        getString(R.string.tile_action_open_app),
                    )
                actions =
                    listOf(
                        { startShizuku() },
                        { openApp() },
                    )
            }
            isTransient -> {
                items = arrayOf(getString(R.string.tile_action_open_app))
                actions = listOf { openApp() }
            }
            else -> {
                openApp()
                return
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_name)
            .setItems(items) { _, which ->
                actions.getOrNull(which)?.invoke()
                finish()
            }.setOnCancelListener { finish() }
            .show()
    }

    // Same settling as a tap on the tile, so a start from here cannot leave the tile on STARTING.
    private fun startShizuku() {
        ShizukuTileService.cancelSupervision()
        ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
        if (Shell.isAppGrantedRoot() == true) {
            Shell.cmd(Starter.internalCommand).submit {
                if (!it.isSuccess && ShizukuStateMachine.get() == ShizukuStateMachine.State.STARTING) {
                    ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
                }
                ShizukuStateMachine.update()
            }
        } else {
            AdbStartWorker.enqueue(this, explicit = true)
            ShizukuTileService.superviseStart()
        }
    }

    private fun stopShizuku() {
        ShizukuTileService.cancelSupervision()
        ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
        AdbStartWorker.cancel(this)
        kotlin.runCatching { rikka.shizuku.Shizuku.exit() }
        ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
        )
        finish()
    }
}
