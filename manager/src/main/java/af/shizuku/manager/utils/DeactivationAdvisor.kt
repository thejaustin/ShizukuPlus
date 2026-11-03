package af.shizuku.manager.utils

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.settings.ServiceDoctorActivity
import android.content.Context
import android.content.Intent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Tracks unexpected Shizuku server deactivations/crashes and provides actionable tips
 * and settings suggestions to keep Shizuku alive in the background, including a
 * "Don't show this recommendation again" option for automatic prompts.
 */
object DeactivationAdvisor {
    private const val PREF_KEY_DEACTIVATION_TIMESTAMPS = "unexpected_deactivation_timestamps"
    private const val WINDOW_MS = 24 * 60 * 60 * 1000L // 24 hours

    /** Record an unexpected server drop or crash. */
    fun recordUnexpectedDrop() {
        val sp = ShizukuSettings.getPreferences() ?: return
        val now = System.currentTimeMillis()
        val raw = sp.getString(PREF_KEY_DEACTIVATION_TIMESTAMPS, "") ?: ""
        val timestamps =
            raw
                .split(",")
                .mapNotNull { it.trim().toLongOrNull() }
                .filter { now - it < WINDOW_MS }
                .toMutableList()
        timestamps.add(now)
        sp.edit().putString(PREF_KEY_DEACTIVATION_TIMESTAMPS, timestamps.joinToString(",")).apply()
    }

    /** Count of unexpected deactivations in the last 24 hours. */
    fun getRecentDropCount(): Int {
        val sp = ShizukuSettings.getPreferences() ?: return 0
        val now = System.currentTimeMillis()
        val raw = sp.getString(PREF_KEY_DEACTIVATION_TIMESTAMPS, "") ?: ""
        return raw
            .split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .count { now - it < WINDOW_MS }
    }

    data class Tip(
        val id: String,
        val title: String,
        val summary: String,
        val isEnabled: Boolean,
        val onEnable: (Context) -> Unit,
    )

    fun getTips(context: Context): List<Tip> {
        val tips = mutableListOf<Tip>()
        val isRootMode = ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ROOT

        if (!isRootMode && (EnvironmentUtils.isTlsSupported() || EnvironmentUtils.isTelevision())) {
            val tcpModeOn = ShizukuSettings.getTcpMode()
            tips.add(
                Tip(
                    id = "tcp_mode",
                    title = context.getString(R.string.settings_tcp_mode),
                    summary = context.getString(R.string.tip_tcp_mode_desc),
                    isEnabled = tcpModeOn,
                    onEnable = {
                        ShizukuSettings.setTcpMode(true)
                        ShizukuSettings.setForceStartWadbEnabled(true)
                    },
                ),
            )
        }

        val watchdogOn = ShizukuSettings.getWatchdog()
        tips.add(
            Tip(
                id = "watchdog",
                title = context.getString(R.string.settings_watchdog),
                summary = context.getString(R.string.tip_watchdog_desc),
                isEnabled = watchdogOn,
                onEnable = { ShizukuSettings.setWatchdog(it, true) },
            ),
        )

        if (!isRootMode) {
            val autoReconnectOn = ShizukuSettings.isAutoReconnectMdnsEnabled()
            tips.add(
                Tip(
                    id = "auto_reconnect",
                    title = context.getString(R.string.settings_auto_reconnect_mdns),
                    summary = context.getString(R.string.tip_auto_reconnect_desc),
                    isEnabled = autoReconnectOn,
                    onEnable = { ShizukuSettings.setAutoReconnectMdnsEnabled(true) },
                ),
            )
        }

        val startOnBootOn = ShizukuSettings.getStartOnBoot(context)
        tips.add(
            Tip(
                id = "start_on_boot",
                title = context.getString(R.string.settings_start_on_boot),
                summary = context.getString(R.string.tip_start_on_boot_desc),
                isEnabled = startOnBootOn,
                onEnable = { ShizukuSettings.setStartOnBoot(it, true) },
            ),
        )

        val hardeningOn = ShizukuSettings.isDeviceHardeningEnabled()
        tips.add(
            Tip(
                id = "device_hardening",
                title = context.getString(R.string.settings_device_hardening),
                summary = context.getString(R.string.tip_device_hardening_desc),
                isEnabled = hardeningOn,
                onEnable = { ShizukuSettings.setDeviceHardeningEnabled(true) },
            ),
        )

        val bgState = DeviceOptimizer.backgroundState(context)
        val protected = bgState == DeviceOptimizer.BackgroundState.PROTECTED
        tips.add(
            Tip(
                id = "battery_doctor",
                title = context.getString(R.string.home_service_doctor_title),
                summary = context.getString(R.string.tip_battery_doctor_desc),
                isEnabled = protected,
                onEnable = { ctx ->
                    ctx.startActivity(Intent(ctx, ServiceDoctorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
            ),
        )

        return tips
    }

    /** Show the Stability / Keep-Alive Tips dialog with a "Don't show again" option */
    @JvmOverloads
    fun showStabilityTipsDialog(
        context: Context,
        isAutomaticPrompt: Boolean = false,
        onDismiss: () -> Unit = {},
    ) {
        if (isAutomaticPrompt &&
            (
                AdaptiveRecommendationManager.isDismissed(AdaptiveRecommendationManager.ID_STABILITY_TIPS) ||
                    AdaptiveRecommendationManager.isCoolingDown(AdaptiveRecommendationManager.ID_STABILITY_TIPS)
            )
        ) {
            return
        }

        if (isAutomaticPrompt) {
            AdaptiveRecommendationManager.markPrompted(AdaptiveRecommendationManager.ID_STABILITY_TIPS)
        }

        val tips = getTips(context)
        val disabledTips = tips.filter { !it.isEnabled }

        val builder =
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.stability_tips_dialog_title)
                .setIcon(R.drawable.ic_settings_suggest_24)

        if (disabledTips.isEmpty()) {
            if (isAutomaticPrompt) return
            builder
                .setMessage(R.string.stability_tips_all_enabled_message)
                .setPositiveButton(R.string.home_service_doctor_title) { _, _ ->
                    context.startActivity(Intent(context, ServiceDoctorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.setNegativeButton(android.R.string.ok, null)
        } else {
            val density = context.resources.displayMetrics.density
            val padH = (24 * density).toInt()
            val padV = (8 * density).toInt()

            val scrollView = ScrollView(context)
            val contentLayout =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(padH, padV, padH, padV)
                }
            scrollView.addView(contentLayout)

            val introView =
                TextView(context).apply {
                    setText(R.string.stability_tips_dialog_message)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    setPadding(0, 0, 0, (8 * density).toInt())
                }
            contentLayout.addView(introView)

            val checkBoxes =
                disabledTips.map { tip ->
                    MaterialCheckBox(context).apply {
                        text = "${tip.title}\n${tip.summary}"
                        isChecked = true
                        setPadding((4 * density).toInt(), (6 * density).toInt(), 0, (6 * density).toInt())
                        contentLayout.addView(this)
                    }
                }

            val divider =
                com.google.android.material.divider.MaterialDivider(context).apply {
                    layoutParams =
                        LinearLayout
                            .LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                topMargin = (8 * density).toInt()
                                bottomMargin = (4 * density).toInt()
                            }
                }
            contentLayout.addView(divider)

            val dontShowCheck =
                MaterialCheckBox(context).apply {
                    text = context.getString(R.string.recommendation_dont_show_again)
                    // The stored choice only; isDismissed() also reads true when the master
                    // switch is off, which would pre-check this on a manual open.
                    isChecked = AdaptiveRecommendationManager.isDismissedById(AdaptiveRecommendationManager.ID_STABILITY_TIPS)
                    contentLayout.addView(this)
                }

            builder
                .setView(scrollView)
                .setPositiveButton(R.string.stability_tips_apply_selected) { _, _ ->
                    AdaptiveRecommendationManager.setDismissed(AdaptiveRecommendationManager.ID_STABILITY_TIPS, dontShowCheck.isChecked)
                    disabledTips.forEachIndexed { index, tip ->
                        if (checkBoxes[index].isChecked) {
                            tip.onEnable(context)
                        }
                    }
                    onDismiss()
                }.setNeutralButton(R.string.home_service_doctor_title) { _, _ ->
                    AdaptiveRecommendationManager.setDismissed(AdaptiveRecommendationManager.ID_STABILITY_TIPS, dontShowCheck.isChecked)
                    context.startActivity(Intent(context, ServiceDoctorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.setNegativeButton(android.R.string.cancel) { _, _ ->
                    AdaptiveRecommendationManager.setDismissed(AdaptiveRecommendationManager.ID_STABILITY_TIPS, dontShowCheck.isChecked)
                }
        }

        builder.setOnDismissListener { onDismiss() }.show()
    }
}
