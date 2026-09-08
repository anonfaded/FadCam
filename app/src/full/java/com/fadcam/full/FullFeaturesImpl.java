package com.fadcam.full;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fadcam.FullFeatures;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.ui.FaditorMiniFragment;
import com.fadcam.ui.RecordsFfmpegOps;
import com.fadcam.ui.VideoInfoBottomSheet;
import com.fadcam.ui.VideoItem;
import com.fadcam.ui.faditor.FaditorEditorActivity;

/**
 * Full-only implementation of FullFeatures (src/full). Loaded once by
 * FeatureRegistry; all calls are direct typed references — no reflection.
 */
public final class FullFeaturesImpl implements FullFeatures {

    @Override
    public boolean hasFaditor() {
        return true;
    }

    @Override
    public Fragment createFaditorFragment() {
        return new FaditorMiniFragment();
    }

    @Override
    public boolean launchFaditorEditor(Context ctx, Uri videoUri) {
        try {
            Intent intent = new Intent(ctx, FaditorEditorActivity.class);
            intent.setData(videoUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(intent);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public long probeDurationMs(Context ctx, Uri videoUri) {
        return RecordsFfmpegOps.probeDurationMs(ctx, videoUri);
    }

    @Override
    public boolean showVideoInfoSheet(FragmentActivity activity, VideoItem videoItem) {
        try {
            VideoInfoBottomSheet bottomSheet = VideoInfoBottomSheet.newInstance(videoItem);
            bottomSheet.show(activity.getSupportFragmentManager(), "video_info_bottom_sheet");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public com.fadcam.motion.domain.detector.AiObjectDetector createAiDetector(Context ctx) {
        try {
            return new com.fadcam.motion.domain.detector.EfficientDetLite1Detector(ctx);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public com.fadcam.motion.domain.detector.MotionDetector createOpenCvMotionDetector() {
        try {
            return new com.fadcam.motion.domain.detector.OpenCvMog2MotionDetector();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public Fragment createFadRecHomeFragment() {
        try {
            return com.fadcam.fadrec.ui.FadRecHomeFragment.newInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public Fragment createScreenRecordingSettingsFragment() {
        try {
            return new com.fadcam.fadrec.ui.ScreenRecordingSettingsFragment();
        } catch (Throwable t) {
            return null;
        }
    }
}
