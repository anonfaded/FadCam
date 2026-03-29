package com.fadcam;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FLog: Privacy-aware logging wrapper.
 * Writes to both android.util.Log and the in-app HTML log (com.fadcam.Log).
 *
 * Use this for user-facing debug logging to avoid leaking sensitive data.
 */
public final class FLog {
    private static final String DEFAULT_TAG = "FadCam";

    /**
     * Redactor hook for custom scrubbing. Called after default redactions.
     */
    public interface Redactor {
        String redact(String input);
    }

    private static volatile Redactor redactor = null;

    // Basic redaction patterns (intentionally conservative)
    private static final Pattern URL_PATTERN = Pattern.compile("(https?://[^\\s\\]]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IP_PATTERN = Pattern.compile(
        "\\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\b"
    );
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
        "(?i)\\b(token|apikey|api_key|access_key|secret|password|pass|auth|bearer)\\b\\s*[:=]\\s*([A-Za-z0-9._\\-+/=]{6,})"
    );
    private static final Pattern FILE_PATH_PATTERN = Pattern.compile(
        "(?i)(/storage/[^\\s\"']+|/sdcard/[^\\s\"']+|/data/[^\\s\"']+|/Users/[^\\s\"']+|C:\\\\[^\\s\"']+)"
    );

    private FLog() {}

    public static void setRedactor(Redactor customRedactor) {
        redactor = customRedactor;
    }

    public static void d(String tag, String message) {
        log(android.util.Log.DEBUG, tag, message, null);
    }

    public static void d(String tag, String message, Throwable t) {
        log(android.util.Log.DEBUG, tag, message, t);
    }

    public static void i(String tag, String message) {
        log(android.util.Log.INFO, tag, message, null);
    }

    public static void i(String tag, String message, Throwable t) {
        log(android.util.Log.INFO, tag, message, t);
    }

    public static void w(String tag, String message) {
        log(android.util.Log.WARN, tag, message, null);
    }

    public static void w(String tag, String message, Throwable t) {
        log(android.util.Log.WARN, tag, message, t);
    }

    public static void e(String tag, String message) {
        log(android.util.Log.ERROR, tag, message, null);
    }

    public static void e(String tag, String message, Throwable t) {
        log(android.util.Log.ERROR, tag, message, t);
    }

    public static void v(String tag, String message) {
        log(android.util.Log.VERBOSE, tag, message, null);
    }

    public static void v(String tag, String message, Throwable t) {
        log(android.util.Log.VERBOSE, tag, message, t);
    }

    public static void wtf(String tag, String message) {
        log(android.util.Log.ASSERT, tag, message, null);
    }

    public static void wtf(String tag, String message, Throwable t) {
        log(android.util.Log.ASSERT, tag, message, t);
    }

    public static void d(String tag, Object... objects) {
        log(android.util.Log.DEBUG, tag, buildMessage(objects), null);
    }

    public static void i(String tag, Object... objects) {
        log(android.util.Log.INFO, tag, buildMessage(objects), null);
    }

    public static void w(String tag, Object... objects) {
        log(android.util.Log.WARN, tag, buildMessage(objects), null);
    }

    public static void e(String tag, Object... objects) {
        log(android.util.Log.ERROR, tag, buildMessage(objects), null);
    }

    public static void v(String tag, Object... objects) {
        log(android.util.Log.VERBOSE, tag, buildMessage(objects), null);
    }

    public static void wtf(String tag, Object... objects) {
        log(android.util.Log.ASSERT, tag, buildMessage(objects), null);
    }

    private static void log(int priority, String tag, String message, Throwable t) {
        String safeTag = (tag == null || tag.isEmpty()) ? DEFAULT_TAG : tag;
        String baseMsg = message == null ? "" : message;
        if (t != null) {
            baseMsg = baseMsg + "\\n" + android.util.Log.getStackTraceString(t);
        }
        String safeMsg = redact(baseMsg);

        // android.util.Log
        switch (priority) {
            case android.util.Log.DEBUG:
                android.util.Log.d(safeTag, safeMsg);
                Log.d(safeTag, safeMsg);
                break;
            case android.util.Log.INFO:
                android.util.Log.i(safeTag, safeMsg);
                Log.i(safeTag, safeMsg);
                break;
            case android.util.Log.WARN:
                android.util.Log.w(safeTag, safeMsg);
                Log.w(safeTag, safeMsg);
                break;
            case android.util.Log.VERBOSE:
                android.util.Log.v(safeTag, safeMsg);
                Log.v(safeTag, safeMsg);
                break;
            case android.util.Log.ASSERT:
                android.util.Log.wtf(safeTag, safeMsg);
                Log.e(safeTag, safeMsg);
                break;
            case android.util.Log.ERROR:
            default:
                android.util.Log.e(safeTag, safeMsg);
                Log.e(safeTag, safeMsg);
                break;
        }
    }

    private static String buildMessage(Object... objects) {
        if (objects == null || objects.length == 0) return "";
        StringBuilder message = new StringBuilder();
        for (Object object : objects) {
            if (object == null) continue;
            if (object instanceof String) {
                message.append(object);
            } else if (object instanceof Exception) {
                StringWriter stringWriter = new StringWriter();
                PrintWriter printWriter = new PrintWriter(stringWriter);
                ((Exception) object).printStackTrace(printWriter);
                message.append(stringWriter.toString());
            } else {
                message.append(String.valueOf(object));
            }
        }
        return message.toString();
    }

    private static String redact(String input) {
        if (input == null || input.isEmpty()) return "";
        String out = input;

        // Scrub URLs
        out = URL_PATTERN.matcher(out).replaceAll("[REDACTED_URL]");
        
        // Scrub IP addresses
        out = IP_PATTERN.matcher(out).replaceAll("[REDACTED_IP]");

        // Scrub obvious tokens
        Matcher tokenMatcher = TOKEN_PATTERN.matcher(out);
        StringBuffer sb = new StringBuffer();
        while (tokenMatcher.find()) {
            tokenMatcher.appendReplacement(sb, tokenMatcher.group(1) + "=REDACTED");
        }
        tokenMatcher.appendTail(sb);
        out = sb.toString();

        // Scrub file paths
        out = FILE_PATH_PATTERN.matcher(out).replaceAll("[REDACTED_PATH]");

        // Optional custom redactor
        Redactor r = redactor;
        if (r != null) {
            try {
                out = r.redact(out);
            } catch (Exception ignored) {
                // Never crash logging
            }
        }

        return out;
    }
}
