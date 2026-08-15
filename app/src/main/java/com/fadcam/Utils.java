package com.fadcam;

import com.fadcam.FLog;
import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.widget.Toast;
import android.media.MediaScannerConnection;
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
            FLog.w("Utils", "Failed to parse timestamp from filename: " + filename);
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
            FLog.w("Utils", "scanFileWithMediaStore: Context or filePath is null/empty.");
            return;
        }
        FLog.d("Utils", "Scanning file with MediaStore: " + filePath);
        try {
            MediaScannerConnection.scanFile(context.getApplicationContext(), // Use application context
                    new String[]{filePath},
                    null, // MIME types (null to infer)
                    (path, uri) -> {
                        if (uri != null) {
                            FLog.i("Utils", "MediaScanner finished scanning " + path + ". URI: " + uri);
                        } else {
                            FLog.w("Utils", "MediaScanner finished scanning " + path + ", but MediaStore URI is null. File might not be recognized or already scanned.");
                        }
                    });
        } catch (Exception e) {
            FLog.e("Utils", "Error during MediaScannerConnection.scanFile for path: " + filePath, e);
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
            FLog.w("Utils", "getFileFromSafUriIfPossible: failed to resolve URI: " + uri, e);
            return null;
        }
    }

    /**
     * Pill-style tap animation for buttons: press scales the view up slightly,
     * release springs it back (with a gentle overshoot). Matches the mode pill's
     * press nudge + overshoot settle so all home controls feel consistent.
     * The touch listener returns false so click/long-click listeners keep working.
     *
     * @param v the view to animate
     * @param scale the scale while pressed (e.g. 1.06f)
     */
    public static void attachPressScale(final android.view.View v, final float scale) {
        attachPressScale(v, scale, true);
    }

    public static void attachPressScale(final android.view.View v) {
        attachPressScale(v, 1.06f, true);
    }

    /**
     * Press feedback for ROWS: a clean press effect — the row dims slightly
     * while pressed and the native masked ripple (from the row background)
     * provides the tactile highlight. NO scale animation and NO clipping
     * changes: scale on wide rows required unclipping containers, which made
     * scrollable content (carousels, log previews) paint over borders. A
     * dim + ripple reads as a proper material press with zero side effects.
     */
    /** Shared vibrate implementation; short pulses for start, longer for stop. */
    private static void vibrateEvent(android.content.Context context, long durationMs) {
        try {
            android.os.Vibrator vibrator = (android.os.Vibrator)
                    context.getSystemService(android.content.Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) {
                return;
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(
                        durationMs, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(durationMs);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Short vibration when a recording STARTS. Uses the Vibrator API directly
     * (not View.performHapticFeedback) so it works from services, shortcuts,
     * widgets and tiles — and regardless of the ringer mode or the system
     * "touch vibrations" setting. Duration is user-configurable (preset or
     * custom ms); gated by the master Haptic feedback toggle.
     */
    public static void vibrateRecordingStart(android.content.Context context) {
        try {
            com.fadcam.SharedPreferencesManager prefs =
                    com.fadcam.SharedPreferencesManager.getInstance(context);
            if (!prefs.isHapticFeedbackEnabled()) {
                return;
            }
            long ms = prefs.getHapticStartDurationMs();
            if (ms > 0L) {
                vibrateEvent(context, ms);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Longer vibration when a recording STOPS (distinct from the start pulse).
     * Duration is user-configurable; gated by the master toggle.
     */
    public static void vibrateRecordingStop(android.content.Context context) {
        try {
            com.fadcam.SharedPreferencesManager prefs =
                    com.fadcam.SharedPreferencesManager.getInstance(context);
            if (!prefs.isHapticFeedbackEnabled()) {
                return;
            }
            long ms = prefs.getHapticStopDurationMs();
            if (ms > 0L) {
                vibrateEvent(context, ms);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Whether UI-touch haptics are allowed: master toggle AND the
     * "Buttons & controls" group toggle (both default ON).
     */
    public static boolean hapticsAllowedForUi(android.content.Context context) {
        try {
            com.fadcam.SharedPreferencesManager prefs =
                    com.fadcam.SharedPreferencesManager.getInstance(context);
            return prefs.isHapticFeedbackEnabled() && prefs.isHapticUiEnabled();
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Double-pulse ("heartbeat") vibration for the TORCH SHORTCUT — deliberately
     * different from the single recording pulses. Intensity comes from the
     * user's preset (soft/default/strong map to amplitude). Gated by the master
     * toggle and the torch preset (Off disables).
     */
    public static void vibrateTorchShortcut(android.content.Context context) {
        try {
            com.fadcam.SharedPreferencesManager prefs =
                    com.fadcam.SharedPreferencesManager.getInstance(context);
            if (!prefs.isHapticFeedbackEnabled()) {
                return;
            }
            String preset = prefs.getHapticTorchPreset();
            if (com.fadcam.SharedPreferencesManager.HAPTIC_PRESET_OFF.equals(preset)) {
                return;
            }
            int amplitude;
            if (com.fadcam.SharedPreferencesManager.HAPTIC_PRESET_SOFT.equals(preset)) {
                amplitude = 64;
            } else if (com.fadcam.SharedPreferencesManager.HAPTIC_PRESET_STRONG.equals(preset)) {
                amplitude = 255;
            } else {
                amplitude = android.os.VibrationEffect.DEFAULT_AMPLITUDE;
            }
            android.os.Vibrator vibrator = (android.os.Vibrator)
                    context.getSystemService(android.content.Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) {
                return;
            }
            // Two pulses: wait 60ms, buzz 60ms, wait 90ms, buzz 60ms.
            long[] pattern = new long[]{0L, 60L, 90L, 60L};
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createWaveform(
                        pattern, new int[]{0, amplitude, 0, amplitude}, /* repeat= */ -1));
            } else {
                vibrator.vibrate(pattern, /* repeat= */ -1);
            }
        } catch (Exception ignored) {
        }
    }

    public static void attachPressScaleRow(final android.view.View v, final float scale) {
        if (v == null) return;
        v.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().cancel();
                    view.animate().alpha(0.65f).setDuration(80L).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().cancel();
                    view.animate().alpha(1f).setDuration(150L).start();
                    break;
                default:
                    break;
            }
            return false; // never consume — clicks/long-clicks still fire
        });
    }

    /**
     * Recursively attaches the row press effect (dim + native ripple) to every
     * clickable row in a settings screen. Skips compound widgets (switches,
     * sliders, checkboxes) that manage their own touch feedback. Idempotent
     * via a tag, so it is safe to call from any fragment.
     */
    public static void attachPressScaleToClickableRows(final android.view.View root) {
        if (root == null) return;
        boolean isCompound = root instanceof android.widget.CompoundButton
                || root instanceof android.widget.SeekBar
                || root instanceof android.widget.RatingBar;
        // Rows hosting a toggle (custom avatar switch / compound widget) must NOT
        // get the press effect — the toggle is the feedback there.
        boolean containsToggle = root instanceof android.view.ViewGroup
                && containsToggleDescendant((android.view.ViewGroup) root);
        if (!isCompound && !containsToggle && root.isClickable()
                && root.getTag(R.id.press_scale_attached) == null) {
            attachPressScaleRow(root, 1.03f);
            root.setTag(R.id.press_scale_attached, Boolean.TRUE);
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                attachPressScaleToClickableRows(g.getChildAt(i));
            }
        }
    }

    /** True if the group contains a compound widget or the custom AvatarToggleView. */
    private static boolean containsToggleDescendant(android.view.ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            android.view.View child = group.getChildAt(i);
            if (child instanceof android.widget.CompoundButton
                    || child instanceof android.widget.SeekBar
                    || child instanceof android.widget.RatingBar
                    || child.getClass().getSimpleName().contains("AvatarToggleView")) {
                return true;
            }
            if (child instanceof android.view.ViewGroup
                    && containsToggleDescendant((android.view.ViewGroup) child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pill-style tap animation for buttons: press scales the view up slightly,
     * release settles it back. Matches the mode pill's press nudge + overshoot
     * settle so all home controls feel consistent.
     *
     * @param v the view to animate
     * @param scale the scale while pressed (e.g. 1.06f)
     * @param spring true = overshoot bounce on release (buttons), false = smooth
     *               single-motion settle (wide rows — the overshoot would read
     *               as a visible "bubble" on large surfaces)
     */
    /**
     * Pill-style tap animation for BUTTONS: press scales the view up slightly,
     * release settles it back. Matches the mode pill's press nudge + overshoot
     * settle so all home controls feel consistent. The button's containers are
     * unclipped up to the first scrollable ancestor so the growth shows
     * (buttons are small contained views — unlike wide rows, this never leaks
     * into scrollable content; rows use the press effect instead).
     *
     * @param v the view to animate
     * @param scale the scale while pressed (e.g. 1.06f)
     * @param spring true = overshoot bounce on release (buttons), false = smooth
     *               single-motion settle
     */
    private static boolean isScrollable(android.view.View v) {
        return v instanceof android.widget.ScrollView
                || v instanceof android.widget.HorizontalScrollView
                || v instanceof androidx.core.widget.NestedScrollView
                || v instanceof android.widget.AbsListView
                || v instanceof androidx.recyclerview.widget.RecyclerView;
    }

    private static void unclipAncestorsForScale(final android.view.View v) {
        try {
            android.view.ViewParent p = v.getParent();
            while (p instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) p;
                if (isScrollable(g)) {
                    break; // never unclip scroll containers or anything above
                }
                g.setClipChildren(false);
                g.setClipToPadding(false);
                p = p.getParent();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Press-scale for a touch target that animates its COMPANION views in
     * sync (e.g., a home tile button + its value label overlay). The label
     * scales and settles together with the button so the whole control feels
     * like one unit. Uses the same bounded unclipping as the button variant.
     * Touches are never consumed.
     *
     * @param touchTarget the view that receives the touch
     * @param scale the scale while pressed (e.g. 1.06f)
     * @param companions extra views (labels, badges) animated in sync
     */
    public static void attachPressScaleWithCompanions(
            final android.view.View touchTarget, final float scale,
            final android.view.View... companions) {
        if (touchTarget == null) {
            return;
        }
        unclipAncestorsForScale(touchTarget);
        touchTarget.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    animateScaleSync(view, scale, companions);
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    animateRestoreSync(view, companions);
                    break;
                default:
                    break;
            }
            return false; // never consume — clicks/long-clicks still fire
        });
    }

    private static void animateScaleSync(android.view.View v, float scale,
            android.view.View[] companions) {
        v.animate().cancel();
        v.animate().scaleX(scale).scaleY(scale).setDuration(90)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
        if (companions != null) {
            // Companions (small value labels, e.g. 6.5sp) need a STRONGER
            // scale to grow visibly — scaling a tiny label by 1.06 is
            // imperceptible. Amplify the growth so the label pops together
            // with its button.
            float companionScale = 1f + (scale - 1f) * 3.5f;
            for (android.view.View c : companions) {
                if (c == null) {
                    continue;
                }
                c.animate().cancel();
                c.animate().scaleX(companionScale).scaleY(companionScale).setDuration(90)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
            }
        }
    }

    private static void animateRestoreSync(android.view.View v,
            android.view.View[] companions) {
        v.animate().cancel();
        v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f))
                .start();
        if (companions != null) {
            for (android.view.View c : companions) {
                if (c == null) {
                    continue;
                }
                c.animate().cancel();
                c.animate().scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f))
                        .start();
            }
        }
    }

    public static void attachPressScale(final android.view.View v, final float scale, final boolean spring) {
        if (v == null) return;
        unclipAncestorsForScale(v);
        v.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(scale).scaleY(scale)
                            .setDuration(90)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (spring) {
                        view.animate().scaleX(1f).scaleY(1f)
                                .setDuration(220)
                                .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f))
                                .start();
                    } else {
                        view.animate().scaleX(1f).scaleY(1f)
                                .setDuration(180)
                                .setInterpolator(new androidx.interpolator.view.animation.FastOutSlowInInterpolator())
                                .start();
                    }
                    break;
            }
            return false; // never consume — clicks/long-clicks still fire
        });
    }
}
