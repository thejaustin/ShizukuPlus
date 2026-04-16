package af.shizuku.manager

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.Configuration
import com.topjohnwu.superuser.Shell
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import io.sentry.android.timber.SentryTimberTree
import io.sentry.Breadcrumb
import af.shizuku.manager.service.WatchdogService
import af.shizuku.manager.utils.ActivityLogManager
import af.shizuku.manager.utils.ShizukuStateMachine
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.core.util.BuildUtils.atLeast30
import rikka.material.app.LocaleDelegate
import rikka.shizuku.Shizuku
import timber.log.Timber
import af.shizuku.manager.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

/**
 * Shizuku+ Application class
 * 
 * Initialization order:
 * 1. Sentry (for crash reporting)
 * 2. Static components (native libraries)
 * 3. Settings and managers
 * 4. State machine
 */
class ShizukuApplication : Application(), Configuration.Provider {

    companion object {
        lateinit var appContext: Context
            private set
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
            .build()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        appContext = base
        // Initialize ShizukuSettings as early as possible
        ShizukuSettings.initialize(base)
    }

    /**
     * Initialize Sentry FIRST to catch all crashes including early startup failures
     */
    private fun initializeSentryEarly() {
        if (BuildConfig.SENTRY_DSN.isEmpty()) {
            Timber.w("Sentry DSN is empty, skipping initialization")
            return
        }

        try {
            SentryAndroid.init(this) { options ->
                options.dsn = BuildConfig.SENTRY_DSN
                
                // Attach visual context for better debugging
                options.isAttachScreenshot = true
                options.isAttachViewHierarchy = true
                
                // User interaction and lifecycle tracing
                options.isEnableUserInteractionTracing = true
                options.isEnableUserInteractionBreadcrumbs = true
                options.isEnableAutoActivityLifecycleTracing = true
                
                // ANR detection — 10s threshold avoids false positives from privileged
                // system calls (__epoll_pwait / __ioctl) in the event loop
                options.isAnrEnabled = true
                options.anrTimeoutIntervalMillis = 10000L
                
                // Performance monitoring (sampled)
                options.tracesSampleRate = 0.2 // 20% sampling for production
                options.profilesSampleRate = 0.1 // 10% profiling
                
                // Release tracking with GitHub integration
                options.release = "shizuku-plus@${BuildConfig.VERSION_NAME}"
                options.environment = if (BuildConfig.DEBUG) "development" else "production"
                options.dist = "${BuildConfig.VERSION_CODE}"
                
                // Session tracking for crash-free rate
                options.isEnableAutoSessionTracking = true
                options.sessionTrackingIntervalMillis = 30000L
                
                // Include breadcrumbs for navigation tracking
                options.maxBreadcrumbs = 100
                
                // Send default PII (for device info, not user data)
                options.isSendDefaultPii = false
                
                // Enable NDK crash reporting
                options.isEnableNdk = true
                
                // Add context about the app
                options.setBeforeSend { event, _ ->
                    // Drop coroutine cancellations — JobCancellationException and all
                    // CancellationException subclasses are expected lifecycle behaviour
                    val throwable = event.throwable
                    if (throwable is kotlinx.coroutines.CancellationException) return@setBeforeSend null

                    // Add build config info to events
                    event.setTag("version_name", BuildConfig.VERSION_NAME)
                    event.setTag("version_code", BuildConfig.VERSION_CODE.toString())
                    event.setTag("build_type", if (BuildConfig.DEBUG) "debug" else "release")
                    
                    // Add system state at time of crash
                    try {
                        event.setTag("shizuku_state", if (Shizuku.pingBinder()) "connected" else "disconnected")
                        event.setTag("is_rooted", Shell.isAppGrantedRoot().toString())
                    } catch (e: Exception) {
                        // Ignore errors during state collection
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
                Timber.plant(io.sentry.android.timber.SentryTimberTree(
                    io.sentry.HubAdapter.getInstance(),
                    io.sentry.SentryLevel.ERROR,
                    io.sentry.SentryLevel.INFO
                ))
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

        Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_REDIRECT_STDERR))
        
        if (Build.VERSION.SDK_INT >= 28) {
            HiddenApiBypass.setHiddenApiExemptions("")
        }
        
        if (atLeast30) {
            try {
                System.loadLibrary("adb")
                Timber.d("Native library 'adb' loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                // Capture to Sentry before the process aborts so we have a trace.
                // Common cause: JNI class path mismatch after a package rename.
                Sentry.captureException(RuntimeException("libadb.so failed to load: ${e.message}", e))
                throw e
            }
        }
    }

    /**
     * Initialize settings and managers
     */
    private fun initializeManagers() {
        ActivityLogManager.initialize(this)
        LocaleDelegate.defaultLocale = ShizukuSettings.getLocale()
        AppCompatDelegate.setDefaultNightMode(ShizukuSettings.getNightMode())
        
        // Initialize Starter with context
        af.shizuku.manager.starter.Starter.initialize(this)

        if (ShizukuSettings.getWatchdog()) {
            WatchdogService.start(this)
        }

        Shizuku.addLogListener { appName, packageName, action ->
            ActivityLogManager.log(appName, packageName, action)
        }
    }

    override fun onCreate() {
        super.onCreate()

        // 0. Initialize Timber
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // 0.1 Initialize Koin
        startKoin {
            if (BuildConfig.DEBUG) androidLogger()
            androidContext(this@ShizukuApplication)
            modules(appModule)
        }

        // 1. CRITICAL: Initialize Sentry FIRST
        initializeSentryEarly()
        Sentry.addBreadcrumb(Breadcrumb("App started: ${BuildConfig.VERSION_NAME}"))

        // 2. Strict mode for debugging (DEBUG only)
        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            android.os.StrictMode.setVmPolicy(
                android.os.StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }

        // 3. Initialize static components
        try {
            initializeStatics()
        } catch (e: Throwable) {
            Timber.e(e, "Failed to initialize static components")
            Sentry.captureException(e)
            if (e is Error) throw e
        }

        // 4. Initialize settings and managers
        try {
            initializeManagers()
        } catch (e: Throwable) {
            Timber.e(e, "Failed to initialize managers")
            Sentry.captureException(e)
            if (e is Error) throw e
        }

        // 5. Update state machine
        try {
            ShizukuStateMachine.update()
        } catch (e: Exception) {
            Timber.e(e, "Failed to update state machine")
            Sentry.captureException(e)
        }

        Timber.d("Shizuku+ ${BuildConfig.VERSION_NAME} initialization complete")
        Sentry.addBreadcrumb(Breadcrumb("App initialization complete"))
    }
}
