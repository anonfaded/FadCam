package com.fadcam.production;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProductionStreamingControllerTest {
    @Test public void acceptsRtmpAndRtmps() {
        assertTrue(ProductionStreamingController.isRtmpServer("rtmp://example.test/live"));
        assertTrue(ProductionStreamingController.isRtmpServer("rtmps://example.test/live"));
    }

    @Test public void rejectsNonRtmpSchemes() {
        assertFalse(ProductionStreamingController.isRtmpServer("https://example.test/live"));
        assertFalse(ProductionStreamingController.isRtmpServer("srt://example.test:9000"));
        assertFalse(ProductionStreamingController.isRtmpServer(""));
        assertFalse(ProductionStreamingController.isRtmpServer(null));
    }
}
