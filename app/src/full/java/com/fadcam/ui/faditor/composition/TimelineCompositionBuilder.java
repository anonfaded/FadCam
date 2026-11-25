package com.fadcam.ui.faditor.composition;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Crop;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.CanvasPickerBottomSheet;
import com.fadcam.ui.faditor.export.VolumeAudioProcessor;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.ExportSettings;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.Timeline;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds a Media3 {@link Composition} from a {@link FaditorProject}.
 *
 * <p>The composition is the single description of the edit: the preview player and the
 * exporter both consume the composition returned here, so framing, crop, rotation, speed,
 * volume and still images cannot drift between what is shown and what is written.</p>
 */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
public final class TimelineCompositionBuilder {

    private static final String TAG = "TimelineCompositionBuilder";

    /** Default frame rate for generated image video streams. */
    private static final int IMAGE_FRAME_RATE = 30;

    /** Source dimensions are stable for a URI; rebuilding the composition should not re-probe. */
    private static final Map<String, int[]> SOURCE_SIZE_CACHE = new ConcurrentHashMap<>();

    private TimelineCompositionBuilder() {
    }

    /** Builds the preview composition for a project. */
    @Nullable
    public static Composition build(@NonNull Context context, @NonNull FaditorProject project) {
        return build(context, project, null, null);
    }

    /**
     * Builds the composition.
     *
     * @param context         used to read source dimensions for canvas and aspect crops
     * @param project         the edit
     * @param exportSettings  optional output resolution; null for preview
     * @param croplessClipId  clip whose crop effect is skipped, used while its crop UI is open
     * @return the composition, or null when the project has no playable clips
     */
    @Nullable
    public static Composition build(@Nullable Context context,
                                    @NonNull FaditorProject project,
                                    @Nullable ExportSettings exportSettings,
                                    @Nullable String croplessClipId) {
        Timeline timeline = project.getTimeline();
        if (timeline.getClipCount() == 0) return null;

        List<EditedMediaItem> videoItems = new ArrayList<>(timeline.getClipCount());
        for (int i = 0; i < timeline.getClipCount(); i++) {
            Clip clip = timeline.getClip(i);
            EditedMediaItem item = buildItem(context, clip, croplessClipId);
            if (item == null) {
                FLog.w(TAG, "Skipping clip " + i + ": no usable source");
                continue;
            }
            videoItems.add(item);
        }
        if (videoItems.isEmpty()) return null;

        List<EditedMediaItemSequence> sequences = new ArrayList<>();
        // Video clips play back to back. Force an audio track so a sequence that starts
        // with a silent clip (image or muted) keeps consistent track structure.
        sequences.add(new EditedMediaItemSequence.Builder(videoItems)
                .experimentalSetForceAudioTrack(true)
                .build());

        if (timeline.hasAudioClips()) {
            EditedMediaItemSequence audioSequence = buildAudioSequence(timeline);
            if (audioSequence != null) sequences.add(audioSequence);
        }

        int[] outputSize = resolveOutputSize(context, project, exportSettings,
                timeline.getClip(0));
        Composition.Builder builder = new Composition.Builder(sequences);
        if (outputSize != null) {
            List<Effect> compositionEffects = new ArrayList<>(1);
            compositionEffects.add(Presentation.createForWidthAndHeight(
                    outputSize[0], outputSize[1], Presentation.LAYOUT_SCALE_TO_FIT));
            builder.setEffects(new Effects(Collections.emptyList(), compositionEffects));
        }

        Composition composition = builder.build();
        return composition;
    }

    /**
     * Legacy entry point without a context. Canvas presets and source-aspect crops need
     * source dimensions, so this form only carries custom crop bounds.
     */
    @Nullable
    public static Composition build(@NonNull FaditorProject project) {
        return build(null, project, null, null);
    }

    /** Output size used by the preview surface: canvas preset or the first source size. */
    @Nullable
    public static int[] previewOutputSize(@NonNull Context context,
                                          @NonNull FaditorProject project) {
        Timeline timeline = project.getTimeline();
        if (timeline.getClipCount() == 0) return null;
        int[] source = sourceSize(context, timeline.getClip(0));
        String preset = project.getCanvasPreset();
        if (source[0] > 0 && source[1] > 0 && !"original".equals(preset)) {
            return CanvasPickerBottomSheet.resolveCanvasDimensions(
                    preset, source[0], source[1]);
        }
        return source[0] > 0 && source[1] > 0 ? source : null;
    }

    /** One timeline clip as an edited media item with an explicit duration. */
    @Nullable
    private static EditedMediaItem buildItem(@Nullable Context context,
                                             @NonNull Clip clip,
                                             @Nullable String croplessClipId) {
        String uri = resolvePlayableUri(clip);
        if (uri == null) return null;

        // Effects retime the item, so the declared source duration is the untrimmed
        // source length; presentation duration is derived from the clipping window.
        long clippedDurationMs = Math.max(1L,
                clip.getOutPointMs() - clip.getInPointMs());
        long durationUs = clip.isImageClip()
                ? clippedDurationMs * 1000L
                : Math.max(clip.getSourceDurationMs(), clip.getOutPointMs()) * 1000L;

        MediaItem.Builder media = new MediaItem.Builder().setUri(uri);
        if (clip.isImageClip()) {
            // Images carry no timestamps, so their display length must be explicit.
            media.setImageDurationMs(clippedDurationMs);
        } else {
            media.setClippingConfiguration(
                    new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(Math.max(0L, clip.getInPointMs()))
                            .setEndPositionMs(Math.max(clip.getInPointMs() + 1L,
                                    clip.getOutPointMs()))
                            .build());
        }

        EditedMediaItem.Builder item = new EditedMediaItem.Builder(media.build())
                .setDurationUs(durationUs);
        if (clip.isImageClip()) {
            item.setFrameRate(IMAGE_FRAME_RATE);
        }

        float speed = clip.getSpeedMultiplier();
        List<AudioProcessor> audioProcessors = new ArrayList<>();
        List<Effect> videoEffects = new ArrayList<>();

        if (Math.abs(speed - 1f) > 0.001f) {
            // This is the composition-safe speed API. CompositionPlayer recognizes the
            // TimestampAdjustment and wraps the item in a SpeedChangingMediaSource, so the
            // player clock, declared duration and seek positions all use the same timebase.
            // SpeedChangeEffect only retimes video and leaves the player position in source time.
            android.util.Pair<AudioProcessor, Effect> speedEffects =
                    Effects.createExperimentalSpeedChangingEffect(
                            new ConstantSpeedProvider(speed));
            audioProcessors.add(speedEffects.first);
            videoEffects.add(speedEffects.second);
        }

        if (clip.isAudioMuted()) {
            // A muted clip must not contribute audio to the composition.
            item.setRemoveAudio(true);
        } else {
            float volume = clip.getVolumeLevel();
            if (Math.abs(volume - 1f) > 0.001f) {
                VolumeAudioProcessor volumeProcessor = new VolumeAudioProcessor();
                volumeProcessor.setVolume(volume);
                audioProcessors.add(volumeProcessor);
            }
        }

        int rotation = clip.getRotationDegrees();
        boolean flipH = clip.isFlipHorizontal();
        boolean flipV = clip.isFlipVertical();
        if (rotation != 0 || flipH || flipV) {
            videoEffects.add(new ScaleAndRotateTransformation.Builder()
                    .setScale(flipH ? -1f : 1f, flipV ? -1f : 1f)
                    .setRotationDegrees(rotation)
                    .build());
        }

        // Crop is expressed against the rotated frame, matching the crop overlay the user
        // drew and the order the exporter has always used.
        if (!clip.getId().equals(croplessClipId)) {
            float[] crop = resolveCrop(clip, context);
            if (crop != null) {
                videoEffects.add(new Crop(crop[0], crop[1], crop[2], crop[3]));
            }
        }

        if (!audioProcessors.isEmpty() || !videoEffects.isEmpty()) {
            item.setEffects(new Effects(audioProcessors, videoEffects));
        }
        return item.build();
    }

    /**
     * Audio-track sequence with silence gaps so each clip starts at its timeline offset.
     * Muted clips become gaps, which keeps the composition as long as the timeline.
     */
    @Nullable
    private static EditedMediaItemSequence buildAudioSequence(@NonNull Timeline timeline) {
        List<AudioClip> clips = new ArrayList<>(timeline.getAudioClips());
        if (clips.isEmpty()) return null;
        Collections.sort(clips, Comparator.comparingLong(AudioClip::getOffsetMs));

        EditedMediaItemSequence.Builder builder =
                new EditedMediaItemSequence.Builder(new ArrayList<>());
        // A leading gap is only valid when the audio track is forced.
        builder.experimentalSetForceAudioTrack(true);

        long cursorUs = 0;
        boolean hasItems = false;
        for (AudioClip ac : clips) {
            String uri = resolvePlayableUri(ac.getSourceUri());
            if (uri == null) continue;

            long startUs = Math.max(0L, ac.getOffsetMs()) * 1000L;
            if (startUs > cursorUs) {
                builder.addGap(startUs - cursorUs);
                hasItems = true;
                cursorUs = startUs;
            }

            long clipDurationUs = Math.max(1L, ac.getTrimmedDurationMs()) * 1000L;
            if (ac.isMuted()) {
                builder.addGap(clipDurationUs);
                hasItems = true;
                cursorUs += clipDurationUs;
                continue;
            }

            MediaItem mediaItem = new MediaItem.Builder()
                    .setUri(uri)
                    .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(Math.max(0L, ac.getInPointMs()))
                            .setEndPositionMs(Math.max(ac.getInPointMs() + 1L,
                                    ac.getOutPointMs()))
                            .build())
                    .build();

            EditedMediaItem.Builder edit = new EditedMediaItem.Builder(mediaItem)
                    .setRemoveVideo(true)
                    .setDurationUs(Math.max(ac.getSourceDurationMs(),
                            ac.getOutPointMs()) * 1000L);

            float volume = ac.getVolumeLevel();
            if (Math.abs(volume - 1f) > 0.001f) {
                VolumeAudioProcessor volumeProcessor = new VolumeAudioProcessor();
                volumeProcessor.setVolume(volume);
                edit.setEffects(new Effects(
                        Collections.singletonList(volumeProcessor), Collections.emptyList()));
            }
            builder.addItem(edit.build());
            hasItems = true;
            cursorUs += clipDurationUs;
        }

        long timelineEndUs = Math.max(0L, timeline.getTotalDurationMs()) * 1000L;
        if (timelineEndUs > cursorUs) {
            builder.addGap(timelineEndUs - cursorUs);
            hasItems = true;
        }

        return hasItems ? builder.build() : null;
    }

    /**
     * Crop rectangle in NDC components {@code [left, right, bottom, top]} for the item.
     * {@code Crop} normalises {@code top}/{@code bottom} from the bottom of the frame.
     */
    @Nullable
    private static float[] resolveCrop(@NonNull Clip clip, @Nullable Context context) {
        String preset = clip.getCropPreset();
        if ("none".equals(preset) || "original".equals(preset)) return null;

        float left;
        float top;
        float right;
        float bottom;

        if ("custom".equals(preset)) {
            left = clip.getCropLeft();
            top = clip.getCropTop();
            right = clip.getCropRight();
            bottom = clip.getCropBottom();
        } else {
            float targetAspect = presetAspect(preset);
            if (targetAspect <= 0f) return null;

            int[] size = context != null ? sourceSize(context, clip) : new int[]{0, 0};
            int width = size[0];
            int height = size[1];
            // The crop applies after rotation, so the source aspect is the rotated one.
            if (clip.getRotationDegrees() == 90 || clip.getRotationDegrees() == 270) {
                int swap = width;
                width = height;
                height = swap;
            }
            if (width <= 0 || height <= 0) return null;

            float sourceAspect = (float) width / height;
            if (sourceAspect > targetAspect) {
                float cropWidth = targetAspect / sourceAspect;
                left = (1f - cropWidth) / 2f;
                right = left + cropWidth;
                top = 0f;
                bottom = 1f;
            } else {
                float cropHeight = sourceAspect / targetAspect;
                top = (1f - cropHeight) / 2f;
                bottom = top + cropHeight;
                left = 0f;
                right = 1f;
            }
        }

        if (right - left < 0.001f || bottom - top < 0.001f) return null;

        float ndcLeft = left * 2f - 1f;
        float ndcRight = right * 2f - 1f;
        float ndcBottom = 1f - bottom * 2f;
        float ndcTop = 1f - top * 2f;
        if (ndcRight <= ndcLeft || ndcTop <= ndcBottom) return null;
        return new float[]{ndcLeft, ndcRight, ndcBottom, ndcTop};
    }

    private static float presetAspect(@NonNull String preset) {
        switch (preset) {
            case "1:1": return 1f;
            case "4:3": return 4f / 3f;
            case "3:4": return 3f / 4f;
            case "16:9": return 16f / 9f;
            case "9:16": return 9f / 16f;
            case "21:9": return 21f / 9f;
            default: return 0f;
        }
    }

    /**
     * Output dimensions: the project canvas preset, overridden by an explicit export
     * resolution while preserving the canvas aspect.
     */
    @Nullable
    private static int[] resolveOutputSize(@Nullable Context context,
                                           @NonNull FaditorProject project,
                                           @Nullable ExportSettings settings,
                                           @NonNull Clip firstClip) {
        if (context == null) return null;
        int[] source = sourceSize(context, firstClip);
        int[] canvas = null;
        String preset = project.getCanvasPreset();
        if (source[0] > 0 && source[1] > 0 && !"original".equals(preset)) {
            canvas = CanvasPickerBottomSheet.resolveCanvasDimensions(
                    preset, source[0], source[1]);
        }

        if (settings != null && settings.getResolution() != ExportSettings.Resolution.ORIGINAL) {
            float aspect;
            if (canvas != null) {
                aspect = (float) canvas[0] / canvas[1];
            } else if (source[0] > 0 && source[1] > 0) {
                aspect = (float) source[0] / source[1];
            } else {
                aspect = 16f / 9f;
            }
            int longSide = resolutionLongSide(settings.getResolution());
            int width;
            int height;
            if (aspect >= 1f) {
                width = longSide;
                height = Math.round(longSide / aspect);
            } else {
                height = longSide;
                width = Math.round(longSide * aspect);
            }
            return new int[]{Math.max(2, width & ~1), Math.max(2, height & ~1)};
        }
        if (canvas != null) return canvas;
        return null;
    }

    private static int resolutionLongSide(@NonNull ExportSettings.Resolution resolution) {
        switch (resolution) {
            case FHD_1080P: return 1920;
            case HD_720P: return 1280;
            case SD_480P: return 854;
            case ORIGINAL:
            default: return 1920;
        }
    }

    /** Display size of a clip's source, cached by URI. */
    private static int[] sourceSize(@NonNull Context context, @NonNull Clip clip) {
        String key = clip.getSourceUri().toString();
        int[] cached = SOURCE_SIZE_CACHE.get(key);
        if (cached != null) return cached;

        int width = 0;
        int height = 0;
        Uri uri = clip.getSourceUri();

        if (clip.isImageClip()) {
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(is, null, options);
                width = options.outWidth;
                height = options.outHeight;
            } catch (Exception e) {
                FLog.w(TAG, "Could not read image size: " + uri, e);
            }
        } else {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                    retriever.setDataSource(uri.getPath());
                } else {
                    retriever.setDataSource(context, uri);
                }
                width = parseInt(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                height = parseInt(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                int rotation = parseInt(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
                if (rotation == 90 || rotation == 270) {
                    int swap = width;
                    width = height;
                    height = swap;
                }
            } catch (Exception e) {
                FLog.w(TAG, "Could not read video size: " + uri, e);
            } finally {
                try {
                    retriever.release();
                } catch (Exception ignored) {
                }
            }
        }

        int[] size = width > 0 && height > 0 ? new int[]{width, height} : new int[]{0, 0};
        if (size[0] > 0) SOURCE_SIZE_CACHE.put(key, size);
        return size;
    }

    private static int parseInt(@Nullable String value) {
        if (value == null) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Source URI to read, or null when a local file no longer exists. */
    @Nullable
    private static String resolvePlayableUri(@NonNull Clip clip) {
        return resolvePlayableUri(clip.getSourceUri());
    }

    @Nullable
    private static String resolvePlayableUri(@NonNull Uri source) {
        String raw = source.toString();
        if (raw.startsWith("file://")) {
            String path = source.getPath();
            if (path == null) return null;
            return new File(path).exists() ? raw : null;
        }
        return raw.isEmpty() ? null : raw;
    }

    /** Total presented duration of the timeline, including an audio tail. */
    public static long compositionDurationMs(@NonNull Timeline timeline) {
        return Math.max(0L, timeline.getTotalDurationMs());
    }

    /** Constant-rate provider for the interlinked speed effect. */
    private static final class ConstantSpeedProvider implements SpeedProvider {
        private final float speed;

        ConstantSpeedProvider(float speed) {
            this.speed = speed;
        }

        @Override
        public float getSpeed(long timeUs) {
            return speed;
        }

        @Override
        public long getNextSpeedChangeTimeUs(long timeUs) {
            return C.TIME_UNSET;
        }
    }
}
