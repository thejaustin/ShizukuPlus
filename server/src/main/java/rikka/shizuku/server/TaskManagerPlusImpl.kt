package rikka.shizuku.server

import android.app.ActivityManager
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ServiceManager
import android.util.Log
import af.shizuku.common.util.UserHandleCompat
import af.shizuku.server.ITaskManagerPlus

/**
 * Recent-tasks access (list, thumbnail, start from recents, remove) through hidden framework
 * APIs by reflection, because their signatures change between Android versions. Behaviour
 * matches AutoCat's former app-side UserService on Android 14-17.
 *
 * The Bundle keys mirror rikka.shizuku.ShizukuPlusAPI.Tasks (the server does not depend on
 * the client API module, so the literals are duplicated here; keep them in sync).
 */
class TaskManagerPlusImpl : ITaskManagerPlus.Stub() {

    companion object {
        private const val TAG = "TaskManagerPlus"
        private const val RESOLUTION_HIGH = 1

        const val KEY_TASK_IDS = "taskIds"
        const val KEY_COMPONENTS = "components"
        const val KEY_BUFFER = "buffer"
    }

    private fun callingUserId() = UserHandleCompat.getUserId(Binder.getCallingUid())

    private val activityTaskManager: Any? by lazy {
        try {
            val binder: IBinder = ServiceManager.getService("activity_task") ?: return@lazy null
            Class.forName("android.app.IActivityTaskManager\$Stub")
                .getDeclaredMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        } catch (e: Exception) {
            Log.w(TAG, "activity_task service unavailable", e)
            null
        }
    }

    override fun getRecentTasks(maxNum: Int, flags: Int): Bundle {
        val atm = activityTaskManager ?: return Bundle()
        return try {
            val slice = atm.javaClass
                .getMethod("getRecentTasks", Int::class.java, Int::class.java, Int::class.java)
                .invoke(atm, maxNum.coerceIn(0, 100), flags or ActivityManager.RECENT_IGNORE_UNAVAILABLE, callingUserId())
            val tasks = slice!!.javaClass.getMethod("getList").invoke(slice) as List<*>
            val recent = tasks.filterIsInstance<ActivityManager.RecentTaskInfo>()
            Bundle().apply {
                putIntArray(KEY_TASK_IDS, recent.map { it.taskId }.toIntArray())
                putStringArray(
                    KEY_COMPONENTS,
                    recent.map { (it.topActivity ?: it.baseIntent?.component)?.flattenToString().orEmpty() }
                        .toTypedArray(),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't read recent tasks", e)
            Bundle()
        }
    }

    override fun getTaskThumbnail(taskId: Int): Bundle {
        return try {
            val snapshot = getTaskSnapshot(taskId) ?: return Bundle()
            val buffer = snapshotBuffer(snapshot) ?: return Bundle()
            Bundle().apply { putParcelable(KEY_BUFFER, buffer) }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't read a thumbnail for task $taskId", e)
            Bundle()
        }
    }

    override fun startTaskFromRecents(taskId: Int, options: Bundle?): Boolean {
        val atm = activityTaskManager ?: return false
        return try {
            val result = atm.javaClass
                .getMethod("startActivityFromRecents", Int::class.java, Bundle::class.java)
                .invoke(atm, taskId, options) as Int
            result >= 0
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't open task $taskId", e)
            false
        }
    }

    override fun removeTask(taskId: Int): Boolean {
        val atm = activityTaskManager ?: return false
        return try {
            atm.javaClass.getMethod("removeTask", Int::class.java).invoke(atm, taskId) as Boolean
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't remove task $taskId", e)
            false
        }
    }

    /**
     * Android 17 moved snapshots to ITaskSnapshotManager; older versions have them on
     * IActivityTaskManager.
     */
    private fun getTaskSnapshot(taskId: Int): Any? {
        val atm = activityTaskManager ?: return null
        val snapshotManager = atm.javaClass.methods
            .firstOrNull { it.name == "getTaskSnapshotManager" && it.parameterCount == 0 }
            ?.invoke(atm)
        if (snapshotManager != null) {
            return snapshotManager.javaClass
                .getMethod("getTaskSnapshot", Int::class.java, Long::class.java, Int::class.java)
                .invoke(snapshotManager, taskId, 0L, RESOLUTION_HIGH)
        }
        val method = atm.javaClass.methods.firstOrNull { it.name == "getTaskSnapshot" } ?: return null
        val args: List<Any?> = method.parameterTypes.mapIndexed { index, type ->
            when {
                index == 0 -> taskId
                // isLowResolution: a phone-sized thumbnail is plenty
                type == Boolean::class.java -> index == 1
                else -> null
            }
        }
        return method.invoke(atm, *args.toTypedArray())
    }

    private fun snapshotBuffer(snapshot: Any): HardwareBuffer? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val bitmap = runCatching {
                snapshot.javaClass.getMethod("wrapToBitmap").invoke(snapshot) as Bitmap?
            }.getOrNull()
            if (bitmap != null && bitmap.config == Bitmap.Config.HARDWARE) {
                return bitmap.hardwareBuffer
            }
        }
        return runCatching {
            snapshot.javaClass.getMethod("getHardwareBuffer").invoke(snapshot) as HardwareBuffer?
        }.getOrNull()
    }
}
