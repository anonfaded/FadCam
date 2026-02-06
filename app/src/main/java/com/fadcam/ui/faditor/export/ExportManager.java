package com.fadcam.ui.faditor.export;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.common.audio.SpeedChangingAudioProcessor;
import androidx.media3.effect.Crop;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.effect.SpeedChangeEffect;
import androidx.media3.common.Effect;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import com.fadcam.Constants;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.ExportSettings;
import com.fadcam.ui.faditor.model.FaditorProject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
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

            // For simple trim (single clip, no effects, normal speed, audio intact) use near-lossless
            boolean isSimpleTrim = project.getTimeline().getClipCount() == 1
                    && project.getTimeline().getClip(0).getSpeedMultiplier() == 1.0f
                    && !project.getTimeline().getClip(0).isAudioMuted()
                    && project.getTimeline().getClip(0).getRotationDegrees() == 0
                    && !project.getTimeline().getClip(0).isFlipHorizontal()
                    && !project.getTimeline().getClip(0).isFlipVertical()
                    && "none".equals(project.getTimeline().getClip(0).getCropPreset());

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
                    String finalPath = outputPath;

                    // If exported to temp for SAF, copy to custom storage now
                    if (pendingSafCopy) {
                        String safResult = copyTempToSaf(outputPath);
                        if (safResult != null) {
                            finalPath = safResult;
                            Log.d(TAG, "Export copied to SAF: " + safResult);
                        } else {
                            Log.e(TAG, "SAF copy failed, file remains at: " + outputPath);
                        }
                        pendingSafCopy = false;
                        safExportFileName = null;
                    }

                    Log.d(TAG, "Export completed: " + finalPath);
                    if (listener != null) {
                        listener.onExportCompleted(finalPath, result);
                    }
                }

                @Override
                public void onError(@NonNull Composition composition,
                                    @NonNull ExportResult result,
                                    @NonNull ExportException exception) {
                    isExporting = false;
                    pendingSafCopy = false;
                    safExportFileName = null;
                    // Clean up temp file on error
                    File tempFile = new File(outputPath);
                    if (tempFile.getParentFile() != null
                            && tempFile.getParentFile().getName().equals("faditor_export")) {
                        tempFile.delete();
                    }
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

            EditedMediaItem.Builder editedBuilder = new EditedMediaItem.Builder(mediaItem);

            // Mute audio if requested
            if (clip.isAudioMuted()) {
                editedBuilder.setRemoveAudio(true);
            }

            // Collect effects
            List<AudioProcessor> audioProcessors = new ArrayList<>();
            List<Effect> videoEffects = new ArrayList<>();

            // Speed change
            float speed = clip.getSpeedMultiplier();
            if (speed != 1.0f) {
                videoEffects.add(new SpeedChangeEffect(speed));
                if (!clip.isAudioMuted()) {
                    SonicAudioProcessor sonicProcessor = new SonicAudioProcessor();
                    sonicProcessor.setSpeed(speed);
                    audioProcessors.add(sonicProcessor);
                }
            }

            // Rotation and/or flip
            int rotation = clip.getRotationDegrees();
            boolean flipH = clip.isFlipHorizontal();
            boolean flipV = clip.isFlipVertical();
            if (rotation != 0 || flipH || flipV) {
                ScaleAndRotateTransformation.Builder transformBuilder =
                        new ScaleAndRotateTransformation.Builder();
                if (rotation != 0) {
                    transformBuilder.setRotationDegrees(rotation);
                }
                float scaleX = flipH ? -1f : 1f;
                float scaleY = flipV ? -1f : 1f;
                if (flipH || flipV) {
                    transformBuilder.setScale(scaleX, scaleY);
                }
                videoEffects.add(transformBuilder.build());
            }

            // Crop preset
            String cropPreset = clip.getCropPreset();
            if (!"none".equals(cropPreset)) {
                float[] cropRect = getCropRect(cropPreset);
                if (cropRect != null) {
                    videoEffects.add(new Crop(
                            cropRect[0], cropRect[1], cropRect[2], cropRect[3]));
                }
            }

            // Apply effects if any
            if (!audioProcessors.isEmpty() || !videoEffects.isEmpty()) {
                editedBuilder.setEffects(new Effects(audioProcessors, videoEffects));
            }

            items.add(editedBuilder.build());
        }

        EditedMediaItemSequence sequence =
                new EditedMediaItemSequence.Builder(items).build();

        return new Composition.Builder(sequence).build();
    }

    /**
     * Returns crop bounds [left, right, bottom, top] for a Crop effect based on
     * the aspect ratio preset, or null if the preset is unknown.
     *
     * @param preset crop preset key
     * @return float array or null
     */
    @Nullable
    private static float[] getCropRect(@NonNull String preset) {
        switch (preset) {
            case "1:1":   return new float[]{-1f, 1f, -1f, 1f};
            case "16:9":  return new float[]{-1f, 1f, -1f, 1f};
            case "9:16":  return new float[]{-0.3125f, 0.3125f, -1f, 1f};
            case "4:3":   return new float[]{-0.833f, 0.833f, -1f, 1f};
            case "3:4":   return new float[]{-0.375f, 0.375f, -1f, 1f};
            case "21:9":  return new float[]{-1f, 1f, -0.643f, 0.643f};
            default:      return null;
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
        String fileName = "Faditor_" + timestamp + "." + Constants.RECORDING_FILE_EXTENSION;

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
            Log.d(TAG, "Custom storage mode: exporting to temp, will copy to SAF");
            return new File(tempDir, fileName).getAbsolutePath();
        } else {
            // Internal mode — same directory as recordings
            File outputDir = new File(
                    context.getExternalFilesDir(null),
                    Constants.RECORDING_DIRECTORY
            );
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            pendingSafCopy = false;
            safExportFileName = null;
            return new File(outputDir, fileName).getAbsolutePath();
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
            Log.e(TAG, "SAF copy: custom storage URI is null");
            return null;
        }

        File tempFile = new File(tempFilePath);
        if (!tempFile.exists()) {
            Log.e(TAG, "SAF copy: temp file does not exist: " + tempFilePath);
            return null;
        }

        try {
            Uri treeUri = Uri.parse(customUriString);
            DocumentFile pickedDir = DocumentFile.fromTreeUri(context, treeUri);
            if (pickedDir == null || !pickedDir.canWrite()) {
                Log.e(TAG, "SAF copy: cannot write to custom directory");
                return null;
            }

            String name = safExportFileName != null ? safExportFileName : tempFile.getName();
            DocumentFile docFile = pickedDir.createFile(
                    "video/" + Constants.RECORDING_FILE_EXTENSION, name);
            if (docFile == null) {
                Log.e(TAG, "SAF copy: failed to create DocumentFile: " + name);
                return null;
            }

            // Stream-copy temp file to SAF
            try (InputStream in = new FileInputStream(tempFile);
                 OutputStream out = context.getContentResolver()
                         .openOutputStream(docFile.getUri())) {
                if (out == null) {
                    Log.e(TAG, "SAF copy: failed to open output stream");
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
                Log.d(TAG, "SAF copy: temp file deleted");
            }

            Log.i(TAG, "SAF copy successful: " + docFile.getUri());
            return name;

        } catch (Exception e) {
            Log.e(TAG, "SAF copy failed", e);
            return null;
        }
    }
}
