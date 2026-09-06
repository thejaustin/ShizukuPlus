package rikka.shizuku.server

import android.graphics.Point
import android.os.Bundle
import android.os.IBinder
import android.os.ServiceManager
import android.util.Log
import af.shizuku.server.IDisplayTunerPlus

class DisplayTunerPlusImpl : IDisplayTunerPlus.Stub() {

    companion object {
        private const val TAG = "DisplayTunerPlus"
        private const val DISPLAY_ID = 0

        private fun windowManagerService(): Any? = try {
            val binder = ServiceManager.getService("window") ?: return null
            Class.forName("android.view.IWindowManager\$Stub")
                .getDeclaredMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        } catch (e: Exception) {
            Log.w(TAG, "IWindowManager unavailable", e)
            null
        }
    }

    override fun setDisplaySize(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return resetDisplaySize()
        // Primary: IWindowManager.setForcedDisplaySize — works at shell UID
        try {
            val wm = windowManagerService() ?: error("no window service")
            wm.javaClass.getMethod("setForcedDisplaySize", Int::class.java, Int::class.java, Int::class.java)
                .invoke(wm, DISPLAY_ID, width, height)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "setForcedDisplaySize IPC failed, falling back to exec", e)
        }
        return try {
            Runtime.getRuntime().exec(arrayOf("wm", "size", "${width}x${height}")).waitFor() == 0
        } catch (_: Exception) { false }
    }

    override fun resetDisplaySize(): Boolean {
        // Primary: IWindowManager.clearForcedDisplaySize
        try {
            val wm = windowManagerService() ?: error("no window service")
            wm.javaClass.getMethod("clearForcedDisplaySize", Int::class.java)
                .invoke(wm, DISPLAY_ID)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "clearForcedDisplaySize IPC failed, falling back to exec", e)
        }
        return try {
            Runtime.getRuntime().exec(arrayOf("wm", "size", "reset")).waitFor() == 0
        } catch (_: Exception) { false }
    }

    override fun setDisplayDensity(dpi: Int): Boolean {
        if (dpi <= 0) return resetDisplayDensity()
        // Primary: IWindowManager.setForcedDisplayDensityForUser (API 24+) or setForcedDisplayDensity
        try {
            val wm = windowManagerService() ?: error("no window service")
            val densityMethod = wm.javaClass.methods.firstOrNull { it.name == "setForcedDisplayDensityForUser" }
            if (densityMethod != null) {
                densityMethod.invoke(wm, DISPLAY_ID, dpi, 0)
            } else {
                wm.javaClass.getMethod("setForcedDisplayDensity", Int::class.java, Int::class.java)
                    .invoke(wm, DISPLAY_ID, dpi)
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "setForcedDisplayDensity IPC failed, falling back to exec", e)
        }
        return try {
            Runtime.getRuntime().exec(arrayOf("wm", "density", dpi.toString())).waitFor() == 0
        } catch (_: Exception) { false }
    }

    override fun resetDisplayDensity(): Boolean {
        // Primary: IWindowManager.clearForcedDisplayDensityForUser (API 24+) or clearForcedDisplayDensity
        try {
            val wm = windowManagerService() ?: error("no window service")
            val clearMethod = wm.javaClass.methods.firstOrNull { it.name == "clearForcedDisplayDensityForUser" }
            if (clearMethod != null) {
                clearMethod.invoke(wm, DISPLAY_ID, 0)
            } else {
                wm.javaClass.getMethod("clearForcedDisplayDensity", Int::class.java)
                    .invoke(wm, DISPLAY_ID)
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "clearForcedDisplayDensity IPC failed, falling back to exec", e)
        }
        return try {
            Runtime.getRuntime().exec(arrayOf("wm", "density", "reset")).waitFor() == 0
        } catch (_: Exception) { false }
    }

    override fun getDisplaySize(): Bundle {
        val bundle = Bundle()
        // Primary: IWindowManager.getInitialDisplaySize (physical) + getBaseDisplaySize (override)
        try {
            val wm = windowManagerService() ?: error("no window service")
            val physical = Point()
            wm.javaClass.getMethod("getInitialDisplaySize", Int::class.java, Point::class.java)
                .invoke(wm, DISPLAY_ID, physical)
            bundle.putInt("physical_width", physical.x)
            bundle.putInt("physical_height", physical.y)

            val base = Point()
            wm.javaClass.getMethod("getBaseDisplaySize", Int::class.java, Point::class.java)
                .invoke(wm, DISPLAY_ID, base)
            bundle.putInt("width", base.x)
            bundle.putInt("height", base.y)
            val hasOverride = base.x != physical.x || base.y != physical.y
            bundle.putInt("has_override", if (hasOverride) 1 else 0)
            return bundle
        } catch (e: Exception) {
            Log.w(TAG, "getDisplaySize IPC failed, falling back to exec", e)
        }
        // Fallback: wm size output parse
        return try {
            val output = Runtime.getRuntime().exec(arrayOf("wm", "size"))
                .inputStream.bufferedReader().use { it.readText() }
            var hasOverride = false
            for (line in output.lines()) {
                val lower = line.lowercase()
                val rawPair = line.substringAfterLast(":").trim()
                val parts = rawPair.split("x")
                if (parts.size != 2) continue
                val w = parts[0].trim().toIntOrNull() ?: continue
                val h = parts[1].trim().toIntOrNull() ?: continue
                when {
                    lower.startsWith("physical") -> {
                        bundle.putInt("physical_width", w)
                        bundle.putInt("physical_height", h)
                        if (!bundle.containsKey("width")) {
                            bundle.putInt("width", w)
                            bundle.putInt("height", h)
                        }
                    }
                    lower.startsWith("override") -> {
                        bundle.putInt("width", w)
                        bundle.putInt("height", h)
                        hasOverride = true
                    }
                }
            }
            bundle.putInt("has_override", if (hasOverride) 1 else 0)
            bundle
        } catch (_: Exception) { bundle }
    }

    override fun getDisplayDensity(): Int {
        // Primary: IWindowManager.getBaseDisplayDensity (returns override if set, else physical)
        try {
            val wm = windowManagerService() ?: error("no window service")
            return wm.javaClass.getMethod("getBaseDisplayDensity", Int::class.java)
                .invoke(wm, DISPLAY_ID) as Int
        } catch (e: Exception) {
            Log.w(TAG, "getBaseDisplayDensity IPC failed, falling back to exec", e)
        }
        // Fallback: wm density parse
        return try {
            val output = Runtime.getRuntime().exec(arrayOf("wm", "density"))
                .inputStream.bufferedReader().use { it.readText() }
            var density = -1
            for (line in output.lines()) {
                val lower = line.lowercase()
                val value = line.substringAfterLast(":").trim().toIntOrNull() ?: continue
                if (lower.startsWith("physical") && density == -1) density = value
                if (lower.startsWith("override")) density = value
            }
            density
        } catch (_: Exception) { -1 }
    }

    override fun getPhysicalDensity(): Int {
        // Primary: IWindowManager.getInitialDisplayDensity (always the hardware value)
        try {
            val wm = windowManagerService() ?: error("no window service")
            return wm.javaClass.getMethod("getInitialDisplayDensity", Int::class.java)
                .invoke(wm, DISPLAY_ID) as Int
        } catch (e: Exception) {
            Log.w(TAG, "getInitialDisplayDensity IPC failed, falling back to exec", e)
        }
        return try {
            Runtime.getRuntime().exec(arrayOf("wm", "density"))
                .inputStream.bufferedReader().use { it.readText() }
                .lines()
                .firstOrNull { it.lowercase().startsWith("physical") }
                ?.substringAfterLast(":")?.trim()?.toIntOrNull() ?: -1
        } catch (_: Exception) { -1 }
    }
}
