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

    /** Returns true if root or Shizuku privileged shell is available. */
    fun isRootAvailable(): Boolean = try {
        Shell.getShell().isRoot || rikka.shizuku.Shizuku.pingBinder()
    } catch (e: Exception) {
        try {
            rikka.shizuku.Shizuku.pingBinder()
        } catch (_: Exception) {
            Timber.tag(TAG).d(e, "Privileged shell check failed")
            false
        }
    }

    /**
     * Reads the old app's settings via root shell or Shizuku and applies every key-value entry to
     * current preferences. Keys that already exist are skipped.
     *
     * @return true if at least one setting was migrated successfully, false otherwise.
     */
    fun migrateSettings(context: Context): Boolean {
        var anyMigrated = false

        // Migrate main settings
        if (migrateFile(context, "settings", "/data/data/$OLD_PACKAGE/shared_prefs/settings.xml")) {
            anyMigrated = true
        }

        // Migrate app management preferences
        if (migrateFile(context, "app_management_prefs", "/data/data/$OLD_PACKAGE/shared_prefs/app_management_prefs.xml")) {
            anyMigrated = true
        }

        return anyMigrated
    }

    private fun migrateFile(context: Context, prefsName: String, oldPath: String): Boolean {
        val currentPrefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

        // Read the raw XML from the old package's data dir via root shell or Shizuku shell
        var xmlContent: String? = null
        try {
            val result = Shell.cmd("cat '$oldPath'").exec()
            if (result.isSuccess && result.out.isNotEmpty()) {
                xmlContent = result.out.joinToString("\n")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "Root read failed, attempting Shizuku fallback")
        }

        if (xmlContent == null && rikka.shizuku.Shizuku.pingBinder()) {
            try {
                val p = rikka.shizuku.Shizuku.newProcess(arrayOf("cat", oldPath), null, null)
                if (p != null) {
                    val out = p.inputStream.bufferedReader().use { it.readText() }
                    if (p.waitFor() == 0 && out.isNotBlank()) {
                        xmlContent = out
                    }
                    try { p.destroy() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Shizuku read failed for $oldPath")
            }
        }

        if (xmlContent == null) {
            Timber.tag(TAG).w("Could not read old prefs (path=$oldPath)")
            return false
        }
        val editor = currentPrefs.edit()
        var count = 0

        // Parse <string name="key">value</string>
        Regex("""<string name="([^"]+)">([^<]*)</string>""").findAll(xmlContent).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2].unescapeXml()
            if (!currentPrefs.contains(key)) {
                editor.putString(key, value)
                count++
            }
        }

        // Parse <boolean name="key" value="true|false" />
        Regex("""<boolean name="([^"]+)"\s+value="(true|false)"\s*/>""").findAll(xmlContent).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2].toBoolean()
            if (!currentPrefs.contains(key)) {
                editor.putBoolean(key, value)
                count++
            }
        }

        // Parse <int name="key" value="N" />
        Regex("""<int name="([^"]+)"\s+value="(-?\d+)"\s*/>""").findAll(xmlContent).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2].toIntOrNull() ?: return@forEach
            if (!currentPrefs.contains(key)) {
                editor.putInt(key, value)
                count++
            }
        }

        // Parse <long name="key" value="N" />
        Regex("""<long name="([^"]+)"\s+value="(-?\d+)"\s*/>""").findAll(xmlContent).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2].toLongOrNull() ?: return@forEach
            if (!currentPrefs.contains(key)) {
                editor.putLong(key, value)
                count++
            }
        }

        // Parse <float name="key" value="N" />
        Regex("""<float name="([^"]+)"\s+value="([^"]+)"\s*/>""").findAll(xmlContent).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2].toFloatOrNull() ?: return@forEach
            if (!currentPrefs.contains(key)) {
                editor.putFloat(key, value)
                count++
            }
        }

        // Parse <set name="key"><string>v1</string>...</set>
        Regex("""<set name="([^"]+)">([\s\S]*?)</set>""").findAll(xmlContent).forEach { match ->
            val key = match.groupValues[1]
            if (!currentPrefs.contains(key)) {
                val setContent = match.groupValues[2]
                val set = mutableSetOf<String>()
                Regex("""<string>([^<]*)</string>""").findAll(setContent).forEach { sMatch ->
                    set.add(sMatch.groupValues[1].unescapeXml())
                }
                editor.putStringSet(key, set)
                count++
            }
        }

        editor.apply()
        if (count > 0) {
            Timber.tag(TAG).i("Migrated $count settings from $oldPath to $prefsName")
        }
        return count > 0
    }

    private fun String.unescapeXml(): String = this
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
}
