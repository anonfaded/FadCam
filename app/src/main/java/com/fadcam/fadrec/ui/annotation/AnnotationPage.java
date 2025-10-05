package com.fadcam.fadrec.ui.annotation;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.UUID;

/**
 * Represents a single annotation page with multiple layers and version control.
 * Each page has independent undo/redo history.
 */
public class AnnotationPage {
    
    private String id;
    private String name;
    private List<AnnotationLayer> layers;
    private int activeLayerIndex;
    private boolean blackboardMode;
    private long createdAt;
    private long modifiedAt;
    
    // Version control (not serialized - rebuilt on load)
    private transient Stack<DrawingCommand> undoStack;
    private transient Stack<DrawingCommand> redoStack;
    private static final int MAX_HISTORY_SIZE = 50;
    
    public AnnotationPage(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.layers = new ArrayList<>();
        this.activeLayerIndex = 0;
        this.blackboardMode = false;
        this.createdAt = System.currentTimeMillis();
        this.modifiedAt = createdAt;
        
        // Create default layer
        layers.add(new AnnotationLayer("Layer 1"));
        
        // Initialize undo/redo stacks
        initializeStacks();
    }
    
    private void initializeStacks() {
        this.undoStack = new Stack<>();
        this.redoStack = new Stack<>();
    }
    
    // Getters and setters
    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { 
        this.name = name;
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public List<AnnotationLayer> getLayers() { return layers; }
    public AnnotationLayer getActiveLayer() {
        if (activeLayerIndex >= 0 && activeLayerIndex < layers.size()) {
            return layers.get(activeLayerIndex);
        }
        return null;
    }
    
    public int getActiveLayerIndex() { return activeLayerIndex; }
    public void setActiveLayerIndex(int index) {
        if (index >= 0 && index < layers.size()) {
            this.activeLayerIndex = index;
        }
    }
    
    public boolean isBlackboardMode() { return blackboardMode; }
    public void setBlackboardMode(boolean enabled) { 
        this.blackboardMode = enabled;
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public long getCreatedAt() { return createdAt; }
    public long getModifiedAt() { return modifiedAt; }
    
    // Layer management
    public void addLayer(String name) {
        layers.add(new AnnotationLayer(name));
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public void removeLayer(int index) {
        if (layers.size() > 1 && index >= 0 && index < layers.size()) {
            layers.remove(index);
            if (activeLayerIndex >= layers.size()) {
                activeLayerIndex = layers.size() - 1;
            }
            this.modifiedAt = System.currentTimeMillis();
        }
    }
    
    public void moveLayer(int fromIndex, int toIndex) {
        if (fromIndex >= 0 && fromIndex < layers.size() && 
            toIndex >= 0 && toIndex < layers.size()) {
            AnnotationLayer layer = layers.remove(fromIndex);
            layers.add(toIndex, layer);
            this.modifiedAt = System.currentTimeMillis();
        }
    }
    
    // Version control
    public void executeCommand(DrawingCommand command) {
        command.execute();
        undoStack.push(command);
        redoStack.clear(); // Clear redo stack when new action is performed
        
        // Limit history size
        if (undoStack.size() > MAX_HISTORY_SIZE) {
            undoStack.remove(0);
        }
        
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public boolean canUndo() {
        return !undoStack.isEmpty();
    }
    
    public boolean canRedo() {
        return !redoStack.isEmpty();
    }
    
    public int getUndoStackSize() {
        return undoStack.size();
    }
    
    public int getRedoStackSize() {
        return redoStack.size();
    }
    
    public void undo() {
        if (canUndo()) {
            DrawingCommand command = undoStack.pop();
            command.undo();
            redoStack.push(command);
            this.modifiedAt = System.currentTimeMillis();
        }
    }
    
    public void redo() {
        if (canRedo()) {
            DrawingCommand command = redoStack.pop();
            command.execute();
            undoStack.push(command);
            this.modifiedAt = System.currentTimeMillis();
        }
    }
    
    public void clearHistory() {
        undoStack.clear();
        redoStack.clear();
    }
    
    // JSON serialization
    public JSONObject toJSON() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("activeLayerIndex", activeLayerIndex);
        json.put("blackboardMode", blackboardMode);
        json.put("createdAt", createdAt);
        json.put("modifiedAt", modifiedAt);
        
        JSONArray layersArray = new JSONArray();
        for (AnnotationLayer layer : layers) {
            layersArray.put(layer.toJSON());
        }
        json.put("layers", layersArray);
        
        return json;
    }
    
    public static AnnotationPage fromJSON(JSONObject json) throws JSONException {
        AnnotationPage page = new AnnotationPage(json.getString("name"));
        page.id = json.getString("id");
        page.activeLayerIndex = json.getInt("activeLayerIndex");
        page.blackboardMode = json.getBoolean("blackboardMode");
        page.createdAt = json.getLong("createdAt");
        page.modifiedAt = json.getLong("modifiedAt");
        
        // Clear default layer and load from JSON
        page.layers.clear();
        JSONArray layersArray = json.getJSONArray("layers");
        for (int i = 0; i < layersArray.length(); i++) {
            JSONObject layerJson = layersArray.getJSONObject(i);
            page.layers.add(AnnotationLayer.fromJSON(layerJson));
        }
        
        return page;
    }
    
    /**
     * Reconstruct transient fields after deserialization
     */
    public void reconstruct() {
        initializeStacks();
    }
}
