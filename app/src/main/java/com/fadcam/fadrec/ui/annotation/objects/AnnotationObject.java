package com.fadcam.fadrec.ui.annotation.objects;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * Base class for all annotation objects (paths, text, shapes, images).
 * Provides common properties like ID, position, rotation, visibility.
 * Implements JSON serialization for .fadproj file format.
 * Uses soft-delete system for complete version control - deleted items are marked, not removed.
 */
public abstract class AnnotationObject {
    
    // Object types
    public enum ObjectType {
        PATH,       // Freehand drawing (pen/eraser)
        TEXT,       // Editable text
        SHAPE,      // Rectangle, circle, arrow, etc.
        IMAGE       // Embedded image (future)
    }
    
    // Common properties
    protected String id;
    protected ObjectType type;
    protected float x, y;                 // Position
    protected float rotation;             // Rotation in degrees
    protected float scale;                // Scale factor (1.0 = 100%)
    protected boolean visible;
    protected boolean locked;
    protected boolean deleted;            // NEW: Soft-delete flag for version control
    protected float opacity;              // 0.0 to 1.0
    protected long createdAt;
    protected long modifiedAt;
    
    public AnnotationObject(ObjectType type) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.x = 0;
        this.y = 0;
        this.rotation = 0;
        this.scale = 1.0f;
        this.visible = true;
        this.locked = false;
        this.deleted = false;             // Default: not deleted
        this.opacity = 1.0f;
        this.createdAt = System.currentTimeMillis();
        this.modifiedAt = createdAt;
    }
    
    // Abstract methods for rendering and serialization
    public abstract void draw(android.graphics.Canvas canvas, android.graphics.Matrix transform);
    public abstract JSONObject toJSON() throws JSONException;
    public abstract void fromJSON(JSONObject json) throws JSONException;
    public abstract AnnotationObject clone();
    
    // Common JSON serialization
    protected JSONObject getBaseJSON() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("type", type.name());
        json.put("x", x);
        json.put("y", y);
        json.put("rotation", rotation);
        json.put("scale", scale);
        json.put("visible", visible);
        json.put("locked", locked);
        json.put("deleted", deleted); // Save deleted state for version control
        json.put("opacity", opacity);
        json.put("createdAt", createdAt);
        json.put("modifiedAt", modifiedAt);
        return json;
    }
    
    protected void loadBaseJSON(JSONObject json) throws JSONException {
        this.id = json.getString("id");
        this.x = (float) json.getDouble("x");
        this.y = (float) json.getDouble("y");
        this.rotation = (float) json.getDouble("rotation");
        this.scale = (float) json.getDouble("scale");
        this.visible = json.getBoolean("visible");
        this.locked = json.getBoolean("locked");
        this.deleted = json.getBoolean("deleted"); // NO backward compatibility - permanent solution
        this.opacity = (float) json.getDouble("opacity");
        this.createdAt = json.getLong("createdAt");
        this.modifiedAt = json.getLong("modifiedAt");
    }
    
    // Getters and setters
    public String getId() { return id; }
    public ObjectType getType() { return type; }
    
    public float getX() { return x; }
    public void setX(float x) {
        this.x = x;
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public float getY() { return y; }
    public void setY(float y) {
        this.y = y;
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public float getRotation() { return rotation; }
    public void setRotation(float rotation) {
        this.rotation = rotation;
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public float getScale() { return scale; }
    public void setScale(float scale) {
        this.scale = Math.max(0.1f, Math.min(5.0f, scale)); // Limit 0.1x to 5x
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) {
        this.visible = visible;
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) {
        this.locked = locked;
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public float getOpacity() { return opacity; }
    public void setOpacity(float opacity) {
        this.opacity = Math.max(0, Math.min(1, opacity));
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public long getCreatedAt() { return createdAt; }
    public long getModifiedAt() { return modifiedAt; }
    
    /**
     * Move object by delta x and y
     */
    public void translate(float dx, float dy) {
        this.x += dx;
        this.y += dy;
        this.modifiedAt = System.currentTimeMillis();
    }
    
    /**
     * Check if point is within object bounds
     */
    public boolean contains(float x, float y) {
        android.graphics.RectF bounds = getBounds();
        return bounds.contains(x, y);
    }
    
    /**
     * Get bounding rectangle for hit testing
     * Subclasses should override to provide accurate bounds
     */
    public android.graphics.RectF getBounds() {
        // Default: 50x50 box around position
        return new android.graphics.RectF(x - 25, y - 25, x + 25, y + 25);
    }
}
