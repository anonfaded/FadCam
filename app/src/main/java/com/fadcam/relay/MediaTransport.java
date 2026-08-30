package com.fadcam.relay;

import java.util.Objects;

/**
 * Transport boundary for Server Room media.
 *
 * <p>The media producer does not know whether bytes are being delivered by
 * the direct path or the authenticated relay path. TransportController owns
 * that decision.</p>
 */
public interface MediaTransport {

    void connect() throws Exception;

    void disconnect();

    boolean isConnected();

    void sendInitializationSegment(byte[] payload) throws Exception;

    void sendFragment(int sequenceNumber, byte[] payload, long durationMs) throws Exception;

    default void requirePayload(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
    }
}
