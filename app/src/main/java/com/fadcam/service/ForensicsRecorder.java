package com.fadcam.service;

/**
 * Forensic event recording seam (shared). DigitalForensicsEventRecorder in
 * src/full implements this; Lite gets null from the registry and skips recording.
 */
public interface ForensicsRecorder {

    void onDetections(
            String mediaUri,
            long timelineMs,
            java.util.List<com.fadcam.motion.domain.detector.AiObjectDetector.DetectionResult> detections,
            byte[] snapshotJpeg,
            boolean frontCamera,
            int sensorOrientationDegrees,
            String recordingOrientation,
            boolean mirrorHorizontally
    );

    void flush(long timelineMs);

    void onMotionStop(long timelineMs);
}
