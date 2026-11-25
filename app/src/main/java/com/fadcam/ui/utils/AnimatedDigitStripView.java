package com.fadcam.ui.utils;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

/**
 * Fixed-width strip of animated digit cells. Each digit lives in a permanent slot.
 */
public class AnimatedDigitStripView extends LinearLayout {

    private final AnimatedTextView[] digitViews;
    private String currentValue = "";
    private float digitTextSizeSp = 16f;
    @Nullable
    private Typeface digitTypeface;
    private float digitLetterSpacing = 0f;
    @Nullable
    private String digitFontFeatureSettings = "tnum";
    private int digitTextColor = 0;
    private boolean digitTextColorSet = false;

    public AnimatedDigitStripView(Context context) {
        this(context, null, 2);
    }

    public AnimatedDigitStripView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 2);
    }

    public AnimatedDigitStripView(Context context, int digitCount) {
        this(context, null, digitCount);
    }

    public AnimatedDigitStripView(Context context, @Nullable AttributeSet attrs, int digitCount) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBaselineAligned(false);
        // Digits within a value are always ordered left-to-right, in every language.
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
        digitViews = new AnimatedTextView[digitCount];
        init(context);
    }

    private void init(Context context) {
        for (int index = 0; index < digitViews.length; index++) {
            AnimatedTextView digitView = new AnimatedTextView(context);
            LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            digitView.setLayoutParams(params);
            digitView.setIncludeFontPadding(false);
            digitView.setMaxLines(1);
            digitView.setGravity(Gravity.CENTER);
            digitView.setText("0");
            digitViews[index] = digitView;
            addView(digitView);
        }
        applyVisualStyle();
        updateDigitCellWidths();
        setDigits("", false);
    }

    public void setDigits(@Nullable String value, boolean animate) {
        String normalized = value == null ? "" : value.trim();
        if (TextUtils.equals(currentValue, normalized)) {
            return;
        }

        String padded = leftPad(normalized, digitViews.length);
        String previous = leftPad(currentValue, digitViews.length);
        for (int index = 0; index < digitViews.length; index++) {
            AnimatedTextView digitView = digitViews[index];
            char nextChar = padded.charAt(index);
            char oldChar = previous.charAt(index);
            boolean leadingBlank = nextChar == ' ';
            if (leadingBlank) {
                digitView.cancelAnimation();
                digitView.setText("");
                digitView.setVisibility(GONE);
                // Set width to 0 for leading blanks so they don't contribute to layout
                LayoutParams params = (LayoutParams) digitView.getLayoutParams();
                params.width = 0;
                digitView.setLayoutParams(params);
                continue;
            }

            digitView.setVisibility(VISIBLE);
            // Restore fixed width for visible digits
            LayoutParams params = (LayoutParams) digitView.getLayoutParams();
            Paint paint = new Paint(digitView.getPaint());
            float widestDigit = widestDigitWidth(paint);
            int widthPx = (int) Math.ceil(widestDigit + dpToPx(1));
            params.width = widthPx;
            digitView.setLayoutParams(params);
            
            String nextText = String.valueOf(nextChar);
            String oldText = oldChar == ' ' ? "" : String.valueOf(oldChar);
            if (animate && !oldText.isEmpty()) {
                digitView.animateSlot(nextText, 400L);
            } else {
                digitView.cancelAnimation();
                digitView.setText(nextText);
            }
        }
        currentValue = normalized;
    }

    @Nullable
    public CharSequence getDigits() {
        return currentValue;
    }

    public void setDigitTextSizeSp(float textSizeSp) {
        digitTextSizeSp = textSizeSp;
        applyVisualStyle();
        updateDigitCellWidths();
    }

    public void setDigitTypeface(@Nullable Typeface typeface, int style) {
        digitTypeface = Typeface.create(typeface, style);
        applyVisualStyle();
        updateDigitCellWidths();
    }

    public void setDigitLetterSpacing(float letterSpacing) {
        digitLetterSpacing = letterSpacing;
        applyVisualStyle();
        updateDigitCellWidths();
    }

    public void setDigitFontFeatureSettings(@Nullable String fontFeatureSettings) {
        digitFontFeatureSettings = fontFeatureSettings;
        applyVisualStyle();
        updateDigitCellWidths();
    }

    public void setDigitTextColor(int color) {
        digitTextColor = color;
        digitTextColorSet = true;
        for (AnimatedTextView digitView : digitViews) {
            digitView.setTextColor(color);
        }
    }

    private void applyVisualStyle() {
        for (AnimatedTextView digitView : digitViews) {
            digitView.setTextSize(TypedValue.COMPLEX_UNIT_SP, digitTextSizeSp);
            digitView.setTypeface(digitTypeface, Typeface.BOLD);
            digitView.setLetterSpacing(digitLetterSpacing);
            digitView.setFontFeatureSettings(digitFontFeatureSettings);
            if (digitTextColorSet) {
                digitView.setTextColor(digitTextColor);
            }
        }
    }

    private void updateDigitCellWidths() {
        if (digitViews.length == 0) {
            return;
        }

        Paint paint = new Paint(digitViews[0].getPaint());
        float widestDigit = widestDigitWidth(paint);
        int widthPx = (int) Math.ceil(widestDigit + dpToPx(1));
        for (AnimatedTextView digitView : digitViews) {
            LayoutParams params = (LayoutParams) digitView.getLayoutParams();
            params.width = widthPx;
            digitView.setLayoutParams(params);
        }
    }

    /**
     * Widest glyph among the digits this view can be asked to show. Arabic-Indic and
     * Persian digits are not the same width as ASCII ones, so measure both the ASCII
     * range and the zero digit of the current locale.
     */
    private float widestDigitWidth(Paint paint) {
        float widest = 0f;
        for (char digit = '0'; digit <= '9'; digit++) {
            widest = Math.max(widest, paint.measureText(String.valueOf(digit)));
        }
        char zero;
        try {
            zero = java.text.DecimalFormatSymbols
                    .getInstance(com.fadcam.utils.DisplayLocale.get(getContext())).getZeroDigit();
        } catch (Exception ignored) {
            zero = '0';
        }
        if (zero != '0') {
            for (int offset = 0; offset < 10; offset++) {
                widest = Math.max(widest, paint.measureText(String.valueOf((char) (zero + offset))));
            }
        }
        return widest;
    }

    private String leftPad(String input, int width) {
        if (input == null) {
            input = "";
        }
        if (input.length() >= width) {
            return input.substring(input.length() - width);
        }
        StringBuilder padded = new StringBuilder(width);
        for (int index = input.length(); index < width; index++) {
            padded.append(' ');
        }
        padded.append(input);
        return padded.toString();
    }

    private int dpToPx(int valueDp) {
        return Math.round(valueDp * getResources().getDisplayMetrics().density);
    }
}