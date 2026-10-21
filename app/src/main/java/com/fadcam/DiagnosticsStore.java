package com.fadcam;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Durable diagnostics for process death — the only thing that survives a native crash, an
 * ANR, or a low-memory kill.
 *
 * <p>Everything here writes to {@code filesDir}, never {@code cacheDir}, and never goes
 * through {@link Log}/{@link FLog}: both depend on {@link Log#init(Context)} having run,
 * which happens only when {@code HomeFragment} is created. The whole point of this class is
 * to capture data <em>before</em> that, so it must not depend on it.
 *
 * <p>Flow:
 * <ol>
 *   <li>{@link #captureExitReasons(Context)} runs first thing in {@code Application.onCreate}
 *       and persists the platform's {@link ActivityManager#getHistoricalProcessExitReasons}
 *       for the PREVIOUS process.</li>
 *   <li>{@link #recordCrash} persists a fatal Java exception from the uncaught handler.</li>
 *   <li>{@link #consumeForDossier(Context)} is called once logging is actually live, moves
 *       both into the HTML debug log, and deletes the files so they are not repeated on
 *       every launch.</li>
 * </ol>
 */
public final class DiagnosticsStore {

    private static final String TAG = "DiagnosticsStore";
    private static final String DIR = "diagnostics";
    private static final String FILE_EXIT_REASONS = "exit_reasons.txt";
    private static final String FILE_CRASH = "last_crash.txt";
    private static final String FILE_CRASH_PREV = "prev_crash.txt";

    /** Separate prefs file — must not depend on SharedPreferencesManager being usable yet. */
    private static final String PREFS = "fadcam_diagnostics_v1";
    private static final String KEY_LAST_EXIT_TS = "last_exit_ts";

    /** Caps so a runaway trace can never bloat the debug log. */
    private static final int MAX_TRACE_LINES = 120;
    private static final int MAX_FILE_BYTES = 256 * 1024;

    private DiagnosticsStore() {
    }

    // ------------------------------------------------------------------ exit reasons

    /**
     * Reads the platform's record of how the PREVIOUS process died and persists anything not
     * already reported in an earlier session.
     *
     * <p>Safe to call on every start; it is a no-op below API 30 and is fully guarded so a
     * broken OEM implementation can never take down {@code Application.onCreate}.
     */
    public static void captureExitReasons(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        try {
            ActivityManager am =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return;
            }
            List<ApplicationExitInfo> history =
                    am.getHistoricalProcessExitReasons(context.getPackageName(), 0, 5);
            if (history == null || history.isEmpty()) {
                return;
            }

            SharedPreferences prefs =
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long lastSeenTs = prefs.getLong(KEY_LAST_EXIT_TS, 0L);
            long newestTs = lastSeenTs;
            StringBuilder report = new StringBuilder();

            for (ApplicationExitInfo info : history) {
                long ts = info.getTimestamp();
                if (ts <= lastSeenTs) {
                    continue; // already reported in a previous session
                }
                if (ts > newestTs) {
                    newestTs = ts;
                }
                int reason = info.getReason();
                // Normal, user-initiated termination — not interesting.
                if (reason == ApplicationExitInfo.REASON_EXIT_SELF
                        || reason == ApplicationExitInfo.REASON_USER_REQUESTED
                        || reason == ApplicationExitInfo.REASON_USER_STOPPED) {
                    continue;
                }
                report.append("[EXIT-INFO] reason=").append(reason)
                        .append(" (").append(exitReasonName(reason)).append(")")
                        .append(" | status=").append(info.getStatus())
                        .append(" | at=").append(formatTimestamp(ts));
                String description = info.getDescription();
                if (description != null && !description.isEmpty()) {
                    report.append(" | description=").append(description);
                }
                report.append('\n');

                try (java.io.InputStream trace = info.getTraceInputStream()) {
                    if (trace != null) {
                        String text = readStream(trace);
                        if (!text.isEmpty()) {
                            report.append("---- trace (").append(exitReasonName(reason))
                                    .append(") ----\n").append(text).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                    // Trace is best-effort.
                }
            }

            // Advance the watermark even when everything was filtered, so normal exits are
            // not re-examined forever.
            prefs.edit().putLong(KEY_LAST_EXIT_TS, newestTs).apply();

            if (report.length() > 0) {
                writeFile(new File(diagnosticsDir(context), FILE_EXIT_REASONS),
                        report.toString());
            }
        } catch (Throwable t) {
            // Never let diagnostics break startup.
            android.util.Log.w(TAG, "captureExitReasons failed", t);
        }
    }

    // ------------------------------------------------------------------ crash capture

    /**
     * Persists a fatal exception. Called from the uncaught exception handler, i.e. while the
     * process is already dying — so: no logging framework, no allocation-heavy work, no
     * throwing.
     */
    public static void recordCrash(Context context, String threadName, String stackTrace) {
        try {
            File dir = diagnosticsDir(context);
            File current = new File(dir, FILE_CRASH);
            File previous = new File(dir, FILE_CRASH_PREV);
            if (current.exists()) {
                // Rotate so two crashes between launches are both preserved.
                if (previous.exists() && !previous.delete()) {
                    android.util.Log.w(TAG, "Could not clear previous crash file");
                }
                if (!current.renameTo(previous)) {
                    android.util.Log.w(TAG, "Could not rotate crash file");
                }
            }
            String text = "thread=" + threadName + "\n"
                    + "at=" + formatTimestamp(System.currentTimeMillis()) + "\n\n"
                    + stackTrace;
            writeFile(current, text);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "recordCrash failed", t);
        }
    }

    // ------------------------------------------------------------------ dossier ingest

    /**
     * Returns the captured diagnostics formatted for the in-app debug log and then removes
     * them, so each death is shown exactly once.
     *
     * @return a block ready to be appended as log lines, or {@code null} when there is nothing
     *     to report.
     */
    public static String consumeForDossier(Context context) {
        try {
            File dir = diagnosticsDir(context);
            StringBuilder out = new StringBuilder();
            boolean any = false;

            File exits = new File(dir, FILE_EXIT_REASONS);
            String exitText = readAndDelete(exits);
            if (exitText != null) {
                any = true;
                out.append("SYSTEM: ===== PREVIOUS PROCESS EXIT =====\n");
                out.append(exitText);
                out.append("SYSTEM: ===== END PREVIOUS PROCESS EXIT =====\n");
            }

            File prevCrash = new File(dir, FILE_CRASH_PREV);
            String prevText = readAndDelete(prevCrash);
            if (prevText != null) {
                any = true;
                out.append("ERROR: ===== PREVIOUS CRASH (older) =====\n");
                out.append(prevText);
                out.append('\n');
            }

            File crash = new File(dir, FILE_CRASH);
            String crashText = readAndDelete(crash);
            if (crashText != null) {
                any = true;
                out.append("ERROR: ===== PREVIOUS CRASH (fatal) =====\n");
                out.append(crashText);
                out.append('\n');
            }

            return any ? out.toString() : null;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "consumeForDossier failed", t);
            return null;
        }
    }

    // ------------------------------------------------------------------ helpers

    private static File diagnosticsDir(Context context) {
        File base = context.getFilesDir();
        File dir = new File(base, DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            android.util.Log.w(TAG, "Could not create diagnostics directory");
        }
        return dir;
    }

    private static void writeFile(File file, String text) {
        try (FileOutputStream fos = new FileOutputStream(file, false);
                OutputStreamWriter writer =
                        new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            if (text.length() > MAX_FILE_BYTES) {
                text = text.substring(0, MAX_FILE_BYTES);
            }
            writer.write(text);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "writeFile failed: " + file.getName(), t);
        }
    }

    /** Reads a file, deletes it, and returns its content (or {@code null} if empty/missing). */
    private static String readAndDelete(File file) {
        if (file == null || !file.exists() || file.length() == 0) {
            if (file != null && file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            return null;
        }
        String text = null;
        try (FileInputStream fis = new FileInputStream(file)) {
            text = readStream(fis);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "readAndDelete failed: " + file.getName(), t);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        return (text == null || text.trim().isEmpty()) ? null : text;
    }

    private static String readStream(java.io.InputStream stream) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        String line;
        int lines = 0;
        while ((line = reader.readLine()) != null && lines < MAX_TRACE_LINES) {
            sb.append(line).append('\n');
            lines++;
        }
        return sb.toString();
    }

    private static String formatTimestamp(long millis) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date(millis));
        } catch (Exception e) {
            return String.valueOf(millis);
        }
    }

    private static String exitReasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH:
                return "JAVA_CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return "NATIVE_CRASH";
            case ApplicationExitInfo.REASON_ANR:
                return "ANR";
            case ApplicationExitInfo.REASON_LOW_MEMORY:
                return "LOW_MEMORY_KILL";
            case ApplicationExitInfo.REASON_SIGNALED:
                return "SIGNALED";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return "INIT_FAILURE";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED:
                return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE:
                return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_OTHER:
                return "OTHER";
            default:
                return "UNKNOWN";
        }
    }
}
