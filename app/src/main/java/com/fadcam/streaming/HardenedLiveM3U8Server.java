package com.fadcam.streaming;

import android.content.Context;

import java.io.IOException;
import java.net.InetAddress;

import fi.iki.elonen.NanoHTTPD;

/**
 * Security boundary around the device-side HTTP server.
 *
 * The phone is never the Internet-facing trust boundary. Cloud/relay access is
 * authenticated separately. This server therefore fails closed for control and
 * state endpoints: loopback is allowed without a bearer session, while any
 * non-loopback request requires an in-memory authenticated session.
 *
 * HLS media/static assets remain intentionally public to a caller that can reach
 * the device-side socket; the public delivery path is the authenticated relay,
 * not this server.
 */
public final class HardenedLiveM3U8Server extends LiveM3U8Server {
    private final Context appContext;

    public HardenedLiveM3U8Server(Context context, int port) throws IOException {
        super(context, port);
        this.appContext = context.getApplicationContext();
    }

    @Override
    public Response serve(IHTTPSession session) {
        final String uri = session.getUri();
        final Method method = session.getMethod();
        final boolean loopback = isLoopback(session.getRemoteIpAddress());

        // Never allow a browser-origin preflight to become an authentication bypass.
        // CORS is only useful to explicitly trusted same-device clients; remote web
        // clients must use the relay instead of reaching the phone directly.
        if (Method.OPTIONS.equals(method)) {
            Response response = newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "");
            addSecurityHeaders(response);
            if (loopback) addLocalCorsHeaders(response);
            return response;
        }

        if (isControlOrStateEndpoint(uri, method) && !isAuthorized(session, loopback)) {
            return unauthorizedResponse();
        }

        Response response = super.serve(session);
        addSecurityHeaders(response);
        if (loopback) addLocalCorsHeaders(response);
        return response;
    }

    private boolean isControlOrStateEndpoint(String uri, Method method) {
        // Authentication discovery endpoints are intentionally public. They do not
        // return bearer credentials and login is the only endpoint allowed to create one.
        if ("/auth/login".equals(uri) || "/auth/logout".equals(uri) || "/auth/check".equals(uri)) {
            return false;
        }

        if ("/auth/changePassword".equals(uri)) {
            return Method.POST.equals(method);
        }

        // Live media and static assets are delivery resources, not control APIs.
        if (isPublicMediaOrStatic(uri, method)) {
            return false;
        }

        // Device state and every mutating API are protected. This deliberately includes
        // GET endpoints such as /status and /audio/volume because they expose or change
        // sensitive device state.
        return Method.GET.equals(method) || Method.POST.equals(method);
    }

    private boolean isPublicMediaOrStatic(String uri, Method method) {
        if (!Method.GET.equals(method)) return false;
        return "/live.m3u8".equals(uri)
                || "/stream.m3u8".equals(uri)
                || "/init.mp4".equals(uri)
                || (uri.startsWith("/seg-") && uri.endsWith(".m4s"))
                || uri.startsWith("/css/")
                || uri.startsWith("/js/")
                || uri.startsWith("/assets/")
                || uri.startsWith("/fadex/");
    }

    private boolean isAuthorized(IHTTPSession session, boolean loopback) {
        // Localhost remains usable for the app's own command bridge without requiring
        // a bearer token. No remote address receives this bypass.
        if (loopback && !RemoteAuthManager.getInstance(appContext).isAuthEnabled()) {
            return true;
        }

        String header = session.getHeaders().get("authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return false;

        String token = header.substring(7).trim();
        if (token.isEmpty()) return false;

        com.fadcam.streaming.model.SessionToken sessionToken =
                RemoteAuthManager.getInstance(appContext).validateToken(token);
        return sessionToken != null && sessionToken.isValid();
    }

    private Response unauthorizedResponse() {
        Response response = newFixedLengthResponse(
                Response.Status.UNAUTHORIZED,
                "application/json; charset=utf-8",
                "{\"status\":\"unauthorized\",\"message\":\"Authentication required\"}");
        response.addHeader("Cache-Control", "no-store");
        response.addHeader("WWW-Authenticate", "Bearer realm=FadCam");
        addSecurityHeaders(response);
        return response;
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
        response.addHeader("Pragma", "no-cache");
    }

    private void addLocalCorsHeaders(Response response) {
        // Do not advertise wildcard CORS. A wildcard policy on an authenticated
        // control surface would unnecessarily broaden browser-origin access.
        response.addHeader("Access-Control-Allow-Origin", "null");
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, Cache-Control, Pragma");
        response.addHeader("Access-Control-Max-Age", "300");
        response.addHeader("Vary", "Origin");
    }
}
