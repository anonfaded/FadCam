package com.fadcam.ui;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.services.RecordingService;
import com.fadcam.utils.ServiceStartPolicy;

import java.io.File;
import java.util.Locale;

/**
 * Watch camera screen — the main screen of the watch UI.
 *
 * <p>Shows the FadCam avatar eye toggle as the primary record/stop button,
 * a live recording timer, available storage, and quick-action icons for
 * toggling audio and flipping the camera.
 *
 * <p>Stripped of controls that do not fit a watch form factor:
 * clock, AF/exposure controls, zoom, watermark, etc.
 */
public class WatchCameraFragment extends Fragment {

    private static final String TAG = "WatchCameraFrag";
    private static final long STORAGE_REFRESH_INTERVAL_MS = 5_000L;

    // ── Views ─────────────────────────────────────────────────────────────────

    private AvatarToggleView watchAvatarToggle;
    private TextView         tvWatchStorage;
    private TextView         tvWatchTimer;
    private ImageView        ivWatchAudioToggle;
    private TextView         tvWatchAudioLabel;   // "Mic On" / "Muted"
    private ImageView        ivWatchPauseResume;
    private TextView         tvWatchPauseLabel;   // "Pause" / "Resume"
    private TextView         ivWatchFullscreen;   // TextView using Material Icons font
    private TextView         ivWatchFadShot;      // TextView using Material Icons font

    // ── State ─────────────────────────────────────────────────────────────────

    private boolean isRecording = false;
    private boolean isPaused    = false;
    private long    recordingStartTime = 0L;

    private SharedPreferencesManager prefs;

    // ── Timer + storage refresh ────────────────────────────────────────────────

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRecording && !isPaused) {
                long elapsed = System.currentTimeMillis() - recordingStartTime;
                if (tvWatchTimer != null) tvWatchTimer.setText(formatElapsed(elapsed));
                handler.postDelayed(this, 1_000L);
            }
        }
    };

    private final Runnable storageRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStorage();
            handler.postDelayed(this, STORAGE_REFRESH_INTERVAL_MS);
        }
    };

    // ── Broadcast receiver ─────────────────────────────────────────────────────

    private final BroadcastReceiver recordingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null) return;
            FLog.d(TAG, "Broadcast: " + action);

            switch (action) {
                case Constants.BROADCAST_ON_RECORDING_STARTED:
                    handleRecordingStarted();
                    break;
                case Constants.BROADCAST_ON_RECORDING_RESUMED:
                    handleRecordingResumed();
                    break;
                case Constants.BROADCAST_ON_RECORDING_PAUSED:
                    handleRecordingPaused();
                    break;
                case Constants.BROADCAST_ON_RECORDING_STOPPED:
                    handleRecordingStopped();
                    break;
                default:
                    break;
            }
        }
    };

    // ── Fragment lifecycle ─────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_watch_camera, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        prefs = SharedPreferencesManager.getInstance(requireContext());

        watchAvatarToggle  = view.findViewById(R.id.watch_avatar_toggle);
        tvWatchStorage     = view.findViewById(R.id.tv_watch_storage);
        tvWatchTimer       = view.findViewById(R.id.tv_watch_timer);
        ivWatchAudioToggle = view.findViewById(R.id.iv_watch_audio_toggle);
        tvWatchAudioLabel  = view.findViewById(R.id.tv_watch_audio_label);
        ivWatchPauseResume = view.findViewById(R.id.iv_watch_pause_resume);
        tvWatchPauseLabel  = view.findViewById(R.id.tv_watch_pause_label);
        ivWatchFullscreen  = view.findViewById(R.id.iv_watch_fullscreen);
        ivWatchFadShot     = view.findViewById(R.id.iv_watch_fadshot);

        // Hamburger opens HomeSidebarFragment (sidebar with preview controls, tips, etc.)
        View hamburger = view.findViewById(R.id.btn_watch_hamburger);
        if (hamburger != null) {
            hamburger.setOnClickListener(v ->
                    HomeSidebarFragment.newInstance().show(getChildFragmentManager(), "watch_sidebar"));
        }

        // FadCam logo click → open PrivacyBlackActivity if privacy mode is enabled
        // long press → open WatchTrashFragment overlay (watch-optimised delete screen)
        View appTitle = view.findViewById(R.id.iv_watch_app_title);
        if (appTitle != null) {
            appTitle.setOnClickListener(v -> {
                try {
                    SharedPreferencesManager sp = SharedPreferencesManager.getInstance(requireContext());
                    if (sp.isPrivacyBlackModeEnabled()) {
                        android.content.Intent intent = new android.content.Intent(
                                requireContext(), com.fadcam.ui.PrivacyBlackActivity.class);
                        startActivity(intent);
                    } else {
                        android.widget.Toast.makeText(requireContext(),
                                R.string.privacy_black_enable_needed_hint,
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    FLog.w(TAG, "Privacy Black launch failed", e);
                }
            });
            appTitle.setOnLongClickListener(v -> {
                try {
                    OverlayNavUtil.show(requireActivity(), new WatchTrashFragment(), "watch_trash");
                } catch (Exception e) {
                    FLog.w(TAG, "Open trash failed", e);
                }
                return true;
            });
        }

        // Central avatar tap = record / stop
        watchAvatarToggle.setOnCheckedChangeListener((view1, isChecked) -> {
            if (isChecked) {
                startRecording();
            } else {
                stopRecording();
            }
        });

        ivWatchAudioToggle.setOnClickListener(v -> toggleAudio());
        if (ivWatchPauseResume != null) ivWatchPauseResume.setOnClickListener(v -> pauseResumeRecording());
        if (ivWatchFullscreen  != null) ivWatchFullscreen.setOnClickListener(v -> toggleFullscreen());
        if (ivWatchFadShot     != null) ivWatchFadShot.setOnClickListener(v -> takeFadShot());

        // Initial UI state
        updateAudioIcon();
        updatePauseResumeIcon();
        refreshStorage();
    }

    @Override
    public void onResume() {
        super.onResume();

        final IntentFilter filter = new IntentFilter();
        filter.addAction(Constants.BROADCAST_ON_RECORDING_STARTED);
        filter.addAction(Constants.BROADCAST_ON_RECORDING_STOPPED);
        filter.addAction(Constants.BROADCAST_ON_RECORDING_PAUSED);
        filter.addAction(Constants.BROADCAST_ON_RECORDING_RESUMED);
        LocalBroadcastManager.getInstance(requireContext())
                .registerReceiver(recordingReceiver, filter);

        // Sync recording state in case we resumed while service is active
        queryRecordingState();

        // Start periodic storage refresh
        handler.post(storageRunnable);
    }

    @Override
    public void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(requireContext())
                .unregisterReceiver(recordingReceiver);
        handler.removeCallbacksAndMessages(null);
    }

    // ── Recording control ──────────────────────────────────────────────────────

    private void startRecording() {
        FLog.d(TAG, "startRecording");
        try {
            final Intent intent = new Intent(requireContext(), RecordingService.class);
            intent.setAction(Constants.INTENT_ACTION_START_RECORDING);
            ServiceStartPolicy.startRecordingAction(requireContext(), intent);
        } catch (Exception e) {
            FLog.e(TAG, "startRecording error", e);
        }
    }

    private void stopRecording() {
        FLog.d(TAG, "stopRecording");
        try {
            final Intent intent = new Intent(requireContext(), RecordingService.class);
            intent.setAction(Constants.INTENT_ACTION_STOP_RECORDING);
            requireContext().startService(intent);
        } catch (Exception e) {
            FLog.e(TAG, "stopRecording error", e);
        }
    }

    private void queryRecordingState() {
        try {
            final Intent q = new Intent(requireContext(), RecordingService.class);
            q.setAction(Constants.BROADCAST_ON_RECORDING_STATE_REQUEST);
            ServiceStartPolicy.startRecordingAction(requireContext(), q);
        } catch (Exception e) {
            FLog.w(TAG, "queryRecordingState: " + e.getMessage());
        }
    }

    // ── Quick actions ──────────────────────────────────────────────────────────

    /**
     * Toggles recording audio on/off and updates the mic icon tint.
     * Takes effect on the next recording session (not live).
     */
    private void toggleAudio() {
        final boolean next = !prefs.isRecordAudioEnabled();
        prefs.setRecordAudioEnabled(next);
        updateAudioIcon();
        FLog.d(TAG, "Audio toggled: enabled=" + next);
    }

    /**
     * Sends a pause or resume intent to RecordingService depending on current state.
     * Has no effect if a recording session is not active.
     */
    private void pauseResumeRecording() {
        if (!isRecording && !isPaused) return;   // nothing to pause/resume
        try {
            final String action = isPaused
                    ? Constants.INTENT_ACTION_RESUME_RECORDING
                    : Constants.INTENT_ACTION_PAUSE_RECORDING;
            final Intent intent = new Intent(requireContext(), RecordingService.class);
            intent.setAction(action);
            requireContext().startService(intent);
            FLog.d(TAG, "pauseResumeRecording: " + action);
        } catch (Exception e) {
            FLog.e(TAG, "pauseResumeRecording error", e);
        }
    }

    /**
     * Captures a still photo:
     * - During recording: sends INTENT_ACTION_CAPTURE_PHOTO to RecordingService.
     * - While idle: launches PhotoCaptureActivity.
     */
    private void takeFadShot() {
        try {
            if (isRecording) {
                final Intent intent = new Intent(requireContext(), RecordingService.class);
                intent.setAction(Constants.INTENT_ACTION_CAPTURE_PHOTO);
                requireContext().startService(intent);
            } else {
                final Intent intent = new Intent(requireContext(),
                        com.fadcam.PhotoCaptureActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                startActivity(intent);
            }
            FLog.d(TAG, "takeFadShot: recording=" + isRecording);
        } catch (Exception e) {
            FLog.e(TAG, "takeFadShot error", e);
        }
    }

    /**
     * Opens WatchFullscreenActivity — a minimal watch-oriented recording status view.
     * Passes the current recording start time so the timer stays in sync.
     */
    private void toggleFullscreen() {
        try {
            final Intent intent = new Intent(requireContext(),
                    WatchFullscreenActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            if (recordingStartTime > 0L) {
                intent.putExtra(WatchFullscreenActivity.EXTRA_RECORDING_START_TIME,
                        recordingStartTime);
            }
            startActivity(intent);
            FLog.d(TAG, "toggleFullscreen → WatchFullscreenActivity");
        } catch (Exception e) {
            FLog.w(TAG, "toggleFullscreen failed: " + e.getMessage());
        }
    }

    // ── UI state helpers ────────────────────────────────────────────────────────

    private void handleRecordingStarted() {
        isRecording = true;
        isPaused    = false;
        recordingStartTime = System.currentTimeMillis();
        applyRecordingUI();
        updatePauseResumeIcon();
        handler.post(timerRunnable);
    }

    private void handleRecordingResumed() {
        isRecording = true;
        isPaused    = false;
        applyRecordingUI();
        updatePauseResumeIcon();
        handler.post(timerRunnable);
    }

    private void handleRecordingPaused() {
        isRecording = true;   // still in a recording session
        isPaused    = true;
        handler.removeCallbacks(timerRunnable);
        applyPausedUI();
        updatePauseResumeIcon();
    }

    private void handleRecordingStopped() {
        isRecording = false;
        isPaused    = false;
        handler.removeCallbacks(timerRunnable);
        applyIdleUI();
        updatePauseResumeIcon();
    }

    private void applyRecordingUI() {
        if (watchAvatarToggle != null) watchAvatarToggle.setChecked(true);
        if (tvWatchTimer != null) tvWatchTimer.setVisibility(View.VISIBLE);
    }

    private void applyPausedUI() {
        if (watchAvatarToggle != null) watchAvatarToggle.setChecked(false);
        if (tvWatchTimer != null) tvWatchTimer.setVisibility(View.VISIBLE);
    }

    private void applyIdleUI() {
        if (watchAvatarToggle != null) watchAvatarToggle.setChecked(false);
        if (tvWatchTimer != null) {
            tvWatchTimer.setVisibility(View.GONE);
            tvWatchTimer.setText("00:00");
        }
    }

    private void updateAudioIcon() {
        if (ivWatchAudioToggle == null) return;
        final boolean audioOn = prefs.isRecordAudioEnabled();
        ivWatchAudioToggle.setImageResource(R.drawable.ic_mic);
        final int iconTint  = audioOn ? 0xFFFFFFFF : 0xFFFF5252;
        ivWatchAudioToggle.setColorFilter(iconTint, android.graphics.PorterDuff.Mode.SRC_IN);
        if (tvWatchAudioLabel != null) {
            tvWatchAudioLabel.setText(audioOn ? R.string.watch_audio_on : R.string.watch_audio_muted);
            tvWatchAudioLabel.setTextColor(iconTint);
        }
    }

    /**
     * Shows the correct pause/play icon for the current recording state.
     * Also dims the icon when recording hasn't started.
     */
    private void updatePauseResumeIcon() {
        if (ivWatchPauseResume == null) return;
        if (!isRecording && !isPaused) {
            // Idle: show pause icon, dimmed
            ivWatchPauseResume.setImageResource(R.drawable.ic_pause);
            ivWatchPauseResume.setColorFilter(0xFF444444, android.graphics.PorterDuff.Mode.SRC_IN);
            if (tvWatchPauseLabel != null) tvWatchPauseLabel.setText(R.string.watch_pause_label);
        } else if (isRecording && !isPaused) {
            // Recording: show pause icon, bright
            ivWatchPauseResume.setImageResource(R.drawable.ic_pause);
            ivWatchPauseResume.setColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
            if (tvWatchPauseLabel != null) tvWatchPauseLabel.setText(R.string.watch_pause_label);
        } else {
            // Paused: show play icon
            ivWatchPauseResume.setImageResource(R.drawable.ic_play);
            ivWatchPauseResume.setColorFilter(0xFFFF5252, android.graphics.PorterDuff.Mode.SRC_IN);
            if (tvWatchPauseLabel != null) tvWatchPauseLabel.setText(R.string.watch_resume_label);
        }
    }

    /** Reads available storage and updates the top storage label. */
    private void refreshStorage() {
        if (tvWatchStorage == null) return;
        try {
            final File path = Environment.getExternalStorageDirectory();
            final long freeBytes = path.getUsableSpace();
            final String text = formatBytes(freeBytes) + " free";
            tvWatchStorage.setText(text);
        } catch (Exception e) {
            FLog.w(TAG, "refreshStorage: " + e.getMessage());
            tvWatchStorage.setText("—");
        }
    }

    // ── Utilities ───────────────────────────────────────────────────────────────

    /** Formats milliseconds to {@code mm:ss} or {@code hh:mm:ss}. */
    @NonNull
    private static String formatElapsed(long ms) {
        final long totalSeconds = ms / 1000L;
        final long s = totalSeconds % 60L;
        final long m = (totalSeconds / 60L) % 60L;
        final long h = totalSeconds / 3600L;
        return (h > 0)
                ? String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
                : String.format(Locale.US, "%02d:%02d", m, s);
    }

    /** Formats byte count to a human-readable GB / MB string. */
    @NonNull
    private static String formatBytes(long bytes) {
        if (bytes >= 1_073_741_824L) {
            return String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0);
        } else if (bytes >= 1_048_576L) {
            return String.format(Locale.US, "%.0f MB", bytes / 1_048_576.0);
        } else {
            return String.format(Locale.US, "%d KB", bytes / 1024L);
        }
    }
}
