package com.fadcam.production;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/** Receives a one-use producer invitation and publishes this phone's camera via WebRTC. */
public final class GuestCameraActivity extends Activity {
    private static final int PERMISSION_REQUEST = 8121;
    private WebView webView;
    private int slot = -1;
    private String token = "";

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        readIntent(getIntent());
        if (slot < 1 || slot > 6 || token.isEmpty()) {
            Toast.makeText(this, "Invalid or incomplete FadCam camera invitation", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        ProductionCameraSlot current = ProductionCameraSlotManager.get(this, slot);
        if (!token.equals(current.getInviteToken()) || current.isInviteConsumed()) {
            Toast.makeText(this, "This camera invitation has already been used or replaced", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        buildUi();
        if (hasPermissions()) loadPublisher();
        else ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readIntent(intent);
        if (webView != null) {
            ProductionCameraSlot current = ProductionCameraSlotManager.get(this, slot);
            if (token.equals(current.getInviteToken()) && !current.isInviteConsumed()) loadPublisher();
        }
    }

    private void readIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        try {
            String slotValue = intent.getData().getQueryParameter("slot");
            slot = Integer.parseInt(slotValue == null ? "-1" : slotValue);
            token = intent.getData().getQueryParameter("token");
            if (token == null) token = "";
        } catch (Exception ignored) {
            slot = -1;
            token = "";
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        TextView header = new TextView(this);
        header.setText("FADCAM • GUEST CAMERA " + slot + "\nConnecting securely to the producer…");
        header.setTextColor(Color.WHITE);
        header.setTextSize(15);
        header.setPadding(18, 18, 18, 18);
        header.setBackgroundColor(Color.rgb(48, 12, 15));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void loadPublisher() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowContentAccess(true);
        settings.setDatabaseEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });
        webView.loadUrl(ProductionCameraSlotManager.vdoPushLink(this, slot));
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST && hasPermissions()) loadPublisher();
        else if (requestCode == PERMISSION_REQUEST) {
            Toast.makeText(this, "Camera and microphone permissions are required for the guest feed", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
