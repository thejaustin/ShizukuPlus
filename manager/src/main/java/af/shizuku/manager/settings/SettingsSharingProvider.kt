package af.shizuku.manager.settings

import af.shizuku.manager.BuildConfig
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.SettingsBackupManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import timber.log.Timber

/**
 * Signature-protected ContentProvider allowing Drop-In (moe.shizuku.privileged.api) and
 * official ShizukuPlus (af.shizuku.plus.api) to seamlessly share and sync configurations.
 *
 * Enforced by android:permission="af.shizuku.plus.permission.MANAGER" (signature level)
 * and verified against PackageManager signature matching.
 */
class SettingsSharingProvider : ContentProvider() {
    companion object {
        const val METHOD_GET_SETTINGS = "getSettings"
        const val METHOD_SET_SETTINGS = "setSettings"
        const val METHOD_GET_LAST_MODIFIED = "getLastModified"
        const val METHOD_PING = "ping"

        const val EXTRA_SETTINGS_JSON = "extra_settings_json"
        const val EXTRA_LAST_MODIFIED = "extra_last_modified"
        const val EXTRA_SOURCE_PACKAGE = "extra_source_package"
        const val EXTRA_SUCCESS = "extra_success"
        const val EXTRA_VERSION_CODE = "extra_version_code"
    }

    override fun onCreate(): Boolean = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        val ctx = context ?: return null
        val caller = callingPackage
        if (caller == null || !isAuthorizedCaller(ctx, caller)) {
            Timber.tag("SettingsShare").w("Unauthorized call from %s", caller)
            return null
        }

        return when (method) {
            METHOD_GET_SETTINGS -> {
                val json = SettingsBackupManager.withoutSecrets(SettingsBackupManager.export(ctx))
                val lastMod = ShizukuSettings.getSettingsLastModified()
                Bundle().apply {
                    putString(EXTRA_SETTINGS_JSON, json)
                    putLong(EXTRA_LAST_MODIFIED, lastMod)
                    putString(EXTRA_SOURCE_PACKAGE, ctx.packageName)
                    putBoolean(EXTRA_SUCCESS, true)
                }
            }
            METHOD_SET_SETTINGS -> {
                val json = extras?.getString(EXTRA_SETTINGS_JSON) ?: return null
                val success = SettingsBackupManager.import(ctx, json, trustPrivileged = true)
                if (success) {
                    val timestamp = extras.getLong(EXTRA_LAST_MODIFIED, System.currentTimeMillis())
                    ShizukuSettings.setSettingsLastModified(timestamp)
                    Timber.tag("SettingsShare").i("Successfully imported settings from %s", caller)
                }
                Bundle().apply {
                    putBoolean(EXTRA_SUCCESS, success)
                }
            }
            METHOD_GET_LAST_MODIFIED -> {
                val lastMod = ShizukuSettings.getSettingsLastModified()
                Bundle().apply {
                    putLong(EXTRA_LAST_MODIFIED, lastMod)
                    putString(EXTRA_SOURCE_PACKAGE, ctx.packageName)
                    putBoolean(EXTRA_SUCCESS, true)
                }
            }
            METHOD_PING -> {
                Bundle().apply {
                    putBoolean(EXTRA_SUCCESS, true)
                    putString(EXTRA_SOURCE_PACKAGE, ctx.packageName)
                    putInt(EXTRA_VERSION_CODE, BuildConfig.VERSION_CODE)
                }
            }
            else -> null
        }
    }

    private fun isAuthorizedCaller(
        context: Context,
        caller: String,
    ): Boolean {
        if (caller == context.packageName) return true
        val allowed = setOf(SettingsShareManager.OFFICIAL_PLUS_PACKAGE, SettingsShareManager.DROPIN_PACKAGE)
        if (caller !in allowed) return false
        return context.packageManager.checkSignatures(context.packageName, caller) == PackageManager.SIGNATURE_MATCH
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
