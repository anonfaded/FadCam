package com.fadcam.audio;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import com.fadcam.FLog;

public class NoiseMonitor {
    private static final String TAG = "NoiseMonitor";
    private static NoiseMonitor instance;

    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private AudioRecord audioRecord = null;
    private boolean isRecording = false;
    private Thread recordingThread = null;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private double currentDb = 0f;
    private double maxDb = 120f;
    private int logCounter = 0;

    private static final double REF_AMPLITUDE = 32767.0;
    private static final double DB_REFERENCE = 90.0;

    private NoiseMonitor() {
        FLog.d(TAG, "NoiseMonitor initialized");
    }

    public static synchronized NoiseMonitor getInstance() {
        if (instance == null) {
            instance = new NoiseMonitor();
        }
        return instance;
    }

    public boolean start(Context context) {
        if (isRecording) {
            return true;
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            FLog.w(TAG, "RECORD_AUDIO permission not granted");
            return false;
        }

        try {
            int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                FLog.e(TAG, "Invalid buffer size");
                return false;
            }

            bufferSize = Math.max(bufferSize, SAMPLE_RATE / 10);

            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                FLog.e(TAG, "AudioRecord not initialized");
                release();
                return false;
            }

            audioRecord.startRecording();
            isRecording = true;

            final int localBufferSize = bufferSize;
            recordingThread = new Thread(() -> {
                short[] buffer = new short[localBufferSize];
                while (isRecording) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        double sum = 0;
                        for (int i = 0; i < read; i++) {
                            sum += Math.abs(buffer[i]);
                        }
                        double average = sum / read;
                        double db = DB_REFERENCE + 20 * Math.log10(average / REF_AMPLITUDE);
                        currentDb = Math.max(0, Math.min(maxDb, db));
                        logCounter++;
                        if (logCounter % 50 == 0) {
                        // Noise level log removed for performance
                        }
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }, "NoiseMonitorThread");
            recordingThread.start();

            FLog.d(TAG, "NoiseMonitor started, bufferSize=" + localBufferSize);
            return true;

        } catch (Exception e) {
            FLog.e(TAG, "Error starting NoiseMonitor", e);
            release();
            return false;
        }
    }

    public void stop() {
        isRecording = false;
        logCounter = 0;
        if (recordingThread != null) {
            try {
                recordingThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            recordingThread = null;
        }
        release();
        FLog.d(TAG, "NoiseMonitor stopped");
    }

    private void release() {
        if (audioRecord != null) {
            try {
                if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception e) {
                FLog.e(TAG, "Error releasing AudioRecord", e);
            }
            audioRecord = null;
        }
    }

    public double getCurrentDb() {
        return currentDb;
    }

    public String getReadableDb() {
        return String.format("%.0fdB", currentDb);
    }

    public boolean isRunning() {
        return isRecording;
    }

    public static synchronized void resetInstance() {
        if (instance != null) {
            instance.stop();
            instance = null;
        }
    }
}