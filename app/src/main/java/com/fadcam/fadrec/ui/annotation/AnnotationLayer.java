package com.fadcam.fadrec.ui.annotation;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a single annotation layer with drawing paths.
 * Each layer can be shown/hidden, locked/unlocked, and has opacity control.
 */
public class AnnotationLayer implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String id;
    private String name;
    private List<DrawingPath> paths;
    private boolean visible;
    private boolean locked;
    private float opacity; // 0.0 to 1.0
    private long createdAt;
    
    public AnnotationLayer(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.paths = new ArrayList<>();
        this.visible = true;
        this.locked = false;
        this.opacity = 1.0f;
        this.createdAt = System.currentTimeMillis();
    }
    
    // Getters and setters
    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public List<DrawingPath> getPaths() { return paths; }
    public void addPath(DrawingPath path) { paths.add(path); }
    public void removePath(DrawingPath path) { paths.remove(path); }
    public void clearPaths() { paths.clear(); }
    
    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }
    
    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }
    
    public float getOpacity() { return opacity; }
    public void setOpacity(float opacity) { 
        this.opacity = Math.max(0f, Math.min(1f, opacity)); 
    }
    
    public long getCreatedAt() { return createdAt; }
    
    /**
     * Reconstruct transient fields after deserialization
     */
    public void reconstruct() {
        for (DrawingPath path : paths) {
            path.reconstruct();
        }
    }
}
