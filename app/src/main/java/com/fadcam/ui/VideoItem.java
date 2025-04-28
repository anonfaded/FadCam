package com.fadcam.ui;

import android.net.Uri;
import java.util.Objects;

/**
 * Represents a video item in the Records list, abstracting
 * whether it's stored internally (File) or via SAF (DocumentFile).
 */
public class VideoItem {
    public final Uri uri; // The unique identifier (file:// or content://)
    public final String displayName; // Filename
    public final long size; // Size in bytes
    public final long lastModified; // Timestamp

    public VideoItem(Uri uri, String displayName, long size, long lastModified) {
        this.uri = uri;
        this.displayName = displayName;
        this.size = size;
        this.lastModified = lastModified;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VideoItem videoItem = (VideoItem) o;
        return Objects.equals(uri, videoItem.uri);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri);
    }

    @Override
    public String toString() {
        return "VideoItem{" +
                "uri=" + uri +
                ", displayName='" + displayName + '\'' +
                ", size=" + size +
                ", lastModified=" + lastModified +
                '}';
    }
}