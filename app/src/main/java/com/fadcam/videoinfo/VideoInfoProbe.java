package com.fadcam.videoinfo;

import android.net.Uri;

import androidx.annotation.Nullable;

/**
 * Seam for builds with ffmpeg (Full). Lite returns a no-op probe that
 * yields null, so the shared video info sheet falls back to
 * MediaMetadataRetriever only.
 */
public interface VideoInfoProbe {
    @Nullable
    FfprobeData probe(Uri videoUri);
}
