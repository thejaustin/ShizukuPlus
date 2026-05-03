package af.shizuku.manager.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import timber.log.Timber

/**
 * AICore+ Automation Bridge
 * Provides privileged UI hierarchy dumping and physical input simulation for AI automation.
 */
class AICorePlusService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.d("AICorePlusService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // This service primarily acts as a bridge for explicit commands,
        // but can optionally listen for specific UI transitions if needed.
    }

    override fun onInterrupt() {
        Timber.d("AICorePlusService interrupted")
    }

    /**
     * Dumps the current view hierarchy starting from the active window root.
     * @return XML string representation of the hierarchy.
     */
    fun dumpHierarchy(): String {
        val rootNode = rootInActiveWindow ?: return "<error>Root node unavailable</error>"
        val sb = java.lang.StringBuilder()
        buildXml(rootNode, sb, 0)
        return sb.toString()
    }

    /**
     * Alias for dumpHierarchy to match IAICorePlus AIDL.
     */
    fun getWindowHierarchy(): String = dumpHierarchy()

    private fun buildXml(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        val indent = "  ".repeat(depth)
        sb.append(indent).append("<node")
        sb.append(" class=\"").append(node.className).append("\"")
        if (!node.text.isNullOrEmpty()) sb.append(" text=\"").append(node.text).append("\"")
        if (!node.contentDescription.isNullOrEmpty()) sb.append(" content-desc=\"").append(node.contentDescription).append("\"")
        if (!node.viewIdResourceName.isNullOrEmpty()) sb.append(" resource-id=\"").append(node.viewIdResourceName).append("\"")
        sb.append(" checkable=\"").append(node.isCheckable).append("\"")
        sb.append(" checked=\"").append(node.isChecked).append("\"")
        sb.append(" clickable=\"").append(node.isClickable).append("\"")
        sb.append(" enabled=\"").append(node.isEnabled).append("\"")
        sb.append(" focusable=\"").append(node.isFocusable).append("\"")
        sb.append(" focused=\"").append(node.isFocused).append("\"")
        sb.append(" scrollable=\"").append(node.isScrollable).append("\"")
        sb.append(" long-clickable=\"").append(node.isLongClickable).append("\"")
        sb.append(" password=\"").append(node.isPassword).append("\"")
        sb.append(" selected=\"").append(node.isSelected).append("\"")
        sb.append(">\n")

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                buildXml(child, sb, depth + 1)
                child.recycle()
            }
        }
        sb.append(indent).append("</node>\n")
    }

    /**
     * Simulates a physical tap at the given coordinates.
     */
    fun performTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Alias for performTap to match IAICorePlus AIDL.
     */
    fun simulateTouch(x: Float, y: Float): Boolean = performTap(x, y)

    /**
     * Simulates a swipe between two coordinates.
     */
    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Alias for performSwipe to match IAICorePlus AIDL.
     */
    fun simulateSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Int): Boolean = 
        performSwipe(x1, y1, x2, y2, duration.toLong())

    /**
     * Simulates typing text input.
     * Note: Requires a focused input field and may use the Clipboard or ImeService.
     */
    fun simulateText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val arguments = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /**
     * Get a color sample from any pixel on the screen.
     * Uses AccessibilityService.takeScreenshot on API 30+.
     */
    fun getPixelColor(x: Int, y: Int): Int {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val latch = java.util.concurrent.CountDownLatch(1)
            var resultColor = android.graphics.Color.TRANSPARENT
            
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    try {
                        val hardwareBuffer = screenshot.hardwareBuffer
                        val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                        if (bitmap != null && x in 0 until bitmap.width && y in 0 until bitmap.height) {
                            resultColor = bitmap.getPixel(x, y)
                        }
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Timber.e("takeScreenshot failed with error code $errorCode")
                    latch.countDown()
                }
            })
            
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            return resultColor
        }
        return android.graphics.Color.TRANSPARENT
    }

    /**
     * Schedule a high-priority task on the Neural Processing Unit (NPU).
     * Stub for AICore 5 advanced methods.
     */
    fun scheduleNPULoad(params: android.os.Bundle): Boolean {
        Timber.d("Scheduling NPU load via AICore+ Bridge: $params")
        // In a real implementation, this would communicate with a vendor-specific NPU service
        return true
    }

    /**
     * Capture a privileged screenshot of a specific window/layer for AI analysis.
     * Uses AccessibilityService.takeScreenshot on API 30+.
     */
    fun captureLayer(layerId: Int): android.graphics.Bitmap? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val latch = java.util.concurrent.CountDownLatch(1)
            var resultBitmap: android.graphics.Bitmap? = null
            
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    try {
                        val hardwareBuffer = screenshot.hardwareBuffer
                        // Convert to software bitmap so it can be returned/processed
                        val hwBitmap = android.graphics.Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                        resultBitmap = hwBitmap?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Timber.e("takeScreenshot failed with error code $errorCode")
                    latch.countDown()
                }
            })
            
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
            return resultBitmap
        }
        return null
    }

    /**
     * Get current system intelligence context.
     * Returns real data about the device and service state.
     */
    fun getSystemContext(): android.os.Bundle {
        return android.os.Bundle().apply {
            putString("bridge_version", "1.6.0")
            putBoolean("accessibility_enabled", true)
            putInt("sdk_int", android.os.Build.VERSION.SDK_INT)
            putString("device_model", android.os.Build.MODEL)
            putString("android_version", android.os.Build.VERSION.RELEASE)
            
            // Detect if we're on a heavy skin like One UI or MIUI
            val brand = android.os.Build.BRAND.lowercase()
            val manufacturer = android.os.Build.MANUFACTURER.lowercase()
            putBoolean("is_samsung", brand.contains("samsung") || manufacturer.contains("samsung"))
            putBoolean("is_xiaomi", brand.contains("xiaomi") || manufacturer.contains("xiaomi"))
            
            // Add current foreground activity info if possible
            rootInActiveWindow?.let { root ->
                putString("foreground_package", root.packageName?.toString())
            }
        }
    }
}
