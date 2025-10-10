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
    private boolean hasBackground;
    private int backgroundColor;
    private RectF bounds; // For hit testing

    public TextObject() {
        super(ObjectType.TEXT);
        this.text = "";
        this.fontFamily = "ubuntu"; // Use Ubuntu font as default
        this.fontSize = 24f;
        this.textColor = 0xFFFFFFFF; // White default
        this.alignment = Paint.Align.LEFT;
        this.bold = false;
        this.italic = false;
        this.hasBackground = false;
        this.backgroundColor = 0xFF000000; // Black default
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
        if (!visible || text == null || text.isEmpty())
            return;

        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextSize(fontSize);
        paint.setColor(textColor);
        paint.setAlpha((int) (opacity * 255));
        // Don't set paint.setTextAlign() - we calculate position manually below

        // Set typeface based on style
        int style = Typeface.NORMAL;
        if (bold && italic)
            style = Typeface.BOLD_ITALIC;
        else if (bold)
            style = Typeface.BOLD;
        else if (italic)
            style = Typeface.ITALIC;

        Typeface typeface = Typeface.create(fontFamily, style);
        paint.setTypeface(typeface);

        // Update bounds for rendering (also used by getBounds())
        String[] lines = text.split("\n");
        float lineHeight = paint.descent() - paint.ascent();

        // Calculate text bounds
        float maxWidth = 0;
        for (String line : lines) {
            float width = paint.measureText(line);
            if (width > maxWidth)
                maxWidth = width;
        }
        float totalHeight = lineHeight * lines.length;

        // Apply transformation
        canvas.save();
        canvas.concat(transform);

        // Move to center position (x, y is now center)
        canvas.translate(x, y);

        // Rotate around center (at origin now after translate)
        canvas.rotate(rotation);

        // Apply scale around center
        canvas.scale(scale, scale);

        // Draw text offset by half dimensions so it's centered at (0,0)
        // Calculate start position based on alignment
        float startY = -totalHeight / 2f - paint.ascent(); // Adjust for baseline
        float yOffset = startY;

        // Draw background if enabled
        if (hasBackground) {
            Paint bgPaint = new Paint();
            bgPaint.setColor(backgroundColor);
            bgPaint.setAlpha((int) (opacity * 255));
            bgPaint.setStyle(Paint.Style.FILL);

            float padding = fontSize * 0.15f; // 15% padding
            RectF bgRect = new RectF(
                    -maxWidth / 2f - padding,
                    -totalHeight / 2f - padding,
                    maxWidth / 2f + padding,
                    totalHeight / 2f + padding);

            float cornerRadius = fontSize * 0.2f; // 20% corner radius
            canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint);
        }

        for (String line : lines) {
            float lineWidth = paint.measureText(line);
            float startX;

            // Calculate X position based on alignment
            if (alignment == Paint.Align.LEFT) {
                startX = -maxWidth / 2f;
            } else if (alignment == Paint.Align.RIGHT) {
                startX = maxWidth / 2f - lineWidth;
            } else { // CENTER
                startX = -lineWidth / 2f;
            }

            canvas.drawText(line, startX, yOffset, paint);
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
        json.put("hasBackground", hasBackground);
        json.put("backgroundColor", backgroundColor);
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
        this.hasBackground = json.optBoolean("hasBackground", false);
        this.backgroundColor = json.optInt("backgroundColor", 0xFF000000);
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
            if (width > maxWidth)
                maxWidth = width;
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
                (float) json.getDouble("bottom"));
    }

    // Check if point is within text bounds (for hit testing with rotation/scale
    // support)
    @Override
    public boolean contains(float px, float py) {
        // Transform touch point to object's local space (inverse transformation)
        // Since bounds are now centered at (x, y), we work in that coordinate system

        // Translate to object center (x, y)
        float tx = px - x;
        float ty = py - y;

        // Inverse rotation
        float radians = (float) Math.toRadians(-rotation);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        float rx = tx * cos - ty * sin;
        float ry = tx * sin + ty * cos;

        // Inverse scale
        if (scale != 0) {
            rx /= scale;
            ry /= scale;
        }

        // Now rx, ry are in local unscaled/unrotated space centered at origin
        // Get bounds dimensions
        RectF bounds = getBounds();
        float halfWidth = (bounds.right - bounds.left) / 2f;
        float halfHeight = (bounds.bottom - bounds.top) / 2f;

        // Test if point is within bounds (centered at origin)
        return Math.abs(rx) <= halfWidth && Math.abs(ry) <= halfHeight;
    }

    // Getters and setters
    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
        calculateBounds();
        this.modifiedAt = System.currentTimeMillis();
    }

    public String getFontFamily() {
        return fontFamily;
    }

    public void setFontFamily(String fontFamily) {
        this.fontFamily = fontFamily;
        calculateBounds();
        this.modifiedAt = System.currentTimeMillis();
    }

    public float getFontSize() {
        return fontSize;
    }

    public void setFontSize(float fontSize) {
        this.fontSize = fontSize;
        calculateBounds();
        this.modifiedAt = System.currentTimeMillis();
    }

    public int getTextColor() {
        return textColor;
    }

    public void setTextColor(int color) {
        this.textColor = color;
        this.modifiedAt = System.currentTimeMillis();
    }

    public Paint.Align getAlignment() {
        return alignment;
    }

    public void setAlignment(Paint.Align alignment) {
        this.alignment = alignment;
        this.modifiedAt = System.currentTimeMillis();
    }

    public boolean isBold() {
        return bold;
    }

    public void setBold(boolean bold) {
        this.bold = bold;
        this.modifiedAt = System.currentTimeMillis();
    }

    public boolean isItalic() {
        return italic;
    }

    public void setItalic(boolean italic) {
        this.italic = italic;
        this.modifiedAt = System.currentTimeMillis();
    }

    public boolean hasBackground() {
        return hasBackground;
    }

    public void setHasBackground(boolean hasBackground) {
        this.hasBackground = hasBackground;
        this.modifiedAt = System.currentTimeMillis();
    }

    public int getBackgroundColor() {
        return backgroundColor;
    }

    public void setBackgroundColor(int backgroundColor) {
        this.backgroundColor = backgroundColor;
        this.modifiedAt = System.currentTimeMillis();
    }

    @Override
    public RectF getBounds() {
        // Calculate bounds dynamically for hit testing
        if (text == null || text.isEmpty()) {
            return new RectF(x, y, x + 50, y + 50); // Default small bounds
        }

        Paint paint = new Paint();
        paint.setTextSize(fontSize);
        paint.setTextAlign(alignment);

        // Set typeface based on style
        int style = Typeface.NORMAL;
        if (bold && italic)
            style = Typeface.BOLD_ITALIC;
        else if (bold)
            style = Typeface.BOLD;
        else if (italic)
            style = Typeface.ITALIC;

        Typeface typeface = Typeface.create(fontFamily, style);
        paint.setTypeface(typeface);

        // Calculate dimensions
        String[] lines = text.split("\n");
        float maxWidth = 0;
        for (String line : lines) {
            float width = paint.measureText(line);
            if (width > maxWidth)
                maxWidth = width;
        }
        float lineHeight = paint.descent() - paint.ascent();
        float totalHeight = lineHeight * lines.length;

        // Return bounds centered at (x, y) for consistent rotation/scale
        // This ensures text doesn't drift when size changes
        float halfWidth = maxWidth / 2f;
        float halfHeight = totalHeight / 2f;

        return new RectF(
                x - halfWidth,
                y - halfHeight,
                x + halfWidth,
                y + halfHeight);
    }
}
