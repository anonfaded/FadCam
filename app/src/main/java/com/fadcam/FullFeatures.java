package com.fadcam;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fadcam.ui.VideoItem;

/**
 * Capabilities that only exist in Full builds (src/full). Shared code calls
 * FeatureRegistry.features() and programs against this interface; Lite receives
 * the no-op default, Full receives the real implementation. No reflection at
 * call sites, no duplication of behavior.
 */
public interface FullFeatures {

    boolean hasFaditor();

    @Nullable
    Fragment createFaditorFragment();

    /** Starts Faditor's editor on the given video. */
    boolean launchFaditorEditor(Context ctx, Uri videoUri);

    /** FFprobe duration in ms; -1 when unavailable/failed (caller falls back to MMR). */
    long probeDurationMs(Context ctx, Uri videoUri);

    /** FFprobe metadata provider (Full-only); Lite receives a no-op probe. */
    @androidx.annotation.NonNull
    com.fadcam.videoinfo.VideoInfoProbe createVideoInfoProbe();

    /** TFLite AI object detector (Full-only); null in Lite. */
    @Nullable
    com.fadcam.motion.domain.detector.AiObjectDetector createAiDetector(Context ctx);

    /** OpenCV MOG2 motion detector (Full-only); null in Lite (Lite uses frame-diff by design). */
    @Nullable
    com.fadcam.motion.domain.detector.MotionDetector createOpenCvMotionDetector(Context ctx);

    /**
     * Motion Lab settings screen (Full-only). Returns null in Lite, which has no
     * Motion Lab and must not compile or reference that UI.
     */
    @Nullable
    androidx.fragment.app.Fragment createMotionLabFragment();


    /**
     * True when this build ships the OpenCV motion backend (Full). Lite returns
     * false and uses the lightweight frame-diff detector by design. There is no
     * silent runtime fallback in Full: if OpenCV cannot initialize, the session
     * reports a clear error instead of degrading to a weaker detector.
     */
    boolean hasOpenCvMotionBackend();

    /** Screen-recording home fragment (FadRec mode) — Full-only; null in Lite. */
    @Nullable
    Fragment createFadRecHomeFragment();

    /** Watch remote tab fragment — Full-only (needs streaming); null in Lite. */
    @Nullable
    Fragment createWatchRemoteFragment();

    /** Remote streaming tab fragment — Full-only; null in Lite. */
    @Nullable
    Fragment createRemoteFragment();

    /** Screen-recording settings fragment — Full-only; null in Lite. */
    @Nullable
    Fragment createScreenRecordingSettingsFragment();

    /** Forensic event recorder (Full-only); null in Lite. */
    @Nullable
    com.fadcam.service.ForensicsRecorder createForensicsRecorder(Context ctx);

    /** Forensics Lab tab fragment — Full-only; null in Lite. */
    @Nullable
    Fragment createLabFragment();

    /** Opens the torch tool (Full-only mini app); false in Lite. */
    boolean openTorchTool(FragmentActivity activity);

    /** Opens the QR scanner (Full-only mini app); false in Lite. */
    boolean openQrScanner(Context ctx);

    /** Forensics settings fragment — Full-only; null in Lite. */
    @Nullable
    Fragment createForensicsSettingsFragment();

    /** Shows the forensics evidence sheet (Full-only); false in Lite. */
    boolean showForensicsEvidenceSheet(FragmentActivity activity, @Nullable String className,
                                       @Nullable String eventType, float confidence, long capturedAt,
                                       long timelineMs, @Nullable String sourceLabel,
                                       @Nullable String sourceVideoUri, @Nullable String snapshotUri);

    /** Queues forensic indexing of records (Full-only); no-op in Lite. */
    void enqueueForensicsIndex(Context ctx, java.util.List<com.fadcam.ui.VideoItem> items);
}
