package com.fadcam.ui;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.fadcam.R;

/**
 * FadCam Pro promotional activity displaying features and purchase options.
 * Features a premium gradient design with animated elements.
 */
public class FadCamProActivity extends AppCompatActivity {

    private static final String PATREON_SHOP_URL = "https://www.patreon.com/cw/Fadedx/shop";
    
    private ShimmerButton btnGetPro;
    private ImageView btnBack;
    private LinearLayout featuresContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fadcam_pro);
        
        // Handle edge-to-edge display
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootContainer), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initViews();
        setupFeatureRows();
        setupClickListeners();
        startAnimations();
    }

    /**
     * Initialize all view references.
     */
    private void initViews() {
        btnGetPro = findViewById(R.id.btnGetPro);
        btnBack = findViewById(R.id.btnBack);
        featuresContainer = findViewById(R.id.featuresContainer);
    }

    /**
     * Setup feature comparison rows with proper data.
     * Format: {featureName, freeStatus, proStatus, proPlusStatus, labStatus}
     * Status values: "check" for available, "remove" for not available, "schedule" for coming soon
     */
    private void setupFeatureRows() {
        // Feature data: name, free, pro, pro+, lab
        String[][] features = {
            {getString(R.string.fadcam_pro_feature_everything_icon), "check", "check", "check", "remove"},
            {getString(R.string.fadcam_pro_feature_notification_icon), "remove", "check", "check", "remove"},
            {getString(R.string.fadcam_pro_feature_user_icon), "remove", "remove", "schedule", "remove"},
            {getString(R.string.fadcam_pro_feature_app_name), "remove", "remove", "schedule", "remove"},
            {getString(R.string.fadcam_pro_feature_faddrive), "remove", "remove", "remove", "schedule"}
        };

        View[] featureViews = {
            findViewById(R.id.featureRow1),
            findViewById(R.id.featureRow2),
            findViewById(R.id.featureRow3),
            findViewById(R.id.featureRow4),
            findViewById(R.id.featureRow5)
        };

        for (int i = 0; i < features.length && i < featureViews.length; i++) {
            View row = featureViews[i];
            if (row == null) continue;

            TextView tvName = row.findViewById(R.id.tvFeatureName);
            TextView tvFree = row.findViewById(R.id.tvFreeStatus);
            TextView tvPro = row.findViewById(R.id.tvProStatus);
            TextView tvProPlus = row.findViewById(R.id.tvProPlusStatus);
            TextView tvLab = row.findViewById(R.id.tvLabStatus);

            if (tvName != null) tvName.setText(features[i][0]);
            
            // Set Free status
            setStatusIcon(tvFree, features[i][1], 0xFF666666, 0xFF4CAF50, 0xFFFF9800);
            
            // Set Pro status (gold color for check)
            setStatusIcon(tvPro, features[i][2], 0xFF666666, 0xFFFFD700, 0xFFFF9800);
            
            // Set Pro+ status (coral color for check)
            setStatusIcon(tvProPlus, features[i][3], 0xFF666666, 0xFFFF6B6B, 0xFFFF6B6B);
            
            // Set Lab status (cyan color for check)
            setStatusIcon(tvLab, features[i][4], 0xFF666666, 0xFF00BCD4, 0xFF00BCD4);
        }
    }

    /**
     * Set the status icon and color for a feature column.
     */
    private void setStatusIcon(TextView tv, String status, int removeColor, int checkColor, int scheduleColor) {
        if (tv == null) return;
        
        switch (status) {
            case "check":
                tv.setText("check_circle");
                tv.setTextColor(checkColor);
                break;
            case "schedule":
                tv.setText("schedule");
                tv.setTextColor(scheduleColor);
                break;
            case "remove":
            default:
                tv.setText("remove");
                tv.setTextColor(removeColor);
                break;
        }
    }

    /**
     * Setup click listeners for interactive elements.
     */
    private void setupClickListeners() {
        // Back button
        btnBack.setOnClickListener(v -> finish());

        // Get Pro button - opens Patreon shop
        btnGetPro.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(PATREON_SHOP_URL));
            startActivity(intent);
        });
    }

    /**
     * Start all animations for the activity.
     */
    private void startAnimations() {
        // ShimmerButton handles its own animation internally
        
        // Fade in feature rows sequentially
        animateFeatureRows();
    }

    /**
     * Animate feature rows with a staggered fade-in effect.
     */
    private void animateFeatureRows() {
        LinearLayout featuresContainer = findViewById(R.id.featuresContainer);
        if (featuresContainer == null) return;

        for (int i = 0; i < featuresContainer.getChildCount(); i++) {
            View child = featuresContainer.getChildAt(i);
            child.setAlpha(0f);
            child.setTranslationY(20f);
            
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(350)
                .setStartDelay(200 + (i * 80L))
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }
}
