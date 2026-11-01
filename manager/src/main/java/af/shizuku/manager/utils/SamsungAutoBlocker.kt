package af.shizuku.manager.utils

import android.content.Context
import android.os.Looper
import android.provider.Settings

/**
 * Reads Samsung Auto Blocker's state. Samsung keeps it in secure settings under its internal
 * name, "rampart": `rampart_main_switch_enabled` is the main switch and
 * `rampart_strict_protection_switch_enabled` is "Maximum restrictions" (One UI 8+). Older builds
 * used `rampart_enabled` / `rampart_max_restrictions_enabled`, so those are read when the new
 * keys are missing. A key the app may not read, or that no key covers, is [State.UNKNOWN]: the
 * caller then neither warns nor claims it is off.
 *
 * Only the main switch's state is trusted; the strict switch keeps its value while Auto Blocker
 * is off, so it counts only while the main switch is on.
 */
object SamsungAutoBlocker {
    enum class State { ON, OFF, UNKNOWN }

    private val MAIN_KEYS = listOf("rampart_main_switch_enabled", "rampart_enabled")
    private val STRICT_KEYS = listOf("rampart_strict_protection_switch_enabled", "rampart_max_restrictions_enabled")

    /** Off the main thread: may ask the Shizuku shell. */
    fun state(context: Context): State = read(context, MAIN_KEYS)

    /** Off the main thread: may ask the Shizuku shell. */
    fun maxRestrictionsState(context: Context): State =
        when (state(context)) {
            State.OFF -> State.OFF
            State.UNKNOWN -> State.UNKNOWN
            State.ON -> read(context, STRICT_KEYS)
        }

    private fun read(
        context: Context,
        keys: List<String>,
    ): State {
        for (key in keys) {
            val value = value(context, key) ?: continue
            return if (value == "0") State.OFF else State.ON
        }
        return State.UNKNOWN
    }

    private fun value(
        context: Context,
        key: String,
    ): String? {
        // Missing keys read as null; on some builds the provider refuses OEM keys to apps.
        runCatching { Settings.Secure.getString(context.contentResolver, key) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        // The shell may read any key; null when neither Shizuku nor root is available.
        return DeviceOptimizer
            .runCommandOutput("settings get secure $key")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "null" }
    }
}
