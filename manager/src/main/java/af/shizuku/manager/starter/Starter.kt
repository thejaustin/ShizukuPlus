package af.shizuku.manager.starter

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.ShizukuStateMachine
import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Starter object for launching Shizuku service
 * Uses appContext from ShizukuApplication
 */
object Starter {
    private var context: Context? = null

    private fun getContext(): Context = context ?: throw IllegalStateException("Context not initialized")

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    private val starterFile: File
        get() = File(getContext().applicationInfo.nativeLibraryDir, "libshizuku.so")

    val userCommand: String
        get() {
            val nativeLibDir = getContext().applicationInfo.nativeLibraryDir
            val tcpFlag = if (ShizukuSettings.getTcpMode()) " --tcp" else ""
            return "LD_LIBRARY_PATH=$nativeLibDir ${starterFile.absolutePath}$tcpFlag"
        }

    val adbCommand: String
        get() {
            val baseCmd = "adb shell $userCommand"
            return if (ShizukuSettings.getTcpMode()) "adb tcpip 5555 && $baseCmd" else baseCmd
        }

    val internalCommand: String
        get() = "$userCommand --apk=${getContext().applicationInfo.sourceDir}"

    val serviceStartedMessage: String
        get() = getContext().getString(R.string.starter_service_started)

    suspend fun waitForBinder(log: ((String) -> Unit)? = null) {
        if (ShizukuStateMachine.isRunning()) {
            log?.invoke(serviceStartedMessage)
            return
        }
        log?.invoke("\n" + getContext().getString(R.string.starter_waiting))
        val t0 = System.currentTimeMillis()

        // Use withTimeout to prevent infinite hanging and trigger the error UI in StarterActivity.
        // Actively poll pingBinder() via ShizukuStateMachine.update() rather than only awaiting the
        // sticky OnBinderReceivedListener: on some devices that callback is delayed or missed, which
        // leaves this screen stuck on "Waiting for service…" even though the service is already
        // reachable. update() pings the binder and, when alive, sets the state to RUNNING (also
        // notifying listeners so the home screen refreshes).
        // withTimeoutOrNull: withTimeout's TimeoutCancellationException is a CancellationException,
        // which callers rethrow as a cancellation, so a start that never connected ended without
        // a retry and left STARTING in place.
        val connected =
            withTimeoutOrNull(20_000) {
                while (ShizukuStateMachine.update() != ShizukuStateMachine.State.RUNNING) {
                    delay(250)
                }
                true
            }
        if (connected == null) throw java.util.concurrent.TimeoutException("The service did not connect within 20 s")

        val elapsed = (System.currentTimeMillis() - t0) / 1000.0
        log?.invoke("Connected in ${String.format("%.1f", elapsed)}s")
        log?.invoke(serviceStartedMessage)
    }
}
