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
import java.io.FileWriter;
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
    private static final int DEFAULT_MAX_LOG_LINES = 5000;
    private static final int TRIM_MARGIN = 120;

    // Async logging internals (batched, low-overhead)
    private static final java.util.concurrent.LinkedBlockingQueue<String> PENDING = new java.util.concurrent.LinkedBlockingQueue<>(5000);
    private static android.os.HandlerThread logThread;
    private static android.os.Handler logHandler;
    private static volatile boolean workerRunning = false;
    private static final int FLUSH_INTERVAL_MS = 250; // periodic flush cadence
    private static final int FLUSH_MAX_BATCH = 400;   // max lines per flush
    private static final java.util.concurrent.atomic.AtomicInteger approxLines = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final java.util.concurrent.atomic.AtomicBoolean trimming = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile int droppedSinceLastNote = 0;

    private static Context context;

    private static Uri fileUri;

    private static boolean isDebugEnabled = false;
    
    // Recording state (used for markers in the debug log)
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
                        int maxLines = getMaxLines();
                        if (current > maxLines) {
                            performTrimNow();
                        }
                        // Write a session header for easier diagnostics
                        appendHtmlToFile(
                            "<font color=\"#dc2626\" class=\"le severity-system\">" + getCurrentTimeStamp()
                                + " SYSTEM: Debug logging enabled. App=" + BuildConfig.VERSION_NAME
                                + " (" + BuildConfig.VERSION_CODE + "), Device=" + Build.MANUFACTURER + " " + Build.MODEL
                                + ", Android=" + Build.VERSION.RELEASE
                                + " (API " + Build.VERSION.SDK_INT + ")</font>"
                        );
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
     * Records a marker when recording/streaming state changes.
     *
     * @param active true when recording or streaming, false when stopped
     */
    public static void setRecordingActive(boolean active) {
        recordingActive = active;
        if (isDebugEnabled) {
            appendHtmlToFile(
                "<font color=\"#dc2626\" class=\"le severity-system\">" + getCurrentTimeStamp()
                    + " SYSTEM: Recording active=" + active + "</font>"
            );
        }
    }
    
    /**
     * Check if debug logging is actually enabled.
     */
    private static boolean isDebugLoggingActive() {
        return isDebugEnabled;
    }

    private static String getCurrentTimeStamp() {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return simpleDateFormat.format(new Date());
    }

    public static void d(String tag, String message) {
        if (!isDebugLoggingActive()) return;
        appendHtmlToFile(buildLogEntry("debug", tag, message));
    }

    public static void w(String tag, String message) {
        if (!isDebugLoggingActive()) return;
        appendHtmlToFile(buildLogEntry("warn", tag, message));
    }

    public static void e(String tag, Object... objects) {
        StringBuilder message = new StringBuilder();
        for(Object object: objects) {
            if(object instanceof String) {
                message.append(object);
            } else if(object instanceof Exception) {
                StringWriter stringWriter = new StringWriter();
                PrintWriter printWriter = new PrintWriter(stringWriter);
                ((Exception) object).printStackTrace(printWriter);
                message.append(stringWriter.toString());
            }
        }
        String logMessage = buildLogEntry("error", tag, message.toString());
        appendHtmlToFile(logMessage);
    }

    private static String buildLogEntry(String severity, String tag, String msg) {
        String level;
        String color;
        switch (severity) {
            case "error":   level = "ERROR";   color = "#dc2626"; break;
            case "warn":    level = "WARN";    color = "#d97706"; break;
            case "debug":   level = "DEBUG";   color = "#5b9bd5"; break;
            case "verbose": level = "VERBOSE"; color = "#8b8b8b"; break;
            case "system":  level = "SYSTEM";  color = "#dc2626"; break;
            default:        level = "INFO";    color = "#dddddd"; break;
        }
        return "<font color=\"" + color + "\" class=\"le severity-" + severity + "\">"
            + getCurrentTimeStamp() + " " + level + " [" + escapeHtml(tag) + "] " + escapeHtml(msg)
            + "</font>";
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\n", "<br>");
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

    public static void v(String tag, String message) {
        if (!isDebugLoggingActive()) return;
        appendHtmlToFile(buildLogEntry("verbose", tag, message));
    }

    public static void i(String tag, String s) {
        if (!isDebugLoggingActive()) return;
        appendHtmlToFile(buildLogEntry("info", tag, s));
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
                    batch.add("<font color=\"#d97706\" class=\"le severity-warn\">" + getCurrentTimeStamp()
                        + " WARN: Dropped " + droppedSinceLastNote + " debug lines due to backpressure</font><br>");
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
                    int maxLines = getMaxLines();
                    if (newCount > maxLines + TRIM_MARGIN) {
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
            int maxLines = getMaxLines();
            trimToMaxLines(maxLines);
        } catch (Exception ignored) {
        }
    }

    private static int getMaxLines() {
        try {
            if (context != null) {
                return SharedPreferencesManager.getInstance(context).getDebugMaxLines();
            }
        } catch (Exception ignored) {}
        return DEFAULT_MAX_LOG_LINES;
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

    public static String buildFullHtmlPage(Context ctx) {
        String raw = readLogAsHtml(ctx);
        if (raw != null) {
            raw = raw.replaceAll("(?s)<!DOCTYPE[^>]*>", "")
                     .replaceAll("(?s)<html[^>]*>", "")
                     .replaceAll("(?s)<head[^>]*>.*?</head>", "")
                     .replaceAll("(?s)<body[^>]*>", "")
                     .replaceAll("(?s)</body>.*?</html>", "")
                     .replaceAll("(?s)<script[^>]*>.*?</script>", "")
                     .replaceAll("(?s)<style[^>]*>.*?</style>", "")
                     .replaceAll("(?s)<meta[^>]*>", "")
                     .replaceAll("(?s)<title[^>]*>.*?</title>", "")
                     .replaceAll("<br\\s*/?>", "")
                     .replaceAll("<BR\\s*/?>", "")
                     .replaceAll("<font color=\"[^\"]*\" class=\"le severity-(\\w+)\">",
                         "<span class=\"le severity-$1\">")
                     .replaceAll("<font color=\"[^\"]*\">", "")
                     .replace("</font>", "");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"en\"><head>\n");
        sb.append("<meta charset=\"UTF-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n");
        sb.append("<title>FadCam · Debug Log</title>\n");
        sb.append("<style>\n");
        sb.append("*{margin:0;padding:0;box-sizing:border-box}\n");
        sb.append(":root{--bg:#0d0d0d;--surface:#141414;--border:#222;--text:#ddd;--muted:#777;--red:#dc2626;--red2:#b91c1c;--amber:#d97706;--green:#16a34a;--blue:#5b9bd5;--gray:#8b8b8b;--purple:#9333ea}\n");
        sb.append("html,body{height:100%}\n");
        sb.append("body{font-family:'SF Mono','Fira Code','JetBrains Mono',Consolas,monospace;background:var(--bg);color:var(--text);line-height:1.5;display:flex;flex-direction:column}\n");
        sb.append(".topbar{display:flex;align-items:center;justify-content:space-between;padding:10px 24px;border-bottom:2px solid var(--red);background:var(--surface);flex-wrap:wrap;gap:6px;flex-shrink:0}\n");
        sb.append(".topbar h1{font-size:15px;font-weight:700;color:var(--red);letter-spacing:1px;text-transform:uppercase}\n");
        sb.append(".topbar h1 span{color:var(--muted);font-weight:400;font-size:11px}\n");
        sb.append(".topbar .meta{font-size:11px;color:var(--muted)}\n");
        sb.append(".controls{padding:10px 24px;display:flex;gap:8px;flex-wrap:wrap;align-items:center;border-bottom:1px solid var(--border);background:var(--bg);position:sticky;top:0;z-index:10;flex-shrink:0}\n");
        sb.append(".controls input{flex:1;min-width:140px;max-width:320px;padding:7px 12px;background:var(--surface);border:1px solid var(--border);color:var(--text);font-size:12px;font-family:inherit;outline:none}\n");
        sb.append(".controls input:focus{border-color:var(--red)}\n");
        sb.append(".controls .nav-btn{font-family:inherit;font-size:13px;padding:5px 8px;border:1px solid var(--border);background:var(--surface);color:var(--muted);cursor:pointer;line-height:1}\n");
        sb.append(".controls .nav-btn:hover{color:var(--text);border-color:var(--red)}\n");
        sb.append(".controls .nav-count{font-size:10px;color:var(--muted);min-width:60px;text-align:center}\n");
        sb.append(".controls .filt{display:flex;gap:3px}\n");
        sb.append(".controls .filt button{font-family:inherit;font-size:10px;font-weight:600;padding:5px 10px;border:1px solid var(--border);background:var(--surface);color:var(--muted);cursor:pointer;text-transform:uppercase;letter-spacing:.5px}\n");
        sb.append(".controls .filt button.on{color:#fff}\n");
        sb.append(".controls .filt .fe.on{border-color:var(--red);color:var(--red)}\n");
        sb.append(".controls .filt .fw.on{border-color:var(--amber);color:var(--amber)}\n");
        sb.append(".controls .filt .fi.on{border-color:var(--green);color:var(--green)}\n");
        sb.append(".controls .filt .fd.on{border-color:var(--blue);color:var(--blue)}\n");
        sb.append(".controls .filt .fv.on{border-color:var(--gray);color:var(--gray)}\n");
        sb.append(".controls .filt .fs.on{border-color:var(--purple);color:var(--purple)}\n");
        sb.append(".controls .btn{font-family:inherit;font-size:11px;padding:7px 14px;border:1px solid var(--border);background:var(--surface);color:var(--text);cursor:pointer;white-space:nowrap}\n");
        sb.append(".controls .btn:hover{border-color:var(--red)}\n");
        sb.append(".controls .count{font-size:10px;color:var(--muted);white-space:nowrap}\n");
        sb.append(".log{padding:0 24px;flex:1;display:flex;flex-direction:column;overflow:hidden}\n");
        sb.append(".log-box{border:1px solid var(--border);border-top:none;background:var(--surface);flex:1;display:flex;flex-direction:column;overflow:hidden}\n");
        sb.append(".log-box .inner{flex:1;overflow-y:auto;counter-reset:lineno}\n");
        sb.append(".le{display:block;padding:2px 16px;font-size:12px;border-left:3px solid transparent;line-height:1.45}\n");
        sb.append(".le::before{counter-increment:lineno;content:counter(lineno)' | ';display:inline-block;width:60px;text-align:right;color:var(--muted);font-size:10px;margin-right:4px;flex-shrink:0;font-feature-settings:'tnum'}\n");
        sb.append(".le.severity-error{border-left-color:var(--red);color:#fca5a5}\n");
        sb.append(".le.severity-warn{border-left-color:var(--amber);color:#fcd34d}\n");
        sb.append(".le.severity-info{border-left-color:transparent;color:var(--text)}\n");
        sb.append(".le.severity-debug{border-left-color:transparent;color:#a0aec0}\n");
        sb.append(".le.severity-verbose{border-left-color:transparent;color:var(--muted);font-size:11px}\n");
        sb.append(".le.severity-system{border-left-color:var(--red);color:#fca5a5}\n");
        sb.append(".le.le-hidden{display:none!important}\n");
        sb.append(".le.le-match mark{background:rgba(220,38,38,0.3);color:#fff;border-radius:2px;padding:0 2px}\n");
        sb.append(".le.le-active mark{background:rgba(220,38,38,0.55);color:#fff;border-radius:2px;padding:0 2px}\n");
        sb.append(".empty{text-align:center;padding:60px 20px;color:var(--muted)}\n");
        sb.append(".toast{position:fixed;bottom:20px;left:50%;transform:translateX(-50%);background:var(--red);color:#fff;padding:8px 20px;font-size:12px;font-family:inherit;z-index:99;opacity:0;transition:opacity .2s;pointer-events:none}\n");
        sb.append(".toast.on{opacity:1}\n");
        sb.append("@media(max-width:640px){.topbar{padding:8px 12px}.controls{padding:8px 12px;gap:5px}.controls input{max-width:100%}.log{padding:0 6px}.le{padding:2px 8px;font-size:11px}}\n");
        sb.append("</style></head><body>\n");

        String today = new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(new Date());
        sb.append("<div class=\"topbar\"><h1>FADCAM <span>· DEBUG DOSSIER</span></h1><div class=\"meta\">");
        sb.append(today + " &nbsp; | &nbsp; " + Build.MANUFACTURER + " " + Build.MODEL + " &nbsp; | &nbsp; Android " + Build.VERSION.RELEASE + " &nbsp; | &nbsp; " + BuildConfig.VERSION_NAME);
        sb.append("</div></div>\n");

        sb.append("<div class=\"controls\">\n");
        sb.append("<input type=\"text\" id=\"q\" placeholder=\"Search logs...\" oninput=\"doFilter()\">\n");
        sb.append("<button class=\"nav-btn\" onclick=\"navMatch(-1)\" title=\"Previous match\">&#9650;</button>\n");
        sb.append("<button class=\"nav-btn\" onclick=\"navMatch(1)\" title=\"Next match\">&#9660;</button>\n");
        sb.append("<span class=\"nav-count\" id=\"navCnt\"></span>\n");
        sb.append("<div class=\"filt\">\n");
        sb.append("<button class=\"fe on\" data-s=\"error\" onclick=\"toggleFilt(this,'error')\">ERR</button>\n");
        sb.append("<button class=\"fw on\" data-s=\"warn\" onclick=\"toggleFilt(this,'warn')\">WRN</button>\n");
        sb.append("<button class=\"fi on\" data-s=\"info\" onclick=\"toggleFilt(this,'info')\">INF</button>\n");
        sb.append("<button class=\"fd on\" data-s=\"debug\" onclick=\"toggleFilt(this,'debug')\">DBG</button>\n");
        sb.append("<button class=\"fv on\" data-s=\"verbose\" onclick=\"toggleFilt(this,'verbose')\">VRB</button>\n");
        sb.append("<button class=\"fs on\" data-s=\"system\" onclick=\"toggleFilt(this,'system')\">SYS</button>\n");
        sb.append("</div>\n");
        sb.append("<button class=\"btn\" onclick=\"copyAll()\">COPY</button>\n");
        sb.append("<button class=\"btn\" id=\"btnWrap\" onclick=\"toggleWrap()\">WRAP</button>\n");
        sb.append("<span class=\"count\" id=\"cnt\"></span>\n");
        sb.append("</div>\n");

        sb.append("<div class=\"log\"><div class=\"log-box\"><div class=\"inner\" id=\"logBox\">\n");
        if (raw == null || raw.trim().isEmpty()) {
            sb.append("<div class=\"empty\">No log entries recorded.</div>\n");
        } else {
            sb.append(raw);
        }
        sb.append("</div></div></div>\n");
        sb.append("<div class=\"toast\" id=\"toast\">Copied</div>\n");

        sb.append("<script>\n");
        sb.append("window.onload=function(){\n");
        sb.append("var b=document.getElementById('logBox');if(!b)return;\n");
        sb.append("var q=document.getElementById('q');var nc=document.getElementById('navCnt');\n");
        sb.append("var cnt=document.getElementById('cnt');var bw=document.getElementById('btnWrap');\n");
        sb.append("var h={};var matches=[],mi=-1;\n");
        sb.append("function allEls(){return b.querySelectorAll('.le');}\n");
        sb.append("function visEls(){return b.querySelectorAll('.le:not(.le-hidden)');}\n");
        sb.append("function up(){if(cnt){var v=visEls().length;cnt.textContent=v;}}\n");
        sb.append("up();\n");
        sb.append("function markText(el,txt){if(!txt)return;var it=document.createNodeIterator(el,NodeFilter.SHOW_TEXT,null);var nodes=[];var n;while(n=it.nextNode())nodes.push(n);for(var i=0;i<nodes.length;i++){var node=nodes[i];var val=node.nodeValue;var idx=val.toLowerCase().indexOf(txt.toLowerCase());if(idx<0)continue;var before=val.substring(0,idx);var match=val.substring(idx,idx+txt.length);var after=val.substring(idx+txt.length);var span=document.createElement('mark');span.textContent=match;var frag=document.createDocumentFragment();if(before)frag.appendChild(document.createTextNode(before));frag.appendChild(span);if(after){var rest=document.createTextNode(after);frag.appendChild(rest);var restNodes=[rest];while(restNodes.length>0){var rn=restNodes.shift();var ri=rn.nodeValue.toLowerCase().indexOf(txt.toLowerCase());if(ri<0)break;var rb=rn.nodeValue.substring(0,ri);var rm=rn.nodeValue.substring(ri,ri+txt.length);var ra=rn.nodeValue.substring(ri+txt.length);var ms=document.createElement('mark');ms.textContent=rm;rn.nodeValue=rb;if(ra){var nr=document.createTextNode(ra);rn.parentNode.insertBefore(nr,rn.nextSibling);restNodes.push(nr);}rn.parentNode.insertBefore(ms,rn.nextSibling);}}node.parentNode.replaceChild(frag,node);}}\n");
        sb.append("window.toggleFilt=function(btn,sev){btn.classList.toggle('on');h[sev]=!btn.classList.contains('on');allEls().forEach(function(e){var m=e.className.match(/severity-(\\w+)/);if(m&&h[m[1]])e.classList.add('le-hidden');else if(m&&!h[m[1]])e.classList.remove('le-hidden');});up();doFilter();};\n");
        sb.append("function clearMarks(){var marks=document.querySelectorAll('#logBox mark');for(var i=0;i<marks.length;i++){var p=marks[i].parentNode;while(marks[i].firstChild)p.insertBefore(marks[i].firstChild,marks[i]);p.removeChild(marks[i]);p.normalize();}}\n");
        sb.append("window.doFilter=function(){var t=(q?q.value:'').toLowerCase();clearMarks();matches=[];mi=-1;var els=visEls();for(var i=0;i<els.length;i++){els[i].classList.remove('le-match','le-active');els[i].style.display='';if(t&&els[i].textContent.toLowerCase().indexOf(t)>=0){matches.push(els[i]);els[i].classList.add('le-match');markText(els[i],t);}else if(t){els[i].style.display='none';}}if(matches.length>0){mi=0;matches[0].classList.add('le-active');setTimeout(function(){try{matches[0].scrollIntoView({block:'center'})}catch(e){}},10);}updateNav();};\n");
        sb.append("function navMatch(dir){if(matches.length===0)return;matches[mi].classList.remove('le-active');mi=(mi+dir+matches.length)%matches.length;matches[mi].classList.add('le-active');setTimeout(function(){try{matches[mi].scrollIntoView({block:'center'})}catch(e){}},10);updateNav();}\n");
        sb.append("window.navMatch=navMatch;\n");
        sb.append("function updateNav(){if(nc)nc.textContent=matches.length>0?(mi+1)+'/'+matches.length:'';}\n");
        sb.append("window.copyAll=function(){var l=[];visEls().forEach(function(e){if(e.style.display!=='none')l.push(e.textContent);});var t=l.join('\\n');try{navigator.clipboard.writeText(t).then(function(){var x=document.getElementById('toast');if(x){x.classList.add('on');setTimeout(function(){x.classList.remove('on')},1800);}});}catch(ex){var ta=document.createElement('textarea');ta.value=t;ta.style.position='fixed';ta.style.left='-9999px';document.body.appendChild(ta);ta.select();document.execCommand('copy');document.body.removeChild(ta);var x=document.getElementById('toast');if(x){x.classList.add('on');setTimeout(function(){x.classList.remove('on')},1800);}}};\n");
        sb.append("window.toggleWrap=function(){var w=bw?bw.textContent:'WRAP';var nw=w==='WRAP'?'UNWRAP':'WRAP';allEls().forEach(function(e){e.style.whiteSpace=nw==='UNWRAP'?'nowrap':'normal';});if(bw)bw.textContent=nw;};\n");
        sb.append("if(q)q.addEventListener('keydown',function(e){if(e.key==='ArrowUp'){e.preventDefault();navMatch(-1);}else if(e.key==='ArrowDown'){e.preventDefault();navMatch(1);}});\n");
        sb.append("};\n");
        sb.append("</script></body></html>");
        return sb.toString();
    }

    public static Uri getFullHtmlPageUri(Context ctx) {
        try {
            String full = buildFullHtmlPage(ctx);
            java.io.File tmpDir = new java.io.File(ctx.getExternalCacheDir(), "debug_share");
            if (!tmpDir.exists()) tmpDir.mkdirs();
            java.io.File tmp = new java.io.File(tmpDir, "FadCam_Debug.html");
            java.io.FileWriter fw = null;
            try {
                fw = new java.io.FileWriter(tmp);
                fw.write(full);
            } finally {
                if (fw != null) try { fw.close(); } catch (Exception ignored) {}
            }
            return androidx.core.content.FileProvider.getUriForFile(
                ctx, ctx.getPackageName() + ".provider", tmp);
        } catch (Exception e) {
            return getLogUri(ctx);
        }
    }

    public static int getCachedDebugPort(Context ctx) {
        return 0;
    }

    static class DebugHtmlServer extends fi.iki.elonen.NanoHTTPD {
        private final String html;
        DebugHtmlServer(String html, int port) { super(port); this.html = html; }
        @Override public Response serve(IHTTPSession session) {
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html);
        }
    }
}
