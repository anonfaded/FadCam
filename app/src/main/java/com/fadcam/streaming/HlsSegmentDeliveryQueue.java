package com.fadcam.streaming;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;
import java.util.TreeMap;

/**
 * Serializes HLS initialization/media delivery so a media segment can never be
 * intentionally discarded just because init.mp4 has not been acknowledged yet.
 *
 * The queue is transport-agnostic: CloudStreamUploader owns HTTP/auth/retry
 * implementation, while this class owns ordering and lifecycle state.
 */
public final class HlsSegmentDeliveryQueue {
    public interface Transport {
        void uploadInit(@NonNull byte[] initData, @NonNull Completion completion);
        void uploadSegment(int sequence, @NonNull byte[] data, @NonNull Completion completion);
    }

    public interface Completion {
        void success();
        void failure(@NonNull String error);
    }

    private final Object lock = new Object();
    private final Transport transport;
    private final TreeMap<Integer, byte[]> pending = new TreeMap<>();
    private byte[] pendingInit;
    private boolean initAcknowledged;
    private boolean initInFlight;
    private boolean draining;
    private boolean stopped;

    public HlsSegmentDeliveryQueue(@NonNull Transport transport) {
        this.transport = transport;
    }

    public void reset() {
        synchronized (lock) {
            pending.clear();
            pendingInit = null;
            initAcknowledged = false;
            initInFlight = false;
            draining = false;
            stopped = false;
        }
    }

    public void offerInit(@NonNull byte[] initData) {
        synchronized (lock) {
            if (stopped) return;
            pendingInit = initData.clone();
            initAcknowledged = false;
        }
        retryInit();
    }

    /** Queues a media segment; it is never silently dropped. */
    public void offerSegment(int sequence, @NonNull byte[] data) {
        if (sequence < 0) throw new IllegalArgumentException("sequence must be >= 0");
        synchronized (lock) {
            if (stopped) return;
            pending.put(sequence, data.clone());
        }
        drain();
    }

    /** Retries the init upload and then resumes the media queue. */
    public void retryInit() {
        final byte[] initData;
        synchronized (lock) {
            if (stopped || initAcknowledged || initInFlight || pendingInit == null) return;
            initInFlight = true;
            initData = pendingInit.clone();
        }

        transport.uploadInit(initData, new Completion() {
            @Override public void success() {
                synchronized (lock) {
                    initInFlight = false;
                    initAcknowledged = true;
                }
                drain();
            }

            @Override public void failure(@NonNull String error) {
                synchronized (lock) {
                    initInFlight = false;
                    initAcknowledged = false;
                }
            }
        });
    }

    /** Retries the oldest pending segment after a transport failure. */
    public void retryPending() {
        if (!isInitAcknowledged()) {
            retryInit();
            return;
        }
        drain();
    }

    /** Stops delivery and explicitly discards the session-owned queue. */
    public void stop() {
        synchronized (lock) {
            stopped = true;
            pending.clear();
            pendingInit = null;
            initInFlight = false;
            draining = false;
        }
    }

    public int pendingCount() {
        synchronized (lock) {
            return pending.size();
        }
    }

    public boolean isInitAcknowledged() {
        synchronized (lock) {
            return initAcknowledged;
        }
    }

    @Nullable
    public Integer nextPendingSequence() {
        synchronized (lock) {
            return pending.isEmpty() ? null : pending.firstKey();
        }
    }

    private void drain() {
        final int sequence;
        final byte[] data;
        synchronized (lock) {
            if (stopped || !initAcknowledged || draining || pending.isEmpty()) return;
            Map.Entry<Integer, byte[]> entry = pending.firstEntry();
            sequence = entry.getKey();
            data = entry.getValue();
            draining = true;
        }

        transport.uploadSegment(sequence, data, new Completion() {
            @Override public void success() {
                synchronized (lock) {
                    pending.remove(sequence);
                    draining = false;
                }
                drain();
            }

            @Override public void failure(@NonNull String error) {
                synchronized (lock) {
                    draining = false;
                }
            }
        });
    }
}
