package com.fadcam.fadrec.ui.annotation;

import com.fadcam.fadrec.ui.annotation.objects.AnnotationObject;
import com.fadcam.fadrec.ui.annotation.objects.PathObject;
import com.fadcam.fadrec.ui.annotation.objects.TextObject;
import com.fadcam.fadrec.ui.annotation.objects.ShapeObject;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a single annotation layer with mixed objects (paths, text, shapes).
 * Each layer can be shown/hidden, locked/unlocked, and has opacity control.
 * Now supports heterogeneous object types for professional vector editing.
 * Uses soft-delete system for complete version control - deleted items are marked, not removed.
 */
public class AnnotationLayer {
    
    private String id;
    private String name;
    private List<AnnotationObject> objects; // Changed from List<DrawingPath>
    private boolean visible;
    private boolean locked;
    private boolean pinned; // NEW: Pinned layers stay visible even when canvas is hidden
    private boolean deleted; // NEW: Soft-delete flag - layer is hidden but preserved for version control
    private float opacity; // 0.0 to 1.0
    private long createdAt;
    
    public AnnotationLayer(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.objects = new ArrayList<>();
        this.visible = true;
        this.locked = false;
        this.pinned = false; // Default: not pinned
        this.deleted = false; // Default: not deleted
        this.opacity = 1.0f;
        this.createdAt = System.currentTimeMillis();
    }
    
    // JSON serialization
    public JSONObject toJSON() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("visible", visible);
        json.put("locked", locked);
        json.put("pinned", pinned); // Save pinned state
        json.put("deleted", deleted); // Save deleted state for version control
        json.put("opacity", opacity);
        json.put("createdAt", createdAt);
        
        JSONArray objectsArray = new JSONArray();
        for (AnnotationObject obj : objects) {
            objectsArray.put(obj.toJSON());
        }
        json.put("objects", objectsArray);
        
        return json;
    }
    
    public static AnnotationLayer fromJSON(JSONObject json) throws JSONException {
        AnnotationLayer layer = new AnnotationLayer(json.getString("name"));
        layer.id = json.getString("id");
        layer.visible = json.getBoolean("visible");
        layer.locked = json.getBoolean("locked");
        layer.pinned = json.getBoolean("pinned");
        layer.deleted = json.getBoolean("deleted"); // NO backward compatibility - permanent solution
        layer.opacity = (float) json.getDouble("opacity");
        layer.createdAt = json.getLong("createdAt");
        
        JSONArray objectsArray = json.getJSONArray("objects");
        for (int i = 0; i < objectsArray.length(); i++) {
            JSONObject objJson = objectsArray.getJSONObject(i);
            AnnotationObject obj = deserializeObject(objJson);
            if (obj != null) {
                layer.objects.add(obj);
            }
        }
        
        return layer;
    }
    
    private static AnnotationObject deserializeObject(JSONObject json) throws JSONException {
        String typeStr = json.getString("type");
        AnnotationObject.ObjectType type = AnnotationObject.ObjectType.valueOf(typeStr);
        
        AnnotationObject obj;
        switch (type) {
            case PATH:
                obj = new PathObject();
                break;
            case TEXT:
                obj = new TextObject();
                break;
            case SHAPE:
                obj = new ShapeObject();
                break;
            default:
                return null;
        }
        
        obj.fromJSON(json);
        return obj;
    }
    
    // Getters and setters
    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public List<AnnotationObject> getObjects() { return objects; }
    public void addObject(AnnotationObject object) { objects.add(object); }
    public void removeObject(AnnotationObject object) { objects.remove(object); }
    public void clearObjects() { objects.clear(); }
    
    // Backward compatibility - convert DrawingPath to PathObject
    @Deprecated
    public void addPath(DrawingPath path) {
        // Convert old DrawingPath to new PathObject
        PathObject pathObj = new PathObject(path.path, path.color, path.strokeWidth, path.isEraser);
        objects.add(pathObj);
    }
    
    @Deprecated
    public void removePath(DrawingPath path) {
        // Find and remove PathObject with matching properties
        // Note: This is a best-effort removal for backward compatibility
        for (int i = objects.size() - 1; i >= 0; i--) {
            AnnotationObject obj = objects.get(i);
            if (obj instanceof PathObject) {
                // For now, just remove the last PathObject (typical undo scenario)
                objects.remove(i);
                break;
            }
        }
    }
    
    @Deprecated
    public void clearPaths() {
        clearObjects();
    }
    
    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }
    
    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }
    
    public boolean isPinned() { return pinned; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }
    
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
    
    public float getOpacity() { return opacity; }
    public void setOpacity(float opacity) { 
        this.opacity = Math.max(0f, Math.min(1f, opacity)); 
    }
    
    public long getCreatedAt() { return createdAt; }
}
