package com.fadcam.ui.components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Custom view for displaying a reusable "NEW" badge for new features.
 * Used across the app for consistent new feature indicators.
 *
 * Attributes:
 * - badgeColor: Badge background color (default: #4CAF50 - Material Green)
 * - textColor: Badge text color (default: #FFFFFF - white)
 * - badgeText: Text to display (default: "NEW")
 *
 * Usage in XML:
 * <com.fadcam.ui.components.NewFeatureBadge
 *     android:layout_width="wrap_content"
 *     android:layout_height="wrap_content"
 *     app:badgeColor="@color/new_badge_green"
 *     app:badgeText="NEW" />
 */
public class NewFeatureBadge extends View {

    private Paint badgePaint;
    private Paint textPaint;
    private String badgeText;
    private int badgeColor;
    private int textColor;
    private float cornerRadius;

    public NewFeatureBadge(Context context) {
        super(context);
        init(context, null);
    }

    public NewFeatureBadge(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public NewFeatureBadge(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(Context context, @Nullable AttributeSet attrs) {
        // Default values
        badgeText = "NEW";
        badgeColor = 0xFF4CAF50; // Material Green 500
        textColor = 0xFFFFFFFF; // White
        cornerRadius = 12f;

        // Parse custom attributes if provided
        if (attrs != null) {
            int[] attrsArray = {
                    android.R.attr.text,
                    android.R.attr.textColor,
                    android.R.attr.background
            };
            // TODO: If using custom namespace, parse properly with TypedArray
        }

        // Initialize paints
        badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        badgePaint.setColor(badgeColor);
        badgePaint.setStyle(Paint.Style.FILL);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(textColor);
        textPaint.setTextSize(28f); // sp equivalent to ~28px
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
    }

    public void setBadgeText(String text) {
        this.badgeText = text;
        invalidate();
    }

    public void setBadgeColor(int color) {
        this.badgeColor = color;
        badgePaint.setColor(color);
        invalidate();
    }

    public void setTextColor(int color) {
        this.textColor = color;
        textPaint.setColor(color);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Fixed size: 56dp x 24dp (Material design badge standard)
        int width = (int) (56 * getResources().getDisplayMetrics().density);
        int height = (int) (24 * getResources().getDisplayMetrics().density);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();

        // Draw rounded badge background
        canvas.drawRoundRect(0, 0, width, height, cornerRadius, cornerRadius, badgePaint);

        // Draw badge text centered
        float textX = width / 2f;
        float textY = (height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f);
        canvas.drawText(badgeText, textX, textY, textPaint);
    }
}
