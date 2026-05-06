package af.shizuku.manager.starter

import android.content.Context
import androidx.lifecycle.asFlow
import java.io.File
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import af.shizuku.manager.R
import af.shizuku.manager.utils.ShizukuStateMachine

/**
 * Starter object for launching Shizuku service
 * Uses appContext from ShizukuApplication
 */
object Starter {

    private var context: Context? = null
    
    private fun getContext(): Context {
        return context ?: throw IllegalStateException("Context not initialized")
    }
    
    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    private val starterFile: File
        get() = File(getContext().applicationInfo.nativeLibraryDir, "libshizuku.so")

    val userCommand: String
        get() = starterFile.absolutePath

    val adbCommand: String
        get() = "adb shell $userCommand"

    val internalCommand: String
        get() = "$userCommand --apk=${getContext().applicationInfo.sourceDir}"

    val serviceStartedMessage: String
        get() = getContext().getString(R.string.starter_service_started)

    suspend fun waitForBinder(log: ((String) -> Unit)? = null) {
        if (ShizukuStateMachine.isRunning()) {
            log?.invoke(serviceStartedMessage)
            return
        }

        for (attempt in 0..1) {
            if (attempt == 1) log?.invoke(getContext().getString(R.string.starter_retrying))
            try {
                log?.invoke("\n" + getContext().getString(R.string.starter_waiting))
                val t0 = System.currentTimeMillis()
                withTimeout(15_000) {
                    launch {
                        for (remaining in 14 downTo 1) {
                            delay(1_000)
                            log?.invoke(getContext().getString(R.string.starter_countdown, remaining))
                        }
                    }
                    ShizukuStateMachine.asFlow()
                        .first { it == ShizukuStateMachine.State.RUNNING }
                }
                val elapsed = (System.currentTimeMillis() - t0) / 1000.0
                log?.invoke("Connected in ${String.format("%.1f", elapsed)}s")
                log?.invoke(serviceStartedMessage)
                return
            } catch (e: TimeoutCancellationException) {
                if (attempt == 1) throw TimeoutException("Failed to receive binder within 15 seconds")
            }
        }
    }
}
