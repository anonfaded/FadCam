package com.fadcam.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import com.github.appintro.indicator.IndicatorController;

/**
 * AppIntro's default indicator is a row of circles. The onboarding flow uses a dossier sheet,
 * so the dots become small squircles sharing the same visual language as the nav arrows.
 */
public class SquircleIndicatorController implements IndicatorController {

    private Context context;
    private LinearLayout container;
    private int selectedColor = 0xFFFFFFFF;
    private int unselectedColor = 0xFF808080;
    private int position = 0;
    private int dotSize = -1;
    private int dotMargin = -1;

    public void setDotSize(int px) {
        dotSize = px;
    }

    public void setDotMargin(int px) {
        dotMargin = px;
    }

    @Override
    public int getSelectedIndicatorColor() {
        return selectedColor;
    }

    @Override
    public void setSelectedIndicatorColor(int color) {
        selectedColor = color;
    }

    @Override
    public int getUnselectedIndicatorColor() {
        return unselectedColor;
    }

    @Override
    public void setUnselectedIndicatorColor(int color) {
        unselectedColor = color;
    }

    @Override
    public View newInstance(Context context) {
        this.context = context;
        container = new LinearLayout(context);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER);
        return container;
    }

    @Override
    public void initialize(int slideCount) {
        if (container == null) return;
        container.removeAllViews();
        for (int i = 0; i < slideCount; i++) {
            View dot = new View(context);
            int size = dotSize > 0 ? dotSize : dp(8);
            int margin = dotMargin > 0 ? dotMargin : dp(4);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMargins(margin, 0, margin, 0);
            dot.setLayoutParams(params);
            container.addView(dot);
        }
        selectPosition(position);
    }

    @Override
    public void selectPosition(int position) {
        this.position = position;
        if (container == null) return;
        for (int i = 0; i < container.getChildCount(); i++) {
            container.getChildAt(i).setBackground(
                    squircle(i == position ? selectedColor : unselectedColor));
        }
    }

    private GradientDrawable squircle(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(dp(2.5f));
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
