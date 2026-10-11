package af.shizuku.manager.service

import af.shizuku.manager.MainActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.AdbAuthWait
import af.shizuku.manager.adb.AdbPortProber
import af.shizuku.manager.starter.Starter
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.worker.AdbStartWorker
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class ShizukuTileService : TileService() {
    private val stateListener: (ShizukuStateMachine.State) -> Unit = { updateTile() }

    override fun onStartListening() {
        super.onStartListening()
        ShizukuStateMachine.addListener(stateListener)
    }

    override fun onStopListening() {
        super.onStopListening()
        ShizukuStateMachine.removeListener(stateListener)
    }

    private fun modeLabel(): String =
        when (ShizukuSettings.getLastLaunchMode()) {
            ShizukuSettings.LaunchMethod.ROOT -> getString(R.string.tile_mode_root)
            ShizukuSettings.LaunchMethod.ADB -> getString(R.string.tile_mode_wifi_adb)
            else -> getString(R.string.tile_mode_adb)
        }

    private fun updateTile() {
        val tile = qsTile ?: return
        val state = ShizukuStateMachine.get()

        tile.state =
            when (state) {
                ShizukuStateMachine.State.RUNNING -> Tile.STATE_ACTIVE
                ShizukuStateMachine.State.STARTING,
                ShizukuStateMachine.State.STOPPING,
                -> Tile.STATE_UNAVAILABLE
                else -> Tile.STATE_INACTIVE
            }
        tile.label = getString(R.string.app_name)
        // subtitle shows in Samsung OneUI 8.5 wide tile text area and standard Android tile secondary text
        tile.subtitle =
            when (state) {
                ShizukuStateMachine.State.RUNNING ->
                    "${modeLabel()} · ${getString(R.string.tile_subtitle_active)}"
                ShizukuStateMachine.State.STARTING -> getString(R.string.tile_subtitle_starting)
                ShizukuStateMachine.State.STOPPING -> getString(R.string.tile_subtitle_stopping)
                ShizukuStateMachine.State.CRASHED -> getString(R.string.tile_subtitle_crashed)
                ShizukuStateMachine.State.STOPPED -> getString(R.string.tile_subtitle_tap_to_start)
            }
        tile.updateTile()
    }

    override fun onClick() {
        val state = ShizukuStateMachine.get()
        try {
            when (state) {
                ShizukuStateMachine.State.RUNNING -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        showRunningOptionsDialog()
                    } else {
                        openApp()
                    }
                }
                ShizukuStateMachine.State.STARTING -> {
                    Toast.makeText(this, getString(R.string.tile_subtitle_starting), Toast.LENGTH_SHORT).show()
                    openApp()
                }
                ShizukuStateMachine.State.STOPPING -> {
                    Toast.makeText(this, getString(R.string.tile_subtitle_stopping), Toast.LENGTH_SHORT).show()
                }
                else -> startShizuku()
            }
        } catch (e: Exception) {
            Toast
                .makeText(
                    this,
                    getString(R.string.tile_state_update_failed, e.localizedMessage),
                    Toast.LENGTH_SHORT,
                ).show()
        }
    }

    internal fun startShizuku() {
        watchdog?.cancel()
        if (Shell.isAppGrantedRoot() == true) {
            ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
            updateTile()
            Shell.cmd(Starter.internalCommand).submit {
                if (!it.isSuccess && ShizukuStateMachine.get() == ShizukuStateMachine.State.STARTING) {
                    ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
                }
                ShizukuStateMachine.update()
                updateTile()
            }
        } else {
            val hasWriteSecure = checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val isWifiOk = !EnvironmentUtils.isWifiRequired() || ShizukuSettings.isForceStartWadbEnabled()
            // Port probes are blocking I/O — move them off the main thread
            CoroutineScope(Dispatchers.IO).launch {
                val hasLoopback =
                    AdbPortProber.isPortOpen(5555, 50) ||
                        (ShizukuSettings.getLastPort() in 1..65535 && AdbPortProber.isPortOpen(ShizukuSettings.getLastPort(), 50))
                withContext(Dispatchers.Main) {
                    if (!hasLoopback && !isWifiOk && !hasWriteSecure) {
                        Toast.makeText(this@ShizukuTileService, R.string.tile_open_app_required, Toast.LENGTH_SHORT).show()
                        openApp()
                        return@withContext
                    }

                    ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
                    updateTile()
                    AdbStartWorker.enqueue(this@ShizukuTileService, explicit = true)
                    superviseStart()
                }
            }
        }
    }

    internal fun stopShizuku() {
        watchdog?.cancel()
        ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
        updateTile()
        AdbStartWorker.cancel(this)
        kotlin.runCatching { rikka.shizuku.Shizuku.exit() }
        ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
        updateTile()
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val intent =
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi =
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            startActivityAndCollapse(pi)
        } else {
            startActivityAndCollapse(intent)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun showRunningOptionsDialog() {
        val items =
            arrayOf(
                getString(R.string.tile_action_restart),
                getString(R.string.tile_action_stop),
                getString(R.string.tile_action_open_app),
            )
        val ctx = android.view.ContextThemeWrapper(this, af.shizuku.manager.R.style.Theme)
        val dialog =
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.app_name)
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> {
                            stopShizuku()
                            startShizuku()
                        }
                        1 -> stopShizuku()
                        2 -> {
                            val pi =
                                PendingIntent.getActivity(
                                    this,
                                    0,
                                    Intent(this, MainActivity::class.java).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    },
                                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                                )
                            startActivityAndCollapse(pi)
                        }
                    }
                }.create()
        showDialog(dialog)
    }

    companion object {
        // Process-wide and touched only on the main thread. A new start or a stop cancels it, so a
        // watchdog left over from an earlier attempt can never settle a later one.
        private var watchdog: Job? = null

        // Covers the gap between enqueue() and the worker starting, during which nothing counts
        // as in flight yet.
        private const val WATCHDOG_GRACE_MS = 25_000L

        /**
         * Keeps the tile from staying frozen on STARTING when nothing will ever settle it — the
         * worker is held back by its Wi-Fi constraint, or enqueue() was skipped. Every start that
         * is actually running settles the state itself (RUNNING via the binder listener, STOPPED
         * from its failure paths) and counts as in flight in [AdbAuthWait.starts] until it has: a
         * start worker for its whole run, an interactive start through its binder wait, any
         * AdbStarter call or authorisation wait. Supervision follows those as events, so a start
         * that begins and ends at any moment restarts the grace; only [WATCHDOG_GRACE_MS] after
         * the last of them ends, still STARTING, does it settle the state from what the server
         * reports. That decision and the transition are one step against a start beginning:
         * a start that begins after the grace ran out either stops the settling or comes after it.
         */
        internal fun superviseStart() {
            watchdog?.cancel()
            watchdog =
                CoroutineScope(Dispatchers.Main).launch {
                    val starting = ShizukuStateMachine.asFlow().map { it == ShizukuStateMachine.State.STARTING }
                    AdbAuthWait.starts.settleWhenStalled(starting, WATCHDOG_GRACE_MS) {
                        if (ShizukuStateMachine.get() != ShizukuStateMachine.State.STARTING) return@settleWhenStalled
                        // State listeners (the tile's included) pick up the transition.
                        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                            ShizukuStateMachine.update()
                        } else {
                            ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
                        }
                    }
                }
        }

        internal fun cancelSupervision() {
            watchdog?.cancel()
        }
    }
}
