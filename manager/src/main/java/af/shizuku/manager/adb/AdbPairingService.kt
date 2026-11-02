package af.shizuku.manager.adb
import af.shizuku.manager.MainActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.home.HomeActivity
import android.annotation.TargetApi
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.util.Log
import androidx.lifecycle.Observer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.core.ktx.unsafeLazy
import timber.log.Timber
import java.net.ConnectException

@TargetApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {
    enum class Phase { IDLE, SEARCHING, FOUND, PAIRING, PAIRED, STARTING, FAILED }

    data class State(
        val phase: Phase,
        val detail: String? = null,
    )

    companion object {
        const val NOTIFICATION_CHANNEL = "adb_pairing"
        const val NOTIFICATION_ID = 1
        private const val RESULT_NOTIFICATION_ID = 3

        private const val tag = "AdbPairingService"

        private const val replyRequestId = 1
        private const val stopRequestId = 2
        private const val retryRequestId = 3
        private const val launchRequestId = 4
        private const val startRequestId = 5
        private const val dialogRequestId = 6
        private const val startAction = "start"
        private const val stopAction = "stop"
        private const val replyAction = "reply"
        internal const val dialogReplyAction = "dialog_reply"
        private const val remoteInputResultKey = "paring_code"
        private const val portKey = "port_number"
        internal const val dialogCodeKey = "dialog_code"

        private val stateFlow = MutableStateFlow(State(Phase.IDLE))

        /** Where pairing stands, for the pairing screen's live status (both pairing paths). */
        val state: StateFlow<State> = stateFlow.asStateFlow()

        internal fun setState(
            phase: Phase,
            detail: String? = null,
        ) {
            stateFlow.value = State(phase, detail)
        }

        // Port of the pairing service the open "Pair with code" dialog advertises, or -1. Read by
        // the auto-fill accessibility service, which prefers it over the port parsed from screen.
        @Volatile
        var discoveredPairingPort: Int = -1
            private set

        // When the last pairing succeeded, and whether the server start was already queued for it.
        @Volatile
        private var pairedAtMs = 0L

        @Volatile
        private var startQueuedForPairing = false

        /**
         * Records a successful pairing; returns true when the server start was queued in the
         * background (WRITE_SECURE_SETTINGS held), so nothing else should start it again.
         */
        internal fun onPaired(context: Context): Boolean {
            pairedAtMs = android.os.SystemClock.elapsedRealtime()
            val canStartInBackground =
                context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            startQueuedForPairing = canStartInBackground
            if (canStartInBackground) {
                af.shizuku.manager.worker.AdbStartWorker
                    .enqueue(context, explicit = true)
            }
            return canStartInBackground
        }

        /**
         * True once for a pairing that succeeded in the last two minutes and whose server start
         * was not queued yet - the pairing screen then starts the server itself when the user
         * comes back to it.
         */
        internal fun consumeRecentPairingNeedingStart(): Boolean {
            val recent =
                pairedAtMs != 0L &&
                    android.os.SystemClock.elapsedRealtime() - pairedAtMs < 120_000L
            val needsStart = recent && !startQueuedForPairing
            if (recent) pairedAtMs = 0L
            return needsStart
        }

        /** Stops a running pairing service and removes its notification (another path paired). */
        internal fun dismiss(context: Context) {
            try {
                context.stopService(Intent(context, AdbPairingService::class.java))
            } catch (e: Throwable) {
                Timber.tag(tag).w(e, "failed to stop pairing service")
            }
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }

        /**
         * The pairing helper's result, as this service's notification, for when its toast is held
         * back: the user is in Settings, and a failed pairing would otherwise end silently.
         */
        internal fun notifyResult(
            context: Context,
            text: String,
        ) {
            runCatching {
                val nm = context.getSystemService(NotificationManager::class.java) ?: return
                nm.createNotificationChannel(
                    NotificationChannel(
                        NOTIFICATION_CHANNEL,
                        context.getString(R.string.notification_channel_adb_pairing),
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        setSound(null, null)
                        setShowBadge(false)
                        setAllowBubbles(false)
                    },
                )
                // Its own id: the pairing service cancels NOTIFICATION_ID when it stops, which
                // removed this result as soon as it appeared.
                nm.notify(
                    RESULT_NOTIFICATION_ID,
                    Notification
                        .Builder(context, NOTIFICATION_CHANNEL)
                        .setColor(context.getColor(R.color.notification))
                        .setSmallIcon(R.drawable.ic_notification_icon)
                        .setContentTitle(text)
                        .setContentIntent(
                            PendingIntent.getActivity(
                                context,
                                NOTIFICATION_ID,
                                Intent(context, af.shizuku.manager.MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                            ),
                        ).setAutoCancel(true)
                        .build(),
                )
            }.onFailure { Timber.tag(tag).w(it, "could not post the pairing result") }
        }

        fun startIntent(context: Context): Intent = Intent(context, AdbPairingService::class.java).setAction(startAction)

        private fun stopIntent(context: Context): Intent = Intent(context, AdbPairingService::class.java).setAction(stopAction)

        private fun replyIntent(
            context: Context,
            port: Int,
        ): Intent = Intent(context, AdbPairingService::class.java).setAction(replyAction).putExtra(portKey, port)

        fun dialogReplyIntent(
            context: Context,
            port: Int,
            code: String,
        ): Intent =
            Intent(context, AdbPairingService::class.java)
                .setAction(dialogReplyAction)
                .putExtra(portKey, port)
                .putExtra(dialogCodeKey, code)
    }

    private var adbMdns: AdbMdns? = null

    // Set once a code was submitted: from then on the notification shows progress and the result,
    // and a "service lost" (Settings closes the dialog when pairing succeeds) must not replace it.
    @Volatile
    private var pairingSubmitted = false

    // The port the "enter code" notification last alerted for. mDNS can drop and re-find the same
    // service; only a new dialog (new port) pops up again.
    private var alertedPort = -1

    private val observer =
        Observer<Int> { port ->
            Timber.tag(tag).i("Pairing service port: $port")
            if (pairingSubmitted) return@Observer
            val nm = getSystemService(NotificationManager::class.java)
            if (port <= 0) {
                // The pairing dialog was closed: drop the reply action, it points at a dead port.
                if (discoveredPairingPort > 0) {
                    discoveredPairingPort = -1
                    setState(Phase.SEARCHING)
                    nm.notify(NOTIFICATION_ID, searchingNotification)
                }
                return@Observer
            }
            discoveredPairingPort = port
            if (!AdbPairingAccessibilityService.isPairing) setState(Phase.FOUND)

            // Alert (heads-up over Settings) when a pairing dialog appears, so the code can be typed
            // straight into the notification without leaving the dialog - unless the auto-fill
            // accessibility service is already pairing with the code it read from the screen.
            val alert = port != alertedPort && !AdbPairingAccessibilityService.isPairing
            if (alert) alertedPort = port

            // Since the service could be killed before user finishing input,
            // we need to put the port into Intent
            nm.notify(NOTIFICATION_ID, createInputNotification(port, alert))
        }

    private var started = false

    override fun onCreate() {
        super.onCreate()

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                getString(R.string.notification_channel_adb_pairing),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                setShowBadge(false)
                setAllowBubbles(false)
            },
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val notification =
            when (intent?.action) {
                startAction -> {
                    onStart()
                }
                replyAction -> {
                    val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(remoteInputResultKey) ?: ""
                    val port = intent.getIntExtra(portKey, -1)
                    if (port != -1) {
                        onInput(code.toString(), port)
                    } else {
                        onStart()
                    }
                }
                dialogReplyAction -> {
                    val code = intent.getStringExtra(dialogCodeKey) ?: ""
                    val port = intent.getIntExtra(portKey, -1)
                    if (port != -1 && code.isNotEmpty()) {
                        onInput(code, port)
                    } else {
                        onStart()
                    }
                }
                stopAction -> {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    null
                }
                else -> {
                    return START_NOT_STICKY
                }
            }
        if (notification != null) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST,
                )
            } catch (e: Throwable) {
                // startForeground can be denied by the OS: a FGS-start-not-allowed from background,
                // or a connectedDevice-FGS SecurityException on OEMs that don't grant the required
                // companion permission (SHIZUKUPLUS-6E). The pairing notification IS the UI the user
                // needs, so post it directly whenever FGS fails — pairing still works without the
                // foreground-service upgrade. Warn (not error) so it doesn't read as a hard crash.
                Timber.tag(tag).w(e, "startForeground failed; posting pairing notification without FGS")
                try {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
                } catch (t: Throwable) {
                    Timber.tag(tag).e(t, "failed to post pairing notification fallback")
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    // The system's limit for this service type. Removing the notification is the only sign: a
    // toast would show over whatever app the user is in.
    override fun onTimeout(startId: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startSearch() {
        if (started) return
        started = true
        adbMdns = AdbMdns(this, AdbMdns.TLS_PAIRING, observer).apply { start() }
    }

    private fun stopSearch() {
        if (!started) return
        started = false
        adbMdns?.stop()
    }

    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        super.onDestroy()
        discoveredPairingPort = -1
        val phase = state.value.phase
        if (phase == Phase.SEARCHING || phase == Phase.FOUND) setState(Phase.IDLE)
        stopSearch()
        serviceScope.cancel()
    }

    private fun onStart(): Notification {
        if (!pairingSubmitted) setState(if (discoveredPairingPort > 0) Phase.FOUND else Phase.SEARCHING)
        startSearch()
        return searchingNotification
    }

    private fun onInput(
        code: String,
        port: Int,
    ): Notification {
        pairingSubmitted = true
        setState(Phase.PAIRING)
        serviceScope.launch {
            // Prefer the mDNS-resolved host; falls back to loopback if discovery isn't live (e.g.
            // the service was killed and restarted from the reply intent, which only carries the port).
            val host = adbMdns?.resolvedHost ?: "127.0.0.1"

            val key =
                try {
                    AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku+")
                } catch (e: Throwable) {
                    Timber.e("failed to load or create AdbKey", e)
                    return@launch
                }

            AdbPairingClient(host, port, code, key)
                .runCatching {
                    start()
                }.onFailure {
                    handleResult(false, it)
                }.onSuccess {
                    handleResult(it, null)
                }
        }

        return workingNotification
    }

    private fun handleResult(
        success: Boolean,
        exception: Throwable?,
    ) {
        stopForeground(STOP_FOREGROUND_DETACH)

        val title: String
        val text: String?
        var autoStarted = false

        if (success) {
            Timber.tag(tag).i("Pair succeed")

            title = getString(R.string.notification_adb_pairing_succeed_title)
            text = null

            stopSearch()
            // No activity launch here: CLEAR_TOP on MainActivity would tear down onboarding / the
            // pairing tutorial, and a background activity start is blocked on Android 10+ anyway.
            // With WRITE_SECURE_SETTINGS the start is queued in the background; otherwise the
            // success notification carries the Start action and the pairing screen starts the
            // server when the user returns to it.
            autoStarted = onPaired(this)
            setState(if (autoStarted) Phase.STARTING else Phase.PAIRED)
        } else {
            title = getString(R.string.notification_adb_pairing_failed_title)

            text =
                when (exception) {
                    is ConnectException -> {
                        getString(R.string.cannot_connect_port)
                    }
                    is AdbInvalidPairingCodeException -> {
                        getString(R.string.paring_code_is_wrong)
                    }
                    is AdbKeyException -> {
                        getString(R.string.adb_error_key_store)
                    }
                    else -> {
                        exception?.let { Log.getStackTraceString(it) }
                    }
                }

            // Known causes have a readable message; anything else is a stack trace, which belongs
            // in the notification's detail, not on the pairing screen.
            val knownCause =
                exception is ConnectException || exception is AdbInvalidPairingCodeException || exception is AdbKeyException
            setState(Phase.FAILED, if (knownCause) text else null)
            if (exception != null) {
                Timber.tag(tag).w(exception, "Pair failed")
            } else {
                Timber.tag(tag).w("Pair failed")
            }
        }

        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            Notification
                .Builder(this, NOTIFICATION_CHANNEL)
                .setColor(getColor(R.color.notification))
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(title)
                // The result alerts once (no setOnlyAlertOnce): the user is still in Settings, and
                // the heads-up is where they see a wrong code or tap Start.
                .setContentText(
                    when {
                        !success -> text
                        autoStarted -> getString(R.string.notification_adb_pairing_succeed_starting)
                        else -> getString(R.string.notification_adb_pairing_succeed_text)
                    },
                ).apply {
                    if (!success) {
                        addAction(retryNotificationAction)
                    } else {
                        setContentIntent(launchPendingIntent)
                        if (!autoStarted) {
                            addAction(startNotificationAction)
                        } else {
                            // The start worker posts its own progress; this is only a note.
                            setTimeoutAfter(60_000L)
                        }
                        setAutoCancel(true)
                    }
                }.build(),
        )
        stopSelf()
    }

    private val launchIntent by unsafeLazy {
        Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
    }

    private val launchPendingIntent by unsafeLazy {
        PendingIntent.getActivity(
            this,
            launchRequestId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private val startNotificationAction by unsafeLazy {
        val startIntent =
            Intent(launchIntent)
                .putExtra(HomeActivity.EXTRA_START_SERVICE_VIA_WADB, true)

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                startRequestId,
                startIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        Notification.Action
            .Builder(
                null,
                getString(R.string.home_root_button_start),
                pendingIntent,
            ).build()
    }

    private val stopNotificationAction by unsafeLazy {
        val pendingIntent =
            PendingIntent.getService(
                this,
                stopRequestId,
                stopIntent(this),
                // FLAG_IMMUTABLE has existed since API 23; there's no reason to leave these mutable
                // pre-S like the >= S gate previously did.
                PendingIntent.FLAG_IMMUTABLE,
            )

        Notification.Action
            .Builder(
                null,
                getString(R.string.notification_adb_pairing_stop_searching),
                pendingIntent,
            ).build()
    }

    private val retryNotificationAction by unsafeLazy {
        val pendingIntent =
            PendingIntent.getService(
                this,
                retryRequestId,
                startIntent(this),
                // FLAG_IMMUTABLE has existed since API 23; there's no reason to leave these mutable
                // pre-S like the >= S gate previously did.
                PendingIntent.FLAG_IMMUTABLE,
            )

        Notification.Action
            .Builder(
                null,
                getString(R.string.notification_adb_pairing_retry),
                pendingIntent,
            ).build()
    }

    private val replyNotificationAction: Notification.Action
        get() {
            val remoteInput =
                RemoteInput.Builder(remoteInputResultKey).run {
                    setLabel(getString(R.string.dialog_adb_pairing_paring_code))
                    if (ShizukuSettings.isNumericalPairingKeyboardEnabled()) {
                        val extras =
                            Bundle().apply {
                                putInt("android.support.remoteinput.EXTRA_INPUT_TYPE", InputType.TYPE_CLASS_NUMBER)
                                putInt("android.content.extra.input_type", InputType.TYPE_CLASS_NUMBER)
                            }
                        addExtras(extras)
                    }
                    build()
                }

            val pendingIntent =
                PendingIntent.getForegroundService(
                    this,
                    replyRequestId,
                    replyIntent(this, -1),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    },
                )

            return Notification.Action
                .Builder(
                    null,
                    getString(R.string.notification_adb_pairing_input_paring_code),
                    pendingIntent,
                ).addRemoteInput(remoteInput)
                .build()
        }

    private fun replyNotificationAction(port: Int): Notification.Action {
        // Ensure pending intent is created
        PendingIntent.getForegroundService(
            this,
            replyRequestId,
            replyIntent(this, port),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            },
        )

        return replyNotificationAction
    }

    private val searchingNotification by unsafeLazy {
        val manualIntent =
            Intent(this, AdbPairingDialogActivity::class.java).apply {
                putExtra(portKey, -1)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        val manualPendingIntent =
            PendingIntent.getActivity(
                this,
                dialogRequestId,
                manualIntent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                },
            )

        Notification
            .Builder(this, NOTIFICATION_CHANNEL)
            .setColor(getColor(R.color.notification))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(getString(R.string.notification_adb_pairing_searching_for_service_title))
            .setContentText(getString(R.string.notification_adb_pairing_input_paring_code))
            .setContentIntent(manualPendingIntent)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action
                    .Builder(
                        null,
                        getString(R.string.notification_adb_pairing_input_paring_code),
                        manualPendingIntent,
                    ).build(),
            ).addAction(stopNotificationAction)
            .build()
    }

    private fun createInputNotification(
        port: Int,
        alert: Boolean,
    ): Notification {
        val dialogIntent =
            Intent(this, AdbPairingDialogActivity::class.java)
                .putExtra(portKey, port)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent =
            PendingIntent.getActivity(
                this,
                dialogRequestId + port,
                dialogIntent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                },
            )
        if (ShizukuSettings.getLegacyPairing()) {
            return Notification
                .Builder(this, NOTIFICATION_CHANNEL)
                .setColor(getColor(R.color.notification))
                .setContentTitle(getString(R.string.notification_adb_pairing_service_found_title))
                .setContentText(getString(R.string.notification_adb_pairing_input_paring_code))
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentIntent(contentIntent)
                .setAutoCancel(false)
                .setOnlyAlertOnce(!alert)
                .build()
        }
        return Notification
            .Builder(this, NOTIFICATION_CHANNEL)
            .setColor(getColor(R.color.notification))
            .setContentTitle(getString(R.string.notification_adb_pairing_service_found_title))
            .setContentText(getString(R.string.notification_adb_pairing_input_paring_code))
            .setSmallIcon(R.drawable.ic_notification_icon)
            // No content intent: tapping the body would open an activity over Settings and can
            // close the system pairing dialog (invalidating the code). Inline reply only.
            // Not setOnlyAlertOnce: this replaces the "searching" notification under the same id,
            // and only an alerting update shows as a heads-up over the Settings dialog.
            .setOnlyAlertOnce(!alert)
            .addAction(replyNotificationAction(port))
            .build()
    }

    private val workingNotification by unsafeLazy {
        Notification
            .Builder(this, NOTIFICATION_CHANNEL)
            .setColor(getColor(R.color.notification))
            .setContentTitle(getString(R.string.notification_adb_pairing_working_title))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
