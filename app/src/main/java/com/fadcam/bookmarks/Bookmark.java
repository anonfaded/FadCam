package com.fadcam.bookmarks;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A single user-marked moment inside a recording.
 *
 * <p>Immutable value object. {@link #positionMs} is the offset from the start of
 * the media file the bookmark belongs to, so it can be handed straight to the
 * player as a seek target.</p>
 */
public final class Bookmark implements Comparable<Bookmark> {

    private static final String KEY_POSITION_MS = "position_ms";
    private static final String KEY_CREATED_AT = "created_at";

    private final long positionMs;
    private final long createdAtEpochMs;

    /**
     * @param positionMs       offset inside the media file, in milliseconds (clamped to &gt;= 0)
     * @param createdAtEpochMs wall-clock time the bookmark was created, in epoch milliseconds
     */
    public Bookmark(long positionMs, long createdAtEpochMs) {
        this.positionMs = Math.max(0L, positionMs);
        this.createdAtEpochMs = createdAtEpochMs;
    }

    /** @return offset inside the media file, in milliseconds. */
    public long getPositionMs() {
        return positionMs;
    }

    /** @return wall-clock creation time, in epoch milliseconds. */
    public long getCreatedAtEpochMs() {
        return createdAtEpochMs;
    }

    /**
     * Serialises this bookmark.
     *
     * @return a JSON object holding the position and creation time
     * @throws JSONException if the values cannot be written
     */
    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put(KEY_POSITION_MS, positionMs);
        json.put(KEY_CREATED_AT, createdAtEpochMs);
        return json;
    }

    /**
     * Deserialises a bookmark previously written by {@link #toJson()}.
     *
     * @param json the stored object
     * @return the restored bookmark
     */
    @NonNull
    public static Bookmark fromJson(@NonNull JSONObject json) {
        return new Bookmark(
                json.optLong(KEY_POSITION_MS, 0L),
                json.optLong(KEY_CREATED_AT, 0L));
    }

    /** Orders bookmarks by their position in the media file. */
    @Override
    public int compareTo(@NonNull Bookmark other) {
        return Long.compare(positionMs, other.positionMs);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Bookmark)) return false;
        return positionMs == ((Bookmark) other).positionMs;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(positionMs);
    }

    @NonNull
    @Override
    public String toString() {
        return "Bookmark{positionMs=" + positionMs + "}";
    }
}
