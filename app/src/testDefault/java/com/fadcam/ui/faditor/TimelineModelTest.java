package com.fadcam.ui.faditor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.net.Uri;

import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.Timeline;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Guards the operations the editor toolbar drives: delete, duplicate, split and trim.
 *
 * <p>Each test pins the contract the UI relies on — notably that deleting or trimming
 * addresses the clip at the index the user sees highlighted, never a neighbour.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class TimelineModelTest {

    private static Clip clip(String name, long sourceMs) {
        return new Clip(Uri.parse("file:///tmp/" + name + ".mp4"), sourceMs);
    }

    private static Timeline timelineOf(int count, long sourceMs) {
        Timeline timeline = new Timeline();
        for (int i = 0; i < count; i++) {
            timeline.addClip(clip("clip" + i, sourceMs));
        }
        return timeline;
    }

    // ── Delete ───────────────────────────────────────────────────────

    @Test
    public void removeClipAtMiddleLeavesNeighboursIntact() {
        Timeline timeline = timelineOf(3, 10_000);
        Clip first = timeline.getClip(0);
        Clip third = timeline.getClip(2);

        timeline.removeClip(1);

        assertEquals(2, timeline.getClipCount());
        assertSame(first, timeline.getClip(0));
        assertSame(third, timeline.getClip(1));
    }

    @Test
    public void removeClipIgnoresOutOfBoundsInsteadOfThrowing() {
        Timeline timeline = timelineOf(2, 5_000);
        timeline.removeClip(-1);
        timeline.removeClip(99);
        assertEquals(2, timeline.getClipCount());
    }

    /** The exact regression users hit: delete must hit the highlighted index only. */
    @Test
    public void deletingTheHighlightedIndexNeverTouchesAnotherClip() {
        Timeline timeline = timelineOf(4, 8_000);
        Clip target = timeline.getClip(2);
        String targetId = target.getId();

        timeline.removeClip(2);

        assertEquals(3, timeline.getClipCount());
        for (Clip remaining : timeline.getClips()) {
            assertNotEquals("Clip 2 must be the one that disappeared",
                    targetId, remaining.getId());
        }
    }

    // ── Duplicate ────────────────────────────────────────────────────

    @Test
    public void duplicateInsertsCopyRightAfterTheSource() {
        Timeline timeline = timelineOf(3, 7_000);
        Clip source = timeline.getClip(1);

        int newIndex = timeline.duplicateClip(1);

        assertEquals(2, newIndex);
        assertEquals(4, timeline.getClipCount());
        assertSame("source stays in place", source, timeline.getClip(1));
        assertNotSame(source, timeline.getClip(2));
        assertEquals(source.getSourceUri(), timeline.getClip(2).getSourceUri());
        assertEquals(source.getInPointMs(), timeline.getClip(2).getInPointMs());
        assertEquals(source.getOutPointMs(), timeline.getClip(2).getOutPointMs());
    }

    @Test
    public void duplicateCopiesEffectsButNotIdentity() {
        Timeline timeline = timelineOf(1, 9_000);
        Clip source = timeline.getClip(0);
        source.setSpeedMultiplier(2.0f);
        source.setRotationDegrees(90);
        source.setFlipHorizontal(true);
        source.setVolumeLevel(0.4f);

        int newIndex = timeline.duplicateClip(0);
        Clip copy = timeline.getClip(newIndex);

        assertNotEquals(source.getId(), copy.getId());
        assertEquals(2.0f, copy.getSpeedMultiplier(), 0.0001f);
        assertEquals(90, copy.getRotationDegrees());
        assertTrue(copy.isFlipHorizontal());
        assertEquals(0.4f, copy.getVolumeLevel(), 0.0001f);
    }

    /** Trimming the copy must not move the source's trim points. */
    @Test
    public void duplicateIsIndependentOfItsSource() {
        Timeline timeline = timelineOf(1, 9_000);
        Clip source = timeline.getClip(0);
        int newIndex = timeline.duplicateClip(0);

        timeline.getClip(newIndex).setInPointMs(3_000);

        assertEquals(0, source.getInPointMs());
        assertEquals(3_000, timeline.getClip(newIndex).getInPointMs());
        assertEquals(9_000, source.getTrimmedDurationMs());
        assertEquals(6_000, timeline.getClip(newIndex).getTrimmedDurationMs());
    }

    @Test
    public void duplicateRejectsInvalidIndex() {
        Timeline timeline = timelineOf(1, 1_000);
        assertEquals(-1, timeline.duplicateClip(-1));
        assertEquals(-1, timeline.duplicateClip(5));
        assertEquals(1, timeline.getClipCount());
    }

    // ── Split ────────────────────────────────────────────────────────

    @Test
    public void splitProducesTwoContiguousPartsAtTheSplitPoint() {
        Timeline timeline = timelineOf(1, 10_000);
        Clip original = timeline.getClip(0);

        int index = timeline.splitAt(0, 4_000);

        assertEquals(0, index);
        assertEquals(2, timeline.getClipCount());
        Clip a = timeline.getClip(0);
        Clip b = timeline.getClip(1);
        assertEquals(0, a.getInPointMs());
        assertEquals(4_000, a.getOutPointMs());
        assertEquals(4_000, b.getInPointMs());
        assertEquals(10_000, b.getOutPointMs());
        assertEquals(original.getSourceUri(), a.getSourceUri());
        assertEquals(a.getSourceUri(), b.getSourceUri());
    }

    @Test
    public void splitInTheMiddleKeepsClipOrder() {
        Timeline timeline = timelineOf(3, 10_000);
        Clip before = timeline.getClip(0);
        Clip after = timeline.getClip(2);

        timeline.splitAt(1, 5_000);

        assertEquals(4, timeline.getClipCount());
        assertSame(before, timeline.getClip(0));
        assertEquals(5_000, timeline.getClip(1).getOutPointMs());
        assertEquals(5_000, timeline.getClip(2).getInPointMs());
        assertSame(after, timeline.getClip(3));
    }

    @Test
    public void splitRejectsPointsTooCloseToTheEdges() {
        Timeline timeline = timelineOf(1, 10_000);
        assertEquals(-1, timeline.splitAt(0, 0));
        assertEquals(-1, timeline.splitAt(0, 50));
        assertEquals(-1, timeline.splitAt(0, 9_990));
        assertEquals(-1, timeline.splitAt(0, 11_000));
        assertEquals(1, timeline.getClipCount());
    }

    @Test
    public void splitRespectsATrimmedRegion() {
        Timeline timeline = timelineOf(1, 10_000);
        Clip clip = timeline.getClip(0);
        clip.setInPointMs(2_000);
        clip.setOutPointMs(6_000);

        // Outside the trim region must be refused even though it is inside the source.
        assertEquals(-1, timeline.splitAt(0, 1_000));
        assertEquals(-1, timeline.splitAt(0, 7_000));

        assertEquals(0, timeline.splitAt(0, 4_000));
        assertEquals(2_000, timeline.getClip(0).getInPointMs());
        assertEquals(4_000, timeline.getClip(0).getOutPointMs());
        assertEquals(4_000, timeline.getClip(1).getInPointMs());
        assertEquals(6_000, timeline.getClip(1).getOutPointMs());
    }

    // ── Trim ─────────────────────────────────────────────────────────

    @Test
    public void trimPointsAreClampedInsideTheSource() {
        Clip clip = clip("c", 5_000);
        clip.setInPointMs(-500);
        assertEquals(0, clip.getInPointMs());

        clip.setOutPointMs(99_000);
        assertEquals(5_000, clip.getOutPointMs());

        clip.setInPointMs(4_999);
        assertTrue("in-point always stays before the out-point",
                clip.getInPointMs() < clip.getOutPointMs());

        clip.setOutPointMs(0);
        assertTrue(clip.getOutPointMs() > clip.getInPointMs());
    }

    @Test
    public void trimmedDurationHonoursSpeed() {
        Clip clip = clip("c", 10_000);
        clip.setSpeedMultiplier(2.0f);
        assertEquals(5_000, clip.getTrimmedDurationMs());

        clip.setSpeedMultiplier(0.5f);
        assertEquals(20_000, clip.getTrimmedDurationMs());
    }

    @Test
    public void speedIsClampedToAUsableRange() {
        Clip clip = clip("c", 10_000);
        clip.setSpeedMultiplier(0f);
        assertEquals(0.1f, clip.getSpeedMultiplier(), 0.0001f);
        clip.setSpeedMultiplier(99f);
        assertEquals(10.0f, clip.getSpeedMultiplier(), 0.0001f);
    }

    // ── Duration ─────────────────────────────────────────────────────

    @Test
    public void totalDurationSumsTrimmedClips() {
        Timeline timeline = timelineOf(3, 10_000);
        timeline.getClip(1).setInPointMs(2_000);
        timeline.getClip(1).setOutPointMs(6_000);

        assertEquals(10_000 + 4_000 + 10_000, timeline.getTotalDurationMs());
    }

    @Test
    public void totalDurationFollowsSpeedChanges() {
        Timeline timeline = timelineOf(1, 10_000);
        timeline.getClip(0).setSpeedMultiplier(2.0f);
        assertEquals(5_000, timeline.getTotalDurationMs());
    }

    @Test
    public void totalDurationGrowsToTheEndOfALongAudioClip() {
        Timeline timeline = timelineOf(1, 10_000);
        AudioClip audio = new AudioClip(Uri.parse("file:///tmp/a.m4a"), 30_000);
        audio.setOffsetMs(0);
        timeline.addAudioClip(audio);

        assertEquals("audio tail extends the project", 30_000,
                timeline.getTotalDurationMs());
    }

    @Test
    public void totalDurationShrinksWhenTheLongAudioClipIsRemoved() {
        Timeline timeline = timelineOf(1, 10_000);
        AudioClip audio = new AudioClip(Uri.parse("file:///tmp/a.m4a"), 30_000);
        timeline.addAudioClip(audio);
        assertEquals(30_000, timeline.getTotalDurationMs());

        timeline.removeAudioClip(0);
        assertEquals(10_000, timeline.getTotalDurationMs());
    }

    @Test
    public void totalDurationIsRecomputedAfterDeleteAndDuplicate() {
        Timeline timeline = timelineOf(2, 10_000);
        assertEquals(20_000, timeline.getTotalDurationMs());

        timeline.duplicateClip(0);
        assertEquals(30_000, timeline.getTotalDurationMs());

        timeline.removeClip(1);
        assertEquals(20_000, timeline.getTotalDurationMs());
    }

    @Test
    public void emptyTimelineHasNoDuration() {
        Timeline timeline = new Timeline();
        assertEquals(0, timeline.getTotalDurationMs());
        assertTrue(timeline.isEmpty());
    }

    // ── Reorder ──────────────────────────────────────────────────────

    @Test
    public void moveClipReordersWithoutLosingAnyClip() {
        Timeline timeline = timelineOf(3, 10_000);
        Clip a = timeline.getClip(0);
        Clip b = timeline.getClip(1);
        Clip c = timeline.getClip(2);

        timeline.moveClip(0, 2);

        assertSame(b, timeline.getClip(0));
        assertSame(c, timeline.getClip(1));
        assertSame(a, timeline.getClip(2));
        assertEquals(3, timeline.getClipCount());
    }

    @Test
    public void moveClipRejectsInvalidIndexes() {
        Timeline timeline = timelineOf(2, 10_000);
        Clip first = timeline.getClip(0);
        timeline.moveClip(-1, 1);
        timeline.moveClip(0, 9);
        assertSame(first, timeline.getClip(0));
    }

    /**
     * {@code getClip(int)} throws on a bad index; the editor must therefore guard
     * every lookup (it does, through the crash paths that used to swallow deletes).
     */
    @Test
    public void clipLookupRejectsBadIndexes() {
        Timeline timeline = timelineOf(2, 10_000);
        assertNotNull(timeline.getClip(1));
        try {
            timeline.getClip(2);
            throw new AssertionError("Expected an IndexOutOfBoundsException");
        } catch (IndexOutOfBoundsException expected) {
            // contract
        }
    }
}
