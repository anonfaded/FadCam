package com.fadcam.streaming;

import android.content.Context;

import java.io.IOException;
import java.net.InetAddress;

import fi.iki.elonen.NanoHTTPD;

/**
 * Security boundary around the local/remote dashboard HTTP server.
 *
 * HLS media remains readable without authentication so standard players can consume it.
 * Control, status, and sensitive dashboard APIs require authentication for remote clients;
 * when remote authentication is disabled, those operations are limited to loopback.
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
            addSecurityHeaders(response);
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
            addSecurityHeaders(response);
            addCorsHeaders(response);
            return response;
        }

        Response response = super.serve(session);
        addSecurityHeaders(response);
        return response;
    }

    private boolean isProtectedEndpoint(String uri, Method method) {
        // Login/check are the unauthenticated discovery surface. Logout is harmless but
        // remains public so a client can always discard a session. Password changes are
        // protected once authentication is enabled to prevent remote account takeover.
        if ("/auth/login".equals(uri) || "/auth/logout".equals(uri) || "/auth/check".equals(uri)) {
            return false;
        }
        if ("/auth/changePassword".equals(uri)) {
            return Method.POST.equals(method) && RemoteAuthManager.getInstance(appContext).isAuthEnabled();
        }

        // These GET endpoints expose device state or act as control/data APIs even though
        // they are not POST requests. Keep HLS media and static assets public.
        if ("/status".equals(uri)
                || "/audio/volume".equals(uri)
                || "/api/notifications".equals(uri)
                || "/api/github/notification".equals(uri)) {
            return true;
        }

        // Every mutating endpoint is protected.
        return Method.POST.equals(method);
    }

    private boolean isAuthorized(IHTTPSession session) {
        RemoteAuthManager authManager = RemoteAuthManager.getInstance(appContext);
        if (authManager.isAuthEnabled()) {
            String header = session.getHeaders().get("authorization");
            if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
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

    private void addSecurityHeaders(Response response) {
        response.addHeader("X-Content-Type-Options", "nosniff");
        response.addHeader("X-Frame-Options", "DENY");
        response.addHeader("Referrer-Policy", "no-referrer");
        response.addHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        response.addHeader("Cache-Control", "no-store");
    }

    private void addCorsHeaders(Response response) {
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, Cache-Control, Pragma");
        response.addHeader("Access-Control-Max-Age", "600");
        response.addHeader("Vary", "Origin");
    }
}
