package rikka.shizuku.server

import android.graphics.Rect
import android.os.Bundle
import android.os.IBinder
import android.os.ServiceManager
import android.util.Log
import af.shizuku.server.IWindowManagerPlus

class WindowManagerPlusImpl : IWindowManagerPlus.Stub() {

    companion object {
        private const val TAG = "WindowManagerPlus"
        private const val ACTIVITY_TASK_SERVICE_NAME = "activity_task"
        private const val WINDOW_SERVICE_NAME = "window"
        private const val TASK_SERVICE_NAME = "task"
    }

    /**
     * Force enable free-form resizing for a specific package,
     * bypassing the app's manifest restrictions.
     */
    override fun forceResizable(packageName: String?, enabled: Boolean) {
        if (packageName == null) return
        Log.d(TAG, "Setting forceResizable for $packageName: $enabled")

        try {
            val activityTaskManager = getActivityTaskManager()
            if (activityTaskManager != null) {
                try {
                    val setResizableMethod = activityTaskManager.javaClass.getMethod(
                        "setTaskResizeable",
                        Int::class.java,
                        Int::class.java
                    )
                    // We need taskId, but we only have packageName.
                    // This is a complex mapping usually requiring ActivityManager.
                    Log.w(TAG, "forceResizable requires task ID for direct ActivityTaskManager API")
                } catch (e: NoSuchMethodException) {
                    Log.d(TAG, "setTaskResizeable method not available in ActivityTaskManager", e)
                }
            }

            // Fallback: system settings (affects global state or specific app settings)
            val process = Runtime.getRuntime().exec(
                arrayOf("settings", "put", "global", "enable_freeform_support", if (enabled) "1" else "0")
            )
            process.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set forceResizable", e)
        }
    }

    /**
     * Pin a window to a specific region of the screen (Desktop Mode).
     */
    override fun pinToRegion(taskId: Int, region: Rect?) {
        if (region == null) return
        Log.d(TAG, "Pinning task $taskId to region: $region")

        try {
            val activityTaskManager = getActivityTaskManager()
            if (activityTaskManager != null) {
                try {
                    val resizeTaskMethod = activityTaskManager.javaClass.getMethod(
                        "resizeTask",
                        Int::class.java,
                        Rect::class.java,
                        Int::class.java
                    )
                    // ResizeMode: 0 = RESIZE_MODE_USER
                    resizeTaskMethod.invoke(activityTaskManager, taskId, region, 0)
                    Log.d(TAG, "Successfully resized task $taskId via ActivityTaskManager")
                } catch (e: NoSuchMethodException) {
                    Log.d(TAG, "resizeTask method not available in ActivityTaskManager", e)
                }
            }

            // Additional logic for desktop mode pinning might require specific manufacturer APIs
            // (e.g., Samsung DeX, Motorola ReadyFor)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pin task $taskId to region", e)
        }
    }

    /**
     * Force an app into a system bubble.
     */
    override fun setAsBubble(taskId: Int, enabled: Boolean) {
        Log.d(TAG, "Setting task $taskId as bubble: $enabled")

        try {
            // Trying WindowManager API first (internal Android 17 APIs)
            val windowManager = getWindowManager()
            if (windowManager != null) {
                try {
                    val setBubbleMethod = windowManager.javaClass.getMethod(
                        "setTaskBubble",
                        Int::class.java,
                        Boolean::class.java
                    )
                    setBubbleMethod.invoke(windowManager, taskId, enabled)
                    Log.d(TAG, "Successfully set task bubble via WindowManager")
                    return
                } catch (e: NoSuchMethodException) {
                    Log.d(TAG, "setTaskBubble method not available in WindowManager", e)
                }
            }
            Log.d(TAG, "Bubble request for task $taskId logged")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set task $taskId as bubble", e)
        }
    }

    /**
     * Configure the position and visibility of the Android 17 'Bubble Bar'.
     */
    override fun configureBubbleBar(settings: Bundle?) {
        val position = settings?.getString("position", "bottom") ?: "bottom"
        val visibility = settings?.getString("visibility", "auto") ?: "auto"
        val size = settings?.getString("size", "medium") ?: "medium"

        Log.d(TAG, "Configuring bubble bar: position=$position, visibility=$visibility, size=$size")

        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("settings", "put", "secure", "bubble_bar_position", position)
            )
            process.waitFor()

            settings?.getString("visibility")?.let { vis ->
                val proc = Runtime.getRuntime().exec(
                    arrayOf("settings", "put", "secure", "bubble_bar_visibility", vis)
                )
                proc.waitFor()
            }

            settings?.getString("size")?.let { sz ->
                val proc = Runtime.getRuntime().exec(
                    arrayOf("settings", "put", "secure", "bubble_bar_size", sz)
                )
                proc.waitFor()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure bubble bar", e)
        }
    }

    /**
     * Set a window as 'Always on Top' using privileged flags.
     */
    override fun setAlwaysOnTop(taskId: Int, enabled: Boolean) {
        Log.d(TAG, "Setting always-on-top for task $taskId: $enabled")

        try {
            val activityTaskManager = getActivityTaskManager()
            if (activityTaskManager != null) {
                try {
                    val setAlwaysOnTopMethod = activityTaskManager.javaClass.getMethod(
                        "setAlwaysOnTop",
                        Int::class.java,
                        Boolean::class.java
                    )
                    setAlwaysOnTopMethod.invoke(activityTaskManager, taskId, enabled)
                    Log.d(TAG, "Successfully set always-on-top for task $taskId")
                    return
                } catch (e: NoSuchMethodException) {
                    Log.d(TAG, "setAlwaysOnTop method not available in ActivityTaskManager", e)
                }

                try {
                    val setTaskWindowingModeMethod = activityTaskManager.javaClass.getMethod(
                        "setTaskWindowingMode",
                        Int::class.java,
                        Int::class.java
                    )
                    val mode = if (enabled) 5 else 1
                    setTaskWindowingModeMethod.invoke(activityTaskManager, taskId, mode)
                    Log.d(TAG, "Successfully set task windowing mode for task $taskId")
                    return
                } catch (e: NoSuchMethodException) {
                    Log.d(TAG, "setTaskWindowingMode method not available", e)
                }
            }

            val windowManager = getWindowManager()
            if (windowManager != null) {
                try {
                    val setAlwaysOnTopMethod = windowManager.javaClass.getMethod(
                        "setAlwaysOnTop",
                        IBinder::class.java,
                        Boolean::class.java
                    )
                    val appToken = getAppTokenForTask(taskId)
                    if (appToken != null) {
                        setAlwaysOnTopMethod.invoke(windowManager, appToken, enabled)
                        Log.d(TAG, "Successfully set always-on-top via WindowManager")
                        return
                    }
                } catch (e: NoSuchMethodException) {
                    Log.d(TAG, "setAlwaysOnTop method not available in WindowManager", e)
                }
            }

            val state = if (enabled) "true" else "false"
            val process = Runtime.getRuntime().exec(
                arrayOf("cmd", "window", "set-always-on-top", taskId.toString(), state)
            )
            process.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set always-on-top for task $taskId", e)
        }
    }

    override fun setImmersiveMode(enabled: Boolean) {
        try {
            val value = if (enabled) "full" else "none"
            Runtime.getRuntime().exec(arrayOf("settings", "put", "global", "policy_control", "immersive.full=*=$value")).waitFor()
        } catch (e: Exception) {
        }
    }

    override fun setDexHighRefreshRate(enabled: Boolean) {
        try {
            val value = if (enabled) "1" else "0"
            Runtime.getRuntime().exec(arrayOf("settings", "put", "system", "min_refresh_rate", if (enabled) "120.0" else "60.0")).waitFor()
            Runtime.getRuntime().exec(arrayOf("settings", "put", "system", "peak_refresh_rate", if (enabled) "120.0" else "60.0")).waitFor()
            Runtime.getRuntime().exec(arrayOf("settings", "put", "global", "dex_force_high_refresh_rate", value)).waitFor()
        } catch (e: Exception) {
        }
    }

    private fun getActivityTaskManager(): Any? {
        return try {
            val serviceNames = listOf(ACTIVITY_TASK_SERVICE_NAME, TASK_SERVICE_NAME)
            
            for (serviceName in serviceNames) {
                val binder = ServiceManager.getService(serviceName)
                if (binder != null) {
                    try {
                        val stubClass = Class.forName("android.app.IActivityTaskManager\$Stub")
                        val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
                        return asInterfaceMethod.invoke(null, binder)
                    } catch (e: Exception) {
                        Log.d(TAG, "Failed to get IActivityTaskManager from $serviceName", e)
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "IActivityTaskManager not available", e)
            null
        }
    }

    private fun getWindowManager(): Any? {
        return try {
            val binder = ServiceManager.getService(WINDOW_SERVICE_NAME)
            if (binder != null) {
                val stubClass = Class.forName("android.view.IWindowManager\$Stub")
                val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
                return asInterfaceMethod.invoke(null, binder)
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "IWindowManager not available", e)
            null
        }
    }

    private fun getAppTokenForTask(taskId: Int): IBinder? {
        return try {
            val activityTaskManager = getActivityTaskManager()
            if (activityTaskManager != null) {
                try {
                    val getTaskTokenMethod = activityTaskManager.javaClass.getMethod(
                        "getTaskToken",
                        Int::class.java
                    )
                    return getTaskTokenMethod.invoke(activityTaskManager, taskId) as? IBinder
                } catch (e: NoSuchMethodException) {
                    Log.d(TAG, "getTaskToken method not available", e)
                }
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "Failed to get app token for task $taskId", e)
            null
        }
    }
}
