package moe.shizuku.manager

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.topjohnwu.superuser.Shell
import io.sentry.android.core.SentryAndroid
import moe.shizuku.manager.ktx.logd
import moe.shizuku.manager.service.WatchdogService
import moe.shizuku.manager.utils.ActivityLogManager
import moe.shizuku.manager.utils.ShizukuStateMachine
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.core.util.BuildUtils.atLeast30
import rikka.material.app.LocaleDelegate
import rikka.shizuku.Shizuku

lateinit var application: ShizukuApplication

class ShizukuApplication : Application() {

    companion object {

        init {
            logd("ShizukuApplication", "init")

            Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_REDIRECT_STDERR))
            if (Build.VERSION.SDK_INT >= 28) {
                HiddenApiBypass.setHiddenApiExemptions("")
            }
            if (atLeast30) {
                System.loadLibrary("adb")
            }
        }

        lateinit var appContext: Context
            private set

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

        if (BuildConfig.SENTRY_DSN.isNotEmpty()) {
            SentryAndroid.init(this) { options ->
                options.dsn = BuildConfig.SENTRY_DSN
                // Enable rich crash/glitch reporting
                options.isAttachScreenshot = true
                options.isAttachViewHierarchy = true
                options.isAnrEnabled = true
                options.tracesSampleRate = 1.0
                
                options.release = "shizuku-plus@${BuildConfig.VERSION_NAME}"
                options.environment = if (BuildConfig.DEBUG) "development" else "production"
            }
        }
        init(this)
    }

}
