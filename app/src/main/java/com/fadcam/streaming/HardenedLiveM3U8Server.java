package com.fadcam.streaming;

import android.content.Context;

import java.io.IOException;
import java.net.InetAddress;

import fi.iki.elonen.NanoHTTPD;

/**
 * Security boundary around the local/remote dashboard HTTP server.
 *
 * HLS media remains readable without authentication so standard players can consume it.
 * Mutating controls and status are protected: authenticated remote clients are allowed,
 * while unauthenticated control is limited to loopback.
 */
public final class HardenedLiveM3U8Server extends LiveM3U8Server {
    private final Context appContext;

    public HardenedLiveM3U8Server(Context context, int port) throws IOException {
        super(context, port);
        this.appContext = context.getApplicationContext();
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        if (Method.OPTIONS.equals(method)) {
            Response response = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "");
            addCorsHeaders(response);
            return response;
        }

        if (isProtectedEndpoint(uri, method) && !isAuthorized(session)) {
            Response response = newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED,
                    "application/json; charset=utf-8",
                    "{\"status\":\"unauthorized\",\"message\":\"Authentication required\"}");
            response.addHeader("Cache-Control", "no-store");
            response.addHeader("WWW-Authenticate", "Bearer realm=FadCam");
            addCorsHeaders(response);
            return response;
        }

        return super.serve(session);
    }

    private boolean isProtectedEndpoint(String uri, Method method) {
        if ("/auth/login".equals(uri) || "/auth/logout".equals(uri)
                || "/auth/check".equals(uri) || "/auth/changePassword".equals(uri)) {
            return false;
        }
        if ("/status".equals(uri)) return true;
        return Method.POST.equals(method);
    }

    private boolean isAuthorized(IHTTPSession session) {
        RemoteAuthManager authManager = RemoteAuthManager.getInstance(appContext);
        if (authManager.isAuthEnabled()) {
            String header = session.getHeaders().get("authorization");
            if (header == null || !header.startsWith("Bearer ")) return false;
            String token = header.substring(7).trim();
            if (token.isEmpty()) return false;
            com.fadcam.streaming.model.SessionToken sessionToken = authManager.validateToken(token);
            return sessionToken != null && sessionToken.isValid();
        }
        return isLoopback(session.getRemoteIpAddress());
    }

    private boolean isLoopback(String address) {
        if (address == null || address.isEmpty()) return false;
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (Exception ignored) {
            return "127.0.0.1".equals(address) || "::1".equals(address);
        }
    }

    private void addCorsHeaders(Response response) {
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, Cache-Control, Pragma");
        response.addHeader("Access-Control-Max-Age", "600");
        response.addHeader("Vary", "Origin");
    }
}
