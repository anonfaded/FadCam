package com.fadcam;

/** Live-streaming mode (shared; used by prefs + recording pipeline). */
public enum StreamingMode {
    STREAM_ONLY,     // Don't save to disk after streaming
    STREAM_AND_SAVE  // Keep recording on disk
}
