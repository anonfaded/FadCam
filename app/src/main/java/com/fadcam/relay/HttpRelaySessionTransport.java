package com.fadcam.relay;

import android.util.Base64;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Objects;

/**
 * HTTPS transport for the relay session protocol.
 *
 * <p>The endpoint is configurable so the Android client does not hard-code a
 * deployment. Each request carries the signed session envelope and MEDIA
 * requests carry base64-encoded fMP4 bytes.</p>
 */
public final class HttpRelaySessionTransport implements RelaySessionController.SessionTransport {
    private final URI endpoint;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private volatile boolean connected;

    public HttpRelaySessionTransport(String endpoint, int connectTimeoutMs, int readTimeoutMs) {
        if (endpoint == null || endpoint.trim().isEmpty()) throw new IllegalArgumentException("endpoint must not be empty");
        this.endpoint = URI.create(endpoint);
        if (!"https".equalsIgnoreCase(this.endpoint.getScheme())) {
            throw new IllegalArgumentException("relay session endpoint must use HTTPS");
        }
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) throw new IllegalArgumentException("timeouts must be positive");
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public void connect() {
        connected = true;
    }

    @Override
    public void disconnect() {
        connected = false;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void send(RelaySessionProtocol.Request request) throws Exception {
        Objects.requireNonNull(request, "request");
        if (!connected) throw new IllegalStateException("relay HTTPS transport is not connected");

        JSONObject body = new JSONObject();
        body.put("deviceId", request.getDeviceId());
        body.put("sessionId", request.getSessionId());
        body.put("timestampMillis", request.getTimestampMillis());
        body.put("timeoutMillis", request.getTimeoutMillis());
        body.put("sequence", request.getSequence());
        body.put("authentication", request.getAuthentication());
        body.put("operation", request.getOperation().name());

        if (request.getOperation() == RelaySessionProtocol.Operation.MEDIA) {
            body.put("mediaTimestampMillis", request.getMediaTimestampMillis());
            body.put("mediaDurationMillis", request.getMediaDurationMillis());
            body.put("mediaType", request.getMediaType());
            body.put("mediaPayloadBase64", Base64.encodeToString(request.getMediaPayload(), Base64.NO_WRAP));
        }

        byte[] bytes = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) endpoint.toURL().openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }

            int status = connection.getResponseCode();
            InputStream input = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            if (input != null) {
                try (InputStream ignored = input) {
                    byte[] buffer = new byte[1024];
                    while (ignored.read(buffer) != -1) { /* drain response */ }
                }
            }
            if (status < 200 || status >= 300) {
                if (status == 401 || status == 403) connected = false;
                throw new IllegalStateException("relay session request failed with HTTP " + status);
            }
        } finally {
            connection.disconnect();
        }
    }
}
