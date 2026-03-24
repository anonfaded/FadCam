package com.fadcam.ui;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.services.RecordingService;

import java.util.Locale;

/**
 * WatchFullscreenActivity — Minimal watch fullscreen recording status screen.
 *
 * <p>Opens when the user taps the "View" icon in WatchCameraFragment.
 * Replaces the cluttered phone FullscreenPreviewActivity on watch.
 * Shows: recording status, elapsed timer, and a back button.</p>
 *
 * <p>Receives {@code EXTRA_RECORDING_START_TIME} from the launching intent
 * to compute the elapsed recording time accurately.</p>
 */
public class WatchFullscreenActivity extends AppCompatActivity {

    /** Extra key: recording start timestamp in millis (pass from WatchCameraFragment). */
    public static final String EXTRA_RECORDING_START_TIME =
            Constants.INTENT_EXTRA_RECORDING_START_TIME;

    private static final long TIMER_INTERVAL_MS = 500L;

    private static final String TAG = "WatchFullscreen";

    private TextView tvStatus;
    private TextView tvTimer;

    /** TextureView for live camera preview. */
    private TextureView previewTextureView;
    @Nullable private Surface previewSurface;

    private long recordingStartTime = 0L;
    private boolean isRecording = false;
    private boolean isPaused    = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing() && isRecording && !isPaused && recordingStartTime > 0) {
                final long elapsed = System.currentTimeMillis() - recordingStartTime;
                if (tvTimer != null) tvTimer.setText(formatElapsed(elapsed));
            }
            handler.postDelayed(this, TIMER_INTERVAL_MS);
        }
    };

    // ── Broadcast receiver for recording state changes ────────────────────────
    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null) return;
            switch (action) {
                case Constants.BROADCAST_ON_RECORDING_STARTED:
                case Constants.BROADCAST_ON_RECORDING_RESUMED:
                    isRecording = true;
                    isPaused    = false;
                    updateStatusLabel();
                    break;
                case Constants.BROADCAST_ON_RECORDING_PAUSED:
                    isPaused = true;
                    updateStatusLabel();
                    break;
                case Constants.BROADCAST_ON_RECORDING_STOPPED:
                    isRecording = false;
                    isPaused    = false;
                    updateStatusLabel();
                    // Optional: auto-close after recording stops
                    handler.postDelayed(() -> {
                        if (!isFinishing()) finish();
                    }, 1500L);
                    break;
                default:
                    break;
            }
        }
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_watch_fullscreen);

        tvStatus = findViewById(R.id.tv_watch_fs_status);
        tvTimer  = findViewById(R.id.tv_watch_fs_timer);

        // Read recording start time from intent
        recordingStartTime = getIntent().getLongExtra(EXTRA_RECORDING_START_TIME, 0L);
        isRecording = (recordingStartTime > 0L);

        // Back button — matches trash screen style (ImageView with ic_arrow_back)
        final View btnBack = findViewById(R.id.btn_watch_fs_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // Live camera preview via TextureView
        previewTextureView = findViewById(R.id.tv_watch_fs_preview);
        setupPreview();

        updateStatusLabel();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Register broadcast receivers
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Constants.BROADCAST_ON_RECORDING_STARTED);
        filter.addAction(Constants.BROADCAST_ON_RECORDING_RESUMED);
        filter.addAction(Constants.BROADCAST_ON_RECORDING_PAUSED);
        filter.addAction(Constants.BROADCAST_ON_RECORDING_STOPPED);
        LocalBroadcastManager.getInstance(this).registerReceiver(stateReceiver, filter);

        // Start timer loop
        handler.post(timerRunnable);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Release preview surface from RecordingService before going to background
        sendSurfaceToService(null, -1, -1);
        if (previewSurface != null) {
            try { previewSurface.release(); } catch (Exception ignored) { }
            previewSurface = null;
        }
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver);
        handler.removeCallbacks(timerRunnable);
    }

    // ── Preview helpers ───────────────────────────────────────────────────────

    /**
     * Attaches a SurfaceTextureListener to the TextureView so we can obtain
     * a {@link Surface} and hand it to RecordingService for live preview.
     */
    private void setupPreview() {
        if (previewTextureView == null) return;
        previewTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
                previewSurface = new Surface(st);
                sendSurfaceToService(previewSurface, w, h);
                // Re-send after short delay to survive service startup race
                handler.postDelayed(() -> {
                    if (previewSurface != null && previewSurface.isValid()) {
                        sendSurfaceToService(previewSurface, w, h);
                    }
                }, 300L);
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {
                if (previewSurface != null && previewSurface.isValid()) {
                    sendSurfaceToService(previewSurface, w, h);
                }
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
                sendSurfaceToService(null, -1, -1);
                if (previewSurface != null) {
                    try { previewSurface.release(); } catch (Exception ignored) { }
                    previewSurface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) { }
        });

        // If the TextureView surface is already available (e.g. after rotation)
        if (previewTextureView.isAvailable()) {
            SurfaceTexture st = previewTextureView.getSurfaceTexture();
            if (st != null) {
                previewSurface = new Surface(st);
                sendSurfaceToService(previewSurface,
                        previewTextureView.getWidth(), previewTextureView.getHeight());
            }
        }
    }

    /**
     * Sends the given surface (or null to detach) to RecordingService
     * via {@link Constants#INTENT_ACTION_CHANGE_SURFACE}.
     */
    private void sendSurfaceToService(@Nullable Surface surface, int w, int h) {
        try {
            Intent intent = new Intent(this, RecordingService.class);
            intent.setAction(Constants.INTENT_ACTION_CHANGE_SURFACE);
            if (surface != null && surface.isValid()) {
                intent.putExtra("SURFACE", surface);
                intent.putExtra("SURFACE_WIDTH", w);
                intent.putExtra("SURFACE_HEIGHT", h);
                intent.putExtra("IS_FULLSCREEN_TRANSITION", true);
            } else {
                intent.putExtra("SURFACE_WIDTH", -1);
                intent.putExtra("SURFACE_HEIGHT", -1);
            }
            startService(intent);
            FLog.d(TAG, "sendSurfaceToService: " +
                    (surface != null && surface.isValid() ? "VALID " + w + "x" + h : "NULL"));
        } catch (Exception e) {
            FLog.w(TAG, "sendSurfaceToService failed: " + e.getMessage());
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void updateStatusLabel() {
        if (tvStatus == null) return;
        if (isPaused) {
            tvStatus.setText(R.string.watch_status_paused);
            tvStatus.setTextColor(0xFFFFAA00); // amber while paused
        } else if (isRecording) {
            tvStatus.setText(R.string.watch_status_recording);
            tvStatus.setTextColor(0xFFFF5252); // red while recording
        } else {
            tvStatus.setText(R.string.watch_fullscreen_not_recording);
            tvStatus.setTextColor(0xFF888888);
        }
    }

    /**
     * Formats elapsed milliseconds as {@code HH:mm:ss}.
     */
    private static String formatElapsed(long millis) {
        if (millis < 0) millis = 0;
        final long totalSecs = millis / 1000L;
        final long h = totalSecs / 3600;
        final long m = (totalSecs % 3600) / 60;
        final long s = totalSecs % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s);
    }
}
