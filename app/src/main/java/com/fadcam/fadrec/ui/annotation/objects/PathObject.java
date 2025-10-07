package com.fadcam.fadrec.ui.annotation.objects;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Path object representing freehand drawing (pen/eraser strokes).
 * Stores vector path data for non-destructive editing.
 */
public class PathObject extends AnnotationObject {
    
    private Path path;
    private int strokeColor;
    private float strokeWidth;
    private Paint.Cap strokeCap;
    private Paint.Join strokeJoin;
    private boolean isEraser;
    
    // For serialization - store path as SVG data
    private String svgPathData;
    
    public PathObject() {
        super(ObjectType.PATH);
        this.path = new Path();
        this.strokeColor = 0xFFF44336; // Red default
        this.strokeWidth = 8f;
        this.strokeCap = Paint.Cap.ROUND;
        this.strokeJoin = Paint.Join.ROUND;
        this.isEraser = false;
    }
    
    public PathObject(Path path, int color, float width, boolean isEraser) {
        super(ObjectType.PATH);
        this.path = new Path(path);
        this.strokeColor = color;
        this.strokeWidth = width;
        this.strokeCap = Paint.Cap.ROUND;
        this.strokeJoin = Paint.Join.ROUND;
        this.isEraser = isEraser;
        this.svgPathData = pathToSvgString(path);
    }
    
    @Override
    public void draw(Canvas canvas, Matrix transform) {
        if (!visible || path == null) return;
        
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);
        paint.setStrokeCap(strokeCap);
        paint.setStrokeJoin(strokeJoin);
        paint.setColor(strokeColor);
        paint.setAlpha((int)(opacity * 255));
        
        if (isEraser) {
            paint.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.CLEAR));
        }
        
        // Apply transformation
        canvas.save();
        canvas.concat(transform);
        canvas.translate(x, y);
        canvas.rotate(rotation);
        canvas.drawPath(path, paint);
        canvas.restore();
    }
    
    @Override
    public JSONObject toJSON() throws JSONException {
        JSONObject json = getBaseJSON();
        json.put("strokeColor", strokeColor);
        json.put("strokeWidth", strokeWidth);
        json.put("strokeCap", strokeCap.name());
        json.put("strokeJoin", strokeJoin.name());
        json.put("isEraser", isEraser);
        json.put("svgPathData", svgPathData != null ? svgPathData : pathToSvgString(path));
        return json;
    }
    
    @Override
    public void fromJSON(JSONObject json) throws JSONException {
        loadBaseJSON(json);
        this.strokeColor = json.getInt("strokeColor");
        this.strokeWidth = (float) json.getDouble("strokeWidth");
        this.strokeCap = Paint.Cap.valueOf(json.getString("strokeCap"));
        this.strokeJoin = Paint.Join.valueOf(json.getString("strokeJoin"));
        this.isEraser = json.getBoolean("isEraser");
        this.svgPathData = json.getString("svgPathData");
        this.path = svgStringToPath(svgPathData);
    }
    
    @Override
    public PathObject clone() {
        PathObject clone = new PathObject(path, strokeColor, strokeWidth, isEraser);
        clone.setPosition(x, y);
        clone.setRotation(rotation);
        clone.setVisible(visible);
        clone.setLocked(locked);
        clone.setOpacity(opacity);
        return clone;
    }
    
    // SVG path conversion - stores path as list of coordinates
    private String pathToSvgString(Path path) {
        if (path == null) return "";
        
        // Convert path to array of points
        android.graphics.PathMeasure pm = new android.graphics.PathMeasure(path, false);
        float[] point = new float[2];
        StringBuilder sb = new StringBuilder();
        
        float distance = 0;
        float length = pm.getLength();
        float step = Math.max(1f, length / 500); // Sample up to 500 points
        
        while (distance < length) {
            pm.getPosTan(distance, point, null);
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(point[0]).append(",").append(point[1]);
            distance += step;
        }
        
        // Add final point
        if (length > 0) {
            pm.getPosTan(length, point, null);
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(point[0]).append(",").append(point[1]);
        }
        
        return sb.toString();
    }
    
    private Path svgStringToPath(String svgData) {
        Path path = new Path();
        if (svgData == null || svgData.isEmpty()) {
            return path;
        }
        
        try {
            String[] coords = svgData.split(",");
            if (coords.length < 2) {
                return path;
            }
            
            // First point - moveTo
            float x = Float.parseFloat(coords[0]);
            float y = Float.parseFloat(coords[1]);
            path.moveTo(x, y);
            
            // Remaining points - lineTo
            for (int i = 2; i < coords.length; i += 2) {
                if (i + 1 < coords.length) {
                    x = Float.parseFloat(coords[i]);
                    y = Float.parseFloat(coords[i + 1]);
                    path.lineTo(x, y);
                }
            }
        } catch (NumberFormatException e) {
            android.util.Log.e("PathObject", "Failed to parse SVG path data", e);
        }
        
        return path;
    }
    
    // Getters and setters
    public Path getPath() { return path; }
    public void setPath(Path path) {
        this.path = path;
        this.svgPathData = pathToSvgString(path);
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
    
    public boolean isEraser() { return isEraser; }
}
