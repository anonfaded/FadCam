package com.fadcam.relay;

import android.util.Base64;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/** HTTPS transport for the deployed outbound Server Room tunnel protocol. */
public final class HttpRelaySessionTransport implements RelaySessionController.SessionTransport {
    private final URI baseEndpoint;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private volatile boolean connected;
    private volatile String sessionToken;

    public HttpRelaySessionTransport(String endpoint, int connectTimeoutMs, int readTimeoutMs) {
        if (endpoint == null || endpoint.trim().isEmpty()) throw new IllegalArgumentException("endpoint must not be empty");
        URI parsed = URI.create(endpoint.trim());
        if (!"https".equalsIgnoreCase(parsed.getScheme())) throw new IllegalArgumentException("relay session endpoint must use HTTPS");
        if (parsed.getPath() != null && !parsed.getPath().isEmpty() && !"/".equals(parsed.getPath())) {
            throw new IllegalArgumentException("relay endpoint must be the public HTTPS origin, not a tunnel path");
        }
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) throw new IllegalArgumentException("timeouts must be positive");
        this.baseEndpoint = parsed;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override public void connect() { connected = true; }

    @Override public void disconnect() {
        connected = false;
        sessionToken = null;
    }

    @Override public boolean isConnected() { return connected; }

    @Override
    public void send(RelaySessionProtocol.Request request) throws Exception {
        Objects.requireNonNull(request, "request");
        if (!connected) throw new IllegalStateException("relay HTTPS transport is disconnected");
        switch (request.getOperation()) {
            case AUTHENTICATE:
                return;
            case REGISTER_SESSION:
                register(request.getDeviceId(), request.getAuthentication());
                return;
            case HEARTBEAT:
            case RENEW:
                heartbeat();
                return;
            case ACKNOWLEDGE:
                return;
            case CLOSE:
                disconnect();
                return;
            case MEDIA:
                // The deployed relay is pull-based. Viewer requests are polled and
                // answered from the existing local Server Room HTTP surface.
                return;
            case CONNECT:
            case POLL:
                return;
            default:
                throw new IllegalArgumentException("unsupported relay operation: " + request.getOperation());
        }
    }

    @Override
    public RelaySessionController.TunnelRequest poll() throws Exception {
        requireSession();
        HttpURLConnection connection = open("/v1/tunnel/poll", "GET", true);
        try {
            int status = connection.getResponseCode();
            if (status == 204) return null;
            String body = readBody(connection, status);
            if (status == 401 || status == 403) {
                connected = false;
                sessionToken = null;
                throw new SecurityException("relay session was rejected");
            }
            if (status < 200 || status >= 300) throw new IllegalStateException("relay poll failed with HTTP " + status);
            JSONObject json = new JSONObject(body);
            return new RelaySessionController.TunnelRequest(
                    json.getString("id"),
                    json.optString("method", "GET"),
                    json.getString("path"),
                    json.optString("query", ""),
                    readStringMap(json.optJSONObject("headers")));
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public void respond(RelaySessionController.TunnelResponse response) throws Exception {
        requireSession();
        JSONObject body = new JSONObject();
        body.put("id", response.getId());
        body.put("status", response.getStatus());
        body.put("headers", new JSONObject(response.getHeaders()));
        body.put("body", Base64.encodeToString(response.getBody(), Base64.NO_WRAP));
        requestJson("/v1/tunnel/respond", "POST", body, true);
    }

    private void register(String deviceId, String deviceSecret) throws Exception {
        if (deviceSecret == null || deviceSecret.trim().isEmpty()) throw new SecurityException("relay device credential is missing");
        JSONObject body = new JSONObject();
        body.put("device_id", deviceId);
        JSONObject response = requestJson("/v1/tunnel/register", "POST", body, false, deviceSecret);
        String token = response.optString("session", null);
        if (token == null || token.isEmpty()) throw new IllegalStateException("relay registration returned no session");
        sessionToken = token;
        connected = true;
    }

    private void heartbeat() throws Exception {
        requireSession();
        requestJson("/v1/tunnel/heartbeat", "POST", new JSONObject(), true);
    }

    private JSONObject requestJson(String path, String method, JSONObject body, boolean sessionAuth) throws Exception {
        return requestJson(path, method, body, sessionAuth, null);
    }

    private JSONObject requestJson(String path, String method, JSONObject body, boolean sessionAuth, String deviceSecret) throws Exception {
        HttpURLConnection connection = open(path, method, sessionAuth);
        if (deviceSecret != null) connection.setRequestProperty("Authorization", "Bearer " + deviceSecret);
        try {
            connection.setDoOutput(true);
            byte[] bytes = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            int status = connection.getResponseCode();
            String response = readBody(connection, status);
            if (status == 401 || status == 403) {
                connected = false;
                sessionToken = null;
                throw new SecurityException("relay request rejected with HTTP " + status);
            }
            if (status < 200 || status >= 300) throw new IllegalStateException("relay request failed with HTTP " + status);
            return response.isEmpty() ? new JSONObject() : new JSONObject(response);
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String path, String method, boolean sessionAuth) throws Exception {
        URI target = baseEndpoint.resolve(path);
        HttpURLConnection connection = (HttpURLConnection) target.toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        if (sessionAuth) {
            requireSession();
            connection.setRequestProperty("Authorization", "Bearer " + sessionToken);
        }
        if ("POST".equals(method)) connection.setRequestProperty("Content-Type", "application/json");
        return connection;
    }

    private void requireSession() {
        if (!connected || sessionToken == null || sessionToken.isEmpty()) throw new IllegalStateException("relay session is not registered");
    }

    private static String readBody(HttpURLConnection connection, int status) throws Exception {
        InputStream input = status >= 200 && status < 400 ? connection.getInputStream() : connection.getErrorStream();
        if (input == null) return "";
        try (InputStream stream = input) {
            byte[] buffer = new byte[4096];
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            int read;
            while ((read = stream.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toString("UTF-8");
        }
    }

    private static Map<String, String> readStringMap(JSONObject object) {
        Map<String, String> result = new HashMap<>();
        if (object == null) return result;
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            result.put(key, object.optString(key, ""));
        }
        return result;
    }
}
