package com.fadcam.ui.faditor.undo;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.Timeline;

/**
 * Collection of concrete {@link EditAction} implementations for all
 * Faditor editor operations.
 *
 * <p>Each action captures the old and new state so it can be reversed.
 * Actions operate directly on the project model.</p>
 */
public final class EditActions {

    private EditActions() {} // Utility class

    // ═══════════════════════════════════════════════════════════════
    // Video Clip Actions
    // ═══════════════════════════════════════════════════════════════

    /** Trim change (in-point and/or out-point). */
    public static final class TrimAction implements EditAction {
        private final Clip clip;
        private final long oldIn, oldOut, newIn, newOut;

        public TrimAction(@NonNull Clip clip,
                          long oldIn, long oldOut,
                          long newIn, long newOut) {
            this.clip = clip;
            this.oldIn = oldIn;
            this.oldOut = oldOut;
            this.newIn = newIn;
            this.newOut = newOut;
        }

        @Override public void execute() {
            clip.setInPointMs(newIn);
            clip.setOutPointMs(newOut);
        }
        @Override public void undo() {
            clip.setInPointMs(oldIn);
            clip.setOutPointMs(oldOut);
        }
        @NonNull @Override public String getDescription() {
            return "Trim [" + oldIn + "–" + oldOut + "] → [" + newIn + "–" + newOut + "]";
        }
    }

    /** Volume level change. */
    public static final class VolumeAction implements EditAction {
        private final Clip clip;
        private final float oldVolume, newVolume;

        public VolumeAction(@NonNull Clip clip, float oldVolume, float newVolume) {
            this.clip = clip;
            this.oldVolume = oldVolume;
            this.newVolume = newVolume;
        }

        @Override public void execute() { clip.setVolumeLevel(newVolume); }
        @Override public void undo() { clip.setVolumeLevel(oldVolume); }
        @NonNull @Override public String getDescription() {
            return "Volume " + oldVolume + " → " + newVolume;
        }
    }

    /** Audio mute/unmute toggle. */
    public static final class MuteAction implements EditAction {
        private final Clip clip;
        private final boolean oldMuted, newMuted;
        private final float oldVolume, newVolume;

        public MuteAction(@NonNull Clip clip,
                          boolean oldMuted, float oldVolume,
                          boolean newMuted, float newVolume) {
            this.clip = clip;
            this.oldMuted = oldMuted;
            this.oldVolume = oldVolume;
            this.newMuted = newMuted;
            this.newVolume = newVolume;
        }

        @Override public void execute() {
            clip.setAudioMuted(newMuted);
            clip.setVolumeLevel(newVolume);
        }
        @Override public void undo() {
            clip.setAudioMuted(oldMuted);
            clip.setVolumeLevel(oldVolume);
        }
        @NonNull @Override public String getDescription() {
            return "Mute " + oldMuted + " → " + newMuted;
        }
    }

    /** Speed multiplier change. */
    public static final class SpeedAction implements EditAction {
        private final Clip clip;
        private final float oldSpeed, newSpeed;

        public SpeedAction(@NonNull Clip clip, float oldSpeed, float newSpeed) {
            this.clip = clip;
            this.oldSpeed = oldSpeed;
            this.newSpeed = newSpeed;
        }

        @Override public void execute() { clip.setSpeedMultiplier(newSpeed); }
        @Override public void undo() { clip.setSpeedMultiplier(oldSpeed); }
        @NonNull @Override public String getDescription() {
            return "Speed " + oldSpeed + "x → " + newSpeed + "x";
        }
    }

    /** Rotation change. */
    public static final class RotateAction implements EditAction {
        private final Clip clip;
        private final int oldDegrees, newDegrees;

        public RotateAction(@NonNull Clip clip, int oldDegrees, int newDegrees) {
            this.clip = clip;
            this.oldDegrees = oldDegrees;
            this.newDegrees = newDegrees;
        }

        @Override public void execute() { clip.setRotationDegrees(newDegrees); }
        @Override public void undo() { clip.setRotationDegrees(oldDegrees); }
        @NonNull @Override public String getDescription() {
            return "Rotate " + oldDegrees + "° → " + newDegrees + "°";
        }
    }

    /** Horizontal flip change. */
    public static final class FlipHorizontalAction implements EditAction {
        private final Clip clip;
        private final boolean oldFlip, newFlip;

        public FlipHorizontalAction(@NonNull Clip clip,
                                    boolean oldFlip, boolean newFlip) {
            this.clip = clip;
            this.oldFlip = oldFlip;
            this.newFlip = newFlip;
        }

        @Override public void execute() { clip.setFlipHorizontal(newFlip); }
        @Override public void undo() { clip.setFlipHorizontal(oldFlip); }
        @NonNull @Override public String getDescription() {
            return "Flip horizontal " + oldFlip + " \u2192 " + newFlip;
        }
    }

    /** Vertical flip change. */
    public static final class FlipVerticalAction implements EditAction {
        private final Clip clip;
        private final boolean oldFlip, newFlip;

        public FlipVerticalAction(@NonNull Clip clip,
                                  boolean oldFlip, boolean newFlip) {
            this.clip = clip;
            this.oldFlip = oldFlip;
            this.newFlip = newFlip;
        }

        @Override public void execute() { clip.setFlipVertical(newFlip); }
        @Override public void undo() { clip.setFlipVertical(oldFlip); }
        @NonNull @Override public String getDescription() {
            return "Flip vertical " + oldFlip + " \u2192 " + newFlip;
        }
    }

    /** Crop preset / bounds change. */
    public static final class CropAction implements EditAction {
        private final Clip clip;
        private final String oldPreset, newPreset;
        private final float oldL, oldT, oldR, oldB;
        private final float newL, newT, newR, newB;

        public CropAction(@NonNull Clip clip,
                          @NonNull String oldPreset, float oldL, float oldT, float oldR, float oldB,
                          @NonNull String newPreset, float newL, float newT, float newR, float newB) {
            this.clip = clip;
            this.oldPreset = oldPreset;
            this.oldL = oldL; this.oldT = oldT; this.oldR = oldR; this.oldB = oldB;
            this.newPreset = newPreset;
            this.newL = newL; this.newT = newT; this.newR = newR; this.newB = newB;
        }

        @Override public void execute() {
            clip.setCropPreset(newPreset);
            clip.setCustomCropBounds(newL, newT, newR, newB);
            // Override preset back since setCustomCropBounds always sets "custom"
            clip.setCropPreset(newPreset);
        }
        @Override public void undo() {
            clip.setCropPreset(oldPreset);
            clip.setCustomCropBounds(oldL, oldT, oldR, oldB);
            clip.setCropPreset(oldPreset);
        }
        @NonNull @Override public String getDescription() {
            return "Crop " + oldPreset + " → " + newPreset;
        }
    }

    /** Canvas preset change (project-level). */
    public static final class CanvasPresetAction implements EditAction {
        private final FaditorProject project;
        private final String oldPreset, newPreset;

        public CanvasPresetAction(@NonNull FaditorProject project,
                                  @NonNull String oldPreset,
                                  @NonNull String newPreset) {
            this.project = project;
            this.oldPreset = oldPreset;
            this.newPreset = newPreset;
        }

        @Override public void execute() { project.setCanvasPreset(newPreset); }
        @Override public void undo() { project.setCanvasPreset(oldPreset); }
        @NonNull @Override public String getDescription() {
            return "Canvas " + oldPreset + " → " + newPreset;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Audio Clip Actions
    // ═══════════════════════════════════════════════════════════════

    /** Add an audio clip to the timeline. */
    public static final class AddAudioClipAction implements EditAction {
        private final Timeline timeline;
        private final AudioClip audioClip;
        /** Video clip that was auto-muted after extraction (null if N/A). */
        private final Clip autoMutedClip;
        private final boolean prevMuted;
        private final float prevVolume;

        public AddAudioClipAction(@NonNull Timeline timeline,
                                  @NonNull AudioClip audioClip,
                                  @NonNull Clip autoMutedClip,
                                  boolean prevMuted, float prevVolume) {
            this.timeline = timeline;
            this.audioClip = audioClip;
            this.autoMutedClip = autoMutedClip;
            this.prevMuted = prevMuted;
            this.prevVolume = prevVolume;
        }

        @Override public void execute() {
            timeline.addAudioClip(audioClip);
            if (autoMutedClip != null) {
                autoMutedClip.setAudioMuted(true);
                autoMutedClip.setVolumeLevel(0f);
            }
        }
        @Override public void undo() {
            timeline.removeAudioClip(audioClip);
            if (autoMutedClip != null) {
                autoMutedClip.setAudioMuted(prevMuted);
                autoMutedClip.setVolumeLevel(prevVolume);
            }
        }
        @NonNull @Override public String getDescription() {
            return "Add audio: " + audioClip.getLabel();
        }
    }

    /** Remove an audio clip from the timeline. */
    public static final class RemoveAudioClipAction implements EditAction {
        private final Timeline timeline;
        private final AudioClip audioClip;
        private final int index;

        public RemoveAudioClipAction(@NonNull Timeline timeline,
                                     @NonNull AudioClip audioClip,
                                     int index) {
            this.timeline = timeline;
            this.audioClip = audioClip;
            this.index = index;
        }

        @Override public void execute() { timeline.removeAudioClip(audioClip); }
        @Override public void undo() {
            // Re-insert at original position
            if (index >= 0 && index <= timeline.getAudioClipCount()) {
                timeline.getAudioClips(); // just verify
                // Timeline doesn't have addAudioClip(index), so add at end
                timeline.addAudioClip(audioClip);
            } else {
                timeline.addAudioClip(audioClip);
            }
        }
        @NonNull @Override public String getDescription() {
            return "Remove audio: " + audioClip.getLabel();
        }
    }

    /** Audio clip trim change. */
    public static final class AudioTrimAction implements EditAction {
        private final AudioClip clip;
        private final long oldIn, oldOut, newIn, newOut;

        public AudioTrimAction(@NonNull AudioClip clip,
                               long oldIn, long oldOut,
                               long newIn, long newOut) {
            this.clip = clip;
            this.oldIn = oldIn;
            this.oldOut = oldOut;
            this.newIn = newIn;
            this.newOut = newOut;
        }

        @Override public void execute() {
            clip.setInPointMs(newIn);
            clip.setOutPointMs(newOut);
        }
        @Override public void undo() {
            clip.setInPointMs(oldIn);
            clip.setOutPointMs(oldOut);
        }
        @NonNull @Override public String getDescription() {
            return "Audio trim [" + oldIn + "–" + oldOut + "] → [" + newIn + "–" + newOut + "]";
        }
    }

    /** Audio clip volume change. */
    public static final class AudioVolumeAction implements EditAction {
        private final AudioClip clip;
        private final float oldVolume, newVolume;

        public AudioVolumeAction(@NonNull AudioClip clip, float oldVolume, float newVolume) {
            this.clip = clip;
            this.oldVolume = oldVolume;
            this.newVolume = newVolume;
        }

        @Override public void execute() { clip.setVolumeLevel(newVolume); }
        @Override public void undo() { clip.setVolumeLevel(oldVolume); }
        @NonNull @Override public String getDescription() {
            return "Audio volume " + oldVolume + " → " + newVolume;
        }
    }

    /** Audio clip mute toggle. */
    public static final class AudioMuteAction implements EditAction {
        private final AudioClip clip;
        private final boolean oldMuted, newMuted;

        public AudioMuteAction(@NonNull AudioClip clip, boolean oldMuted, boolean newMuted) {
            this.clip = clip;
            this.oldMuted = oldMuted;
            this.newMuted = newMuted;
        }

        @Override public void execute() { clip.setMuted(newMuted); }
        @Override public void undo() { clip.setMuted(oldMuted); }
        @NonNull @Override public String getDescription() {
            return "Audio mute " + oldMuted + " → " + newMuted;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Timeline Structure Actions
    // ═══════════════════════════════════════════════════════════════

    /** Split a video clip into two at a given point. */
    public static final class SplitClipAction implements EditAction {
        private final Timeline timeline;
        private final int originalIndex;
        private final Clip originalClip;
        private final Clip clipA;
        private final Clip clipB;

        public SplitClipAction(@NonNull Timeline timeline, int originalIndex,
                               @NonNull Clip originalClip,
                               @NonNull Clip clipA, @NonNull Clip clipB) {
            this.timeline = timeline;
            this.originalIndex = originalIndex;
            this.originalClip = originalClip;
            this.clipA = clipA;
            this.clipB = clipB;
        }

        @Override public void execute() {
            // Remove original, insert two split clips
            timeline.removeClip(originalIndex);
            timeline.addClip(originalIndex, clipB);
            timeline.addClip(originalIndex, clipA);
        }
        @Override public void undo() {
            // Remove two split clips, re-insert original
            timeline.removeClip(originalIndex + 1);
            timeline.removeClip(originalIndex);
            timeline.addClip(originalIndex, originalClip);
        }
        @NonNull @Override public String getDescription() { return "Split clip"; }
    }

    /** Split an audio clip into two at a given point. */
    public static final class SplitAudioClipAction implements EditAction {
        private final Timeline timeline;
        private final AudioClip originalClip;
        private final AudioClip leftClip;
        private final AudioClip rightClip;
        private final int originalIndex;

        public SplitAudioClipAction(@NonNull Timeline timeline, int originalIndex,
                                    @NonNull AudioClip originalClip,
                                    @NonNull AudioClip leftClip,
                                    @NonNull AudioClip rightClip) {
            this.timeline = timeline;
            this.originalIndex = originalIndex;
            this.originalClip = originalClip;
            this.leftClip = leftClip;
            this.rightClip = rightClip;
        }

        @Override public void execute() {
            timeline.removeAudioClip(originalClip);
            timeline.addAudioClip(leftClip);
            timeline.addAudioClip(rightClip);
        }
        @Override public void undo() {
            timeline.removeAudioClip(rightClip);
            timeline.removeAudioClip(leftClip);
            timeline.addAudioClip(originalClip);
        }
        @NonNull @Override public String getDescription() { return "Split audio clip"; }
    }

    /** Delete a video clip from the timeline. */
    public static final class DeleteClipAction implements EditAction {
        private final Timeline timeline;
        private final Clip clip;
        private final int index;

        public DeleteClipAction(@NonNull Timeline timeline,
                                @NonNull Clip clip, int index) {
            this.timeline = timeline;
            this.clip = clip;
            this.index = index;
        }

        @Override public void execute() { timeline.removeClip(clip); }
        @Override public void undo() {
            if (index >= 0 && index <= timeline.getClipCount()) {
                timeline.addClip(index, clip);
            } else {
                timeline.addClip(clip);
            }
        }
        @NonNull @Override public String getDescription() { return "Delete clip"; }
    }

    /** Delete an audio clip from the timeline. */
    public static final class DeleteAudioClipAction implements EditAction {
        private final Timeline timeline;
        private final AudioClip audioClip;
        private final int index;

        public DeleteAudioClipAction(@NonNull Timeline timeline,
                                     @NonNull AudioClip audioClip, int index) {
            this.timeline = timeline;
            this.audioClip = audioClip;
            this.index = index;
        }

        @Override public void execute() { timeline.removeAudioClip(audioClip); }
        @Override public void undo() {
            if (index >= 0 && index <= timeline.getAudioClipCount()) {
                timeline.addAudioClip(audioClip);
            } else {
                timeline.addAudioClip(audioClip);
            }
        }
        @NonNull @Override public String getDescription() { return "Delete audio clip"; }
    }

    /** Duplicate a clip (insert copy after original). */
    public static final class DuplicateClipAction implements EditAction {
        private final Timeline timeline;
        private final Clip duplicatedClip;
        private final int insertIndex;

        public DuplicateClipAction(@NonNull Timeline timeline,
                                   @NonNull Clip duplicatedClip, int insertIndex) {
            this.timeline = timeline;
            this.duplicatedClip = duplicatedClip;
            this.insertIndex = insertIndex;
        }

        @Override public void execute() { timeline.addClip(insertIndex, duplicatedClip); }
        @Override public void undo() { timeline.removeClip(duplicatedClip); }
        @NonNull @Override public String getDescription() { return "Duplicate clip"; }
    }

    /** Add a clip (image or video) to the timeline. */
    public static final class AddClipAction implements EditAction {
        private final Timeline timeline;
        private final Clip clip;
        private final int insertIndex;

        public AddClipAction(@NonNull Timeline timeline,
                             @NonNull Clip clip, int insertIndex) {
            this.timeline = timeline;
            this.clip = clip;
            this.insertIndex = insertIndex;
        }

        @Override public void execute() { timeline.addClip(insertIndex, clip); }
        @Override public void undo() { timeline.removeClip(clip); }
        @NonNull @Override public String getDescription() { return "Add clip"; }
    }

    /** Reorder a clip (move from one position to another). */
    public static final class ReorderClipAction implements EditAction {
        private final Timeline timeline;
        private final int fromIndex, toIndex;

        public ReorderClipAction(@NonNull Timeline timeline,
                                 int fromIndex, int toIndex) {
            this.timeline = timeline;
            this.fromIndex = fromIndex;
            this.toIndex = toIndex;
        }

        @Override public void execute() { timeline.moveClip(fromIndex, toIndex); }
        @Override public void undo() { timeline.moveClip(toIndex, fromIndex); }
        @NonNull @Override public String getDescription() {
            return "Reorder clip " + fromIndex + " → " + toIndex;
        }
    }
}
