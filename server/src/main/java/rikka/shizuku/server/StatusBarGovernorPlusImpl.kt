package rikka.shizuku.server

import af.shizuku.server.IStatusBarGovernorPlus

class StatusBarGovernorPlusImpl : IStatusBarGovernorPlus.Stub() {

    private fun exec(vararg args: String): Boolean = try {
        Runtime.getRuntime().exec(args).waitFor() == 0
    } catch (_: Exception) { false }

    private fun execOutput(vararg args: String): String = try {
        Runtime.getRuntime().exec(args).inputStream.bufferedReader().readText().trim()
    } catch (_: Exception) { "" }

    override fun disableExpansion(): Boolean =
        exec("cmd", "statusbar", "send-disable-flag", "statusbar-expansion")

    override fun enableExpansion(): Boolean =
        exec("cmd", "statusbar", "send-disable-flag", "none")

    override fun clickTile(component: String?): Boolean {
        if (component.isNullOrBlank()) return false
        return exec("cmd", "statusbar", "click-tile", component)
    }

    override fun getCurrentTiles(): String =
        execOutput("settings", "get", "secure", "sysui_qs_tiles")

    override fun setTiles(tileList: String?): Boolean {
        if (tileList.isNullOrBlank()) return false
        return exec("cmd", "statusbar", "set-tiles", tileList)
    }

    override fun collapse(): Boolean =
        exec("cmd", "statusbar", "collapse")

    override fun expandSettings(): Boolean =
        exec("cmd", "statusbar", "expand-settings")

    override fun addTile(tileSpec: String?): Boolean {
        if (tileSpec.isNullOrBlank()) return false
        val current = getCurrentTiles()
        val tiles = if (current.isBlank()) mutableListOf() else current.split(",").map { it.trim() }.toMutableList()
        if (tiles.contains(tileSpec)) return true
        tiles.add(tileSpec)
        return exec("cmd", "statusbar", "set-tiles", tiles.joinToString(","))
    }

    override fun removeTile(tileSpec: String?): Boolean {
        if (tileSpec.isNullOrBlank()) return false
        val current = getCurrentTiles()
        if (current.isBlank()) return true
        val tiles = current.split(",").map { it.trim() }.filter { it != tileSpec }.toMutableList()
        return exec("cmd", "statusbar", "set-tiles", tiles.joinToString(","))
    }

    override fun moveTileToPosition(tileSpec: String?, position: Int): Boolean {
        if (tileSpec.isNullOrBlank()) return false
        val current = getCurrentTiles()
        val tiles = if (current.isBlank()) mutableListOf() else current.split(",").map { it.trim() }.toMutableList()
        tiles.remove(tileSpec)
        val clampedPos = position.coerceIn(0, tiles.size)
        tiles.add(clampedPos, tileSpec)
        return exec("cmd", "statusbar", "set-tiles", tiles.joinToString(","))
    }
}
