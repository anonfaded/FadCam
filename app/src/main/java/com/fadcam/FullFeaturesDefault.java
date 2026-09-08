package com.fadcam;

/**
 * No-op implementation of FullFeatures used by Lite variants (no src/full code
 * compiled in). Every capability reports "absent" so shared code degrades
 * gracefully without null checks at call sites.
 */
public final class FullFeaturesDefault implements FullFeatures {

    @Override
    public boolean hasFaditor() {
        return false;
    }

    @Override
    public androidx.fragment.app.Fragment createFaditorFragment() {
        return null;
    }

    @Override
    public boolean launchFaditorEditor(android.content.Context ctx, android.net.Uri videoUri) {
        return false;
    }

    @Override
    public long probeDurationMs(android.content.Context ctx, android.net.Uri videoUri) {
        return -1;
    }

    @Override
    public boolean showVideoInfoSheet(androidx.fragment.app.FragmentActivity activity,
                                      com.fadcam.ui.VideoItem videoItem) {
        return false;
    }

    @Override
    public com.fadcam.motion.domain.detector.AiObjectDetector createAiDetector(
            android.content.Context ctx) {
        return null;
    }

    @Override
    public com.fadcam.motion.domain.detector.MotionDetector createOpenCvMotionDetector() {
        return null;
    }

    @Override
    public androidx.fragment.app.Fragment createFadRecHomeFragment() {
        return null;
    }

    @Override
    public androidx.fragment.app.Fragment createRemoteFragment() {
        return null;
    }

    @Override
    public androidx.fragment.app.Fragment createScreenRecordingSettingsFragment() {
        return null;
    }

    @Override
    public com.fadcam.service.ForensicsRecorder createForensicsRecorder(android.content.Context ctx) {
        return null;
    }

    @Override
    public androidx.fragment.app.Fragment createLabFragment() {
        return null;
    }

    @Override
    public androidx.fragment.app.Fragment createForensicsSettingsFragment() {
        return null;
    }

    @Override
    public boolean showForensicsEvidenceSheet(androidx.fragment.app.FragmentActivity activity,
                                              String className, String eventType, float confidence,
                                              long capturedAt, long timelineMs, String sourceLabel,
                                              String sourceVideoUri, String snapshotUri) {
        return false;
    }

    @Override
    public void enqueueForensicsIndex(android.content.Context ctx,
                                      java.util.List<com.fadcam.ui.VideoItem> items) {
    }
}
