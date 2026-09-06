package rikka.shizuku.server

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.util.Log
import af.shizuku.server.IPackageGovernorPlus
import af.shizuku.common.compat.Android17Compat
import af.shizuku.common.util.UserHandleCompat

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
        return exec("pm", "uninstall", "--user", "0", packageName)
    }

    override fun restoreSystemApp(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return exec("pm", "install-existing", "--user", "0", packageName)
    }

    override fun suspendApp(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return exec("pm", "suspend", "--user", "0", packageName)
    }

    override fun unsuspendApp(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return exec("pm", "unsuspend", "--user", "0", packageName)
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
