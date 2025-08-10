package com.fadcam;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

/**
 * Simple splash screen activity showing the FadSecLab flag centered.
 * Uses a short delay, then launches MainActivity.
 */
public class SplashActivity extends Activity {
    private static final long SPLASH_DELAY_MS = 800; // short, just to show logo

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        ImageView iv = findViewById(R.id.splash_image);
        // (Optional future: animate alpha or scale)
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            startActivity(new Intent(SplashActivity.this, MainActivity.class));
            // Apply fade transition instead of default activity animation
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
            finish();
        }, SPLASH_DELAY_MS);
    }
}
