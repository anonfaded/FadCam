package com.fadcam.ui.faditor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.net.Uri;

import com.fadcam.ui.faditor.composition.TimelineCompositionBuilder;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.Timeline;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

import androidx.media3.transformer.Composition;

/**
 * The composition is the single description of the edit used by both preview and export,
 * so its declared duration must equal what the editor shows. These tests pin that.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class TimelineCompositionBuilderTest {

    private static Clip clip(String name, long sourceMs) {
        return new Clip(Uri.parse("file:///tmp/" + name + ".mp4"), sourceMs);
    }

    private static Clip existingClip(String name, long sourceMs) throws Exception {
        File file = File.createTempFile("clip_" + name, ".mp4");
        file.deleteOnExit();
        return new Clip(Uri.fromFile(file), sourceMs);
    }

    @Test
    public void durationSumsTrimmedClips() {
        Timeline timeline = new Timeline();
        timeline.addClip(clip("a", 10_000));
        Clip b = clip("b", 8_000);
        b.setInPointMs(2_000);
        b.setOutPointMs(6_000);
        timeline.addClip(b);

        assertEquals(10_000 + 4_000, TimelineCompositionBuilder.compositionDurationMs(timeline));
    }

    @Test
    public void durationHonoursSpeed() {
        Timeline timeline = new Timeline();
        Clip a = clip("a", 10_000);
        a.setSpeedMultiplier(2f);
        timeline.addClip(a);

        // 10s of source at 2x presents as 5s.
        assertEquals(5_000, TimelineCompositionBuilder.compositionDurationMs(timeline));
    }

    @Test
    public void durationExtendsToALongAudioTrack() {
        Timeline timeline = new Timeline();
        timeline.addClip(clip("a", 10_000));
        AudioClip audio = new AudioClip(Uri.parse("file:///tmp/m.m4a"), 25_000);
        audio.setOffsetMs(0);
        timeline.addAudioClip(audio);

        assertEquals(25_000, TimelineCompositionBuilder.compositionDurationMs(timeline));
    }

    @Test
    public void compositionDurationEqualsProjectDuration() {
        FaditorProject project = new FaditorProject("duration");
        Clip a = clip("a", 10_000);
        a.setSpeedMultiplier(2f);
        project.getTimeline().addClip(a);
        Clip b = clip("b", 8_000);
        b.setInPointMs(2_000);
        b.setOutPointMs(6_000);
        project.getTimeline().addClip(b);
        AudioClip audio = new AudioClip(Uri.parse("file:///tmp/m.m4a"), 20_000);
        audio.setOffsetMs(4_000);
        project.getTimeline().addAudioClip(audio);

        assertEquals(project.getTimeline().getTotalDurationMs(),
                TimelineCompositionBuilder.compositionDurationMs(project.getTimeline()));
        assertEquals(24_000, TimelineCompositionBuilder.compositionDurationMs(
                project.getTimeline()));
    }

    @Test
    public void clipCountMatchesVideoSequenceItemCount() throws Exception {
        FaditorProject project = new FaditorProject("count");
        project.getTimeline().addClip(existingClip("a", 5_000));
        project.getTimeline().addClip(existingClip("b", 5_000));
        project.getTimeline().addClip(existingClip("c", 5_000));

        Composition composition = TimelineCompositionBuilder.build(project);
        assertNotNull(composition);
        assertEquals(1, composition.sequences.size());
        assertEquals(project.getTimeline().getClipCount(),
                composition.sequences.get(0).editedMediaItems.size());
    }

    @Test
    public void audioTrackBecomesASecondSequence() throws Exception {
        FaditorProject project = new FaditorProject("audio");
        project.getTimeline().addClip(existingClip("a", 5_000));
        File audioFile = File.createTempFile("track", ".m4a");
        audioFile.deleteOnExit();
        AudioClip audio = new AudioClip(Uri.fromFile(audioFile), 3_000);
        project.getTimeline().addAudioClip(audio);

        Composition composition = TimelineCompositionBuilder.build(project);
        assertNotNull(composition);
        assertEquals(2, composition.sequences.size());
        assertEquals(1, composition.sequences.get(0).editedMediaItems.size());
    }

    @Test
    public void emptyOrUnplayableProjectYieldsNoComposition() {
        FaditorProject project = new FaditorProject("empty");
        assertEquals(0, TimelineCompositionBuilder.compositionDurationMs(project.getTimeline()));
        // Files do not exist on the test filesystem, so no item survives the check.
        assertEquals(null, TimelineCompositionBuilder.build(project));
    }
}
