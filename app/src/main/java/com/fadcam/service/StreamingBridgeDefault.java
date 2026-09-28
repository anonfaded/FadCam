package com.fadcam.service;

import com.fadcam.StreamingMode;

/** Lite no-op: streaming never runs. */
public final class StreamingBridgeDefault implements StreamingBridge {

    @Override
    public boolean isStreamingEnabled() {
        return false;
    }

    @Override
    public StreamingMode getStreamingMode() {
        return StreamingMode.STREAM_AND_SAVE;
    }

    @Override
    public void invalidateStatusCache() {
    }

    @Override
    public void stopRecording() {
    }

    @Override
    public void onInitializationSegment(byte[] data) {
    }

    @Override
    public void onFragmentComplete(int sequenceNumber, byte[] data, long durationMs) {
    }

    @Override
    public void startRecording(java.io.File recordingFile) {
    }

    @Override
    public void startRecordingSaf() {
    }

    @Override
    public void pauseRecording() {
    }

    @Override
    public void resumeRecording() {
    }
}
