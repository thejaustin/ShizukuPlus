package moe.shizuku.manager;

import android.content.Intent;
import android.os.Bundle;
import moe.shizuku.manager.home.HomeActivity;
import moe.shizuku.manager.onboarding.OnboardingActivity;

public class MainActivity extends HomeActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (!ShizukuSettings.hasSeenOnboarding()) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }
        super.onCreate(savedInstanceState);
    }
}
