package rikka.shizuku.server

import android.graphics.Rect
import android.os.Bundle
import android.os.IBinder
import android.os.ServiceManager
import android.util.Log
import af.shizuku.server.IWindowManagerPlus

/**
 * Implementation of WindowManagerPlus using Android's window management APIs.
 * 
 * This class provides enhanced window management features including:
 * - Force resizable activities
 * - Pin windows to regions (Desktop Mode)
 * - Bubble mode support
 * - Always-on-top windows
 * 
 * Uses reflection to access hidden IActivityTaskManager and IWindowManager APIs.
 */
class WindowManagerPlusImpl : IWindowManagerPlus.Stub() {
    companion object {
        private const val TAG = "WindowManagerPlusImpl"
        private const val TASK_SERVICE_NAME = "task"
        private const val WINDOW_SERVICE_NAME = "window"
        private const val ACTIVITY_TASK_SERVICE_NAME = "activity_task"
    }

    /**
     * Force enable free-form resizing for a specific package.
     * 
     * Bypasses the app's manifest restrictions by using hidden
     * ActivityTaskManager APIs to force resizeable mode.
     * 
     * @param packageName The package name to modify
     * @param enabled Whether to enable or disable force resizable mode
     */
    override fun forceResizable(packageName: String?, enabled: Boolean) {
        if (packageName == null) {
            Log.w(TAG, "forceResizable called with null packageName")
            return
        }

        Log.d(TAG, "Setting force resizable for $packageName: $enabled")

        try {
            // Try using ActivityTaskManager APIs
            val activityTaskManager = getActivityTaskManager()
            if (activityTaskManager != null) {
                // Use setForceResizable if available (Android 11+)
                try {
                    val setForceResizableMethod = activityTaskManager.javaClass.getMethod(
                        "setForceResizable",
                        String::class.java,
                        Boolean::class.java
                    )
                    setForceResizableMethod.invoke(activityTaskManager, packageName, enabled)
                    Log.d(TAG, "Successfully set force resizable for $packageName")
                    return
                } catch (e: NoSuchMethodException) {
                    Log.d(TAG, "setForceResizable method not available", e)
                }
            }

            // Fallback: Use settings command
            val value = if (enabled) "1" else "0"
            val process = Runtime.getRuntime().exec(
                arrayOf("settings", "put", "global", "force_resizable_activities", value)
            )
            process.waitFor()
            Log.d(TAG, "Set force_resizable_activities setting to $value")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set force resizable for $packageName", e)
        }
    }

    /**
     * Pin a window to a specific region of the screen (Desktop Mode).
     * 
     * Uses ActivityTaskManager to lock a task to a specific screen region,
     * useful for desktop mode implementations.
     * 
     * @param taskId The task ID to pin
     * @param region The screen region to pin the task to
     */
    override fun pinToRegion(taskId: Int, region: Rect?) {
        Log.d(TAG, "Pinning task $taskId to region: $region")

        try {
            val activityTaskManager = getActivityTaskManager()
            if (activityTaskManager != null) {
                // Try to use resizeTask or setTaskBounds
                try {
                    val resizeTaskMethod = activityTaskManager.javaClass.getMethod(
                        "resizeTask",
                        Int::class.java,
                        Rect::class.java,
                        Boolean::class.java
                    )
                    resizeTaskMethod.invoke(activityTaskManager, taskId, region, true)
                    Log.d(TAG, "Successfully pinned task $taskId to region")
                    return
                } catch (e: NoSuchMethodException) {
                    Log.d(TAG, "resizeTask method not available, trying alternatives", e)
                }

                try {
                    val setTaskBoundsMethod = activityTaskManager.javaClass.getMethod(
                        "setTaskBounds",
                        Int::class.java,
                        Rect::class.java
                    )
                    setTaskBoundsMethod.invoke(activityTaskManager, taskId, region)
                    Log.d(TAG, "Successfully set task bounds for task $taskId")
                    return
                } catch (e: NoSuchMethodException) {
                    Log.d(TAG, "setTaskBounds method not available", e)
                }
            }

            // Fallback: Use am command
            if (region != null) {
                val process = Runtime.getRuntime().exec(
                    arrayOf("am", "task", "lock", taskId.toString())
                )
                process.waitFor()
                Log.d(TAG, "Locked task $taskId using am command")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pin task $taskId to region", e)
        }
    }

    /**
     * Force an app into a system bubble.
     * 
     * Uses ActivityTaskManager to set a task as a bubble, making it
     * appear in the system's bubble UI (Android 11+).
     * 
     * @param taskId The task ID to set as bubble
     * @param enabled Whether to enable or disable bubble mode
     */
    override fun setAsBubble(taskId: Int, enabled: Boolean) {
        Log.d(TAG, "Setting task $taskId as bubble: $enabled")

        try {
            val activityTaskManager = getActivityTaskManager()
            if (activityTaskManager != null) {
                // Try to use setTaskAsBubble or similar method
                try {
                    val setTaskAsBubbleMethod = activityTaskManager.javaClass.getMethod(
                        "setTaskAsBubble",
                        Int::class.java,
                        Boolean::class.java
                    )
                    setTaskAsBubbleMethod.invoke(activityTaskManager, taskId, enabled)
                    Log.d(TAG, "Successfully set task $taskId as bubble: $enabled")
                    return
                } catch (e: NoSuchMethodException) {
                    Log.d(TAG, "setTaskAsBubble method not available, trying alternatives", e)
                }

                // Try using IWindowManager for bubble settings
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
            }

            // Fallback: Log the request (actual bubble implementation requires notification APIs)
            Log.d(TAG, "Bubble request for task $taskId logged (requires NotificationManager for full implementation)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set task $taskId as bubble", e)
        }
    }

    /**
     * Configure the position and visibility of the Android 17 'Bubble Bar'.
     * 
     * Sets system settings for bubble bar configuration.
     * 
     * @param settings Bundle containing:
     *   - "position": Position of bubble bar ("top", "bottom", "left", "right")
     *   - "visibility": Visibility setting ("always", "auto", "never")
     *   - "size": Size setting ("small", "medium", "large")
     */
    override fun configureBubbleBar(settings: Bundle?) {
        val position = settings?.getString("position", "bottom") ?: "bottom"
        val visibility = settings?.getString("visibility", "auto") ?: "auto"
        val size = settings?.getString("size", "medium") ?: "medium"

        Log.d(TAG, "Configuring bubble bar: position=$position, visibility=$visibility, size=$size")

        try {
            // Store bubble bar settings in secure settings
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

            Log.d(TAG, "Bubble bar configuration saved")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure bubble bar", e)
        }
    }

    /**
     * Set a window as 'Always on Top' using privileged flags.
     * 
     * Uses ActivityTaskManager or WindowManager to set the always-on-top
     * flag for a specific task.
     * 
     * @param taskId The task ID to modify
     * @param enabled Whether to enable or disable always-on-top
     */
    override fun setAlwaysOnTop(taskId: Int, enabled: Boolean) {
        Log.d(TAG, "Setting always-on-top for task $taskId: $enabled")

        try {
            val activityTaskManager = getActivityTaskManager()
            if (activityTaskManager != null) {
                // Try to use setAlwaysOnTop method
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

                // Try setTaskWindowingMode with fullscreen or pinned mode
                try {
                    val setTaskWindowingModeMethod = activityTaskManager.javaClass.getMethod(
                        "setTaskWindowingMode",
                        Int::class.java,
                        Int::class.java
                    )
                    // WINDOWING_MODE_PINNED = 5
                    val mode = if (enabled) 5 else 1 // PINNED or FREEFORM
                    setTaskWindowingModeMethod.invoke(activityTaskManager, taskId, mode)
                    Log.d(TAG, "Successfully set task windowing mode for task $taskId")
                    return
                } catch (e: NoSuchMethodException) {
                    Log.d(TAG, "setTaskWindowingMode method not available", e)
                }
            }

            // Try IWindowManager
            val windowManager = getWindowManager()
            if (windowManager != null) {
                try {
                    val setAlwaysOnTopMethod = windowManager.javaClass.getMethod(
                        "setAlwaysOnTop",
                        IBinder::class.java,
                        Boolean::class.java
                    )
                    // Get app token for the task
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

            // Fallback: Try cmd window command (may not work on all devices)
            val state = if (enabled) "true" else "false"
            val process = Runtime.getRuntime().exec(
                arrayOf("cmd", "window", "set-always-on-top", taskId.toString(), state)
            )
            process.waitFor()
            Log.d(TAG, "Executed cmd window command for always-on-top")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set always-on-top for task $taskId", e)
        }
    }

    override fun setImmersiveMode(enabled: Boolean) {
        try {
            val value = if (enabled) "full" else "none"
            // Use 'policy' command if available, otherwise fallback to settings
            Runtime.getRuntime().exec(arrayOf("settings", "put", "global", "policy_control", "immersive.full=*=$value")).waitFor()
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun setDexHighRefreshRate(enabled: Boolean) {
        try {
            // Samsung DeX often locks to 60Hz. Bypassing SemRefreshRateManager via settings.
            val value = if (enabled) "1" else "0"
            Runtime.getRuntime().exec(arrayOf("settings", "put", "system", "min_refresh_rate", if (enabled) "120.0" else "60.0")).waitFor()
            Runtime.getRuntime().exec(arrayOf("settings", "put", "system", "peak_refresh_rate", if (enabled) "120.0" else "60.0")).waitFor()
            // Samsung specific DeX flag
            Runtime.getRuntime().exec(arrayOf("settings", "put", "global", "dex_force_high_refresh_rate", value)).waitFor()
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * Get IActivityTaskManager instance via ServiceManager.
     * 
     * @return IActivityTaskManager instance, or null if not available
     */
    private fun getActivityTaskManager(): Any? {
        return try {
            // Try multiple service names
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

    /**
     * Get IWindowManager instance via ServiceManager.
     * 
     * @return IWindowManager instance, or null if not available
     */
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

    /**
     * Get app token for a specific task.
     * 
     * @param taskId The task ID
     * @return App token IBinder, or null if not found
     */
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
