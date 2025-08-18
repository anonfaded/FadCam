package com.fadcam;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.widget.Toast;
import android.media.MediaScannerConnection;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit; // Required for time conversions
import java.text.ParseException; // Add this import

import androidx.annotation.StringRes;
import android.net.Uri;
import java.io.File;

public class Utils {

    /**
     * Formats a timestamp into a relative "time ago" string.
     * @param timeMillis The timestamp in milliseconds since the epoch.
     * @return A relative time string (e.g., "Just now", "5m ago", "2h ago", "3d ago", "1w ago", "2mo ago", "1yr ago").
     */
    public static String formatTimeAgo(long timeMillis) {
        if (timeMillis <= 0) return ""; // Handle invalid timestamp

        long currentTime = System.currentTimeMillis();
        long diff = currentTime - timeMillis;

        // Ensure the timestamp is not in the future (though unlikely for lastModified)
        if (diff < 0) {
            // Option 1: Return specific text
            // return "In the future";
            // Option 2: Treat as "Just now" for practical purposes
            diff = 0;
        }

        // Convert diff to various units
        long seconds = TimeUnit.MILLISECONDS.toSeconds(diff);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(diff);
        long hours = TimeUnit.MILLISECONDS.toHours(diff);
        long days = TimeUnit.MILLISECONDS.toDays(diff);
        long weeks = days / 7;
        long months = days / 30; // Approximate months
        long years = days / 365; // Approximate years

        if (years > 0) {
            return years + (years == 1 ? "yr ago" : "yrs ago");
        } else if (months > 0) {
            return months + (months == 1 ? "mo ago" : "mos ago");
        } else if (weeks > 0) {
            return weeks + (weeks == 1 ? "wk ago" : "wks ago");
        } else if (days > 0) {
            return days + (days == 1 ? "d ago" : "d ago"); // Keep 'd' consistent
        } else if (hours > 0) {
            return hours + (hours == 1 ? "h ago" : "h ago"); // Keep 'h' consistent
        } else if (minutes > 0) {
            return minutes + (minutes == 1 ? "m ago" : "m ago"); // Keep 'm' consistent
        } else {
            // Less than a minute
            // Optionally show seconds: return seconds + (seconds <= 1 ? "s ago" : "s ago");
            return "Just now";
        }
    }

    /**
     * Checks if a video is considered "new" based on its timestamp.
     * A video is considered new if it was modified within the last 24 hours.
     * @param timestampMillis The last modified timestamp in milliseconds.
     * @return True if the video is considered new, false otherwise.
     */
    public static boolean isVideoConsideredNew(long timestampMillis) {
        if (timestampMillis <= 0) {
            return false; // Invalid timestamp
        }
        long currentTime = System.currentTimeMillis();
        long twentyFourHoursInMillis = 24 * 60 * 60 * 1000;
        return (currentTime - timestampMillis) < twentyFourHoursInMillis;
    }

    /**
     * Tries to parse the timestamp from a FadCam filename.
     * Expects format like "FadCam_yyyyMMdd_HHmmss.mp4".
     * @param filename The filename string.
     * @return Timestamp in milliseconds since epoch, or -1 if parsing fails.
     */
    public static long parseTimestampFromFilename(String filename) {
        if (filename == null || !filename.startsWith(Constants.RECORDING_DIRECTORY + "_") || !filename.endsWith("." + Constants.RECORDING_FILE_EXTENSION)) {
            return -1; // Not a valid FadCam filename format
        }
        try {
            // Extract the timestamp part: yyyyMMdd_HHmmss
            String timestampString = filename.substring(
                    Constants.RECORDING_DIRECTORY.length() + 1, // Start after "FadCam_"
                    filename.length() - (Constants.RECORDING_FILE_EXTENSION.length() + 1) // End before ".mp4"
            );
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
            Date date = sdf.parse(timestampString);
            return date != null ? date.getTime() : -1;
        } catch (ParseException | IndexOutOfBoundsException e) {
            Log.w("Utils", "Failed to parse timestamp from filename: " + filename);
            return -1;
        }
    }


    public static int estimateBitrate(Size resolution, int frameRate) {
        // Estimate bitrate based on resolution and frame rate
        int width = resolution.getWidth();
        int height = resolution.getHeight();
        
        // Base bitrate calculation (you can adjust these values)
        return width * height * frameRate / 8;
    }

    public static boolean isCodecSupported(String mimeType) {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        MediaCodecInfo[] codecs = codecList.getCodecInfos();

        for (MediaCodecInfo codecInfo : codecs) {
            if (codecInfo.isEncoder()) {
                String[] supportedTypes = codecInfo.getSupportedTypes();
                for (String type : supportedTypes) {
                    if (type.equalsIgnoreCase(mimeType)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    /**
     * Shows a toast message for 0.5 second duration (shorter than Android's default SHORT duration).
     * Example usage:  Utils.showQuickToast(this, R.string.video_recording_started);
     * @param context The context in which to show the toast
     * @param messageResId Resource ID of the string message to display
     */
    public static void showQuickToast(Context context, @StringRes int messageResId) {
        Toast toast = Toast.makeText(context, messageResId, Toast.LENGTH_SHORT);
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(toast::cancel, 500); // 500ms = half second
        toast.show();
    }

    /**
     * Shows a toast message for 0.5 second duration (shorter than Android's default SHORT duration).
     * Example usage:  Utils.showQuickToast(this, "Recording started");
     * @param context The context in which to show the toast
     * @param message The string message to display
     */
    public static void showQuickToast(Context context, String message) {
        Toast toast = Toast.makeText(context, message, Toast.LENGTH_SHORT);
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(toast::cancel, 500); // 500ms = half second
        toast.show();
    }

    /**
     * Scans a file using MediaScannerConnection to make it visible in the MediaStore (e.g., Gallery).
     * @param context The application context.
     * @param filePath The absolute path of the file to scan.
     */
    public static void scanFileWithMediaStore(Context context, String filePath) {
        if (context == null || filePath == null || filePath.isEmpty()) {
            Log.w("Utils", "scanFileWithMediaStore: Context or filePath is null/empty.");
            return;
        }
        Log.d("Utils", "Scanning file with MediaStore: " + filePath);
        try {
            MediaScannerConnection.scanFile(context.getApplicationContext(), // Use application context
                    new String[]{filePath},
                    null, // MIME types (null to infer)
                    (path, uri) -> {
                        if (uri != null) {
                            Log.i("Utils", "MediaScanner finished scanning " + path + ". URI: " + uri);
                        } else {
                            Log.w("Utils", "MediaScanner finished scanning " + path + ", but MediaStore URI is null. File might not be recognized or already scanned.");
                        }
                    });
        } catch (Exception e) {
            Log.e("Utils", "Error during MediaScannerConnection.scanFile for path: " + filePath, e);
        }
    }

    /**
     * Try to resolve a java.io.File from a given SAF/URI if possible.
     * This is intentionally conservative: it only maps file:// URIs directly.
     * For SAF/content URIs this will return null (caller should handle gracefully).
     */
    public static File getFileFromSafUriIfPossible(Context context, Uri uri) {
        if (uri == null) return null;
        try {
            String scheme = uri.getScheme();
            if (scheme == null) return null;
            if (scheme.equals("file")) {
                return new File(uri.getPath());
            }
            // For content:// or tree:// URIs we cannot reliably map to a File path here.
            return null;
        } catch (Exception e) {
            Log.w("Utils", "getFileFromSafUriIfPossible: failed to resolve URI: " + uri, e);
            return null;
        }
    }
}
