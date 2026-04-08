package moe.shizuku.manager

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import io.sentry.Breadcrumb
import io.sentry.Sentry
import moe.shizuku.manager.home.HomeActivity
import moe.shizuku.manager.onboarding.OnboardingActivity
import moe.shizuku.manager.utils.ShizukuStateMachine

class MainActivity : HomeActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            Log.d(TAG, "Calling super.onCreate")
            Sentry.addBreadcrumb(Breadcrumb("Calling super.onCreate"))
            super.onCreate(savedInstanceState)

            Log.d(TAG, "Checking onboarding status")
            Sentry.addBreadcrumb(Breadcrumb("Checking onboarding status"))
            
            if (!ShizukuSettings.hasSeenOnboarding()) {
                Log.d(TAG, "Showing onboarding")
                Sentry.addBreadcrumb(Breadcrumb("Showing onboarding"))
                startActivity(Intent(this, OnboardingActivity::class.java))
                finish()
                return
            }

            Log.d(TAG, "MainActivity onCreate complete")
            Sentry.addBreadcrumb(Breadcrumb("MainActivity onCreate complete"))
        } catch (e: Exception) {
            Log.e(TAG, "Crash in MainActivity.onCreate", e)
            Sentry.captureException(e)
            Sentry.addBreadcrumb(Breadcrumb("MainActivity crash: ${e.message}"))
            
            // Try to recover by starting HomeActivity directly
            try {
                startActivity(Intent(this, HomeActivity::class.java))
                finish()
            } catch (e2: Exception) {
                Log.e(TAG, "Recovery also failed", e2)
            }
            
            throw e
        }
    }
    
    override fun onStart() {
        try {
            super.onStart()
            // Update state machine on app start
            ShizukuStateMachine.update()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStart", e)
            Sentry.captureException(e)
            throw e
        }
    }
}
