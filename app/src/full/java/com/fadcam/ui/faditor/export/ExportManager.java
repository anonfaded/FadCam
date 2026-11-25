package com.fadcam.ui.faditor.export;

import com.fadcam.FLog;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import androidx.media3.common.MimeTypes;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import com.fadcam.Constants;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.ui.faditor.composition.TimelineCompositionBuilder;
import com.fadcam.ui.faditor.model.ExportSettings;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.utils.RecordingStoragePaths;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
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

    @NonNull
    private final SharedPreferencesManager prefsManager;

    @Nullable
    private Transformer transformer;

    @Nullable
    private ExportListener listener;

    private boolean isExporting = false;

    /** When true, the export was written to a temp file that needs to be copied to SAF. */
    private boolean pendingSafCopy = false;

    /** The display filename used for the SAF DocumentFile. */
    @Nullable
    private String safExportFileName = null;

    /** Handler for periodic progress polling. */
    private final Handler progressHandler = new Handler(Looper.getMainLooper());

    /** Reusable progress holder to avoid allocation on every poll. */
    private final ProgressHolder progressHolder = new ProgressHolder();

    /** Interval between progress polls (ms). */
    private static final long PROGRESS_POLL_INTERVAL_MS = 300;

    /**
     * Callback interface for export progress and completion events.
     */
    public interface ExportListener {
        void onExportStarted(@NonNull String outputPath);
        void onExportProgress(float progress);
        void onExportCompleted(@NonNull String outputPath, @NonNull ExportResult result);
        void onExportError(@NonNull Exception error);
    }

    public ExportManager(@NonNull Context context,
                         @NonNull SharedPreferencesManager prefsManager) {
        this.context = context.getApplicationContext();
        this.prefsManager = prefsManager;
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
            FLog.w(TAG, "Export already in progress");
            return;
        }

        if (project.getTimeline().isEmpty()) {
            FLog.e(TAG, "Cannot export empty timeline");
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

            // For simple trim (single clip, no effects, normal speed, audio intact,
            // and no audio clips on the audio track) use near-lossless
            ExportSettings settings = project.getExportSettings();
            boolean isSimpleTrim = project.getTimeline().getClipCount() == 1
                    && !project.getTimeline().hasAudioClips()
                    && !project.getTimeline().getClip(0).isImageClip()
                    && project.getTimeline().getClip(0).getSpeedMultiplier() == 1.0f
                    && !project.getTimeline().getClip(0).isAudioMuted()
                    && Math.abs(project.getTimeline().getClip(0).getVolumeLevel() - 1.0f) < 0.01f
                    && project.getTimeline().getClip(0).getRotationDegrees() == 0
                    && !project.getTimeline().getClip(0).isFlipHorizontal()
                    && !project.getTimeline().getClip(0).isFlipVertical()
                    && "none".equals(project.getTimeline().getClip(0).getCropPreset())
                    && "original".equals(project.getCanvasPreset())
                    && settings.getResolution() == ExportSettings.Resolution.ORIGINAL;

            if (isSimpleTrim) {
                builder.experimentalSetTrimOptimizationEnabled(true);
                FLog.d(TAG, "Using near-lossless trim optimization");
            } else {
                builder.setEncoderFactory(new DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder()
                                .setBitrate(qualityBitrate(settings.getQuality()))
                                .build())
                        .build());
            }

            // Add progress listener
            builder.addListener(new Transformer.Listener() {
                @Override
                public void onCompleted(@NonNull Composition composition,
                                        @NonNull ExportResult result) {
                    stopProgressPolling();
                    isExporting = false;
                    String finalPath = outputPath;

                    // If exported to temp for SAF, copy to custom storage now
                    if (pendingSafCopy) {
                        String safResult = copyTempToSaf(outputPath);
                        if (safResult != null) {
                            finalPath = safResult;
                            FLog.d(TAG, "Export copied to SAF: " + safResult);
                        } else {
                            FLog.e(TAG, "SAF copy failed, file remains at: " + outputPath);
                        }
                        pendingSafCopy = false;
                        safExportFileName = null;
                    }

                    FLog.d(TAG, "Export completed: " + finalPath);
                    if (listener != null) {
                        listener.onExportCompleted(finalPath, result);
                    }
                }

                @Override
                public void onError(@NonNull Composition composition,
                                    @NonNull ExportResult result,
                                    @NonNull ExportException exception) {
                    stopProgressPolling();
                    isExporting = false;
                    pendingSafCopy = false;
                    safExportFileName = null;
                    // Clean up temp file on error
                    File tempFile = new File(outputPath);
                    if (tempFile.getParentFile() != null
                            && tempFile.getParentFile().getName().equals("faditor_export")) {
                        tempFile.delete();
                    }
                    FLog.e(TAG, "Export failed", exception);
                    if (listener != null) {
                        listener.onExportError(exception);
                    }
                }
            });

            transformer = builder.build();

            // The exporter and the preview play the same composition.
            Composition composition = TimelineCompositionBuilder.build(
                    context, project, settings, null);
            if (composition == null) {
                throw new IllegalStateException("Composition could not be built");
            }

            // Start export
            transformer.start(composition, outputPath);

            // Begin polling for progress (Transformer doesn't push progress via Listener)
            startProgressPolling();

            FLog.d(TAG, "Export started → " + outputPath);
            if (listener != null) {
                listener.onExportStarted(outputPath);
            }

        } catch (Exception e) {
            isExporting = false;
            FLog.e(TAG, "Failed to start export", e);
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
            stopProgressPolling();
            transformer.cancel();
            isExporting = false;
            FLog.d(TAG, "Export cancelled");
        }
    }

    /**
     * Start periodic progress polling using Transformer.getProgress().
     * Media3 Transformer does not push progress via its Listener; it must be polled.
     */
    private void startProgressPolling() {
        progressHandler.removeCallbacksAndMessages(null);
        progressHandler.postDelayed(progressPoller, PROGRESS_POLL_INTERVAL_MS);
    }

    /** Stop polling for progress. */
    private void stopProgressPolling() {
        progressHandler.removeCallbacksAndMessages(null);
    }

    /** Runnable that periodically polls Transformer progress and forwards to listener. */
    private final Runnable progressPoller = new Runnable() {
        @Override
        public void run() {
            if (transformer == null || !isExporting) return;
            try {
                int state = transformer.getProgress(progressHolder);
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    float progress = progressHolder.progress / 100f;
                    if (listener != null) {
                        listener.onExportProgress(progress);
                    }
                }
                // Continue polling regardless of state (may become available later)
                progressHandler.postDelayed(this, PROGRESS_POLL_INTERVAL_MS);
            } catch (Exception e) {
                FLog.w(TAG, "Progress poll error", e);
                // Keep polling in case of transient errors
                progressHandler.postDelayed(this, PROGRESS_POLL_INTERVAL_MS);
            }
        }
    };

    /** Video bitrate for the chosen export quality. */
    private static int qualityBitrate(@NonNull ExportSettings.Quality quality) {
        switch (quality) {
            case MEDIUM: return 6_000_000;
            case LOW: return 3_000_000;
            case HIGH:
            default: return 12_000_000;
        }
    }

    /**
     * Generate an output file path respecting the user's storage preference.
     *
     * <p>Internal mode: writes to the app-private FadCam directory
     * (same location as recordings).</p>
     * <p>Custom/SAF mode: writes to a temporary cache file; on export
     * completion the file is copied to the SAF directory.</p>
     */
    @NonNull
    private String generateOutputPath(@NonNull FaditorProject project) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date());
        // The project name is user-editable in the editor header, and that name is
        // what the exported file carries. Only fall back to a timestamp when the name
        // is empty or sanitises to nothing.
        String baseName = com.fadcam.ui.faditor.util.FileNameSlug.slugify(project.getName());
        if (baseName.isEmpty()) {
            baseName = "Faditor_" + timestamp;
        }
        String fileName = baseName + "." + Constants.RECORDING_FILE_EXTENSION;

        String storageMode = prefsManager.getStorageMode();

        if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode)) {
            // SAF/Custom mode — Transformer only accepts file paths, so write to
            // a temp location first; onCompleted will copy to SAF.
            File tempDir = new File(context.getCacheDir(), "faditor_export");
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }
            pendingSafCopy = true;
            safExportFileName = fileName;
            FLog.d(TAG, "Custom storage mode: exporting to temp, will copy to SAF");
            return new File(tempDir, fileName).getAbsolutePath();
        } else {
            // Internal mode — same directory as recordings
            File outputDir = RecordingStoragePaths.getInternalCategoryDir(
                    context,
                    RecordingStoragePaths.Category.FADITOR,
                    true
            );
            if (outputDir == null) {
                // Use the app files directory when the category directory is unavailable.
                outputDir = context.getExternalFilesDir(null);
            }
            pendingSafCopy = false;
            safExportFileName = null;
            // Re-exporting the same project must not clobber the previous file (and
            // Transformer refuses to write over an existing output anyway).
            File target = new File(outputDir, fileName);
            if (target.exists()) {
                fileName = baseName + "_" + timestamp + "."
                        + Constants.RECORDING_FILE_EXTENSION;
                target = new File(outputDir, fileName);
            }
            return target.getAbsolutePath();
        }
    }

    /**
     * Copy a temp export file to the user's custom SAF storage location.
     *
     * @param tempFilePath path to the temporary export file
     * @return the SAF display name on success, or null on failure
     */
    @Nullable
    private String copyTempToSaf(@NonNull String tempFilePath) {
        String customUriString = prefsManager.getCustomStorageUri();
        if (customUriString == null) {
            FLog.e(TAG, "SAF copy: custom storage URI is null");
            return null;
        }

        File tempFile = new File(tempFilePath);
        if (!tempFile.exists()) {
            FLog.e(TAG, "SAF copy: temp file does not exist: " + tempFilePath);
            return null;
        }

        try {
            Uri treeUri = Uri.parse(customUriString);
            DocumentFile pickedDir = DocumentFile.fromTreeUri(context, treeUri);
            if (pickedDir == null || !pickedDir.canWrite()) {
                FLog.e(TAG, "SAF copy: cannot write to custom directory");
                return null;
            }
            pickedDir = RecordingStoragePaths.findOrCreateChildDirectory(
                    pickedDir,
                    Constants.RECORDING_SUBDIR_FADITOR,
                    true
            );
            if (pickedDir == null || !pickedDir.canWrite()) {
                FLog.e(TAG, "SAF copy: cannot write to Faditor subdirectory");
                return null;
            }

            String name = safExportFileName != null ? safExportFileName : tempFile.getName();
            DocumentFile docFile = pickedDir.createFile(
                    "video/" + Constants.RECORDING_FILE_EXTENSION, name);
            if (docFile == null) {
                FLog.e(TAG, "SAF copy: failed to create DocumentFile: " + name);
                return null;
            }

            // Stream-copy temp file to SAF
            try (InputStream in = new FileInputStream(tempFile);
                 OutputStream out = context.getContentResolver()
                         .openOutputStream(docFile.getUri())) {
                if (out == null) {
                    FLog.e(TAG, "SAF copy: failed to open output stream");
                    return null;
                }
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                out.flush();
            }

            // Clean up temp file after successful copy
            if (tempFile.delete()) {
                FLog.d(TAG, "SAF copy: temp file deleted");
            }

            FLog.i(TAG, "SAF copy successful: " + docFile.getUri());
            return name;

        } catch (Exception e) {
            FLog.e(TAG, "SAF copy failed", e);
            return null;
        }
    }
}
