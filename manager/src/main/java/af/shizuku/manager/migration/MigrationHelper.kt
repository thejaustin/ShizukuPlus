package af.shizuku.manager.migration

import android.content.Context
import android.content.pm.PackageManager
import com.topjohnwu.superuser.Shell
import timber.log.Timber

/**
 * Detects and migrates settings from the old `moe.shizuku.privileged.api` package
 * to the current `af.shizuku.plus.api` package.
 *
 * The applicationId changed, so users cannot do an in-place update — they must uninstall
 * and reinstall. This helper copies the old SharedPreferences file to the current app's
 * data directory via a root shell so no settings are lost.
 */
object MigrationHelper {

    const val OLD_PACKAGE = "moe.shizuku.privileged.api"
    private const val TAG = "MigrationHelper"

    private val OLD_PREFS_PATH = "/data/data/$OLD_PACKAGE/shared_prefs/settings.xml"

    /** Returns true if the old package is still installed on this device. */
    fun isOldPackageInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(OLD_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** Returns true if root is available. Avoids obtaining a full shell when not needed. */
    fun isRootAvailable(): Boolean = try {
        Shell.getShell().isRoot
    } catch (e: Exception) {
        Timber.tag(TAG).d(e, "Root check failed")
        false
    }

    /**
     * Reads the old app's `settings.xml` via root shell and applies every key-value entry to
     * [currentPrefs]. Keys that already exist in [currentPrefs] are skipped so that settings
     * already restored by Android Auto-Backup are never overwritten with stale data.
     *
     * @return true if at least one setting was migrated successfully, false otherwise.
     */
    fun migrateSettings(context: Context): Boolean {
        val currentPrefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

        // Read the raw XML from the old package's data dir
        val result = Shell.cmd("cat '$OLD_PREFS_PATH'").exec()
        if (!result.isSuccess || result.out.isEmpty()) {
            Timber.tag(TAG).w("Could not read old prefs (path=$OLD_PREFS_PATH, code=${result.code})")
            return false
        }

        val xmlContent = result.out.joinToString("\n")
        Timber.tag(TAG).d("Old prefs XML length=${xmlContent.length}")

        val editor = currentPrefs.edit()
        var count = 0

        // Parse <string name="key">value</string>
        val stringPattern = Regex("""<string name="([^"]+)">([^<]*)</string>""")
        for (match in stringPattern.findAll(xmlContent)) {
            val key = match.groupValues[1]
            val value = match.groupValues[2].unescapeXml()
            if (!currentPrefs.contains(key)) {
                editor.putString(key, value)
                count++
            }
        }

        // Parse <boolean name="key" value="true|false" />
        val boolPattern = Regex("""<boolean name="([^"]+)"\s+value="(true|false)"\s*/>""")
        for (match in boolPattern.findAll(xmlContent)) {
            val key = match.groupValues[1]
            val value = match.groupValues[2].toBoolean()
            if (!currentPrefs.contains(key)) {
                editor.putBoolean(key, value)
                count++
            }
        }

        // Parse <int name="key" value="N" />
        val intPattern = Regex("""<int name="([^"]+)"\s+value="(-?\d+)"\s*/>""")
        for (match in intPattern.findAll(xmlContent)) {
            val key = match.groupValues[1]
            val value = match.groupValues[2].toIntOrNull() ?: continue
            if (!currentPrefs.contains(key)) {
                editor.putInt(key, value)
                count++
            }
        }

        // Parse <long name="key" value="N" />
        val longPattern = Regex("""<long name="([^"]+)"\s+value="(-?\d+)"\s*/>""")
        for (match in longPattern.findAll(xmlContent)) {
            val key = match.groupValues[1]
            val value = match.groupValues[2].toLongOrNull() ?: continue
            if (!currentPrefs.contains(key)) {
                editor.putLong(key, value)
                count++
            }
        }

        // Parse <float name="key" value="N" />
        val floatPattern = Regex("""<float name="([^"]+)"\s+value="([^"]+)"\s*/>""")
        for (match in floatPattern.findAll(xmlContent)) {
            val key = match.groupValues[1]
            val value = match.groupValues[2].toFloatOrNull() ?: continue
            if (!currentPrefs.contains(key)) {
                editor.putFloat(key, value)
                count++
            }
        }

        editor.apply()
        Timber.tag(TAG).i("Migrated $count settings from $OLD_PACKAGE")
        return count > 0
    }

    private fun String.unescapeXml(): String = this
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
}
