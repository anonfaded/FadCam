package com.fadcam;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.viewpager2.widget.ViewPager2;

import com.fadcam.ui.ViewPagerAdapter;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private BottomNavigationView bottomNavigationView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Apply saved theme on startup
        SharedPreferencesManager sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
        String savedTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, "Dark Mode");
        
        Log.d("MainActivity", "Saved theme: " + savedTheme);
        
        // Always force dark mode
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        
        // Only set AMOLED theme if explicitly selected
        if ("AMOLED Black".equals(savedTheme)) {
            Log.d("MainActivity", "Setting AMOLED theme");
            getTheme().applyStyle(R.style.Theme_FadCam_Amoled, true);
        } else {
            Log.d("MainActivity", "Using default dark theme");
        }
        
        // Load and apply the saved language preference before anything else
        SharedPreferences prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        String savedLanguageCode = prefs.getString(Constants.LANGUAGE_KEY, Locale.getDefault().getLanguage());

        applyLanguage(savedLanguageCode);  // Apply the language preference

        // Check if current locale is Pashto
        if (getResources().getConfiguration().locale.getLanguage().equals("ps")) {
            getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        }

        setContentView(R.layout.activity_main);

        viewPager = findViewById(R.id.view_pager);
        bottomNavigationView = findViewById(R.id.bottom_navigation);

        ViewPagerAdapter adapter = new ViewPagerAdapter(this);
        viewPager.setAdapter(adapter);

        // Assuming viewPager is the instance of your ViewPager
        viewPager.setOffscreenPageLimit(4); // Adjust the number based on your requirement

        bottomNavigationView.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.navigation_home) {
                viewPager.setCurrentItem(0);
            } else if (itemId == R.id.navigation_records) {
                viewPager.setCurrentItem(1);
            } else if (itemId == R.id.navigation_remote) {
                viewPager.setCurrentItem(2);
            } else if (itemId == R.id.navigation_settings) {
                viewPager.setCurrentItem(3);
            } else if (itemId == R.id.navigation_about) {
                viewPager.setCurrentItem(4);
            }
            return true;
        });

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                switch (position) {
                    case 0:
                        bottomNavigationView.setSelectedItemId(R.id.navigation_home);
                        break;
                    case 1:
                        bottomNavigationView.setSelectedItemId(R.id.navigation_records);
                        break;
                    case 2:
                        bottomNavigationView.setSelectedItemId(R.id.navigation_remote);
                        break;
                    case 3:
                        bottomNavigationView.setSelectedItemId(R.id.navigation_settings);
                        break;
                    case 4:
                        bottomNavigationView.setSelectedItemId(R.id.navigation_about);
                        break;
                }
            }
        });

        // Add custom badge to the Remote tab in BottomNavigationView
        bottomNavigationView.post(() -> {
            ViewGroup menuView = (ViewGroup) bottomNavigationView.getChildAt(0);
            if (menuView != null && menuView.getChildCount() > 2) {
                View remoteTab = menuView.getChildAt(2); // 0:home, 1:records, 2:remote
                if (remoteTab instanceof ViewGroup) {
                    // Prevent duplicate badge
                    View existingBadge = ((ViewGroup) remoteTab).findViewById(R.id.badge_text);
                    if (existingBadge == null) {
                        View badge = getLayoutInflater().inflate(R.layout.custom_badge, (ViewGroup) remoteTab, false);
                        ((ViewGroup) remoteTab).addView(badge);
                    }
                }
            }
        });

        // This is the path for the osmdroid tile cache
        File osmdroidBasePath = new File(getCacheDir().getAbsolutePath(), "osmdroid");
        File osmdroidTileCache = new File(osmdroidBasePath, "tiles");
        org.osmdroid.config.Configuration.getInstance().setOsmdroidBasePath(osmdroidBasePath);
        org.osmdroid.config.Configuration.getInstance().setOsmdroidTileCache(osmdroidTileCache);

        // Add dynamic shortcut for torch
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            createDynamicShortcuts();
        }
    }

@RequiresApi(api = Build.VERSION_CODES.N_MR1)
private void createDynamicShortcuts() {
    ShortcutManager shortcutManager = getSystemService(ShortcutManager.class);

    // Torch Toggle Shortcut
    Intent torchIntent = new Intent(this, TorchToggleActivity.class);
    torchIntent.setAction(Intent.ACTION_VIEW);

    ShortcutInfo torchShortcut = new ShortcutInfo.Builder(this, "torch_toggle")
        .setShortLabel(getString(R.string.torch_shortcut_short_label))
        .setLongLabel(getString(R.string.torch_shortcut_long_label))
        .setIcon(Icon.createWithResource(this, R.drawable.flashlight_shortcut))
        .setIntent(torchIntent)
        .build();

    // Recording Start Shortcut
    Intent startRecordIntent = new Intent(this, RecordingStartActivity.class);
    startRecordIntent.setAction(Intent.ACTION_VIEW);

    ShortcutInfo startRecordShortcut = new ShortcutInfo.Builder(this, "record_start")
        .setShortLabel(getString(R.string.start_recording))
        .setLongLabel(getString(R.string.start_recording))
        .setIcon(Icon.createWithResource(this, R.drawable.start_shortcut))
        .setIntent(startRecordIntent)
        .build();

    // Recording Stop Shortcut
    Intent stopRecordIntent = new Intent(this, RecordingStopActivity.class);
    stopRecordIntent.setAction(Intent.ACTION_VIEW);

    ShortcutInfo stopRecordShortcut = new ShortcutInfo.Builder(this, "record_stop")
        .setShortLabel(getString(R.string.stop_recording))
        .setLongLabel(getString(R.string.stop_recording))
        .setIcon(Icon.createWithResource(this, R.drawable.stop_shortcut))
        .setIntent(stopRecordIntent)
        .build();

    // Set all shortcuts
    shortcutManager.setDynamicShortcuts(Arrays.asList(
        torchShortcut,
        startRecordShortcut,
        stopRecordShortcut
    ));
}


    public void applyLanguage(String languageCode) {
        // Get current app language
        String currentLanguage = getResources().getConfiguration().locale.getLanguage();

        // Only apply language change if it's different from the current language
        if (!languageCode.equals(currentLanguage)) {
            Log.d("MainActivity", "Applying language: " + languageCode);
            Locale locale = new Locale(languageCode);
            Locale.setDefault(locale);

            android.content.res.Configuration config = new android.content.res.Configuration();
            config.setLocale(locale);
            getApplicationContext().createConfigurationContext(config);

            getResources().updateConfiguration(config, getResources().getDisplayMetrics());

            // Recreate the activity to apply the changes
            recreate();
        } else {
            Log.d("MainActivity", "Language is already set to " + languageCode + "; no need to change.");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }
}