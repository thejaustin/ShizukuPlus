package af.shizuku.manager.adb
import af.shizuku.manager.MainActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.home.HomeActivity
import af.shizuku.manager.utils.EnvironmentUtils
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.ConnectException

/**
 * Optional pairing-code auto-fill: reads the six-digit code (and the port) from the system
 * "Pair device with pairing code" dialog and pairs with it, then starts the server.
 *
 * <p>Reading another app's screen before Shizuku runs is only possible through accessibility, so
 * this stays opt-in; the default flow is the heads-up notification from [AdbPairingService],
 * where the code is typed in. Only Settings windows are read (see accessibility_service_config),
 * and the service turns itself off after pairing or after a minute without Settings activity.
 */
class AdbPairingAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "AdbAccessibility"

        // Idle time (no Settings events) before the service turns itself off, and the hard cap.
        private const val IDLE_TIMEOUT_MS = 60_000L
        private const val MAX_LIFETIME_MS = 5 * 60_000L

        private val ipPortRegex = Regex("""(?:\d{1,3}\.){3}\d{1,3}:(\d{2,5})""")

        // The code is shown on its own as six digits; some OEMs space it out ("123 456").
        private val codeRegex = Regex("""^\d{3}\s?\d{3}$""")

        /** True while a code read from the screen is being paired; the notification stays quiet. */
        @Volatile
        var isPairing: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var pairingStarted = false
    private var connectedAt = 0L

    @Volatile
    private var lastEventAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()

        Sentry.addBreadcrumb(
            Breadcrumb("ADB Pairing Accessibility Service connected").apply {
                category = "adb.pairing"
            },
        )

        val isTv = EnvironmentUtils.isTelevision()

        if (!EnvironmentUtils.isTlsSupported()) {
            Toast.makeText(this, getString(R.string.toast_accessibility_tv_only), Toast.LENGTH_SHORT).show()
            disableSelf()
            return
        }

        if (isTv) {
            val intent =
                Intent(this, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                    putExtra(HomeActivity.EXTRA_SHOW_PAIRING_DIALOG, true)
                }
            startActivity(intent)
        } else {
            Toast.makeText(this, R.string.accessibility_service_monitoring, Toast.LENGTH_SHORT).show()
        }

        connectedAt = SystemClock.elapsedRealtime()
        lastEventAt = connectedAt
        // Turn off when the user stops using Settings for a minute (or after five minutes at most),
        // so the service never lingers in the background.
        serviceScope.launch(Dispatchers.Main) {
            while (true) {
                delay(5_000)
                if (pairingStarted) return@launch
                // The dialog may show the code before mDNS resolves its port and then send no
                // further events: look again once the port is known.
                if (AdbPairingService.discoveredPairingPort > 0) {
                    rootInActiveWindow?.let { scan(it) }
                    if (pairingStarted) return@launch
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastEventAt > IDLE_TIMEOUT_MS || now - connectedAt > MAX_LIFETIME_MS) {
                    Timber.tag(TAG).w("Pairing discovery timed out")
                    Sentry.addBreadcrumb(
                        Breadcrumb("Pairing discovery timed out").apply {
                            level = SentryLevel.WARNING
                        },
                    )
                    Toast.makeText(this@AdbPairingAccessibilityService, getString(R.string.toast_pairing_timeout), Toast.LENGTH_LONG).show()
                    disableSelf()
                    return@launch
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || pairingStarted) return
        lastEventAt = SystemClock.elapsedRealtime()

        // Look at the whole window the event came from: the code and the IP:port are separate
        // views of the same dialog, and both must come from it.
        val root = event.source?.let { rootOf(it) } ?: rootInActiveWindow ?: return
        scan(root)
    }

    private fun scan(root: AccessibilityNodeInfo) {
        if (pairingStarted) return
        var port: Int? = null
        var code: String? = null
        walk(root, 0) { text ->
            if (code == null) {
                val compact = text.trim()
                if (codeRegex.matches(compact)) code = compact.replace(" ", "")
            }
            if (port == null) {
                ipPortRegex
                    .find(text)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
                    ?.let { port = it }
            }
            code != null && port != null
        }

        val foundCode = code ?: return
        // The port mDNS discovered for the open dialog is authoritative; the on-screen one is the
        // fallback when the notification flow is not running.
        val pairingPort =
            AdbPairingService.discoveredPairingPort.takeIf { it > 0 } ?: port ?: return
        pairingStarted = true
        isPairing = true
        AdbPairingService.setState(AdbPairingService.Phase.PAIRING)
        Sentry.addBreadcrumb(
            Breadcrumb("Pairing code found on screen").apply {
                category = "adb.pairing"
            },
        )
        pair(pairingPort, foundCode)
    }

    private fun pair(
        port: Int,
        code: String,
    ) {
        serviceScope.launch {
            var toastMsg = getString(R.string.notification_adb_pairing_failed_title)
            var succeeded = false
            try {
                val key =
                    try {
                        AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku+")
                    } catch (e: Throwable) {
                        Timber.tag(TAG).e(e, "Failed to load AdbKey")
                        Sentry.captureException(e)
                        toastMsg = getString(R.string.adb_error_key_store)
                        return@launch
                    }

                AdbPairingClient("127.0.0.1", port, code, key)
                    .runCatching {
                        start()
                    }.onFailure {
                        Timber.tag(TAG).e(it, "Pairing client failed")
                        when (it) {
                            is ConnectException -> toastMsg = getString(R.string.cannot_connect_port)
                            is AdbInvalidPairingCodeException -> toastMsg = getString(R.string.paring_code_is_wrong)
                            is AdbKeyException -> toastMsg = getString(R.string.adb_error_key_store)
                            else -> Sentry.captureException(it)
                        }
                    }.onSuccess { paired ->
                        if (paired) {
                            Sentry.addBreadcrumb(
                                Breadcrumb("Pairing client succeeded").apply {
                                    category = "adb.pairing"
                                },
                            )
                            toastMsg = getString(R.string.notification_adb_pairing_succeed_title)
                            // The notification flow has nothing left to do.
                            AdbPairingService.dismiss(this@AdbPairingAccessibilityService)
                            val queued = AdbPairingService.onPaired(this@AdbPairingAccessibilityService)
                            succeeded = true
                            AdbPairingService.setState(
                                if (queued) AdbPairingService.Phase.STARTING else AdbPairingService.Phase.PAIRED,
                            )
                            if (!queued) {
                                // An accessibility service may start activities from the
                                // background; Home starts the server from this extra. If the
                                // platform still blocks it, the pairing screen starts it on return.
                                val intent =
                                    Intent(this@AdbPairingAccessibilityService, MainActivity::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                        putExtra(HomeActivity.EXTRA_START_SERVICE_VIA_WADB, true)
                                    }
                                try {
                                    startActivity(intent)
                                } catch (e: Throwable) {
                                    Timber.tag(TAG).w(e, "Could not open Home after pairing")
                                }
                            }
                        } else {
                            Sentry.addBreadcrumb(
                                Breadcrumb("Pairing client returned false").apply {
                                    category = "adb.pairing"
                                    level = SentryLevel.WARNING
                                },
                            )
                        }
                    }
            } finally {
                if (!succeeded) {
                    AdbPairingService.setState(
                        AdbPairingService.Phase.FAILED,
                        toastMsg.takeIf { it != getString(R.string.notification_adb_pairing_failed_title) },
                    )
                }
                isPairing = false
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AdbPairingAccessibilityService, toastMsg, Toast.LENGTH_LONG).show()
                    disableSelf()
                }
            }
        }
    }

    private fun rootOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        var depth = 0
        while (depth < 50) {
            current = current.parent ?: break
            depth++
        }
        return current
    }

    /** Depth-first walk over node texts; stops as soon as [visit] returns true. */
    private fun walk(
        node: AccessibilityNodeInfo?,
        depth: Int,
        visit: (String) -> Boolean,
    ): Boolean {
        if (node == null || depth > 15) return false // bounded: some OEM trees are deep
        val text = node.text?.toString()
        if (!text.isNullOrEmpty() && visit(text)) return true
        for (i in 0 until node.childCount) {
            if (walk(node.getChild(i), depth + 1, visit)) return true
        }
        return false
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        isPairing = false
        serviceScope.cancel()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        isPairing = false
        serviceScope.cancel()
    }
}
