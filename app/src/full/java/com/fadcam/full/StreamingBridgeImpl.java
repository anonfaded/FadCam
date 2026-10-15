package com.fadcam.full;

import com.fadcam.StreamingMode;
import com.fadcam.service.StreamingBridge;

/** Full-only streaming bridge: delegates to the RemoteStreamManager singleton. */
public final class StreamingBridgeImpl implements StreamingBridge {

    private static com.fadcam.streaming.RemoteStreamManager manager() {
        return com.fadcam.streaming.RemoteStreamManager.getInstance();
    }

    @Override
    public boolean isStreamingEnabled() {
        return manager() != null && manager().isStreamingEnabled();
    }

    @Override
    public StreamingMode getStreamingMode() {
        com.fadcam.streaming.RemoteStreamManager m = manager();
        return m != null ? m.getStreamingMode() : StreamingMode.STREAM_AND_SAVE;
    }

    @Override
    public void invalidateStatusCache() {
        com.fadcam.streaming.RemoteStreamManager m = manager();
        if (m != null) m.invalidateStatusCache();
    }

    @Override
    public void stopRecording() {
        com.fadcam.streaming.RemoteStreamManager m = manager();
        if (m != null) m.stopRecording();
    }

    @Override
    public void onInitializationSegment(byte[] data) {
        com.fadcam.streaming.RemoteStreamManager m = manager();
        if (m != null) m.onInitializationSegment(data);
    }

    @Override
    public void onFragmentComplete(int sequenceNumber, byte[] data, long durationMs) {
        com.fadcam.streaming.RemoteStreamManager m = manager();
        if (m != null) m.onFragmentComplete(sequenceNumber, data, durationMs);
    }

    @Override
    public void startRecording(java.io.File recordingFile) {
        com.fadcam.streaming.RemoteStreamManager m = manager();
        if (m != null) m.startRecording(recordingFile);
    }

    @Override
    public void startRecordingSaf() {
        com.fadcam.streaming.RemoteStreamManager m = manager();
        if (m != null) m.startRecordingSaf();
    }

    @Override
    public void pauseRecording() {
        com.fadcam.streaming.RemoteStreamManager m = manager();
        if (m != null) m.pauseRecording();
    }

    @Override
    public void resumeRecording() {
        com.fadcam.streaming.RemoteStreamManager m = manager();
        if (m != null) m.resumeRecording();
    }
}
