package com.fadcam.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BrandingManagerTest {
    @Test
    public void emptyNameFallsBackToFadCam() {
        assertEquals("FadCam", BrandingManager.sanitizeName(""));
    }

    @Test
    public void controlCharactersAreRemoved() {
        assertEquals("Studio Cam", BrandingManager.sanitizeName("Studio\nCam\t"));
    }

    @Test
    public void nameIsTrimmedToSafeLength() {
        String value = "1234567890123456789012345678901234567890";
        assertEquals(32, BrandingManager.sanitizeName(value).length());
    }
}
