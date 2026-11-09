package af.shizuku.manager.utils

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Manages adaptive settings recommendations across the app with a persistent
 * "Don't show this recommendation again" checkbox per recommendation ID.
 *
 * Users can reset all dismissed recommendations anytime in Settings -> Developer Options.
 */
object AdaptiveRecommendationManager {
    const val KEY_DISMISSED_RECOMMENDATIONS = "dismissed_adaptive_recommendations"

    const val ID_TCP_MODE = "tcp_mode"
    const val ID_USB_TCP_PERSISTENCE = "usb_tcp_persistence"
    const val ID_STABILITY_TIPS = "stability_tips"
    const val ID_TCP_NETWORK_GUARD = "tcp_network_guard"

    private const val KEY_LAST_PROMPT_PREFIX = "adaptive_last_prompt_"
    private const val COOLDOWN_MS = 3 * 24 * 60 * 60 * 1000L // 3-day cooldown between automatic prompts

    // In-memory session guard so we don't prompt the same recommendation twice in one process session
    private val shownThisSession = mutableSetOf<String>()

    @JvmStatic
    fun isCoolingDown(id: String): Boolean {
        val sp = ShizukuSettings.getPreferences() ?: return false
        val lastPrompt = sp.getLong(KEY_LAST_PROMPT_PREFIX + id, 0L)
        return (System.currentTimeMillis() - lastPrompt) < COOLDOWN_MS
    }

    @JvmStatic
    fun markPrompted(id: String) {
        val sp = ShizukuSettings.getPreferences() ?: return
        sp.edit().putLong(KEY_LAST_PROMPT_PREFIX + id, System.currentTimeMillis()).apply()
    }

    @JvmStatic
    fun isDismissed(id: String): Boolean {
        if (!ShizukuSettings.isAdaptiveRecommendationsEnabled()) return true
        return isDismissedById(id)
    }

    /** Whether [id] itself was dismissed, ignoring the master switch. */
    @JvmStatic
    fun isDismissedById(id: String): Boolean {
        val sp = ShizukuSettings.getPreferences() ?: return false
        val dismissed = sp.getStringSet(KEY_DISMISSED_RECOMMENDATIONS, emptySet()) ?: emptySet()
        return id in dismissed
    }

    @JvmStatic
    fun setDismissed(
        id: String,
        dismissed: Boolean = true,
    ) {
        val sp = ShizukuSettings.getPreferences() ?: return
        val current =
            (sp.getStringSet(KEY_DISMISSED_RECOMMENDATIONS, emptySet()) ?: emptySet())
                .toMutableSet()
        if (dismissed) {
            current.add(id)
        } else {
            current.remove(id)
        }
        sp.edit().putStringSet(KEY_DISMISSED_RECOMMENDATIONS, current).apply()
    }

    @JvmStatic
    fun resetAll() {
        shownThisSession.clear()
        ShizukuSettings.setAdaptiveRecommendationsEnabled(true)
        val sp = ShizukuSettings.getPreferences() ?: return
        val editor =
            sp
                .edit()
                .remove(KEY_DISMISSED_RECOMMENDATIONS)
                .remove("diagnostics_dismissed")
                .putBoolean("diagnostics_enabled", true)
        listOf(ID_TCP_MODE, ID_USB_TCP_PERSISTENCE, ID_STABILITY_TIPS, ID_TCP_NETWORK_GUARD).forEach {
            editor.remove(KEY_LAST_PROMPT_PREFIX + it)
        }
        editor.apply()
    }

    /**
     * Shows a Material 3 recommendation dialog with a "Don't show this recommendation again"
     * checkbox if [id] has not been permanently dismissed.
     */
    @JvmStatic
    fun showRecommendationDialog(
        context: Context,
        id: String,
        title: CharSequence,
        message: CharSequence,
        positiveLabel: CharSequence,
        negativeLabel: CharSequence = context.getString(R.string.recommendation_not_now),
        @DrawableRes iconRes: Int = R.drawable.ic_settings_suggest_24,
        respectSessionGuard: Boolean = true,
        onAccept: () -> Unit,
        onDecline: () -> Unit = {},
    ): Boolean {
        if (isDismissed(id)) return false
        if (respectSessionGuard && isCoolingDown(id)) return false
        // Callers often post this from async state callbacks; a dialog on a finishing or
        // destroyed activity throws BadTokenException.
        val activity = context as? android.app.Activity
        if (activity != null && (activity.isFinishing || activity.isDestroyed)) return false
        if (respectSessionGuard && !shownThisSession.add(id)) return false

        markPrompted(id)

        val density = context.resources.displayMetrics.density
        val padH = (24 * density).toInt()
        val padTop = (8 * density).toInt()
        val padBottom = (4 * density).toInt()

        val container =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padH, padTop, padH, padBottom)
            }

        val messageView =
            TextView(context).apply {
                text = message
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            }
        container.addView(messageView)

        val dontShowBox =
            MaterialCheckBox(context).apply {
                text = context.getString(R.string.recommendation_dont_show_again)
                val lp =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            topMargin = (12 * density).toInt()
                        }
                layoutParams = lp
            }
        container.addView(dontShowBox)

        val builder =
            MaterialAlertDialogBuilder(context)
                .setIcon(iconRes)
                .setTitle(title)
                .setView(container)
                .setPositiveButton(positiveLabel) { _, _ ->
                    if (dontShowBox.isChecked) {
                        setDismissed(id, true)
                    }
                    onAccept()
                }.setNegativeButton(negativeLabel) { _, _ ->
                    if (dontShowBox.isChecked) {
                        setDismissed(id, true)
                    }
                    onDecline()
                }.setOnCancelListener {
                    if (dontShowBox.isChecked) {
                        setDismissed(id, true)
                    }
                }
        return try {
            builder.show()
            true
        } catch (e: android.view.WindowManager.BadTokenException) {
            shownThisSession.remove(id)
            false
        }
    }

    /**
     * Proactively recommends TCP Mode (Port 5555) + Force Start Wireless ADB when the user is
     * using Wireless ADB without TCP Mode enabled.
     */
    @JvmStatic
    fun maybeRecommendTcpMode(
        context: Context,
        onEnabled: () -> Unit = {},
    ): Boolean {
        if (ShizukuSettings.getTcpMode()) return false
        if (!EnvironmentUtils.isTlsSupported() && !EnvironmentUtils.isTelevision()) return false
        if (ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ROOT) return false

        return showRecommendationDialog(
            context = context,
            id = ID_TCP_MODE,
            title = context.getString(R.string.recommendation_tcp_mode_title),
            message = context.getString(R.string.recommendation_tcp_mode_message),
            positiveLabel = context.getString(R.string.recommendation_tcp_mode_enable),
            onAccept = {
                ShizukuSettings.setTcpMode(true)
                ShizukuSettings.setTcpPort(5555)
                ShizukuSettings.setForceStartWadbEnabled(true)
                af.shizuku.manager.service.AdbProxyService
                    .enableAdbTcp(5555)
                Toast
                    .makeText(
                        context,
                        R.string.diagnostics_tcp_mode_enabled_toast,
                        Toast.LENGTH_SHORT,
                    ).show()
                onEnabled()
            },
        )
    }

    /**
     * Proactively recommends enabling TCP Mode (Port 5555) when the device is connected
     * via USB or started via ADB, so that disconnecting the USB cable does not terminate the
     * server session or prevent loopback reconnection.
     */
    @JvmStatic
    fun maybeRecommendUsbTcpMode(
        context: Context,
        onEnabled: () -> Unit = {},
    ): Boolean {
        if (ShizukuSettings.getTcpMode()) return false
        if (ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ROOT) return false

        return showRecommendationDialog(
            context = context,
            id = ID_USB_TCP_PERSISTENCE,
            title = context.getString(R.string.recommendation_usb_tcp_title),
            message = context.getString(R.string.recommendation_usb_tcp_message),
            positiveLabel = context.getString(R.string.recommendation_usb_tcp_enable),
            iconRes = R.drawable.ic_settings_suggest_24,
            onAccept = {
                ShizukuSettings.setTcpMode(true)
                ShizukuSettings.setTcpPort(5555)
                af.shizuku.manager.service.AdbProxyService
                    .enableAdbTcp(5555)
                Toast
                    .makeText(
                        context,
                        R.string.diagnostics_tcp_mode_enabled_toast,
                        Toast.LENGTH_SHORT,
                    ).show()
                onEnabled()
            },
        )
    }

    /**
     * Recommends enabling Open & Public Wi-Fi Protection when the user enables TCP Mode while
     * Network Protection is currently Off.
     */
    @JvmStatic
    fun maybeRecommendTcpNetworkGuard(
        context: Context,
        onEnabled: () -> Unit = {},
    ): Boolean {
        if (NetworkTrust.mode() != NetworkTrust.MODE_OFF) return false

        return showRecommendationDialog(
            context = context,
            id = ID_TCP_NETWORK_GUARD,
            title = context.getString(R.string.recommendation_tcp_guard_title),
            message = context.getString(R.string.recommendation_tcp_guard_message),
            positiveLabel = context.getString(R.string.recommendation_tcp_guard_enable),
            iconRes = R.drawable.ic_shield_24,
            onAccept = {
                ShizukuSettings
                    .getPreferences()
                    ?.edit()
                    ?.putString(NetworkTrust.KEY_MODE, NetworkTrust.MODE_OPEN)
                    ?.putBoolean(NetworkTrust.KEY_AUTO_CLOSE, true)
                    ?.apply()
                Toast
                    .makeText(
                        context,
                        R.string.recommendation_tcp_guard_enabled_toast,
                        Toast.LENGTH_SHORT,
                    ).show()
                onEnabled()
            },
        )
    }
}
