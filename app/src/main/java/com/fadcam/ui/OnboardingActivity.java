package com.fadcam.ui;

import android.os.Bundle;
import androidx.annotation.Nullable;

import com.fadcam.SharedPreferencesManager;
import com.github.appintro.AppIntro;
import com.github.appintro.AppIntroFragment;
import com.fadcam.R;
import androidx.core.content.ContextCompat;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * OnboardingActivity shows the app intro slides using AppIntro.
 */
public class OnboardingActivity extends AppIntro {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addSlide(AppIntroFragment.createInstance(
            "Welcome to FadCam!",
            "This is your privacy-first dashcam app. Swipe to continue.",
            R.mipmap.ic_launcher,
            R.color.amoled_background
        ));
    }

    @Override
    public void onDonePressed(@Nullable androidx.fragment.app.Fragment currentFragment) {
        // Mark onboarding as completed
        SharedPreferencesManager.getInstance(this)
            .sharedPreferences.edit().putBoolean("PREF_SHOW_ONBOARDING", false).apply();

        // Launch MainActivity
        Intent intent = new Intent(this, com.fadcam.MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);

        super.onDonePressed(currentFragment);
        finish();
    }
} 