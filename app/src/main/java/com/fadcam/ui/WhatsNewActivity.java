package com.fadcam.ui;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.Context;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.widget.Toast;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.JavascriptInterface;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

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
    private boolean firstFrameDrawn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Same treatment as the Pro screen: transparent status bar, hidden by default and
        // revealed transiently by a swipe over the content.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.argb(1, 0, 0, 0));
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            getWindow().setStatusBarContrastEnforced(false);
        }
        getWindow().setNavigationBarColor(0xFF000000);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            android.view.WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
        }
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(false);
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        setContentView(R.layout.activity_whats_new);

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
                public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                    // Open external links in browser (modern overload — the
                    // String-only variant is deprecated since API 24).
                    String url = request.getUrl().toString();
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setData(Uri.parse(url));
                        try {
                            startActivity(intent);
                        } catch (ActivityNotFoundException e) {
                            Toast.makeText(view.getContext(),
                                    R.string.whats_new_no_browser, Toast.LENGTH_SHORT).show();
                        }
                        return true;
                    }
                    return false;
                }
            });
        }

        // Content stays below the status-bar/cutout safe area while the bar stays hidden. The
        // header panel itself covers the top of the screen so no empty strip shows above it.
        View rootView = findViewById(R.id.whatsNewRoot);
        if (rootView != null) {
            final View insetsRoot = rootView;
            final View headerPanel = findViewById(R.id.whatsNewHeader);
            ViewCompat.setOnApplyWindowInsetsListener(insetsRoot, (v, insets) -> {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                        | WindowInsetsCompat.Type.displayCutout());
                Insets safe = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars()
                        | WindowInsetsCompat.Type.displayCutout());
                v.setPadding(bars.left, 0, bars.right, bars.bottom);
                if (headerPanel != null) {
                    headerPanel.setPadding(headerPanel.getPaddingLeft(), safe.top,
                            headerPanel.getPaddingRight(), headerPanel.getPaddingBottom());
                }
                return insets;
            });
            ViewCompat.requestApplyInsets(insetsRoot);
            // Hide the bar only after the first frame so the entrance stays stable.
            insetsRoot.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    insetsRoot.getViewTreeObserver().removeOnPreDrawListener(this);
                    firstFrameDrawn = true;
                    insetsRoot.postOnAnimation(() -> {
                        if (!isFinishing()) hideStatusBar(insetsRoot);
                    });
                    return true;
                }
            });
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

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                navigateToHome();
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
    }

    /**
     * Social buttons rendered at the end of the changelog content, below the thank-you note.
     * HTML so they scroll with the changelog; taps open in the browser via the WebViewClient.
     */
    private String socialButtonsHtml() {
        String xIcon =
            "<svg width='16' height='16' viewBox='0 0 24 24' fill='#FFFFFF' style='vertical-align:-2px'>" +
            "<path d='M18.901 1.153h3.68l-8.04 9.19L24 22.846h-7.406l-5.8-7.584-6.638 7.584H0.474l8.6-9.83L0 1.154h7.594l5.243 6.932ZM17.61 20.644h2.039L6.486 3.24H4.298Z'/></svg>";
        String discordIcon =
            "<svg width='18' height='18' viewBox='0 0 24 24' fill='#FFFFFF' style='vertical-align:-3px'>" +
            "<path d='M 20.317 4.3698 a 19.7913 19.7913 0 0 0 -4.8851 -1.5152 0.0741 0.0741 0 0 0 -0.0785 0.0371 c -0.211 0.3753 -0.4447 0.8648 -0.6083 1.2495 -1.8447 -0.2762 -3.68 -0.2762 -5.4868 0 -0.1636 -0.3933 -0.4058 -0.8742 -0.6177 -1.2495 a 0.077 0.077 0 0 0 -0.0785 -0.037 19.7363 19.7363 0 0 0 -4.8852 1.515 0.0699 0.0699 0 0 0 -0.0321 0.0277 C 0.5334 9.0458 -0.319 13.5799 0.0992 18.0578 a 0.0824 0.0824 0 0 0 0.0312 0.0561 c 2.0528 1.5076 4.0413 2.4228 5.9929 3.0294 a 0.0777 0.0777 0 0 0 0.0842 -0.0276 c 0.4616 -0.6304 0.8731 -1.2952 1.226 -1.9942 a 0.076 0.076 0 0 0 -0.0416 -0.1057 c -0.6528 -0.2476 -1.2743 -0.5495 -1.8722 -0.8923 a 0.077 0.077 0 0 1 -0.0076 -0.1277 c 0.1258 -0.0943 0.2517 -0.1923 0.3718 -0.2914 a 0.0743 0.0743 0 0 1 0.0776 -0.0105 c 3.9278 1.7933 8.18 1.7933 12.0614 0 a 0.0739 0.0739 0 0 1 0.0785 0.0095 c 0.1202 0.099 0.246 0.1981 0.3728 0.2924 a 0.077 0.077 0 0 1 -0.0066 0.1276 12.2986 12.2986 0 0 1 -1.873 0.8914 0.0766 0.0766 0 0 0 -0.0407 0.1067 c 0.3604 0.698 0.7719 1.3628 1.225 1.9932 a 0.076 0.076 0 0 0 0.0842 0.0286 c 1.961 -0.6067 3.9495 -1.5219 6.0023 -3.0294 a 0.077 0.077 0 0 0 0.0313 -0.0552 c 0.5004 -5.177 -0.8382 -9.6739 -3.5485 -13.6604 a 0.061 0.061 0 0 0 -0.0312 -0.0286 z M 8.02 15.3312 c -1.1825 0 -2.1569 -1.0857 -2.1569 -2.419 0 -1.3332 0.9555 -2.4189 2.157 -2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332 -0.9555 2.4189 -2.1569 2.4189 z m 7.9748 0 c -1.1825 0 -2.1569 -1.0857 -2.1569 -2.419 0 -1.3332 0.9554 -2.4189 2.1569 -2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332 -0.946 2.4189 -2.1568 2.4189 Z'/></svg>";
        return "<div style='margin:18px 0 8px;'>" +
            "<a class='social-btn social-x' href='https://x.com/anon_faded'>" +
            xIcon + "&nbsp;&nbsp;" + getString(R.string.follow_on_x) + "</a>" +
            "<a class='social-btn social-discord' href='https://discord.gg/kvAZvdkuuN'>" +
            discordIcon + "&nbsp;&nbsp;" + getString(R.string.whats_new_discord) + "</a>" +
            "</div>";
    }

    /**
     * Navigate to home screen (MainActivity)
     */
    private void navigateToHome() {        FLog.d(TAG, "navigateToHome: Marking onboarding as complete before navigation");
        // Mark onboarding as complete BEFORE navigating to prevent it from showing again
        SharedPreferencesManager.getInstance(this).setShowOnboarding(false);
        FLog.d(TAG, "navigateToHome: Onboarding marked as complete");
        
        FLog.d(TAG, "navigateToHome: Starting navigation to MainActivity");
        Intent intent = new Intent(this, com.fadcam.MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        FLog.d(TAG, "navigateToHome: Activity started, calling finish()");
        finish();
        FLog.d(TAG, "navigateToHome: finish() called");
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
                String changelog = readAssetFile(this, com.fadcam.BuildConfig.LITE_EDITION
                        ? "changelog/lite.md" : "changelog/main.md");
                FLog.d(TAG, "Changelog loaded successfully, size: " + changelog.length());
                
                String formattedChangelog = ChangelogParser.parseChangelogOffline(changelog);
                FLog.d(TAG, "Changelog parsed, formatted size: " + formattedChangelog.length());

                runOnUiThread(() -> {
                    if (changelogWebView != null) {
                        // Wrap HTML with proper styling and margins
                        String htmlContent = "<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1, user-scalable=no'><style>" +
                            "body { color: #E0E0E0; background: transparent; font-family: sans-serif; margin: 0; padding: 0 12px; font-size: 12px; line-height: 1.4; } " +
                            "a { color: #4CAF50; -webkit-tap-highlight-color: transparent; } " +
                            ".social-btn { display: block; text-align: center; border-radius: 12px; padding: 12px; " +
                            "font-size: 14px; font-weight: 600; text-decoration: none; margin-bottom: 8px; " +
                            "-webkit-tap-highlight-color: transparent; -webkit-user-select: none; user-select: none; } " +
                            ".social-x { background: #000000; border: 1px solid #2F3336; color: #FFFFFF; } " +
                            ".social-x:active { background: #17181A; } " +
                            ".social-discord { background: #5865F2; color: #FFFFFF; } " +
                            ".social-discord:active { background: #4752C4; } " +
                            "</style></head><body>" +
                            formattedChangelog +
                            socialButtonsHtml() +
                            "</body></html>";
                        
                        changelogWebView.loadDataWithBaseURL("file:///android_asset/changelog/", htmlContent, "text/html", "utf-8", null);
                        FLog.d(TAG, "Changelog displayed in WebView");
                        
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
                FLog.e(TAG, "Error loading changelog", e);
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

    /** Hides the status bar with the same recipe as the Pro screen (transient swipe reveal). */
    private void hideStatusBar(View root) {
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        View decor = getWindow().getDecorView();
        int systemUiVisibility = decor.getSystemUiVisibility();
        decor.setSystemUiVisibility(systemUiVisibility
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .hide(WindowInsetsCompat.Type.statusBars());
        // Force a relayout so OEM window managers recompute the frame with the bar hidden.
        getWindow().setAttributes(getWindow().getAttributes());
        ViewCompat.requestApplyInsets(root);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            View root = findViewById(R.id.whatsNewRoot);
            if (root != null) ViewCompat.requestApplyInsets(root);
            if (root != null && firstFrameDrawn) root.postOnAnimation(() -> {
                if (hasWindowFocus() && !isFinishing()) hideStatusBar(root);
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        FLog.d(TAG, "onDestroy: WhatsNewActivity being destroyed");
        
        // Cancel countdown timer if still running
        if (countdownTimer != null) {
            countdownTimer.cancel();
        }
        
        // Note: Onboarding is now marked complete in navigateToHome() before navigation
        // to ensure persistence before activity destruction
        FLog.d(TAG, "onDestroy: WhatsNewActivity destroyed");
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
