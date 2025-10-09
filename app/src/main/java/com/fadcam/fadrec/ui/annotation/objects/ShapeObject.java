package com.fadcam.fadrec.ui.annotation.objects;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Shape object for geometric shapes (rectangle, circle, arrow, line, etc.).
 * Fully resizable and editable.
 */
public class ShapeObject extends AnnotationObject {
    
    public enum ShapeType {
        RECTANGLE,
        CIRCLE,
        ELLIPSE,
        LINE,
        ARROW,
        TRIANGLE,
        STAR
    }
    
    private ShapeType shapeType;
    private RectF bounds; // Defines shape area
    private int fillColor;
    private int strokeColor;
    private float strokeWidth;
    private boolean filled;
    private float cornerRadius; // For rounded rectangles
    
    public ShapeObject() {
        super(ObjectType.SHAPE);
        this.shapeType = ShapeType.RECTANGLE;
        this.bounds = new RectF();
        this.fillColor = 0x80FF5722; // Semi-transparent orange
        this.strokeColor = 0xFFFF5722; // Orange
        this.strokeWidth = 4f;
        this.filled = true;
        this.cornerRadius = 0f;
    }
    
    public ShapeObject(ShapeType type, float left, float top, float right, float bottom) {
        this();
        this.shapeType = type;
        // Store bounds in world space
        this.bounds.set(left, top, right, bottom);
        this.x = left; // Position at top-left
        this.y = top;
    }
    
    @Override
    public void draw(Canvas canvas, Matrix transform) {
        if (!visible) return;
        
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        
        canvas.save();
        canvas.concat(transform);
        // Rotate at center and apply scale
        canvas.rotate(rotation, bounds.centerX(), bounds.centerY());
        canvas.scale(scale, scale, bounds.centerX(), bounds.centerY());
        
        switch (shapeType) {
            case RECTANGLE:
                drawRectangle(canvas, paint);
                break;
            case CIRCLE:
                drawCircle(canvas, paint);
                break;
            case ELLIPSE:
                drawEllipse(canvas, paint);
                break;
            case LINE:
                drawLine(canvas, paint);
                break;
            case ARROW:
                drawArrow(canvas, paint);
                break;
            case TRIANGLE:
                drawTriangle(canvas, paint);
                break;
            case STAR:
                drawStar(canvas, paint);
                break;
        }
        
        canvas.restore();
    }
    
    private void drawRectangle(Canvas canvas, Paint paint) {
        if (filled) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withOpacity(fillColor));
            canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, paint);
        }
        
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);
        paint.setColor(withOpacity(strokeColor));
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, paint);
    }
    
    private void drawCircle(Canvas canvas, Paint paint) {
        float cx = bounds.centerX();
        float cy = bounds.centerY();
        float radius = Math.min(bounds.width(), bounds.height()) / 2f;
        
        if (filled) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withOpacity(fillColor));
            canvas.drawCircle(cx, cy, radius, paint);
        }
        
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);
        paint.setColor(withOpacity(strokeColor));
        canvas.drawCircle(cx, cy, radius, paint);
    }
    
    private void drawEllipse(Canvas canvas, Paint paint) {
        if (filled) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withOpacity(fillColor));
            canvas.drawOval(bounds, paint);
        }
        
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);
        paint.setColor(withOpacity(strokeColor));
        canvas.drawOval(bounds, paint);
    }
    
    private void drawLine(Canvas canvas, Paint paint) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(withOpacity(strokeColor));
        canvas.drawLine(bounds.left, bounds.top, bounds.right, bounds.bottom, paint);
    }
    
    private void drawArrow(Canvas canvas, Paint paint) {
        Path path = new Path();
        
        // Arrow line
        path.moveTo(bounds.left, bounds.top);
        path.lineTo(bounds.right, bounds.bottom);
        
        // Arrow head
        float angle = (float) Math.atan2(bounds.bottom - bounds.top, bounds.right - bounds.left);
        float arrowSize = 30f;
        
        float x1 = bounds.right - arrowSize * (float) Math.cos(angle - Math.PI / 6);
        float y1 = bounds.bottom - arrowSize * (float) Math.sin(angle - Math.PI / 6);
        float x2 = bounds.right - arrowSize * (float) Math.cos(angle + Math.PI / 6);
        float y2 = bounds.bottom - arrowSize * (float) Math.sin(angle + Math.PI / 6);
        
        path.moveTo(bounds.right, bounds.bottom);
        path.lineTo(x1, y1);
        path.moveTo(bounds.right, bounds.bottom);
        path.lineTo(x2, y2);
        
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(withOpacity(strokeColor));
        canvas.drawPath(path, paint);
    }
    
    private void drawTriangle(Canvas canvas, Paint paint) {
        Path path = new Path();
        path.moveTo(bounds.centerX(), bounds.top);
        path.lineTo(bounds.left, bounds.bottom);
        path.lineTo(bounds.right, bounds.bottom);
        path.close();
        
        if (filled) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withOpacity(fillColor));
            canvas.drawPath(path, paint);
        }
        
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);
        paint.setColor(withOpacity(strokeColor));
        canvas.drawPath(path, paint);
    }
    
    private void drawStar(Canvas canvas, Paint paint) {
        Path path = new Path();
        int points = 5;
        float outerRadius = Math.min(bounds.width(), bounds.height()) / 2f;
        float innerRadius = outerRadius * 0.4f;
        float cx = bounds.centerX();
        float cy = bounds.centerY();
        
        for (int i = 0; i < points * 2; i++) {
            float radius = (i % 2 == 0) ? outerRadius : innerRadius;
            float angle = (float) (i * Math.PI / points - Math.PI / 2);
            float px = cx + radius * (float) Math.cos(angle);
            float py = cy + radius * (float) Math.sin(angle);
            
            if (i == 0) path.moveTo(px, py);
            else path.lineTo(px, py);
        }
        path.close();
        
        if (filled) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withOpacity(fillColor));
            canvas.drawPath(path, paint);
        }
        
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);
        paint.setColor(withOpacity(strokeColor));
        canvas.drawPath(path, paint);
    }
    
    @Override
    public JSONObject toJSON() throws JSONException {
        JSONObject json = getBaseJSON();
        json.put("shapeType", shapeType.name());
        json.put("bounds", boundsToJSON());
        json.put("fillColor", fillColor);
        json.put("strokeColor", strokeColor);
        json.put("strokeWidth", strokeWidth);
        json.put("filled", filled);
        json.put("cornerRadius", cornerRadius);
        return json;
    }
    
    @Override
    public void fromJSON(JSONObject json) throws JSONException {
        loadBaseJSON(json);
        this.shapeType = ShapeType.valueOf(json.getString("shapeType"));
        this.bounds = boundsFromJSON(json.getJSONObject("bounds"));
        this.fillColor = json.getInt("fillColor");
        this.strokeColor = json.getInt("strokeColor");
        this.strokeWidth = (float) json.getDouble("strokeWidth");
        this.filled = json.getBoolean("filled");
        this.cornerRadius = (float) json.getDouble("cornerRadius");
    }
    
    @Override
    public ShapeObject clone() {
        ShapeObject clone = new ShapeObject(shapeType, bounds.left, bounds.top, bounds.right, bounds.bottom);
        clone.setFillColor(fillColor);
        clone.setStrokeColor(strokeColor);
        clone.setStrokeWidth(strokeWidth);
        clone.setFilled(filled);
        clone.setCornerRadius(cornerRadius);
        clone.setRotation(rotation);
        clone.setVisible(visible);
        clone.setLocked(locked);
        clone.setOpacity(opacity);
        return clone;
    }

    private int withOpacity(int color) {
        int originalAlpha = (color >>> 24) & 0xFF;
        int targetAlpha = Math.round(originalAlpha * opacity);
        targetAlpha = Math.max(0, Math.min(255, targetAlpha));
        return (color & 0x00FFFFFF) | (targetAlpha << 24);
    }
    
    private JSONObject boundsToJSON() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("left", bounds.left);
        json.put("top", bounds.top);
        json.put("right", bounds.right);
        json.put("bottom", bounds.bottom);
        return json;
    }
    
    private RectF boundsFromJSON(JSONObject json) throws JSONException {
        return new RectF(
            (float) json.getDouble("left"),
            (float) json.getDouble("top"),
            (float) json.getDouble("right"),
            (float) json.getDouble("bottom")
        );
    }
    
    @Override
    public void translate(float dx, float dy) {
        super.translate(dx, dy); // Update x, y
        // Also update bounds since they're in world space
        bounds.offset(dx, dy);
    }
    
    // Check if point is within shape bounds
    @Override
    public boolean contains(float px, float py) {
        return bounds.contains(px, py); // Bounds are in world space
    }
    
    // Getters and setters
    public ShapeType getShapeType() { return shapeType; }
    public void setShapeType(ShapeType type) {
        this.shapeType = type;
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public RectF getBounds() { 
        // Bounds are already in world space
        return new RectF(bounds);
    }
    public void setBounds(float left, float top, float right, float bottom) {
        this.bounds.set(left, top, right, bottom);
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public int getFillColor() { return fillColor; }
    public void setFillColor(int color) {
        this.fillColor = color;
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public int getStrokeColor() { return strokeColor; }
    public void setStrokeColor(int color) {
        this.strokeColor = color;
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public float getStrokeWidth() { return strokeWidth; }
    public void setStrokeWidth(float width) {
        this.strokeWidth = width;
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public boolean isFilled() { return filled; }
    public void setFilled(boolean filled) {
        this.filled = filled;
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public float getCornerRadius() { return cornerRadius; }
    public void setCornerRadius(float radius) {
        this.cornerRadius = radius;
        this.modifiedAt = System.currentTimeMillis();
    }
}
