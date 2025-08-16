package com.fadcam.ui;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.fadcam.R;

/**
 * Hosts the original monolithic SettingsFragment so new home can deep link
 * without altering existing logic. Temporary bridge during refactor.
 */
public class LegacySettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_legacy_settings_holder);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.legacy_settings_container, new SettingsFragment())
                    .commit();
        }
    }
}
