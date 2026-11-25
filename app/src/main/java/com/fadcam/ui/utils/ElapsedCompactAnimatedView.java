package com.fadcam.ui.utils;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fadcam.R;

import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compact elapsed timer that animates numeric values only and keeps unit labels static.
 */
public class ElapsedCompactAnimatedView extends LinearLayout {

    // \p{Nd} keeps Arabic-Indic and Persian digits together with their unit, where a
    // plain \d would skip them and leave the elapsed timer empty in Arabic.
    private static final Pattern TOKEN_PATTERN = Pattern.compile("(\\p{Nd}+)(\\p{L}+)");
    private static final String[] UNIT_ORDER = new String[] {"d", "h", "m", "s"};
    private static final int[] UNIT_DIGIT_COUNTS = new int[] {3, 2, 2, 2};

    private final String[] unitLabels = new String[UNIT_ORDER.length];
    private final LinearLayout[] slotContainers = new LinearLayout[UNIT_ORDER.length];
    private final AnimatedDigitStripView[] valueViews = new AnimatedDigitStripView[UNIT_ORDER.length];
    private final TextView[] unitViews = new TextView[UNIT_ORDER.length];
    private String displayText = "";

    public ElapsedCompactAnimatedView(Context context) {
        super(context);
        init(context);
    }

    public ElapsedCompactAnimatedView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ElapsedCompactAnimatedView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        setBaselineAligned(false);
        // "1d 2h 3m" is a duration, not a sentence: it must keep its slot order in RTL
        // layouts. Without this the RTL layout direction reversed the slots and rendered
        // the elapsed timer as "s3m2h1d".
        setLayoutDirection(LAYOUT_DIRECTION_LTR);

        // Localized short unit letters ("d/h/m/s", Arabic "ي/س/د/ث"). Parsing maps the
        // rendered label back to its slot, so the timer keeps working in any language.
        unitLabels[0] = context.getString(R.string.duration_unit_days);
        unitLabels[1] = context.getString(R.string.duration_unit_hours);
        unitLabels[2] = context.getString(R.string.duration_unit_minutes);
        unitLabels[3] = context.getString(R.string.duration_unit_seconds);

        for (int index = 0; index < UNIT_ORDER.length; index++) {
            LinearLayout slot = new LinearLayout(context);
            slot.setOrientation(HORIZONTAL);
            slot.setGravity(Gravity.CENTER_VERTICAL);
            slot.setBaselineAligned(false);
            LayoutParams slotParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            slotParams.rightMargin = dpToPx(4);
            slot.setLayoutParams(slotParams);

            AnimatedDigitStripView valueView = new AnimatedDigitStripView(context, UNIT_DIGIT_COUNTS[index]);
            valueView.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

            TextView unitView = new TextView(context);
            LayoutParams unitParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            unitParams.leftMargin = dpToPx(1);
            unitView.setLayoutParams(unitParams);
            unitView.setIncludeFontPadding(false);
            unitView.setMaxLines(1);
            unitView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            unitView.setText(unitLabels[index]);

            slot.addView(valueView);
            slot.addView(unitView);
            slot.setVisibility(GONE);

            slotContainers[index] = slot;
            valueViews[index] = valueView;
            unitViews[index] = unitView;
            addView(slot);
        }
    }

    public void setElapsedText(@Nullable String text, boolean animate) {
        String normalized = text == null ? "" : text.trim();
        if (TextUtils.equals(displayText, normalized)) {
            return;
        }

        Map<String, String> valuesByUnit = parseElapsedText(normalized);
        for (int index = 0; index < UNIT_ORDER.length; index++) {
            String unit = UNIT_ORDER[index];
            String value = resolveUnitValue(valuesByUnit, unit);
            LinearLayout slot = slotContainers[index];
            AnimatedDigitStripView valueView = valueViews[index];
            TextView unitView = unitViews[index];

            if (!shouldShowUnit(valuesByUnit, unit)) {
                valueView.setDigits("", false);
                slot.setVisibility(GONE);
                continue;
            }

            String oldValue = valueView.getDigits() != null ? valueView.getDigits().toString() : "";
            String oldUnit = unitView.getText() != null ? unitView.getText().toString() : "";
            slot.setVisibility(VISIBLE);
            unitView.setText(unitLabels[index]);

            boolean canAnimate = animate
                    && unit.equals(oldUnit)
                    && !oldValue.isEmpty();
            valueView.setDigits(value, canAnimate);
        }

        displayText = normalized;
        updateSlotMargins();
    }

    @Nullable
    public CharSequence getDisplayText() {
        return displayText;
    }

    public void setSegmentTextSizeSp(float textSizeSp) {
        for (int index = 0; index < UNIT_ORDER.length; index++) {
            valueViews[index].setDigitTextSizeSp(textSizeSp);
            unitViews[index].setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp);
        }
    }

    public void setSegmentTypeface(@Nullable Typeface typeface, int style) {
        for (int index = 0; index < UNIT_ORDER.length; index++) {
            valueViews[index].setDigitTypeface(typeface, style);
            unitViews[index].setTypeface(typeface, style);
        }
    }

    public void setSegmentLetterSpacing(float letterSpacing) {
        for (int index = 0; index < UNIT_ORDER.length; index++) {
            valueViews[index].setDigitLetterSpacing(letterSpacing);
            unitViews[index].setLetterSpacing(letterSpacing);
        }
    }

    public void setSegmentFontFeatureSettings(@Nullable String fontFeatureSettings) {
        for (int index = 0; index < UNIT_ORDER.length; index++) {
            valueViews[index].setDigitFontFeatureSettings(fontFeatureSettings);
            unitViews[index].setFontFeatureSettings(fontFeatureSettings);
        }
    }

    public void setSegmentTextColor(int color) {
        for (int index = 0; index < UNIT_ORDER.length; index++) {
            valueViews[index].setDigitTextColor(color);
            unitViews[index].setTextColor(color);
        }
    }

    public void setContentGravity(int gravity) {
        setGravity(gravity | Gravity.CENTER_VERTICAL);
    }

    private Map<String, String> parseElapsedText(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String canonical = canonicalUnitFor(matcher.group(2));
            if (canonical != null) {
                values.put(canonical, matcher.group(1));
            }
        }
        return values;
    }

    /** Maps a rendered unit label (any language) back to its canonical slot key. */
    private String canonicalUnitFor(String label) {
        for (int index = 0; index < unitLabels.length; index++) {
            if (unitLabels[index] != null && unitLabels[index].equalsIgnoreCase(label)) {
                return UNIT_ORDER[index];
            }
        }
        // Fallback for text produced before localization was applied.
        String lower = label == null ? "" : label.toLowerCase(java.util.Locale.ROOT);
        for (String canonical : UNIT_ORDER) {
            if (canonical.equals(lower)) {
                return canonical;
            }
        }
        return null;
    }

    @Nullable
    private String resolveUnitValue(Map<String, String> valuesByUnit, String unit) {
        if (valuesByUnit.containsKey(unit)) {
            return valuesByUnit.get(unit);
        }
        if ("m".equals(unit) || "s".equals(unit)) {
            return "0";
        }
        if ("h".equals(unit) && valuesByUnit.containsKey("d")) {
            return "0";
        }
        return null;
    }

    private boolean shouldShowUnit(Map<String, String> valuesByUnit, String unit) {
        if ("s".equals(unit) || "m".equals(unit)) {
            return true;
        }
        if ("h".equals(unit)) {
            return valuesByUnit.containsKey("h") || valuesByUnit.containsKey("d");
        }
        return valuesByUnit.containsKey(unit);
    }

    private void updateSlotMargins() {
        int lastVisibleIndex = -1;
        for (int index = 0; index < slotContainers.length; index++) {
            if (slotContainers[index].getVisibility() == VISIBLE) {
                lastVisibleIndex = index;
            }
        }

        for (int index = 0; index < slotContainers.length; index++) {
            LayoutParams params = (LayoutParams) slotContainers[index].getLayoutParams();
            params.rightMargin = slotContainers[index].getVisibility() == View.VISIBLE && index != lastVisibleIndex
                    ? dpToPx(4)
                    : 0;
            slotContainers[index].setLayoutParams(params);
        }
    }

    private int dpToPx(int valueDp) {
        return Math.round(valueDp * getResources().getDisplayMetrics().density);
    }
}