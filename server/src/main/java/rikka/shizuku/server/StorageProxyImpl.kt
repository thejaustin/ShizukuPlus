package rikka.shizuku.server

import android.os.Bundle
import android.os.ParcelFileDescriptor
import af.shizuku.server.IStorageProxy
import rikka.shizuku.server.util.InputValidationUtils
import java.io.File

class StorageProxyImpl : IStorageProxy.Stub() {

    // Cached once per process — getuid() is a syscall, not a field.
    private val serverUid: Int by lazy { android.system.Os.getuid() }

    override fun openFile(path: String?, mode: Int): ParcelFileDescriptor? {
        if (!InputValidationUtils.isSafePath(path)) return null
        return try {
            try {
                ParcelFileDescriptor.open(File(path!!), mode)
            } catch (e: Exception) {
                // Android 13+ (API 33) progressively restricts /Android/data to the owning app's
                // UID; Android 16 (API 36) + OneUI 8 tightened it further. The shell-pipe fallback
                // works from API 33 onwards — not just the API 36+ check that was here before.
                if (android.os.Build.VERSION.SDK_INT >= 33 && path!!.contains("/Android/data")) {
                    return openViaShellPipe(arrayOf("sh", "-c", "cat \"$1\"", "sh", path))
                }
                // ADB mode (UID 2000): /data/data/<pkg>/ is owned by the app UID, but `run-as`
                // lets the shell impersonate the target app if it is debuggable. Only attempt
                // this when direct open already failed — no-op for non-debuggable or root mode.
                if (serverUid == 2000 &&
                    (path!!.startsWith("/data/data/") || path.startsWith("/data/user/"))) {
                    val pkg = extractPackageName(path)
                    if (pkg != null) {
                        return openViaShellPipe(arrayOf("run-as", pkg, "cat", path))
                    }
                }
                throw e
            }
        } catch (e: Exception) {
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
        } catch (e: Exception) {
            null
        }
    }

    override fun exists(path: String?): Boolean {
        if (!InputValidationUtils.isSafePath(path)) return false
        return File(path!!).exists()
    }

    override fun delete(path: String?): Boolean {
        if (!InputValidationUtils.isSafePath(path)) return false
        return try {
            File(path!!).delete()
        } catch (e: Exception) {
            false
        }
    }

    override fun listFiles(path: String?): List<String> {
        if (!InputValidationUtils.isSafePath(path)) return emptyList()
        val direct = File(path!!).list()
        if (!direct.isNullOrEmpty()) return direct.toList()
        // For /data/data/<pkg>/ paths (ADB mode, debuggable apps only)
        if (serverUid == 2000 &&
            (path.startsWith("/data/data/") || path.startsWith("/data/user/"))) {
            val pkg = extractPackageName(path) ?: return emptyList()
            return try {
                Runtime.getRuntime().exec(arrayOf("run-as", pkg, "ls", path))
                    .inputStream.bufferedReader().readLines()
                    .filter { it.isNotBlank() }
            } catch (_: Exception) { emptyList() }
        }
        return emptyList()
    }

    override fun getFileInfo(path: String?): Bundle {
        val bundle = Bundle()
        if (InputValidationUtils.isSafePath(path)) {
            val file = File(path!!)
            if (file.exists()) {
                bundle.putBoolean("exists", true)
                bundle.putLong("size", file.length())
                bundle.putLong("lastModified", file.lastModified())
                bundle.putBoolean("isDirectory", file.isDirectory)
            } else {
                bundle.putBoolean("exists", false)
            }
        } else {
            bundle.putBoolean("exists", false)
        }
        return bundle
    }

    override fun mkdir(path: String?): Boolean {
        if (!InputValidationUtils.isSafePath(path)) return false
        return try {
            File(path!!).mkdirs()
        } catch (e: Exception) {
            false
        }
    }

    override fun copyFile(srcPath: String?, destPath: String?): Boolean {
        if (!InputValidationUtils.isSafePath(srcPath) || !InputValidationUtils.isSafePath(destPath)) return false
        return try {
            File(srcPath!!).inputStream().use { src ->
                File(destPath!!).outputStream().use { dst -> src.copyTo(dst) }
            }
            true
        } catch (_: Exception) {
            // For /data/data/<pkg>/ paths, fall back to run-as cp
            if (serverUid == 2000 &&
                (srcPath!!.startsWith("/data/data/") || srcPath.startsWith("/data/user/"))) {
                val pkg = extractPackageName(srcPath) ?: return false
                return try {
                    Runtime.getRuntime().exec(arrayOf("run-as", pkg, "cp", srcPath, destPath!!))
                        .waitFor() == 0
                } catch (_: Exception) { false }
            }
            false
        }
    }

    override fun openContentUri(contentUri: String?): ParcelFileDescriptor? {
        if (contentUri.isNullOrBlank()) return null
        // Reject non-content URIs to prevent unintended file-scheme access
        if (!contentUri.startsWith("content://")) return null
        return openViaShellPipe(arrayOf("content", "read", "--uri", contentUri))
    }

    override fun tarDirectory(dirPath: String?, packageContext: String?): ParcelFileDescriptor? {
        if (!InputValidationUtils.isSafePath(dirPath)) return null
        val dir = dirPath!!
        return if (!packageContext.isNullOrBlank() && serverUid == 2000 &&
            (dir.startsWith("/data/data/") || dir.startsWith("/data/user/"))) {
            // run-as <pkg> tar for debuggable-app data directories
            openViaShellPipe(arrayOf("run-as", packageContext, "tar", "-czf", "-", "-C", dir, "."))
        } else {
            openViaShellPipe(arrayOf("tar", "-czf", "-", "-C", dir, "."))
        }
    }
}
