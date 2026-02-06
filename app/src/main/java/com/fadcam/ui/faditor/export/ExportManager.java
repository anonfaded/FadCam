package com.fadcam.ui.faditor.export;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.ExportSettings;
import com.fadcam.ui.faditor.model.FaditorProject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Orchestrates video export using Media3 Transformer.
 *
 * <p>Handles both near-lossless trim (single clip, no effects) and
 * full re-encode (effects, speed changes, multi-clip).</p>
 */
public class ExportManager {

    private static final String TAG = "ExportManager";

    @NonNull
    private final Context context;

    @Nullable
    private Transformer transformer;

    @Nullable
    private ExportListener listener;

    private boolean isExporting = false;

    /**
     * Callback interface for export progress and completion events.
     */
    public interface ExportListener {
        void onExportStarted(@NonNull String outputPath);
        void onExportProgress(float progress);
        void onExportCompleted(@NonNull String outputPath, @NonNull ExportResult result);
        void onExportError(@NonNull Exception error);
    }

    public ExportManager(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    public void setExportListener(@Nullable ExportListener listener) {
        this.listener = listener;
    }

    public boolean isExporting() {
        return isExporting;
    }

    /**
     * Export the project timeline to a video file.
     *
     * @param project the project to export
     */
    public void export(@NonNull FaditorProject project) {
        if (isExporting) {
            Log.w(TAG, "Export already in progress");
            return;
        }

        if (project.getTimeline().isEmpty()) {
            Log.e(TAG, "Cannot export empty timeline");
            if (listener != null) {
                listener.onExportError(new IllegalStateException("Timeline is empty"));
            }
            return;
        }

        String outputPath = generateOutputPath(project);
        isExporting = true;

        try {
            // Build the Transformer
            Transformer.Builder builder = new Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC);

            // For simple trim (single clip, no effects, normal speed) use near-lossless
            boolean isSimpleTrim = project.getTimeline().getClipCount() == 1
                    && project.getTimeline().getClip(0).getSpeedMultiplier() == 1.0f;

            if (isSimpleTrim) {
                builder.experimentalSetTrimOptimizationEnabled(true);
                Log.d(TAG, "Using near-lossless trim optimization");
            }

            // Add progress listener
            builder.addListener(new Transformer.Listener() {
                @Override
                public void onCompleted(@NonNull Composition composition,
                                        @NonNull ExportResult result) {
                    isExporting = false;
                    Log.d(TAG, "Export completed: " + outputPath);
                    if (listener != null) {
                        listener.onExportCompleted(outputPath, result);
                    }
                }

                @Override
                public void onError(@NonNull Composition composition,
                                    @NonNull ExportResult result,
                                    @NonNull ExportException exception) {
                    isExporting = false;
                    Log.e(TAG, "Export failed", exception);
                    if (listener != null) {
                        listener.onExportError(exception);
                    }
                }
            });

            transformer = builder.build();

            // Build Composition from timeline clips
            Composition composition = buildComposition(project);

            // Start export
            transformer.start(composition, outputPath);

            Log.d(TAG, "Export started → " + outputPath);
            if (listener != null) {
                listener.onExportStarted(outputPath);
            }

        } catch (Exception e) {
            isExporting = false;
            Log.e(TAG, "Failed to start export", e);
            if (listener != null) {
                listener.onExportError(e);
            }
        }
    }

    /**
     * Cancel the current export.
     */
    public void cancel() {
        if (transformer != null && isExporting) {
            transformer.cancel();
            isExporting = false;
            Log.d(TAG, "Export cancelled");
        }
    }

    /**
     * Get current export progress (0.0 – 1.0).
     * Call from a periodic handler to update progress UI.
     */
    public float getProgress() {
        if (transformer == null || !isExporting) return 0f;
        // Transformer progress is polled
        // Requires periodic calling — the Activity handles this via Handler
        return -1f; // Indeterminate; polled in Activity
    }

    // ── Internal ─────────────────────────────────────────────────────

    @NonNull
    private Composition buildComposition(@NonNull FaditorProject project) {
        List<EditedMediaItem> items = new ArrayList<>();

        for (Clip clip : project.getTimeline().getClips()) {
            MediaItem.ClippingConfiguration clipping =
                    new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clip.getInPointMs())
                            .setEndPositionMs(clip.getOutPointMs())
                            .build();

            MediaItem mediaItem = new MediaItem.Builder()
                    .setUri(clip.getSourceUri())
                    .setClippingConfiguration(clipping)
                    .build();

            EditedMediaItem editedItem = new EditedMediaItem.Builder(mediaItem)
                    .build();

            items.add(editedItem);
        }

        EditedMediaItemSequence sequence =
                new EditedMediaItemSequence.Builder(items).build();

        return new Composition.Builder(sequence).build();
    }

    /**
     * Generate an output file path in the FadCam recordings directory.
     */
    @NonNull
    private String generateOutputPath(@NonNull FaditorProject project) {
        File outputDir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "FadCam"
        );
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date());
        String fileName = "Faditor_" + timestamp + ".mp4";

        return new File(outputDir, fileName).getAbsolutePath();
    }
}
