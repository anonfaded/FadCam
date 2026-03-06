package com.fadcam;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import com.fadcam.ui.WatchMainActivity;
import com.fadcam.utils.RuntimeCompat;

/**
 * Simple splash screen activity showing the FadSecLab flag centered.
 * Uses a short delay, then routes to the appropriate main activity:
 * - Wear OS: {@link WatchMainActivity} (minimal watch UI)
 * - Phone/tablet: {@link MainActivity}
 */
public class SplashActivity extends Activity {
    private static final long SPLASH_DELAY_MS = 800; // short, just to show logo

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        ImageView iv = findViewById(R.id.splash_image);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Class<?> destination = RuntimeCompat.isWatchDevice(SplashActivity.this)
                    ? WatchMainActivity.class
                    : MainActivity.class;
            startActivity(new Intent(SplashActivity.this, destination));
            // Apply fade transition instead of default activity animation
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
            finish();
        }, SPLASH_DELAY_MS);
    }
}
