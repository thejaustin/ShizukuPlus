package af.shizuku.manager

import af.shizuku.core.ui.ThemeDelegateManager
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.database.AppContextManager
import af.shizuku.manager.di.appModule
import af.shizuku.manager.service.WatchdogService
import af.shizuku.manager.utils.ActivityLogSettingsImpl
import af.shizuku.manager.utils.AppContextSettingsImpl
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.utils.ThemeDelegateImpl
import af.shizuku.manager.worker.RemoteDbSyncWorker
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.work.Configuration
import com.airbnb.mvrx.Mavericks
import com.topjohnwu.superuser.Shell
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import io.sentry.android.timber.SentryTimberTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.core.util.BuildUtils.atLeast30
import rikka.material.app.LocaleDelegate
import rikka.shizuku.Shizuku
import timber.log.Timber

/**
 * Shizuku+ Application class
 *
 * Initialization order:
 * 1. Sentry (for crash reporting)
 * 2. Static components (native libraries)
 * 3. Settings and managers
 * 4. State machine
 */
class ShizukuApplication :
    Application(),
    Configuration.Provider {
    /** Background work tied to the process. A failure is logged instead of crashing the app. */
    private val appScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                kotlinx.coroutines.CoroutineExceptionHandler { _, t -> Timber.e(t, "Background task failed") },
        )

    // SharedPreferences only holds change listeners weakly - keep strong references here or they
    // get garbage collected and silently stop firing.
    private var peerSyncPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var keepAlivePrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    companion object {
        lateinit var appContext: Context
            private set

        // Shizuku+ activities between onStart and onStop; above zero, the app is on screen.
        private val startedActivities =
            java.util.concurrent.atomic
                .AtomicInteger()

        /** Whether one of the app's own screens is showing (a toast then lands on it). */
        fun isOnScreen(): Boolean = startedActivities.get() > 0

        /** True only if libadb.so loaded successfully. ADB pairing features must check this. */
        var isAdbNativeAvailable: Boolean = false
            private set

        /**
         * Per-process Sentry event cap. A single device stuck in a crash/error loop can otherwise
         * emit hundreds of identical events in one session (one r1989 install produced 1143), which
         * drains the quota for no added signal — the first few events already capture the bug.
         */
        private val sentrySessionEventCount =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        private const val MAX_SENTRY_EVENTS_PER_SESSION = 5
    }

    // WorkManager's own internal background thread (e.g. ForceStopRunnable's Room queries) can
    // throw on an unrecoverable device condition (observed: full-disk SQLiteFullException, which
    // WorkManager converts to an IllegalStateException) entirely inside library code, with no
    // ShizukuPlus frames in the stack. Without a custom TaskExecutor to catch it here, that
    // reaches the process-wide uncaught-exception handler and kills the whole app.
    private val workManagerTaskExecutor: java.util.concurrent.Executor by lazy {
        val delegate =
            java.util.concurrent.Executors
                .newFixedThreadPool(4)
        java.util.concurrent.Executor { command ->
            delegate.execute {
                try {
                    command.run()
                } catch (t: Throwable) {
                    Timber.e(t, "WorkManager task threw on internal executor")
                }
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.INFO)
                .setTaskExecutor(workManagerTaskExecutor)
                .build()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        appContext = base
        // Initialize ShizukuSettings as early as possible
        ShizukuSettings.initialize(base)
        // Before the AICore+ accessibility service can connect (it runs in this process).
        af.shizuku.manager.automation.AICoreAccessibilityHealer
            .recordBaseline(base)
    }

    /**
     * Initialize Sentry FIRST to catch all crashes including early startup failures
     */
    internal fun initializeSentryEarly() {
        if (BuildConfig.SENTRY_DSN.isEmpty()) {
            Timber.w("Sentry DSN is empty, skipping initialization")
            return
        }
        // Consent first: nothing is sent until the user said yes (first-run prompt or setting).
        if (!ShizukuSettings.isCrashReportingEnabled()) {
            Timber.i("Crash reporting is off")
            return
        }
        if (Sentry.isEnabled()) return

        af.shizuku.manager.utils.SentryEventDeduper
            .resetIfWindowExpired()

        if (ShizukuSettings.isSentryLimitReached()) {
            Timber.i("Sentry quota reached, skipping initialization")
            return
        }

        try {
            SentryAndroid.init(this) { options ->
                options.dsn = BuildConfig.SENTRY_DSN

                // Screenshot/view-hierarchy attachments are billed against a separate (much
                // smaller) attachment quota and bloat every event's payload size. This project
                // is free-tier only (no budget for a paid plan), and per the class doc above
                // Sentry here is "for crash reporting" - not full debugging context capture -
                // so these stay off to conserve quota. Re-enable only if actively debugging a
                // specific hard-to-diagnose UI issue, then turn back off.
                options.isAttachScreenshot = false
                options.isAttachViewHierarchy = false

                // Screenshots are never attached: they can show anything on screen.

                // Breadcrumbs are bundled into the event itself (not separately billed), so
                // keep them for context. isEnableUserInteractionTracing and
                // isEnableAutoActivityLifecycleTracing generate performance "transaction"
                // events on their own quota, unrelated to actual crashes - since this project
                // only needs crash reporting (not APM), those stay off entirely.
                options.isEnableUserInteractionTracing = false
                options.isEnableUserInteractionBreadcrumbs = true
                options.isEnableAutoActivityLifecycleTracing = false

                // ANR detection — 10s threshold avoids false positives from privileged
                // system calls (__epoll_pwait / __ioctl) in the event loop
                options.isAnrEnabled = true
                options.anrTimeoutIntervalMillis = 10000L

                // Performance monitoring / profiling off entirely - this is the single
                // largest quota drain relative to actual crash-reporting value: at any sample
                // rate > 0 it generates a transaction (and, with profilesSampleRate > 0, a
                // profile) for every sampled activity lifecycle across every user's session,
                // continuously, whether or not anything is actually wrong. None of that is
                // needed for "did the app crash and why" - only error events are.
                options.tracesSampleRate = 0.0
                options.profilesSampleRate = 0.0

                // Release tracking with GitHub integration
                options.release = "shizuku-plus@${BuildConfig.VERSION_NAME}"
                options.environment = if (BuildConfig.DEBUG) "development" else "production"
                options.dist = "${BuildConfig.VERSION_CODE}"

                // Session tracking for crash-free rate
                options.isEnableAutoSessionTracking = true
                options.sessionTrackingIntervalMillis = 30000L

                // Include breadcrumbs for navigation tracking
                options.maxBreadcrumbs = 100
                // Logs become breadcrumbs: strip addresses, network names and emails first.
                options.setBeforeBreadcrumb { breadcrumb, _ ->
                    breadcrumb.message =
                        af.shizuku.manager.utils.TelemetryScrubber
                            .scrub(breadcrumb.message)
                    breadcrumb
                }

                // Send default PII (for device info, not user data)
                options.isSendDefaultPii = false

                // Enable NDK crash reporting
                options.isEnableNdk = true

                // Add context about the app
                options.setBeforeSend { event, _ ->
                    // 1. Drop coroutine cancellations
                    val throwable = event.throwable
                    if (throwable is kotlinx.coroutines.CancellationException) return@setBeforeSend null

                    // 2. Drop expected ADB connection failures (transient or user-induced)
                    if (throwable is java.io.EOFException ||
                        throwable is java.net.SocketException ||
                        throwable is java.net.SocketTimeoutException ||
                        throwable is java.net.ConnectException ||
                        throwable is javax.net.ssl.SSLException ||
                        throwable?.javaClass?.simpleName == "AdbKeyException" ||
                        throwable?.javaClass?.simpleName == "AdbInvalidPairingCodeException"
                    ) {
                        return@setBeforeSend null
                    }

                    // 3. Drop "Not an attached client" SecurityExceptions
                    // (Common when apps call Shizuku immediately before the binder is received)
                    if (throwable is SecurityException && throwable.message?.contains("is not an attached client") == true) {
                        return@setBeforeSend null
                    }

                    // 4. Drop FileNotFoundException for last_crash.txt — this file simply
                    // doesn't exist on fresh installs or after a cache clear, and reading
                    // it without a prior exists() check is expected behaviour in the crash
                    // reporter. Not an actionable bug.
                    if (throwable is java.io.FileNotFoundException &&
                        throwable.message?.contains("last_crash") == true
                    ) {
                        return@setBeforeSend null
                    }

                    // 5. Drop BackgroundServiceStartNotAllowedException / IllegalStateException
                    // thrown when the OS refuses to start ShizukuLiveService from background.
                    // These are caught at the call-site; Sentry captures them via the global
                    // uncaught handler before our catch block in older SDK versions. They are
                    // expected on API 31+ when the app is backgrounded.
                    val simpleName = throwable?.javaClass?.simpleName ?: ""
                    if (simpleName == "BackgroundServiceStartNotAllowedException" ||
                        (
                            throwable is IllegalStateException &&
                                (
                                    throwable.message?.contains("startForegroundService") == true ||
                                        // Plain startService() from background on API 31+ (AutomationService /
                                        // ShizukuLiveService). Already caught at the call site; expected, not a bug.
                                        throwable.message?.contains("Not allowed to start service") == true
                                )
                        )
                    ) {
                        return@setBeforeSend null
                    }

                    val message = throwable?.message ?: ""

                    // 6. Drop hardware-keystore failures — OEM TrustZone/StrongBox (TEE) issues,
                    //    not app bugs and not fixable from app code.
                    if (simpleName == "KeyStoreException" ||
                        (simpleName == "ProviderException" && message.contains("eystore")) ||
                        message.contains("AndroidKeyStore is unusable") ||
                        message.contains("Keystore operation failed")
                    ) {
                        return@setBeforeSend null
                    }

                    // 7. Drop device-storage failures — full disk / filesystem IO (device state).
                    if (simpleName == "SQLiteFullException" ||
                        simpleName == "SQLiteDiskIOException" ||
                        message.contains("ENOSPC") ||
                        message.contains("No space left on device") ||
                        message.contains("disk I/O error")
                    ) {
                        return@setBeforeSend null
                    }

                    // 8. Drop the OEM WifiInfo-redaction SecurityException thrown out of
                    //    ConnectivityManager.getNetworkCapabilities() on some ROMs — already caught
                    //    defensively (AutomationService.checkNetworkState); non-eliminable from app code.
                    if (throwable is SecurityException &&
                        (message.contains("ACCESS_WIFI_STATE") || message.contains("getNetworkCapabilities"))
                    ) {
                        return@setBeforeSend null
                    }

                    // 9. Drop SQLiteCantOpenDatabaseException for missing databases directory
                    // (Happens sporadically when OS or user clears data/cache while in background)
                    if (simpleName == "SQLiteCantOpenDatabaseException" &&
                        throwable?.message?.contains("doesn't exist") == true
                    ) {
                        return@setBeforeSend null
                    }

                    // 10. Drop Hardware Keystore failures (KeyPermanentlyInvalidatedException / KeyStoreException)
                    if (throwable is java.security.KeyStoreException ||
                        throwable is java.security.UnrecoverableKeyException ||
                        simpleName == "KeyPermanentlyInvalidatedException" ||
                        simpleName == "KeyStoreException"
                    ) {
                        return@setBeforeSend null
                    }

                    // 11. Drop DeadObjectException from binder IPC when process terminates
                    if (throwable is android.os.DeadObjectException || simpleName == "DeadObjectException") {
                        return@setBeforeSend null
                    }

                    // 12. Drop SecurityException for non-changeable system permissions
                    if (throwable is SecurityException && throwable.message?.contains("is not a changeable permission type") == true) {
                        return@setBeforeSend null
                    }

                    // 13. Crash deduplication — drop repeated occurrences of identical errors within 15 mins
                    val eventTag = event.exceptions?.firstOrNull()?.type ?: event.throwable?.javaClass?.simpleName ?: "Error"
                    val eventMsg = event.exceptions?.firstOrNull()?.value ?: event.message?.formatted ?: event.throwable?.message ?: ""
                    if (!af.shizuku.manager.utils.SentryEventDeduper
                            .shouldSend(eventTag, eventMsg)
                    ) {
                        return@setBeforeSend null
                    }

                    // 14. Session event cap — stop a crash/error loop from draining the quota
                    if (sentrySessionEventCount.incrementAndGet() > MAX_SENTRY_EVENTS_PER_SESSION) {
                        return@setBeforeSend null
                    }

                    // 15. Daily persistent event cap — hard stop at device daily limit (max 10/day)
                    if (!af.shizuku.manager.utils.SentryEventDeduper
                            .checkDailyLimitAndIncrement()
                    ) {
                        return@setBeforeSend null
                    }

                    // Add build config info to events
                    event.setTag("version_name", BuildConfig.VERSION_NAME)
                    event.setTag("version_code", BuildConfig.VERSION_CODE.toString())
                    event.setTag("build_type", if (BuildConfig.DEBUG) "debug" else "release")

                    // Add system state at time of crash
                    try {
                        event.setTag("shizuku_state", if (Shizuku.pingBinder()) "connected" else "disconnected")
                        event.setTag("is_rooted", Shell.isAppGrantedRoot().toString())

                        // Deep Vendor Diagnostics for Triage
                        event.setTag("manufacturer", Build.MANUFACTURER)
                        if (af.shizuku.manager.utils.EnvironmentUtils
                                .isSamsung()
                        ) {
                            event.setTag(
                                "oneui_version",
                                af.shizuku.manager.utils.EnvironmentUtils
                                    .getOneUiVersion()
                                    .toString(),
                            )
                        }
                        if (af.shizuku.manager.utils.EnvironmentUtils
                                .isOppo() ||
                            af.shizuku.manager.utils.EnvironmentUtils
                                .isOnePlus()
                        ) {
                            event.setTag(
                                "coloros_version",
                                af.shizuku.manager.utils.EnvironmentUtils
                                    .getColorOsVersion(),
                            )
                        }
                        if (af.shizuku.manager.utils.EnvironmentUtils
                                .isXiaomi()
                        ) {
                            event.setTag(
                                "hyperos_version",
                                af.shizuku.manager.utils.EnvironmentUtils
                                    .getHyperOsVersion(),
                            )
                        }
                        if (af.shizuku.manager.utils.EnvironmentUtils
                                .isTCL()
                        ) {
                            event.setTag("vendor", "tcl")
                        }
                    } catch (_: Exception) {
                        // Ignore errors during state collection
                    }

                    // Same scrub as breadcrumbs: exception messages can carry addresses ("connect to
                    // 192.168.1.5:5555 failed").
                    event.message?.let { m ->
                        m.formatted =
                            af.shizuku.manager.utils.TelemetryScrubber
                                .scrub(m.formatted)
                        m.message =
                            af.shizuku.manager.utils.TelemetryScrubber
                                .scrub(m.message)
                    }
                    event.exceptions?.forEach { ex ->
                        ex.value =
                            af.shizuku.manager.utils.TelemetryScrubber
                                .scrub(ex.value)
                    }

                    event
                }
            }

            // Set user context (anonymous, for crash grouping)
            Sentry.setUser(null) // Anonymous user
            Sentry.setTag("app_variant", BuildConfig.VERSION_NAME)

            // Plant Sentry Timber tree to automatically capture logs as breadcrumbs
            // Sentry 8.x requires: (scopes, minEventLevel, minBreadcrumbLevel)
            try {
                Timber.plant(
                    io.sentry.android.timber.SentryTimberTree(
                        io.sentry.ScopesAdapter.getInstance(),
                        io.sentry.SentryLevel.ERROR,
                        io.sentry.SentryLevel.INFO,
                    ),
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to plant SentryTimberTree")
            }

            Timber.d("Sentry initialized with release tracking and advanced monitoring")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize Sentry early")
            // Don't throw - allow app to continue even if Sentry fails
        }
    }

    /**
     * Initialize static components (native libraries, etc.)
     */
    private fun initializeStatics() {
        Timber.d("Initializing static components")

        Shell.setDefaultBuilder(
            Shell.Builder
                .create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(20),
        )

        if (Build.VERSION.SDK_INT >= 28) {
            HiddenApiBypass.setHiddenApiExemptions("")
        }

        if (atLeast30) {
            try {
                System.loadLibrary("adb")
                isAdbNativeAvailable = true
                Timber.d("Native library 'adb' loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                // Log and report but do NOT rethrow — ADB pairing features degrade gracefully.
                // Common causes: SELinux policy on vendor ROMs, missing system dependency.
                Timber.e(e, "libadb.so failed to load — ADB pairing features disabled")
                Sentry.captureException(RuntimeException("libadb.so failed to load: ${e.message}", e))
            }
        }
    }

    /**
     * Keeps AppIconCache honest: trims it under memory pressure (it's sized to 1/4 of max heap
     * and never shrinks on its own otherwise) and drops entries for apps that update/uninstall
     * so a changed icon doesn't keep showing the stale cached one.
     */
    private fun registerIconCacheMaintenance() {
        registerComponentCallbacks(
            object : android.content.ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    af.shizuku.manager.utils.AppIconCache
                        .trimMemory(level)
                }

                override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}

                @Deprecated("Deprecated in Java", ReplaceWith("onTrimMemory"))
                override fun onLowMemory() {
                    af.shizuku.manager.utils.AppIconCache
                        .trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
                }
            },
        )

        val packageChangeReceiver =
            object : android.content.BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    val packageName = intent.data?.schemeSpecificPart ?: return
                    af.shizuku.manager.utils.AppIconCache
                        .invalidate(packageName)
                }
            }
        val filter =
            android.content.IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            }
        // Never unregistered — this is an Application-scoped singleton, same lifetime as the process.
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            packageChangeReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /**
     * Initialize settings and managers
     */
    private fun initializeManagers() {
        // Legacy rish clients (MT Manager and other prebuilt rikka/rish loaders) ask for the
        // binder with a broadcast and only wait a few hundred milliseconds for the shell socket.
        // Keep the manager process alive while the server runs so that broadcast is always
        // answered quickly, even when an OEM freezer would otherwise freeze this process.
        // See docs/MT_COMPAT.md.
        appScope.launch {
            ShizukuStateMachine
                .asFlow()
                .distinctUntilChanged()
                .collect {
                    af.shizuku.manager.service.LegacyRishKeepAliveService
                        .sync(this@ShizukuApplication)
                }
        }
        // Apply the settings switch live, not only on the next state change.
        keepAlivePrefsListener =
            SharedPreferences
                .OnSharedPreferenceChangeListener { _, key ->
                    if (key == ShizukuSettings.Keys.KEY_LEGACY_RISH_KEEPALIVE) {
                        af.shizuku.manager.service.LegacyRishKeepAliveService
                            .sync(this)
                    }
                }.also { ShizukuSettings.getPreferences()?.registerOnSharedPreferenceChangeListener(it) }
        // A start refused in the background is retried once the app is in the foreground, where
        // foreground service starts are always allowed.
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    startedActivities.incrementAndGet()
                    if (!af.shizuku.manager.service.LegacyRishKeepAliveService
                            .isRunning()
                    ) {
                        af.shizuku.manager.service.LegacyRishKeepAliveService
                            .sync(this@ShizukuApplication)
                    }
                }

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) {
                    startedActivities.updateAndGet { (it - 1).coerceAtLeast(0) }
                    af.shizuku.manager.utils.SettingsBackupManager
                        .requestAutoBackup(this@ShizukuApplication)
                }

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )

        ActivityLogManager.initialize(this, ActivityLogSettingsImpl())
        af.shizuku.manager.database.ScriptSnippetManager
            .initialize(this)
        af.shizuku.manager.plugin.PlusFeatureRegistry
            .register(af.shizuku.manager.scripting.ScriptingFeatureModule)

        // Auto-run snippets start from ShizukuStateMachine's RUNNING transition, which knows the trigger.

        // Redeploy the SU bridge dex whenever the server comes up, not just on app self-update
        // (#423 fix, `9dba4bd3`, only covered ACTION_MY_PACKAGE_REPLACED). If the server wasn't
        // running yet at that point, deployBridgeToTmp() silently no-ops with nothing to retry
        // it later — common on devices where an OEM freezer (Xiaomi/HyperOS, Samsung "Sleeping
        // apps") or a dropped wireless-ADB session delays the server past that moment. Cheap and
        // idempotent to just redeploy on every RUNNING transition.
        // Sync the SU bridge when the server starts. If enabled, ensure the latest bridge is deployed.
        // If disabled, ensure any stale /data/local/tmp/su binaries are cleaned up to prevent Google Wallet
        // and Play Integrity security failures.
        appScope.launch {
            ShizukuStateMachine
                .asFlow()
                .distinctUntilChanged()
                .filter { it == ShizukuStateMachine.State.RUNNING }
                .collect {
                    try {
                        if (ShizukuSettings.isSuBridgeEnabled()) {
                            af.shizuku.manager.database.RootCompatHelper
                                .deployBridgeToTmp(this@ShizukuApplication)
                        } else {
                            if (af.shizuku.manager.database.RootCompatHelper
                                    .isBridgePresentInTmp()
                            ) {
                                af.shizuku.manager.database.RootCompatHelper
                                    .cleanupBridgeFromTmp(this@ShizukuApplication)
                            }
                        }
                    } catch (e: Exception) {
                        Timber.tag("ShizukuApplication").w(e, "SU bridge sync on service start failed")
                    }

                    // Auto-apply TCP mode and On-Device ADB TCP settings without requiring manual re-toggle
                    try {
                        val tcpMode =
                            ShizukuSettings.getTcpMode() &&
                                !af.shizuku.manager.utils.NetworkTrust
                                    .shouldBlockTcp(this@ShizukuApplication)
                        val onDeviceAdbTcp = ShizukuSettings.isOnDeviceAdbTcpEnabled()
                        if (tcpMode || onDeviceAdbTcp) {
                            val desiredPort = if (tcpMode) ShizukuSettings.getTcpPort().takeIf { it in 1..65535 } ?: 5555 else 5555
                            val currentPort =
                                af.shizuku.manager.utils.EnvironmentUtils
                                    .getAdbTcpPort()
                            if (currentPort != desiredPort) {
                                af.shizuku.manager.service.AdbProxyService
                                    .enableAdbTcp(desiredPort)
                            }
                        }
                    } catch (e: Exception) {
                        Timber.tag("ShizukuApplication").w(e, "TCP mode auto-apply on service start failed")
                    }

                    // Ensure Local ADB Proxy is running if enabled in settings
                    try {
                        if (ShizukuSettings.isAdbProxyEnabled()) {
                            val proxyIntent = Intent(this@ShizukuApplication, af.shizuku.manager.service.AdbProxyService::class.java)
                            startService(proxyIntent)
                        }
                    } catch (e: Exception) {
                        Timber.tag("ShizukuApplication").w(e, "ADB Proxy Service start failed")
                    }
                }
        }
        AppContextManager.initialize(AppContextSettingsImpl(applicationContext))
        ShizukuSettings.clearLegacyEnhancementPrefs()
        LocaleDelegate.defaultLocale = ShizukuSettings.getLocale()

        // #429: one-time migration — push the legacy custom locale into AppCompat's
        // own per-app-language store so AppCompatDelegate.getApplicationLocales()
        // becomes authoritative going forward (system Settings > App Info > Language
        // integration on API 33+; AppLocalesStorageHelper-backed persistence below
        // it). AppActivity.attachBaseContext() re-syncs LocaleDelegate.defaultLocale
        // from AppCompatDelegate on every activity creation once this flag is set,
        // so AppCompatDelegate genuinely becomes the source of truth, not just a
        // parallel/secondary write MaterialActivity ignores. Runs exactly once, ever.
        if (!ShizukuSettings.hasMigratedToAppCompatLocales()) {
            val tag = ShizukuSettings.getRawLanguageTag() // KEY_LANGUAGE pref, may be null/"SYSTEM"
            if (!tag.isNullOrEmpty() && tag != "SYSTEM") {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
            ShizukuSettings.setMigratedToAppCompatLocales()
        }

        // One-time sync so the "Start on boot" toggle (Settings > Behavior) reflects reality from
        // the first time anyone looks at it - see syncStartOnBootDefaultIfNeeded()'s doc comment.
        ShizukuSettings.syncStartOnBootDefaultIfNeeded(this)
        // Before any activity themes itself: Rounded edges / card corner styles -> Corner rounding.
        ShizukuSettings.migrateCornerSettingsIfNeeded()
        // Before the first Plus feature sync sends the firewall state to the server.
        ShizukuSettings.migrateFirewallAutomationIfNeeded()
        af.shizuku.manager.utils.ShizukuIssues
            .deleteRetiredChannels(this)

        AppCompatDelegate.setDefaultNightMode(ShizukuSettings.getNightMode())

        // Initialize Starter with context
        af.shizuku.manager.starter.Starter
            .initialize(this)

        if (ShizukuSettings.getWatchdog()) {
            WatchdogService.start(this)
            val userManagerWatchdog = getSystemService(Context.USER_SERVICE) as? UserManager
            if (userManagerWatchdog == null || userManagerWatchdog.isUserUnlocked) {
                try {
                    af.shizuku.manager.worker.WatchdogWorker
                        .schedule(this)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to schedule WatchdogWorker in direct boot")
                }
                af.shizuku.manager.receiver.WatchdogAlarmReceiver
                    .schedule(this)
            }
        }

        // AutomationService (#435) now runs real rules - NetworkFirewallRule (trusted-network ->
        // Binder Firewall) and AppAutoHideRule (per-app ShadowBinder auto-hide) - but only if the
        // user has actually configured a trusted network or an auto-hide package in Settings.
        // It used to start unconditionally for every user regardless, showing a permanent "Network
        // monitor" notification and running a battery-costing 2-second UsageStatsManager
        // foreground-app poll for two rules that, at the time, were non-functional placeholders
        // that could never match real data. Registering rules is cheap and always safe; actually
        // starting the service (and its notification/polling) is gated on real configuration so a
        // user who never touches either list sees nothing. Editing the lists later starts or stops
        // it (TrustedNetworksSheet, the auto-hide picker).
        af.shizuku.manager.automation
            .registerDefaultRules()
        if (ShizukuSettings.isAnyAutomationConfigured()) {
            af.shizuku.manager.automation.AutomationService
                .startIfNeeded(this)
        }

        Shizuku.addLogListener { appName, packageName, action ->
            val resolvedName =
                if (appName.isBlank() && packageName.isNotBlank()) {
                    try {
                        val pm = packageManager
                        val ai = pm.getApplicationInfo(packageName, 0)
                        pm.getApplicationLabel(ai).toString()
                    } catch (_: Exception) {
                        packageName
                    }
                } else {
                    appName
                }
            ActivityLogManager.log(resolvedName, packageName, action)
        }

        Shizuku.addSentryEventListener { eventJson ->
            try {
                val jsonObject = org.json.JSONObject(eventJson)
                val levelStr = jsonObject.optString("level", "error")
                val tag = jsonObject.optString("tag", "ShizukuPlus")
                // Server records can carry other apps' command lines, package names, paths and
                // tokens; only a redacted form reaches crash reports.
                val message = redactServerEventMessage(jsonObject.optString("message", ""))

                // Guard against a single device's hot-looping error from draining the shared
                // quota by itself: skip re-sending the exact same tag+message within the
                // dedup window instead of forwarding every single repeated occurrence.
                if (!af.shizuku.manager.utils.SentryEventDeduper
                        .shouldSend(tag, message)
                ) {
                    return@addSentryEventListener
                }

                val sentryLevel =
                    when (levelStr.uppercase()) {
                        "INFO" -> io.sentry.SentryLevel.INFO
                        "WARN", "WARNING" -> io.sentry.SentryLevel.WARNING
                        "ERROR" -> io.sentry.SentryLevel.ERROR
                        "FATAL" -> io.sentry.SentryLevel.FATAL
                        "DEBUG" -> io.sentry.SentryLevel.DEBUG
                        else -> io.sentry.SentryLevel.ERROR
                    }

                io.sentry.Sentry.withScope { scope ->
                    scope.setTag("server_side", "true")
                    scope.setTag("server_tag", tag)
                    val event = io.sentry.SentryEvent()
                    event.level = sentryLevel
                    val sentryMessage = io.sentry.protocol.Message()
                    sentryMessage.formatted = "[$tag] $message"
                    event.message = sentryMessage

                    io.sentry.Sentry.captureEvent(event)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse and dispatch server Sentry event: $eventJson")
            }
        }

        val userManager = getSystemService(Context.USER_SERVICE) as? UserManager
        if (userManager == null || userManager.isUserUnlocked) {
            try {
                RemoteDbSyncWorker.schedule(this)
            } catch (e: Exception) {
                Timber.e(e, "Failed to schedule RemoteDbSyncWorker")
            }
        } else {
            Timber.w("Direct Boot mode: skipping RemoteDbSyncWorker scheduling")
        }
    }

    override fun onCreate() {
        ThemeDelegateManager.setDelegate(ThemeDelegateImpl())
        super.onCreate()

        // First, before the start-up code below writes any of the keys an earlier build leaves
        // behind (the seen version, the one-time migrations) and before anything reads the
        // Watchdog setting: tells a new install (Watchdog on at its first server start) from an
        // update (keeps the off an absent setting meant). See decideWatchdogDefaultIfNeeded().
        ShizukuSettings.decideWatchdogDefaultIfNeeded(this)
        ShizukuSettings.decideLegacyRishKeepaliveDefaultIfNeeded(this)
        ShizukuSettings.decideSecurityDefaultsIfNeeded(this)
        ShizukuSettings.clearSeededCustomAppNameOnce()

        // 0. Initialize Timber
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Prewarm root check on a background thread early to avoid main thread delays/ANRs
        af.shizuku.manager.utils.EnvironmentUtils
            .prewarmAsync()

        // Network-aware TCP protection (#518): registers a callback only when enabled.
        af.shizuku.manager.utils.TcpNetworkGuard
            .sync(this)

        // Optional floating navigation bar (#498); attaches itself to top-level screens when enabled.
        af.shizuku.manager.widget.FloatingNavBar
            .install(this)

        // Retry the startup connection when Wi-Fi returns (opt-in via start on boot).
        af.shizuku.manager.utils.ReconnectTrigger
            .install(this)

        // 1. Run security check
        if (af.shizuku.manager.security.SecurityGuard
                .isTampered()
        ) {
            Timber.e("Security violation: Environment tampered!")
            // Optionally: crash or notify user
        }

        // Clear the Sentry quota flag only when the app version advances, not on every cold start.
        // Resetting unconditionally would silently re-enable Sentry for a user who hit the quota.
        val currentCode =
            try {
                packageManager.getPackageInfo(packageName, 0).versionCode
            } catch (_: Exception) {
                0
            }
        if (currentCode > ShizukuSettings.getLastSeenVersion()) {
            ShizukuSettings.setSentryLimitReached(false)
            ShizukuSettings.setLastSeenVersion(currentCode)
        }

        // 2. Initialize Sentry FIRST to catch all crashes including early startup failures
        initializeSentryEarly()

        // 2. Register persistent crash handler
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(
            af.shizuku.manager.utils
                .CrashHandler(this, defaultHandler),
        )

        // 3. Initialize Mavericks and Koin
        Mavericks.initialize(this)
        startKoin {
            if (BuildConfig.DEBUG) androidLogger()
            androidContext(this@ShizukuApplication)
            modules(appModule)
        }

        // 2. Initialize static components FIRST to ensure HiddenApiBypass is active
        try {
            initializeStatics()
        } catch (e: Throwable) {
            Timber.e(e, "Failed to initialize static components")
            if (e is Error) throw e
        }

        // 3. Add breadcrumb for start
        Sentry.addBreadcrumb(Breadcrumb("App started: ${BuildConfig.VERSION_NAME}"))

        // 4. Strict mode for debugging (DEBUG only)
        // Note: penaltyFlashScreen() intentionally removed to prevent red screen flashing
        // during main-thread disk reads / preference inflation.
        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy
                    .Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
            android.os.StrictMode.setVmPolicy(
                android.os.StrictMode.VmPolicy
                    .Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
        }

        registerIconCacheMaintenance()

        // 5. Initialize settings and managers
        try {
            initializeManagers()
            initPeerSettingsSync()
            if (ShizukuSettings.isAdbProxyEnabled()) {
                startService(Intent(this, af.shizuku.manager.service.AdbProxyService::class.java))
            }
        } catch (e: Throwable) {
            Timber.e(e, "Failed to initialize managers")
            Sentry.captureException(e)
            if (e is Error) throw e
        }

        // 6. Update state machine
        try {
            ShizukuStateMachine.update()
        } catch (e: Exception) {
            Timber.e(e, "Failed to update state machine")
            Sentry.captureException(e)
        }

        Timber.d("Shizuku+ ${BuildConfig.VERSION_NAME} initialization complete")
        Sentry.addBreadcrumb(Breadcrumb("App initialization complete"))
    }

    private fun initPeerSettingsSync() {
        val prefs = ShizukuSettings.getPreferences() ?: return
        peerSyncPrefsListener =
            SharedPreferences
                .OnSharedPreferenceChangeListener { _, key ->
                    if (key != null &&
                        key != ShizukuSettings.Keys.KEY_SETTINGS_LAST_MODIFIED &&
                        key != ShizukuSettings.Keys.KEY_PEER_INITIAL_IMPORT_DONE &&
                        key != ShizukuSettings.Keys.KEY_AUTO_SYNC_PEER_SETTINGS
                    ) {
                        af.shizuku.manager.settings.SettingsShareManager
                            .markSettingsModified()
                        af.shizuku.manager.utils.SettingsBackupManager
                            .requestAutoBackup(this@ShizukuApplication)
                        if (af.shizuku.manager.settings.SettingsShareManager
                                .isAutoSyncEnabled() &&
                            af.shizuku.manager.settings.SettingsShareManager
                                .isPeerInstalled(this)
                        ) {
                            appScope.launch {
                                af.shizuku.manager.settings.SettingsShareManager
                                    .exportToPeer(this@ShizukuApplication)
                            }
                        }
                    }
                }.also { prefs.registerOnSharedPreferenceChangeListener(it) }

        // On startup: check for initial import or auto-sync and persist current settings backup
        appScope.launch {
            try {
                // Past onboarding this install has its own settings: keep its reinstall copy
                // current even if no screen is opened, and once remove the copies older builds
                // left in app storage and public Downloads (they held API keys).
                if (ShizukuSettings.isOnboardingSeen() &&
                    !af.shizuku.manager.utils.SettingsBackupManager
                        .isRestoreChecked()
                ) {
                    af.shizuku.manager.utils.SettingsBackupManager
                        .markRestoreChecked()
                    af.shizuku.manager.update.UpdateInstaller
                        .deleteAllBackupCopies(this@ShizukuApplication)
                }
                af.shizuku.manager.utils.SettingsBackupManager
                    .saveAutoBackup(this@ShizukuApplication)
                af.shizuku.manager.settings.SettingsShareManager
                    .checkAndPerformAutoSync(this@ShizukuApplication)
                // Downloaded update APKs were never removed after installing (~16 MB each).
                af.shizuku.manager.update
                    .UpdateManager(this@ShizukuApplication)
                    .cleanup()
            } catch (e: Exception) {
                Timber.tag("ShizukuApplication").w(e, "Peer settings initial auto-sync or backup failed")
            }
        }
    }
}

/**
 * What of a server log record may go into a crash report: the description before the first ": ",
 * then the details with package names, paths, key=value pairs and bearer tokens replaced, cut to
 * a short length. The server logs blocked commands, caller packages and binder descriptors at
 * WARN and above, and those were forwarded verbatim.
 */
internal fun redactServerEventMessage(message: String): String {
    val split = message.indexOf(": ")
    val head = if (split >= 0) message.substring(0, split) else message
    val tail = if (split >= 0) message.substring(split + 2) else ""

    fun redact(text: String) =
        text
            .replace(Regex("""(?i)bearer\s+\S+"""), "<token>")
            .replace(Regex("""\b[\w.-]+=\S+"""), "<redacted>")
            .replace(Regex("""(?<![\w])/[^\s,;)]+"""), "<path>")
            .replace(Regex("""\b[a-zA-Z][\w]*(\.[a-zA-Z_][\w]*){2,}\b"""), "<pkg>")
    val details = redact(tail).take(80)
    val redactedHead = redact(head).take(120)
    return if (details.isEmpty()) redactedHead else "$redactedHead: $details"
}
