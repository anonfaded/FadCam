package com.fadcam.ui;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * FadDrive sync implementation using the open WebDAV protocol.
 *
 * <p>This deliberately does not persist the cloud password. Endpoint and username
 * are saved locally; the password is supplied for each test/sync operation.</p>
 */
public final class FadDriveSyncManager {
    public static final String PREF_ENDPOINT = "faddrive_endpoint";
    public static final String PREF_USERNAME = "faddrive_username";
    private static final String REMOTE_FOLDER = "FadCam/";
    private static final long MAX_UPLOAD_BYTES = 4L * 1024L * 1024L * 1024L;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
            .readTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
            .build();

    public interface Callback {
        void onProgress(String message, int completed, int total);
        void onComplete(String message);
    }

    private FadDriveSyncManager() {}

    public static void testConnection(@NonNull String endpoint, @NonNull String username,
                                      @NonNull String password, @NonNull Callback callback) {
        EXECUTOR.execute(() -> {
            try {
                URI base = validateEndpoint(endpoint);
                Request.Builder builder = new Request.Builder().url(base.toString()).method("OPTIONS", null);
                applyAuth(builder, username, password);
                try (Response response = CLIENT.newCall(builder.build()).execute()) {
                    int code = response.code();
                    if (code >= 200 && code < 400) {
                        callback.onComplete("FadDrive connection OK (HTTP " + code + ").");
                    } else {
                        callback.onComplete("FadDrive connection failed (HTTP " + code + ").");
                    }
                }
            } catch (Exception e) {
                callback.onComplete("FadDrive connection failed: " + safeMessage(e));
            }
        });
    }

    public static void syncAllVideos(@NonNull Context context, @NonNull String endpoint,
                                     @NonNull String username, @NonNull String password,
                                     @NonNull Callback callback) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            List<VideoItem> videos = queryVideos(app.getContentResolver());
            if (videos.isEmpty()) {
                callback.onComplete("FadDrive: no videos found to sync.");
                return;
            }
            try {
                URI base = validateEndpoint(endpoint);
                ensureFolder(base, username, password);
                int completed = 0;
                for (VideoItem item : videos) {
                    try {
                        upload(app.getContentResolver(), base, username, password, item);
                        completed++;
                        callback.onProgress("Synced " + completed + "/" + videos.size() + ": " + item.name,
                                completed, videos.size());
                    } catch (Exception e) {
                        callback.onProgress("Skipped " + item.name + ": " + safeMessage(e),
                                completed, videos.size());
                    }
                }
                callback.onComplete("FadDrive sync finished: " + completed + "/" + videos.size() + " videos uploaded.");
            } catch (Exception e) {
                callback.onComplete("FadDrive sync failed: " + safeMessage(e));
            }
        });
    }

    static String buildRemoteUrl(String endpoint, String fileName) throws URISyntaxException {
        URI base = validateEndpoint(endpoint);
        String safeName = Uri.encode(fileName).replace("%2F", "_");
        String basePath = base.getPath() == null ? "" : base.getPath();
        if (!basePath.endsWith("/")) basePath += "/";
        return new URI(base.getScheme(), base.getUserInfo(), base.getHost(), base.getPort(),
                basePath + REMOTE_FOLDER + safeName, null, null).toString();
    }

    private static URI validateEndpoint(String endpoint) throws URISyntaxException {
        String value = endpoint == null ? "" : endpoint.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("WebDAV URL is required");
        URI uri = new URI(value);
        String scheme = uri.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("WebDAV URL must use http:// or https://");
        }
        if (uri.getHost() == null) throw new IllegalArgumentException("WebDAV URL has no host");
        return uri;
    }

    private static void ensureFolder(URI base, String username, String password) throws Exception {
        String path = base.getPath() == null ? "/" : base.getPath();
        if (!path.endsWith("/")) path += "/";
        URI folder = new URI(base.getScheme(), base.getUserInfo(), base.getHost(), base.getPort(),
                path + REMOTE_FOLDER, null, null);
        Request.Builder builder = new Request.Builder().url(folder.toString()).method("MKCOL", null);
        applyAuth(builder, username, password);
        try (Response response = CLIENT.newCall(builder.build()).execute()) {
            int code = response.code();
            if (!((code >= 200 && code < 300) || code == 405 || code == 301 || code == 302)) {
                throw new IOException("Cannot create FadCam folder (HTTP " + code + ")");
            }
        }
    }

    private static void upload(ContentResolver resolver, URI base, String username,
                               String password, VideoItem item) throws Exception {
        if (item.size < 0 || item.size > MAX_UPLOAD_BYTES) {
            throw new IOException("unsupported file size");
        }
        String url = buildRemoteUrl(base.toString(), item.name);
        RequestBody body = new ContentResolverRequestBody(resolver, item.uri, item.mimeType, item.size);
        Request.Builder builder = new Request.Builder().url(url).put(body);
        applyAuth(builder, username, password);
        try (Response response = CLIENT.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
        }
    }

    private static void applyAuth(Request.Builder builder, String username, String password) {
        if (username != null && !username.trim().isEmpty()) {
            builder.header("Authorization", Credentials.basic(username, password == null ? "" : password));
        }
    }

    private static List<VideoItem> queryVideos(ContentResolver resolver) {
        List<VideoItem> result = new ArrayList<>();
        String[] projection = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED
        };
        String sort = MediaStore.Video.Media.DATE_ADDED + " DESC";
        try (Cursor cursor = resolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, sort)) {
            if (cursor == null) return result;
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE);
            int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                String name = cursor.getString(nameCol);
                String mime = cursor.getString(mimeCol);
                long size = cursor.getLong(sizeCol);
                if (name == null || name.trim().isEmpty()) name = "video-" + id + ".mp4";
                Uri uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Long.toString(id));
                result.add(new VideoItem(uri, name, mime == null ? "video/mp4" : mime, size));
            }
        } catch (RuntimeException ignored) {
            // The caller receives an empty list and a friendly status message.
        }
        return result;
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private static final class VideoItem {
        final Uri uri;
        final String name;
        final String mimeType;
        final long size;
        VideoItem(Uri uri, String name, String mimeType, long size) {
            this.uri = uri;
            this.name = name;
            this.mimeType = mimeType;
            this.size = size;
        }
    }

    private static final class ContentResolverRequestBody extends RequestBody {
        private final ContentResolver resolver;
        private final Uri uri;
        private final MediaType mediaType;
        private final long length;

        ContentResolverRequestBody(ContentResolver resolver, Uri uri, String mimeType, long length) {
            this.resolver = resolver;
            this.uri = uri;
            this.mediaType = MediaType.parse(mimeType == null ? "application/octet-stream" : mimeType);
            this.length = length;
        }

        @Override public MediaType contentType() { return mediaType; }
        @Override public long contentLength() { return length; }

        @Override public void writeTo(@NonNull okio.BufferedSink sink) throws IOException {
            try (InputStream in = resolver.openInputStream(uri)) {
                if (in == null) throw new IOException("Unable to open media file");
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) sink.write(buffer, 0, read);
            }
        }
    }
}
