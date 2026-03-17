package moe.shizuku.manager

import android.content.Intent
import android.os.Bundle
import moe.shizuku.manager.home.HomeActivity
import moe.shizuku.manager.onboarding.OnboardingActivity

class MainActivity : HomeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (!ShizukuSettings.hasSeenOnboarding()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        super.onCreate(savedInstanceState)
    }
}
