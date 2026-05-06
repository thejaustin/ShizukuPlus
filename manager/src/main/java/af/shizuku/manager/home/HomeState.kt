package af.shizuku.manager.home

import com.airbnb.mvrx.MavericksState
import com.airbnb.mvrx.Async
import com.airbnb.mvrx.Uninitialized
import af.shizuku.manager.model.ServiceStatus

data class HomeState(
    val serviceStatus: Async<ServiceStatus> = Uninitialized,
    val shouldShowBatteryOptimizationSnackbar: Boolean = false,
    val grantedAppCount: Int = 0,
    val isEditMode: Boolean = false,
    // Port discovered via mDNS TLS_CONNECT; -1 = not yet found
    val discoveredAdbPort: Int = -1,
    val companionInstalled: Boolean = false
) : MavericksState
