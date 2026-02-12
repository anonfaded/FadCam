package com.fadcam.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.util.Log;

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
    private static final String TAG = "PhotoStorageHelper";
    private static final int JPEG_QUALITY = 88;

    public enum ShotSource {
        BACK,
        SELFIE,
        FADREC
    }

    private PhotoStorageHelper() {
    }

    @Nullable
    public static Uri saveJpegBitmap(
            @NonNull Context context,
            @NonNull Bitmap bitmap
    ) {
        return saveJpegBitmap(context, bitmap, false, ShotSource.BACK);
    }

    @Nullable
    public static Uri saveJpegBitmap(
            @NonNull Context context,
            @NonNull Bitmap bitmap,
            boolean applyWatermarkFromPreferences
    ) {
        return saveJpegBitmap(context, bitmap, applyWatermarkFromPreferences, ShotSource.BACK);
    }

    @Nullable
    public static Uri saveJpegBitmap(
            @NonNull Context context,
            @NonNull Bitmap bitmap,
            boolean applyWatermarkFromPreferences,
            @NonNull ShotSource shotSource
    ) {
        String fileName = buildShotFileName(shotSource);
        Log.d(TAG, "saveJpegBitmap: source=" + shotSource + ", fileName=" + fileName
                + ", applyWatermark=" + applyWatermarkFromPreferences);

        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(context);
        Bitmap prepared = prepareBitmapForSave(context, bitmap, prefs, applyWatermarkFromPreferences);
        if (prepared == null) {
            return null;
        }
        String customTreeUri = prefs != null ? prefs.getCustomStorageUri() : null;

        Uri result = null;
        if (customTreeUri != null && !customTreeUri.trim().isEmpty()) {
            Log.d(TAG, "saveJpegBitmap: attempting SAF save. treeUri=" + customTreeUri);
            Uri safUri = saveToSaf(context, customTreeUri, fileName, prepared, shotSource);
            if (safUri != null) {
                Log.d(TAG, "saveJpegBitmap: saved via SAF uri=" + safUri);
                result = safUri;
            } else {
                Log.w(TAG, "saveJpegBitmap: SAF save failed, falling back to internal.");
            }
        }
        if (result == null) {
            result = saveToInternal(context, fileName, prepared, shotSource);
            Log.d(TAG, "saveJpegBitmap: internal save result uri=" + result);
        }
        if (prepared != bitmap && !prepared.isRecycled()) {
            prepared.recycle();
        }
        return result;
    }

    @Nullable
    private static Uri saveToInternal(
            @NonNull Context context,
            @NonNull String fileName,
            @NonNull Bitmap bitmap,
            @NonNull ShotSource shotSource
    ) {
        File shotDir = RecordingStoragePaths.getInternalShotSourceDir(
                context,
                toStorageShotSource(shotSource),
                true);
        if (shotDir == null) {
            Log.e(TAG, "saveToInternal: shotDir is null for source=" + shotSource);
            return null;
        }
        File outputFile = new File(shotDir, fileName);
        Log.d(TAG, "saveToInternal: outputPath=" + outputFile.getAbsolutePath());
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            boolean ok = bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
            if (!ok) {
                Log.e(TAG, "saveToInternal: bitmap.compress returned false");
                return null;
            }
            fos.flush();
            return Uri.fromFile(outputFile);
        } catch (Exception e) {
            Log.e(TAG, "saveToInternal: exception", e);
            return null;
        }
    }

    @Nullable
    private static Uri saveToSaf(
            @NonNull Context context,
            @NonNull String customTreeUri,
            @NonNull String fileName,
            @NonNull Bitmap bitmap,
            @NonNull ShotSource shotSource
    ) {
        DocumentFile shotDir = RecordingStoragePaths.getSafShotSourceDir(
                context,
                customTreeUri,
                toStorageShotSource(shotSource),
                true);
        if (shotDir == null || !shotDir.canWrite()) {
            Log.e(TAG, "saveToSaf: shotDir unavailable or not writable. source=" + shotSource
                    + ", treeUri=" + customTreeUri);
            return null;
        }

        DocumentFile doc = shotDir.createFile("image/jpeg", fileName);
        if (doc == null) {
            Log.e(TAG, "saveToSaf: createFile returned null for fileName=" + fileName);
            return null;
        }
        try (OutputStream os = context.getContentResolver().openOutputStream(doc.getUri(), "w")) {
            if (os == null) {
                Log.e(TAG, "saveToSaf: openOutputStream returned null uri=" + doc.getUri());
                return null;
            }
            boolean ok = bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, os);
            if (!ok) {
                Log.e(TAG, "saveToSaf: bitmap.compress returned false");
                return null;
            }
            os.flush();
            Log.d(TAG, "saveToSaf: saved uri=" + doc.getUri());
            return doc.getUri();
        } catch (Exception e) {
            Log.e(TAG, "saveToSaf: exception", e);
            return null;
        }
    }

    @NonNull
    private static String buildShotFileName(@NonNull ShotSource shotSource) {
        return Constants.RECORDING_FILE_PREFIX_FADSHOT
                + shotSourceLabel(shotSource)
                + "_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + "."
                + Constants.RECORDING_IMAGE_EXTENSION;
    }

    @NonNull
    private static String shotSourceLabel(@NonNull ShotSource shotSource) {
        switch (shotSource) {
            case SELFIE:
                return "Selfie";
            case FADREC:
                return "FadRec";
            case BACK:
            default:
                return "Back";
        }
    }

    @NonNull
    private static RecordingStoragePaths.ShotSource toStorageShotSource(@NonNull ShotSource shotSource) {
        switch (shotSource) {
            case SELFIE:
                return RecordingStoragePaths.ShotSource.SELFIE;
            case FADREC:
                return RecordingStoragePaths.ShotSource.FADREC;
            case BACK:
            default:
                return RecordingStoragePaths.ShotSource.BACK;
        }
    }

    @Nullable
    private static Bitmap prepareBitmapForSave(
            @NonNull Context context,
            @NonNull Bitmap source,
            @Nullable SharedPreferencesManager prefs,
            boolean applyWatermarkFromPreferences
    ) {
        Bitmap working = source;
        if (applyWatermarkFromPreferences) {
            String watermarkText = buildWatermarkText(prefs);
            if (!watermarkText.isEmpty()) {
                Bitmap watermarked = applyWatermark(context, working, watermarkText);
                if (watermarked != null) {
                    if (working != source && !working.isRecycled()) {
                        working.recycle();
                    }
                    working = watermarked;
                }
            }
        }
        return working;
    }

    private static String buildWatermarkText(@Nullable SharedPreferencesManager prefs) {
        if (prefs == null) {
            return "";
        }
        String option = prefs.getWatermarkOption();
        if ("no_watermark".equals(option)) {
            return "";
        }
        String timestamp = new SimpleDateFormat("dd/MMM/yyyy hh:mm:ss a", Locale.ENGLISH).format(new Date());
        String custom = prefs.getWatermarkCustomText();
        String customLine = (custom != null && !custom.trim().isEmpty()) ? ("\n" + custom.trim()) : "";
        if ("timestamp".equals(option)) {
            return timestamp + customLine;
        }
        return "Captured by FadCam - " + timestamp + customLine;
    }

    @Nullable
    private static Bitmap applyWatermark(@NonNull Context context, @NonNull Bitmap source, @NonNull String text) {
        try {
            Bitmap result = source.copy(Bitmap.Config.ARGB_8888, true);
            Canvas canvas = new Canvas(result);
            float textSize = Math.max(18f, Math.min(24f, result.getWidth() * 0.006f));

            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.WHITE);
            paint.setTextSize(textSize);
            paint.setShadowLayer(2.5f, 0f, 1.5f, Color.BLACK);
            paint.setFakeBoldText(true);
            try {
                Typeface ubuntu = Typeface.createFromAsset(context.getAssets(), "ubuntu_regular.ttf");
                paint.setTypeface(ubuntu);
            } catch (Exception ignored) {
                paint.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD));
            }

            int padding = Math.max(14, Math.round(result.getWidth() * 0.012f));
            String[] lines = text.split("\n");
            Paint.FontMetrics fm = paint.getFontMetrics();
            float lineHeight = (fm.descent - fm.ascent) + 4f;
            float y = padding - fm.ascent;
            for (String line : lines) {
                canvas.drawText(line, padding, y, paint);
                y += lineHeight;
            }
            return result;
        } catch (Exception ignored) {
            return null;
        }
    }
}
