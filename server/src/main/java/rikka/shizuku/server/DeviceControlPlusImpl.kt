package rikka.shizuku.server

import android.os.IBinder
import android.os.ServiceManager
import android.util.Log
import af.shizuku.server.IDeviceControlPlus
import rikka.shizuku.server.util.ShellExecutor

/**
 * Implements IDeviceControlPlus using shell commands and Binder IPC available to uid 2000.
 *
 * Shell has WRITE_SETTINGS and WRITE_SECURE_SETTINGS as install-time grants, and is
 * authorised to call svc/media/reboot utilities without additional permissions.
 */
class DeviceControlPlusImpl : IDeviceControlPlus.Stub() {

    companion object {
        private const val TAG = "DeviceControlPlus"
        private val VALID_NAMESPACES = setOf("system", "secure", "global")
        private val VALID_USB_FUNCTIONS = setOf("mtp", "adb", "charging", "none", "rndis", "midi", "ncm")
        // "edl" (Emergency Download) is deliberately excluded: it drops the device into a
        // vendor flash mode that most users can't exit without a PC + firmware, so exposing
        // it across an untrusted Binder boundary is a bricking foot-gun.
        private val VALID_REBOOT_REASONS = setOf(null, "recovery", "bootloader", "fastboot", "quiescent")
        private val VALID_STREAMS = 0..5
    }

    private fun settingsPut(namespace: String, key: String, value: String): Boolean {
        if (namespace !in VALID_NAMESPACES) return false
        return ShellExecutor.execBool("settings", "put", namespace, key, value)
    }

    // ── Connectivity ──────────────────────────────────────────────────────────

    override fun setAirplaneModeEnabled(enabled: Boolean): Boolean {
        val value = if (enabled) "1" else "0"
        val ok = settingsPut("global", "airplane_mode_on", value)
        if (ok) {
            // Broadcast so telephony/WiFi radios react immediately
            val state = if (enabled) "true" else "false"
            ShellExecutor.exec("am", "broadcast", "-a", "android.intent.action.AIRPLANE_MODE", "--ez", "state", state)
        }
        return ok
    }

    override fun setWifiEnabled(enabled: Boolean): Boolean =
        ShellExecutor.execBool("svc", "wifi", if (enabled) "enable" else "disable")

    override fun setBluetoothEnabled(enabled: Boolean): Boolean =
        ShellExecutor.execBool("svc", "bluetooth", if (enabled) "enable" else "disable")

    override fun setMobileDataEnabled(enabled: Boolean): Boolean =
        ShellExecutor.execBool("svc", "data", if (enabled) "enable" else "disable")

    override fun setNfcEnabled(enabled: Boolean): Boolean =
        ShellExecutor.execBool("svc", "nfc", if (enabled) "enable" else "disable")

    // ── USB ───────────────────────────────────────────────────────────────────

    override fun setUsbFunction(function: String?): Boolean {
        if (function == null || function !in VALID_USB_FUNCTIONS) return false
        return ShellExecutor.execBool("svc", "usb", "setFunctions", function)
    }

    // ── Power ─────────────────────────────────────────────────────────────────

    override fun reboot(reason: String?): Boolean {
        if (reason != null && reason !in VALID_REBOOT_REASONS) return false
        return if (reason.isNullOrEmpty()) {
            ShellExecutor.execBool("reboot")
        } else {
            ShellExecutor.execBool("reboot", reason)
        }
    }

    override fun shutdown(): Boolean =
        ShellExecutor.execBool("svc", "power", "shutdown")

    // ── Display ───────────────────────────────────────────────────────────────

    override fun setScreenBrightness(level: Int): Boolean {
        val clamped = level.coerceIn(0, 255)
        // Disable auto-brightness first so the manual level takes effect
        settingsPut("system", "screen_brightness_mode", "0")
        return settingsPut("system", "screen_brightness", clamped.toString())
    }

    override fun setAutoBrightnessEnabled(enabled: Boolean): Boolean =
        settingsPut("system", "screen_brightness_mode", if (enabled) "1" else "0")

    override fun setScreenTimeout(ms: Int): Boolean =
        settingsPut("system", "screen_off_timeout", ms.toString())

    override fun setAutoRotateEnabled(enabled: Boolean): Boolean =
        settingsPut("system", "accelerometer_rotation", if (enabled) "1" else "0")

    // ── Audio ─────────────────────────────────────────────────────────────────

    override fun setStreamVolume(stream: Int, level: Int): Boolean {
        if (stream !in VALID_STREAMS) return false
        // Try AudioManager binder first (works even when media command not available)
        try {
            val binder = ServiceManager.getService("audio") ?: error("no audio service")
            val stub = Class.forName("android.media.IAudioService\$Stub")
                .getDeclaredMethod("asInterface", IBinder::class.java)
                .invoke(null, binder) ?: error("asInterface null")
            // setStreamVolume(int streamType, int index, int flags, String callingPackage)
            stub.javaClass.getMethod("setStreamVolume", Int::class.java, Int::class.java, Int::class.java, String::class.java)
                .invoke(stub, stream, level, 0, "com.android.shell")
            return true
        } catch (e: Exception) {
            Log.d(TAG, "setStreamVolume binder failed, trying media command", e)
        }
        // Fallback: media volume command (Android 11+)
        return ShellExecutor.execBool("media", "volume", "--stream", stream.toString(), "--set", level.toString())
    }

    override fun getStreamVolume(stream: Int): Int {
        if (stream !in VALID_STREAMS) return -1
        try {
            val binder = ServiceManager.getService("audio") ?: error("no audio")
            val stub = Class.forName("android.media.IAudioService\$Stub")
                .getDeclaredMethod("asInterface", IBinder::class.java).invoke(null, binder)
                ?: error("null stub")
            return stub.javaClass.getMethod("getStreamVolume", Int::class.java)
                .invoke(stub, stream) as? Int ?: -1
        } catch (e: Exception) {
            Log.d(TAG, "getStreamVolume binder failed, trying media command", e)
        }
        val out = ShellExecutor.exec("media", "volume", "--stream", stream.toString(), "--get")
        // Output: "volume is X" or "Current volume: X"
        return Regex("""(\d+)""").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    // ── System Appearance ─────────────────────────────────────────────────────

    override fun setFontScale(scale: Float): Boolean {
        val clamped = scale.coerceIn(0.70f, 2.00f)
        return settingsPut("system", "font_scale", "%.2f".format(clamped))
    }

    override fun setAnimationsEnabled(enabled: Boolean): Boolean {
        val v = if (enabled) "1.0" else "0.0"
        val a = settingsPut("global", "window_animation_scale", v)
        val b = settingsPut("global", "transition_animation_scale", v)
        val c = settingsPut("global", "animator_duration_scale", v)
        return a && b && c
    }

    // ── Settings convenience ──────────────────────────────────────────────────

    override fun putSetting(namespace: String?, key: String?, value: String?): Boolean {
        if (namespace == null || key == null || value == null) return false
        if (namespace !in VALID_NAMESPACES) return false
        return settingsPut(namespace, key, value)
    }

    override fun getSetting(namespace: String?, key: String?): String? {
        if (namespace == null || key == null) return null
        if (namespace !in VALID_NAMESPACES) return null
        val out = ShellExecutor.exec("settings", "get", namespace, key)
        return if (out == "null" || out.isEmpty()) null else out
    }
}
