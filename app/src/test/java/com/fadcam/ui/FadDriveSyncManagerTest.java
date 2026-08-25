package com.fadcam.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.net.URI;

import org.junit.Test;

public class FadDriveSyncManagerTest {
    @Test
    public void buildRemoteUrlKeepsBasePathAndEncodesFilename() throws Exception {
        assertEquals(
                "https://cloud.example.test/dav/FadCam/My%20show%20%231.mp4",
                FadDriveSyncManager.buildRemoteUrl("https://cloud.example.test/dav", "My show #1.mp4"));
    }

    @Test
    public void buildRemoteUrlCannotEmbedCredentials() {
        assertThrows(IllegalArgumentException.class,
                () -> FadDriveSyncManager.buildRemoteUrl("https://user:pass@cloud.example.test/dav", "a.mp4"));
    }

    @Test
    public void endpointRejectsQueryAndFragment() {
        assertThrows(IllegalArgumentException.class,
                () -> FadDriveSyncManager.validateEndpoint("https://cloud.example.test/dav?token=secret"));
        assertThrows(IllegalArgumentException.class,
                () -> FadDriveSyncManager.validateEndpoint("https://cloud.example.test/dav#fragment"));
    }

    @Test
    public void endpointAcceptsHttpAndHttps() throws Exception {
        URI http = FadDriveSyncManager.validateEndpoint("http://nas.example.test/webdav/");
        URI https = FadDriveSyncManager.validateEndpoint("https://cloud.example.test/webdav/");
        assertEquals("http", http.getScheme());
        assertEquals("https", https.getScheme());
    }
}
