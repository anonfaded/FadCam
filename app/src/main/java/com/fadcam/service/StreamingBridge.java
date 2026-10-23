package com.fadcam.service;

import com.fadcam.StreamingMode;

/**
 * Live-streaming seam. Full builds implement this via RemoteStreamManager
 * (src/full); Lite uses StreamingBridgeDefault so the per-frame recording
 * checks always read inactive.
 */
public interface StreamingBridge {

    boolean isStreamingEnabled();

    StreamingMode getStreamingMode();

    void invalidateStatusCache();

    void stopRecording();

    void onInitializationSegment(byte[] data);

    void onFragmentComplete(int sequenceNumber, byte[] data, long durationMs);

    void startRecording(java.io.File recordingFile);

    void startRecordingSaf();

    void pauseRecording();

    void resumeRecording();
}
