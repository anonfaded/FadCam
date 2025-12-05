package com.fadcam.playback;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.source.ClippingMediaSource;
import androidx.media3.exoplayer.source.CompositeSequenceableLoaderFactory;
import androidx.media3.exoplayer.source.DefaultCompositeSequenceableLoaderFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MediaSourceFactory;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;

/**
 * A MediaSourceFactory that creates seekable media sources for fragmented MP4 files.
 * 
 * The problem: ExoPlayer's FragmentedMp4Extractor marks fMP4 files as UNSEEKABLE
 * when they lack sidx (segment index) or mfra (movie fragment random access) boxes.
 * This causes seeking to fail and duration to be reported incorrectly.
 * 
 * The solution: This factory pre-reads the actual duration using MediaMetadataRetriever
 * (which uses FFmpeg-like parsing) and creates a ClippingMediaSource that enforces
 * the correct duration and enables seeking.
 * 
 * This approach is similar to how VLC handles fMP4 files - by scanning the file
 * structure rather than relying on optional index boxes.
 */
@UnstableApi
public class FragmentedMp4SeekableMediaSource implements MediaSourceFactory {

    private static final String TAG = "FMp4SeekableSource";

    private final Context context;
    private final DataSource.Factory dataSourceFactory;
    private final ExtractorsFactory extractorsFactory;
    private final ProgressiveMediaSource.Factory progressiveFactory;

    /**
     * Creates a FragmentedMp4SeekableMediaSource factory.
     *
     * @param context The application context.
     */
    public FragmentedMp4SeekableMediaSource(Context context) {
        this.context = context.getApplicationContext();
        this.dataSourceFactory = new DefaultDataSource.Factory(this.context);
        
        // Configure extractors for fragmented MP4 with workarounds
        this.extractorsFactory = new DefaultExtractorsFactory()
                .setMp4ExtractorFlags(FragmentedMp4Extractor.FLAG_WORKAROUND_EVERY_VIDEO_FRAME_IS_SYNC_FRAME)
                .setFragmentedMp4ExtractorFlags(
                        FragmentedMp4Extractor.FLAG_WORKAROUND_EVERY_VIDEO_FRAME_IS_SYNC_FRAME |
                        FragmentedMp4Extractor.FLAG_ENABLE_EMSG_TRACK);
        
        this.progressiveFactory = new ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory);
    }

    @NonNull
    @Override
    public MediaSource createMediaSource(@NonNull MediaItem mediaItem) {
        Uri uri = mediaItem.localConfiguration != null ? 
                  mediaItem.localConfiguration.uri : null;
        
        if (uri == null) {
            Log.w(TAG, "No URI in MediaItem, using default progressive source");
            return progressiveFactory.createMediaSource(mediaItem);
        }

        // Get actual duration using MediaMetadataRetriever (FFmpeg-like parsing)
        long durationUs = getDurationUs(uri);
        
        if (durationUs > 0) {
            Log.i(TAG, "Creating seekable source for: " + uri.getLastPathSegment() + 
                       ", duration: " + (durationUs / 1000) + "ms");
            
            // Create the base progressive source
            MediaSource baseSource = progressiveFactory.createMediaSource(mediaItem);
            
            // Wrap with ClippingMediaSource to enforce correct duration
            // This makes ExoPlayer treat the media as seekable with known duration
            // startPositionUs = 0, endPositionUs = actual duration
            // enableInitialDiscontinuity = false (smooth start)
            // allowDynamicClippingUpdates = true (handle duration updates)
            // relativeToDefaultPosition = false (use absolute positions)
            return new ClippingMediaSource(
                    baseSource,
                    /* startPositionUs= */ 0,
                    /* endPositionUs= */ durationUs,
                    /* enableInitialDiscontinuity= */ false,
                    /* allowDynamicClippingUpdates= */ true,
                    /* relativeToDefaultPosition= */ false
            );
        } else {
            Log.w(TAG, "Could not determine duration for: " + uri.getLastPathSegment() + 
                       ", falling back to progressive source");
            return progressiveFactory.createMediaSource(mediaItem);
        }
    }

    /**
     * Gets the duration of a media file using MediaMetadataRetriever.
     * This method uses Android's native media scanner which properly parses
     * fragmented MP4 files regardless of whether they have sidx/mfra boxes.
     *
     * @param uri The URI of the media file.
     * @return Duration in microseconds, or C.TIME_UNSET if unavailable.
     */
    private long getDurationUs(Uri uri) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            
            String scheme = uri.getScheme();
            if ("file".equals(scheme)) {
                retriever.setDataSource(uri.getPath());
            } else if ("content".equals(scheme)) {
                retriever.setDataSource(context, uri);
            } else {
                // For other schemes, try direct path
                retriever.setDataSource(uri.toString());
            }
            
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                long durationMs = Long.parseLong(durationStr);
                long durationUs = durationMs * 1000; // Convert ms to us
                Log.d(TAG, "Retrieved duration: " + durationMs + "ms (" + durationUs + "us)");
                return durationUs;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting duration for: " + uri, e);
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing MediaMetadataRetriever", e);
                }
            }
        }
        return C.TIME_UNSET;
    }

    @NonNull
    @Override
    public int[] getSupportedTypes() {
        return new int[]{C.CONTENT_TYPE_OTHER};
    }

    @NonNull
    @Override
    public MediaSourceFactory setDrmSessionManagerProvider(@Nullable DrmSessionManagerProvider provider) {
        progressiveFactory.setDrmSessionManagerProvider(provider);
        return this;
    }

    @NonNull
    @Override
    public MediaSourceFactory setLoadErrorHandlingPolicy(@Nullable LoadErrorHandlingPolicy policy) {
        progressiveFactory.setLoadErrorHandlingPolicy(policy);
        return this;
    }
}
