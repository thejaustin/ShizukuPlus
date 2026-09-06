package rikka.shizuku.server

import android.content.ComponentName
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.ServiceManager
import android.util.Log
import af.shizuku.server.IStatusBarGovernorPlus
import af.shizuku.common.util.UserHandleCompat
import rikka.hidden.compat.ActivityManagerApis
import rikka.shizuku.server.api.IContentProviderUtils

class StatusBarGovernorPlusImpl : IStatusBarGovernorPlus.Stub() {

    companion object {
        private const val TAG = "StatusBarGovernorPlus"
        private const val TILES_KEY = "sysui_qs_tiles"

        private fun statusBarService(): Any? = try {
            val binder = ServiceManager.getService("statusbar") ?: return null
            Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
                .getDeclaredMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        } catch (e: Exception) {
            Log.w(TAG, "IStatusBarService unavailable", e)
            null
        }
    }

    private fun callingUserId() = UserHandleCompat.getUserId(Binder.getCallingUid())

    private fun exec(vararg args: String): Boolean = try {
        Runtime.getRuntime().exec(args).waitFor() == 0
    } catch (_: Exception) { false }

    private fun putSecureSetting(key: String, value: String): Boolean {
        return try {
            val userId = callingUserId()
            val provider = ActivityManagerApis.getContentProviderExternal(
                "settings", userId, null, "com.android.shell"
            ) ?: return false
            val extras = Bundle().apply { putString("value", value) }
            IContentProviderUtils.callCompat(provider, null, "settings", "PUT_secure", key, extras)
            true
        } catch (e: Exception) {
            Log.w(TAG, "putSecureSetting $key failed", e)
            false
        }
    }

    private fun getSecureSetting(key: String): String {
        return try {
            val userId = callingUserId()
            val provider = ActivityManagerApis.getContentProviderExternal(
                "settings", userId, null, "com.android.shell"
            ) ?: return ""
            val result = IContentProviderUtils.callCompat(
                provider, null, "settings", "GET_secure", key, null
            )
            result?.getString("value") ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "getSecureSetting $key failed", e)
            ""
        }
    }

    override fun disableExpansion(): Boolean {
        // Primary: IStatusBarService.disable() — stateful call, works at shell UID
        try {
            val sb = statusBarService() ?: error("no statusbar service")
            val userId = callingUserId()
            // DISABLE_EXPAND = 0x10000 (StatusBarManager.DISABLE_EXPAND)
            val method = sb.javaClass.methods.firstOrNull { it.name == "disable" && it.parameterCount == 3 }
            if (method != null) {
                method.invoke(sb, userId, 0x10000, 0)
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "disableExpansion IPC failed, falling back to exec", e)
        }
        return exec("cmd", "statusbar", "send-disable-flag", "statusbar-expansion")
    }

    override fun enableExpansion(): Boolean {
        // Primary: IStatusBarService.disable() — clear all disable flags
        try {
            val sb = statusBarService() ?: error("no statusbar service")
            val userId = callingUserId()
            val method = sb.javaClass.methods.firstOrNull { it.name == "disable" && it.parameterCount == 3 }
            if (method != null) {
                method.invoke(sb, userId, 0, 0)
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "enableExpansion IPC failed, falling back to exec", e)
        }
        return exec("cmd", "statusbar", "send-disable-flag", "none")
    }

    override fun clickTile(component: String?): Boolean {
        if (component.isNullOrBlank()) return false
        // Primary: IStatusBarService.clickTile(ComponentName) — direct Binder IPC
        try {
            val sb = statusBarService() ?: error("no statusbar service")
            val cn = ComponentName.unflattenFromString(component)
                ?: error("invalid ComponentName: $component")
            val method = sb.javaClass.methods.firstOrNull { it.name == "clickTile" }
                ?: error("clickTile not found")
            method.invoke(sb, cn)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "clickTile IPC failed for $component, falling back to exec", e)
        }
        return exec("cmd", "statusbar", "click-tile", component)
    }

    override fun getCurrentTiles(): String {
        // Primary: secure settings ContentProvider GET — no exec needed
        val result = getSecureSetting(TILES_KEY)
        if (result.isNotEmpty()) return result
        // Fallback: settings get exec
        return try {
            Runtime.getRuntime().exec(arrayOf("settings", "get", "secure", TILES_KEY))
                .inputStream.bufferedReader().use { it.readText().trim() }
        } catch (_: Exception) { "" }
    }

    override fun setTiles(tileList: String?): Boolean {
        if (tileList.isNullOrBlank()) return false
        // Primary: secure settings ContentProvider PUT — no exec needed
        if (putSecureSetting(TILES_KEY, tileList)) return true
        // Fallback: cmd statusbar set-tiles exec
        return exec("cmd", "statusbar", "set-tiles", tileList)
    }

    override fun collapse(): Boolean {
        // Primary: IStatusBarService.collapsePanels() — direct Binder IPC
        try {
            val sb = statusBarService() ?: error("no statusbar service")
            val method = sb.javaClass.methods.firstOrNull { it.name == "collapsePanels" }
                ?: error("collapsePanels not found")
            method.invoke(sb)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "collapse IPC failed, falling back to exec", e)
        }
        return exec("cmd", "statusbar", "collapse")
    }

    override fun expandSettings(): Boolean {
        // Primary: IStatusBarService.expandSettingsPanel — direct Binder IPC
        try {
            val sb = statusBarService() ?: error("no statusbar service")
            val method = sb.javaClass.methods.firstOrNull {
                it.name == "expandSettingsPanel" || it.name == "expandNotificationsPanel"
            } ?: error("expandSettingsPanel not found")
            if (method.name == "expandSettingsPanel") {
                method.invoke(sb, null as String?)
            } else {
                method.invoke(sb)
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "expandSettings IPC failed, falling back to exec", e)
        }
        return exec("cmd", "statusbar", "expand-settings")
    }

    override fun addTile(tileSpec: String?): Boolean {
        if (tileSpec.isNullOrBlank()) return false
        val current = getCurrentTiles()
        val tiles = if (current.isBlank()) mutableListOf() else current.split(",").map { it.trim() }.toMutableList()
        if (tiles.contains(tileSpec)) return true
        tiles.add(tileSpec)
        return setTiles(tiles.joinToString(","))
    }

    override fun removeTile(tileSpec: String?): Boolean {
        if (tileSpec.isNullOrBlank()) return false
        val current = getCurrentTiles()
        if (current.isBlank()) return true
        val tiles = current.split(",").map { it.trim() }.filter { it != tileSpec }.toMutableList()
        return setTiles(tiles.joinToString(","))
    }

    override fun moveTileToPosition(tileSpec: String?, position: Int): Boolean {
        if (tileSpec.isNullOrBlank()) return false
        val current = getCurrentTiles()
        val tiles = if (current.isBlank()) mutableListOf() else current.split(",").map { it.trim() }.toMutableList()
        tiles.remove(tileSpec)
        val clampedPos = position.coerceIn(0, tiles.size)
        tiles.add(clampedPos, tileSpec)
        return setTiles(tiles.joinToString(","))
    }
}
