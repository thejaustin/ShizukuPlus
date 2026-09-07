package rikka.shizuku.server

import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.ServiceManager
import android.util.Log
import af.shizuku.server.IBackupRestorePlus
import af.shizuku.common.compat.Android17Compat
import af.shizuku.common.compat.InstalledPackagesCompat
import af.shizuku.common.util.UserHandleCompat
import rikka.hidden.compat.ActivityManagerApis
import rikka.shizuku.server.api.IContentProviderUtils
import java.io.File

class BackupRestorePlusImpl : IBackupRestorePlus.Stub() {

    companion object {
        private const val TAG = "BackupRestorePlus"
        // PackageManager.REQUESTED_PERMISSION_GRANTED = 2
        private const val REQUESTED_PERMISSION_GRANTED = 2

        private fun packageManagerService(): Any? = try {
            val binder = ServiceManager.getService("package") ?: return null
            Class.forName("android.content.pm.IPackageManager\$Stub")
                .getDeclaredMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        } catch (e: Exception) {
            Log.w(TAG, "packageManagerService unavailable", e)
            null
        }
    }

    private fun callingUserId() = UserHandleCompat.getUserId(Binder.getCallingUid())

    private fun exec(vararg args: String): String = try {
        val proc = Runtime.getRuntime().exec(args)
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        out
    } catch (_: Exception) { "" }

    private fun execExit(vararg args: String): Int = try {
        Runtime.getRuntime().exec(args).waitFor()
    } catch (_: Exception) { -1 }

    private fun pipe(vararg args: String): ParcelFileDescriptor? = try {
        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
        Thread {
            try {
                val proc = Runtime.getRuntime().exec(args)
                proc.inputStream.use { src ->
                    ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { dst ->
                        src.copyTo(dst)
                    }
                }
                proc.waitFor()
            } catch (_: Exception) {
                try { writeSide.close() } catch (_: Exception) {}
            }
        }.also { it.isDaemon = true }.start()
        readSide
    } catch (_: Exception) { null }

    private fun parseContentRows(output: String): List<Bundle> =
        output.lines()
            .filter { it.trimStart().startsWith("Row:") }
            .map { row ->
                val b = Bundle()
                val content = row.substringAfter("Row:").trimStart().substringAfter(" ")
                for (pair in content.split(", ")) {
                    val eq = pair.indexOf('=')
                    if (eq > 0) b.putString(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim())
                }
                b
            }

    // ── Package Inventory ─────────────────────────────────────────────────────

    override fun listInstalledPackages(includeSystem: Boolean): List<Bundle> {
        val userId = callingUserId()
        // Primary: InstalledPackagesCompat — works on Android 17 without exec
        try {
            val flags: Long = if (includeSystem) 0L else PackageManager.MATCH_SYSTEM_ONLY.toLong().inv().and(0xFFFFL)
            val packages = InstalledPackagesCompat.getInstalledPackagesNoThrow(0L, userId)
            if (packages.isNotEmpty()) {
                return packages
                    .filter { pi -> includeSystem || (pi.applicationInfo?.flags?.and(android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) }
                    .map { pi ->
                        val ai = pi.applicationInfo
                        Bundle().apply {
                            putString("packageName", pi.packageName)
                            putString("sourceDir", ai?.sourceDir)
                            putLong("versionCode", pi.longVersionCode)
                            putBoolean("isSystem", (ai?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0)
                        }
                    }
            }
        } catch (e: Exception) {
            Log.w(TAG, "listInstalledPackages IPC failed, falling back to exec", e)
        }
        // Fallback: pm list packages
        val args = if (includeSystem)
            arrayOf("pm", "list", "packages", "-f", "--show-versioncode")
        else
            arrayOf("pm", "list", "packages", "-f", "--show-versioncode", "-3")
        val output = exec(*args)
        val result = mutableListOf<Bundle>()
        for (line in output.lines()) {
            val pkgSection = line.removePrefix("package:").trim()
            val eqIdx = pkgSection.lastIndexOf('=')
            if (eqIdx < 0) continue
            val apkPath = pkgSection.substring(0, eqIdx)
            val rest = pkgSection.substring(eqIdx + 1)
            val parts = rest.split(" ")
            val packageName = parts[0]
            val versionCode = parts.find { it.startsWith("versionCode:") }
                ?.removePrefix("versionCode:")?.toLongOrNull() ?: -1L
            result.add(Bundle().apply {
                putString("packageName", packageName)
                putString("sourceDir", apkPath)
                putLong("versionCode", versionCode)
                putBoolean("isSystem", apkPath.startsWith("/system/") || apkPath.startsWith("/product/") || apkPath.startsWith("/vendor/"))
            })
        }
        return result
    }

    override fun getApkPaths(packageName: String?): List<String> {
        if (packageName.isNullOrBlank()) return emptyList()
        val userId = callingUserId()
        // Primary: ApplicationInfo.sourceDir + splitSourceDirs
        try {
            val ai = Android17Compat.getApplicationInfo(packageName, 0L, userId)
            if (ai != null) {
                val paths = mutableListOf<String>()
                ai.sourceDir?.let { paths.add(it) }
                ai.splitSourceDirs?.forEach { paths.add(it) }
                if (paths.isNotEmpty()) return paths
            }
        } catch (e: Exception) {
            Log.w(TAG, "getApkPaths IPC failed for $packageName, falling back", e)
        }
        // Fallback: pm path parse
        return exec("pm", "path", packageName).lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
    }

    override fun streamApk(packageName: String?): ParcelFileDescriptor? {
        if (packageName.isNullOrBlank()) return null
        val paths = getApkPaths(packageName)
        val base = paths.firstOrNull { !it.contains("split_") } ?: paths.firstOrNull() ?: return null
        return try {
            ParcelFileDescriptor.open(File(base), ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            Log.w(TAG, "streamApk direct open failed for $base, falling back to cat", e)
            pipe("cat", base)
        }
    }

    override fun getAppDataSize(packageName: String?): Bundle {
        val b = Bundle()
        if (packageName.isNullOrBlank()) return b
        val dump = exec("dumpsys", "diskstats")
        val line = dump.lines().find { it.contains("Package: $packageName ") } ?: return b
        fun extractBytes(label: String): Long {
            return Regex("$label: (\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
        }
        b.putLong("codeBytes", extractBytes("Code"))
        b.putLong("dataBytes", extractBytes("Data"))
        b.putLong("cacheBytes", extractBytes("Cache"))
        return b
    }

    // ── Pre-backup / Pre-restore Utilities ───────────────────────────────────

    override fun forceStop(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        // Primary: ActivityManagerApis.forceStopPackageNoThrow — works at shell UID
        return try {
            ActivityManagerApis.forceStopPackageNoThrow(packageName, callingUserId())
            true
        } catch (e: Exception) {
            Log.w(TAG, "forceStop IPC failed for $packageName, falling back", e)
            execExit("am", "force-stop", packageName) == 0
        }
    }

    override fun clearAppData(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val userId = callingUserId()
        // Primary: IPackageManager.clearApplicationUserData with blocking observer
        try {
            val pm = packageManagerService() ?: error("no package service")
            val latch = java.util.concurrent.CountDownLatch(1)
            val observer = object : android.content.pm.IPackageDataObserver.Stub() {
                override fun onRemoveCompleted(pkgName: String?, succeeded: Boolean) { latch.countDown() }
            }
            val method = pm.javaClass.methods.firstOrNull { it.name == "clearApplicationUserData" }
                ?: error("clearApplicationUserData not found")
            method.invoke(pm, packageName, observer, userId)
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "clearAppData IPC failed for $packageName, falling back", e)
        }
        return execExit("pm", "clear", packageName) == 0
    }

    // ── ADB Backup / Restore ──────────────────────────────────────────────────

    override fun backupAppData(
        packageName: String?,
        includeApk: Boolean,
        includeShared: Boolean
    ): ParcelFileDescriptor? {
        if (packageName.isNullOrBlank()) return null
        if (Build.VERSION.SDK_INT >= 32) return null
        val cmd = mutableListOf("bu", "backup")
        if (!includeApk) cmd += "-noapk"
        if (!includeShared) cmd += "-noshared"
        cmd += packageName
        return pipe(*cmd.toTypedArray())
    }

    override fun restoreAppData(backupStream: ParcelFileDescriptor?): Boolean {
        if (backupStream == null) return false
        if (Build.VERSION.SDK_INT >= 32) return false
        return try {
            val pb = ProcessBuilder("bu", "restore")
            pb.redirectErrorStream(false)
            val proc = pb.start()
            Thread {
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(backupStream).use { src ->
                        proc.outputStream.use { dst -> src.copyTo(dst) }
                    }
                } catch (_: Exception) {
                    proc.outputStream.runCatching { close() }
                }
            }.also { it.isDaemon = true }.start()
            proc.waitFor() == 0
        } catch (_: Exception) { false }
    }

    // ── External Storage Backup / Restore ─────────────────────────────────────

    override fun backupExternalData(packageName: String?): ParcelFileDescriptor? {
        if (packageName.isNullOrBlank()) return null
        val dir = listOf(
            "/sdcard/Android/data/$packageName",
            "/storage/emulated/0/Android/data/$packageName"
        ).firstOrNull { File(it).exists() } ?: return null
        return pipe("tar", "-czf", "-", "-C", dir, ".")
    }

    override fun restoreExternalData(packageName: String?, tarStream: ParcelFileDescriptor?): Boolean {
        if (packageName.isNullOrBlank() || tarStream == null) return false
        val dir = "/sdcard/Android/data/$packageName"
        File(dir).mkdirs()
        return try {
            val proc = ProcessBuilder("tar", "-xzf", "-", "-C", dir).start()
            Thread {
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(tarStream).use { src ->
                        proc.outputStream.use { dst -> src.copyTo(dst) }
                    }
                } catch (_: Exception) {
                    proc.outputStream.runCatching { close() }
                }
            }.also { it.isDaemon = true }.start()
            proc.waitFor() == 0
        } catch (_: Exception) { false }
    }

    // ── Streaming APK Install ─────────────────────────────────────────────────

    override fun createInstallSession(packageName: String?): Int {
        val output = exec("pm", "install-create", "-g")
        val match = Regex("\\[(\\d+)]").find(output)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    override fun writeApkToSession(
        sessionId: Int,
        splitName: String?,
        apkData: ParcelFileDescriptor?
    ): Boolean {
        if (sessionId < 0 || apkData == null) return false
        val name = if (splitName.isNullOrBlank()) "base.apk" else splitName
        return try {
            val proc = ProcessBuilder("pm", "install-write", sessionId.toString(), name, "-").start()
            Thread {
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(apkData).use { src ->
                        proc.outputStream.use { dst -> src.copyTo(dst) }
                    }
                } catch (_: Exception) {
                    proc.outputStream.runCatching { close() }
                }
            }.also { it.isDaemon = true }.start()
            proc.waitFor() == 0
        } catch (_: Exception) { false }
    }

    override fun commitInstallSession(sessionId: Int): Boolean {
        if (sessionId < 0) return false
        return execExit("pm", "install-commit", sessionId.toString()) == 0
    }

    override fun abandonInstallSession(sessionId: Int) {
        if (sessionId < 0) return
        try { Runtime.getRuntime().exec(arrayOf("pm", "install-abandon", sessionId.toString())).waitFor() } catch (_: Exception) {}
    }

    // ── Permission State ──────────────────────────────────────────────────────

    override fun getPermissionState(packageName: String?): List<Bundle> {
        if (packageName.isNullOrBlank()) return emptyList()
        val userId = callingUserId()
        // Primary: PackageInfo with GET_PERMISSIONS — all runtime perms + grant flags
        try {
            val info = Android17Compat.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS.toLong(), userId)
            if (info != null) {
                val perms = info.requestedPermissions ?: return emptyList()
                val flags = info.requestedPermissionsFlags ?: IntArray(perms.size)
                return perms.mapIndexed { i, perm ->
                    Bundle().apply {
                        putString("name", perm)
                        putBoolean("granted", flags.getOrElse(i) { 0 } and REQUESTED_PERMISSION_GRANTED != 0)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getPermissionState IPC failed for $packageName, falling back", e)
        }
        // Fallback: pm dump parse
        val output = exec("pm", "dump", packageName)
        val result = mutableListOf<Bundle>()
        var inGranted = false; var inRequested = false
        val granted = mutableSetOf<String>()
        val allRuntime = mutableSetOf<String>()
        for (line in output.lines()) {
            val t = line.trim()
            when {
                t == "requested permissions:" -> { inRequested = true; inGranted = false }
                t == "install permissions:" -> { inRequested = false; inGranted = false }
                t == "runtime permissions:" || t == "granted permissions:" -> { inRequested = false; inGranted = true }
                inRequested && (t.startsWith("android.permission.") || t.contains(".permission.")) -> allRuntime.add(t)
                inGranted && t.contains(".permission.") && t.contains(":") -> {
                    val name = t.substringBefore(":").trim()
                    if (t.contains("granted=true")) granted.add(name)
                }
            }
        }
        for (perm in allRuntime) {
            result.add(Bundle().apply { putString("name", perm); putBoolean("granted", perm in granted) })
        }
        return result
    }

    override fun restorePermissions(packageName: String?, permissions: List<Bundle>?): Int {
        if (packageName.isNullOrBlank() || permissions.isNullOrEmpty()) return 0
        val userId = callingUserId()
        var count = 0
        for (perm in permissions) {
            val name = perm.getString("name") ?: continue
            if (!perm.getBoolean("granted", false)) continue
            // Primary: Android17Compat.grantRuntimePermission — handles Android 17 deviceId
            try {
                Android17Compat.grantRuntimePermission(packageName, name, userId)
                count++
            } catch (e: Exception) {
                if (execExit("pm", "grant", packageName, name) == 0) count++
            }
        }
        return count
    }

    // ── BackupManager (bmgr) ──────────────────────────────────────────────────

    override fun isBackupEnabled(): Boolean {
        val out = exec("bmgr", "enabled")
        return out.contains("enabled") && !out.contains("disabled")
    }

    override fun requestBmgrBackup(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return execExit("bmgr", "backup", packageName) == 0
    }

    override fun listBmgrBackupSets(): List<Bundle> {
        val result = mutableListOf<Bundle>()
        for (line in exec("bmgr", "list", "sets").lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val parts = t.split("\\s+".toRegex(), 2)
            if (parts.size < 2) continue
            result.add(Bundle().apply { putString("token", parts[0]); putString("name", parts[1]) })
        }
        return result
    }

    override fun getActiveBackupTransport(): String {
        return exec("bmgr", "list", "transports").lines()
            .firstOrNull { it.trimStart().startsWith("*") }
            ?.trim()?.removePrefix("*")?.trim() ?: ""
    }

    // ── Settings Backup / Restore ─────────────────────────────────────────────

    override fun dumpSettings(namespace: String?): Bundle {
        val b = Bundle()
        val ns = when (namespace?.lowercase()) {
            "global", "secure", "system" -> namespace.lowercase()
            else -> return b
        }
        for (line in exec("settings", "list", ns).lines()) {
            val eq = line.indexOf('=')
            if (eq > 0) b.putString(line.substring(0, eq).trim(), line.substring(eq + 1))
        }
        return b
    }

    override fun restoreSettings(namespace: String?, settings: Bundle?): Int {
        val ns = when (namespace?.lowercase()) {
            "global", "secure", "system" -> namespace.lowercase()
            else -> return 0
        }
        if (settings == null || settings.isEmpty) return 0
        // Primary: ContentProvider PUT for each key
        var count = 0
        try {
            val userId = callingUserId()
            val provider = ActivityManagerApis.getContentProviderExternal(
                "settings", userId, null, "com.android.shell"
            )
            if (provider != null) {
                for (key in settings.keySet()) {
                    val value = settings.getString(key) ?: continue
                    try {
                        val extras = android.os.Bundle().apply { putString("value", value) }
                        IContentProviderUtils.callCompat(provider, null, "settings", "PUT_$ns", key, extras)
                        count++
                    } catch (_: Exception) {}
                }
                return count
            }
        } catch (e: Exception) {
            Log.w(TAG, "restoreSettings ContentProvider failed, falling back to exec", e)
        }
        // Fallback: settings put exec
        for (key in settings.keySet()) {
            val value = settings.getString(key) ?: continue
            if (execExit("settings", "put", ns, key, value) == 0) count++
        }
        return count
    }

    // ── OBB Data Backup / Restore ─────────────────────────────────────────────

    override fun backupObbData(packageName: String?): ParcelFileDescriptor? {
        if (packageName.isNullOrBlank()) return null
        val dir = listOf(
            "/sdcard/Android/obb/$packageName",
            "/storage/emulated/0/Android/obb/$packageName"
        ).firstOrNull { File(it).exists() } ?: return null
        return pipe("tar", "-czf", "-", "-C", dir, ".")
    }

    override fun restoreObbData(packageName: String?, tarStream: ParcelFileDescriptor?): Boolean {
        if (packageName.isNullOrBlank() || tarStream == null) return false
        val dir = "/sdcard/Android/obb/$packageName"
        File(dir).mkdirs()
        return try {
            val proc = ProcessBuilder("tar", "-xzf", "-", "-C", dir).start()
            Thread {
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(tarStream).use { src ->
                        proc.outputStream.use { dst -> src.copyTo(dst) }
                    }
                } catch (_: Exception) {
                    proc.outputStream.runCatching { close() }
                }
            }.also { it.isDaemon = true }.start()
            proc.waitFor() == 0
        } catch (_: Exception) { false }
    }

    // ── Detailed Package Metadata ─────────────────────────────────────────────

    override fun getPackageMetadata(packageName: String?): Bundle {
        val b = Bundle()
        if (packageName.isNullOrBlank()) return b
        val userId = callingUserId()
        // Primary: PackageInfo + ApplicationInfo via Binder IPC
        try {
            val pi = Android17Compat.getPackageInfo(packageName, 0L, userId)
            val ai = pi?.applicationInfo ?: Android17Compat.getApplicationInfo(packageName, 0L, userId)
            if (pi != null || ai != null) {
                ai?.let { a ->
                    b.putInt("uid", a.uid)
                    b.putString("dataDir", a.dataDir)
                    b.putString("nativeLibDir", a.nativeLibraryDir)
                    b.putBoolean("isDebuggable", (a.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                    b.putBoolean("allowBackup", (a.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP) != 0)
                    b.putBoolean("isSystem", (a.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                }
                pi?.let { p ->
                    b.putString("versionName", p.versionName)
                    b.putLong("versionCode", p.longVersionCode)
                    b.putString("firstInstallTime", p.firstInstallTime.toString())
                    b.putString("lastUpdateTime", p.lastUpdateTime.toString())
                    b.putInt("targetSdk", p.applicationInfo?.targetSdkVersion ?: -1)
                    b.putInt("minSdk", p.applicationInfo?.minSdkVersion ?: -1)
                }
                return b
            }
        } catch (e: Exception) {
            Log.w(TAG, "getPackageMetadata IPC failed for $packageName, falling back", e)
        }
        // Fallback: pm dump parse
        val dump = exec("pm", "dump", packageName)
        if (dump.isBlank()) return b
        for (line in dump.lines()) {
            val t = line.trim()
            when {
                t.startsWith("userId=") -> b.putInt("uid", t.removePrefix("userId=").trim().toIntOrNull() ?: -1)
                t.startsWith("versionName=") -> b.putString("versionName", t.removePrefix("versionName=").trim())
                t.startsWith("dataDir=") -> b.putString("dataDir", t.removePrefix("dataDir=").trim())
                t.startsWith("nativeLibraryDir=") -> b.putString("nativeLibDir", t.removePrefix("nativeLibraryDir=").trim())
                t.startsWith("firstInstallTime=") -> b.putString("firstInstallTime", t.removePrefix("firstInstallTime=").trim())
                t.startsWith("lastUpdateTime=") -> b.putString("lastUpdateTime", t.removePrefix("lastUpdateTime=").trim())
                t.startsWith("versionCode=") || t.contains("versionCode=") -> {
                    Regex("versionCode=(\\d+)").find(t)?.groupValues?.get(1)?.toLongOrNull()?.let { b.putLong("versionCode", it) }
                    Regex("targetSdk=(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { b.putInt("targetSdk", it) }
                    Regex("minSdk=(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { b.putInt("minSdk", it) }
                }
                t.startsWith("pkgFlags=") || t.startsWith("flags=") -> {
                    b.putBoolean("isDebuggable", t.contains("DEBUGGABLE"))
                    b.putBoolean("allowBackup", t.contains("ALLOW_BACKUP"))
                    b.putBoolean("isSystem", t.contains("SYSTEM"))
                }
            }
        }
        return b
    }

    // ── Split APK Streaming ───────────────────────────────────────────────────

    override fun listApkSplits(packageName: String?): List<Bundle> {
        if (packageName.isNullOrBlank()) return emptyList()
        return getApkPaths(packageName).mapNotNull { path ->
            val file = File(path)
            if (!file.exists()) return@mapNotNull null
            Bundle().apply {
                putString("fileName", file.name)
                putString("path", path)
                putLong("size", file.length())
            }
        }
    }

    override fun streamApkSplit(packageName: String?, fileName: String?): ParcelFileDescriptor? {
        if (packageName.isNullOrBlank() || fileName.isNullOrBlank()) return null
        val validPath = getApkPaths(packageName).firstOrNull { File(it).name == fileName } ?: return null
        return try {
            ParcelFileDescriptor.open(File(validPath), ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            Log.w(TAG, "streamApkSplit direct open failed for $validPath, falling back to cat", e)
            pipe("cat", validPath)
        }
    }

    // ── App Freeze / Unfreeze ─────────────────────────────────────────────────

    private fun setApplicationEnabledSetting(packageName: String, state: Int): Boolean {
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
        if (packageName.isNullOrBlank()) return false
        // COMPONENT_ENABLED_STATE_DISABLED_USER = 3
        if (setApplicationEnabledSetting(packageName, 3)) return true
        return execExit("pm", "disable-user", "--user", "0", packageName) == 0
    }

    override fun unfreezeApp(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        // COMPONENT_ENABLED_STATE_DEFAULT = 0
        if (setApplicationEnabledSetting(packageName, 0)) return true
        return execExit("pm", "enable", "--user", "0", packageName) == 0
    }

    override fun isAppFrozen(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        // Primary: ApplicationInfo.enabled — direct Binder IPC
        try {
            val ai = Android17Compat.getApplicationInfo(packageName, 0L, callingUserId())
            if (ai != null) return !ai.enabled
        } catch (e: Exception) {
            Log.w(TAG, "isAppFrozen IPC failed for $packageName, falling back", e)
        }
        // Fallback: pm dump parse — "enabled=3" = DISABLED_USER, "enabled=2" = DISABLED
        return exec("pm", "dump", packageName).lines().any { line ->
            val t = line.trim()
            t.startsWith("enabled=") && (t.contains("=3") || t.contains("=2"))
        }
    }

    // ── SMS Restore ───────────────────────────────────────────────────────────

    override fun insertSmsMessages(messages: List<Bundle>?): Int {
        if (messages.isNullOrEmpty()) return 0
        val smsUri = android.net.Uri.parse("content://sms")
        val smsAuthority = "sms"
        val userId = callingUserId()
        // Primary: IContentProvider.insert() via Binder — no exec needed
        val provider = try {
            ActivityManagerApis.getContentProviderExternal(smsAuthority, userId, null, "com.android.shell")
        } catch (_: Exception) { null }

        var count = 0
        for (msg in messages) {
            val address = msg.getString("address") ?: continue
            val body    = msg.getString("body") ?: continue
            val date    = msg.getLong("date", System.currentTimeMillis())
            val type    = msg.getInt("type", 1)
            val read    = msg.getInt("read", 1)

            if (provider != null) {
                val cv = android.content.ContentValues().apply {
                    put("address", address)
                    put("body", body)
                    put("date", date)
                    put("type", type)
                    put("read", read)
                }
                val inserted = runCatching {
                    val insertMethods = provider.javaClass.methods.filter { it.name == "insert" }
                    insertMethods.any { m ->
                        runCatching {
                            when (m.parameterTypes.size) {
                                4 -> m.invoke(provider, "com.android.shell", null, smsUri, cv) != null
                                3 -> m.invoke(provider, "com.android.shell", smsUri, cv) != null
                                else -> false
                            }
                        }.getOrDefault(false)
                    }
                }.getOrDefault(false)
                if (inserted) { count++; continue }
            }
            // Fallback: content insert exec
            val result = execExit(
                "content", "insert", "--uri", "content://sms",
                "--bind", "address:s:$address", "--bind", "body:s:$body",
                "--bind", "date:l:$date", "--bind", "type:i:$type", "--bind", "read:i:$read"
            )
            if (result == 0) count++
        }
        return count
    }

    // ── Permission Management ─────────────────────────────────────────────────

    override fun revokeRuntimePermission(packageName: String?, permission: String?): Boolean {
        if (packageName.isNullOrBlank() || permission.isNullOrBlank()) return false
        return try {
            Android17Compat.revokeRuntimePermission(packageName, permission, callingUserId())
            true
        } catch (e: Exception) {
            Log.w(TAG, "revokeRuntimePermission IPC failed for $packageName/$permission, falling back", e)
            execExit("pm", "revoke", packageName, permission) == 0
        }
    }

    override fun grantRuntimePermission(packageName: String?, permission: String?): Boolean {
        if (packageName.isNullOrBlank() || permission.isNullOrBlank()) return false
        return try {
            Android17Compat.grantRuntimePermission(packageName, permission, callingUserId())
            true
        } catch (e: Exception) {
            Log.w(TAG, "grantRuntimePermission IPC failed for $packageName/$permission, falling back", e)
            execExit("pm", "grant", packageName, permission) == 0
        }
    }
}
