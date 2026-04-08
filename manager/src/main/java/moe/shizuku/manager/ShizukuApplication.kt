package moe.shizuku.manager

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.Configuration
import com.topjohnwu.superuser.Shell
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import io.sentry.Breadcrumb
import moe.shizuku.manager.service.WatchdogService
import moe.shizuku.manager.utils.ActivityLogManager
import moe.shizuku.manager.utils.ShizukuStateMachine
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.core.util.BuildUtils.atLeast30
import rikka.material.app.LocaleDelegate
import rikka.shizuku.Shizuku

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
        private const val TAG = "ShizukuApplication"
        
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
            Log.w(TAG, "Sentry DSN is empty, skipping initialization")
            return
        }

        try {
            SentryAndroid.init(this) { options ->
                options.dsn = BuildConfig.SENTRY_DSN
                
                // Attach visual context for better debugging
                options.isAttachScreenshot = true
                options.isAttachViewHierarchy = true
                
                // ANR detection
                options.isAnrEnabled = true
                options.anrTimeoutIntervalMillis = 5000L
                
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
                options.setBeforeSend { event, hint ->
                    // Add build config info to events
                    event.setTag("version_name", BuildConfig.VERSION_NAME)
                    event.setTag("version_code", BuildConfig.VERSION_CODE.toString())
                    event.setTag("build_type", if (BuildConfig.DEBUG) "debug" else "release")
                    event
                }
            }
            
            // Set user context (anonymous, for crash grouping)
            Sentry.setUser(null) // Anonymous user
            Sentry.setTag("app_variant", BuildConfig.VERSION_NAME)
            
            Log.d(TAG, "Sentry initialized with release tracking")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Sentry early", e)
            // Don't throw - allow app to continue even if Sentry fails
        }
    }

    /**
     * Initialize static components (native libraries, etc.)
     */
    private fun initializeStatics() {
        Log.d(TAG, "Initializing static components")

        Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_REDIRECT_STDERR))
        
        if (Build.VERSION.SDK_INT >= 28) {
            HiddenApiBypass.setHiddenApiExemptions("")
        }
        
        if (atLeast30) {
            System.loadLibrary("adb")
            Log.d(TAG, "Native library 'adb' loaded successfully")
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
        moe.shizuku.manager.starter.Starter.initialize(this)

        if (ShizukuSettings.getWatchdog()) {
            WatchdogService.start(this)
        }

        Shizuku.addLogListener { appName, packageName, action ->
            ActivityLogManager.log(appName, packageName, action)
        }
    }

    override fun onCreate() {
        super.onCreate()

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
            Log.e(TAG, "Failed to initialize static components", e)
            Sentry.captureException(e)
            if (e is Error) throw e
        }

        // 4. Initialize settings and managers
        try {
            initializeManagers()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize managers", e)
            Sentry.captureException(e)
            if (e is Error) throw e
        }

        // 5. Update state machine
        try {
            ShizukuStateMachine.update()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update state machine", e)
            Sentry.captureException(e)
        }

        Log.d(TAG, "Shizuku+ ${BuildConfig.VERSION_NAME} initialization complete")
        Sentry.addBreadcrumb(Breadcrumb("App initialization complete"))
    }
}
