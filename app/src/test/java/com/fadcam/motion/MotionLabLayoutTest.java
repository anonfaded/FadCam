package com.fadcam.motion;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guards the Motion Lab layout resource itself: every setting the motion engine
 * reads must have a row the user can tap.
 *
 * <p>This is a plain XML check rather than an inflation test on purpose. Inflating
 * pulls in Robolectric's native font runtime, which races when several Robolectric
 * classes share a JVM. Reading the resource directly is deterministic and still
 * fails if a row is deleted or a whole screen is accidentally reverted.
 */
public class MotionLabLayoutTest {

    private static final String LAYOUT =
            "src/main/res/layout/fragment_settings_motion_lab.xml";

    /** Every preference the engine consumes must be editable from this screen. */
    private static final String[] REQUIRED_IDS = {
            "row_motion_enabled",
            "row_motion_trigger_mode",
            "row_motion_sensitivity",
            "row_motion_analysis_fps",
            "row_motion_analysis_resolution",
            "row_motion_debounce",
            "row_motion_post_roll",
            "row_motion_pre_roll",
            "row_motion_auto_torch",
    };

    private static final String[] REQUIRED_VALUE_IDS = {
            "value_motion_trigger_mode",
            "value_motion_analysis_fps",
            "value_motion_analysis_resolution",
            "value_motion_pre_roll",
            "value_motion_post_roll",
    };

    private static String layout() throws IOException {
        // Gradle unit tests run with the module directory as the working directory.
        File f = new File(LAYOUT);
        if (!f.exists()) {
            f = new File("app/" + LAYOUT);
        }
        assertTrue("Motion Lab layout not found at " + f.getAbsolutePath(), f.exists());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static Set<String> declaredIds(String xml) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher m = Pattern.compile("@\\+id/([A-Za-z0-9_]+)").matcher(xml);
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    @Test
    public void everyEngineSettingHasARow() throws IOException {
        Set<String> ids = declaredIds(layout());
        StringBuilder missing = new StringBuilder();
        for (String id : REQUIRED_IDS) {
            if (!ids.contains(id)) {
                missing.append(' ').append(id);
            }
        }
        assertTrue("Motion Lab is missing rows for:" + missing,
                missing.length() == 0);
    }

    @Test
    public void valueLabelsExist() throws IOException {
        Set<String> ids = declaredIds(layout());
        StringBuilder missing = new StringBuilder();
        for (String id : REQUIRED_VALUE_IDS) {
            if (!ids.contains(id)) {
                missing.append(' ').append(id);
            }
        }
        assertTrue("Motion Lab is missing value labels for:" + missing,
                missing.length() == 0);
    }

    @Test
    public void triggerModeRowIsNotHidden() throws IOException {
        String xml = layout();
        // Find the trigger-mode row element and make sure it does not carry
        // android:visibility="gone" (which would make the setting unreachable).
        int at = xml.indexOf("@+id/row_motion_trigger_mode");
        assertTrue("trigger-mode row must exist", at > 0);
        int blockStart = xml.lastIndexOf('<', at);
        int blockEnd = xml.indexOf('>', at);
        String element = xml.substring(blockStart, blockEnd + 1);
        assertFalse("trigger-mode row must be visible, not gone:\n" + element,
                element.contains("visibility=\"gone\""));
    }

    @Test
    public void noDuplicateIds() throws IOException {
        // Two views with the same id make findViewById return the wrong one.
        String xml = layout();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> dupes = new LinkedHashSet<>();
        Matcher m = Pattern.compile("@\\+id/([A-Za-z0-9_]+)").matcher(xml);
        while (m.find()) {
            if (!seen.add(m.group(1))) {
                dupes.add(m.group(1));
            }
        }
        assertTrue("duplicate ids in Motion Lab layout: " + dupes, dupes.isEmpty());
    }
}
