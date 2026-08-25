package com.fadcam.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.fadcam.R;

/**
 * FadCam Pro feature center.
 *
 * <p>All Pro/Pro+/Lab capabilities are intentionally unlocked in this build.
 * The page is a feature/configuration surface rather than a payment gate.</p>
 */
public class FadCamProActivity extends AppCompatActivity {

    private static final String PREFS = "fadcam_pro_features";
    private static final String PREF_CUSTOM_NAME = "custom_app_name";
    private static final String PREF_CUSTOM_ICON = "custom_app_icon_uri";

    private ShimmerButton btnGetPro;
    private ImageView btnBack;
    private LinearLayout featuresContainer;
    private ActivityResultLauncher<String[]> brandingImagePicker;
    private ImageView brandingPreview;
    private Uri pendingBrandingUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        brandingImagePicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri == null) return;
                    try {
                        getContentResolver().takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        );
                    } catch (SecurityException ignored) {
                        // Some providers do not support persistable grants; the URI can
                        // still be used for the current session.
                    }
                    pendingBrandingUri = uri;
                    if (brandingPreview != null) brandingPreview.setImageURI(uri);
                });

        setContentView(R.layout.activity_fadcam_pro);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootContainer), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initViews();
        setupFeatureRows();
        setupClickListeners();
        exposeUnlockedTools();
        startAnimations();
    }

    private void initViews() {
        btnGetPro = findViewById(R.id.btnGetPro);
        btnBack = findViewById(R.id.btnBack);
        featuresContainer = findViewById(R.id.featuresContainer);
    }

    /** Every tier is available in the free build; no purchase state is consulted. */
    private void setupFeatureRows() {
        String[][] features = {
            {getString(R.string.fadcam_pro_feature_everything_icon), "check", "check", "check", "check"},
            {getString(R.string.fadcam_pro_feature_notification_icon), "check", "check", "check", "check"},
            {getString(R.string.fadcam_pro_feature_user_icon), "check", "check", "check", "check"},
            {getString(R.string.fadcam_pro_feature_app_name), "check", "check", "check", "check"},
            {getString(R.string.fadcam_pro_feature_faddrive), "check", "check", "check", "check"}
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
            setStatusIcon(tvFree, features[i][1], 0xFF666666, 0xFF4CAF50, 0xFFFF9800);
            setStatusIcon(tvPro, features[i][2], 0xFF666666, 0xFFFFD700, 0xFFFF9800);
            setStatusIcon(tvProPlus, features[i][3], 0xFF666666, 0xFFFF6B6B, 0xFFFF6B6B);
            setStatusIcon(tvLab, features[i][4], 0xFF666666, 0xFF00BCD4, 0xFF00BCD4);
        }
    }

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
            default:
                tv.setText("remove");
                tv.setTextColor(removeColor);
                break;
        }
    }

    private void setupClickListeners() {
        btnBack.setOnClickListener(v -> finish());
        btnGetPro.setText("✓  Pro unlocked — all features are free");
        btnGetPro.setOnClickListener(v ->
                android.widget.Toast.makeText(this, "All Pro, Pro+ and Lab features are unlocked.", android.widget.Toast.LENGTH_SHORT).show());
    }

    /**
     * Turns the old "coming soon" presentation into real configuration entry points.
     * The legacy purchase/early-bird cards are hidden because this build has no paywall.
     */
    private void exposeUnlockedTools() {
        if (featuresContainer == null) return;

        // Header + 5 feature rows occupy indices 0..5. The old early-bird and
        // coming-soon cards follow them.
        for (int i = 6; i < featuresContainer.getChildCount(); i++) {
            featuresContainer.getChildAt(i).setVisibility(View.GONE);
        }

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.VERTICAL);
        tools.setPadding(dp(4), dp(8), dp(4), dp(4));

        TextView status = new TextView(this);
        status.setText("ALL FEATURES AVAILABLE NOW  •  NO PAYMENT REQUIRED");
        status.setTextColor(0xFF4CAF50);
        status.setTextSize(12);
        status.setGravity(android.view.Gravity.CENTER);
        status.setPadding(dp(8), dp(10), dp(8), dp(10));
        tools.addView(status, new LinearLayout.LayoutParams(-1, -2));

        com.google.android.material.button.MaterialButton branding = new com.google.android.material.button.MaterialButton(this);
        branding.setText("Customize app name & icon");
        branding.setOnClickListener(v -> showBrandingDialog());
        tools.addView(branding, new LinearLayout.LayoutParams(-1, dp(48)));

        com.google.android.material.button.MaterialButton drive = new com.google.android.material.button.MaterialButton(this);
        drive.setText("FadDrive cloud sync");
        drive.setOnClickListener(v -> showFadDriveDialog());
        LinearLayout.LayoutParams driveParams = new LinearLayout.LayoutParams(-1, dp(48));
        driveParams.topMargin = dp(6);
        tools.addView(drive, driveParams);

        featuresContainer.addView(tools);

        View bottomSection = findViewById(R.id.bottomSection);
        if (bottomSection instanceof LinearLayout) {
            LinearLayout bottom = (LinearLayout) bottomSection;
            // Hide the old external-payment notice while keeping the developer support text.
            if (bottom.getChildCount() > 1) bottom.getChildAt(1).setVisibility(View.GONE);
        }
    }

    private void showBrandingDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(8), dp(24), dp(8));

        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setBackgroundColor(0xFF202020);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(dp(96), dp(96));
        previewParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        previewParams.bottomMargin = dp(12);
        root.addView(preview, previewParams);
        brandingPreview = preview;

        EditText name = new EditText(this);
        name.setHint("Custom app name");
        name.setSingleLine(true);
        name.setText(getPrefs().getString(PREF_CUSTOM_NAME, "FadCam"));
        root.addView(name, new LinearLayout.LayoutParams(-1, -2));

        com.google.android.material.button.MaterialButton pick = new com.google.android.material.button.MaterialButton(this);
        pick.setText("Choose custom icon");
        pick.setOnClickListener(v -> brandingImagePicker.launch(new String[]{"image/*"}));
        root.addView(pick, new LinearLayout.LayoutParams(-1, dp(48)));

        String savedUri = getPrefs().getString(PREF_CUSTOM_ICON, null);
        pendingBrandingUri = savedUri == null ? null : Uri.parse(savedUri);
        if (pendingBrandingUri != null) preview.setImageURI(pendingBrandingUri);

        new AlertDialog.Builder(this)
                .setTitle("Custom branding — unlocked")
                .setMessage("The custom name and icon are stored locally and used by FadCam's in-app branding surfaces. Android launcher labels remain package-managed.")
                .setView(root)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    String customName = name.getText().toString().trim();
                    if (customName.isEmpty()) customName = "FadCam";
                    SharedPreferences.Editor e = getPrefs().edit().putString(PREF_CUSTOM_NAME, customName);
                    if (pendingBrandingUri != null) e.putString(PREF_CUSTOM_ICON, pendingBrandingUri.toString());
                    else e.remove(PREF_CUSTOM_ICON);
                    e.apply();
                    android.widget.Toast.makeText(this, "Custom branding saved.", android.widget.Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void showFadDriveDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(8), dp(24), dp(8));

        EditText endpoint = new EditText(this);
        endpoint.setHint("WebDAV server URL (https://…)");
        endpoint.setSingleLine(true);
        endpoint.setText(getPrefs().getString(FadDriveSyncManager.PREF_ENDPOINT, ""));
        root.addView(endpoint, new LinearLayout.LayoutParams(-1, -2));

        EditText username = new EditText(this);
        username.setHint("Username");
        username.setSingleLine(true);
        username.setText(getPrefs().getString(FadDriveSyncManager.PREF_USERNAME, ""));
        root.addView(username, new LinearLayout.LayoutParams(-1, -2));

        EditText password = new EditText(this);
        password.setHint("Password (not stored)");
        password.setSingleLine(true);
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(password, new LinearLayout.LayoutParams(-1, -2));

        TextView help = new TextView(this);
        help.setText("FadDrive uses standard WebDAV so it works with compatible private cloud/NAS providers. Credentials stay in memory for the sync and the password is not persisted.");
        help.setTextSize(12);
        help.setTextColor(0xFFAAAAAA);
        help.setPadding(0, dp(10), 0, dp(4));
        root.addView(help, new LinearLayout.LayoutParams(-1, -2));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("FadDrive cloud sync — unlocked")
                .setView(root)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Test connection", null)
                .setPositiveButton("Sync videos", null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                String url = endpoint.getText().toString().trim();
                String user = username.getText().toString().trim();
                String pass = password.getText().toString();
                saveDrivePrefs(url, user);
                FadDriveSyncManager.testConnection(url, user, pass, new FadDriveSyncManager.Callback() {
                    @Override public void onProgress(String message, int completed, int total) { runOnUiThread(() -> help.setText(message)); }
                    @Override public void onComplete(String message) { runOnUiThread(() -> help.setText(message)); }
                });
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String url = endpoint.getText().toString().trim();
                String user = username.getText().toString().trim();
                String pass = password.getText().toString();
                saveDrivePrefs(url, user);
                FadDriveSyncManager.syncAllVideos(FadCamProActivity.this, url, user, pass,
                        new FadDriveSyncManager.Callback() {
                            @Override public void onProgress(String message, int completed, int total) { runOnUiThread(() -> help.setText(message)); }
                            @Override public void onComplete(String message) { runOnUiThread(() -> { help.setText(message); android.widget.Toast.makeText(FadCamProActivity.this, message, android.widget.Toast.LENGTH_LONG).show(); }); }
                        });
            });
        });
        dialog.show();
    }

    private void saveDrivePrefs(String endpoint, String username) {
        getPrefs().edit()
                .putString(FadDriveSyncManager.PREF_ENDPOINT, endpoint)
                .putString(FadDriveSyncManager.PREF_USERNAME, username)
                .apply();
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void startAnimations() {
        animateFeatureRows();
    }

    private void animateFeatureRows() {
        LinearLayout featuresContainer = findViewById(R.id.featuresContainer);
        if (featuresContainer == null) return;
        for (int i = 0; i < featuresContainer.getChildCount(); i++) {
            View child = featuresContainer.getChildAt(i);
            child.setAlpha(0f);
            child.setTranslationY(20f);
            child.animate().alpha(1f).translationY(0f).setDuration(350).setStartDelay(200 + (i * 80L)).start();
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
    }
}
