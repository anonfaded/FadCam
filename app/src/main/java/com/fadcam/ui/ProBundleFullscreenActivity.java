package com.fadcam.ui;

import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.fadcam.R;
import com.fadcam.ui.widgets.ProBundleShowcaseView;

/**
 * Fullscreen inspector for the Pro bundle phone: the whole device, uncropped, with every
 * mock interaction live (rotate, lock screen, shade, volume, app previews).
 */
public class ProBundleFullscreenActivity extends AppCompatActivity {

    private float appliedPhoneWidthDp = -1f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN,
                    android.R.anim.fade_in, android.R.anim.fade_out);
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setBackgroundDrawable(new ColorDrawable(0xFF0B0B0D));
        setContentView(R.layout.activity_pro_bundle_fullscreen);

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());

        ProBundleShowcaseView showcase = findViewById(R.id.fullscreenShowcase);
        showcase.setBootEnabled(false);
        showcase.setFullscreenQuality(true);
        showcase.configure(getString(R.string.pro_showcase_label),
                getString(R.string.pro_screen_bundle), null, null);
        showcase.setPhoneOnlyMode(true);

        View close = findViewById(R.id.fullscreenClose);
        close.setOnClickListener(v -> finish());

        float density = getResources().getDisplayMetrics().density;
        int margin = Math.round(12f * density);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.fullscreenRoot), (root, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) close.getLayoutParams();
            lp.setMargins(bars.left + margin, bars.top + margin, bars.right + margin, bars.bottom + margin);
            close.setLayoutParams(lp);

            float screenWidthDp = getResources().getDisplayMetrics().widthPixels / density;
            float screenHeightDp = getResources().getDisplayMetrics().heightPixels / density;
            float availableHeightDp = screenHeightDp - (bars.top + bars.bottom) / density - 48f;
            float phoneWidthDp = Math.min(0.80f * screenWidthDp, availableHeightDp / 2.05f);
            if (phoneWidthDp != appliedPhoneWidthDp) {
                appliedPhoneWidthDp = phoneWidthDp;
                showcase.setPhoneSize(phoneWidthDp, true);
            }
            return windowInsets;
        });
    }

    @Override
    public void finish() {
        super.finish();
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE,
                    android.R.anim.fade_in, android.R.anim.fade_out);
        } else {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }
    }
}
