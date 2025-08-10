package com.fadcam.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

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
        // Add more runtime/transient keys here if discovered later
        EXCLUDED_KEYS = java.util.Collections.unmodifiableSet(set);
    }

    private static boolean isTransientKey(String key){
        return EXCLUDED_KEYS.contains(key);
    }

    /**
     * Builds a JSONObject representing all app SharedPreferences values.
     */
    @NonNull
    public static JSONObject buildBackupJson(Context context) throws JSONException {
        SharedPreferences sp = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        Map<String, ?> all = sp.getAll();
        JSONObject root = new JSONObject();
        for(Map.Entry<String, ?> e : all.entrySet()){
            if(isTransientKey(e.getKey())){ continue; }
            Object v = e.getValue();
            if(v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Double || v instanceof String){
                root.put(e.getKey(), v);
            } else if(v instanceof Set){
                // Assume set of strings
                JSONArray arr = new JSONArray();
                for(Object o : (Set<?>) v){
                    arr.put(String.valueOf(o));
                }
                root.put(e.getKey(), arr);
            } else {
                Log.w(TAG, "Skipping unsupported pref type for key: " + e.getKey());
            }
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
     */
    public static void applyFromJson(Context context, JSONObject root) throws JSONException {
        if(root == null) throw new JSONException("Root JSON null");
        SharedPreferences sp = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.clear(); // wipe first so removed keys disappear
        java.util.Iterator<String> it = root.keys();
        while(it.hasNext()){
            String key = it.next();
            if(isTransientKey(key)) { continue; }
            Object v = root.get(key);
            if(v instanceof Boolean){
                editor.putBoolean(key, (Boolean)v);
            } else if(v instanceof Integer){
                editor.putInt(key, (Integer)v);
            } else if(v instanceof Long){
                editor.putLong(key, (Long)v);
            } else if(v instanceof Double){
                // SharedPreferences has no double - store as String to not lose precision
                editor.putString(key, String.valueOf(v));
            } else if(v instanceof String){
                editor.putString(key, (String)v);
            } else if(v instanceof JSONArray){
                // Convert to Set<String>
                JSONArray arr = (JSONArray) v;
                java.util.HashSet<String> set = new java.util.HashSet<>();
                for(int i=0;i<arr.length();i++){
                    set.add(arr.optString(i));
                }
                editor.putStringSet(key, set);
            } else {
                Log.w(TAG, "Unsupported import type for key: " + key);
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
            if(v instanceof JSONArray){
                sb.append("<font color='#c792ea'>[");
                JSONArray arr = (JSONArray)v;
                for(int i=0;i<arr.length();i++){
                    if(i>0) sb.append(", ");
                    sb.append(colorizeValue(arr.opt(i)));
                }
                sb.append("]</font>");
                sb.append(typeSuffix("Set<String>"));
            } else {
                sb.append(colorizeValue(v));
                sb.append(typeSuffix(typeName(v)));
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

    private static String typeSuffix(String type){
        return " <font color='#555555'>/* "+type+" */</font>";
    }
}
