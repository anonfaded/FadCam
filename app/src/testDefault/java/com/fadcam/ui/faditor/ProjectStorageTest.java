package com.fadcam.ui.faditor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.net.Uri;

import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.project.ProjectStorage;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class ProjectStorageTest {

    @Test
    public void imageClipFlagSurvivesSaveAndLoad() {
        ProjectStorage storage = new ProjectStorage(RuntimeEnvironment.getApplication());
        FaditorProject project = new FaditorProject("storage");

        Clip video = new Clip(Uri.parse("content://media/video/1"), 8_000);
        Clip image = new Clip(Uri.parse("content://media/image/1"), 5_000);
        image.setImageClip(true);
        image.setAudioMuted(true);
        project.getTimeline().addClip(video);
        project.getTimeline().addClip(image);

        assertTrue(storage.save(project));

        FaditorProject loaded = storage.load(project.getId());
        assertNotNull(loaded);
        assertFalse(loaded.getTimeline().getClip(0).isImageClip());
        assertTrue(loaded.getTimeline().getClip(1).isImageClip());
    }
}
