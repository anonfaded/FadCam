package com.fadcam.production;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import java.util.WeakHashMap;

/** Embedded VDO.Ninja WebRTC receiver for the producer's remote guest camera slots. */
public final class ProductionGuestWebView {
    private static final WeakHashMap<FrameLayout, ProductionGuestWebView> INSTANCES = new WeakHashMap<>();
    private static final int POLL_MS = 1000;
    private static final int DISCONNECT_POLLS = 5;

    public static synchronized ProductionGuestWebView obtain(@NonNull Context context, @NonNull FrameLayout canvas) {
        ProductionGuestWebView existing = INSTANCES.get(canvas);
        if (existing != null) return existing;
        ProductionGuestWebView created = new ProductionGuestWebView(context, canvas);
        INSTANCES.put(canvas, created);
        return created;
    }

    private final Context context;
    private final FrameLayout canvas;
    private final WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int slot;
    private boolean mediaSeen;
    private int missingMediaPolls;

    private ProductionGuestWebView(@NonNull Context context, @NonNull FrameLayout canvas) {
        this.context = context;
        this.canvas = canvas;
        webView = new WebView(context);
        webView.setTag("fadcam_guest_webview");
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowContentAccess(true);
        settings.setDatabaseEnabled(true);
        webView.setBackgroundColor(Color.BLACK);
        webView.setWebViewClient(new WebViewClient());
        webView.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        webView.setVisibility(WebView.GONE);
        canvas.addView(webView, new FrameLayout.LayoutParams(-1, -1));
    }

    public void loadSlot(int slot) {
        int normalized = ProductionControlState.clampCameraSlot(slot);
        if (this.slot == normalized && mediaSeen && webView.getVisibility() == WebView.VISIBLE) return;
        this.slot = normalized;
        mediaSeen = false;
        missingMediaPolls = 0;
        webView.setVisibility(WebView.VISIBLE);
        webView.loadUrl(ProductionCameraSlotManager.viewerLink(context, normalized));
        pollForMedia(0);
    }

    public void hide() {
        handler.removeCallbacksAndMessages(null);
        webView.stopLoading();
        webView.setVisibility(WebView.GONE);
        mediaSeen = false;
        missingMediaPolls = 0;
    }

    public void applyScene(@NonNull ProductionScene scene) {
        FrameLayout.LayoutParams lp;
        switch (scene) {
            case DUET_SPLIT:
                lp = new FrameLayout.LayoutParams(Math.max(1, canvas.getWidth() / 2), -1, Gravity.END);
                break;
            case COMMENTARY:
                lp = new FrameLayout.LayoutParams(dp(190), dp(270), Gravity.TOP | Gravity.END);
                lp.setMargins(0, dp(16), dp(16), 0);
                break;
            case INTERVIEW:
                lp = new FrameLayout.LayoutParams(dp(230), dp(310), Gravity.BOTTOM | Gravity.END);
                lp.setMargins(0, 0, dp(16), dp(70));
                break;
            case DUET_PIP:
            case CAMERA:
            default:
                lp = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
                break;
        }
        webView.setLayoutParams(lp);
    }

    public void destroy() {
        handler.removeCallbacksAndMessages(null);
        INSTANCES.remove(canvas);
        canvas.removeView(webView);
        webView.stopLoading();
        webView.destroy();
    }

    private void pollForMedia(final int attempt) {
        if (webView.getVisibility() != WebView.VISIBLE || attempt > 3600) return;
        webView.evaluateJavascript(
                "(function(){var v=[].slice.call(document.querySelectorAll('video'));" +
                "return v.some(function(x){return x.readyState>=2 && !x.paused && x.videoWidth>0});})()",
                value -> {
                    boolean hasMedia = "true".equals(value);
                    if (hasMedia) {
                        missingMediaPolls = 0;
                        if (!mediaSeen) {
                            mediaSeen = true;
                            ProductionCameraSlotManager.markConsumed(context, slot);
                        }
                    } else if (mediaSeen) {
                        missingMediaPolls++;
                        if (missingMediaPolls >= DISCONNECT_POLLS) {
                            ProductionCameraSlotManager.markDisconnected(context, slot);
                            mediaSeen = false;
                            missingMediaPolls = 0;
                            webView.loadUrl(ProductionCameraSlotManager.viewerLink(context, slot));
                        }
                    }
                    handler.postDelayed(() -> pollForMedia(attempt + 1), POLL_MS);
                });
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
