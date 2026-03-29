package com.fadcam.ui;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;

import com.fadcam.R;
import com.fadcam.security.StreamKeyManager;
import com.fadcam.streaming.CloudAuthManager;

/**
 * Activity for linking device to FadCam Remote cloud account.
 * Opens a WebView to id.fadseclab.com/device-link for authentication.
 * Uses JavaScript interface to receive JWT token after successful login.
 */
public class CloudAccountActivity extends AppCompatActivity {
    private static final String TAG = "CloudAccountActivity";

    /**
     * Plaintext password received from Login page via {@link FadCamBridge#initE2E}.
     * Used once to derive the E2E master key in {@link FadCamBridge#onLinkSuccess},
     * then cleared immediately. Volatile for cross-thread visibility.
     */
    private volatile String pendingE2EPassword = null;
    
    public static final String EXTRA_DEVICE_NAME = "device_name";
    public static final String EXTRA_ALREADY_LINKED = "already_linked";
    
    private WebView webView;
    private ProgressBar progressBar;
    private TextView statusText;
    private CloudAuthManager cloudAuthManager;
    private String deviceName;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Enable edge-to-edge display (extends behind status bar and navigation bar)
        getWindow().getDecorView().setSystemUiVisibility(
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        
        // Set status bar and navigation bar to black to match header
        getWindow().setStatusBarColor(0xFF000000);
        getWindow().setNavigationBarColor(0xFF000000);
        
        setContentView(R.layout.activity_cloud_account);
        
        cloudAuthManager = CloudAuthManager.getInstance(this);
        
        // Get device name from intent
        deviceName = getIntent().getStringExtra(EXTRA_DEVICE_NAME);
        boolean alreadyLinked = getIntent().getBooleanExtra(EXTRA_ALREADY_LINKED, false);
        
        if (deviceName == null || deviceName.isEmpty()) {
            deviceName = "FadCam Device";
        }
        
        // Initialize views
        webView = findViewById(R.id.cloud_webview);
        progressBar = findViewById(R.id.cloud_progress_bar);
        statusText = findViewById(R.id.cloud_status_text);
        
        // Back button
        findViewById(R.id.cloud_back_button).setOnClickListener(v -> finish());
        
        // Setup WebView
        setupWebView();
        
        // Apply window insets for proper edge-to-edge layout with notch/gesture support
        android.view.View headerBar = findViewById(R.id.cloud_header_bar);
        
        ViewCompat.setOnApplyWindowInsetsListener(headerBar, (v, insets) -> {
            int systemInsetTop = insets.getSystemWindowInsetTop();
            int systemInsetLeft = insets.getSystemWindowInsetLeft();
            int systemInsetRight = insets.getSystemWindowInsetRight();
            
            v.setPadding(
                systemInsetLeft + v.getPaddingStart(),
                systemInsetTop + 16,  // 16dp + status bar height
                systemInsetRight + v.getPaddingEnd(),
                v.getPaddingBottom()
            );
            return insets;
        });
        
        ViewCompat.setOnApplyWindowInsetsListener(webView, (v, insets) -> {
            int systemInsetLeft = insets.getSystemWindowInsetLeft();
            int systemInsetRight = insets.getSystemWindowInsetRight();
            int systemInsetBottom = insets.getSystemWindowInsetBottom();
            
            v.setPadding(systemInsetLeft, 0, systemInsetRight, systemInsetBottom);
            return insets;
        });
        
        // Load device link URL
        String url = cloudAuthManager.buildDeviceLinkUrl(deviceName);
        FLog.d(TAG, "Loading URL: " + url);
        webView.loadUrl(url);
    }
    
    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setAllowContentAccess(true);
        
        // Add JavaScript interface for receiving auth callbacks
        webView.addJavascriptInterface(new FadCamBridge(), "FadCamBridge");
        
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(android.webkit.WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                progressBar.setVisibility(View.VISIBLE);
                statusText.setText(R.string.cloud_account_connecting);
            }
            
            @Override
            public void onPageFinished(android.webkit.WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                statusText.setText("");
            }
            
            @Override
            public void onReceivedError(android.webkit.WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                FLog.e(TAG, "WebView error: " + description);
                statusText.setText(R.string.cloud_account_link_failed);
            }
        });
        
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
            }
        });
    }
    
    /**
     * JavaScript interface for receiving auth callbacks from the web page.
     * The device-link page will call these methods via window.FadCamBridge
     */
    public class FadCamBridge {
        /**
         * Called when device linking is successful.
         *
         * @param e2eVerifyTag 64-char hex HMAC verify tag, or empty string if E2E not configured.
         */
        @JavascriptInterface
        public void onLinkSuccess(String token, long expiryMs, String email,
                                  String refreshToken, String userId, String e2eVerifyTag) {
            FLog.i(TAG, "Device linked successfully to: " + email + ", has refresh: " + (refreshToken != null && !refreshToken.isEmpty()));

            cloudAuthManager.setJwtToken(token, expiryMs, refreshToken, userId);
            cloudAuthManager.setDeviceName(deviceName);
            cloudAuthManager.setUserEmail(email);

            // Cache the verify_tag locally so the settings row can validate password changes
            // without a fresh REST call.
            boolean hasVerifyTag = e2eVerifyTag != null && e2eVerifyTag.length() == 64;
            cloudAuthManager.storeE2EVerifyTag(hasVerifyTag ? e2eVerifyTag : null);

            // Auto-derive E2E key if the login form handed us the password.
            // The derivation happens on a background thread (PBKDF2 is intentionally slow).
            final String password  = pendingE2EPassword;
            pendingE2EPassword = null; // Clear immediately after use — never keep in memory

            if (password != null && hasVerifyTag) {
                // Existing account with E2E already configured — validate password and store key.
                FLog.i(TAG, "Auto-deriving E2E master key after device link (verify_tag present)…");
                new Thread(() -> {
                    try {
                        StreamKeyManager.getInstance(CloudAccountActivity.this)
                                .initFromPassword(password, userId, e2eVerifyTag);
                        FLog.i(TAG, "E2E key configured automatically during device link");
                    } catch (SecurityException e) {
                        // Should not happen: verifyTag came from the account that owns this password
                        FLog.e(TAG, "E2E auto-setup: unexpected verify_tag mismatch", e);
                    } catch (Exception e) {
                        FLog.e(TAG, "E2E auto-setup failed", e);
                    }
                }, "e2e-auto-setup").start();
            } else if (password != null) {
                // First-time setup — no verify_tag in Supabase yet.
                // Derive key, compute verify_tag, and write it to Supabase so the
                // dashboard (and future links) can validate the password.
                FLog.i(TAG, "First-time E2E setup — deriving key and writing verify_tag to Supabase…");
                new Thread(() -> {
                    try {
                        StreamKeyManager keyManager =
                                StreamKeyManager.getInstance(CloudAccountActivity.this);
                        // Derive + store (no server-side validation yet — this IS the first setup)
                        keyManager.initFromPassword(password, userId);
                        // Compute the tag and write it to Supabase for dashboard validation
                        String computedTag = keyManager.computeVerifyTag(password, userId);
                        cloudAuthManager.writeE2EVerifyTagSync(computedTag);
                        FLog.i(TAG, "E2E first-time setup complete — verify_tag written to Supabase");
                    } catch (Exception e) {
                        FLog.e(TAG, "E2E first-time setup failed", e);
                    }
                }, "e2e-first-setup").start();
            } else if (hasVerifyTag) {
                // User linked without going through Login (e.g. already had a session).
                // verify_tag is cached locally; E2E key will be available next time they
                // re-link while the Login page passes the password.
                FLog.i(TAG, "E2E verify_tag cached; key will auto-derive on next link with password");
            } else {
                FLog.i(TAG, "E2E not configured on this account (no verify_tag)");
            }

            runOnUiThread(() -> {
                Toast.makeText(CloudAccountActivity.this,
                        R.string.cloud_account_link_success, Toast.LENGTH_SHORT).show();
                Intent resultIntent = new Intent();
                resultIntent.putExtra("linked", true);
                resultIntent.putExtra("email", email);
                setResult(RESULT_OK, resultIntent);
                finish();
            });
        }

        /**
         * Called by the Login page (during device-link WebView flow) to pass the user's
         * plaintext password so {@link #onLinkSuccess} can automatically derive the E2E
         * master key without prompting the user a second time.
         *
         * <p>The password is held only in memory and cleared immediately after use.
         */
        @JavascriptInterface
        public void initE2E(String password) {
            FLog.i(TAG, "E2E password received from WebView — will auto-derive key on next link success");
            pendingE2EPassword = password;
        }
        
        /**
         * Called when device linking fails
         * @param error Error message
         */
        @JavascriptInterface
        public void onLinkFailed(String error) {
            FLog.e(TAG, "Device linking failed: " + error);
            runOnUiThread(() -> {
                Toast.makeText(CloudAccountActivity.this, 
                    R.string.cloud_account_link_failed, Toast.LENGTH_SHORT).show();
            });
        }
        
        /**
         * Called when user wants to close the WebView (e.g., cancelled)
         */
        @JavascriptInterface
        public void onClose() {
            runOnUiThread(() -> {
                setResult(RESULT_CANCELED);
                finish();
            });
        }
        
        /**
         * Get the device ID for display in the web page
         */
        @JavascriptInterface
        public String getDeviceId() {
            return cloudAuthManager.getDeviceId();
        }
        
        /**
         * Get the short device ID for display
         */
        @JavascriptInterface
        public String getShortDeviceId() {
            return cloudAuthManager.getShortDeviceId();
        }
    }
    
    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
    
    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
