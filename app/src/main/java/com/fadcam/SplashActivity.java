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
 * Splash router.
 * Debug builds enter the new professional Studio dashboard first so the UI can
 * be verified independently. Release builds keep the existing MainActivity flow.
 */
public class SplashActivity extends Activity {
    private static final long SPLASH_DELAY_MS = 800;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        ImageView iv = findViewById(R.id.splash_image);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            final Class<?> destination;
            if (BuildConfig.DEBUG && !RuntimeCompat.shouldUseWatchUi(SplashActivity.this)) {
                destination = StudioActivity.class;
            } else {
                destination = RuntimeCompat.shouldUseWatchUi(SplashActivity.this)
                        ? WatchMainActivity.class
                        : MainActivity.class;
            }

            startActivity(new Intent(SplashActivity.this, destination));
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
            finish();
        }, SPLASH_DELAY_MS);
    }
}
