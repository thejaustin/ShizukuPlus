package af.shizuku.manager.backup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.ShizukuPlusAPI
import af.shizuku.manager.utils.ShizukuStateMachine
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class BackupViewModel(app: Application) : AndroidViewModel(app) {

    data class AppEntry(
        val packageName: String,
        val label: String,
        val versionName: String,
        val isSystem: Boolean,
        val allowBackup: Boolean,
        val isFrozen: Boolean = false
    )

    sealed class UiState {
        object Loading : UiState()
        data class Loaded(val apps: List<AppEntry>) : UiState()
        data class Error(val msg: String) : UiState()
        object ServiceNotRunning : UiState()
    }

    sealed class BackupEvent {
        data class BackupComplete(val pkg: String, val path: String) : BackupEvent()
        data class FreezeChanged(val pkg: String, val nowFrozen: Boolean) : BackupEvent()
        data class Failure(val msg: String) : BackupEvent()
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state

    private val _events = MutableSharedFlow<BackupEvent>()
    val events: SharedFlow<BackupEvent> = _events

    // Packages currently being backed up — drives per-row busy state in the adapter.
    private val _busyPackages = MutableStateFlow<Set<String>>(emptySet())
    val busyPackages: StateFlow<Set<String>> = _busyPackages

    fun loadApps(includeSystem: Boolean = false) {
        _state.value = UiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            if (ShizukuStateMachine.get() != ShizukuStateMachine.State.RUNNING) {
                _state.value = UiState.ServiceNotRunning
                return@launch
            }
            try {
                val pm = getApplication<Application>().packageManager
                val bundles = ShizukuPlusAPI.BackupRestorePlus.listInstalledPackages(includeSystem)
                val entries = bundles
                    .mapNotNull { b ->
                        val pkg = b.getString("packageName") ?: return@mapNotNull null
                        val label = try {
                            val info = pm.getApplicationInfo(pkg, 0)
                            pm.getApplicationLabel(info).toString()
                        } catch (e: Exception) { pkg }
                        val isFrozen = try {
                            ShizukuPlusAPI.BackupRestorePlus.isAppFrozen(pkg)
                        } catch (e: Exception) { false }
                        AppEntry(
                            packageName = pkg,
                            label = label,
                            versionName = b.getString("versionName") ?: "",
                            isSystem = b.getBoolean("isSystem"),
                            allowBackup = b.getBoolean("allowBackup"),
                            isFrozen = isFrozen
                        )
                    }
                    .sortedBy { it.label.lowercase() }
                _state.value = UiState.Loaded(entries)
            } catch (e: Exception) {
                Timber.e(e, "loadApps failed")
                _state.value = UiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun backupAppData(entry: AppEntry, outputDir: File) {
        val pkg = entry.packageName
        if (pkg in _busyPackages.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _busyPackages.value = _busyPackages.value + pkg
            var prepared = false
            try {
                val pkgDir = File(outputDir, pkg).also { it.mkdirs() }

                ShizukuPlusAPI.BackupRestorePlus.forceStop(pkg)

                // prepareTempDebug is best-effort; Shizuku's privileged API can stream data
                // without debug mode on most paths, so a failure here is not fatal.
                prepared = try {
                    ShizukuPlusAPI.ApkPatcher.prepareTempDebug(pkg)
                } catch (e: Exception) {
                    Timber.w(e, "prepareTempDebug skipped for $pkg, attempting direct backup")
                    false
                }

                var backedUpSomething = false

                val dataPfd = try { ShizukuPlusAPI.ApkPatcher.streamDataDir(pkg) } catch (e: Exception) {
                    Timber.w(e, "streamDataDir failed for $pkg")
                    null
                }
                if (dataPfd != null) {
                    val dataFile = File(pkgDir, "data.tar.gz")
                    dataPfd.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).use { input ->
                            FileOutputStream(dataFile).use { input.copyTo(it) }
                        }
                    }
                    backedUpSomething = true
                }

                val extPfd = try { ShizukuPlusAPI.BackupRestorePlus.backupExternalData(pkg) } catch (e: Exception) {
                    Timber.w(e, "backupExternalData failed for $pkg")
                    null
                }
                if (extPfd != null) {
                    val extFile = File(pkgDir, "external.tar.gz")
                    extPfd.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).use { input ->
                            FileOutputStream(extFile).use { input.copyTo(it) }
                        }
                    }
                    backedUpSomething = true
                }

                if (backedUpSomething) {
                    _events.emit(BackupEvent.BackupComplete(pkg, pkgDir.absolutePath))
                } else {
                    _events.emit(BackupEvent.Failure("No data could be read for $pkg. The app may block backup access."))
                }
            } catch (e: Exception) {
                Timber.e(e, "Backup failed for $pkg")
                _events.emit(BackupEvent.Failure("Backup failed for $pkg: ${e.message}"))
            } finally {
                if (prepared) {
                    try { ShizukuPlusAPI.ApkPatcher.restoreOriginal(pkg) } catch (ex: Exception) {
                        Timber.w(ex, "restoreOriginal failed for $pkg")
                    }
                }
                _busyPackages.value = _busyPackages.value - pkg
            }
        }
    }

    fun toggleFreeze(entry: AppEntry) {
        val pkg = entry.packageName
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val nowFrozen = if (entry.isFrozen) {
                    ShizukuPlusAPI.BackupRestorePlus.unfreezeApp(pkg)
                    false
                } else {
                    ShizukuPlusAPI.BackupRestorePlus.freezeApp(pkg)
                    true
                }
                // Update the frozen state directly in the loaded list.
                val current = _state.value
                if (current is UiState.Loaded) {
                    _state.value = UiState.Loaded(
                        current.apps.map { if (it.packageName == pkg) it.copy(isFrozen = nowFrozen) else it }
                    )
                }
                _events.emit(BackupEvent.FreezeChanged(pkg, nowFrozen))
            } catch (e: Exception) {
                Timber.e(e, "toggleFreeze failed for $pkg")
                _events.emit(BackupEvent.Failure("Freeze toggle failed: ${e.message}"))
            }
        }
    }
}
