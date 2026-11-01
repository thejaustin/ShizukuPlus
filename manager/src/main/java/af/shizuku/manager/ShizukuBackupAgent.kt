package af.shizuku.manager

import af.shizuku.manager.utils.SettingsBackupManager
import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
import timber.log.Timber
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Handles Android system backup (Auto Backup / Google Drive) and ADB backup (used by Swift Backup,
 * Neo Backup, Migrate, and similar apps).
 *
 * The app stores all settings in device-protected (DE) storage via
 * ShizukuSettings.initialize(createDeviceProtectedStorageContext()). The XML-based backup rules
 * (backup_descriptor.xml / data_extraction_rules.xml) reference credential-encrypted (CE) storage,
 * which is a different file and is always empty. This agent overrides the backup/restore path to
 * read and write DE storage directly via SettingsBackupManager, which already uses the correct
 * ShizukuSettings.getPreferences() instance.
 *
 * ShizukuSettings.initialize() is always called from ShizukuApplication.onCreate() before the
 * backup agent runs (Application#onCreate precedes BackupAgent method calls in the same process),
 * so getPreferences() is guaranteed non-null here.
 */
class ShizukuBackupAgent : BackupAgent() {
    companion object {
        private const val TAG = "ShizukuBackupAgent"
        private const val SETTINGS_KEY = "shizukuplus_settings.json"
    }

    // ── Key-value backup (bmgr trigger; not typically used by Swift Backup / Neo Backup) ─────────

    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput,
        newState: ParcelFileDescriptor,
    ) {
        try {
            val bytes = SettingsBackupManager.withoutSecrets(SettingsBackupManager.export(this)).toByteArray(Charsets.UTF_8)
            data.writeEntityHeader(SETTINGS_KEY, bytes.size)
            data.writeEntityData(bytes, bytes.size)
            FileOutputStream(newState.fileDescriptor).use { fos ->
                DataOutputStream(fos).use { dos -> dos.writeLong(System.currentTimeMillis()) }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "onBackup failed")
        }
    }

    override fun onRestore(
        data: BackupDataInput,
        appVersionCode: Int,
        newState: ParcelFileDescriptor?,
    ) {
        try {
            while (data.readNextHeader()) {
                if (data.key == SETTINGS_KEY) {
                    val bytes = ByteArray(data.dataSize)
                    readFully(data, bytes)
                    val imported = SettingsBackupManager.importAndCommit(this, String(bytes, Charsets.UTF_8))
                    Timber.tag(TAG).d("onRestore key-value: imported=%s", imported)
                } else {
                    data.skipEntityData()
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "onRestore failed")
        }
    }

    // ── Full backup (adb backup, Auto Backup to Google Drive, Swift Backup / Neo Backup) ─────────

    override fun onFullBackup(data: FullBackupDataOutput) {
        // Export settings to a temp file in CE filesDir, add it to the backup stream, then delete
        // it. fullBackupFile() embeds the file with its domain+path; onRestoreFile() intercepts
        // it on the way back and routes the content to DE storage rather than writing it to CE.
        val tmpFile = File(filesDir, SETTINGS_KEY)
        try {
            tmpFile.writeText(SettingsBackupManager.withoutSecrets(SettingsBackupManager.export(this)), Charsets.UTF_8)
            fullBackupFile(tmpFile, data)
            Timber.tag(TAG).d("onFullBackup: wrote %d bytes", tmpFile.length())
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "onFullBackup failed")
        } finally {
            tmpFile.delete()
        }
    }

    override fun onRestoreFile(
        data: ParcelFileDescriptor,
        size: Long,
        destination: File,
        type: Int,
        mode: Long,
        mtime: Long,
    ) {
        if (destination.name == SETTINGS_KEY) {
            // Read backup bytes and import to DE storage. Do NOT call super — that would write to
            // the CE-storage destination path, which ShizukuSettings never reads.
            try {
                val json =
                    FileInputStream(data.fileDescriptor).use { fis ->
                        DataInputStream(fis).use { dis ->
                            val bytes = ByteArray(size.toInt())
                            dis.readFully(bytes)
                            String(bytes, Charsets.UTF_8)
                        }
                    }
                val imported = SettingsBackupManager.importAndCommit(this, json)
                Timber.tag(TAG).d("onRestoreFile: imported=%s (%d bytes)", imported, size)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "onRestoreFile failed")
            }
        }
        // Other files: ignore — all settings are captured in SETTINGS_KEY; there are no other
        // files the app writes that benefit from backup.
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────────

    private fun readFully(
        data: BackupDataInput,
        buf: ByteArray,
    ) {
        var offset = 0
        while (offset < buf.size) {
            val n = data.readEntityData(buf, offset, buf.size - offset)
            if (n <= 0) break
            offset += n
        }
    }
}
