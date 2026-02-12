package com.fadcam.utils;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import com.fadcam.Constants;
import com.fadcam.SharedPreferencesManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class PhotoStorageHelper {

    private PhotoStorageHelper() {
    }

    @Nullable
    public static Uri saveJpegBitmap(
            @NonNull Context context,
            @NonNull android.graphics.Bitmap bitmap
    ) {
        String fileName = Constants.RECORDING_FILE_PREFIX_FADSHOT
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + "."
                + Constants.RECORDING_IMAGE_EXTENSION;

        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(context);
        String customTreeUri = prefs != null ? prefs.getCustomStorageUri() : null;

        if (customTreeUri != null && !customTreeUri.trim().isEmpty()) {
            Uri safUri = saveToSaf(context, customTreeUri, fileName, bitmap);
            if (safUri != null) {
                return safUri;
            }
        }
        return saveToInternal(context, fileName, bitmap);
    }

    @Nullable
    private static Uri saveToInternal(
            @NonNull Context context,
            @NonNull String fileName,
            @NonNull android.graphics.Bitmap bitmap
    ) {
        File shotDir = RecordingStoragePaths.getInternalCategoryDir(
                context,
                RecordingStoragePaths.Category.SHOT,
                true
        );
        if (shotDir == null) {
            return null;
        }
        File outputFile = new File(shotDir, fileName);
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            boolean ok = bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fos);
            if (!ok) return null;
            fos.flush();
            return Uri.fromFile(outputFile);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private static Uri saveToSaf(
            @NonNull Context context,
            @NonNull String customTreeUri,
            @NonNull String fileName,
            @NonNull android.graphics.Bitmap bitmap
    ) {
        DocumentFile shotDir = RecordingStoragePaths.getSafCategoryDir(
                context,
                customTreeUri,
                RecordingStoragePaths.Category.SHOT,
                true
        );
        if (shotDir == null || !shotDir.canWrite()) {
            return null;
        }

        DocumentFile doc = shotDir.createFile("image/jpeg", fileName);
        if (doc == null) {
            return null;
        }
        try (OutputStream os = context.getContentResolver().openOutputStream(doc.getUri(), "w")) {
            if (os == null) return null;
            boolean ok = bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, os);
            if (!ok) return null;
            os.flush();
            return doc.getUri();
        } catch (Exception ignored) {
            return null;
        }
    }
}

