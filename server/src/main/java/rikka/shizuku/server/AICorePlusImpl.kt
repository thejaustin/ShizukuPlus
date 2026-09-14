package rikka.shizuku.server

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import af.shizuku.server.IAICorePlus

/**
 * Implementation of AICorePlus using Android's SurfaceControl and AI framework APIs.
 */
class AICorePlusImpl(
    private val clientManager: ShizukuClientManager,
    private val service: ShizukuService
) : IAICorePlus.Stub() {
    companion object {
        private const val TAG = "AICorePlusImpl"
    }

    private var automationBridge: af.shizuku.server.IAIAutomationBridge? = null

    fun setAutomationBridge(bridge: af.shizuku.server.IAIAutomationBridge?) {
        this.automationBridge = bridge
    }

    private fun checkExperimental(): Boolean {
        if (!service.isPlusFeatureEnabled("ai_core_experimental")) {
            Log.w(TAG, "Experimental AI Core features are disabled.")
            return false
        }
        return true
    }

    override fun getPixelColor(x: Int, y: Int): Int {
        if (!checkExperimental()) return Color.TRANSPARENT
        return try {
            automationBridge?.getPixelColor(x, y) ?: getPixelColorViaSurfaceControl(x, y)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get pixel color", e)
            Color.TRANSPARENT
        }
    }

    private fun getPixelColorViaSurfaceControl(x: Int, y: Int): Int {
        return try {
            val displayToken = getDisplayToken() ?: return Color.TRANSPARENT
            val screenshotMethod = Class.forName("android.view.SurfaceControl")
                .getMethod("screenshot", IBinder::class.java, Rect::class.java, Int::class.java, Int::class.java)

            val crop = Rect(x, y, x + 1, y + 1)
            val bitmap = screenshotMethod.invoke(null, displayToken, crop, 1, 1) as? Bitmap
            val color = bitmap?.getPixel(0, 0) ?: Color.TRANSPARENT
            bitmap?.recycle()
            color
        } catch (e: Exception) {
            Color.TRANSPARENT
        }
    }

    override fun scheduleNPULoad(taskData: Bundle?): Bundle? {
        if (!service.isPlusFeatureEnabled("ai_core_master") || !service.isPlusFeatureEnabled("npu_acceleration")) return null
        if (taskData == null) return null
        
        try {
            // android.provider.Settings.System.putInt(service.contentResolver, "processing_speed", 2)
            val response = Bundle()
            response.putBoolean("success", true)
            return response
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule NPU task", e)
            return null
        }
    }

    override fun captureLayer(p0: Int): Bitmap? {
        if (!checkExperimental()) return null
        return try {
            automationBridge?.captureLayer(p0) ?: captureLayerViaSurfaceControl(p0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture layer", e)
            null
        }
    }

    private fun captureLayerViaSurfaceControl(layerId: Int): Bitmap? {
        return try {
            val displayToken = getDisplayToken() ?: return null
            val screenshotMethod = Class.forName("android.view.SurfaceControl")
                .getMethod("screenshot", IBinder::class.java, Rect::class.java, Int::class.java, Int::class.java)
            screenshotMethod.invoke(null, displayToken, null, 0, 0) as? Bitmap
        } catch (e: Exception) {
            null
        }
    }

    override fun getSystemContext(): Bundle? {
        val bundle = Bundle()
        bundle.putString("ai_core_version", "2.0")
        bundle.putBoolean("npu_available", true)
        bundle.putString("android_version", android.os.Build.VERSION.RELEASE)
        bundle.putInt("sdk_int", android.os.Build.VERSION.SDK_INT)
        return bundle
    }

    private var inputShellProcess: Process? = null
    private var inputShellWriter: java.io.OutputStreamWriter? = null

    private fun injectInput(cmd: String): Boolean {
        return try {
            synchronized(this) {
                if (inputShellProcess == null) { // Simple alive check
                    try { inputShellProcess?.exitValue() } catch (e: IllegalThreadStateException) { /* Alive */ }
                }
                if (inputShellProcess == null) {
                    val proc = Runtime.getRuntime().exec("sh")
                    inputShellProcess = proc
                    inputShellWriter = java.io.OutputStreamWriter(proc.outputStream)
                }
                inputShellWriter?.write("$cmd\n")
                inputShellWriter?.flush()
            }
            true
        } catch (e: Exception) {
            synchronized(this) {
                inputShellProcess?.destroy()
                inputShellProcess = null
                inputShellWriter = null
            }
            false
        }
    }

    override fun simulateTouch(p0: Float, p1: Float): Boolean {
        if (!checkExperimental()) return false
        return try {
            automationBridge?.simulateTouch(p0, p1) ?: injectInput("input tap $p0 $p1")
        } catch (e: Exception) {
            false
        }
    }

    override fun simulateSwipe(p0: Float, p1: Float, p2: Float, p3: Float, p4: Int): Boolean {
        if (!checkExperimental()) return false
        return try {
            automationBridge?.simulateSwipe(p0, p1, p2, p3, p4) ?: injectInput("input swipe $p0 $p1 $p2 $p3 $p4")
        } catch (e: Exception) {
            false
        }
    }

    override fun simulateText(text: String?): Boolean {
        if (!checkExperimental() || text == null) return false
        return try {
            // argv exec avoids the persistent `sh` pipe so untrusted text can't break out of shell quoting
            automationBridge?.simulateText(text) ?: run {
                Runtime.getRuntime().exec(arrayOf("input", "text", text)).waitFor()
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun getWindowHierarchy(): String? {
        if (!service.isPlusFeatureEnabled("ai_core_master") || !service.isPlusFeatureEnabled("native_window_crawler")) return ""
        return try {
            automationBridge?.windowHierarchy ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private val serverStartTimeMs = android.os.SystemClock.elapsedRealtime()

    override fun getServerStats(): Bundle? {
        val bundle = Bundle()
        bundle.putInt("client_count", clientManager.clientCount)
        bundle.putLong("mem_total", Runtime.getRuntime().totalMemory())
        bundle.putLong("mem_free", Runtime.getRuntime().freeMemory())
        bundle.putLong("mem_max", Runtime.getRuntime().maxMemory())
        bundle.putLong("uptime_ms", android.os.SystemClock.elapsedRealtime() - serverStartTimeMs)
        return bundle
    }

    private fun getDisplayToken(): IBinder? {
        return try {
            val displayTokenMethod = Class.forName("android.view.SurfaceControl")
                .getMethod("getInternalDisplayToken")
            displayTokenMethod.invoke(null) as? IBinder
        } catch (e: Exception) {
            null
        }
    }
}
