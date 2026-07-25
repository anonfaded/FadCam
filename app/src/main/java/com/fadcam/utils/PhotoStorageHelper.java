package com.fadcam.utils;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import com.fadcam.Constants;
import com.fadcam.R;
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
        return saveJpegBitmap(context, bitmap, applyWatermarkFromPreferences, shotSource, null);
    }

    @Nullable
    public static Uri saveJpegBitmap(
            @NonNull Context context,
            @NonNull Bitmap bitmap,
            boolean applyWatermarkFromPreferences,
            @NonNull ShotSource shotSource,
            @Nullable String extraWatermarkData
    ) {
        String fileName = buildShotFileName(shotSource);
        FLog.d(TAG, "saveJpegBitmap: source=" + shotSource + ", fileName=" + fileName
                + ", applyWatermark=" + applyWatermarkFromPreferences);

        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(context);
        Bitmap prepared = prepareBitmapForSave(context, bitmap, prefs, applyWatermarkFromPreferences, extraWatermarkData);
        if (prepared == null) {
            return null;
        }
        String customTreeUri = prefs != null ? prefs.getCustomStorageUri() : null;

        Uri result = null;
        if (customTreeUri != null && !customTreeUri.trim().isEmpty()) {
            FLog.d(TAG, "saveJpegBitmap: attempting SAF save. treeUri=" + customTreeUri);
            Uri safUri = saveToSaf(context, customTreeUri, fileName, prepared, shotSource);
            if (safUri != null) {
                FLog.d(TAG, "saveJpegBitmap: saved via SAF uri=" + safUri);
                result = safUri;
            } else {
                FLog.w(TAG, "saveJpegBitmap: SAF save failed, falling back to internal.");
            }
        }
        if (result == null) {
            result = saveToInternal(context, fileName, prepared, shotSource);
            FLog.d(TAG, "saveJpegBitmap: internal save result uri=" + result);
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
            FLog.e(TAG, "saveToInternal: shotDir is null for source=" + shotSource);
            return null;
        }
        File outputFile = new File(shotDir, fileName);
        FLog.d(TAG, "saveToInternal: outputPath=" + outputFile.getAbsolutePath());
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            boolean ok = bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
            if (!ok) {
                FLog.e(TAG, "saveToInternal: bitmap.compress returned false");
                return null;
            }
            fos.flush();
            return Uri.fromFile(outputFile);
        } catch (Exception e) {
            FLog.e(TAG, "saveToInternal: exception", e);
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
            FLog.e(TAG, "saveToSaf: shotDir unavailable or not writable. source=" + shotSource
                    + ", treeUri=" + customTreeUri);
            return null;
        }

        DocumentFile doc = shotDir.createFile("image/jpeg", fileName);
        if (doc == null) {
            FLog.e(TAG, "saveToSaf: createFile returned null for fileName=" + fileName);
            return null;
        }
        try (OutputStream os = context.getContentResolver().openOutputStream(doc.getUri(), "w")) {
            if (os == null) {
                FLog.e(TAG, "saveToSaf: openOutputStream returned null uri=" + doc.getUri());
                return null;
            }
            boolean ok = bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, os);
            if (!ok) {
                FLog.e(TAG, "saveToSaf: bitmap.compress returned false");
                return null;
            }
            os.flush();
            FLog.d(TAG, "saveToSaf: saved uri=" + doc.getUri());
            return doc.getUri();
        } catch (Exception e) {
            FLog.e(TAG, "saveToSaf: exception", e);
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
        return prepareBitmapForSave(context, source, prefs, applyWatermarkFromPreferences, null);
    }

    @Nullable
    private static Bitmap prepareBitmapForSave(
            @NonNull Context context,
            @NonNull Bitmap source,
            @Nullable SharedPreferencesManager prefs,
            boolean applyWatermarkFromPreferences,
            @Nullable String extraWatermarkData
    ) {
        Bitmap working = source;
        if (applyWatermarkFromPreferences) {
            String watermarkText = buildWatermarkText(prefs, extraWatermarkData);
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

    private static String buildWatermarkText(@Nullable SharedPreferencesManager prefs, @Nullable String extraWatermarkData) {
        if (prefs == null) {
            return "";
        }
        String option = prefs.getWatermarkOption();
        if ("no_watermark".equals(option)) {
            return "";
        }
        String timestamp = new SimpleDateFormat("dd/MMM/yyyy hh:mm:ss a", Locale.ENGLISH).format(new Date());

        // Timezone suffix (matching video watermark)
        String timezoneSuffix = "";
        if (prefs.isTimezoneEnabled()) {
            java.util.TimeZone tz = java.util.TimeZone.getDefault();
            int offsetMs = tz.getOffset(System.currentTimeMillis());
            int totalMinutes = offsetMs / 60000;
            int hours = totalMinutes / 60;
            int minutes = Math.abs(totalMinutes % 60);
            String sign = offsetMs >= 0 ? "+" : "";
            String gmt;
            if (minutes == 0) {
                gmt = "GMT" + sign + hours;
            } else {
                gmt = "GMT" + sign + hours + ":" + String.format(Locale.ENGLISH, "%02d", minutes);
            }
            if ("gmt_name".equals(prefs.getTimezoneFormat())) {
                gmt += " (" + tz.getID() + ")";
            }
            timezoneSuffix = " " + gmt;
        }

        String custom = prefs.getWatermarkCustomText();
        String customLine = (custom != null && !custom.trim().isEmpty()) ? ("\n" + custom.trim()) : "";

        String base;
        switch (option) {
            case "badge_fadcam":
                base = "Captured by <FADCAM_ICON>" + customLine;
                break;
            case "timestamp":
                base = timestamp + timezoneSuffix + customLine;
                break;
            case "timestamp_fadcam":
            default:
                base = "Captured by <FADCAM_ICON> - " + timestamp + timezoneSuffix + customLine;
                break;
        }

        // Append extended sensor data if available, with a blank line gap
        // matching the video watermark pipeline layout
        if (extraWatermarkData != null && !extraWatermarkData.isEmpty()) {
            base += "\n\n" + extraWatermarkData;
        }

        return base;
    }

    @Nullable
    private static Bitmap applyWatermark(@NonNull Context context, @NonNull Bitmap source, @NonNull String text) {
        try {
            Bitmap result = source.copy(Bitmap.Config.ARGB_8888, true);
            Canvas canvas = new Canvas(result);
            // Text size: ~3% of smallest dimension (same proportion as video watermark)
            // For 1080p: 1080 * 0.03 = ~32px (clamped 24-40)
            float minDim = Math.min(result.getWidth(), result.getHeight());
            float textSize = Math.max(18f, Math.min(26f, minDim * 0.02f));

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

            // Load FadCam icon - same resource used by video watermark
            Bitmap fadcamIcon = null;
            try {
                fadcamIcon = BitmapFactory.decodeResource(context.getResources(), R.drawable.menu_icon_unknown);
            } catch (Exception ignored) {}

            // Icon bigger than text like video watermark (icon is ~2x text height)
            float iconHeight = textSize * 2.0f;

            // Padding accounts for icon extending above text
            int padding = Math.max(Math.round(iconHeight * 0.5f + 8),
                    Math.round(minDim * 0.015f));
            String[] lines = text.split("\n");
            Paint.FontMetrics fm = paint.getFontMetrics();
            float lineHeight = (fm.descent - fm.ascent) + 4f;

            Bitmap scaledIcon = null;
            if (fadcamIcon != null) {
                // Keep aspect ratio — don't stretch square
                float ratio = (float) fadcamIcon.getWidth() / fadcamIcon.getHeight();
                int iconW = Math.round(iconHeight * ratio);
                int iconH = Math.round(iconHeight);
                scaledIcon = Bitmap.createScaledBitmap(fadcamIcon, iconW, iconH, true);
            }

            float y = padding - fm.ascent;
            for (String line : lines) {
                float x = padding;
                if (line.contains("<FADCAM_ICON>") && scaledIcon != null) {
                    // Draw inline icon, then text after it (same as video watermark)
                    String[] parts = line.split("<FADCAM_ICON>", 2);
                    if (parts.length > 0 && !parts[0].isEmpty()) {
                        canvas.drawText(parts[0], x, y, paint);
                        x += paint.measureText(parts[0]) + 6f;
                    }
                    // Center icon vertically on the text line
                    float iconCenterY = y + (fm.ascent + fm.descent) * 0.5f;
                    float iconY = iconCenterY - iconHeight * 0.5f;
                    canvas.drawBitmap(scaledIcon, x, iconY, null);
                    x += scaledIcon.getWidth() + 6f;
                    if (parts.length > 1 && !parts[1].isEmpty()) {
                        canvas.drawText(parts[1], x, y, paint);
                    }
                } else {
                    canvas.drawText(line, x, y, paint);
                }
                y += lineHeight;
            }
            if (fadcamIcon != null && !fadcamIcon.isRecycled()) fadcamIcon.recycle();
            return result;
        } catch (Exception ignored) {
            return null;
        }
    }
}
