package com.fadcam;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Log {
    private static File logFile;

    private static final String TAG = "Log";
    private static final String DEBUG_FILE_NAME = "FADCAM_debug.html";
    private static final int MAX_LOG_LINES = 1000;

    // Async logging internals (batched, low-overhead)
    private static final java.util.concurrent.LinkedBlockingQueue<String> PENDING = new java.util.concurrent.LinkedBlockingQueue<>(5000);
    private static android.os.HandlerThread logThread;
    private static android.os.Handler logHandler;
    private static volatile boolean workerRunning = false;
    private static final int FLUSH_INTERVAL_MS = 250; // periodic flush cadence
    private static final int FLUSH_MAX_BATCH = 400;   // max lines per flush
    private static final int TRIM_MARGIN = 120;       // allow small overage before trimming
    private static final java.util.concurrent.atomic.AtomicInteger approxLines = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final java.util.concurrent.atomic.AtomicBoolean trimming = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile int droppedSinceLastNote = 0;

    private static Context context;

    private static Uri fileUri;

    private static boolean isDebugEnabled = false;
    
    // Auto-disable debug logging during recording/streaming to save CPU and battery
    private static volatile boolean recordingActive = false;

    public static void init(Context context)
    {
        Log.context = context;
        
        // Check SharedPreferences for debug setting
        SharedPreferencesManager sharedPreferencesManager = SharedPreferencesManager.getInstance(context);
        isDebugEnabled = sharedPreferencesManager.isDebugLoggingEnabled();

    if (isDebugEnabled) {
            // Try to create the debug log target; gracefully degrade if unavailable
            try {
        Uri created = createHtmlFile(context, DEBUG_FILE_NAME);
                if (created == null) {
                    // Creation failed (OEM-specific scoped storage quirks). Disable to avoid NPE spam
                    isDebugEnabled = false;
                }
            } catch (Exception e) {
                // Disable debug logging to prevent crashes on OEMs (e.g., Huawei EMUI 10)
                isDebugEnabled = false;
            }
            // Start async logger thread and precompute current line count; trim once if oversized
            if (isDebugEnabled) {
                startWorkerIfNeeded();
                // Initialize approxLines on background to avoid main thread work
                if (logHandler != null) {
                    logHandler.post(() -> {
                        int current = countLinesFast();
                        approxLines.set(current);
                        if (current > MAX_LOG_LINES) {
                            performTrimNow();
                        }
                    });
                }
            }
        }
    }

    public static void setDebugEnabled(boolean enabled) {
        isDebugEnabled = enabled;
        if (enabled) {
            // Ensure the single log file exists; do NOT wipe it. We keep a rolling window via trim.
            createHtmlFile(context, DEBUG_FILE_NAME);
            startWorkerIfNeeded();
        } else {
            stopWorkerAndFlush();
        }
    }
    
    /**
     * Signal to Log system that recording/streaming is active.
     * Automatically disables debug logging during recording to save CPU/battery.
     * 
     * @param active true when recording or streaming, false when stopped
     */
    public static void setRecordingActive(boolean active) {
        recordingActive = active;
        if (active && isDebugEnabled) {
            // Recording started - pause debug logging to save CPU/battery
            // Do NOT disable the feature; just pause writes
            // Re-enable automatically when recording stops
        } else if (!active && isDebugEnabled) {
            // Recording stopped - resume normal debug logging
        }
    }
    
    /**
     * Check if debug logging is actually enabled.
     * Returns false if recording is active (even if debug mode is on).
     */
    private static boolean isDebugLoggingActive() {
        return isDebugEnabled && !recordingActive;
    }

    private static String getCurrentTimeStamp() {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return simpleDateFormat.format(new Date());
    }

    public static void d(String tag, String message) {
        if (!isDebugLoggingActive()) return;
        
        String logMessage = "<font color=\"34495e\">" + getCurrentTimeStamp() + " INFO: [" + tag + "]" + message + "</font>";
        appendHtmlToFile(logMessage);
    }

    public static void w(String tag, String message) {
        if (!isDebugLoggingActive()) return;
        
        String logMessage = "<font color=\"f1c40f\">" + getCurrentTimeStamp() + " WARNING: [" + tag + "]" + message + "</font>";
        appendHtmlToFile(logMessage);
    }

    public static void e(String tag, Object... objects) {
        // NOTE: Keep ERROR logs even during recording - they indicate real problems
        // Only suppress DEBUG and WARN logs to save CPU during active recording
        
        StringBuilder message = new StringBuilder();
        for(Object object: objects)
        {
            if(object instanceof String)
            {
                message.append(object);
            }
            else if(object instanceof Exception)
            {
                StringWriter stringWriter = new StringWriter();
                PrintWriter printWriter = new PrintWriter(stringWriter);
                ((Exception) object).printStackTrace(printWriter);
                String stackTrace = stringWriter.toString();
                message.append(stackTrace);
            }
        }

        String logMessage = "<font color=\"e74c3c\">" + getCurrentTimeStamp() + " ERROR: [" + tag + "]" + message + "</font>";
        appendHtmlToFile(logMessage);
    }

    public static Uri createHtmlFile(Context context, String fileName) {
        try {
            // Use a static filename for debug log to simplify discovery
            String debugFileName = DEBUG_FILE_NAME;

            // If already created, return it
            if (fileUri != null) {
                return fileUri;
            }

            // First, try MediaStore Downloads (preferred when available)
            Uri existingFileUri = checkIfFileExists(context, debugFileName);
            if (existingFileUri != null) {
                fileUri = existingFileUri;
                return fileUri;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    ContentValues contentValues = new ContentValues();
                    contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, debugFileName);
                    contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "text/html");
                    contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FadCam");
                    fileUri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues);
                } catch (Exception mediaStoreEx) {
                    // Some OEMs (e.g., Huawei EMUI 10) can deny inserts unpredictably
                    fileUri = null; // fall through to app-private fallback
                }
            }

            // Fallback: App-specific external storage (no special permissions; works across OEMs)
            if (fileUri == null) {
                File downloadsDir = new File(context.getExternalFilesDir(null), "Download");
                if (!downloadsDir.exists()) {
                    // Best-effort create directory
                    //noinspection ResultOfMethodCallIgnored
                    downloadsDir.mkdirs();
                }
                File file = new File(downloadsDir, debugFileName);
                fileUri = Uri.fromFile(file); // file:// scheme; handled specially when writing
            }

            return fileUri;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void appendHtmlToFile(String htmlContent) {
        if (!isDebugEnabled) return;

        // Ensure target URI exists or disable logging to avoid NPE on OEMs
        if (fileUri == null) {
            Uri created = createHtmlFile(context, DEBUG_FILE_NAME);
            if (created == null) {
                // Give up silently to avoid disrupting app flow (esp. during onStop)
                return;
            }
        }
        // Ensure worker is up
        startWorkerIfNeeded();

        // Offer line to queue; drop oldest if saturated to avoid UI jank during recording
        String line = htmlContent + "<br>";
        boolean offered = PENDING.offer(line);
        if (!offered) {
            // Try to make room by dropping one oldest; then attempt again
            PENDING.poll();
            if (!PENDING.offer(line)) {
                // Count drops to emit a single notice later
                droppedSinceLastNote++;
            }
        }
    }

    /** Returns the current log Uri if available, creating it if needed. */
    public static Uri getLogUri(Context ctx) {
        if (fileUri == null) {
            createHtmlFile(ctx, DEBUG_FILE_NAME);
        }
        return fileUri;
    }

    /** Returns true if a log file currently exists on disk/storage. */
    public static boolean logFileExists(Context ctx){
        try {
            Uri uri = getLogUri(ctx);
            if (uri == null) return false;
            if ("content".equalsIgnoreCase(uri.getScheme())) {
                // Try open for read
                java.io.InputStream in = ctx.getContentResolver().openInputStream(uri);
                if (in != null) { in.close(); return true; }
                return false;
            } else {
                File f = new File(uri.getPath());
                return f.exists() && f.length() > 0;
            }
        } catch (Exception e){
            return false;
        }
    }

    /** Returns a sharable Uri (content:// preferred). */
    public static Uri getSharableLogUri(Context ctx) {
        Uri uri = getLogUri(ctx);
        if (uri == null) return null;
        if ("content".equalsIgnoreCase(uri.getScheme())) return uri;
        // Convert file:// to content:// using FileProvider
        try {
            File f = new File(uri.getPath());
        return androidx.core.content.FileProvider.getUriForFile(
            ctx,
            ctx.getPackageName() + ".provider",
            f);
        } catch (Exception e) {
            return null;
        }
    }

    /** Deletes the log file and clears cached Uri. */
    public static boolean deleteLog(Context ctx) {
        try {
            if (fileUri == null) {
                createHtmlFile(ctx, DEBUG_FILE_NAME);
            }
            if (fileUri == null) return false;
            boolean success = false;
            if ("content".equalsIgnoreCase(fileUri.getScheme())) {
                success = ctx.getContentResolver().delete(fileUri, null, null) > 0;
            } else if ("file".equalsIgnoreCase(fileUri.getScheme())) {
                File f = new File(fileUri.getPath());
                success = f.exists() && f.delete();
            }
            if (success) fileUri = null;
            return success;
        } catch (Exception e) {
            return false;
        }
    }

    /** Reads the raw HTML content of the log file. Returns empty string if missing. */
    public static String readLogAsHtml(Context ctx) {
        try {
            Uri uri = getLogUri(ctx);
            if (uri == null) return "";
            java.io.InputStream in;
            if ("content".equalsIgnoreCase(uri.getScheme())) {
                in = ctx.getContentResolver().openInputStream(uri);
            } else {
                in = new java.io.FileInputStream(new File(uri.getPath()));
            }
            if (in == null) return "";
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(in))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                return sb.toString();
            }
        } catch (Exception e) {
            return "";
        }
    }

    /** Trims the log to the last maxLines lines. */
    private static void trimToMaxLines(int maxLines) {
        if (context == null || fileUri == null) return;
        String html = readLogAsHtml(context);
        if (html.isEmpty()) return;
        // Split on common HTML break tags used in our file
    String normalized = html.replace("</br>", "<br>").replace("<br/>", "<br>").replace("<BR>", "<br>");
    String[] parts = normalized.split("<br>\n?|\n");
        if (parts.length <= maxLines) return;
    // Keep only last maxLines (drop oldest; new entries keep appending)
        StringBuilder sb = new StringBuilder();
        int start = parts.length - maxLines;
        for (int i = start; i < parts.length; i++) {
            sb.append(parts[i]);
            if (i < parts.length - 1) sb.append("<br>");
        }
        // Rewrite file with truncated content
        writeAllHtml(sb.toString());
    approxLines.set(Math.min(maxLines, parts.length));
    }

    /** Rewrites the entire log content. */
    private static void writeAllHtml(String html) {
        OutputStream os = null;
        try {
            if (fileUri == null) return;
            if ("content".equalsIgnoreCase(fileUri.getScheme())) {
                os = context.getContentResolver().openOutputStream(fileUri, "w"); // truncate
            } else if ("file".equalsIgnoreCase(fileUri.getScheme())) {
                os = new java.io.FileOutputStream(new File(fileUri.getPath()), false);
            }
            if (os != null) {
                os.write(html.getBytes());
            }
        } catch (Exception ignored) {
        } finally {
            if (os != null) try { os.close(); } catch (Exception ignored) {}
        }
    }

    private static Uri checkIfFileExists(Context context, String fileName) {
        Uri uri = null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        } else {
            String downloadsPath = context.getExternalFilesDir(null).getPath() + "/Download";
            File downloadsDir = new File(downloadsPath);

            if (!downloadsDir.exists()) {
                boolean dirCreated = downloadsDir.mkdirs();
                if (!dirCreated) {
                    return null;
                }
            }

            File file = new File(downloadsDir, fileName);
            uri = Uri.fromFile(file);
        }

        String[] projection = {MediaStore.MediaColumns._ID};
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=?";
        String[] selectionArgs = new String[]{fileName};

        Cursor cursor = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            cursor = resolver.query(uri, projection, selection, selectionArgs, null);

            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                return ContentUris.withAppendedId(uri, id);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return null;
    }

    public static void v(String sharedPrefs, String s) {
    }

    public static void i(String tag, String s) {
    }

    // -------------------- Async logger worker helpers --------------------
    private static void startWorkerIfNeeded() {
        if (!isDebugEnabled) return;
        if (workerRunning && logThread != null && logHandler != null) return;
        logThread = new android.os.HandlerThread("FadCamLog");
        logThread.start();
        logHandler = new android.os.Handler(logThread.getLooper());
        workerRunning = true;
        // Kick off periodic flusher
        logHandler.post(flushTask);
    }

    private static final Runnable flushTask = new Runnable() {
        @Override public void run() {
            try {
                if (!isDebugEnabled || context == null || fileUri == null) {
                    // Re-check later; cheap idle
                    reschedule(FLUSH_INTERVAL_MS);
                    return;
                }
                java.util.ArrayList<String> batch = new java.util.ArrayList<>(FLUSH_MAX_BATCH + 2);
                PENDING.drainTo(batch, FLUSH_MAX_BATCH);

                // If we dropped lines earlier due to backpressure, record a single notice
                if (droppedSinceLastNote > 0) {
                    batch.add("<font color=\"#f1c40f\">Dropped " + droppedSinceLastNote + " debug lines due to backpressure</font><br>");
                    droppedSinceLastNote = 0;
                }

                if (!batch.isEmpty()) {
                    OutputStream os = null;
                    try {
                        if ("content".equalsIgnoreCase(fileUri.getScheme())) {
                            os = context.getContentResolver().openOutputStream(fileUri, "wa");
                        } else if ("file".equalsIgnoreCase(fileUri.getScheme())) {
                            os = new java.io.FileOutputStream(new File(fileUri.getPath()), true);
                        }
                        if (os != null) {
                            // Concatenate once to minimize writes
                            StringBuilder sb = new StringBuilder(4096);
                            for (String s : batch) sb.append(s);
                            os.write(sb.toString().getBytes());
                        }
                    } catch (Exception e) {
                        // Disable to avoid tight error loops
                        isDebugEnabled = false;
                    } finally {
                        if (os != null) try { os.close(); } catch (Exception ignored) {}
                    }
                    int newCount = approxLines.addAndGet(batch.size());
                    if (newCount > MAX_LOG_LINES + TRIM_MARGIN) {
                        scheduleTrim();
                    }
                }
            } finally {
                reschedule(FLUSH_INTERVAL_MS);
            }
        }

        private void reschedule(int delayMs) {
            if (logHandler != null) logHandler.postDelayed(this, delayMs);
        }
    };

    private static void scheduleTrim() {
        if (!trimming.compareAndSet(false, true)) return;
        if (logHandler == null) { trimming.set(false); return; }
        logHandler.post(() -> {
            try {
                performTrimNow();
            } finally {
                trimming.set(false);
            }
        });
    }

    private static void performTrimNow() {
        try {
            trimToMaxLines(MAX_LOG_LINES);
        } catch (Exception ignored) {
        }
    }

    private static int countLinesFast() {
        try {
            String html = readLogAsHtml(context);
            if (html == null || html.isEmpty()) return 0;
            // Normalize common break variants to <br>
            String normalized = html.replace("</br>", "<br>").replace("<br/>", "<br>").replace("<BR>", "<br>").replace("<br />", "<br>");
            if (normalized.isEmpty()) return 0;
            int count = 0;
            int idx = -1;
            while (true) {
                idx = normalized.indexOf("<br>", idx + 1);
                if (idx < 0) break;
                count++;
            }
            // Fallback: if no <br> found but content exists, count as 1 line
            return count > 0 ? count : 1;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static void stopWorkerAndFlush() {
        // Attempt a best-effort final flush synchronously
        if (logThread == null || logHandler == null) return;
        try {
            java.util.ArrayList<String> rest = new java.util.ArrayList<>();
            PENDING.drainTo(rest);
            if (!rest.isEmpty() && context != null && fileUri != null) {
                OutputStream os = null;
                try {
                    if ("content".equalsIgnoreCase(fileUri.getScheme())) {
                        os = context.getContentResolver().openOutputStream(fileUri, "wa");
                    } else if ("file".equalsIgnoreCase(fileUri.getScheme())) {
                        os = new java.io.FileOutputStream(new File(fileUri.getPath()), true);
                    }
                    if (os != null) {
                        StringBuilder sb = new StringBuilder(4096);
                        for (String s : rest) sb.append(s);
                        os.write(sb.toString().getBytes());
                    }
                } catch (Exception ignored) {
                } finally {
                    if (os != null) try { os.close(); } catch (Exception ignored2) {}
                }
                approxLines.addAndGet(rest.size());
            }
        } finally {
            try { logHandler.removeCallbacksAndMessages(null); } catch (Exception ignored) {}
            try { logThread.quitSafely(); } catch (Exception ignored) {}
            logHandler = null;
            logThread = null;
            workerRunning = false;
        }
    }
}
