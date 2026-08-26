package com.fadcam.production;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.fadcam.Constants;
import com.fadcam.streaming.RemoteStreamService;
import com.fadcam.studio.StudioRtmpConfig;
import com.fadcam.studio.StudioRtmpSecretStore;

/**
 * Live output panel used by the hidden production control room.
 * The review surface previews the same local HLS PROGRAM feed that the RTMP
 * bridge consumes, so the producer can inspect the actual outgoing program
 * before pressing GO LIVE.
 */
@OptIn(markerClass = UnstableApi.class)
public final class ProductionStreamingDialog extends Dialog {
    public interface Listener {
        void onStreamingStateChanged();
    }

    private static final int BG = Color.rgb(10, 10, 10);
    private static final int PANEL = Color.rgb(28, 28, 28);
    private static final int ACCENT = Color.rgb(220, 42, 48);
    private static final int TEXT = Color.WHITE;
    private static final int MUTED = Color.rgb(180, 180, 180);
    private static final int GOOD = Color.rgb(100, 230, 130);
    private static final int WARN = Color.rgb(245, 190, 80);
    private static final int BAD = Color.rgb(235, 90, 90);
    private static final int HLS_WAIT_MS = 12000;

    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Spinner platform;
    private EditText server;
    private EditText key;
    private TextView status;
    private TextView reviewStatus;
    private TextView reviewChecks;
    private PlayerView reviewPlayerView;
    private ExoPlayer reviewPlayer;
    private boolean reviewServiceStarted;
    private int reviewWaitElapsedMs;

    public ProductionStreamingDialog(@NonNull Context context, @NonNull Listener listener) {
        super(context);
        this.listener = listener;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(buildContent());
        setCanceledOnTouchOutside(false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setDimAmount(0.78f);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setLayout((int) (getContext().getResources().getDisplayMetrics().widthPixels * 0.94f),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    @Override
    protected void onStop() {
        stopReview(false);
        super.onStop();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(14));
        root.setBackground(round(BG, 18));

        LinearLayout titleRow = row();
        TextView title = text("LIVE OUTPUT • SOCIAL DESTINATIONS", 15, TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1));
        TextView close = text("×", 28, TEXT);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("Close live output panel");
        close.setOnClickListener(v -> dismiss());
        titleRow.addView(close, new LinearLayout.LayoutParams(dp(42), dp(42)));
        root.addView(titleRow);

        TextView help = text("Review the real PROGRAM feed first. GO LIVE is gated behind an active recording, a valid RTMP/RTMPS destination and a protected stream key.", 10, MUTED);
        help.setPadding(0, 0, 0, dp(10));
        root.addView(help);

        status = text("", 11, TEXT);
        status.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(44)));

        TextView platformLabel = text("DESTINATION", 9, MUTED);
        platformLabel.setPadding(dp(2), dp(10), 0, dp(4));
        root.addView(platformLabel);

        platform = new Spinner(getContext());
        String[] platforms = {
                StudioRtmpConfig.PLATFORM_YOUTUBE,
                StudioRtmpConfig.PLATFORM_FACEBOOK,
                StudioRtmpConfig.PLATFORM_TWITCH,
                StudioRtmpConfig.PLATFORM_CUSTOM
        };
        platform.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, platforms));
        String saved = ProductionStreamingController.getPlatform(getContext());
        for (int i = 0; i < platforms.length; i++) if (platforms[i].equals(saved)) platform.setSelection(i);
        platform.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String selected = platforms[position];
                if (server != null && !StudioRtmpConfig.PLATFORM_CUSTOM.equals(selected)) {
                    server.setText(StudioRtmpConfig.defaultServer(selected));
                    server.setEnabled(true);
                }
                updateStatus();
                updateReviewChecks();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        root.addView(platform, new LinearLayout.LayoutParams(-1, dp(48)));

        server = new EditText(getContext());
        server.setSingleLine(true);
        server.setHint("RTMP / RTMPS server URL");
        server.setText(ProductionStreamingController.getServer(getContext()));
        root.addView(server, new LinearLayout.LayoutParams(-1, dp(52)));

        key = new EditText(getContext());
        key.setSingleLine(true);
        key.setHint("Stream key • protected");
        key.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        key.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        try {
            String savedKey = StudioRtmpSecretStore.load(getContext());
            if (savedKey != null && !savedKey.isEmpty()) key.setText(savedKey);
        } catch (Exception ignored) {
        }
        root.addView(key, new LinearLayout.LayoutParams(-1, dp(52)));

        root.addView(buildStreamingReview(), new LinearLayout.LayoutParams(-1, dp(330)));

        LinearLayout actions = row();
        actions.setPadding(0, dp(10), 0, 0);

        android.widget.Button save = actionButton("SAVE DESTINATION", true);
        save.setOnClickListener(v -> saveDestination());
        actions.addView(save, new LinearLayout.LayoutParams(0, dp(52), 1));
        actions.addView(space(dp(8)));

        android.widget.Button live = actionButton("GO LIVE", true);
        live.setOnClickListener(v -> goLive());
        actions.addView(live, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(actions);

        android.widget.Button stop = actionButton("STOP LIVE", false);
        stop.setOnClickListener(v -> stopLive());
        root.addView(stop, new LinearLayout.LayoutParams(-1, dp(48)));

        TextView destinations = text("SUPPORTED PRESETS\nYouTube • Facebook • Twitch • Custom RTMP\n\nOther RTMP-compatible destinations can be added with their server URL and stream key. Platform API authentication is intentionally not faked; RTMP/RTMPS credentials are supplied by the broadcaster.", 9, MUTED);
        destinations.setPadding(dp(2), dp(12), 0, 0);
        root.addView(destinations);

        updateStatus();
        updateReviewChecks();
        scroll.addView(root);
        return scroll;
    }

    private View buildStreamingReview() {
        LinearLayout panel = new LinearLayout(getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(10), dp(10), dp(10));
        panel.setBackground(round(PANEL, 14));

        LinearLayout heading = row();
        TextView title = text("STREAMING REVIEW", 12, TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, dp(30), 1));
        reviewStatus = text("REVIEW OFF", 9, WARN);
        reviewStatus.setGravity(Gravity.CENTER);
        heading.addView(reviewStatus, new LinearLayout.LayoutParams(dp(96), dp(30)));
        panel.addView(heading);

        reviewPlayerView = new PlayerView(getContext());
        reviewPlayerView.setUseController(true);
        reviewPlayerView.setShutterBackgroundColor(Color.BLACK);
        reviewPlayerView.setContentDescription("Live program streaming review preview");
        panel.addView(reviewPlayerView, new LinearLayout.LayoutParams(-1, dp(160)));

        reviewChecks = text("", 9, MUTED);
        reviewChecks.setPadding(dp(2), dp(8), dp(2), dp(4));
        panel.addView(reviewChecks, new LinearLayout.LayoutParams(-1, dp(74)));

        LinearLayout buttons = row();
        android.widget.Button review = actionButton("REVIEW PROGRAM", true);
        review.setOnClickListener(v -> startReview());
        buttons.addView(review, new LinearLayout.LayoutParams(0, dp(44), 1));
        buttons.addView(space(dp(8)));
        android.widget.Button stopReview = actionButton("STOP REVIEW", false);
        stopReview.setOnClickListener(v -> stopReview(true));
        buttons.addView(stopReview, new LinearLayout.LayoutParams(0, dp(44), 1));
        panel.addView(buttons);
        return panel;
    }

    private void startReview() {
        if (!ProductionStreamingController.hasRecording(getContext())) {
            setReviewStatus("RECORD FIRST", WARN);
            Toast.makeText(getContext(), "Start RECORD before reviewing the outgoing program feed", Toast.LENGTH_LONG).show();
            return;
        }
        int cloudMode = getContext().getSharedPreferences("FadCamCloudPrefs", Context.MODE_PRIVATE)
                .getInt("streaming_mode", 0);
        if (cloudMode == 1) {
            setReviewStatus("CLOUD MODE", WARN);
            Toast.makeText(getContext(), "Local HLS review is unavailable in cloud streaming mode", Toast.LENGTH_LONG).show();
            return;
        }

        setReviewStatus("STARTING…", WARN);
        reviewServiceStarted = true;
        try {
            ContextCompat.startForegroundService(getContext(), new Intent(getContext(), RemoteStreamService.class));
        } catch (Exception e) {
            reviewServiceStarted = false;
            setReviewStatus("HLS ERROR", BAD);
            Toast.makeText(getContext(), "Could not start the local HLS review engine", Toast.LENGTH_LONG).show();
            return;
        }
        reviewWaitElapsedMs = 0;
        handler.post(reviewWaitRunnable);
    }

    private final Runnable reviewWaitRunnable = new Runnable() {
        @Override public void run() {
            if (!isShowing()) return;
            int port = getContext().getSharedPreferences("FadCamPrefs", Context.MODE_PRIVATE)
                    .getInt("stream_server_port", -1);
            if (port > 0) {
                loadReviewPlayer(port);
                return;
            }
            reviewWaitElapsedMs += 250;
            if (reviewWaitElapsedMs >= HLS_WAIT_MS) {
                setReviewStatus("HLS TIMEOUT", BAD);
                Toast.makeText(getContext(), "Program HLS output did not become ready", Toast.LENGTH_LONG).show();
                return;
            }
            handler.postDelayed(this, 250);
        }
    };

    private void loadReviewPlayer(int port) {
        if (reviewPlayer != null) reviewPlayer.release();
        reviewPlayer = new ExoPlayer.Builder(getContext()).build();
        reviewPlayerView.setPlayer(reviewPlayer);
        Uri uri = Uri.parse("http://127.0.0.1:" + port + "/live.m3u8");
        reviewPlayer.setMediaItem(MediaItem.fromUri(uri));
        reviewPlayer.prepare();
        reviewPlayer.setPlayWhenReady(true);
        setReviewStatus("PROGRAM REVIEW", GOOD);
        updateReviewChecks();
    }

    private void stopReview(boolean showToast) {
        handler.removeCallbacks(reviewWaitRunnable);
        if (reviewPlayer != null) {
            reviewPlayer.stop();
            reviewPlayer.release();
            reviewPlayer = null;
        }
        if (reviewPlayerView != null) reviewPlayerView.setPlayer(null);
        if (reviewServiceStarted && !ProductionStreamingController.isLive(getContext())) {
            try {
                getContext().stopService(new Intent(getContext(), RemoteStreamService.class));
            } catch (Exception ignored) {
            }
        }
        reviewServiceStarted = false;
        if (reviewStatus != null) setReviewStatus("REVIEW OFF", WARN);
        if (showToast) Toast.makeText(getContext(), "Program review stopped", Toast.LENGTH_SHORT).show();
    }

    private void saveDestination() {
        String selected = String.valueOf(platform.getSelectedItem());
        try {
            ProductionStreamingController.saveDestination(getContext(), selected,
                    server.getText().toString(), key.getText().toString());
            Toast.makeText(getContext(), "LIVE destination saved securely", Toast.LENGTH_SHORT).show();
            updateStatus();
            updateReviewChecks();
            listener.onStreamingStateChanged();
        } catch (Exception e) {
            Toast.makeText(getContext(), e.getMessage() == null ? "Could not save destination" : e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void goLive() {
        try {
            if (!ProductionStreamingController.hasRecording(getContext())) {
                Toast.makeText(getContext(), "Start RECORD before GO LIVE", Toast.LENGTH_LONG).show();
                return;
            }
            ProductionStreamingController.saveDestination(getContext(), String.valueOf(platform.getSelectedItem()),
                    server.getText().toString(), key.getText().toString());
            ProductionStreamingController.start(getContext());
            updateStatus();
            updateReviewChecks();
            listener.onStreamingStateChanged();
            Toast.makeText(getContext(), "Connecting to " + platform.getSelectedItem() + "…", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(getContext(), e.getMessage() == null ? "Unable to start LIVE output" : e.getMessage(), Toast.LENGTH_LONG).show();
            updateStatus();
            updateReviewChecks();
        }
    }

    private void stopLive() {
        ProductionStreamingController.stop(getContext());
        stopReview(false);
        updateStatus();
        updateReviewChecks();
        listener.onStreamingStateChanged();
        Toast.makeText(getContext(), "LIVE output stopping…", Toast.LENGTH_SHORT).show();
    }

    private void updateStatus() {
        if (status == null) return;
        boolean live = ProductionStreamingController.isLive(getContext());
        boolean recording = ProductionStreamingController.hasRecording(getContext());
        String destination = ProductionStreamingController.getPlatform(getContext());
        status.setText(live
                ? "● LIVE • " + destination
                : (recording ? "READY • RECORDING • " + destination : "READY • START RECORDING FIRST"));
        status.setTextColor(live ? GOOD : TEXT);
        status.setBackground(round(live ? Color.rgb(25, 70, 38) : PANEL, 10));
    }

    private void updateReviewChecks() {
        if (reviewChecks == null) return;
        boolean recording = ProductionStreamingController.hasRecording(getContext());
        boolean destination = ProductionStreamingController.isRtmpServer(server == null ? null : server.getText().toString());
        boolean keyConfigured = key != null && key.getText() != null && !key.getText().toString().trim().isEmpty();
        boolean protectedKey = ProductionStreamingController.hasStreamKey(getContext());
        String review = reviewPlayer != null ? "READY" : "NOT STARTED";
        reviewChecks.setText("PROGRAM FEED     " + (recording ? "✓ RECORDING" : "✕ RECORD FIRST")
                + "\nDESTINATION      " + (destination ? "✓ RTMP/RTMPS" : "✕ INVALID URL")
                + "\nSTREAM KEY       " + ((keyConfigured && protectedKey) ? "✓ PROTECTED" : "✕ NOT CONFIGURED")
                + "\nREVIEW STATE     " + review);
        reviewChecks.setTextColor(recording && destination && keyConfigured && protectedKey ? GOOD : MUTED);
    }

    private void setReviewStatus(String value, int color) {
        if (reviewStatus != null) {
            reviewStatus.setText(value);
            reviewStatus.setTextColor(color);
        }
    }

    private android.widget.Button actionButton(String value, boolean accent) {
        android.widget.Button button = new android.widget.Button(getContext());
        button.setText(value);
        button.setTextSize(10);
        button.setTextColor(TEXT);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setBackground(round(accent ? ACCENT : PANEL, 12));
        return button;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private View space(int width) {
        View view = new View(getContext());
        view.setLayoutParams(new LinearLayout.LayoutParams(width, 1));
        return view;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }
}
