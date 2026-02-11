package com.fadcam.ui.faditor.export;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.common.audio.SpeedChangingAudioProcessor;
import androidx.media3.effect.Crop;
import androidx.media3.effect.Presentation;
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
import com.fadcam.ui.faditor.CanvasPickerBottomSheet;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.ExportSettings;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.utils.RecordingStoragePaths;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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

            // For simple trim (single clip, no effects, normal speed, audio intact,
            // and no audio clips on the audio track) use near-lossless
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
                    && "original".equals(project.getCanvasPreset());

            if (isSimpleTrim) {
                builder.experimentalSetTrimOptimizationEnabled(true);
                Log.d(TAG, "Using near-lossless trim optimization");
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

            // Begin polling for progress (Transformer doesn't push progress via Listener)
            startProgressPolling();

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
            stopProgressPolling();
            transformer.cancel();
            isExporting = false;
            Log.d(TAG, "Export cancelled");
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
                Log.w(TAG, "Progress poll error", e);
                // Keep polling in case of transient errors
                progressHandler.postDelayed(this, PROGRESS_POLL_INTERVAL_MS);
            }
        }
    };

    // ── Internal ─────────────────────────────────────────────────────

    @NonNull
    private Composition buildComposition(@NonNull FaditorProject project) {
        List<EditedMediaItem> items = new ArrayList<>();

        // Resolve canvas dimensions if a canvas preset is active
        String canvasPreset = project.getCanvasPreset();
        int[] canvasDims = null;
        if (!"original".equals(canvasPreset)) {
            // Use first clip's source dimensions as base
            Clip firstClip = project.getTimeline().getClip(0);
            int srcW = firstClip.isImageClip() ? 1080 : getSourceWidth(firstClip);
            int srcH = firstClip.isImageClip() ? 1920 : getSourceHeight(firstClip);
            if (srcW > 0 && srcH > 0) {
                canvasDims = CanvasPickerBottomSheet.resolveCanvasDimensions(
                        canvasPreset, srcW, srcH);
            }
        }

        for (Clip clip : project.getTimeline().getClips()) {
            MediaItem mediaItem;

            if (clip.isImageClip()) {
                // Image clip: set image duration for Transformer to render still frames
                long imageDurationMs = clip.getTrimmedDurationMs();
                mediaItem = new MediaItem.Builder()
                        .setUri(clip.getSourceUri())
                        .setImageDurationMs(imageDurationMs)
                        .build();
            } else {
                // Video clip: apply clipping configuration
                MediaItem.ClippingConfiguration clipping =
                        new MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(clip.getInPointMs())
                                .setEndPositionMs(clip.getOutPointMs())
                                .build();

                mediaItem = new MediaItem.Builder()
                        .setUri(clip.getSourceUri())
                        .setClippingConfiguration(clipping)
                        .build();
            }

            EditedMediaItem.Builder editedBuilder = new EditedMediaItem.Builder(mediaItem);

            // Image clips require frameRate for ImageAssetLoader
            if (clip.isImageClip()) {
                editedBuilder.setFrameRate(30);
            }

            // Mute audio if requested or if image clip (no audio track)
            if (clip.isAudioMuted() || clip.isImageClip()) {
                editedBuilder.setRemoveAudio(true);
            }

            // Collect effects
            List<AudioProcessor> audioProcessors = new ArrayList<>();
            List<Effect> videoEffects = new ArrayList<>();

            // Speed change and/or volume adjustment
            float speed = clip.getSpeedMultiplier();
            float volume = clip.getVolumeLevel();
            if (speed != 1.0f) {
                videoEffects.add(new SpeedChangeEffect(speed));
            }
            // Apply audio processors (speed + volume) when audio is present
            if (!clip.isAudioMuted()) {
                if (speed != 1.0f) {
                    SonicAudioProcessor sonicProcessor = new SonicAudioProcessor();
                    sonicProcessor.setSpeed(speed);
                    audioProcessors.add(sonicProcessor);
                }
                if (Math.abs(volume - 1.0f) >= 0.01f) {
                    VolumeAudioProcessor volumeProcessor = new VolumeAudioProcessor();
                    volumeProcessor.setVolume(volume);
                    audioProcessors.add(volumeProcessor);
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

            // Crop preset or custom crop
            String cropPreset = clip.getCropPreset();
            if ("custom".equals(cropPreset)) {
                // Convert normalised bounds (0-1) to NDC (-1 to 1)
                float left  = clip.getCropLeft()  * 2f - 1f;
                float right = clip.getCropRight() * 2f - 1f;
                float top   = 1f - clip.getCropTop()    * 2f;
                float bottom = 1f - clip.getCropBottom() * 2f;
                videoEffects.add(new Crop(left, right, bottom, top));
            } else if (!"none".equals(cropPreset)) {
                float[] cropRect = getCropRect(cropPreset);
                if (cropRect != null) {
                    videoEffects.add(new Crop(
                            cropRect[0], cropRect[1], cropRect[2], cropRect[3]));
                }
            }

            // Canvas (output resolution / aspect ratio) — must be last video effect
            if (canvasDims != null) {
                videoEffects.add(Presentation.createForWidthAndHeight(
                        canvasDims[0], canvasDims[1],
                        Presentation.LAYOUT_SCALE_TO_FIT));
            }

            // Apply effects if any
            if (!audioProcessors.isEmpty() || !videoEffects.isEmpty()) {
                editedBuilder.setEffects(new Effects(audioProcessors, videoEffects));
            }

            items.add(editedBuilder.build());
        }

        EditedMediaItemSequence videoSequence =
                new EditedMediaItemSequence.Builder(items).build();

        // Build audio sequence from AudioClips on the audio track (if any)
        Timeline timeline = project.getTimeline();
        if (timeline.hasAudioClips()) {
            EditedMediaItemSequence audioSequence = buildAudioSequence(timeline);
            if (audioSequence != null) {
                return new Composition.Builder(videoSequence, audioSequence).build();
            }
        }

        return new Composition.Builder(videoSequence).build();
    }

    /**
     * Build an audio-only {@link EditedMediaItemSequence} from the timeline's
     * {@link AudioClip}s. Silence gaps are inserted so that each clip starts
     * at its correct {@link AudioClip#getOffsetMs()} position.
     *
     * @param timeline the project timeline
     * @return the audio sequence, or null if building failed
     */
    @Nullable
    private EditedMediaItemSequence buildAudioSequence(@NonNull Timeline timeline) {
        List<AudioClip> clips = new ArrayList<>(timeline.getAudioClips());
        if (clips.isEmpty()) return null;

        // Sort by offset so we insert gaps correctly
        Collections.sort(clips, Comparator.comparingLong(AudioClip::getOffsetMs));

        // Generate a reusable silence WAV file in cache
        File silenceFile = getOrCreateSilenceFile();
        if (silenceFile == null) {
            Log.e(TAG, "Failed to create silence file — skipping audio track");
            return null;
        }
        Uri silenceUri = Uri.fromFile(silenceFile);

        List<EditedMediaItem> audioItems = new ArrayList<>();
        long cursorMs = 0; // current position on the timeline

        for (AudioClip ac : clips) {
            if (ac.isMuted()) {
                // Skip muted audio clips entirely
                continue;
            }

            long clipStartMs = ac.getOffsetMs();

            // Insert silence gap if necessary
            if (clipStartMs > cursorMs) {
                long gapMs = clipStartMs - cursorMs;
                EditedMediaItem silenceItem = buildSilenceItem(silenceUri, gapMs);
                audioItems.add(silenceItem);
                cursorMs = clipStartMs;
            }

            // Build the audio clip item with trim & volume
            MediaItem.ClippingConfiguration clipping =
                    new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(ac.getInPointMs())
                            .setEndPositionMs(ac.getOutPointMs())
                            .build();

            MediaItem mediaItem = new MediaItem.Builder()
                    .setUri(ac.getSourceUri())
                    .setClippingConfiguration(clipping)
                    .build();

            EditedMediaItem.Builder editBuilder = new EditedMediaItem.Builder(mediaItem)
                    .setRemoveVideo(true); // audio only

            // Apply volume adjustment if needed
            float volume = ac.getVolumeLevel();
            if (Math.abs(volume - 1.0f) >= 0.01f) {
                VolumeAudioProcessor volumeProcessor = new VolumeAudioProcessor();
                volumeProcessor.setVolume(volume);
                List<AudioProcessor> processors = new ArrayList<>();
                processors.add(volumeProcessor);
                editBuilder.setEffects(new Effects(processors, Collections.emptyList()));
            }

            audioItems.add(editBuilder.build());
            cursorMs = clipStartMs + ac.getTrimmedDurationMs();
        }

        if (audioItems.isEmpty()) {
            Log.d(TAG, "All audio clips muted — no audio sequence");
            return null;
        }

        return new EditedMediaItemSequence.Builder(audioItems).build();
    }

    /**
     * Build an {@link EditedMediaItem} of silence for the given duration.
     * Uses a pre-generated 1-second silent WAV file and clips it to the
     * required duration. For gaps longer than 1 s the file is looped or
     * a longer file is generated.
     */
    @NonNull
    private EditedMediaItem buildSilenceItem(@NonNull Uri silenceUri, long durationMs) {
        // Clip the silence file to the required duration
        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(silenceUri)
                .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(0)
                                .setEndPositionMs(durationMs)
                                .build())
                .build();

        return new EditedMediaItem.Builder(mediaItem)
                .setRemoveVideo(true)
                .build();
    }

    /**
     * Get or create a silent WAV file in the app's cache directory.
     * The file is long enough to cover any reasonable gap (10 minutes).
     * Format: 16-bit PCM mono at 44100 Hz.
     *
     * @return the silence file, or null on failure
     */
    @Nullable
    private File getOrCreateSilenceFile() {
        File cacheDir = new File(context.getCacheDir(), "faditor_export");
        if (!cacheDir.exists()) cacheDir.mkdirs();

        File silenceFile = new File(cacheDir, "silence.wav");
        if (silenceFile.exists() && silenceFile.length() > 44) {
            return silenceFile;
        }

        try {
            // Generate a 10-minute silent WAV (enough for any gap)
            int sampleRate = 44100;
            int channels = 1;
            int bitsPerSample = 16;
            long durationSeconds = 600; // 10 minutes
            long numSamples = sampleRate * durationSeconds;
            long dataSize = numSamples * channels * (bitsPerSample / 8);

            FileOutputStream fos = new FileOutputStream(silenceFile);

            // WAV header (44 bytes)
            ByteBuffer header = ByteBuffer.allocate(44);
            header.order(ByteOrder.LITTLE_ENDIAN);
            // RIFF chunk
            header.put((byte) 'R'); header.put((byte) 'I');
            header.put((byte) 'F'); header.put((byte) 'F');
            header.putInt((int) (36 + dataSize)); // file size - 8
            header.put((byte) 'W'); header.put((byte) 'A');
            header.put((byte) 'V'); header.put((byte) 'E');
            // fmt sub-chunk
            header.put((byte) 'f'); header.put((byte) 'm');
            header.put((byte) 't'); header.put((byte) ' ');
            header.putInt(16);                          // sub-chunk size
            header.putShort((short) 1);                 // PCM format
            header.putShort((short) channels);
            header.putInt(sampleRate);
            header.putInt(sampleRate * channels * bitsPerSample / 8); // byte rate
            header.putShort((short) (channels * bitsPerSample / 8)); // block align
            header.putShort((short) bitsPerSample);
            // data sub-chunk
            header.put((byte) 'd'); header.put((byte) 'a');
            header.put((byte) 't'); header.put((byte) 'a');
            header.putInt((int) dataSize);

            fos.write(header.array());

            // Write silence data in chunks (all zeros = silence)
            byte[] zeroChunk = new byte[8192];
            long remaining = dataSize;
            while (remaining > 0) {
                int toWrite = (int) Math.min(zeroChunk.length, remaining);
                fos.write(zeroChunk, 0, toWrite);
                remaining -= toWrite;
            }

            fos.flush();
            fos.close();

            Log.d(TAG, "Created silence file: " + silenceFile.getAbsolutePath()
                    + " (" + silenceFile.length() + " bytes)");
            return silenceFile;

        } catch (Exception e) {
            Log.e(TAG, "Failed to create silence WAV file", e);
            return null;
        }
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
     * Get the source video width using MediaMetadataRetriever.
     *
     * @param clip the clip to query
     * @return width in pixels, or 0 on failure
     */
    private int getSourceWidth(@NonNull Clip clip) {
        try {
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            r.setDataSource(context, clip.getSourceUri());
            String w = r.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            r.release();
            return w != null ? Integer.parseInt(w) : 0;
        } catch (Exception e) {
            Log.w(TAG, "Failed to get source width", e);
            return 0;
        }
    }

    /**
     * Get the source video height using MediaMetadataRetriever.
     *
     * @param clip the clip to query
     * @return height in pixels, or 0 on failure
     */
    private int getSourceHeight(@NonNull Clip clip) {
        try {
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            r.setDataSource(context, clip.getSourceUri());
            String h = r.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            r.release();
            return h != null ? Integer.parseInt(h) : 0;
        } catch (Exception e) {
            Log.w(TAG, "Failed to get source height", e);
            return 0;
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
            File outputDir = RecordingStoragePaths.getInternalCategoryDir(
                    context,
                    RecordingStoragePaths.Category.FADITOR,
                    true
            );
            if (outputDir == null) {
                // Fallback to app files dir if category dir could not be created.
                outputDir = context.getExternalFilesDir(null);
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
            pickedDir = RecordingStoragePaths.findOrCreateChildDirectory(
                    pickedDir,
                    Constants.RECORDING_SUBDIR_FADITOR,
                    true
            );
            if (pickedDir == null || !pickedDir.canWrite()) {
                Log.e(TAG, "SAF copy: cannot write to Faditor subdirectory");
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
