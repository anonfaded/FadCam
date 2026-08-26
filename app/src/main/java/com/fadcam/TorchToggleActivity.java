package com.fadcam;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.fadcam.dualcam.service.DualCameraRecordingService;
import com.fadcam.production.ProductionCameraSlot;
import com.fadcam.production.ProductionCameraSlotManager;
import com.fadcam.services.RecordingService;
import com.fadcam.services.TorchService;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.streaming.RemoteStreamManager;
import com.fadcam.utils.ServiceUtils;

import java.util.Random;

/** Existing exported torch router plus the FadCam app-to-app guest camera entry point. */
public class TorchToggleActivity extends Activity {
    private static final String TAG = "TorchToggleActivity";
    private static final int GUEST_PERMISSION_REQUEST = 8121;
    private boolean guestMode;
    private WebView guestWebView;
    private int guestSlot = -1;
    private String guestToken = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (isGuestInvite(getIntent())) {
            guestMode = true;
            readGuestInvite(getIntent());
            showGuestCamera();
            return;
        }
        runTorchToggle();
    }

    private boolean isGuestInvite(Intent intent) {
        if (intent == null || intent.getData() == null) return false;
        return "fadcam".equalsIgnoreCase(intent.getData().getScheme())
                && "guest-camera".equalsIgnoreCase(intent.getData().getHost());
    }

    private void readGuestInvite(Intent intent) {
        try {
            String value = intent.getData().getQueryParameter("slot");
            guestSlot = Integer.parseInt(value == null ? "-1" : value);
            guestToken = intent.getData().getQueryParameter("token");
            if (guestToken == null) guestToken = "";
        } catch (Exception ignored) {
            guestSlot = -1;
            guestToken = "";
        }
    }

    private void showGuestCamera() {
        getWindow().setBackgroundDrawableResource(android.R.color.black);
        if (guestSlot < 1 || guestSlot > 6 || guestToken.isEmpty()) {
            Toast.makeText(this, "Invalid FadCam camera invitation", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        ProductionCameraSlot slot = ProductionCameraSlotManager.get(this, guestSlot);
        if (!guestToken.equals(slot.getInviteToken()) || slot.isInviteConsumed()) {
            Toast.makeText(this, "This FadCam camera invitation is no longer active", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        TextView header = new TextView(this);
        header.setText("FADCAM • CAMERA " + guestSlot + "\nGuest camera • secure one-time studio invite");
        header.setTextColor(Color.WHITE);
        header.setTextSize(15);
        header.setPadding(18, 18, 18, 18);
        header.setBackgroundColor(Color.rgb(55, 14, 18));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
        guestWebView = new WebView(this);
        guestWebView.setBackgroundColor(Color.BLACK);
        root.addView(guestWebView, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        if (hasGuestPermissions()) loadGuestPublisher();
        else ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, GUEST_PERMISSION_REQUEST);
    }

    private boolean hasGuestPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void loadGuestPublisher() {
        WebSettings settings = guestWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowContentAccess(true);
        settings.setDatabaseEnabled(true);
        guestWebView.setWebViewClient(new WebViewClient());
        guestWebView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    java.util.ArrayList<String> allowed = new java.util.ArrayList<>();
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                                || PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) allowed.add(resource);
                    }
                    if (allowed.isEmpty()) request.deny();
                    else request.grant(allowed.toArray(new String[0]));
                });
            }
        });
        guestWebView.loadUrl(ProductionCameraSlotManager.vdoPushLink(this, guestSlot));
    }

    private void runTorchToggle() {
        try {
            SharedPreferencesManager sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
            Intent intent;
            RemoteStreamManager streamManager = RemoteStreamManager.getInstance();
            if (streamManager.isStreamingEnabled()) {
                boolean dualRunning = ServiceUtils.isServiceRunning(this, DualCameraRecordingService.class)
                        || (sharedPreferencesManager.getCameraSelection() != null
                        && sharedPreferencesManager.getCameraSelection().isDual());
                intent = new Intent(this, dualRunning ? DualCameraRecordingService.class : RecordingService.class);
                intent.setAction(Constants.INTENT_ACTION_TOGGLE_RECORDING_TORCH);
            } else {
                intent = new Intent(this, TorchService.class);
                intent.setAction(Constants.INTENT_ACTION_TOGGLE_TORCH);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
            else startService(intent);
        } catch (Exception e) {
            FLog.e(TAG, "Error toggling torch", e);
            showTorchErrorToast();
        } finally {
            moveTaskToBack(true);
            finish();
        }
    }

    private void showTorchErrorToast() {
        String[] errorMessages = getResources().getStringArray(R.array.torch_error_messages);
        String humorousMessage = errorMessages[new Random().nextInt(errorMessages.length)];
        Toast.makeText(this, humorousMessage, Toast.LENGTH_LONG).show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == GUEST_PERMISSION_REQUEST && hasGuestPermissions()) loadGuestPublisher();
        else if (requestCode == GUEST_PERMISSION_REQUEST) finish();
    }

    @Override protected void onStop() {
        super.onStop();
        if (!guestMode) finish();
    }

    @Override protected void onPause() {
        super.onPause();
        if (!guestMode) moveTaskToBack(true);
    }

    @Override protected void onDestroy() {
        if (guestWebView != null) {
            guestWebView.stopLoading();
            guestWebView.destroy();
        }
        super.onDestroy();
    }
}
