package af.shizuku.manager.doctor

import af.shizuku.manager.ShizukuSettings

/**
 * Why recent starts failed, so Service Doctor can offer the repair that fits. These were the most
 * common start failures in crash reports: port discovery timing out, ADB authorization never
 * being answered, and a broken ADB key store. Cleared once Shizuku+ is running.
 */
object StartFailureLog {
    enum class Kind { MDNS_TIMEOUT, AUTH_TIMEOUT, ADB_KEY }

    const val KEY_KIND = "start_failure_kind"
    const val KEY_COUNT = "start_failure_count"
    const val KEY_TIME = "start_failure_time"

    // Older failures say little about the device now.
    private const val WINDOW_MS = 24 * 60 * 60 * 1000L

    data class Recent(
        val kind: Kind,
        val count: Int,
    )

    /** Consecutive failures of the same kind count up; a different kind starts over. */
    fun record(kind: Kind) {
        val p = ShizukuSettings.getPreferences() ?: return
        val now = System.currentTimeMillis()
        val same = p.getString(KEY_KIND, null) == kind.name && now - p.getLong(KEY_TIME, 0) < WINDOW_MS
        p
            .edit()
            .putString(KEY_KIND, kind.name)
            .putInt(KEY_COUNT, if (same) p.getInt(KEY_COUNT, 0) + 1 else 1)
            .putLong(KEY_TIME, now)
            .apply()
    }

    fun recent(): Recent? {
        val p = ShizukuSettings.getPreferences() ?: return null
        if (System.currentTimeMillis() - p.getLong(KEY_TIME, 0) >= WINDOW_MS) return null
        val kind = p.getString(KEY_KIND, null)?.let { name -> Kind.entries.firstOrNull { it.name == name } } ?: return null
        return Recent(kind, p.getInt(KEY_COUNT, 0))
    }

    fun clear() {
        val p = ShizukuSettings.getPreferences() ?: return
        if (!p.contains(KEY_KIND)) return
        p
            .edit()
            .remove(KEY_KIND)
            .remove(KEY_COUNT)
            .remove(KEY_TIME)
            .apply()
    }

    /** Key-store failures (corrupted or unusable keystore) that resetting the ADB key fixes. */
    fun isAdbKeyFailure(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is java.security.GeneralSecurityException ||
                t is java.security.KeyStoreException ||
                t.stackTrace
                    .firstOrNull()
                    ?.className
                    ?.contains("AdbKey") == true
            ) {
                return true
            }
            t = t.cause
        }
        return false
    }
}
