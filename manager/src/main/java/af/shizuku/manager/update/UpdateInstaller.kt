package af.shizuku.manager.update

import android.content.Context
import android.os.Environment
import com.topjohnwu.superuser.Shell
import af.shizuku.manager.utils.SettingsBackupManager
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.io.File

object UpdateInstaller {
    private const val TAG = "UpdateInstaller"
    const val AUTO_BACKUP_FILENAME = "shizuku_plus_auto_backup.json"

    fun getBackupFile(context: Context): File? {
        val extDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir ?: return null
        return File(extDir, AUTO_BACKUP_FILENAME)
    }

    fun forceUpdateWithShizuku(context: Context, apkFile: File): Boolean {
        if (!Shizuku.pingBinder() && !Shell.getShell().isRoot) {
            Timber.tag(TAG).w("No root or Shizuku available for force update.")
            return false
        }

        try {
            // 1. Auto-export settings to a public location
            val backupJson = SettingsBackupManager.export(context)
            val backupFile = getBackupFile(context)
            if (backupFile != null) {
                backupFile.writeText(backupJson)
                Timber.tag(TAG).i("Auto-exported settings to ${backupFile.absolutePath}")
            }

            // 2. Create a shell script to do the detached reinstall
            val cacheDir = context.cacheDir ?: context.filesDir ?: return false
            val scriptFile = File(cacheDir, "force_update.sh")
            val packageName = context.packageName
            val apkPath = apkFile.absolutePath

            // The script sleeps for 2 seconds to allow the app to finish its current execution,
            // then uninstalls the current package, installs the new APK, and restarts the app.
            val script = """
                #!/system/bin/sh
                sleep 2
                cp "$apkPath" /data/local/tmp/update.apk
                chmod 644 /data/local/tmp/update.apk
                pm uninstall $packageName
                pm install -r -d /data/local/tmp/update.apk
                rm /data/local/tmp/update.apk
 
                # Enhance: Auto-grant crucial permissions and AppOps to ensure a truly seamless transition
                pm grant $packageName android.permission.POST_NOTIFICATIONS 2>/dev/null
                pm grant $packageName android.permission.WRITE_SECURE_SETTINGS 2>/dev/null
                appops set $packageName SYSTEM_ALERT_WINDOW allow 2>/dev/null
                appops set $packageName GET_USAGE_STATS allow 2>/dev/null
 
                am start -n $packageName/af.shizuku.manager.MainActivity
                rm /data/local/tmp/force_update.sh
            """.trimIndent()
 
            scriptFile.writeText(script)
 
            // 3. Execute the script in a detached background process via root/shizuku
            // Copy script to /data/local/tmp and run it from there so it survives app uninstallation
            Shell.cmd("cp '${scriptFile.absolutePath}' /data/local/tmp/force_update.sh && chmod 755 /data/local/tmp/force_update.sh && nohup sh /data/local/tmp/force_update.sh >/dev/null 2>&1 &").exec()

            return true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to force update with Shizuku")
            return false
        }
    }
}
