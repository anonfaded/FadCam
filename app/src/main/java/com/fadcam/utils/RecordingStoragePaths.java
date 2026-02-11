package com.fadcam.utils;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import com.fadcam.Constants;

import java.io.File;

/**
 * Centralized helpers for recording/export storage paths across internal and SAF modes.
 */
public final class RecordingStoragePaths {

    public enum Category {
        CAMERA,
        DUAL,
        SCREEN,
        FADITOR,
        STREAM,
        UNKNOWN
    }

    private RecordingStoragePaths() {
    }

    @NonNull
    public static String folderNameForCategory(@NonNull Category category) {
        switch (category) {
            case CAMERA:
                return Constants.RECORDING_SUBDIR_CAMERA;
            case DUAL:
                return Constants.RECORDING_SUBDIR_DUAL;
            case SCREEN:
                return Constants.RECORDING_SUBDIR_SCREEN;
            case FADITOR:
                return Constants.RECORDING_SUBDIR_FADITOR;
            case STREAM:
                return Constants.RECORDING_SUBDIR_STREAM;
            case UNKNOWN:
            default:
                return "";
        }
    }

    @NonNull
    public static Category categoryFromFolderName(@Nullable String folderName) {
        if (folderName == null) return Category.UNKNOWN;
        if (Constants.RECORDING_SUBDIR_CAMERA.equals(folderName)) return Category.CAMERA;
        if (Constants.RECORDING_SUBDIR_DUAL.equals(folderName)) return Category.DUAL;
        if (Constants.RECORDING_SUBDIR_SCREEN.equals(folderName)) return Category.SCREEN;
        if (Constants.RECORDING_SUBDIR_FADITOR.equals(folderName)) return Category.FADITOR;
        if (Constants.RECORDING_SUBDIR_STREAM.equals(folderName)) return Category.STREAM;
        return Category.UNKNOWN;
    }

    @Nullable
    public static File getInternalBaseDir(@NonNull Context context) {
        File external = context.getExternalFilesDir(null);
        if (external == null) return null;
        return new File(external, Constants.RECORDING_DIRECTORY);
    }

    @Nullable
    public static File getInternalCategoryDir(
            @NonNull Context context,
            @NonNull Category category,
            boolean createIfMissing
    ) {
        File base = getInternalBaseDir(context);
        if (base == null) return null;
        if (!base.exists() && createIfMissing && !base.mkdirs()) return null;
        String childName = folderNameForCategory(category);
        if (childName.isEmpty()) return base;
        File child = new File(base, childName);
        if (!child.exists() && createIfMissing && !child.mkdirs()) return null;
        return child;
    }

    @Nullable
    public static DocumentFile getSafBaseDir(@NonNull Context context, @Nullable String treeUriString) {
        if (treeUriString == null || treeUriString.isEmpty()) return null;
        try {
            Uri treeUri = Uri.parse(treeUriString);
            DocumentFile base = DocumentFile.fromTreeUri(context, treeUri);
            if (base == null || !base.exists() || !base.canWrite()) return null;
            return base;
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    public static DocumentFile getSafCategoryDir(
            @NonNull Context context,
            @Nullable String treeUriString,
            @NonNull Category category,
            boolean createIfMissing
    ) {
        DocumentFile base = getSafBaseDir(context, treeUriString);
        if (base == null) return null;
        String folderName = folderNameForCategory(category);
        if (folderName.isEmpty()) return base;
        return findOrCreateChildDirectory(base, folderName, createIfMissing);
    }

    @Nullable
    public static DocumentFile findOrCreateChildDirectory(
            @NonNull DocumentFile parent,
            @NonNull String childName,
            boolean createIfMissing
    ) {
        for (DocumentFile child : parent.listFiles()) {
            if (child != null && child.isDirectory() && childName.equals(child.getName())) {
                return child;
            }
        }
        if (!createIfMissing) return null;
        return parent.createDirectory(childName);
    }
}
