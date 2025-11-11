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
    private int maxWidth; // Maximum text width for line wrapping (from editor)

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
        this.maxWidth = 0; // 0 means no constraint (use natural wrapping)
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

        // Setup paint
        android.text.TextPaint textPaint = new android.text.TextPaint();
        textPaint.setAntiAlias(true);
        textPaint.setTextSize(fontSize);
        textPaint.setColor(textColor);
        textPaint.setAlpha((int) (opacity * 255));

        // Set typeface based on style
        int style = Typeface.NORMAL;
        if (bold && italic)
            style = Typeface.BOLD_ITALIC;
        else if (bold)
            style = Typeface.BOLD;
        else if (italic)
            style = Typeface.ITALIC;

        Typeface typeface = Typeface.create(fontFamily, style);
        textPaint.setTypeface(typeface);
        
        // Determine layout width (use maxWidth from editor if set, otherwise measure longest line)
        int layoutWidth;
        if (maxWidth > 0) {
            layoutWidth = maxWidth;
            android.util.Log.d("TextObject", "Using maxWidth from editor: " + layoutWidth);
        } else {
            // Auto-width: measure the longest line
            String[] lines = text.split("\n");
            float maxLineWidth = 0;
            for (String line : lines) {
                float lineWidth = textPaint.measureText(line);
                if (lineWidth > maxLineWidth) maxLineWidth = lineWidth;
            }
            layoutWidth = (int) Math.ceil(maxLineWidth);
            android.util.Log.d("TextObject", "Calculated layoutWidth: " + layoutWidth);
        }
        
        // Create StaticLayout for proper text wrapping
        android.text.StaticLayout layout = android.text.StaticLayout.Builder
            .obtain(text, 0, text.length(), textPaint, layoutWidth)
            .setAlignment(convertPaintAlignToLayoutAlignment(alignment))
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build();
        
        // Use layout width as container width (StaticLayout positions text within this width)
        float containerWidth = layoutWidth;
        float containerHeight = layout.getHeight();

        // Apply transformation
        canvas.save();
        canvas.concat(transform);

        // Move to position
        canvas.translate(x, y);

        // Rotate around center
        canvas.rotate(rotation);

        // Apply scale
        canvas.scale(scale, scale);

        // Measure actual text bounds (needed for proper centering with different alignments)
        float textBoundsLeft = 0;
        float textBoundsRight = 0;
        float textCenterOffset = 0;
        
        if (layout.getLineCount() > 0) {
            if (alignment == Paint.Align.LEFT) {
                // LEFT: text starts at 0, find the rightmost position
                textBoundsLeft = 0;
                textBoundsRight = 0;
                for (int i = 0; i < layout.getLineCount(); i++) {
                    float lineWidth = layout.getLineMax(i);
                    if (lineWidth > textBoundsRight) {
                        textBoundsRight = lineWidth;
                    }
                }
                textCenterOffset = (textBoundsLeft + textBoundsRight) / 2f;
            } else if (alignment == Paint.Align.RIGHT) {
                // RIGHT: text ends at layoutWidth, starts from layoutWidth - measured_width
                textBoundsRight = layoutWidth;
                textBoundsLeft = layoutWidth;
                for (int i = 0; i < layout.getLineCount(); i++) {
                    float lineWidth = layout.getLineMax(i);
                    float lineStart = layoutWidth - lineWidth;
                    if (lineStart < textBoundsLeft) {
                        textBoundsLeft = lineStart;
                    }
                }
                textCenterOffset = (textBoundsLeft + textBoundsRight) / 2f;
            } else {
                // CENTER: StaticLayout centers text at layoutWidth/2
                textCenterOffset = layoutWidth / 2f;
                float halfWidth = 0;
                for (int i = 0; i < layout.getLineCount(); i++) {
                    float lineWidth = layout.getLineMax(i);
                    if (lineWidth > halfWidth) {
                        halfWidth = lineWidth;
                    }
                }
                textBoundsLeft = textCenterOffset - halfWidth / 2f;
                textBoundsRight = textCenterOffset + halfWidth / 2f;
            }
        }

        // Draw background if enabled
        if (hasBackground) {
            Paint bgPaint = new Paint();
            bgPaint.setColor(backgroundColor);
            bgPaint.setAlpha((int) (opacity * 255));
            bgPaint.setStyle(Paint.Style.FILL);

            float padding = fontSize * 0.15f;
            android.graphics.RectF bgRect = new android.graphics.RectF(
                    textBoundsLeft - padding,
                    -containerHeight / 2f - padding,
                    textBoundsRight + padding,
                    containerHeight / 2f + padding);

            float cornerRadius = fontSize * 0.2f;
            canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint);
        }

        // Center the text by offsetting to the actual text center
        canvas.translate(-textCenterOffset, -containerHeight / 2f);
        layout.draw(canvas);

        canvas.restore();
    }
    
    // Helper to convert Paint.Align to Layout.Alignment
    private android.text.Layout.Alignment convertPaintAlignToLayoutAlignment(Paint.Align align) {
        if (align == Paint.Align.LEFT) {
            return android.text.Layout.Alignment.ALIGN_NORMAL;
        } else if (align == Paint.Align.RIGHT) {
            return android.text.Layout.Alignment.ALIGN_OPPOSITE;
        } else {
            return android.text.Layout.Alignment.ALIGN_CENTER;
        }
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
        json.put("maxWidth", maxWidth);
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
        this.maxWidth = json.optInt("maxWidth", 0);
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
        clone.setHasBackground(hasBackground);
        clone.setBackgroundColor(backgroundColor);
        clone.setMaxWidth(maxWidth);
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

    public int getMaxWidth() {
        return maxWidth;
    }

    public void setMaxWidth(int maxWidth) {
        this.maxWidth = maxWidth;
        calculateBounds();
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
