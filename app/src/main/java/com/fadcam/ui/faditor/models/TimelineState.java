package com.fadcam.ui.faditor.models;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Preserves timeline view and interaction state
 */
public class TimelineState {
    public enum ViewMode {
        OVERVIEW, DETAILED, FRAME_ACCURATE
    }

    private float zoomLevel;
    private long viewportStart;
    private long viewportEnd;
    private long playheadPosition;
    private boolean frameSnappingEnabled;
    private ViewMode viewMode;

    public TimelineState() {
        this.zoomLevel = 1.0f;
        this.viewportStart = 0;
        this.viewportEnd = 0;
        this.playheadPosition = 0;
        this.frameSnappingEnabled = true;
        this.viewMode = ViewMode.OVERVIEW;
    }

    public TimelineState(float zoomLevel, long viewportStart, long viewportEnd, 
                        long playheadPosition, boolean frameSnappingEnabled, ViewMode viewMode) {
        this.zoomLevel = zoomLevel;
        this.viewportStart = viewportStart;
        this.viewportEnd = viewportEnd;
        this.playheadPosition = playheadPosition;
        this.frameSnappingEnabled = frameSnappingEnabled;
        this.viewMode = viewMode;
    }

    // State serialization for project persistence
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("zoomLevel", zoomLevel);
        json.put("viewportStart", viewportStart);
        json.put("viewportEnd", viewportEnd);
        json.put("playheadPosition", playheadPosition);
        json.put("frameSnappingEnabled", frameSnappingEnabled);
        json.put("viewMode", viewMode.name());
        return json;
    }

    public static TimelineState fromJson(JSONObject json) throws JSONException {
        TimelineState state = new TimelineState();
        
        state.zoomLevel = (float) json.optDouble("zoomLevel", 1.0);
        state.viewportStart = json.optLong("viewportStart", 0);
        state.viewportEnd = json.optLong("viewportEnd", 0);
        state.playheadPosition = json.optLong("playheadPosition", 0);
        state.frameSnappingEnabled = json.optBoolean("frameSnappingEnabled", true);
        
        String viewModeStr = json.optString("viewMode", ViewMode.OVERVIEW.name());
        try {
            state.viewMode = ViewMode.valueOf(viewModeStr);
        } catch (IllegalArgumentException e) {
            state.viewMode = ViewMode.OVERVIEW;
        }
        
        return state;
    }

    // Getters and setters
    public float getZoomLevel() {
        return zoomLevel;
    }

    public void setZoomLevel(float zoomLevel) {
        this.zoomLevel = zoomLevel;
    }

    public long getViewportStart() {
        return viewportStart;
    }

    public void setViewportStart(long viewportStart) {
        this.viewportStart = viewportStart;
    }

    public long getViewportEnd() {
        return viewportEnd;
    }

    public void setViewportEnd(long viewportEnd) {
        this.viewportEnd = viewportEnd;
    }

    public long getPlayheadPosition() {
        return playheadPosition;
    }

    public void setPlayheadPosition(long playheadPosition) {
        this.playheadPosition = playheadPosition;
    }

    public boolean isFrameSnappingEnabled() {
        return frameSnappingEnabled;
    }

    public void setFrameSnappingEnabled(boolean frameSnappingEnabled) {
        this.frameSnappingEnabled = frameSnappingEnabled;
    }

    public ViewMode getViewMode() {
        return viewMode;
    }

    public void setViewMode(ViewMode viewMode) {
        this.viewMode = viewMode;
    }
}