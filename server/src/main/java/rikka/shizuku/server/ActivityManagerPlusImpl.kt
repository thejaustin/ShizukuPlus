package rikka.shizuku.server

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ServiceManager
import android.util.Log
import af.shizuku.server.IActivityManagerPlus
import af.shizuku.common.compat.Android17Compat
import af.shizuku.common.util.UserHandleCompat
import rikka.hidden.compat.ActivityManagerApis
import rikka.shizuku.server.api.IContentProviderUtils
import rikka.shizuku.server.util.InputValidationUtils
import rikka.shizuku.server.util.ShellExecutor

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
        if (!InputValidationUtils.isValidPackageName(packageName)) return false
        val bucketStr = when (bucket) {
            10 -> "active"
            20 -> "working_set"
            30 -> "frequent"
            40 -> "rare"
            45, 50 -> "restricted"
            else -> bucket.toString()
        }
        return ShellExecutor.execBool("am", "set-standby-bucket", packageName, bucketStr)
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
        return ShellExecutor.execBool("am", "kill-all")
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
        if (!InputValidationUtils.isValidPackageName(packageName)) return false
        return ShellExecutor.execBool("pm", "disable-user", "--user", "0", packageName)
    }

    override fun unfreezeApp(packageName: String?): Boolean {
        if (packageName == null) return false
        // COMPONENT_ENABLED_STATE_DEFAULT = 0 — restore manifest-declared state
        if (setApplicationEnabledSetting(packageName, 0)) return true
        if (!InputValidationUtils.isValidPackageName(packageName)) return false
        return ShellExecutor.execBool("pm", "enable", "--user", "0", packageName)
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
        if (!InputValidationUtils.isValidPackageName(packageName)) return false
        return ShellExecutor.exec("pm", "list", "packages", "-d", packageName).contains(packageName)
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
        // Fallback: ContentProvider PUT for settings + am exec for process limit
        try {
            val userId = callingUserId()
            val provider = ActivityManagerApis.getContentProviderExternal(
                "settings", userId, null, "com.android.shell"
            )
            if (provider != null) {
                val extras = Bundle().apply { putString("value", limit.toString()) }
                IContentProviderUtils.callCompat(provider, null, "settings", "PUT_global", "max_phantom_processes", extras)
            }
        } catch (_: Exception) {}
        ShellExecutor.execCode("am", "set-process-limit", limit.toString())
    }

    override fun getRunningProcesses(): List<String> {
        // Primary: IActivityManager.getRunningAppProcesses — works at shell UID, no exec needed
        try {
            val am = activityManagerService() ?: error("no activity service")
            val method = am.javaClass.methods.firstOrNull { it.name == "getRunningAppProcesses" }
                ?: error("getRunningAppProcesses not found")
            @Suppress("UNCHECKED_CAST")
            val procs = method.invoke(am) as? List<*>
            if (!procs.isNullOrEmpty()) {
                return procs.mapNotNull { p ->
                    if (p == null) return@mapNotNull null
                    try {
                        val name = p.javaClass.getField("processName").get(p) as? String ?: return@mapNotNull null
                        val pid = p.javaClass.getField("pid").get(p) as? Int ?: 0
                        "$name $pid"
                    } catch (_: Exception) { null }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getRunningAppProcesses IPC failed, falling back to exec", e)
        }
        // Fallback: ps -A (may be blocked by SELinux on Samsung OneUI 8)
        return ShellExecutor.exec("ps", "-A", "-o", "NAME,RSS,PID").lines()
    }

    private fun createPackageDataObserver(latch: java.util.concurrent.CountDownLatch): IBinder {
        // Parcel.writeStrongBinder() requires a real android.os.Binder subclass — a
        // Proxy.newProxyInstance implementing IBinder cannot be marshaled cross-process and
        // causes the pm IPC call to throw, silently always falling through to the exec fallback.
        // IPackageDataObserver has one method (onRemoveCompleted) at FIRST_CALL_TRANSACTION.
        return object : android.os.Binder() {
            init { attachInterface(null, "android.content.pm.IPackageDataObserver") }
            override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
                return when (code) {
                    IBinder.FIRST_CALL_TRANSACTION -> { // onRemoveCompleted(String packageName, boolean succeeded)
                        data.enforceInterface("android.content.pm.IPackageDataObserver")
                        latch.countDown()
                        reply?.writeNoException()
                        true
                    }
                    else -> super.onTransact(code, data, reply, flags)
                }
            }
        }
    }

    override fun clearAppCache(packageName: String?): Boolean {
        if (packageName == null) return false
        val userId = callingUserId()
        // Primary: IPackageManager.deleteApplicationCacheFilesAsUser with a blocking observer
        try {
            val pm = packageManagerService() ?: error("no package service")
            val latch = java.util.concurrent.CountDownLatch(1)
            val observer = createPackageDataObserver(latch)
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
        if (!InputValidationUtils.isValidPackageName(packageName)) return false
        return ShellExecutor.execBool("pm", "clear-cache", "--user", "0", packageName)
    }

    override fun clearAppData(packageName: String?): Boolean {
        if (packageName == null) return false
        val userId = callingUserId()
        // Primary: IPackageManager.clearApplicationUserData with a blocking observer
        try {
            val pm = packageManagerService() ?: error("no package service")
            val latch = java.util.concurrent.CountDownLatch(1)
            val observer = createPackageDataObserver(latch)
            val method = pm.javaClass.methods.firstOrNull { it.name == "clearApplicationUserData" }
                ?: error("clearApplicationUserData not found")
            method.invoke(pm, packageName, observer, userId)
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "clearAppData IPC failed for $packageName, falling back to exec", e)
        }
        if (!InputValidationUtils.isValidPackageName(packageName)) return false
        return ShellExecutor.execBool("pm", "clear", "--user", "0", packageName)
    }
}
