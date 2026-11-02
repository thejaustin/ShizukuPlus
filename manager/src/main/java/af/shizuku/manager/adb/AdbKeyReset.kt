package af.shizuku.manager.adb

import af.shizuku.manager.ShizukuSettings
import timber.log.Timber

/**
 * Deletes Shizuku+'s ADB key and its keystore wrapper. The next start makes a new key and Android
 * asks "Allow USB debugging?" once. Fixes starts that fail on a stale or corrupted key (#656).
 */
object AdbKeyReset {
    fun reset(): Boolean =
        try {
            ShizukuSettings
                .getPreferences()
                .edit()
                .remove("adbkey")
                .apply()
            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.deleteEntry("_adbkey_encryption_key_")
            true
        } catch (e: Exception) {
            Timber.tag("AdbKeyReset").e(e, "Failed to reset ADB keys")
            false
        }
}
