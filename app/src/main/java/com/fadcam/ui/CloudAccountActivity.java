package com.fadcam.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
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

import com.fadcam.R;
import com.fadcam.streaming.CloudAuthManager;

/**
 * Activity for linking device to FadCam Remote cloud account.
 * Opens a WebView to id.fadseclab.com/device-link for authentication.
 * Uses JavaScript interface to receive JWT token after successful login.
 */
public class CloudAccountActivity extends AppCompatActivity {
    private static final String TAG = "CloudAccountActivity";
    
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
        
        // Load device link URL
        String url = cloudAuthManager.buildDeviceLinkUrl(deviceName);
        Log.d(TAG, "Loading URL: " + url);
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
                Log.e(TAG, "WebView error: " + description);
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
         * Called when device linking is successful
         * @param token JWT access token for API authentication
         * @param expiryMs Token expiry timestamp in milliseconds
         * @param email User's email address
         * @param refreshToken Refresh token for seamless renewal (may be null for legacy)
         * @param userId User's UUID (may be null, will be extracted from token)
         */
        @JavascriptInterface
        public void onLinkSuccess(String token, long expiryMs, String email, String refreshToken, String userId) {
            Log.i(TAG, "Device linked successfully to: " + email + ", has refresh: " + (refreshToken != null && !refreshToken.isEmpty()));
            
            // Store the token with refresh token for seamless renewal
            cloudAuthManager.setJwtToken(token, expiryMs, refreshToken, userId);
            cloudAuthManager.setDeviceName(deviceName);
            cloudAuthManager.setUserEmail(email);
            
            runOnUiThread(() -> {
                Toast.makeText(CloudAccountActivity.this, 
                    R.string.cloud_account_link_success, Toast.LENGTH_SHORT).show();
                
                // Set result and close
                Intent resultIntent = new Intent();
                resultIntent.putExtra("linked", true);
                resultIntent.putExtra("email", email);
                setResult(RESULT_OK, resultIntent);
                finish();
            });
        }
        
        /**
         * Called when device linking fails
         * @param error Error message
         */
        @JavascriptInterface
        public void onLinkFailed(String error) {
            Log.e(TAG, "Device linking failed: " + error);
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
