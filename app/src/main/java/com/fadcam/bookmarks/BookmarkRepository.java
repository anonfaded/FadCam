package com.fadcam.bookmarks;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persistence for recording bookmarks — the moments a user marked while recording.
 *
 * <p>Bookmarks are stored in their own {@link SharedPreferences} file, one JSON
 * array per media file, keyed by the media's <em>display name</em> rather than
 * its URI: SAF document URIs change whenever a file is renamed, moved to the
 * trash or restored, while the display name survives all three.</p>
 *
 * <p>All mutating calls are synchronised and committed synchronously, because the
 * writer ({@link com.fadcam.services.RecordingService}) and the readers (player /
 * records UI) live in different components and must never observe a half-written
 * list.</p>
 */
public final class BookmarkRepository {

    private static final String TAG = "BookmarkRepository";

    /** Dedicated prefs file so bookmarks never collide with app settings. */
    private static final String PREFS_NAME = "fadcam_bookmarks";

    /** Prefix for the per-media entries inside {@link #PREFS_NAME}. */
    private static final String KEY_PREFIX = "bookmarks_";

    /**
     * Two bookmarks closer together than this are treated as the same moment.
     * Guards against a double tap producing a duplicate marker.
     */
    private static final long MIN_SPACING_MS = 400L;

    private static volatile BookmarkRepository instance;

    private final SharedPreferences preferences;

    private BookmarkRepository(@NonNull Context context) {
        this.preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Thread-safe singleton accessor.
     *
     * @param context any context; the application context is retained
     * @return the shared repository instance
     */
    @NonNull
    public static BookmarkRepository getInstance(@NonNull Context context) {
        if (instance == null) {
            synchronized (BookmarkRepository.class) {
                if (instance == null) {
                    instance = new BookmarkRepository(context);
                }
            }
        }
        return instance;
    }

    /**
     * Resolves the storage key for a media URI: its display name for SAF content
     * URIs, the last path segment otherwise.
     *
     * <p>Both the recording side and the playback side must derive the key the
     * same way, so this is the single place that does it.</p>
     *
     * @param context context used to query the content resolver
     * @param uri     media URI; may be {@code null}
     * @return the media name, or {@code null} when it cannot be resolved
     */
    @Nullable
    public static String resolveMediaName(@NonNull Context context, @Nullable Uri uri) {
        if (uri == null) {
            return null;
        }
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver()
                    .query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (column >= 0 && !cursor.isNull(column)) {
                        String name = cursor.getString(column);
                        if (name != null && !name.isEmpty()) {
                            return name;
                        }
                    }
                }
            } catch (Exception e) {
                FLog.w(TAG, "Could not query display name for " + uri + ": " + e.getMessage());
            }
        }
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            return null;
        }
        int cut = path.lastIndexOf('/');
        String name = cut >= 0 ? path.substring(cut + 1) : path;
        return name.isEmpty() ? null : name;
    }

    /**
     * Adds a bookmark for a media file, ignoring positions that duplicate an
     * existing marker (see {@link #MIN_SPACING_MS}).
     *
     * @param mediaName  the media's display name
     * @param positionMs offset inside the media file, in milliseconds
     * @return the number of bookmarks the media has after the call
     */
    public synchronized int add(@Nullable String mediaName, long positionMs) {
        if (mediaName == null || mediaName.isEmpty()) {
            FLog.w(TAG, "add: ignored — no media name");
            return 0;
        }
        List<Bookmark> bookmarks = getAll(mediaName);
        long clamped = Math.max(0L, positionMs);
        for (Bookmark existing : bookmarks) {
            if (Math.abs(existing.getPositionMs() - clamped) < MIN_SPACING_MS) {
                FLog.d(TAG, "add: duplicate at " + clamped + "ms ignored for " + mediaName);
                return bookmarks.size();
            }
        }
        bookmarks.add(new Bookmark(clamped, System.currentTimeMillis()));
        Collections.sort(bookmarks);
        write(mediaName, bookmarks);
        FLog.i(TAG, "Bookmark added at " + clamped + "ms for " + mediaName
                + " (total " + bookmarks.size() + ")");
        return bookmarks.size();
    }

    /**
     * Reads every bookmark stored for a media file.
     *
     * @param mediaName the media's display name
     * @return a modifiable list ordered by position; empty when nothing is stored
     */
    @NonNull
    public List<Bookmark> getAll(@Nullable String mediaName) {
        List<Bookmark> bookmarks = new ArrayList<>();
        if (mediaName == null || mediaName.isEmpty()) {
            return bookmarks;
        }
        String stored = preferences.getString(KEY_PREFIX + mediaName, null);
        if (stored == null || stored.isEmpty()) {
            return bookmarks;
        }
        try {
            JSONArray array = new JSONArray(stored);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null) {
                    bookmarks.add(Bookmark.fromJson(item));
                }
            }
            Collections.sort(bookmarks);
        } catch (Exception e) {
            FLog.w(TAG, "Corrupt bookmark data for " + mediaName + ", dropping it: " + e.getMessage());
            preferences.edit().remove(KEY_PREFIX + mediaName).apply();
            bookmarks.clear();
        }
        return bookmarks;
    }

    /**
     * @param mediaName the media's display name
     * @return how many bookmarks the media has
     */
    public int count(@Nullable String mediaName) {
        return getAll(mediaName).size();
    }

    /**
     * Removes a single bookmark.
     *
     * @param mediaName  the media's display name
     * @param positionMs the exact position of the bookmark to drop
     * @return {@code true} when a bookmark was removed
     */
    public synchronized boolean remove(@Nullable String mediaName, long positionMs) {
        if (mediaName == null || mediaName.isEmpty()) {
            return false;
        }
        List<Bookmark> bookmarks = getAll(mediaName);
        boolean removed = bookmarks.remove(new Bookmark(positionMs, 0L));
        if (removed) {
            write(mediaName, bookmarks);
        }
        return removed;
    }

    /**
     * Drops every bookmark stored for a media file.
     *
     * @param mediaName the media's display name
     */
    public synchronized void clear(@Nullable String mediaName) {
        if (mediaName == null || mediaName.isEmpty()) {
            return;
        }
        preferences.edit().remove(KEY_PREFIX + mediaName).commit();
    }

    /**
     * Re-keys a media file's bookmarks after a rename so they stay attached to
     * the file the user still thinks of as the same recording.
     *
     * @param oldMediaName the previous display name
     * @param newMediaName the new display name
     */
    public synchronized void move(@Nullable String oldMediaName, @Nullable String newMediaName) {
        if (oldMediaName == null || newMediaName == null
                || oldMediaName.isEmpty() || newMediaName.isEmpty()
                || oldMediaName.equals(newMediaName)) {
            return;
        }
        String stored = preferences.getString(KEY_PREFIX + oldMediaName, null);
        if (stored == null) {
            return;
        }
        preferences.edit()
                .remove(KEY_PREFIX + oldMediaName)
                .putString(KEY_PREFIX + newMediaName, stored)
                .commit();
        FLog.d(TAG, "Bookmarks moved from " + oldMediaName + " to " + newMediaName);
    }

    /** Serialises and commits a bookmark list, removing the entry when empty. */
    private void write(@NonNull String mediaName, @NonNull List<Bookmark> bookmarks) {
        String key = KEY_PREFIX + mediaName;
        if (bookmarks.isEmpty()) {
            preferences.edit().remove(key).commit();
            return;
        }
        try {
            JSONArray array = new JSONArray();
            for (Bookmark bookmark : bookmarks) {
                array.put(bookmark.toJson());
            }
            preferences.edit().putString(key, array.toString()).commit();
        } catch (Exception e) {
            FLog.e(TAG, "Failed to persist bookmarks for " + mediaName, e);
        }
    }
}
