package moe.shizuku.manager

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import io.sentry.Breadcrumb
import io.sentry.Sentry
import moe.shizuku.manager.home.HomeActivity
import moe.shizuku.manager.onboarding.OnboardingActivity

class MainActivity : HomeActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            installSplashScreen()
            Log.d(TAG, "MainActivity onCreate starting")
            Sentry.addBreadcrumb(Breadcrumb("MainActivity onCreate starting"))
            
            super.onCreate(savedInstanceState)

            if (!ShizukuSettings.hasSeenOnboarding()) {
                Log.d(TAG, "Showing onboarding")
                Sentry.addBreadcrumb(Breadcrumb("Showing onboarding"))
                startActivity(Intent(this, OnboardingActivity::class.java))
                finish()
                return
            }
            
            Log.d(TAG, "MainActivity onCreate complete")
        } catch (e: Exception) {
            Log.e(TAG, "Crash in MainActivity.onCreate", e)
            Sentry.captureException(e)
            Sentry.addBreadcrumb(Breadcrumb("MainActivity crash: ${e.message}"))
            throw e
        }
    }
}
