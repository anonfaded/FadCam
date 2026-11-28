package com.fadcam.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.fadcam.R;

/**
 * Builds the arrow-based row used by every bottom sheet in the app.
 *
 * <p>Extracted from {@link InputActionBottomSheetFragment}, which had the only implementation,
 * so the download manager's rows come from the same code rather than a lookalike. One source of
 * truth for row shape, icon tint, text sizes and the destructive treatment.
 */
public final class SheetRowBuilder {

    private SheetRowBuilder() {
    }

    /**
     * One sheet row: leading icon, title, optional subtitle, trailing arrow.
     *
     * @param destructive adds the subtle red wash used for delete-style actions
     */
    @NonNull
    public static LinearLayout build(@NonNull Context context, @DrawableRes int iconRes,
                                     @NonNull String title, @Nullable String subtitle,
                                     boolean destructive, @NonNull View.OnClickListener click) {
        return build(context, iconRes, title, subtitle, destructive, click, false);
    }

    /**
     * @param rowIsCard true when the row sits directly on the sheet surface and must carry its
     *                  own card; false when a container already provides the card
     */
    @NonNull
    public static LinearLayout build(@NonNull Context context, @DrawableRes int iconRes,
                                     @NonNull String title, @Nullable String subtitle,
                                     boolean destructive, @NonNull View.OnClickListener click,
                                     boolean rowIsCard) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // Either the row is the card (sheets that place rows straight on the surface) or the
        // container is (settings-style groups). Getting this backwards is what makes rows look
        // like separate rounded boxes instead of one list.
        row.setBackgroundResource(rowIsCard
                ? R.drawable.settings_home_row_bg
                : R.drawable.asset_action_row_bg);
        int padH = dp(context, 16);
        int padV = dp(context, 8);
        row.setPadding(padH, padV, padH, padV);
        row.setOnClickListener(click);

        ImageView icon = new ImageView(context);
        LinearLayout.LayoutParams iconLp =
                new LinearLayout.LayoutParams(dp(context, 24), dp(context, 24));
        iconLp.setMarginEnd(dp(context, 16));
        icon.setLayoutParams(iconLp);
        icon.setImageResource(iconRes);
        // Neutral grey, matching every other row. Accent is reserved for state, not decoration.
        icon.setImageTintList(ColorStateList.valueOf(
                ContextCompat.getColor(context, android.R.color.darker_gray)));
        row.addView(icon);

        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView primary = new TextView(context);
        primary.setText(title);
        primary.setTypeface(primary.getTypeface(), Typeface.BOLD);
        TypedValue heading = new TypedValue();
        if (context.getTheme().resolveAttribute(R.attr.colorHeading, heading, true)) {
            primary.setTextColor(heading.data);
        } else {
            primary.setTextColor(ContextCompat.getColor(context, R.color.colorHeading));
        }
        primary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.addView(primary);

        // Only when there is one: an empty TextView still takes a full line, which pushed the
        // title off the row's centre.
        if (subtitle != null && !subtitle.trim().isEmpty()) {
            TextView secondary = new TextView(context);
            secondary.setText(subtitle);
            secondary.setTextColor(ContextCompat.getColor(context,
                    android.R.color.darker_gray));
            secondary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            text.addView(secondary);
        }
        row.addView(text);

        ImageView arrow = new ImageView(context);
        arrow.setLayoutParams(new LinearLayout.LayoutParams(dp(context, 14), dp(context, 14)));
        arrow.setImageResource(R.drawable.ic_arrow_right);
        arrow.setImageTintList(ColorStateList.valueOf(
                ContextCompat.getColor(context, android.R.color.darker_gray)));
        row.addView(arrow);

        if (destructive) {
            try {
                row.getBackground().mutate();
                row.setBackgroundTintList(ColorStateList.valueOf(0x33FF3B30));
            } catch (Exception ignored) {
                // Tinting is cosmetic; a failure here must not stop the row being built.
            }
        }
        return row;
    }

    /**
     * Divider between rows, matching what PickerBottomSheetFragment inserts: a 1dp line with
     * horizontal inset equal to the row padding, and no vertical margin because the rows
     * provide their own spacing through padding.
     */
    @NonNull
    public static View divider(@NonNull Context context) {
        View view = new View(context);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(dp(context, 14), 0, dp(context, 12), 0);
        view.setLayoutParams(lp);
        view.setBackgroundColor(0xFF262626);
        return view;
    }

    private static int dp(@NonNull Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
