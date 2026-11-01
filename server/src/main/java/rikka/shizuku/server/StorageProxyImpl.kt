package rikka.shizuku.server

import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.util.Log
import af.shizuku.server.IStorageProxy
import af.shizuku.common.util.UserHandleCompat
import rikka.hidden.compat.ActivityManagerApis
import rikka.shizuku.server.util.InputValidationUtils
import rikka.shizuku.server.util.BinderCallLogger
import rikka.shizuku.server.util.ShellExecutor
import rikka.shizuku.server.util.ArchiveVetting
import rikka.shizuku.server.util.StorageAccessPolicy
import java.io.File
import java.util.function.BooleanSupplier

/**
 * @param unrestricted the "Unrestricted app data access" developer option. While it is off,
 *     [StorageAccessPolicy] keeps third-party callers out of other apps' private data.
 * @param personalData the "Personal data & device control" option, or the caller is the manager.
 *     [openContentUri] reads any provider the shell can (SMS, MMS, contacts, call log), so it
 *     needs that option, not just this one.
 */
class StorageProxyImpl(
    private val unrestricted: BooleanSupplier,
    private val personalData: BooleanSupplier,
) : IStorageProxy.Stub() {

    // Cached once per process — getuid() is a syscall, not a field.
    private val serverUid: Int by lazy { android.system.Os.getuid() }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        BinderCallLogger.log(Log.isLoggable("BinderCall", Log.INFO),
            IStorageProxy.Stub::class.java, code, Binder.getCallingUid())
        return super.onTransact(code, data, reply, flags)
    }

    /**
     * The canonical form of [path] if the caller may touch it, else null. Every method works on
     * this canonical string so a symlink or `..` can't pass the check and then reach elsewhere.
     */
    private fun allowedPath(path: String?, write: Boolean): String? {
        if (path.isNullOrEmpty() || !InputValidationUtils.isSafePath(path)) return null
        val canonical = runCatching { File(path).canonicalPath }.getOrNull() ?: return null
        if (!StorageAccessPolicy.isAllowed(canonical, write, unrestricted.asBoolean)) {
            Log.w("StorageProxy", "denied ${if (write) "write" else "read"} of $canonical (developer option off)")
            return null
        }
        return canonical
    }

    private fun isAppDataPath(path: String) = path.startsWith("/data/data/") || path.startsWith("/data/user/")

    // Shared storage is case-insensitive, so /android/DATA reaches the same folder.
    private fun isExternalAppPath(path: String) =
        path.contains("/Android/data", ignoreCase = true) || path.contains("/Android/obb", ignoreCase = true)

    override fun openFile(path: String?, mode: Int): ParcelFileDescriptor? {
        val write = mode and WRITE_MODE_BITS != 0
        val path = allowedPath(path, write) ?: return null
        return try {
            try {
                ParcelFileDescriptor.open(File(path), mode)
            } catch (e: Exception) {
                // The fallbacks below are read-only pipes; never hand one out for a write request.
                if (write) throw e
                // Android 13+ (API 33) progressively restricts /Android/data and /Android/obb to the owning
                // app's UID; Android 16 (API 36) + OneUI 8 tightened it further. The shell-pipe fallback
                // works from API 33 onwards — not just the API 36+ check that was here before.
                if (android.os.Build.VERSION.SDK_INT >= 33 && isExternalAppPath(path)) {
                    return openViaShellPipe(arrayOf("sh", "-c", "cat \"$1\"", "sh", path))
                }
                // ADB mode (UID 2000): /data/data/<pkg>/ is owned by the app UID, but `run-as`
                // lets the shell impersonate the target app if it is debuggable. Only attempt
                // this when direct open already failed — no-op for non-debuggable or root mode.
                if (serverUid == 2000 && isAppDataPath(path)) {
                    val pkg = extractPackageName(path)
                    if (pkg != null) {
                        return openViaShellPipe(arrayOf("run-as", pkg, "cat", path))
                    }
                }
                throw e
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractPackageName(path: String): String? {
        val pkg = when {
            path.startsWith("/data/data/") ->
                path.removePrefix("/data/data/").substringBefore("/")
            path.startsWith("/data/user/") ->
                path.removePrefix("/data/user/").substringAfter("/").substringBefore("/")
            else -> null
        }
        // Basic package-name sanity: must contain a dot and only safe characters.
        // `run-as` enforces its own security (debuggable flag); we only guard against injection.
        return pkg?.takeIf { it.isNotEmpty() && it.contains('.') &&
                it.matches(Regex("[a-zA-Z0-9_.]+")) }
    }

    // Spawns `cmd` and pipes its stdout into the write end of an Android pipe on a daemon thread,
    // returning the read end to the caller. AutoCloseOutputStream closes the write end on exit,
    // so the reader sees EOF when the process finishes — no leaked fd, no hung readers.
    //
    // Note: ParcelFileDescriptor.createPipe() sets O_CLOEXEC on both ends, so we cannot pass the
    // fd number via /proc/self/fd/<n> in the child's command string — exec() closes those fds
    // before the shell starts. Instead we copy stdout here in the parent process.
    private fun openViaShellPipe(cmd: Array<String>): ParcelFileDescriptor? {
        return try {
            val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
            Thread {
                try {
                    val proc = Runtime.getRuntime().exec(cmd)
                    try {
                        proc.inputStream.use { src ->
                            ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { dst ->
                                src.copyTo(dst)
                            }
                        }
                        proc.waitFor()
                    } finally {
                        proc.destroy()
                    }
                } catch (_: Exception) {
                    try { writeSide.close() } catch (_: Exception) {}
                }
            }.also { it.isDaemon = true }.start()
            readSide
        } catch (_: Exception) {
            null
        }
    }

    override fun exists(path: String?): Boolean {
        val path = allowedPath(path, write = false) ?: return false
        val file = File(path)
        if (file.exists()) return true
        if (isExternalAppPath(path)) {
            return ShellExecutor.execBool("sh", "-c", "[ -e \"$1\" ]", "sh", path)
        }
        return false
    }

    override fun delete(path: String?): Boolean {
        val path = allowedPath(path, write = true) ?: return false
        val file = File(path)
        if (file.delete()) return true
        if (isExternalAppPath(path)) {
            return ShellExecutor.execBool("sh", "-c", "rm -rf \"$1\"", "sh", path)
        }
        return false
    }

    override fun listFiles(path: String?): List<String> {
        val path = allowedPath(path, write = false) ?: return emptyList()
        val direct = File(path).list()
        if (!direct.isNullOrEmpty()) return direct.toList()
        if (isExternalAppPath(path)) {
            return ShellExecutor.exec("sh", "-c", "ls -1 \"$1\"", "sh", path)
                .lines().filter { it.isNotBlank() }
        }
        // For /data/data/<pkg>/ paths (ADB mode, debuggable apps only)
        if (serverUid == 2000 && isAppDataPath(path)) {
            val pkg = extractPackageName(path) ?: return emptyList()
            return ShellExecutor.exec("run-as", pkg, "ls", path)
                .lines().filter { it.isNotBlank() }
        }
        return emptyList()
    }

    override fun getFileInfo(path: String?): Bundle {
        val bundle = Bundle()
        bundle.putBoolean("exists", false)
        val safePath = allowedPath(path, write = false) ?: return bundle
        val file = File(safePath)
        if (file.exists()) {
            bundle.putBoolean("exists", true)
            bundle.putLong("size", file.length())
            bundle.putLong("lastModified", file.lastModified())
            bundle.putBoolean("isDirectory", file.isDirectory)
        } else if (isExternalAppPath(safePath)) {
            val out = ShellExecutor.exec("sh", "-c", "stat -c '%s %Y %F' \"$1\" 2>/dev/null", "sh", safePath)
                .lines().firstOrNull { it.isNotBlank() }
            if (!out.isNullOrBlank()) {
                val parts = out.trim().split(" ")
                if (parts.size >= 2) {
                    bundle.putBoolean("exists", true)
                    bundle.putLong("size", parts[0].toLongOrNull() ?: 0L)
                    bundle.putLong("lastModified", (parts[1].toLongOrNull() ?: 0L) * 1000)
                    bundle.putBoolean("isDirectory", out.contains("directory", ignoreCase = true))
                }
            }
        }
        return bundle
    }

    override fun mkdir(path: String?): Boolean {
        val path = allowedPath(path, write = true) ?: return false
        val file = File(path)
        if (file.mkdirs()) return true
        if (isExternalAppPath(path)) {
            return ShellExecutor.execBool("sh", "-c", "mkdir -p \"$1\"", "sh", path)
        }
        return false
    }

    override fun copyFile(srcPath: String?, destPath: String?): Boolean {
        val srcPath = allowedPath(srcPath, write = false) ?: return false
        val destPath = allowedPath(destPath, write = true) ?: return false
        return try {
            File(srcPath).inputStream().use { src ->
                File(destPath).outputStream().use { dst -> src.copyTo(dst) }
            }
            true
        } catch (_: Exception) {
            if (isExternalAppPath(srcPath) || isExternalAppPath(destPath)) {
                return ShellExecutor.execBool("sh", "-c", "cp -rf \"$1\" \"$2\"", "sh", srcPath, destPath)
            }
            // For /data/data/<pkg>/ paths, fall back to run-as cp
            if (serverUid == 2000 && isAppDataPath(srcPath)) {
                val pkg = extractPackageName(srcPath) ?: return false
                return ShellExecutor.execBool("run-as", pkg, "cp", srcPath, destPath)
            }
            false
        }
    }

    override fun openContentUri(contentUri: String?): ParcelFileDescriptor? {
        if (contentUri.isNullOrBlank() || !contentUri.startsWith("content://")) return null
        if (!personalData.asBoolean) {
            Log.w("StorageProxy", "denied openContentUri (developer option off)")
            return null
        }
        // Primary: IContentProvider.openFile() via Binder — no exec needed
        try {
            val uri = Uri.parse(contentUri)
            val authority = uri.authority ?: throw Exception("no authority in URI")
            val userId = UserHandleCompat.getUserId(Binder.getCallingUid())
            val provider = ActivityManagerApis.getContentProviderExternal(authority, userId, null, "com.android.shell")
                ?: throw Exception("provider unavailable for $authority")
            val methods = provider.javaClass.methods.filter { it.name == "openFile" }
            for (m in methods) {
                val pfd = runCatching {
                    when (m.parameterTypes.size) {
                        5 -> m.invoke(provider, null, uri, "r", null, null) as? ParcelFileDescriptor
                        4 -> m.invoke(provider, null, uri, "r", null) as? ParcelFileDescriptor
                        3 -> m.invoke(provider, uri, "r", null) as? ParcelFileDescriptor
                        2 -> m.invoke(provider, uri, "r") as? ParcelFileDescriptor
                        else -> null
                    }
                }.getOrNull()
                if (pfd != null) return pfd
            }
        } catch (e: Exception) {
            Log.w("StorageProxy", "openContentUri IPC failed, falling back to exec", e)
        }
        // Fallback: content read exec
        return openViaShellPipe(arrayOf("content", "read", "--uri", contentUri))
    }

    override fun tarDirectory(dirPath: String?, packageContext: String?): ParcelFileDescriptor? {
        val dir = allowedPath(dirPath, write = false) ?: return null
        return if (InputValidationUtils.isValidPackageName(packageContext) && serverUid == 2000 && isAppDataPath(dir)) {
            // run-as <pkg> tar for debuggable-app data directories; isValidPackageName guards non-null
            openViaShellPipe(arrayOf("run-as", packageContext!!, "tar", "-czf", "-", "-C", dir, "."))
        } else {
            openViaShellPipe(arrayOf("tar", "-czf", "-", "-C", dir, "."))
        }
    }

    override fun restoreTarDirectory(
        destDirPath: String?,
        packageContext: String?,
        tarPfd: ParcelFileDescriptor?
    ): Boolean {
        val dir = allowedPath(destDirPath, write = true) ?: return false
        if (tarPfd == null) return false
        // Stage the archive so it can be vetted before anything is extracted: a symlink entry
        // followed by a file written through it would land outside the allowed directory.
        val staged = File(STAGING_DIR, "splus-restore-${System.nanoTime()}.archive")
        return try {
            ParcelFileDescriptor.AutoCloseInputStream(tarPfd).use { input ->
                staged.outputStream().use { input.copyTo(it) }
            }
            if (!isArchiveSafe(staged)) {
                Log.e("StorageProxy", "restoreTarDirectory refused: archive writes through a link or outside the folder")
                return false
            }
            val cmd = if (InputValidationUtils.isValidPackageName(packageContext) && serverUid == 2000 && isAppDataPath(dir)) {
                arrayOf("run-as", packageContext!!, "tar", "-xf", "-", "-C", dir)
            } else {
                arrayOf("tar", "-xf", "-", "-C", dir)
            }
            // Fed on stdin: under run-as the app's uid can't open a file the server staged.
            val proc = ProcessBuilder(*cmd).redirectInput(staged).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            val exitCode = proc.waitFor()
            if (exitCode != 0) {
                Log.e("StorageProxy", "restoreTarDirectory exitCode=$exitCode: $output")
            }
            exitCode == 0
        } catch (e: Exception) {
            Log.e("StorageProxy", "restoreTarDirectory failed", e)
            false
        } finally {
            staged.delete()
        }
    }

    private fun isArchiveSafe(archive: File): Boolean {
        val names = runTar("-tf", archive.path) ?: return false
        val verbose = runTar("-tvf", archive.path) ?: return false
        return ArchiveVetting.isSafe(names, verbose)
    }

    private fun runTar(vararg args: String): List<String>? = try {
        val proc = ProcessBuilder("tar", *args).start()
        val lines = proc.inputStream.bufferedReader().readLines().filter { it.isNotBlank() }
        if (proc.waitFor() == 0) lines else null
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val WRITE_MODE_BITS = ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or
            ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_APPEND

        // Writable by both the shell (2000) and root server; the manager already stages files here.
        val STAGING_DIR = File("/data/local/tmp")
    }
}
