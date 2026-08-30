package com.fadcam.relay;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Adapter boundary for remote Server Room media relay. */
public final class ServerRoomRelayAgent implements MediaTransport {
    public enum Mode { DIRECT, RELAY }

    public interface RelayTransport {
        void connect() throws Exception;
        void disconnect();
        boolean isConnected();
        default void sendMedia(int sequenceNumber, byte[] payload, long durationMs) throws Exception {
            throw new UnsupportedOperationException("relay media transport is not configured");
        }
        default void sendInitializationSegment(byte[] payload) throws Exception {
            throw new UnsupportedOperationException("relay media transport is not configured");
        }
        default RelaySessionController.TunnelRequest poll() throws Exception {
            throw new UnsupportedOperationException("relay tunnel polling is not configured");
        }
        default void respond(RelaySessionController.TunnelResponse response) throws Exception {
            throw new UnsupportedOperationException("relay tunnel response is not configured");
        }
    }

    public interface RelayMediaSink { void onRelayMedia(byte[] payload); }

    public interface RelayRequestHandler {
        RelaySessionController.TunnelResponse handle(RelaySessionController.TunnelRequest request) throws Exception;
    }

    private final RelayTransport transport;
    private final RelayMediaSink mediaSink;
    private final RelayRequestHandler requestHandler;
    private final Runnable relayFailureHandler;
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.DIRECT);
    private volatile boolean polling;
    private Thread pollThread;

    public ServerRoomRelayAgent(RelayTransport transport, RelayMediaSink mediaSink) {
        this(transport, mediaSink, null, null);
    }

    public ServerRoomRelayAgent(RelayTransport transport, RelayMediaSink mediaSink,
                                RelayRequestHandler requestHandler, Runnable relayFailureHandler) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.mediaSink = Objects.requireNonNull(mediaSink, "mediaSink");
        this.requestHandler = requestHandler;
        this.relayFailureHandler = relayFailureHandler;
    }

    public void start() { mode.set(Mode.DIRECT); }

    @Override
    public void connect() throws Exception { enterRelayMode(); }

    @Override
    public void disconnect() { leaveRelayMode(); }

    @Override
    public boolean isConnected() { return mode.get() == Mode.RELAY && transport.isConnected(); }

    @Override
    public void sendInitializationSegment(byte[] payload) throws Exception {
        requirePayload(payload);
        requireRelayConnected();
        // The deployed relay is pull-based: viewers request HLS resources through
        // the tunnel and the phone serves them from its existing local Server Room.
        // Do not create a second media upload path here.
    }

    @Override
    public void sendFragment(int sequenceNumber, byte[] payload, long durationMs) throws Exception {
        requirePayload(payload);
        requireRelayConnected();
        if (sequenceNumber < 1) throw new IllegalArgumentException("sequenceNumber must be positive");
        if (durationMs <= 0) throw new IllegalArgumentException("durationMs must be positive");
        // Pull-based relay: media is fetched from the existing Server Room HTTP
        // surface in response to relay viewer requests, so the fragment is not
        // uploaded a second time.
    }

    public void enterRelayMode() throws Exception {
        transport.connect();
        if (!transport.isConnected()) throw new IllegalStateException("Relay transport did not connect");
        mode.set(Mode.RELAY);
        startPolling();
    }

    public void leaveRelayMode() {
        stopPolling();
        transport.disconnect();
        mode.set(Mode.DIRECT);
    }

    public void deliverRelayMedia(byte[] payload) {
        if (mode.get() != Mode.RELAY) throw new IllegalStateException("Relay media received while not in RELAY mode");
        Objects.requireNonNull(payload, "payload");
        mediaSink.onRelayMedia(payload.clone());
    }

    public Mode getMode() { return mode.get(); }
    public void stop() { disconnect(); }

    private void startPolling() {
        if (requestHandler == null || polling) return;
        polling = true;
        pollThread = new Thread(() -> {
            while (polling && mode.get() == Mode.RELAY) {
                try {
                    RelaySessionController.TunnelRequest request = transport.poll();
                    if (request == null) continue;
                    RelaySessionController.TunnelResponse response = requestHandler.handle(request);
                    transport.respond(response);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception failure) {
                    if (!polling) return;
                    if (relayFailureHandler != null) relayFailureHandler.run();
                    return;
                }
            }
        }, "FadCam-RelayPoll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void stopPolling() {
        polling = false;
        Thread thread = pollThread;
        pollThread = null;
        if (thread != null) thread.interrupt();
    }

    private void requireRelayConnected() {
        if (!isConnected()) throw new IllegalStateException("relay media transport is not connected");
    }
}
