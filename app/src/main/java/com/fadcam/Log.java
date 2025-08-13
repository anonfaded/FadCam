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

    private static Context context;

    private static Uri fileUri;

    private static boolean isDebugEnabled = false;

    public static void init(Context context)
    {
        Log.context = context;
        
        // Check SharedPreferences for debug setting
        SharedPreferencesManager sharedPreferencesManager = SharedPreferencesManager.getInstance(context);
        isDebugEnabled = sharedPreferencesManager.isDebugLoggingEnabled();

    if (isDebugEnabled) {
            // -------------- Fix Start for this method(init)-----------
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
            // -------------- Fix Ended for this method(init)-----------
        }
    }

    public static void setDebugEnabled(boolean enabled) {
        isDebugEnabled = enabled;
        if (enabled) {
            // -------------- Fix Start for this method(setDebugEnabled)-----------
            // Ensure the single log file exists; do NOT wipe it. We keep a rolling window via trim.
            createHtmlFile(context, DEBUG_FILE_NAME);
            // -------------- Fix Ended for this method(setDebugEnabled)-----------
        }
    }

    private static String getCurrentTimeStamp() {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return simpleDateFormat.format(new Date());
    }

    public static void d(String tag, String message) {
        if (!isDebugEnabled) return;
        
        String logMessage = "<font color=\"34495e\">" + getCurrentTimeStamp() + " INFO: [" + tag + "]" + message + "</font>";
        appendHtmlToFile(logMessage);
    }

    public static void w(String tag, String message) {
        if (!isDebugEnabled) return;
        
        String logMessage = "<font color=\"f1c40f\">" + getCurrentTimeStamp() + " WARNING: [" + tag + "]" + message + "</font>";
        appendHtmlToFile(logMessage);
    }

    public static void e(String tag, Object... objects) {
        if (!isDebugEnabled) return;
        
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
        // -------------- Fix Start for this method(createHtmlFile)-----------
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
        // -------------- Fix Ended for this method(createHtmlFile)-----------
    }

    public static void appendHtmlToFile(String htmlContent) {
        if (!isDebugEnabled) return;

        // -------------- Fix Start for this method(appendHtmlToFile)-----------
        // Ensure target URI exists or disable logging to avoid NPE on OEMs
        if (fileUri == null) {
            Uri created = createHtmlFile(context, DEBUG_FILE_NAME);
            if (created == null) {
                // Give up silently to avoid disrupting app flow (esp. during onStop)
                return;
            }
        }

        OutputStream outputStream = null;
        try {
            if ("content".equalsIgnoreCase(fileUri.getScheme())) {
                outputStream = context.getContentResolver().openOutputStream(fileUri, "wa");
            } else if ("file".equalsIgnoreCase(fileUri.getScheme())) {
                // App-private fallback path
                File file = new File(fileUri.getPath());
                //noinspection IOStreamConstructor
                outputStream = new java.io.FileOutputStream(file, true);
            }

            if (outputStream != null) {
        // Always terminate each entry with a single <br> delimiter
        outputStream.write(htmlContent.getBytes());
        outputStream.write("<br>".getBytes());
            }
        } catch (Exception e) {
            // As a last resort, disable debug logging to avoid repeated failures
            isDebugEnabled = false;
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (Exception ignored) {}
            }
        }
    // After append, enforce max lines cap for robustness with "one in, one out" effect
    try { trimToMaxLines(MAX_LOG_LINES); } catch (Exception ignored) {}
        // -------------- Fix Ended for this method(appendHtmlToFile)-----------
    }

    // -------------- Fix Start: Helpers for manage/share/delete/read -----------
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
    // -------------- Fix End: Helpers for manage/share/delete/read -----------

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
}
