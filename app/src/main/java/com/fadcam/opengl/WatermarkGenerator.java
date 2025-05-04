package com.fadcam.opengl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.Log;

import com.fadcam.Constants;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.ui.LocationHelper; // Assuming LocationHelper is accessible or moved

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Generates the dynamic watermark text and renders it onto a Bitmap.
 */
public class WatermarkGenerator {
    private static final String TAG = "WatermarkGenerator";

    private Context mContext;
    private SharedPreferencesManager mSharedPreferencesManager;
    private LocationHelper mLocationHelper; // Assuming LocationHelper is available

    private Paint mTextPaint;
    private int mBitmapWidth = 512; // Default texture size (power of 2)
    private int mBitmapHeight = 128; // Default texture size (power of 2)

    public WatermarkGenerator(Context context, SharedPreferencesManager sharedPreferencesManager, LocationHelper locationHelper) {
        mContext = context;
        mSharedPreferencesManager = sharedPreferencesManager;
        mLocationHelper = locationHelper; // Initialize LocationHelper

        setupTextPaint();
    }

    private void setupTextPaint() {
        mTextPaint = new Paint();
        mTextPaint.setAntiAlias(true);
        mTextPaint.setColor(Color.WHITE); // Default color
        mTextPaint.setTextSize(32); // Default size (will be adjusted)
        mTextPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)); // Use a monospaced font for stability
        mTextPaint.setShadowLayer(1f, 0f, 0f, Color.BLACK); // Simple shadow for readability
    }

    /**
     * Generates the current watermark text based on user preferences.
     */
    private String generateWatermarkText() {
        String watermarkOption = mSharedPreferencesManager.getWatermarkOption();
        if ("no_watermark".equals(watermarkOption)) {
            return null; // No watermark requested
        }

        String text = "";
        boolean isLocationEnabled = mSharedPreferencesManager.isLocalisationEnabled();
        String locationText = "";
        if (isLocationEnabled && mLocationHelper != null) {
             locationText = mLocationHelper.getLocationData(); // Get location data
             if (locationText != null && !locationText.isEmpty()) {
                 // Format location data as needed, e.g., " Lat:XX.XX Lon:YY.YY"
                 // Assuming getLocationData returns something like "Lat=XX.XX, Lon=YY.YY"
                 // We might need to parse and reformat it here if necessary.
                 // For now, let's just append it if available.
                 text += " " + locationText;
             }
        }


        switch (watermarkOption) {
            case "timestamp":
                text = getCurrentTimestamp() + text; // Add timestamp + location
                break;
            case "timestamp_fadcam":
            default: // Default to timestamp_fadcam if option is unknown
                text = "FadCam - " + getCurrentTimestamp() + text; // Add "FadCam - " + timestamp + location
                break;
        }

        // Convert Arabic numerals if necessary (assuming Utils.convertArabicNumeralsToEnglish exists)
        // text = Utils.convertArabicNumeralsToEnglish(text); // Need to make this method accessible or copy

        return text;
    }

    /**
     * Gets the current timestamp in a desired format.
     */
    private String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US); // Consistent format
        return sdf.format(new Date());
    }

    /**
     * Renders the watermark text onto a Bitmap.
     * @return The generated Bitmap, or null if no watermark is needed or text is empty.
     */
    public Bitmap generateWatermarkBitmap() {
        String watermarkText = generateWatermarkText();
        if (watermarkText == null || watermarkText.isEmpty()) {
            return null;
        }

        // Measure text size to determine required bitmap dimensions
        Rect textBounds = new Rect();
        mTextPaint.getTextBounds(watermarkText, 0, watermarkText.length(), textBounds);

        // Calculate required bitmap size (add some padding)
        int requiredWidth = textBounds.width() + 20; // 10 pixels padding on each side
        int requiredHeight = textBounds.height() + 20; // 10 pixels padding on top/bottom

        // Ensure bitmap dimensions are powers of 2 (required by some older GLES implementations)
        // Find the smallest power of 2 greater than or equal to the required size
        mBitmapWidth = nextPowerOf2(requiredWidth);
        mBitmapHeight = nextPowerOf2(requiredHeight);

        // Create a transparent bitmap
        Bitmap bitmap = Bitmap.createBitmap(mBitmapWidth, mBitmapHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.TRANSPARENT); // Make the background transparent

        // Draw the text onto the canvas
        // Position the text with padding
        canvas.drawText(watermarkText, 10, 10 + textBounds.height(), mTextPaint); // Draw at (10, 10 + text height)

        return bitmap;
    }

    /**
     * Calculates the next power of 2 for a given number.
     */
    private int nextPowerOf2(int n) {
        int p = 1;
        while (p < n) {
            p <<= 1;
        }
        return p;
    }

    /**
     * Sets the text size for the watermark.
     * @param size The desired text size in pixels.
     */
    public void setTextSize(float size) {
        mTextPaint.setTextSize(size);
    }

    /**
     * Sets the text color for the watermark.
     * @param color The desired text color (e.g., Color.WHITE).
     */
    public void setTextColor(int color) {
        mTextPaint.setColor(color);
    }

    /**
     * Sets the shadow properties for the watermark text.
     * @param radius The blur radius.
     * @param dx The horizontal offset.
     * @param dy The vertical offset.
     * @param shadowColor The color of the shadow.
     */
    public void setTextShadow(float radius, float dx, float dy, int shadowColor) {
        mTextPaint.setShadowLayer(radius, dx, dy, shadowColor);
    }

    /**
     * Gets the width of the generated watermark bitmap.
     */
    public int getBitmapWidth() {
        return mBitmapWidth;
    }

    /**
     * Gets the height of the generated watermark bitmap.
     */
    public int getBitmapHeight() {
        return mBitmapHeight;
    }
}
