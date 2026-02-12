package com.fadcam.ui;

import android.net.Uri;
import java.util.Objects;

/**
 * Represents a video item in the Records list, abstracting
 * whether it's stored internally (File) or via SAF (DocumentFile).
 */
public class VideoItem {
    public enum MediaType {
        VIDEO,
        IMAGE
    }

    public enum Category {
        ALL,
        CAMERA,
        DUAL,
        SCREEN,
        FADITOR,
        STREAM,
        SHOT,
        UNKNOWN
    }

    public enum ShotSubtype {
        ALL,
        BACK,
        SELFIE,
        FADREC,
        UNKNOWN
    }

    public enum CameraSubtype {
        ALL,
        BACK,
        FRONT,
        DUAL,
        UNKNOWN
    }

    public enum FaditorSubtype {
        ALL,
        CONVERTED,
        MERGE,
        OTHER,
        UNKNOWN
    }

    public final Uri uri; // The unique identifier (file:// or content://)
    public final String displayName; // Filename
    public final long size; // Size in bytes
    public final long lastModified; // Timestamp
    public final Category category; // Folder-derived source category
    public final MediaType mediaType; // video or image
    public final ShotSubtype shotSubtype; // Shot source (for image shot filtering/badging)
    public final CameraSubtype cameraSubtype; // Camera source (for camera sub-filtering)
    public final FaditorSubtype faditorSubtype; // Faditor source (converted/merge/other)

    public boolean isTemporary = false;
    public boolean isNew = false;
    public boolean isProcessingUri = false;
    public boolean isSkeleton = false; // Flag for skeleton loading

    public VideoItem(Uri uri, String displayName, long size, long lastModified) {
        this(uri, displayName, size, lastModified, Category.UNKNOWN, MediaType.VIDEO, ShotSubtype.UNKNOWN,
                CameraSubtype.UNKNOWN, FaditorSubtype.UNKNOWN);
    }

    public VideoItem(Uri uri, String displayName, long size, long lastModified, Category category) {
        this(uri, displayName, size, lastModified, category, MediaType.VIDEO, ShotSubtype.UNKNOWN,
                CameraSubtype.UNKNOWN, FaditorSubtype.UNKNOWN);
    }

    public VideoItem(
        Uri uri,
        String displayName,
        long size,
        long lastModified,
        Category category,
        MediaType mediaType
    ) {
        this(
                uri,
                displayName,
                size,
                lastModified,
                category,
                mediaType,
                ShotSubtype.UNKNOWN,
                CameraSubtype.UNKNOWN,
                FaditorSubtype.UNKNOWN);
    }

    public VideoItem(
        Uri uri,
        String displayName,
        long size,
        long lastModified,
        Category category,
        MediaType mediaType,
        ShotSubtype shotSubtype
    ) {
        this(
                uri,
                displayName,
                size,
                lastModified,
                category,
                mediaType,
                shotSubtype,
                CameraSubtype.UNKNOWN,
                FaditorSubtype.UNKNOWN);
    }

    public VideoItem(
        Uri uri,
        String displayName,
        long size,
        long lastModified,
        Category category,
        MediaType mediaType,
        ShotSubtype shotSubtype,
        CameraSubtype cameraSubtype
    ) {
        this(
                uri,
                displayName,
                size,
                lastModified,
                category,
                mediaType,
                shotSubtype,
                cameraSubtype,
                FaditorSubtype.UNKNOWN);
    }

    public VideoItem(
        Uri uri,
        String displayName,
        long size,
        long lastModified,
        Category category,
        MediaType mediaType,
        ShotSubtype shotSubtype,
        CameraSubtype cameraSubtype,
        FaditorSubtype faditorSubtype
    ) {
        this.uri = uri;
        this.displayName = displayName;
        this.size = size;
        this.lastModified = lastModified;
        this.category = category == null ? Category.UNKNOWN : category;
        this.mediaType = mediaType == null ? MediaType.VIDEO : mediaType;
        this.shotSubtype = shotSubtype == null ? ShotSubtype.UNKNOWN : shotSubtype;
        this.cameraSubtype = cameraSubtype == null ? CameraSubtype.UNKNOWN : cameraSubtype;
        this.faditorSubtype = faditorSubtype == null ? FaditorSubtype.UNKNOWN : faditorSubtype;
    }
    
    /**
     * Creates a skeleton placeholder item for loading state
     */
    public static VideoItem createSkeleton() {
        VideoItem skeleton = new VideoItem(
            Uri.parse("skeleton://placeholder"),
            "Loading...",
            0,
            System.currentTimeMillis()
        );
        skeleton.isSkeleton = true;
        return skeleton;
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
                ", category=" + category +
                ", mediaType=" + mediaType +
                ", shotSubtype=" + shotSubtype +
                ", cameraSubtype=" + cameraSubtype +
                ", faditorSubtype=" + faditorSubtype +
                '}';
    }
}
