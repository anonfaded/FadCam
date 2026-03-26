package com.fadcam;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;

import java.io.File;

/**
 * Thread-safe in-process holder for the currently-active recording segment.
 *
 * <p>{@link com.fadcam.services.RecordingService} updates this whenever the active
 * segment file changes and clears it on stop.  {@code HomeFragment} reads it every
 * stats-update cycle to include the growing in-progress file in the "Used" total shown
 * on the UI — without touching Room DB (which only receives completed recordings).
 *
 * <p>Because both the service and the fragment run in the same process, volatile
 * fields are sufficient; no IPC overhead is incurred.
 *
 * <p>{@code sessionAccumulatedBytes} keeps a running total of all <em>finalised</em>
 * segments from the current recording session so that segment rollovers never cause
 * the displayed size to dip back to zero.
 */
public final class ActiveRecordingStats {

    private static final String TAG = "ActiveRecordingStats";

    /** Absolute file-system path for internal-storage recordings; null when SAF is used. */
    private static volatile String activeSegmentPath = null;

    /** Content URI string for SAF-backed recordings; null when internal storage is used. */
    private static volatile String activeSegmentUri = null;

    /**
     * Running total of bytes from all <em>completed</em> segments in the current session.
     * Allows the live-size display to grow monotonically across segment rollovers.
     */
    private static volatile long sessionAccumulatedBytes = 0L;

    // Private constructor — static-only utility class.
    private ActiveRecordingStats() {}

    /**
     * Called by {@link com.fadcam.services.RecordingService} when a new recording
     * session starts (first segment of a fresh recording).
     * Clears any accumulated size from a previous session and sets the initial segment.
     *
     * @param path Absolute file path for internal-storage segments; {@code null} for SAF.
     * @param uri  Content URI string for SAF segments; {@code null} for internal storage.
     */
    public static void setActiveSegment(@Nullable String path, @Nullable String uri) {
        sessionAccumulatedBytes = 0L;
        activeSegmentPath = path;
        activeSegmentUri = uri;
    }

    /**
     * Called by {@link com.fadcam.services.RecordingService} on every segment rollover.
     * Captures the final size of the finishing segment into {@code sessionAccumulatedBytes}
     * before switching the active pointer to the new (empty) segment, so the displayed
     * total never dips back to zero during rollovers.
     *
     * @param newPath Absolute file path of the new segment; {@code null} for SAF.
     * @param newUri  Content URI string of the new SAF segment; {@code null} for internal.
     * @param context A valid context (needed only for SAF URI size queries).
     */
    public static void rolloverToNewSegment(
            @Nullable String newPath,
            @Nullable String newUri,
            @NonNull  Context context) {
        // Capture the current (finishing) segment's size before switching.
        long finishedSize = currentSegmentSize(context);
        sessionAccumulatedBytes += finishedSize;
        FLog.d(TAG, "Segment rollover: accumulated " + (finishedSize / (1024 * 1024))
                + " MB, session total now " + (sessionAccumulatedBytes / (1024 * 1024)) + " MB");
        // Switch active pointer to the new (empty) segment.
        activeSegmentPath = newPath;
        activeSegmentUri  = newUri;
    }

    /**
     * Called by {@link com.fadcam.services.RecordingService} when recording stops so
     * the HomeFragment stops adding the (now-finalised) file size to the displayed total.
     * Also resets the session accumulator ready for the next recording.
     */
    public static void clearActiveSegment() {
        activeSegmentPath       = null;
        activeSegmentUri        = null;
        sessionAccumulatedBytes = 0L;
    }

    /**
     * Returns the total live bytes for the current recording session: all completed
     * segments plus the current growing segment.  Returns {@code 0} if not recording.
     *
     * @param context Any valid context (used only for SAF URI resolution).
     */
    public static long getActiveFileSizeBytes(@NonNull Context context) {
        return sessionAccumulatedBytes + currentSegmentSize(context);
    }

    /** Size of the segment currently being written. */
    private static long currentSegmentSize(@NonNull Context context) {
        String path = activeSegmentPath;
        String uri  = activeSegmentUri;

        if (path != null && !path.isEmpty()) {
            if (path.startsWith("content://")) {
                return querySafSize(context, Uri.parse(path));
            }
            File f = new File(path);
            return f.exists() ? f.length() : 0L;
        }

        if (uri != null && !uri.isEmpty()) {
            return querySafSize(context, Uri.parse(uri));
        }

        return 0L;
    }

    /** Queries the file size for a SAF content URI via {@link OpenableColumns#SIZE}. */
    private static long querySafSize(@NonNull Context context, @NonNull Uri uri) {
        try {
            Cursor cursor = context.getContentResolver()
                    .query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        int col = cursor.getColumnIndex(OpenableColumns.SIZE);
                        if (col >= 0 && !cursor.isNull(col)) {
                            return cursor.getLong(col);
                        }
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Exception e) {
            // Silently ignore — size is best-effort for display purposes only.
        }
        return 0L;
    }
}
