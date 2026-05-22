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
        set.add("applock_session_unlocked"); // session cache
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
            } else if(v instanceof Integer){
                // JSON numbers in int range are parsed as Integer.
                // We can't know if this was originally Long, so store as Long
                // to prevent ClassCastException on getLong() callers.
                editor.putLong(key, ((Integer)v).longValue());
            } else if(v instanceof Long){
                editor.putLong(key, (Long)v);
            } else if(v instanceof Double){
                editor.putString(key, String.valueOf(v));
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

    /**
     * Returns a colorized (rudimentary) HTML representation of the JSON for preview in a TextView supporting fromHtml.
     */
    public static String buildPreviewHtml(JSONObject json){
        if(json==null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("<pre style='white-space:nowrap;'>");
        java.util.Iterator<String> it = json.keys();
        while(it.hasNext()){
            String key = it.next();
            Object v = json.opt(key);
            sb.append("<font color='#8ab4f8'>\"").append(escape(key)).append("\"</font><font color='#ffffff'>: </font>");

            // Extract type and display value from either typed or legacy format
            Object displayValue = v;
            String displayType = typeName(v);
            if(v instanceof JSONObject) {
                String t = ((JSONObject)v).optString("t", "");
                if(!t.isEmpty()) {
                    displayValue = ((JSONObject)v).opt("v");
                    displayType = typeNameForTag(t);
                }
            }

            if(displayValue instanceof JSONArray){
                sb.append("<font color='#c792ea'>[");
                JSONArray arr = (JSONArray)displayValue;
                for(int i=0;i<arr.length();i++){
                    if(i>0) sb.append(", ");
                    sb.append(colorizeValue(arr.opt(i)));
                }
                sb.append("]</font>");
                sb.append(typeSuffix("Set<String>"));
            } else {
                sb.append(colorizeValue(displayValue));
                sb.append(typeSuffix(displayType));
            }
            if(it.hasNext()) sb.append(",");
            sb.append("\n");
        }
        sb.append("</pre>");
        return sb.toString();
    }

    private static String colorizeValue(Object v){
        if(v==null) return wrap("null", "#808080");
        if(v instanceof Boolean) return wrap(String.valueOf(v), "#f28b82");
        if(v instanceof Number) return wrap(String.valueOf(v), "#fbbc04");
        return "<font color='#a5d6ff'>\""+escape(String.valueOf(v))+"\"</font>"; // string
    }

    private static String escape(String in){
        return in.replace("<","&lt;").replace(">","&gt;");
    }

    private static String wrap(String txt, String color){
        return "<font color='"+color+"'>"+txt+"</font>";
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

    private static String typeSuffix(String type){
        return " <font color='#555555'>/* "+type+" */</font>";
    }
}
