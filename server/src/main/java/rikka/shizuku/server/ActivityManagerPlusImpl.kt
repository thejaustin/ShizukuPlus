package rikka.shizuku.server

import android.os.Binder
import android.os.IBinder
import android.os.ServiceManager
import android.util.Log
import af.shizuku.server.IActivityManagerPlus
import af.shizuku.common.compat.Android17Compat
import af.shizuku.common.util.UserHandleCompat
import rikka.hidden.compat.ActivityManagerApis

class ActivityManagerPlusImpl : IActivityManagerPlus.Stub() {

    companion object {
        private const val TAG = "ActivityManagerPlus"

        private fun packageManagerService(): Any? = try {
            val binder = ServiceManager.getService("package") ?: return null
            Class.forName("android.content.pm.IPackageManager\$Stub")
                .getDeclaredMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        } catch (e: Exception) {
            Log.w(TAG, "packageManagerService unavailable", e)
            null
        }

        private fun activityManagerService(): Any? = try {
            val binder = ServiceManager.getService("activity") ?: return null
            Class.forName("android.app.IActivityManager\$Stub")
                .getDeclaredMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        } catch (e: Exception) {
            Log.w(TAG, "activityManagerService unavailable", e)
            null
        }

        private fun usageStatsService(): Any? = try {
            val binder = ServiceManager.getService("usagestats") ?: return null
            Class.forName("android.app.usage.IUsageStatsManager\$Stub")
                .getDeclaredMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        } catch (e: Exception) {
            Log.w(TAG, "usageStatsService unavailable", e)
            null
        }
    }

    private fun callingUserId() = UserHandleCompat.getUserId(Binder.getCallingUid())

    override fun deepForceStop(packageName: String?): Boolean {
        if (packageName == null) return false
        return try {
            ActivityManagerApis.forceStopPackageNoThrow(packageName, callingUserId())
            true
        } catch (e: Exception) {
            Log.w(TAG, "deepForceStop failed for $packageName", e)
            false
        }
    }

    override fun setAppStandbyBucket(packageName: String?, bucket: Int): Boolean {
        if (packageName == null) return false
        // Primary: IUsageStatsManager.setAppStandbyBucket — works at shell UID on all API levels
        try {
            val um = usageStatsService() ?: error("no usagestats service")
            val method = um.javaClass.methods.firstOrNull { it.name == "setAppStandbyBucket" }
                ?: error("setAppStandbyBucket not found")
            method.invoke(um, packageName, bucket, callingUserId())
            return true
        } catch (e: Exception) {
            Log.w(TAG, "setAppStandbyBucket IPC failed, falling back to exec", e)
        }
        // Fallback: am set-standby-bucket
        return try {
            val bucketStr = when (bucket) {
                10 -> "active"
                20 -> "working_set"
                30 -> "frequent"
                40 -> "rare"
                45, 50 -> "restricted"
                else -> bucket.toString()
            }
            Runtime.getRuntime().exec(arrayOf("am", "set-standby-bucket", packageName, bucketStr)).waitFor() == 0
        } catch (e: Exception) { false }
    }

    override fun killAllBackgroundProcesses(): Boolean {
        // Primary: IActivityManager.killAllBackgroundProcesses — works at shell UID
        try {
            val am = activityManagerService() ?: error("no activity service")
            val method = am.javaClass.methods.firstOrNull { it.name == "killAllBackgroundProcesses" }
                ?: error("killAllBackgroundProcesses not found")
            method.invoke(am)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "killAllBackgroundProcesses IPC failed, falling back to exec", e)
        }
        return try {
            Runtime.getRuntime().exec(arrayOf("am", "kill-all")).waitFor() == 0
        } catch (e: Exception) { false }
    }

    private fun setApplicationEnabledSetting(packageName: String, state: Int): Boolean {
        // Primary: IPackageManager.setApplicationEnabledSetting
        return try {
            val pm = packageManagerService() ?: error("no package service")
            val method = pm.javaClass.methods.firstOrNull { it.name == "setApplicationEnabledSetting" }
                ?: error("setApplicationEnabledSetting not found")
            method.invoke(pm, packageName, state, 0, callingUserId(), "com.android.shell")
            true
        } catch (e: Exception) {
            Log.w(TAG, "setApplicationEnabledSetting IPC failed for $packageName state=$state", e)
            false
        }
    }

    override fun freezeApp(packageName: String?): Boolean {
        if (packageName == null) return false
        // COMPONENT_ENABLED_STATE_DISABLED_USER = 3 — user-level disable, reversible
        if (setApplicationEnabledSetting(packageName, 3)) return true
        return try {
            Runtime.getRuntime().exec(arrayOf("pm", "disable-user", "--user", "0", packageName)).waitFor() == 0
        } catch (e: Exception) { false }
    }

    override fun unfreezeApp(packageName: String?): Boolean {
        if (packageName == null) return false
        // COMPONENT_ENABLED_STATE_DEFAULT = 0 — restore manifest-declared state
        if (setApplicationEnabledSetting(packageName, 0)) return true
        return try {
            Runtime.getRuntime().exec(arrayOf("pm", "enable", "--user", "0", packageName)).waitFor() == 0
        } catch (e: Exception) { false }
    }

    override fun isAppFrozen(packageName: String?): Boolean {
        if (packageName == null) return false
        // Primary: check ApplicationInfo.enabled via Binder IPC
        try {
            val ai = Android17Compat.getApplicationInfo(packageName, 0L, callingUserId())
            if (ai != null) return !ai.enabled
        } catch (e: Exception) {
            Log.w(TAG, "isAppFrozen IPC failed for $packageName, falling back", e)
        }
        // Fallback: pm list packages -d lists only disabled packages
        return try {
            Runtime.getRuntime().exec(arrayOf("pm", "list", "packages", "-d", packageName))
                .inputStream.bufferedReader().use { it.readText() }
                .contains(packageName)
        } catch (e: Exception) { false }
    }

    override fun setAppProcessLimit(limit: Int) {
        // Primary: IActivityManager.setProcessLimit
        try {
            val am = activityManagerService()
            val method = am?.javaClass?.methods?.firstOrNull { it.name == "setProcessLimit" }
            if (method != null) {
                method.invoke(am, limit)
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "setProcessLimit IPC failed", e)
        }
        // Fallback: settings + am
        try {
            Runtime.getRuntime().exec(arrayOf("settings", "put", "global", "max_phantom_processes", limit.toString())).waitFor()
            Runtime.getRuntime().exec(arrayOf("am", "set-process-limit", limit.toString())).waitFor()
        } catch (_: Exception) {}
    }

    override fun getRunningProcesses(): List<String> {
        return try {
            Runtime.getRuntime().exec(arrayOf("ps", "-A", "-o", "NAME,RSS,PID"))
                .inputStream.bufferedReader().use { it.readLines() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun clearAppCache(packageName: String?): Boolean {
        if (packageName == null) return false
        val userId = callingUserId()
        // Primary: IPackageManager.deleteApplicationCacheFilesAsUser with a blocking observer
        try {
            val pm = packageManagerService() ?: error("no package service")
            val latch = java.util.concurrent.CountDownLatch(1)
            val observer = object : android.content.pm.IPackageDataObserver.Stub() {
                override fun onRemoveCompleted(pkgName: String?, succeeded: Boolean) {
                    latch.countDown()
                }
            }
            val method = pm.javaClass.methods.firstOrNull { it.name == "deleteApplicationCacheFilesAsUser" }
                ?: pm.javaClass.methods.firstOrNull { it.name == "deleteApplicationCacheFiles" }
                ?: error("deleteApplicationCacheFiles[AsUser] not found")
            if (method.parameterCount == 3) {
                method.invoke(pm, packageName, observer, userId)
            } else {
                method.invoke(pm, packageName, observer)
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "clearAppCache IPC failed for $packageName, falling back to exec", e)
        }
        // Fallback: pm clear-cache (Android 13+) — targets the specific package
        return try {
            Runtime.getRuntime().exec(arrayOf("pm", "clear-cache", "--user", "0", packageName)).waitFor() == 0
        } catch (_: Exception) { false }
    }

    override fun clearAppData(packageName: String?): Boolean {
        if (packageName == null) return false
        val userId = callingUserId()
        // Primary: IPackageManager.clearApplicationUserData with a blocking observer
        try {
            val pm = packageManagerService() ?: error("no package service")
            val latch = java.util.concurrent.CountDownLatch(1)
            val observer = object : android.content.pm.IPackageDataObserver.Stub() {
                override fun onRemoveCompleted(pkgName: String?, succeeded: Boolean) {
                    latch.countDown()
                }
            }
            val method = pm.javaClass.methods.firstOrNull { it.name == "clearApplicationUserData" }
                ?: error("clearApplicationUserData not found")
            method.invoke(pm, packageName, observer, userId)
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "clearAppData IPC failed for $packageName, falling back to exec", e)
        }
        return try {
            Runtime.getRuntime().exec(arrayOf("pm", "clear", "--user", "0", packageName)).waitFor() == 0
        } catch (e: Exception) { false }
    }
}
