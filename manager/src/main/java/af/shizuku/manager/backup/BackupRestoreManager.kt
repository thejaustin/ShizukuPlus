package af.shizuku.manager.backup

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import javax.crypto.Cipher
import af.shizuku.manager.R
import af.shizuku.manager.utils.SettingsBackupManager

object BackupRestoreManager {

    fun isEncrypted(payload: String): Boolean = try {
        JSONObject(payload).has("iv")
    } catch (_: Exception) { false }

    fun createPlainBackupPayload(context: Context): String {
        val jsonString = SettingsBackupManager.export(context)
        val backupJson = JSONObject()
        backupJson.put("version", 2)
        backupJson.put("encrypted", false)
        backupJson.put("data", Base64.encodeToString(jsonString.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        return backupJson.toString()
    }

    fun restoreFromPlainPayload(context: Context, payload: String) {
        val backupJson = JSONObject(payload)
        val version = backupJson.optInt("version", 1)
        if (version == 2) {
            val jsonString = String(Base64.decode(backupJson.getString("data"), Base64.NO_WRAP), Charsets.UTF_8)
            if (!SettingsBackupManager.import(context, jsonString)) {
                throw IllegalArgumentException(context.getString(R.string.settings_backup_invalid_format))
            }
        } else {
            throw IllegalArgumentException(context.getString(R.string.settings_backup_encrypted_restore_required))
        }
    }

    fun createBackupPayload(context: Context, cipher: Cipher): String {
        val jsonString = SettingsBackupManager.export(context)
        val encryptedBytes = cipher.doFinal(jsonString.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv

        val backupJson = JSONObject()
        backupJson.put("version", 1)
        backupJson.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
        backupJson.put("data", Base64.encodeToString(encryptedBytes, Base64.NO_WRAP))

        return backupJson.toString()
    }

    fun extractIv(payload: String): ByteArray {
        val backupJson = JSONObject(payload)
        return Base64.decode(backupJson.getString("iv"), Base64.NO_WRAP)
    }

    fun restoreFromPayload(context: Context, payload: String, cipher: Cipher) {
        val backupJson = JSONObject(payload)
        val data = Base64.decode(backupJson.getString("data"), Base64.NO_WRAP)

        val decryptedBytes = cipher.doFinal(data)
        val jsonString = String(decryptedBytes, Charsets.UTF_8)

        val success = SettingsBackupManager.import(context, jsonString)
        if (!success) {
            throw IllegalArgumentException(context.getString(R.string.settings_backup_invalid_format))
        }
    }
}
