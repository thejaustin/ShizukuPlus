package rikka.shizuku.server

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.ServiceManager
import android.util.Log
import af.shizuku.server.IPackageGovernorPlus
import af.shizuku.common.compat.Android17Compat
import af.shizuku.common.util.UserHandleCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PackageGovernorPlusImpl : IPackageGovernorPlus.Stub() {

    companion object {
        private const val TAG = "PackageGovernorPlus"
        // PackageManager.REQUESTED_PERMISSION_GRANTED = 2
        private const val REQUESTED_PERMISSION_GRANTED = 2
    }

    private fun callingUserId() = UserHandleCompat.getUserId(Binder.getCallingUid())

    private fun exec(vararg args: String): Boolean = try {
        Runtime.getRuntime().exec(args).waitFor() == 0
    } catch (_: Exception) { false }

    private fun packageManagerService(): Any? = try {
        val binder = ServiceManager.getService("package") ?: return null
        Class.forName("android.content.pm.IPackageManager\$Stub")
            .getDeclaredMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
    } catch (_: Exception) { null }

    override fun grantPermission(packageName: String?, permission: String?): Boolean {
        if (packageName.isNullOrBlank() || permission.isNullOrBlank()) return false
        // Primary: Binder IPC via Android17Compat — handles Android 17 deviceId parameter
        return try {
            Android17Compat.grantRuntimePermission(packageName, permission, callingUserId())
            true
        } catch (e: Exception) {
            Log.w(TAG, "grantPermission IPC failed for $packageName/$permission, falling back", e)
            exec("pm", "grant", "--user", "0", packageName, permission)
        }
    }

    override fun revokePermission(packageName: String?, permission: String?): Boolean {
        if (packageName.isNullOrBlank() || permission.isNullOrBlank()) return false
        return try {
            Android17Compat.revokeRuntimePermission(packageName, permission, callingUserId())
            true
        } catch (e: Exception) {
            Log.w(TAG, "revokePermission IPC failed for $packageName/$permission, falling back", e)
            exec("pm", "revoke", "--user", "0", packageName, permission)
        }
    }

    override fun getGrantedPermissions(packageName: String?): List<String> {
        if (packageName.isNullOrBlank()) return emptyList()
        // Primary: PackageInfo.requestedPermissionsFlags filtered by REQUESTED_PERMISSION_GRANTED
        try {
            val info = Android17Compat.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS.toLong(), callingUserId())
            if (info != null) {
                val perms = info.requestedPermissions ?: return emptyList()
                val flags = info.requestedPermissionsFlags ?: IntArray(perms.size)
                return perms.filterIndexed { i, _ ->
                    flags.getOrElse(i) { 0 } and REQUESTED_PERMISSION_GRANTED != 0
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getGrantedPermissions IPC failed for $packageName, falling back", e)
        }
        // Fallback: pm dump parse
        return try {
            val output = Runtime.getRuntime().exec(arrayOf("pm", "dump", packageName))
                .inputStream.bufferedReader().use { it.readText() }
            val granted = mutableListOf<String>()
            var inGrantedSection = false
            for (line in output.lines()) {
                val trimmed = line.trim()
                when {
                    trimmed == "granted permissions:" -> inGrantedSection = true
                    inGrantedSection && trimmed.startsWith("android.permission.") -> granted.add(trimmed)
                    inGrantedSection && !trimmed.startsWith("android.") && trimmed.isNotEmpty() -> inGrantedSection = false
                }
            }
            granted
        } catch (_: Exception) { emptyList() }
    }

    override fun uninstallForUser(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val userId = callingUserId()
        // Primary: IPackageManager.deletePackageAsUser — shell UID has DELETE_PACKAGES
        try {
            val pm = packageManagerService() ?: error("no package service")
            val latch = CountDownLatch(1)
            var deleteResult = -1
            val observer = object : android.content.pm.IPackageDeleteObserver.Stub() {
                override fun packageDeleted(name: String?, returnCode: Int) {
                    deleteResult = returnCode
                    latch.countDown()
                }
            }
            val invoked = pm.javaClass.methods
                .filter { it.name == "deletePackageAsUser" }
                .any { m ->
                    runCatching {
                        when (m.parameterTypes.size) {
                            4 -> m.invoke(pm, packageName, observer, userId, 0)
                            5 -> m.invoke(pm, packageName, null, observer, userId, 0)
                            else -> return@any false
                        }
                        true
                    }.getOrDefault(false)
                }
            if (invoked) {
                latch.await(15, TimeUnit.SECONDS)
                if (deleteResult == 1) return true // DELETE_SUCCEEDED = 1
            }
        } catch (e: Exception) {
            Log.w(TAG, "uninstallForUser IPC failed for $packageName, falling back", e)
        }
        return exec("pm", "uninstall", "--user", "0", packageName)
    }

    override fun restoreSystemApp(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val userId = callingUserId()
        // Primary: IPackageManager.installExistingPackageAsUser — no callback needed
        try {
            val pm = packageManagerService() ?: error("no package service")
            val methods = pm.javaClass.methods.filter { it.name == "installExistingPackageAsUser" }
            for (m in methods) {
                val result = runCatching {
                    when (m.parameterTypes.size) {
                        2 -> m.invoke(pm, packageName, userId) as? Int
                        3 -> m.invoke(pm, packageName, userId, 0) as? Int
                        4 -> m.invoke(pm, packageName, userId, 0, 1) as? Int
                        else -> null
                    }
                }.getOrNull()
                if (result == 1) return true // INSTALL_SUCCEEDED
            }
        } catch (e: Exception) {
            Log.w(TAG, "restoreSystemApp IPC failed for $packageName, falling back", e)
        }
        return exec("pm", "install-existing", "--user", "0", packageName)
    }

    override fun suspendApp(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val userId = callingUserId()
        // Primary: IPackageManager.setPackagesSuspendedAsUser — shell UID has MANAGE_USERS
        try {
            val pm = packageManagerService() ?: error("no package service")
            val pkgArray = arrayOf(packageName)
            val method = pm.javaClass.methods.firstOrNull { it.name == "setPackagesSuspendedAsUser" }
            if (method != null) {
                val result = method.invoke(pm, *buildSuspendArgs(method, pkgArray, true, userId))
                // returns String[] of packages that failed — empty means all succeeded
                val failed = result as? Array<*>
                if (failed != null && failed.isEmpty()) return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "suspendApp IPC failed for $packageName, falling back", e)
        }
        return exec("pm", "suspend", "--user", "0", packageName)
    }

    override fun unsuspendApp(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val userId = callingUserId()
        // Primary: IPackageManager.setPackagesSuspendedAsUser
        try {
            val pm = packageManagerService() ?: error("no package service")
            val pkgArray = arrayOf(packageName)
            val method = pm.javaClass.methods.firstOrNull { it.name == "setPackagesSuspendedAsUser" }
            if (method != null) {
                val result = method.invoke(pm, *buildSuspendArgs(method, pkgArray, false, userId))
                val failed = result as? Array<*>
                if (failed != null && failed.isEmpty()) return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "unsuspendApp IPC failed for $packageName, falling back", e)
        }
        return exec("pm", "unsuspend", "--user", "0", packageName)
    }

    // Build args for setPackagesSuspendedAsUser — signature changed across API levels.
    // API 24-27: (String[], boolean, int)
    // API 28-29: (String[], boolean, PersistableBundle, PersistableBundle, String, int)
    // API 30+:   (String[], boolean, PersistableBundle, PersistableBundle, SuspendDialogInfo, String, int)
    private fun buildSuspendArgs(method: java.lang.reflect.Method, pkgs: Array<String>, suspend: Boolean, userId: Int): Array<Any?> {
        return when (method.parameterTypes.size) {
            3 -> arrayOf(pkgs, suspend, userId)
            6 -> arrayOf(pkgs, suspend, null, null, "com.android.shell", userId)
            7 -> arrayOf(pkgs, suspend, null, null, null, "com.android.shell", userId)
            else -> arrayOf(pkgs, suspend, userId)
        }
    }

    override fun isAppSuspended(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        // Primary: ApplicationInfo.FLAG_SUSPENDED — direct Binder IPC, no text parsing
        try {
            val ai = Android17Compat.getApplicationInfo(packageName, 0L, callingUserId())
            if (ai != null) return (ai.flags and ApplicationInfo.FLAG_SUSPENDED) != 0
        } catch (e: Exception) {
            Log.w(TAG, "isAppSuspended IPC failed for $packageName, falling back", e)
        }
        // Fallback: pm dump parse
        return try {
            Runtime.getRuntime().exec(arrayOf("pm", "dump", packageName))
                .inputStream.bufferedReader().use { it.readText() }
                .lines().any { it.trim() == "suspended=true" }
        } catch (_: Exception) { false }
    }

    override fun installApk(apkPath: String?): Boolean {
        if (apkPath.isNullOrBlank()) return false
        return exec("pm", "install", "-g", apkPath)
    }

    override fun isAppDebuggable(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        // Primary: ApplicationInfo.FLAG_DEBUGGABLE — direct Binder IPC
        try {
            val ai = Android17Compat.getApplicationInfo(packageName, 0L, callingUserId())
            if (ai != null) return (ai.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (e: Exception) {
            Log.w(TAG, "isAppDebuggable IPC failed for $packageName, falling back", e)
        }
        // Fallback: run-as exits 0 only for debuggable apps
        return exec("run-as", packageName, "true")
    }

    override fun isBackupAllowed(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        // Primary: ApplicationInfo.FLAG_ALLOW_BACKUP — direct Binder IPC
        try {
            val ai = Android17Compat.getApplicationInfo(packageName, 0L, callingUserId())
            if (ai != null) return (ai.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) != 0
        } catch (e: Exception) {
            Log.w(TAG, "isBackupAllowed IPC failed for $packageName, falling back", e)
        }
        // Fallback: pm dump parse
        return try {
            Runtime.getRuntime().exec(arrayOf("pm", "dump", packageName))
                .inputStream.bufferedReader().use { it.readText() }
                .lines().any { it.trim().equals("allowBackup=true", ignoreCase = true) }
        } catch (_: Exception) { false }
    }

    override fun getAppDataDir(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        // Primary: ApplicationInfo.dataDir — direct Binder IPC, no text parsing
        try {
            val ai = Android17Compat.getApplicationInfo(packageName, 0L, callingUserId())
            val dir = ai?.dataDir
            if (!dir.isNullOrEmpty()) return dir
        } catch (e: Exception) {
            Log.w(TAG, "getAppDataDir IPC failed for $packageName, falling back", e)
        }
        // Fallback: pm dump parse
        return try {
            Runtime.getRuntime().exec(arrayOf("pm", "dump", packageName))
                .inputStream.bufferedReader().use { it.readText() }
                .lines()
                .firstOrNull { it.trim().startsWith("dataDir=") }
                ?.trim()?.removePrefix("dataDir=")?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }
}
