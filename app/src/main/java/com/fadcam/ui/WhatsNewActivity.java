package com.fadcam.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.JavascriptInterface;

import androidx.appcompat.app.AppCompatActivity;

import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.utils.ChangelogParser;
import com.google.android.material.button.MaterialButton;

/**
 * What's New Screen - Full-screen activity showing latest changes
 * Displayed on app launch if "Show Intro" is enabled in settings
 * Uses offline changelog from assets
 */
public class WhatsNewActivity extends AppCompatActivity {
    private static final String TAG = "WhatsNewActivity";
    private static final long COUNTDOWN_DURATION = 5000; // 5 seconds in milliseconds
    
    private WebView changelogWebView;
    private TextView countdownText;
    private MaterialButton btnGotIt;
    private ImageButton btnClose;
    private CountDownTimer countdownTimer;
    private View shimmerContainer;
    private ProgressBar scrollProgressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_whats_new);

        // Set edge-to-edge display and status bar color
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(0xFF0d0d0d); // Match gradient bottom color

        // Initialize views
        changelogWebView = findViewById(R.id.changelogWebView);
        btnClose = findViewById(R.id.btnCloseWhatsNew);
        countdownText = findViewById(R.id.countdownText);
        btnGotIt = findViewById(R.id.btnGotIt);
        shimmerContainer = findViewById(R.id.shimmerContainer);
        scrollProgressBar = findViewById(R.id.scrollProgressBar);

        // Configure WebView
        if (changelogWebView != null) {
            changelogWebView.setBackgroundColor(0x00000000);
            
            // Enable JavaScript for scroll progress tracking
            WebSettings webSettings = changelogWebView.getSettings();
            webSettings.setJavaScriptEnabled(true);
            webSettings.setBuiltInZoomControls(false);
            webSettings.setDisplayZoomControls(false);
            webSettings.setSupportZoom(false);
            
            // Add JavaScript interface for progress updates
            changelogWebView.addJavascriptInterface(new ProgressBridge(), "ProgressBridge");
            
            // Set WebViewClient to handle link clicks and open them in browser
            changelogWebView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    // Open external links in browser
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setData(Uri.parse(url));
                        startActivity(intent);
                        return true;
                    }
                    return false;
                }
            });
        }

        // Set gradient background
        View rootView = findViewById(R.id.whatsNewRoot);
        if (rootView != null) {
            GradientDrawable gradient = new GradientDrawable();
            gradient.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
            gradient.setColors(new int[]{
                    0xFF1a1a1a, // Dark gray
                    0xFF0d0d0d  // Very dark gray
            });
            rootView.setBackground(gradient);
        }

        // Load and display offline changelog
        loadOfflineChangelog();

        // Close button listener
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> {
                navigateToHome();
            });
        }

        // Start countdown timer for "Got It" button
        startCountdownTimer();
        
        // Start shimmer pulse animation
        startShimmerAnimation();
    }

    /**
     * Handle back button press
     */
    @Override
    public void onBackPressed() {
        super.onBackPressed();
        navigateToHome();
    }

    /**
     * Navigate to home screen (MainActivity)
     */
    private void navigateToHome() {
        Log.d(TAG, "navigateToHome: Marking onboarding as complete before navigation");
        // Mark onboarding as complete BEFORE navigating to prevent it from showing again
        SharedPreferencesManager.getInstance(this).setShowOnboarding(false);
        Log.d(TAG, "navigateToHome: Onboarding marked as complete");
        
        Log.d(TAG, "navigateToHome: Starting navigation to MainActivity");
        Intent intent = new Intent(this, com.fadcam.MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        Log.d(TAG, "navigateToHome: Activity started, calling finish()");
        finish();
        Log.d(TAG, "navigateToHome: finish() called");
    }

    /**
     * Start shimmer pulse animation for loading state
     */
    private void startShimmerAnimation() {
        if (shimmerContainer != null) {
            shimmerContainer.animate()
                .alpha(0.4f)
                .setDuration(1000)
                .withEndAction(() -> {
                    if (shimmerContainer != null && shimmerContainer.getVisibility() == View.VISIBLE) {
                        shimmerContainer.animate()
                            .alpha(0.8f)
                            .setDuration(1000)
                            .withEndAction(this::startShimmerAnimation)
                            .start();
                    }
                })
                .start();
        }
    }

    /**
     * Load offline changelog from assets and display it
     */
    private void loadOfflineChangelog() {
        new Thread(() -> {
            try {
                String changelog = readAssetFile(this, "changelog/main.md");
                android.util.Log.d(TAG, "Changelog loaded successfully, size: " + changelog.length());
                
                String formattedChangelog = ChangelogParser.parseChangelogOffline(changelog);
                android.util.Log.d(TAG, "Changelog parsed, formatted size: " + formattedChangelog.length());

                runOnUiThread(() -> {
                    if (changelogWebView != null) {
                        // Wrap HTML with proper styling and margins
                        String htmlContent = "<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1, user-scalable=no'><style>" +
                            "body { color: #E0E0E0; background: transparent; font-family: sans-serif; margin: 0; padding: 0 12px; font-size: 12px; line-height: 1.4; } " +
                            "a { color: #4CAF50; } " +
                            "</style></head><body>" +
                            formattedChangelog +
                            "</body></html>";
                        
                        changelogWebView.loadDataWithBaseURL("file:///android_asset/changelog/", htmlContent, "text/html", "utf-8", null);
                        android.util.Log.d(TAG, "Changelog displayed in WebView");
                        
                        // Hide shimmer and show content with animation
                        if (shimmerContainer != null) {
                            shimmerContainer.animate()
                                .alpha(0f)
                                .setDuration(200)
                                .withEndAction(() -> shimmerContainer.setVisibility(View.GONE))
                                .start();
                        }
                        
                        changelogWebView.setVisibility(View.VISIBLE);
                        changelogWebView.setAlpha(0f);
                        changelogWebView.animate()
                            .alpha(1f)
                            .setDuration(300)
                            .start();
                    }
                });
            } catch (Exception e) {
                android.util.Log.e(TAG, "Error loading changelog", e);
            }
        }).start();
    }

    /**
     * Start countdown timer for button enabling
     */
    private void startCountdownTimer() {
        countdownTimer = new CountDownTimer(COUNTDOWN_DURATION, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                long secondsLeft = (millisUntilFinished + 999) / 1000;
                if (countdownText != null) {
                    countdownText.setText("Ready in " + secondsLeft + " second" + (secondsLeft == 1 ? "" : "s") + "...");
                }
            }

            @Override
            public void onFinish() {
                // Enable button and update UI when countdown finishes
                if (btnGotIt != null) {
                    btnGotIt.setEnabled(true);
                    btnGotIt.setAlpha(1.0f);
                    btnGotIt.setOnClickListener(v -> {
                        navigateToHome();
                    });
                }
                
                if (countdownText != null) {
                    countdownText.setText("You can now close this");
                }
            }
        }.start();
    }

    /**
     * Read asset file content
     */
    private static String readAssetFile(Context context, String fileName) throws Exception {
        StringBuilder sb = new StringBuilder();
        java.io.InputStream is = context.getAssets().open(fileName);
        java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is));
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line).append("\n");
        }
        br.close();
        is.close();
        return sb.toString();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy: WhatsNewActivity being destroyed");
        
        // Cancel countdown timer if still running
        if (countdownTimer != null) {
            countdownTimer.cancel();
        }
        
        // Note: Onboarding is now marked complete in navigateToHome() before navigation
        // to ensure persistence before activity destruction
        Log.d(TAG, "onDestroy: WhatsNewActivity destroyed");
    }

    /**
     * JavaScript Interface for WebView to communicate scroll progress
     */
    public class ProgressBridge {
        @JavascriptInterface
        public void updateProgress(int progress) {
            runOnUiThread(() -> {
                if (scrollProgressBar != null) {
                    // Smooth animation with color shift based on progress
                    int targetProgress = Math.min(progress, 100);
                    scrollProgressBar.setProgress(targetProgress);
                    
                    // Add color shift: green -> bright green -> lime as progress increases
                    if (targetProgress >= 80) {
                        scrollProgressBar.setProgressTintList(
                            android.content.res.ColorStateList.valueOf(0xFF66BB6A) // Bright green
                        );
                    } else if (targetProgress >= 50) {
                        scrollProgressBar.setProgressTintList(
                            android.content.res.ColorStateList.valueOf(0xFF4CAF50) // Medium green
                        );
                    } else {
                        scrollProgressBar.setProgressTintList(
                            android.content.res.ColorStateList.valueOf(0xFF45a049) // Dark green
                        );
                    }
                }
            });
        }
    }
}
