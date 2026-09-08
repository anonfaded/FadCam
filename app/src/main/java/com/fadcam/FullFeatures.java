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

    /** Full ffprobe-backed video info sheet. */
    boolean showVideoInfoSheet(FragmentActivity activity, VideoItem videoItem);

    /** TFLite AI object detector (Full-only); null in Lite. */
    @Nullable
    com.fadcam.motion.domain.detector.AiObjectDetector createAiDetector(Context ctx);

    /** OpenCV MOG2 motion detector (Full-only); null in Lite (FrameDiff fallback stays). */
    @Nullable
    com.fadcam.motion.domain.detector.MotionDetector createOpenCvMotionDetector();
}
