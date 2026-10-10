package com.fadcam.ui;

import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.fadcam.R;
import com.fadcam.ui.widgets.ProBundleShowcaseView;

/**
 * Fullscreen inspector for the Pro bundle phone: the whole device, uncropped, with every
 * mock interaction live (rotate, lock screen, shade, volume, app previews).
 */
public class ProBundleFullscreenActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pro_bundle_fullscreen);

        ProBundleShowcaseView showcase = findViewById(R.id.fullscreenShowcase);
        if (showcase != null) {
            showcase.configure(getString(R.string.pro_showcase_label),
                    getString(R.string.pro_screen_bundle), null, null);
            showcase.setPhoneOnlyMode(true);
            float density = getResources().getDisplayMetrics().density;
            float widthDp = getResources().getDisplayMetrics().widthPixels / density;
            showcase.setPhoneSize(Math.min(340f, widthDp * 0.80f), true);
        }
        View close = findViewById(R.id.fullscreenClose);
        if (close != null) close.setOnClickListener(v -> finish());
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
