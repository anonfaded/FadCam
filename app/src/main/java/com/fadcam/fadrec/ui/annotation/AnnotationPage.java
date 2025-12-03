package com.fadcam.fadrec.ui.annotation;

import com.fadcam.fadrec.ui.annotation.objects.AnnotationObject;

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
 * Uses soft-delete system for complete version control - deleted pages preserved forever.
 */
public class AnnotationPage {
    
    private String id;
    private String name;
    private List<AnnotationLayer> layers;
    private int activeLayerIndex;
    private boolean blackboardMode;
    private boolean whiteboardMode;
    private boolean deleted; // NEW: Soft-delete flag for version control
    private long createdAt;
    private long modifiedAt;
    
    // Version control (undo/redo counts are saved, but not full command objects)
    private transient Stack<DrawingCommand> undoStack;
    private transient Stack<DrawingCommand> redoStack;
    private static final int MAX_HISTORY_SIZE = 1000; // Increased for longer recording sessions
    
    // Save undo/redo counts for UI display after reload
    private int savedUndoCount = 0;
    private int savedRedoCount = 0;
    
    public AnnotationPage(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.layers = new ArrayList<>();
        this.activeLayerIndex = 0;
        this.blackboardMode = false;
        this.whiteboardMode = false;
        this.deleted = false; // Default: not deleted
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
    
    /**
     * Find a layer by its unique ID.
     * Returns null if not found.
     * Used for command deserialization to resolve layer references.
     */
    public AnnotationLayer getLayerById(String layerId) {
        for (AnnotationLayer layer : layers) {
            if (layer.getId().equals(layerId)) {
                return layer;
            }
        }
        return null;
    }
    
    /**
     * Get only non-deleted (visible) layers for UI display.
     * Deleted layers are kept in memory for version control but hidden from view.
     */
    public List<AnnotationLayer> getVisibleLayers() {
        List<AnnotationLayer> visibleLayers = new ArrayList<>();
        for (AnnotationLayer layer : layers) {
            if (!layer.isDeleted()) {
                visibleLayers.add(layer);
            }
        }
        return visibleLayers;
    }
    
    public AnnotationLayer getActiveLayer() {
        if (activeLayerIndex >= 0 && activeLayerIndex < layers.size()) {
            AnnotationLayer layer = layers.get(activeLayerIndex);
            // Skip deleted layers
            if (!layer.isDeleted()) {
                return layer;
            }
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
        if (enabled) this.whiteboardMode = false; // Mutually exclusive
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public boolean isWhiteboardMode() { return whiteboardMode; }
    public void setWhiteboardMode(boolean enabled) { 
        this.whiteboardMode = enabled;
        if (enabled) this.blackboardMode = false; // Mutually exclusive
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
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
        if (layers.isEmpty()) {
            return;
        }

        if (fromIndex < 0 || fromIndex >= layers.size()) {
            return;
        }

        if (toIndex < 0) {
            toIndex = 0;
        } else if (toIndex > layers.size() - 1) {
            toIndex = layers.size() - 1;
        }

        if (fromIndex == toIndex) {
            return;
        }

        AnnotationLayer layer = layers.remove(fromIndex);
        layers.add(toIndex, layer);

        if (activeLayerIndex == fromIndex) {
            activeLayerIndex = toIndex;
        } else if (fromIndex < activeLayerIndex && toIndex >= activeLayerIndex) {
            activeLayerIndex -= 1;
        } else if (fromIndex > activeLayerIndex && toIndex <= activeLayerIndex) {
            activeLayerIndex += 1;
        }

        if (activeLayerIndex < 0) {
            activeLayerIndex = 0;
        } else if (activeLayerIndex >= layers.size()) {
            activeLayerIndex = layers.size() - 1;
        }

        this.modifiedAt = System.currentTimeMillis();
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
        
        // Save counts for persistence
        savedUndoCount = undoStack.size();
        savedRedoCount = redoStack.size();
        
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
            
            // Save counts for persistence
            savedUndoCount = undoStack.size();
            savedRedoCount = redoStack.size();
            
            this.modifiedAt = System.currentTimeMillis();
        }
    }
    
    public void redo() {
        if (canRedo()) {
            DrawingCommand command = redoStack.pop();
            command.execute();
            undoStack.push(command);
            
            // Save counts for persistence
            savedUndoCount = undoStack.size();
            savedRedoCount = redoStack.size();
            
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
        json.put("whiteboardMode", whiteboardMode);
        json.put("deleted", deleted); // Save deleted state for version control
        json.put("createdAt", createdAt);
        json.put("modifiedAt", modifiedAt);
        
        // CRITICAL: Serialize undo/redo command history for complete version control
        JSONArray undoArray = new JSONArray();
        if (undoStack != null) {
            for (DrawingCommand cmd : undoStack) {
                try {
                    undoArray.put(cmd.toJSON());
                } catch (Exception e) {
                    android.util.Log.w("AnnotationPage", "Failed to serialize command: " + cmd.getDescription(), e);
                }
            }
        }
        json.put("undoHistory", undoArray);
        
        JSONArray redoArray = new JSONArray();
        if (redoStack != null) {
            for (DrawingCommand cmd : redoStack) {
                try {
                    redoArray.put(cmd.toJSON());
                } catch (Exception e) {
                    android.util.Log.w("AnnotationPage", "Failed to serialize command: " + cmd.getDescription(), e);
                }
            }
        }
        json.put("redoHistory", redoArray);
        
        // Save counts for UI
        json.put("undoCount", undoStack != null ? undoStack.size() : savedUndoCount);
        json.put("redoCount", redoStack != null ? redoStack.size() : savedRedoCount);
        
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
        page.whiteboardMode = json.getBoolean("whiteboardMode");
        page.deleted = json.getBoolean("deleted"); // NO backward compatibility
        page.createdAt = json.getLong("createdAt");
        page.modifiedAt = json.getLong("modifiedAt");
        
        // Load saved undo/redo counts for fallback
        page.savedUndoCount = json.optInt("undoCount", 0);
        page.savedRedoCount = json.optInt("redoCount", 0);
        
        // Clear default layer and load from JSON
        page.layers.clear();
        JSONArray layersArray = json.getJSONArray("layers");
        for (int i = 0; i < layersArray.length(); i++) {
            JSONObject layerJson = layersArray.getJSONObject(i);
            page.layers.add(AnnotationLayer.fromJSON(layerJson));
        }
        
        // CRITICAL: Restore command history for complete version control
        page.initializeStacks();
        
        // Deserialize undo history
        if (json.has("undoHistory")) {
            JSONArray undoArray = json.getJSONArray("undoHistory");
            android.util.Log.d("AnnotationPage", "Restoring " + undoArray.length() + " undo commands");
            for (int i = 0; i < undoArray.length(); i++) {
                try {
                    JSONObject cmdJson = undoArray.getJSONObject(i);
                    DrawingCommand cmd = deserializeCommand(cmdJson, page);
                    if (cmd != null) {
                        page.undoStack.add(cmd);
                    }
                } catch (Exception e) {
                    android.util.Log.e("AnnotationPage", "Failed to deserialize undo command " + i, e);
                }
            }
        }
        
        // Deserialize redo history
        if (json.has("redoHistory")) {
            JSONArray redoArray = json.getJSONArray("redoHistory");
            android.util.Log.d("AnnotationPage", "Restoring " + redoArray.length() + " redo commands");
            for (int i = 0; i < redoArray.length(); i++) {
                try {
                    JSONObject cmdJson = redoArray.getJSONObject(i);
                    DrawingCommand cmd = deserializeCommand(cmdJson, page);
                    if (cmd != null) {
                        page.redoStack.add(cmd);
                    }
                } catch (Exception e) {
                    android.util.Log.e("AnnotationPage", "Failed to deserialize redo command " + i, e);
                }
            }
        }
        
        android.util.Log.d("AnnotationPage", "Loaded page with " + page.undoStack.size() + " undo, " + 
                          page.redoStack.size() + " redo commands");
        
        return page;
    }
    
    /**
     * Factory method to deserialize a command from JSON.
     * Routes to appropriate command type based on "type" field.
     */
    private static DrawingCommand deserializeCommand(JSONObject json, AnnotationPage page) throws JSONException {
        String type = json.getString("type");
        
        switch (type) {
            case "DELETE_LAYER":
                return DeleteLayerCommand.fromJSON(page, json);
                
            case "ADD_LAYER":
                return AddLayerCommand.fromJSON(page, json);
                
            case "ADD_PATH":
                // AddPathCommand needs the layer, not the page
                // Resolve layer from layerId stored in JSON
                String layerId = json.getString("layerId");
                AnnotationLayer layer = page.getLayerById(layerId);
                if (layer == null) {
                    android.util.Log.e("AnnotationPage", "Cannot deserialize ADD_PATH: layer " + layerId + " not found");
                    return null;
                }
                return AddPathCommand.fromJSON(layer, json);
                
            case "ADD_OBJECT":
                // AddObjectCommand needs the layer
                String objLayerId = json.getString("layerId");
                AnnotationLayer objLayer = page.getLayerById(objLayerId);
                if (objLayer == null) {
                    android.util.Log.e("AnnotationPage", "Cannot deserialize ADD_OBJECT: layer " + objLayerId + " not found");
                    return null;
                }
                return AddObjectCommand.fromJSON(objLayer, json);
                
            case "MODIFY_TEXT_OBJECT":
                // ModifyTextObjectCommand needs the layer
                String textLayerId = json.getString("layerId");
                AnnotationLayer textLayer = page.getLayerById(textLayerId);
                if (textLayer == null) {
                    android.util.Log.e("AnnotationPage", "Cannot deserialize MODIFY_TEXT_OBJECT: layer " + textLayerId + " not found");
                    return null;
                }
                return ModifyTextObjectCommand.fromJSON(textLayer, json);
                
            case "CLEAR_LAYER":
                return ClearLayerCommand.fromJSON(page, json);
                
            case "CLEAR_ALL_LAYERS":
                return ClearAllLayersCommand.fromJSON(page, json);
            
            default:
                android.util.Log.w("AnnotationPage", "Unknown command type: " + type);
                return null;
        }
    }
    
    /**
     * Reconstruct transient fields after deserialization.
     * SKIP if command history was already loaded from JSON.
     * Legacy fallback: rebuilds undo/redo history from all drawable objects in all layers.
     */
    public void reconstruct() {
        // CRITICAL: If commands were deserialized, don't overwrite them
        if (undoStack != null && !undoStack.isEmpty()) {
            android.util.Log.d("AnnotationPage", "Command history already loaded from JSON (" + 
                              undoStack.size() + " undo, " + redoStack.size() + " redo). Skipping reconstruct.");
            return;
        }
        
        initializeStacks();
        
        android.util.Log.d("AnnotationPage", "=== RECONSTRUCT HISTORY STARTED (Legacy) ===");
        android.util.Log.d("AnnotationPage", "Saved undo count: " + savedUndoCount);
        android.util.Log.d("AnnotationPage", "Saved redo count: " + savedRedoCount);
        
        // Rebuild undo history from ALL objects in ALL layers
        // Each object becomes one undo step
        int totalObjects = 0;
        for (AnnotationLayer layer : layers) {
            for (AnnotationObject obj : layer.getObjects()) {
                // Create a restore command for each existing object
                undoStack.push(new RestoreObjectCommand(layer, obj));
                totalObjects++;
            }
        }
        
        // Respect MAX_HISTORY_SIZE limit
        while (undoStack.size() > MAX_HISTORY_SIZE) {
            undoStack.remove(0);
        }
        
        android.util.Log.d("AnnotationPage", "Rebuilt " + totalObjects + " objects into undo stack");
        android.util.Log.d("AnnotationPage", "Final undo count: " + undoStack.size());
        android.util.Log.d("AnnotationPage", "=== RECONSTRUCT HISTORY COMPLETED ===");
    }
    
    /**
     * Simple command to restore an object's state (for reconstructed history)
     * LEGACY - only used when command history is NOT serialized
     */
    private static class RestoreObjectCommand implements DrawingCommand {
        private AnnotationLayer layer;
        private AnnotationObject object;
        private boolean wasRemoved = false;
        
        public RestoreObjectCommand(AnnotationLayer layer, AnnotationObject object) {
            this.layer = layer;
            this.object = object;
        }
        
        @Override
        public void execute() {
            if (wasRemoved && !layer.getObjects().contains(object)) {
                layer.getObjects().add(object);
                wasRemoved = false;
            }
        }
        
        @Override
        public void undo() {
            if (layer.getObjects().contains(object)) {
                layer.getObjects().remove(object);
                wasRemoved = true;
            }
        }
        
        @Override
        public String getDescription() {
            return "Restore " + object.getClass().getSimpleName();
        }
        
        @Override
        public String getCommandType() {
            return "RESTORE_OBJECT"; // Legacy command, not serialized
        }
        
        @Override
        public JSONObject toJSON() throws JSONException {
            // Legacy command - not serializable
            // This should never be called since reconstruct() is skipped when commands exist
            throw new UnsupportedOperationException("RestoreObjectCommand is legacy and not serializable");
        }
    }
}
