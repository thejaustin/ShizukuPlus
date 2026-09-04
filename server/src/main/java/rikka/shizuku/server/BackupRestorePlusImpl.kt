package rikka.shizuku.server

import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import af.shizuku.server.IBackupRestorePlus
import java.io.File

class BackupRestorePlusImpl : IBackupRestorePlus.Stub() {

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

    // ── Package Inventory ─────────────────────────────────────────────────────

    override fun listInstalledPackages(includeSystem: Boolean): List<Bundle> {
        // pm list packages -f gives "package:<path>=<pkg>" lines.
        // pm dump <pkg> is expensive per-package; use pm list + pm path for bulk inventory.
        val args = if (includeSystem)
            arrayOf("pm", "list", "packages", "-f", "--show-versioncode")
        else
            arrayOf("pm", "list", "packages", "-f", "--show-versioncode", "-3")

        val output = exec(*args)
        val result = mutableListOf<Bundle>()
        for (line in output.lines()) {
            // Format: "package:<apkPath>=<pkgName>  versionCode:<N>"
            val pkgSection = line.removePrefix("package:").trim()
            val eqIdx = pkgSection.lastIndexOf('=')
            if (eqIdx < 0) continue
            val apkPath = pkgSection.substring(0, eqIdx)
            val rest = pkgSection.substring(eqIdx + 1)
            val parts = rest.split(" ")
            val packageName = parts[0]
            val versionCode = parts.find { it.startsWith("versionCode:") }
                ?.removePrefix("versionCode:")?.toLongOrNull() ?: -1L

            val b = Bundle()
            b.putString("packageName", packageName)
            b.putString("sourceDir", apkPath)
            b.putLong("versionCode", versionCode)
            // Lightweight flags: avoid pm dump per-package for the bulk list
            b.putBoolean("isSystem", apkPath.startsWith("/system/") || apkPath.startsWith("/product/") || apkPath.startsWith("/vendor/"))
            result.add(b)
        }
        return result
    }

    override fun getApkPaths(packageName: String?): List<String> {
        if (packageName.isNullOrBlank()) return emptyList()
        val output = exec("pm", "path", packageName)
        // Output: one or more lines of "package:<path>"
        return output.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
    }

    override fun streamApk(packageName: String?): ParcelFileDescriptor? {
        if (packageName.isNullOrBlank()) return null
        val paths = getApkPaths(packageName)
        val base = paths.firstOrNull { !it.contains("split_") } ?: paths.firstOrNull() ?: return null
        return pipe("cat", base)
    }

    override fun getAppDataSize(packageName: String?): Bundle {
        val b = Bundle()
        if (packageName.isNullOrBlank()) return b
        val dump = exec("dumpsys", "diskstats")
        // Android 8+ diskstats format per package:
        //   Package: <pkg> Code: <N> Data: <N> Cache: <N>
        val line = dump.lines().find { it.contains("Package: $packageName ") } ?: return b
        fun extractBytes(label: String): Long {
            val pattern = Regex("$label: (\\d+)")
            return pattern.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
        }
        b.putLong("codeBytes", extractBytes("Code"))
        b.putLong("dataBytes", extractBytes("Data"))
        b.putLong("cacheBytes", extractBytes("Cache"))
        return b
    }

    // ── Pre-backup / Pre-restore Utilities ───────────────────────────────────

    override fun forceStop(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return execExit("am", "force-stop", packageName) == 0
    }

    override fun clearAppData(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return execExit("pm", "clear", packageName) == 0
    }

    // ── ADB Backup / Restore ──────────────────────────────────────────────────

    override fun backupAppData(
        packageName: String?,
        includeApk: Boolean,
        includeShared: Boolean
    ): ParcelFileDescriptor? {
        if (packageName.isNullOrBlank()) return null
        // bu restore permission tightened in Android 12 (API 31 was fine, API 32+ is not)
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
            // Pipe the client PFD into bu restore's stdin on a background thread
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
        // Try both the primary external path and the Android/data path
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
        // pm install-create returns: "Success: created install session [<id>]"
        // Note: --multi-package is for installing multiple packages atomically, NOT for
        // split APKs of a single package. Split APKs use a plain session without that flag.
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
        try {
            Runtime.getRuntime().exec(arrayOf("pm", "install-abandon", sessionId.toString())).waitFor()
        } catch (_: Exception) {}
    }

    // ── Permission State ──────────────────────────────────────────────────────

    override fun getPermissionState(packageName: String?): List<Bundle> {
        if (packageName.isNullOrBlank()) return emptyList()
        val output = exec("pm", "dump", packageName)
        val result = mutableListOf<Bundle>()
        var inGranted = false
        var inRequested = false
        val granted = mutableSetOf<String>()
        val allRuntime = mutableSetOf<String>()

        for (line in output.lines()) {
            val t = line.trim()
            when {
                t == "requested permissions:" -> { inRequested = true; inGranted = false }
                t == "install permissions:" -> { inRequested = false; inGranted = false }
                t == "runtime permissions:" || t == "granted permissions:" -> {
                    inRequested = false; inGranted = true
                }
                inRequested && t.startsWith("android.permission.") -> allRuntime.add(t)
                inRequested && t.contains(".permission.") -> allRuntime.add(t)
                inGranted && t.startsWith("android.permission.") -> {
                    // "android.permission.FOO: granted=true, flags=..."
                    val name = t.substringBefore(":").trim()
                    val isGranted = t.contains("granted=true")
                    if (isGranted) granted.add(name)
                }
                inGranted && t.contains(".permission.") && t.contains(":") -> {
                    val name = t.substringBefore(":").trim()
                    val isGranted = t.contains("granted=true")
                    if (isGranted) granted.add(name)
                }
                // Blank line or next section header resets state
                t.isEmpty() && (inGranted || inRequested) -> {}
            }
        }

        // Build result: all runtime permissions with their grant state
        for (perm in allRuntime) {
            val b = Bundle()
            b.putString("name", perm)
            b.putBoolean("granted", perm in granted)
            result.add(b)
        }
        return result
    }

    override fun restorePermissions(packageName: String?, permissions: List<Bundle>?): Int {
        if (packageName.isNullOrBlank() || permissions.isNullOrEmpty()) return 0
        var count = 0
        for (perm in permissions) {
            val name = perm.getString("name") ?: continue
            if (!perm.getBoolean("granted", false)) continue
            if (execExit("pm", "grant", packageName, name) == 0) count++
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
        val output = exec("bmgr", "list", "sets")
        // Output format: "  <token>  <name>"
        for (line in output.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val parts = t.split("\\s+".toRegex(), 2)
            if (parts.size < 2) continue
            val b = Bundle()
            b.putString("token", parts[0])
            b.putString("name", parts[1])
            result.add(b)
        }
        return result
    }

    override fun getActiveBackupTransport(): String {
        val output = exec("bmgr", "list", "transports")
        // Output has "* <transport>" for the active one
        return output.lines()
            .firstOrNull { it.trimStart().startsWith("*") }
            ?.trim()
            ?.removePrefix("*")
            ?.trim()
            ?: ""
    }

    // ── Settings Backup / Restore ─────────────────────────────────────────────

    override fun dumpSettings(namespace: String?): Bundle {
        val b = Bundle()
        val ns = when (namespace?.lowercase()) {
            "global", "secure", "system" -> namespace.lowercase()
            else -> return b
        }
        val output = exec("settings", "list", ns)
        for (line in output.lines()) {
            val eq = line.indexOf('=')
            if (eq > 0) {
                b.putString(line.substring(0, eq).trim(), line.substring(eq + 1))
            }
        }
        return b
    }

    override fun restoreSettings(namespace: String?, settings: Bundle?): Int {
        val ns = when (namespace?.lowercase()) {
            "global", "secure", "system" -> namespace.lowercase()
            else -> return 0
        }
        if (settings == null || settings.isEmpty) return 0
        var count = 0
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

        val dump = exec("pm", "dump", packageName)
        if (dump.isBlank()) return b

        for (line in dump.lines()) {
            val t = line.trim()
            when {
                t.startsWith("userId=")           -> b.putInt("uid", t.removePrefix("userId=").trim().toIntOrNull() ?: -1)
                t.startsWith("versionName=")       -> b.putString("versionName", t.removePrefix("versionName=").trim())
                t.startsWith("dataDir=")           -> b.putString("dataDir", t.removePrefix("dataDir=").trim())
                t.startsWith("nativeLibraryDir=")  -> b.putString("nativeLibDir", t.removePrefix("nativeLibraryDir=").trim())
                t.startsWith("firstInstallTime=")  -> b.putString("firstInstallTime", t.removePrefix("firstInstallTime=").trim())
                t.startsWith("lastUpdateTime=")    -> b.putString("lastUpdateTime", t.removePrefix("lastUpdateTime=").trim())
                t.startsWith("versionCode=") || t.contains("versionCode=") -> {
                    // "versionCode=1234 minSdk=21 targetSdk=33"
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
        val output = exec("pm", "path", packageName)
        return output.lines()
            .filter { it.startsWith("package:") }
            .mapNotNull { line ->
                val path = line.removePrefix("package:").trim()
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
        // Validate: fileName must belong to this package's APK set
        val output = exec("pm", "path", packageName)
        val validPath = output.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .firstOrNull { File(it).name == fileName }
            ?: return null
        return pipe("cat", validPath)
    }

    // ── App Freeze / Unfreeze ─────────────────────────────────────────────────

    override fun freezeApp(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return execExit("pm", "disable-user", "--user", "0", packageName) == 0
    }

    override fun unfreezeApp(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return execExit("pm", "enable", "--user", "0", packageName) == 0
    }

    override fun isAppFrozen(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val dump = exec("pm", "dump", packageName)
        // "enabled=3" means COMPONENT_ENABLED_STATE_DISABLED_USER
        return dump.lines().any { line ->
            val t = line.trim()
            t.startsWith("enabled=") && (t.contains("=3") || t.contains("=2"))
        }
    }

    // ── SMS Restore ───────────────────────────────────────────────────────────

    override fun insertSmsMessages(messages: List<Bundle>?): Int {
        if (messages.isNullOrEmpty()) return 0
        var count = 0
        for (msg in messages) {
            val address = msg.getString("address") ?: continue
            val body    = msg.getString("body") ?: continue
            val date    = msg.getLong("date", System.currentTimeMillis())
            val type    = msg.getInt("type", 1)
            val read    = msg.getInt("read", 1)
            val result = execExit(
                "content", "insert",
                "--uri", "content://sms",
                "--bind", "address:s:$address",
                "--bind", "body:s:$body",
                "--bind", "date:l:$date",
                "--bind", "type:i:$type",
                "--bind", "read:i:$read"
            )
            if (result == 0) count++
        }
        return count
    }

    // ── Permission Management ─────────────────────────────────────────────────

    override fun revokeRuntimePermission(packageName: String?, permission: String?): Boolean {
        if (packageName.isNullOrBlank() || permission.isNullOrBlank()) return false
        return execExit("pm", "revoke", packageName, permission) == 0
    }

    override fun grantRuntimePermission(packageName: String?, permission: String?): Boolean {
        if (packageName.isNullOrBlank() || permission.isNullOrBlank()) return false
        return execExit("pm", "grant", packageName, permission) == 0
    }
}
