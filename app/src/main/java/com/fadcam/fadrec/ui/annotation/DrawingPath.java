package com.fadcam.fadrec.ui.annotation;

import android.graphics.Paint;
import android.graphics.Path;

import java.io.Serializable;

/**
 * Represents a single drawing path with its paint properties.
 * Serializable for state persistence.
 */
public class DrawingPath implements Serializable {
    private static final long serialVersionUID = 1L;
    
    public String pathData; // SVG path data for serialization
    public int color;
    public float strokeWidth;
    public boolean isEraser;
    public long timestamp;
    
    // Transient fields (not serialized)
    public transient Path path;
    public transient Paint paint;
    
    public DrawingPath(Path path, Paint paint) {
        this.path = new Path(path);
        this.paint = new Paint(paint);
        
        // Store properties for serialization
        this.color = paint.getColor();
        this.strokeWidth = paint.getStrokeWidth();
        this.isEraser = (paint.getXfermode() != null);
        this.timestamp = System.currentTimeMillis();
        
        // Convert path to SVG data for serialization
        this.pathData = pathToString(path);
    }
    
    /**
     * Reconstruct transient fields after deserialization
     */
    public void reconstruct() {
        this.path = stringToPath(pathData);
        
        this.paint = new Paint();
        this.paint.setAntiAlias(true);
        this.paint.setStyle(Paint.Style.STROKE);
        this.paint.setStrokeJoin(Paint.Join.ROUND);
        this.paint.setStrokeCap(Paint.Cap.ROUND);
        this.paint.setColor(color);
        this.paint.setStrokeWidth(strokeWidth);
        
        if (isEraser) {
            this.paint.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.CLEAR));
        }
    }
    
    /**
     * Convert Path to string representation for serialization
     */
    private String pathToString(Path path) {
        // Simple implementation - stores as comma-separated coordinates
        // In production, use proper SVG path serialization
        return ""; // Placeholder - will implement proper serialization
    }
    
    /**
     * Convert string back to Path after deserialization
     */
    private Path stringToPath(String data) {
        Path path = new Path();
        // Parse and reconstruct path from data
        return path;
    }
}
