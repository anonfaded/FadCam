package com.fadcam.ui.faditor.models;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

/**
 * Complete editor state for auto-save and restoration
 */
public class EditorState {
    private String selectedTool; // Current tool selection
    private TimelineState timelineState;
    private boolean isPlaying;
    private long lastPlayPosition;
    private Map<String, Object> toolSettings; // Tool-specific settings
    private long lastModified;
    private boolean hasUnsavedChanges;

    public EditorState() {
        this.toolSettings = new HashMap<>();
        this.timelineState = new TimelineState();
        this.lastModified = System.currentTimeMillis();
        this.hasUnsavedChanges = false;
    }

    // Auto-save integration
    public void markModified() {
        this.lastModified = System.currentTimeMillis();
        this.hasUnsavedChanges = true;
    }

    public boolean needsSaving() {
        return hasUnsavedChanges;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("selectedTool", selectedTool);
        json.put("timelineState", timelineState != null ? timelineState.toJson() : null);
        json.put("isPlaying", isPlaying);
        json.put("lastPlayPosition", lastPlayPosition);
        json.put("lastModified", lastModified);
        json.put("hasUnsavedChanges", hasUnsavedChanges);
        
        // Convert tool settings to JSON
        JSONObject toolSettingsJson = new JSONObject();
        for (Map.Entry<String, Object> entry : toolSettings.entrySet()) {
            toolSettingsJson.put(entry.getKey(), entry.getValue());
        }
        json.put("toolSettings", toolSettingsJson);
        
        return json;
    }

    public static EditorState fromJson(JSONObject json) throws JSONException {
        EditorState state = new EditorState();
        
        if (json.has("selectedTool")) {
            state.selectedTool = json.getString("selectedTool");
        }
        
        if (json.has("timelineState") && !json.isNull("timelineState")) {
            state.timelineState = TimelineState.fromJson(json.getJSONObject("timelineState"));
        }
        
        state.isPlaying = json.optBoolean("isPlaying", false);
        state.lastPlayPosition = json.optLong("lastPlayPosition", 0);
        state.lastModified = json.optLong("lastModified", System.currentTimeMillis());
        state.hasUnsavedChanges = json.optBoolean("hasUnsavedChanges", false);
        
        // Parse tool settings
        if (json.has("toolSettings")) {
            JSONObject toolSettingsJson = json.getJSONObject("toolSettings");
            state.toolSettings = new HashMap<>();
            for (java.util.Iterator<String> keys = toolSettingsJson.keys(); keys.hasNext();) {
                String key = keys.next();
                state.toolSettings.put(key, toolSettingsJson.get(key));
            }
        }
        
        return state;
    }

    // Getters and setters
    public String getSelectedTool() {
        return selectedTool;
    }

    public void setSelectedTool(String selectedTool) {
        this.selectedTool = selectedTool;
        markModified();
    }

    public TimelineState getTimelineState() {
        return timelineState;
    }

    public void setTimelineState(TimelineState timelineState) {
        this.timelineState = timelineState;
        markModified();
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public void setPlaying(boolean playing) {
        isPlaying = playing;
        markModified();
    }

    public long getLastPlayPosition() {
        return lastPlayPosition;
    }

    public void setLastPlayPosition(long lastPlayPosition) {
        this.lastPlayPosition = lastPlayPosition;
        markModified();
    }

    public Map<String, Object> getToolSettings() {
        return toolSettings;
    }

    public void setToolSettings(Map<String, Object> toolSettings) {
        this.toolSettings = toolSettings;
        markModified();
    }

    public void setToolSetting(String key, Object value) {
        this.toolSettings.put(key, value);
        markModified();
    }

    public Object getToolSetting(String key) {
        return toolSettings.get(key);
    }

    public long getLastModified() {
        return lastModified;
    }

    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }

    public boolean isHasUnsavedChanges() {
        return hasUnsavedChanges;
    }

    public void setHasUnsavedChanges(boolean hasUnsavedChanges) {
        this.hasUnsavedChanges = hasUnsavedChanges;
    }

    public void clearUnsavedChanges() {
        this.hasUnsavedChanges = false;
    }
}