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
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Log {
    private static File logFile;

    private static final String TAG = "Log";

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
            createHtmlFile(context, "debug.html");
        }
    }

    public static void setDebugEnabled(boolean enabled) {
        isDebugEnabled = enabled;
        if (enabled) {
            createHtmlFile(context, "debug.html");
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
        try {
            // Use a static filename for debug log
            String debugFileName = "FADCAM_debug.html";

            Uri existingFileUri = checkIfFileExists(context, debugFileName);

            if (existingFileUri != null) {
                return existingFileUri;
            }

            ContentValues contentValues = new ContentValues();
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, debugFileName);
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "text/html");
            contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FadCam");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                fileUri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues);
            } else {
                fileUri = Uri.parse("file://" + context.getExternalFilesDir(null).getPath() + "/Download/" + debugFileName);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    public static void appendHtmlToFile(String htmlContent) {
        if (!isDebugEnabled) return;
        
        OutputStream outputStream = null;

        try {
            outputStream = context.getContentResolver().openOutputStream(fileUri, "wa");
            if (outputStream != null) {
                outputStream.write(htmlContent.getBytes());
                outputStream.write(("</br>").getBytes());
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
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
}
