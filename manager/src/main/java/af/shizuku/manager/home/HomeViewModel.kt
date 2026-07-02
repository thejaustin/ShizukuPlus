package af.shizuku.manager.home

import android.content.Context
import android.content.pm.PackageManager
import android.os.Parcel
import android.os.Build
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.MavericksViewModel
import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.ViewModelContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import af.shizuku.manager.BuildConfig
import af.shizuku.manager.Manifest
import af.shizuku.manager.ShizukuApplication
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.AdbMdns
import af.shizuku.manager.model.ServiceStatus
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.Logger.LOGGER
import af.shizuku.manager.utils.SettingsHelper
import af.shizuku.manager.utils.ShizukuSystemApis
import af.shizuku.manager.utils.StockShizukuCompat
import rikka.shizuku.Shizuku
import rikka.shizuku.server.ServerConstants

@Keep
class HomeViewModel(
    initialState: HomeState
) : MavericksViewModel<HomeState>(initialState) {

    // Fetched lazily from application context; avoids a second constructor param that
    // triggers a bytecode register-count VerifyError in release (R8) builds.
    private val appContext: Context = ShizukuApplication.appContext

    private var adbMdns: AdbMdns? = null

    init {
        reload()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && EnvironmentUtils.isTlsSupported()) {
            startAdbPortDiscovery()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun startAdbPortDiscovery() {
        val observer = Observer<Int> { port ->
            setState { copy(discoveredAdbPort = port) }
        }
        adbMdns = AdbMdns(appContext, AdbMdns.TLS_CONNECT, observer).also { it.start() }
    }

    override fun onCleared() {
        super.onCleared()
        adbMdns?.stop()
    }

    fun reload() {
        setState { copy(serviceStatus = Loading()) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val status = loadStatus()
                val companionInstalled = StockShizukuCompat.isStockShizukuInstalled(appContext)
                val compatHubInstalled = StockShizukuCompat.isCompatAppInstalled(appContext)
                val isOriginalRunning = StockShizukuCompat.isOriginalRunning()
                setState { copy(
                    serviceStatus = Success(status),
                    companionInstalled = companionInstalled,
                    compatHubInstalled = compatHubInstalled,
                    isOriginalShizukuRunning = isOriginalRunning
                ) }
            } catch (e: Exception) {
                LOGGER.e(e, "Failed to load Shizuku status")
                setState { copy(serviceStatus = Fail(e)) }
            }
        }
    }

    private fun loadStatus(): ServiceStatus {
        if (!Shizuku.pingBinder()) {
            LOGGER.d("Shizuku binder not available")
            return ServiceStatus()
        }

        val uid = Shizuku.getUid()
        val apiVersion = Shizuku.getVersion()
        // getServerPatchVersion() only reads the bindApplication cache; unlike getVersion()/getUid()
        // it has no direct-binder fallback. The manager's own client doesn't reliably receive that
        // oneway callback, so fall back to a direct binder call to read the running server's patch.
        // Keep unknown as -1 (patch 0 is a legitimate value); the display layer decides how to
        // render "unknown".
        val cachedPatch = Shizuku.getServerPatchVersion()
        val patchVersion = if (cachedPatch >= 0) cachedPatch else queryServerPatchVersion()
        val seContext = if (apiVersion >= 6) {
            try {
                Shizuku.getSELinuxContext()
            } catch (tr: Throwable) {
                LOGGER.w(tr, "getSELinuxContext")
                null
            }
        } else null

        // checkRemotePermission enforceCallingPermission-gates on the caller being an attached
        // client; right after start the manager may not be attached yet, and an unguarded throw
        // here aborts the whole status load (reload() -> Fail -> shown as "not running") even though
        // pingBinder already confirmed the service is up. Treat a failure as "not granted".
        val permissionTest = try {
            Shizuku.checkRemotePermission("android.permission.GRANT_RUNTIME_PERMISSIONS") == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            LOGGER.w(e, "checkRemotePermission")
            false
        }

        try {
            ShizukuSystemApis.checkPermission(Manifest.permission.API_V23, BuildConfig.APPLICATION_ID, 0)
        } catch (e: Exception) {
            LOGGER.w(e, "Permission check failed")
        }

        // pingBinder() above already succeeded, so the service is reachable and running regardless
        // of whether the attach-gated getUid()/getVersion() calls returned valid values.
        return ServiceStatus(uid, apiVersion, patchVersion, seContext, permissionTest, running = true)
    }

    /**
     * Direct binder call for the running server's patch version, mirroring how Shizuku.getVersion()
     * falls back to requireService().getVersion(). Returns -1 if unavailable (e.g. a stock Shizuku
     * server that does not implement this transaction).
     */
    private fun queryServerPatchVersion(): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService")
            val binder = Shizuku.getBinder() ?: return -1
            // transact() returns false when the server doesn't implement this code (e.g. a stock
            // Shizuku server). Treat that as "unknown" rather than calling readException(), which
            // would throw and log noise for an expected outcome.
            if (!binder.transact(ServerConstants.BINDER_TRANSACTION_getServerPatchVersion, data, reply, 0)) {
                return -1
            }
            reply.readException()
            reply.readInt()
        } catch (e: Throwable) {
            LOGGER.w(e, "queryServerPatchVersion failed")
            -1
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun checkBatteryOptimization() {
        if (EnvironmentUtils.isTelevision()) return
        if (!ShizukuSettings.getStartOnBoot(appContext) && !ShizukuSettings.getWatchdog()) return
        val isIgnoring = SettingsHelper.isIgnoringBatteryOptimizations(appContext)
        setState { copy(shouldShowBatteryOptimizationSnackbar = !isIgnoring) }
    }

    fun setEditMode(active: Boolean) {
        setState { copy(isEditMode = active) }
    }

    fun updateGrantedAppCount(count: Int) {
        setState { copy(grantedAppCount = count) }
    }

    @Keep
    companion object : com.airbnb.mvrx.MavericksViewModelFactory<HomeViewModel, HomeState> {
        override fun create(viewModelContext: com.airbnb.mvrx.ViewModelContext, state: HomeState): HomeViewModel {
            return HomeViewModel(state)
        }
    }
}
