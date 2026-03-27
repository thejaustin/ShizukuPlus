package moe.shizuku.manager

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.Configuration
import com.topjohnwu.superuser.Shell
import io.sentry.android.core.SentryAndroid
import moe.shizuku.manager.service.WatchdogService
import moe.shizuku.manager.utils.ActivityLogManager
import moe.shizuku.manager.utils.ShizukuStateMachine
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.core.util.BuildUtils.atLeast30
import rikka.material.app.LocaleDelegate
import rikka.shizuku.Shizuku

lateinit var application: ShizukuApplication

class ShizukuApplication : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
            .build()

    companion object {
        private const val TAG = "ShizukuApplication"
        lateinit var appContext: Context
            private set

        fun initializeStatics() {
            Log.d(TAG, "Initializing static components")

            try {
                Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_REDIRECT_STDERR))
                if (Build.VERSION.SDK_INT >= 28) {
                    HiddenApiBypass.setHiddenApiExemptions("")
                }
                if (atLeast30) {
                    System.loadLibrary("adb")
                    Log.d(TAG, "Native library 'adb' loaded successfully")
                }
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error in static initializer", e)
                throw e
            }
        }
    }

    private fun init(context: Context) {
        ShizukuSettings.initialize(context)
        ActivityLogManager.initialize(context)
        LocaleDelegate.defaultLocale = ShizukuSettings.getLocale()
        AppCompatDelegate.setDefaultNightMode(ShizukuSettings.getNightMode())

        if(ShizukuSettings.getWatchdog()) WatchdogService.start(context)

        Shizuku.addLogListener { appName, packageName, action ->
            ActivityLogManager.log(appName, packageName, action)
        }
    }

    override fun onCreate() {
        super.onCreate()
        application = this
        appContext = applicationContext

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

        // Initialize static components FIRST (native libraries, etc.)
        try {
            initializeStatics()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize static components", e)
            throw e
        }

        // Initialize settings and managers BEFORE Sentry
        // This ensures all components are ready before any crash reporting
        try {
            init(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ShizukuApplication", e)
            // Re-throw to ensure we don't continue in a broken state
            throw e
        }

        // Initialize Sentry AFTER all components are ready
        if (BuildConfig.SENTRY_DSN.isNotEmpty()) {
            try {
                SentryAndroid.init(this) { options ->
                    options.dsn = BuildConfig.SENTRY_DSN
                    // Enable rich crash/glitch reporting
                    options.isAttachScreenshot = true
                    options.isAttachViewHierarchy = true
                    options.isAnrEnabled = true
                    options.tracesSampleRate = 1.0

                    options.release = "shizuku-plus@${BuildConfig.VERSION_NAME}"
                    options.environment = if (BuildConfig.DEBUG) "development" else "production"
                    
                    // Add breadcrumbs for better debugging
                    options.isEnableAutoSessionTracking = true
                    options.sessionTrackingIntervalMillis = 30000L
                }
                Log.d(TAG, "Sentry initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Sentry", e)
                // Don't crash the app if Sentry fails
            }
        }
        
        // Update state machine after everything is initialized
        try {
            ShizukuStateMachine.update()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update ShizukuStateMachine", e)
        }
    }

}
