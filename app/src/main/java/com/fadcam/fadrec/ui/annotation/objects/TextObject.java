package com.fadcam.fadrec.ui.annotation.objects;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Text object with editable content, font, size, and styling.
 * Non-destructive - can be edited at any time.
 */
public class TextObject extends AnnotationObject {
    
    private String text;
    private String fontFamily;
    private float fontSize;
    private int textColor;
    private Paint.Align alignment;
    private boolean bold;
    private boolean italic;
    private RectF bounds; // For hit testing
    
    public TextObject() {
        super(ObjectType.TEXT);
        this.text = "";
        this.fontFamily = "sans-serif";
        this.fontSize = 24f;
        this.textColor = 0xFFFFFFFF; // White default
        this.alignment = Paint.Align.LEFT;
        this.bold = false;
        this.italic = false;
        this.bounds = new RectF();
    }
    
    public TextObject(String text, float x, float y) {
        this();
        this.text = text;
        this.x = x;
        this.y = y;
        calculateBounds();
    }
    
    @Override
    public void draw(Canvas canvas, Matrix transform) {
        if (!visible || text == null || text.isEmpty()) return;
        
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextSize(fontSize);
        paint.setColor(textColor);
        paint.setAlpha((int)(opacity * 255));
        paint.setTextAlign(alignment);
        
        // Set typeface based on style
        int style = Typeface.NORMAL;
        if (bold && italic) style = Typeface.BOLD_ITALIC;
        else if (bold) style = Typeface.BOLD;
        else if (italic) style = Typeface.ITALIC;
        
        Typeface typeface = Typeface.create(fontFamily, style);
        paint.setTypeface(typeface);
        
        // Apply transformation
        canvas.save();
        canvas.concat(transform);
        canvas.translate(x, y);
        canvas.rotate(rotation);
        
        // Draw text
        String[] lines = text.split("\n");
        float lineHeight = paint.descent() - paint.ascent();
        float yOffset = 0;
        
        for (String line : lines) {
            canvas.drawText(line, 0, yOffset, paint);
            yOffset += lineHeight;
        }
        
        canvas.restore();
    }
    
    @Override
    public JSONObject toJSON() throws JSONException {
        JSONObject json = getBaseJSON();
        json.put("text", text);
        json.put("fontFamily", fontFamily);
        json.put("fontSize", fontSize);
        json.put("textColor", textColor);
        json.put("alignment", alignment.name());
        json.put("bold", bold);
        json.put("italic", italic);
        json.put("bounds", boundsToJSON());
        return json;
    }
    
    @Override
    public void fromJSON(JSONObject json) throws JSONException {
        loadBaseJSON(json);
        this.text = json.getString("text");
        this.fontFamily = json.getString("fontFamily");
        this.fontSize = (float) json.getDouble("fontSize");
        this.textColor = json.getInt("textColor");
        this.alignment = Paint.Align.valueOf(json.getString("alignment"));
        this.bold = json.getBoolean("bold");
        this.italic = json.getBoolean("italic");
        this.bounds = boundsFromJSON(json.getJSONObject("bounds"));
    }
    
    @Override
    public TextObject clone() {
        TextObject clone = new TextObject(text, x, y);
        clone.setFontFamily(fontFamily);
        clone.setFontSize(fontSize);
        clone.setTextColor(textColor);
        clone.setAlignment(alignment);
        clone.setBold(bold);
        clone.setItalic(italic);
        clone.setRotation(rotation);
        clone.setVisible(visible);
        clone.setLocked(locked);
        clone.setOpacity(opacity);
        return clone;
    }
    
    // Calculate bounds for hit testing
    private void calculateBounds() {
        Paint paint = new Paint();
        paint.setTextSize(fontSize);
        
        float maxWidth = 0;
        String[] lines = text.split("\n");
        for (String line : lines) {
            float width = paint.measureText(line);
            if (width > maxWidth) maxWidth = width;
        }
        
        float height = lines.length * (paint.descent() - paint.ascent());
        bounds.set(0, 0, maxWidth, height);
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
    
    // Check if point is within text bounds (for hit testing)
    public boolean contains(float px, float py) {
        // Transform point to local coordinates
        float localX = px - x;
        float localY = py - y;
        return bounds.contains(localX, localY);
    }
    
    // Getters and setters
    public String getText() { return text; }
    public void setText(String text) {
        this.text = text;
        calculateBounds();
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public String getFontFamily() { return fontFamily; }
    public void setFontFamily(String fontFamily) {
        this.fontFamily = fontFamily;
        calculateBounds();
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public float getFontSize() { return fontSize; }
    public void setFontSize(float fontSize) {
        this.fontSize = fontSize;
        calculateBounds();
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public int getTextColor() { return textColor; }
    public void setTextColor(int color) {
        this.textColor = color;
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public Paint.Align getAlignment() { return alignment; }
    public void setAlignment(Paint.Align alignment) {
        this.alignment = alignment;
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public boolean isBold() { return bold; }
    public void setBold(boolean bold) {
        this.bold = bold;
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public boolean isItalic() { return italic; }
    public void setItalic(boolean italic) {
        this.italic = italic;
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public RectF getBounds() { return new RectF(bounds); }
}
