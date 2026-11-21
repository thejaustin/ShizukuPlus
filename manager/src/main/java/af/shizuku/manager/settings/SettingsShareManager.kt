package af.shizuku.manager.settings

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.SettingsBackupManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Manages configuration synchronization between official ShizukuPlus (af.shizuku.plus.api)
 * and the drop-in flavor (moe.shizuku.privileged.api).
 *
 * Provides automatic detection, initial import on first launch/switching, background auto-sync
 * on preference changes, and manual import/export capabilities.
 */
object SettingsShareManager {
    const val OFFICIAL_PLUS_PACKAGE = "af.shizuku.plus.api"
    const val DROPIN_PACKAGE = "moe.shizuku.privileged.api"

    private const val TAG = "SettingsShareManager"

    fun isDropIn(context: Context): Boolean =
        context.packageName == DROPIN_PACKAGE

    fun getPeerPackage(context: Context): String =
        if (context.packageName == DROPIN_PACKAGE) OFFICIAL_PLUS_PACKAGE else DROPIN_PACKAGE

    fun getPeerLabel(context: Context): String =
        if (context.packageName == DROPIN_PACKAGE) "Shizuku+ (Standalone App)" else "Shizuku+ (Drop-In)"

    fun getPeerAuthority(context: Context): String =
        "${getPeerPackage(context)}.settings.share"

    /**
     * True only when the peer is a real ShizukuPlus manager build (Plus or Drop-In flavor).
     *
     * A bare package lookup is not enough: the Compat Hub stub bundled with the Plus flavor is
     * ALSO named moe.shizuku.privileged.api, but it has no settings-share provider. Treating it as
     * a peer made every preference change fire a doomed provider call. Resolving the peer's
     * settings-share authority distinguishes the two.
     */
    fun isPeerInstalled(context: Context): Boolean {
        val authority = getPeerAuthority(context)
        return try {
            val info =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.resolveContentProvider(
                        authority,
                        PackageManager.ComponentInfoFlags.of(0),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.resolveContentProvider(authority, 0)
                }
            info != null && info.packageName == getPeerPackage(context)
        } catch (_: Exception) {
            false
        }
    }

    fun isPeerAvailable(context: Context): Boolean {
        if (!isPeerInstalled(context)) return false
        return try {
            val uri = Uri.parse("content://${getPeerAuthority(context)}")
            val reply = context.contentResolver.call(uri, SettingsSharingProvider.METHOD_PING, null, null)
            reply?.getBoolean(SettingsSharingProvider.EXTRA_SUCCESS, false) == true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Peer is installed but provider not reachable")
            false
        }
    }

    fun isAutoSyncEnabled(): Boolean =
        ShizukuSettings.isAutoSyncPeerSettingsEnabled()

    fun setAutoSyncEnabled(enabled: Boolean) =
        ShizukuSettings.setAutoSyncPeerSettingsEnabled(enabled)

    fun markSettingsModified() {
        ShizukuSettings.setSettingsLastModified(System.currentTimeMillis())
    }

    fun getLastModified(): Long =
        ShizukuSettings.getSettingsLastModified()

    fun importFromPeer(context: Context): Boolean {
        if (!isPeerInstalled(context)) return false
        return try {
            val uri = Uri.parse("content://${getPeerAuthority(context)}")
            val reply = context.contentResolver.call(uri, SettingsSharingProvider.METHOD_GET_SETTINGS, null, null)
            val json = reply?.getString(SettingsSharingProvider.EXTRA_SETTINGS_JSON) ?: return false
            val lastMod = reply.getLong(SettingsSharingProvider.EXTRA_LAST_MODIFIED, System.currentTimeMillis())
            val success = SettingsBackupManager.import(context, json, trustPrivileged = true)
            if (success) {
                ShizukuSettings.setSettingsLastModified(lastMod)
                ShizukuSettings
                    .getPreferences()
                    ?.edit()
                    ?.putBoolean(ShizukuSettings.Keys.KEY_PEER_INITIAL_IMPORT_DONE, true)
                    ?.apply()
                Timber.tag(TAG).i("Imported settings from %s", getPeerLabel(context))
            }
            success
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to import settings from peer")
            false
        }
    }

    fun exportToPeer(context: Context): Boolean {
        if (!isPeerInstalled(context)) return false
        return try {
            val json = SettingsBackupManager.withoutSecrets(SettingsBackupManager.export(context))
            val lastMod = getLastModified()
            val uri = Uri.parse("content://${getPeerAuthority(context)}")
            val extras =
                Bundle().apply {
                    putString(SettingsSharingProvider.EXTRA_SETTINGS_JSON, json)
                    putLong(SettingsSharingProvider.EXTRA_LAST_MODIFIED, lastMod)
                }
            val reply = context.contentResolver.call(uri, SettingsSharingProvider.METHOD_SET_SETTINGS, null, extras)
            val success = reply?.getBoolean(SettingsSharingProvider.EXTRA_SUCCESS, false) == true
            if (success) {
                Timber.tag(TAG).i("Exported settings to %s", getPeerLabel(context))
            }
            success
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to export settings to peer")
            false
        }
    }

    /**
     * Checks if initial import should happen (e.g. fresh install/switch where peer exists and we haven't imported yet)
     * or if bi-directional sync is needed based on timestamps.
     */
    suspend fun checkAndPerformAutoSync(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            if (!isPeerInstalled(context)) return@withContext false
            if (!isAutoSyncEnabled()) return@withContext false

            val prefs = ShizukuSettings.getPreferences() ?: return@withContext false
            val initialImportDone = prefs.getBoolean(ShizukuSettings.Keys.KEY_PEER_INITIAL_IMPORT_DONE, false)

            if (!initialImportDone) {
                // First time switching or opening with peer present: automatically import peer's settings!
                val success = importFromPeer(context)
                if (success) {
                    prefs.edit().putBoolean(ShizukuSettings.Keys.KEY_PEER_INITIAL_IMPORT_DONE, true).apply()
                    return@withContext true
                }
            }

            // Check timestamps for bi-directional sync
            try {
                val uri = Uri.parse("content://${getPeerAuthority(context)}")
                val reply = context.contentResolver.call(uri, SettingsSharingProvider.METHOD_GET_LAST_MODIFIED, null, null)
                val peerLastMod = reply?.getLong(SettingsSharingProvider.EXTRA_LAST_MODIFIED, 0L) ?: 0L
                val localLastMod = getLastModified()

                // 2-second threshold prevents echo loops
                if (peerLastMod > localLastMod + 2000L) {
                    Timber.tag(TAG).i("Peer settings are newer ($peerLastMod vs $localLastMod), importing...")
                    return@withContext importFromPeer(context)
                } else if (localLastMod > peerLastMod + 2000L && peerLastMod > 0L) {
                    Timber.tag(TAG).i("Local settings are newer ($localLastMod vs $peerLastMod), exporting to peer...")
                    return@withContext exportToPeer(context)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Auto-sync check failed")
            }
            false
        }
}
