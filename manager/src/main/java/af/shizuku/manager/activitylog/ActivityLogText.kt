package af.shizuku.manager.activitylog

import af.shizuku.manager.R
import af.shizuku.manager.utils.BatteryOptimizationGuard
import af.shizuku.manager.utils.VendorBatteryRestrictions
import android.content.Context
import androidx.annotation.StringRes

/**
 * Turns a stored activity-log action into text for the current language.
 *
 * The log keeps the English action: [af.shizuku.manager.database.ActivityEventType.fromAction]
 * classifies entries by its wording every time the log loads, the server sends English it can't
 * localize, and exports stay readable in bug reports. Anything not listed here (per-app server
 * events, `su` commands, entries from older builds) is shown as stored.
 */
object ActivityLogText {
    private val exact: Map<String, Int> =
        mapOf(
            "Connected to Shizuku" to R.string.log_entry_connected,
            "Bound user service" to R.string.log_entry_bound_user_service,
            "Requested permission" to R.string.log_entry_requested_permission,
            "Service started via root" to R.string.log_entry_started_root,
            "Connection: Shizuku service dropped unexpectedly (process or binder died)" to R.string.log_entry_service_dropped,
            "Connection: Shizuku service stopped by you" to R.string.log_entry_service_stopped_by_user,
            "Connection: Shizuku service stopping" to R.string.log_entry_service_stopping,
            "Connection: Shizuku service stopped" to R.string.log_entry_service_stopped,
            "Connection: start did not complete (stopped)" to R.string.log_entry_start_incomplete_stopped,
            "Connection: start did not complete (crashed)" to R.string.log_entry_start_incomplete_crashed,
            "Connection: Wi-Fi lost" to R.string.log_entry_wifi_lost,
            "Connection: Wi-Fi lost while Shizuku was running" to R.string.log_entry_wifi_lost_running,
            "Connection: Wi-Fi available, retrying start" to R.string.log_entry_wifi_retry,
            "Closed ADB port/wireless debugging on untrusted network" to R.string.log_entry_tcp_closed_untrusted,
            "Watchdog: turned on with the first server start (the default for a new install)" to R.string.log_entry_watchdog_default_on,
            "Watchdog: the server came back after restart #1" to R.string.log_entry_watchdog_recovered,
            VendorBatteryRestrictions.LOG_ACTION_UNDID_BACKGROUND_ACTIVITY to R.string.log_entry_watchdog_undid_background,
            VendorBatteryRestrictions.LOG_ACTION_UNDID_STANDBY_BUCKET to R.string.log_entry_watchdog_undid_standby,
            VendorBatteryRestrictions.LOG_ACTION_UNDID_SAMSUNG_DEEP_SLEEP to R.string.log_entry_watchdog_undid_samsung,
            VendorBatteryRestrictions.LOG_ACTION_UNDID_XIAOMI_AUTOSTART to R.string.log_entry_watchdog_undid_xiaomi,
            VendorBatteryRestrictions.LOG_ACTION_REPORTED_BACKGROUND_ACTIVITY to R.string.log_entry_watchdog_restricted_background,
            VendorBatteryRestrictions.LOG_ACTION_REPORTED_STANDBY_BUCKET to R.string.log_entry_watchdog_restricted_standby,
            VendorBatteryRestrictions.LOG_ACTION_REPORTED_SAMSUNG_DEEP_SLEEP to R.string.log_entry_watchdog_restricted_samsung,
            VendorBatteryRestrictions.LOG_ACTION_REPORTED_XIAOMI_AUTOSTART to R.string.log_entry_watchdog_restricted_xiaomi,
            VendorBatteryRestrictions.LOG_ACTION_REPORTED_XIAOMI_NO_RESTRICT to R.string.log_entry_watchdog_xiaomi_whitelist,
            BatteryOptimizationGuard.GAP_LOG_ACTION to R.string.log_entry_watchdog_battery_optimization,
            "Permission: Granted by user" to R.string.log_entry_permission_granted,
            "Permission: Denied by user" to R.string.log_entry_permission_denied,
        )

    /** Templates whose captured groups become the string's arguments, in order. */
    private val templates: List<Pair<Regex, Int>> =
        listOf(
            Regex("Service started via ADB on port (\\d+)") to R.string.log_entry_started_adb,
            Regex("Service started via direct TCP port (\\d+) \\(no Wi-Fi required\\)") to R.string.log_entry_started_tcp,
            Regex("Service started via force_start_wadb TCP probe on port (\\d+)") to R.string.log_entry_started_tcp_probe,
            Regex("Connection: ADB start failed on port (\\d+) \\((.*)\\)", RegexOption.DOT_MATCHES_ALL) to R.string.log_entry_adb_start_failed,
            Regex("Connection: connecting via (.+) on port (\\d+)") to R.string.log_entry_connecting_via,
            Regex("Connection: restored after (\\d+)s") to R.string.log_entry_restored,
            Regex("Watchdog: restarting after crash #(\\d+)") to R.string.log_entry_watchdog_restarting,
            Regex("Watchdog: the server came back after restart #(\\d+)") to R.string.log_entry_watchdog_recovered_n,
            Regex("Watchdog: the server came back after a second try at restart #(\\d+)") to R.string.log_entry_watchdog_recovered_retry,
            Regex("Watchdog: restart #(\\d+) never came back, trying once more") to R.string.log_entry_watchdog_retrying,
        )

    /** App-list action tokens logged after "Swipe: " / "Long-press: ", and the labels they show as. */
    private val actionLabels: Map<String, Int> =
        mapOf(
            "none" to R.string.settings_action_none,
            "open_app" to R.string.settings_action_open_app,
            "app_info" to R.string.settings_action_app_info,
            "toggle_permission" to R.string.settings_action_toggle_permission,
            "hide_from_list" to R.string.settings_action_hide_from_list,
            "hide_app" to R.string.settings_action_hide_from_list,
            "grant_permission" to R.string.app_management_context_grant,
            "revoke_permission" to R.string.app_management_context_revoke,
            "freeze" to R.string.backup_action_freeze,
            "unfreeze" to R.string.backup_action_unfreeze,
        )

    /** English action logged when a permission is toggled by tapping a row in the Apps list. */
    const val TOGGLE_GRANT = "Toggle permission: Grant permission"
    const val TOGGLE_REVOKE = "Toggle permission: Revoke permission"
    private val REPEAT_SUFFIX_REGEX = Regex("^(.*) \\(×(\\d+)\\)$")

    fun localize(
        context: Context,
        action: String,
    ): String {
        val repeatMatch = REPEAT_SUFFIX_REGEX.matchEntire(action)
        if (repeatMatch != null) {
            val base = repeatMatch.groupValues[1]
            val count = repeatMatch.groupValues[2]
            return "${localize(context, base)} (×$count)"
        }
        exact[action]?.let { return context.getString(it) }
        when (action) {
            TOGGLE_GRANT -> return toggle(context, R.string.app_management_context_grant)
            TOGGLE_REVOKE -> return toggle(context, R.string.app_management_context_revoke)
        }
        for ((regex, res) in templates) {
            val m = regex.matchEntire(action) ?: continue
            return context.getString(res, *m.groupValues.drop(1).toTypedArray())
        }
        prefixed(context, action, "Swipe: ", R.string.log_entry_swipe)?.let { return it }
        prefixed(context, action, "Long-press: ", R.string.log_entry_long_press)?.let { return it }
        return action
    }

    private fun toggle(
        context: Context,
        @StringRes label: Int,
    ) = context.getString(R.string.app_management_log_permission_toggle, context.getString(label))

    private fun prefixed(
        context: Context,
        action: String,
        prefix: String,
        @StringRes res: Int,
    ): String? {
        if (!action.startsWith(prefix)) return null
        val label = actionLabels[action.removePrefix(prefix)] ?: return null
        return context.getString(res, context.getString(label))
    }
}
