package af.shizuku.manager.utils

import af.shizuku.manager.MainActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.receiver.IssueDismissReceiver
import af.shizuku.manager.service.WatchdogService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Everything currently wrong with Shizuku+, keyed by what reported it, and the one place that
 * decides how the user hears about it. Each reporter clears its own key once the cause is gone,
 * and a server reaching RUNNING clears every start problem ([clearStartProblems]).
 *
 * With the Watchdog notification showing, problems are written into it. Otherwise one shared
 * notification is posted, replaced in place rather than stacked, and only as far as the user's
 * "Helper notifications" setting allows. Nothing here posts a toast: those land on whatever app the user
 * happens to be in.
 */
object ShizukuIssues {
    enum class Severity {
        /** Something is degraded, but Shizuku+ is running or will retry on its own. */
        PROBLEM,

        /** Shizuku+ is not running, or something needs the user now (an open debugging port). */
        URGENT,
    }

    class Action(
        val label: String,
        val intent: PendingIntent,
    )

    class Issue(
        val key: String,
        val severity: Severity,
        val title: String,
        val text: String,
        val actions: List<Action> = emptyList(),
        val contentIntent: PendingIntent? = null,
    )

    /** The "Helper notifications" setting. */
    enum class AlertLevel(
        val value: String,
    ) {
        OFF("off"),
        URGENT("stopped"),
        ALL("all"),
        ;

        companion object {
            fun of(value: String?): AlertLevel = entries.firstOrNull { it.value == value } ?: URGENT
        }
    }

    /** Where an issue goes; both may be true for one that needs attention now. */
    data class Route(
        val inOngoing: Boolean,
        val shared: Boolean,
    )

    // Reporters whose problems a running server makes stale.
    const val KEY_PORT_DISCOVERY = "port_discovery"
    const val KEY_START_FAILED = "start_failed"
    const val KEY_STOPPED = "stopped"
    const val KEY_TCP_OPEN = "tcp_open"
    const val KEY_DOCTOR = "doctor"
    private val START_KEYS = setOf(KEY_PORT_DISCOVERY, KEY_START_FAILED, KEY_STOPPED)

    private const val CHANNEL_ID = "helper_notifications"
    const val NOTIFICATION_ID = 1450

    // Each issue with the order it was reported in, so the newest of equal severity shows.
    private val issues = ConcurrentHashMap<String, Pair<Issue, Long>>()
    private val sequence = AtomicLong()

    private val currentFlow = MutableStateFlow<Issue?>(null)

    /** The issue [top] returns, for screens that show it (Home's status card). */
    val current: StateFlow<Issue?> = currentFlow.asStateFlow()

    // The severity last posted in the shared notification, so an escalation alerts again.
    @Volatile
    private var postedSeverity: Severity? = null

    // The issue (key and report number) whose shared notification the user swiped away. It stays
    // out of the shade until it is reported again with different wording; the Watchdog
    // notification still shows it.
    @Volatile
    private var dismissed: Pair<String, Long>? = null

    /**
     * The Watchdog notification is already in the shade, so a problem always shows there,
     * silently; only one that needs attention now also alerts, and only if the user allows it.
     * Without that notification, the setting alone decides.
     */
    fun route(
        ongoingShowing: Boolean,
        level: AlertLevel,
        severity: Severity,
    ): Route {
        val alertAllowed =
            when (level) {
                AlertLevel.OFF -> false
                AlertLevel.URGENT -> severity == Severity.URGENT
                AlertLevel.ALL -> true
            }
        return if (ongoingShowing) {
            Route(inOngoing = true, shared = alertAllowed && severity == Severity.URGENT)
        } else {
            Route(inOngoing = false, shared = alertAllowed)
        }
    }

    /** The issue to show: the most severe, then the most recently reported. */
    fun top(): Issue? = topEntry()?.first

    private fun topEntry(): Pair<Issue, Long>? =
        issues.values.maxWithOrNull(compareBy<Pair<Issue, Long>> { it.first.severity.ordinal }.thenBy { it.second })

    fun report(
        context: Context,
        issue: Issue,
    ) {
        // The same problem reported again (the TCP guard does on every network change) keeps its
        // number, so a swiped-away notification stays away and nothing alerts twice.
        val previous = issues[issue.key]
        val same = previous != null && previous.first.title == issue.title && previous.first.text == issue.text
        issues[issue.key] = issue to (if (same) previous!!.second else sequence.incrementAndGet())
        Timber.tag("ShizukuIssues").i("%s: %s", issue.key, issue.title)
        render(context.applicationContext)
    }

    fun clear(
        context: Context,
        key: String,
    ) {
        if (issues.remove(key) != null) render(context.applicationContext)
    }

    fun clearStartProblems(context: Context) {
        if (issues.keys.removeAll(START_KEYS)) render(context.applicationContext)
    }

    /** The shared notification was swiped away (its delete intent). */
    fun onSharedDismissed(
        key: String?,
        number: Long,
    ) {
        if (key != null) dismissed = key to number
        postedSeverity = null
    }

    /**
     * The server went down because the network it was reached over did (Wireless ADB without
     * Wi-Fi). Expected, so it is not reported as a failure; the Watchdog skips it the same way.
     */
    fun droppedWithNetwork(context: Context): Boolean =
        ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ADB &&
            !NetworkStateHelper.isWirelessAdbSupportedNetwork(context) &&
            EnvironmentUtils.isWifiRequired(allowNetworkProbe = false)

    /**
     * Crashes and the TCP guard posted on channels of their own (the guard's popped up over other
     * apps). Both now go through this one, so the empty channels are removed from the app's
     * notification settings, once.
     */
    fun deleteRetiredChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val prefs = ShizukuSettings.getPreferences() ?: return
        if (prefs.getBoolean(PREF_RETIRED_CHANNELS_DELETED, false)) return
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            listOf("crash_reports", "TcpNetworkGuard").forEach { nm.deleteNotificationChannel(it) }
            prefs.edit().putBoolean(PREF_RETIRED_CHANNELS_DELETED, true).apply()
        }.onFailure { Timber.tag("ShizukuIssues").w(it, "could not delete the retired channels") }
    }

    private const val PREF_RETIRED_CHANNELS_DELETED = "retired_problem_channels_deleted"

    fun alertLevel(): AlertLevel = AlertLevel.of(ShizukuSettings.getHelperNotificationLevel())

    /** Starts the server again, as the start notification's "Attempt now" does. */
    fun retryAction(context: Context): Action =
        Action(
            context.getString(R.string.starter_retry),
            PendingIntent.getActivity(
                context,
                13,
                Intent(context, af.shizuku.manager.receiver.NotifAttemptActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )

    /** Opens Service Doctor, to see what is wrong and fix it by hand. */
    fun openDoctorAction(context: Context): Action =
        Action(
            context.getString(R.string.issue_open_doctor),
            PendingIntent.getActivity(
                context,
                14,
                Intent(context, af.shizuku.manager.settings.ServiceDoctorActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )

    fun stoppedIssue(
        context: Context,
        actions: List<Action> = listOf(retryAction(context), openDoctorAction(context)),
        contentIntent: PendingIntent? = null,
    ): Issue =
        Issue(
            KEY_STOPPED,
            Severity.URGENT,
            context.getString(R.string.issue_stopped_title),
            context.getString(R.string.issue_stopped_text),
            actions,
            contentIntent,
        )

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var stoppedCheck: Runnable? = null

    /**
     * The server died unexpectedly. The Watchdog restarts it and reports what it could not fix;
     * without it (or with its restarting turned off), nothing will bring the server back, so after a short wait (another starter may
     * already be on it) that is reported here.
     */
    fun onServerLost(context: Context) {
        val app = context.applicationContext
        main.post {
            stoppedCheck?.let { main.removeCallbacks(it) }
            if (WatchdogService.isRestarting()) return@post
            val check =
                Runnable {
                    if (!ShizukuStateMachine.isRunning() && !WatchdogService.isRestarting() && !droppedWithNetwork(app)) {
                        report(app, stoppedIssue(app))
                    }
                }
            stoppedCheck = check
            main.postDelayed(check, STOPPED_GRACE_MS)
        }
    }

    private const val STOPPED_GRACE_MS = 15_000L

    /** Opens Home; the default tap target of a problem without one of its own. */
    fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Re-applies the current problems, e.g. after the Watchdog notification appears or goes. */
    fun render(context: Context) {
        val entry = topEntry()
        val issue = entry?.first
        currentFlow.value = issue
        val ongoing = WatchdogService.isRunning()
        if (ongoing) WatchdogService.refreshNotification(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val route = issue?.let { route(ongoing, alertLevel(), it.severity) }
        val swiped = entry != null && dismissed == (entry.first.key to entry.second)
        if (entry == null || issue == null || route?.shared != true || swiped || !ShizukuSettings.isNotificationSystemEnabled()) {
            nm.cancel(NOTIFICATION_ID)
            postedSeverity = null
            return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Default importance: a sound and a status-bar icon, never a pop-up over another app.
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.helper_notifications_channel), NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val builder =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification_icon)
                    .setContentTitle(issue.title)
                    .setContentText(issue.text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(issue.text))
                    .setContentIntent(issue.contentIntent ?: openAppIntent(context))
                    .setDeleteIntent(
                        PendingIntent.getBroadcast(
                            context,
                            NOTIFICATION_ID,
                            Intent(context, IssueDismissReceiver::class.java)
                                .putExtra(IssueDismissReceiver.EXTRA_KEY, issue.key)
                                .putExtra(IssueDismissReceiver.EXTRA_NUMBER, entry.second),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    ).setCategory(NotificationCompat.CATEGORY_ERROR)
                    // A problem that only changes its wording does not alert again.
                    .setOnlyAlertOnce(true)
                    .setSilent(issue.severity != Severity.URGENT)
                    .setAutoCancel(true)
            issue.actions.take(3).forEach { builder.addAction(0, it.label, it.intent) }
            // setOnlyAlertOnce would keep an update silent; a problem that became urgent must alert.
            if (issue.severity == Severity.URGENT && postedSeverity == Severity.PROBLEM) nm.cancel(NOTIFICATION_ID)
            nm.notify(NOTIFICATION_ID, builder.build())
            postedSeverity = issue.severity
        }.onFailure { Timber.tag("ShizukuIssues").w(it, "could not post the problem notification") }
    }
}
