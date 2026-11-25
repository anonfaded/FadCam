package com.fadcam.ui.faditor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fadcam.ui.faditor.util.FileNameSlug;

import org.junit.Test;

/**
 * The project name is the exported filename, so the transformation has to be exactly
 * predictable — and visible in the sheet before the user commits.
 */
public class FileNameSlugTest {

    @Test
    public void spacesBecomeSingleHyphens() {
        assertEquals("My-Holiday-Clip", FileNameSlug.slugify("My Holiday Clip"));
    }

    @Test
    public void repeatedWhitespaceCollapses() {
        assertEquals("a-b", FileNameSlug.slugify("a    b"));
        assertEquals("a-b", FileNameSlug.slugify("  a \t\n b  "));
    }

    @Test
    public void leadingAndTrailingSeparatorsAreRemoved() {
        assertEquals("name", FileNameSlug.slugify("   name   "));
        assertEquals("name", FileNameSlug.slugify("---name---"));
        assertEquals("name", FileNameSlug.slugify("...name..."));
    }

    @Test
    public void illegalFilesystemCharactersBecomeHyphens() {
        assertEquals("a-b-c-d-e-f-g-h-i",
                FileNameSlug.slugify("a/b\\c:d*e?f\"g<h>i"));
    }

    @Test
    public void hyphenRunsCollapse() {
        assertEquals("a-b", FileNameSlug.slugify("a - - b"));
        assertEquals("a-b", FileNameSlug.slugify("a///b"));
    }

    @Test
    public void unicodeAndEmojiSurvive() {
        assertEquals("Καλημέρα-κόσμε", FileNameSlug.slugify("Καλημέρα κόσμε"));
        assertEquals("视频-2026", FileNameSlug.slugify("视频 2026"));
    }

    @Test
    public void emptyAndSeparatorOnlyNamesYieldNothing() {
        assertEquals("", FileNameSlug.slugify(null));
        assertEquals("", FileNameSlug.slugify(""));
        assertEquals("", FileNameSlug.slugify("   "));
        assertEquals("", FileNameSlug.slugify("///"));
        assertEquals("", FileNameSlug.slugify("..."));
    }

    @Test
    public void longNamesAreCappedWithoutATrailingHyphen() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) sb.append("word ");
        String slug = FileNameSlug.slugify(sb.toString());
        assertTrue("capped", slug.length() <= 64);
        assertTrue("no trailing hyphen", !slug.endsWith("-"));
    }

    @Test
    public void alreadyCleanNamesAreUnchanged() {
        assertEquals("FadCam_20261124_043715",
                FileNameSlug.slugify("FadCam_20261124_043715"));
        assertEquals("MyClip2", FileNameSlug.slugify("MyClip2"));
    }

}
