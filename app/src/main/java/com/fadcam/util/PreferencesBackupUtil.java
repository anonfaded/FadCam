package com.fadcam.util;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import androidx.annotation.NonNull;

import com.fadcam.Constants;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PreferencesBackupUtil
 * Utility for exporting, importing, and resetting SharedPreferences to JSON.
 */
public final class PreferencesBackupUtil {

    private static final String TAG = "PrefsBackup";
    private PreferencesBackupUtil() {}

    // Keys we do NOT persist (ephemeral / runtime state)
    private static final java.util.Set<String> EXCLUDED_KEYS;
    static {
        java.util.HashSet<String> set = new java.util.HashSet<>();
        set.add(com.fadcam.Constants.PREF_IS_RECORDING_IN_PROGRESS); // runtime
        set.add("applock_session_unlocked"); // legacy session cache
        set.add("tab_lock_unlocked_home"); // per-tab session cache
        set.add("tab_lock_unlocked_records");
        set.add("tab_lock_unlocked_remote");
        set.add("tab_lock_unlocked_faditor");
        set.add("tab_lock_unlocked_settings");
        set.add("tab_lock_unlocked_lab");
        // Recording session state — not persistent config
        set.add(com.fadcam.Constants.PREF_RECORDING_START_TIME);
        set.add(com.fadcam.Constants.PREF_RECORDING_PAUSE_STARTED_AT);
        set.add(com.fadcam.Constants.PREF_RECORDING_ACCUMULATED_PAUSED_DURATION);
        // Add more runtime/transient keys here if discovered later
        EXCLUDED_KEYS = java.util.Collections.unmodifiableSet(set);
    }

    private static boolean isTransientKey(String key){
        return EXCLUDED_KEYS.contains(key);
    }

    /**
     * Builds a JSONObject representing all app SharedPreferences values.
     * Uses a type-annotated format to preserve Long vs Integer distinction:
     *   {"key": {"t": "long", "v": 5000}}
     * This prevents ClassCastException when restoring values that were originally
     * stored as Long but happen to fit in int range (e.g. watermark_update_interval).
     */
    @NonNull
    public static JSONObject buildBackupJson(Context context) throws JSONException {
        SharedPreferences sp = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        Map<String, ?> all = sp.getAll();
        JSONObject root = new JSONObject();
        for(Map.Entry<String, ?> e : all.entrySet()){
            if(isTransientKey(e.getKey())){ continue; }
            Object v = e.getValue();
            JSONObject entry = new JSONObject();
            if(v instanceof Boolean){
                entry.put("t", "bool");
                entry.put("v", (Boolean)v);
            } else if(v instanceof Integer){
                entry.put("t", "int");
                entry.put("v", (Integer)v);
            } else if(v instanceof Long){
                entry.put("t", "long");
                entry.put("v", (Long)v);
            } else if(v instanceof Float){
                entry.put("t", "float");
                entry.put("v", (Float)v);
            } else if(v instanceof String){
                entry.put("t", "string");
                entry.put("v", (String)v);
            } else if(v instanceof Set){
                entry.put("t", "string_set");
                JSONArray arr = new JSONArray();
                for(Object o : (Set<?>) v){
                    arr.put(String.valueOf(o));
                }
                entry.put("v", arr);
            } else {
                FLog.w(TAG, "Skipping unsupported pref type for key: " + e.getKey());
                continue;
            }
            root.put(e.getKey(), entry);
        }
        return root;
    }

    /**
     * Writes the given JSON to a document URI selected by the user.
     */
    public static void writeJsonToUri(Context context, Uri uri, JSONObject json) throws IOException {
        if(uri == null) throw new IOException("Target URI is null");
        try(OutputStream os = context.getContentResolver().openOutputStream(uri, "wt");
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))){
            String pretty;
            try { pretty = json.toString(2); } catch (org.json.JSONException e){ pretty = json.toString(); }
            bw.write(pretty);
            bw.flush();
        }
    }

    /**
     * Reads JSON from provided URI.
     */
    @NonNull
    public static JSONObject readJsonFromUri(Context context, Uri uri) throws IOException, JSONException {
        if(uri == null) throw new IOException("Source URI is null");
        StringBuilder sb = new StringBuilder();
        try(InputStream is = context.getContentResolver().openInputStream(uri);
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))){
            String line;
            while((line = br.readLine()) != null){
                sb.append(line).append('\n');
            }
        }
        return new JSONObject(sb.toString());
    }

    /**
     * Applies preferences from JSON. Existing prefs overwritten.
     *
     * Supports two import formats:
     * 1. NEW typed format:  {"key": {"t": "long", "v": 5000}}
     *    Produced by buildBackupJson(). Preserves exact SharedPreferences types
     *    (Long vs Integer vs Float) so getLong() never throws ClassCastException.
     *
     * 2. LEGACY flat format: {"key": 5000}
     *    Old export format. For legacy numbers, ALL are stored as Long because
     *    JSON parsers lose type info (org.json represents small numbers as Integer).
     *    This may affect getInt() callers, but it's safer than crashing getLong().
     */
    public static void applyFromJson(Context context, JSONObject root) throws JSONException {
        if(root == null) throw new JSONException("Root JSON null");
        SharedPreferences sp = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();

        // Remove keys that exist in current prefs but not in the import
        // (safer than editor.clear() which would wipe keys the import doesn't know about)
        Map<String, ?> existing = sp.getAll();
        for(String existingKey : existing.keySet()) {
            if(!root.has(existingKey) && !isTransientKey(existingKey)) {
                editor.remove(existingKey);
            }
        }

        java.util.Iterator<String> it = root.keys();
        while(it.hasNext()){
            String key = it.next();
            if(isTransientKey(key)) { continue; }
            Object v = root.get(key);

            // --- NEW typed format: {"t": "type", "v": value} ---
            if(v instanceof JSONObject) {
                JSONObject entry = (JSONObject) v;
                String type = entry.optString("t", "");
                switch(type) {
                    case "bool":
                        editor.putBoolean(key, entry.getBoolean("v"));
                        break;
                    case "int":
                        editor.putInt(key, entry.getInt("v"));
                        break;
                    case "long":
                        editor.putLong(key, entry.getLong("v"));
                        break;
                    case "float":
                        editor.putFloat(key, (float) entry.getDouble("v"));
                        break;
                    case "string":
                        editor.putString(key, entry.getString("v"));
                        break;
                    case "string_set": {
                        JSONArray arr = entry.getJSONArray("v");
                        java.util.HashSet<String> set = new java.util.HashSet<>();
                        for(int i = 0; i < arr.length(); i++) {
                            set.add(arr.optString(i));
                        }
                        editor.putStringSet(key, set);
                        break;
                    }
                    default:
                        FLog.w(TAG, "Unsupported typed import type '" + type + "' for key: " + key);
                }
                continue;
            }

            // --- LEGACY flat format (backward compat) ---
            if(v instanceof Boolean){
                editor.putBoolean(key, (Boolean)v);
            } else if(v instanceof Integer || v instanceof Long){
                // JSON numbers lose their original type. To avoid the
                // ClassCastException bug (legacy file stores Long, reader calls
                // getInt), coerce to the type this app currently uses for the
                // key — that's the ground truth of what readers expect.
                // Unknown keys default to Long (safe for getLong readers;
                // read-time tolerance in SharedPreferencesManager covers the
                // rest).
                Object current = sp.getAll().get(key);
                long num = (v instanceof Long) ? (Long)v : ((Integer)v).longValue();
                if(current instanceof Integer){
                    editor.putInt(key, (int) num);
                } else if(current instanceof Float){
                    editor.putFloat(key, (float) num);
                } else {
                    editor.putLong(key, num);
                }
            } else if(v instanceof Double){
                Object current = sp.getAll().get(key);
                if(current instanceof Float){
                    editor.putFloat(key, ((Double)v).floatValue());
                } else if(current instanceof Integer){
                    editor.putInt(key, ((Double)v).intValue());
                } else if(current instanceof Long){
                    editor.putLong(key, ((Double)v).longValue());
                } else {
                    editor.putString(key, String.valueOf(v));
                }
            } else if(v instanceof String){
                editor.putString(key, (String)v);
            } else if(v instanceof JSONArray){
                JSONArray arr = (JSONArray) v;
                java.util.HashSet<String> set = new java.util.HashSet<>();
                for(int i=0;i<arr.length();i++){
                    set.add(arr.optString(i));
                }
                editor.putStringSet(key, set);
            } else {
                FLog.w(TAG, "Unsupported import type for key: " + key);
            }
        }
        editor.apply();
    }

    /**
     * Clears all preferences (factory defaults will apply next access).
     */
    public static void resetAll(Context context){
        SharedPreferences sp = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit().clear().apply();
    }

    /**
     * Generates a suggested filename for export.
     */
    public static String buildSuggestedFileName(){
        return "fadcam_prefs_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".json";
    }

    // ── Structured preview + validation ──────────────────────────────

    /** Status of a single preference entry in an import file. */
    public enum EntryStatus {
        OK,          // will import cleanly with the correct type
        WARN,        // importable, but the stored type may differ from what readers expect
        ERROR        // malformed / will likely crash a getter or is unreadable
    }

    /** A single preference entry from an import file, rendered by the preview UI. */
    public static final class PreviewEntry {
        public final String key;
        public final String type;      // human-readable type name ("Int", "Long", ...)
        public final String value;     // display string
        public final EntryStatus status;
        public final String message;   // human explanation when not OK

        PreviewEntry(String key, String type, String value, EntryStatus status, String message) {
            this.key = key; this.type = type; this.value = value;
            this.status = status; this.message = message;
        }
    }

    /**
     * Parses an import file into structured, validated preview entries.
     * Never throws on malformed content — malformed values are reported as
     * ERROR entries so the UI can show the problem instead of crashing.
     *
     * Uses the app's current prefs as the ground-truth type map: a legacy
     * number whose key currently holds an Int will be repaired to Int on
     * import, so it is OK. Unknown keys are flagged WARN (they default to
     * Long, which may mismatch a getInt reader).
     */
    @NonNull
    public static java.util.List<PreviewEntry> buildPreviewEntries(Context context, JSONObject root) {
        java.util.Map<String, ?> current = null;
        if (context != null) {
            try {
                current = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).getAll();
            } catch (Exception ignored) {}
        }
        return buildPreviewEntries(root, current);
    }

    @NonNull
    public static java.util.List<PreviewEntry> buildPreviewEntries(JSONObject root) {
        return buildPreviewEntries(root, null);
    }

    @NonNull
    private static java.util.List<PreviewEntry> buildPreviewEntries(JSONObject root,
                                                                     java.util.Map<String, ?> currentPrefs) {
        java.util.List<PreviewEntry> out = new java.util.ArrayList<>();
        if (root == null) return out;
        java.util.Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (isTransientKey(key)) continue;
            Object v;
            try { v = root.get(key); } catch (JSONException e) { continue; }

            // Typed format: {"t": "long", "v": 5000}
            if (v instanceof JSONObject) {
                JSONObject entry = (JSONObject) v;
                String t = entry.optString("t", "");
                if (t.isEmpty()) {
                    out.add(new PreviewEntry(key, "Object", entry.toString(), EntryStatus.ERROR,
                            "Entry is an object without a type tag (\"t\")."));
                    continue;
                }
                Object val;
                try { val = entry.get("v"); }
                catch (JSONException e) {
                    out.add(new PreviewEntry(key, typeNameForTag(t), "—", EntryStatus.ERROR,
                            "Missing value field (\"v\") for type " + t + "."));
                    continue;
                }
                out.add(buildTypedEntry(key, t, val));
                continue;
            }

            // Legacy flat format.
            out.add(buildLegacyEntry(key, v, currentPrefs));
        }
        return out;
    }

    private static PreviewEntry buildTypedEntry(String key, String t, Object val) {
        switch (t) {
            case "bool":
                if (val instanceof Boolean) return ok(key, "Boolean", String.valueOf(val));
                return err(key, "Boolean", String.valueOf(val), "Expected boolean, got " + typeName(val) + ".");
            case "int":
                if (val instanceof Number) return ok(key, "Int", String.valueOf(val));
                return err(key, "Int", String.valueOf(val), "Expected number, got " + typeName(val) + ".");
            case "long":
                if (val instanceof Number) return ok(key, "Long", String.valueOf(val));
                return err(key, "Long", String.valueOf(val), "Expected number, got " + typeName(val) + ".");
            case "float":
                if (val instanceof Number) return ok(key, "Float", String.valueOf(val));
                return err(key, "Float", String.valueOf(val), "Expected number, got " + typeName(val) + ".");
            case "string":
                if (val instanceof String) return ok(key, "String", (String) val);
                // JSON non-string values are accepted by org.json as getString? No —
                // applyFromJson uses entry.getString("v") which coerces numbers.
                return warn(key, "String", String.valueOf(val),
                        "Value is " + typeName(val) + ", will be stored as its string form.");
            case "string_set": {
                if (val instanceof JSONArray) return ok(key, "Set<String>", ((JSONArray) val).length() + " items");
                return err(key, "Set<String>", String.valueOf(val), "Expected array, got " + typeName(val) + ".");
            }
            default:
                return err(key, t, String.valueOf(val), "Unknown type tag \"" + t + "\".");
        }
    }

    private static PreviewEntry buildLegacyEntry(String key, Object v, java.util.Map<String, ?> currentPrefs) {
        if (v instanceof Boolean) return ok(key, "Boolean", String.valueOf(v));
        if (v instanceof Integer || v instanceof Long) {
            // The app's current type for this key is the ground truth of what
            // readers expect. If the key is known, the import coerces to that
            // type and repairs cleanly — OK. Unknown keys default to Long,
            // which can mismatch a getInt reader — WARN.
            Object current = currentPrefs != null ? currentPrefs.get(key) : null;
            String type = current instanceof Integer ? "Int"
                    : current instanceof Float ? "Float"
                    : current instanceof Long ? "Long" : "Long";
            if (current instanceof Integer || current instanceof Float || current instanceof Long) {
                return ok(key, type, String.valueOf(v));
            }
            return warn(key, "Long", String.valueOf(v),
                    "Unknown key — will be stored as Long. If any setting reads it with getInt(), it may crash. Consider re-exporting from a newer version.");
        }
        if (v instanceof Double || v instanceof Float) {
            Object current = currentPrefs != null ? currentPrefs.get(key) : null;
            if (current instanceof Float || current instanceof Integer || current instanceof Long) {
                return ok(key, "Number", String.valueOf(v));
            }
            return warn(key, "Number", String.valueOf(v),
                    "Legacy number stored as string by applyFromJson; readers expecting a number may misbehave.");
        }
        if (v instanceof String) return ok(key, "String", (String) v);
        if (v instanceof JSONArray) {
            return warn(key, "Set<String>", ((JSONArray) v).length() + " items",
                    "Legacy array imported as string set; ordering not preserved.");
        }
        return err(key, "?", String.valueOf(v), "Unsupported value type " + typeName(v) + ".");
    }

    private static PreviewEntry ok(String key, String type, String value) {
        return new PreviewEntry(key, type, value, EntryStatus.OK, "");
    }
    private static PreviewEntry warn(String key, String type, String value, String msg) {
        return new PreviewEntry(key, type, value, EntryStatus.WARN, msg);
    }
    private static PreviewEntry err(String key, String type, String value, String msg) {
        return new PreviewEntry(key, type, value, EntryStatus.ERROR, msg);
    }

    /** Summary counts for the preview banner. */
    public static final class PreviewSummary {
        public final int total, ok, warnings, errors;
        PreviewSummary(int total, int ok, int warnings, int errors) {
            this.total = total; this.ok = ok; this.warnings = warnings; this.errors = errors;
        }
        public boolean hasErrors() { return errors > 0; }
    }

    public static PreviewSummary summarize(java.util.List<PreviewEntry> entries) {
        int ok = 0, warn = 0, err = 0;
        for (PreviewEntry e : entries) {
            switch (e.status) {
                case OK: ok++; break;
                case WARN: warn++; break;
                case ERROR: err++; break;
            }
        }
        return new PreviewSummary(entries.size(), ok, warn, err);
    }

    private static String typeName(Object v){
        if(v==null) return "null";
        if(v instanceof Boolean) return "Boolean";
        if(v instanceof Integer) return "Int";
        if(v instanceof Long) return "Long";
        if(v instanceof Double || v instanceof Float) return "Number";
        if(v instanceof JSONArray) return "Array";
        return "String";
    }

    private static String typeNameForTag(String t){
        switch(t) {
            case "bool": return "Boolean";
            case "int": return "Int";
            case "long": return "Long";
            case "float": return "Float";
            case "string": return "String";
            case "string_set": return "Set<String>";
            default: return t;
        }
    }
}
