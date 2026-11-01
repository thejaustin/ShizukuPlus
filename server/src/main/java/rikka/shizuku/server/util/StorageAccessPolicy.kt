package rikka.shizuku.server.util

/**
 * Which canonical paths the Storage Bridge may touch for a third-party caller.
 *
 * Restricted (the default): other apps' private data is off limits, both internal
 * (/data/data, /data/user, Shizuku's own /data/adb/shizuku) and external (Android/data,
 * Android/obb), and installed APKs under /data/app are read-only. Shared storage stays open.
 * Unrestricted (developer option "Unrestricted app data access"): the pre-2026-11 behavior,
 * everything on the InputValidationUtils allow-list, read and write.
 */
object StorageAccessPolicy {

    private val PRIVATE_DATA_PREFIXES = listOf("/data/data", "/data/user", "/data/adb/shizuku")

    private val EXTERNAL_APP_DIRS =
        // Shared storage is case-insensitive: /android/DATA is the same folder.
        Regex("^(/storage/emulated/\\d+|/storage/self/primary|/sdcard)/Android/(data|obb)(/.*)?$", RegexOption.IGNORE_CASE)

    private const val APK_DIR = "/data/app"

    /** @param canonicalPath a path already resolved by [java.io.File.getCanonicalPath]. */
    fun isAllowed(canonicalPath: String, write: Boolean, unrestricted: Boolean): Boolean {
        if (unrestricted) return true
        if (PRIVATE_DATA_PREFIXES.any { isUnder(canonicalPath, it) }) return false
        if (EXTERNAL_APP_DIRS.matches(canonicalPath)) return false
        if (write && isUnder(canonicalPath, APK_DIR)) return false
        return true
    }

    private fun isUnder(path: String, dir: String): Boolean = path == dir || path.startsWith("$dir/")
}
