package com.fadcam.ui.faditor;

import android.app.Dialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Material bottom sheet for choosing playback speed.
 * Shows a list of speed presets with the current speed checked.
 */
public class SpeedPickerBottomSheet extends BottomSheetDialogFragment {

    /** Callback when user picks a speed value. */
    public interface Callback {
        void onSpeedSelected(float speed);
    }

    private static final float[] SPEED_PRESETS = {
        0.1f, 0.25f, 0.5f, 0.75f,
        1f,
        1.25f, 1.5f, 2f, 3f, 4f, 5f, 8f, 10f
    };

    @Nullable
    private Callback callback;
    private float currentSpeed = 1f;

    /**
     * Create a new SpeedPickerBottomSheet with the given current speed.
     *
     * @param currentSpeed the currently selected speed multiplier
     * @return a new instance
     */
    public static SpeedPickerBottomSheet newInstance(float currentSpeed) {
        SpeedPickerBottomSheet sheet = new SpeedPickerBottomSheet();
        Bundle args = new Bundle();
        args.putFloat("currentSpeed", currentSpeed);
        sheet.setArguments(args);
        return sheet;
    }

    /**
     * Set the callback for speed selection events.
     *
     * @param callback the callback to notify
     */
    public void setCallback(@Nullable Callback callback) {
        this.callback = callback;
    }

    // ── Theme & dark styling ─────────────────────────────────────────

    @Override
    public int getTheme() {
        return R.style.CustomBottomSheetDialogTheme;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((BottomSheetDialog) dialog)
                    .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackgroundResource(R.drawable.picker_bottom_sheet_dark_gradient_bg);
            }
        });
        return dialog;
    }

    // ── View creation ────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        if (getArguments() != null) {
            currentSpeed = getArguments().getFloat("currentSpeed", 1f);
        }

        float dp = getResources().getDisplayMetrics().density;
        Typeface materialIcons = ResourcesCompat.getFont(requireContext(), R.font.materialicons);

        // Root layout (vertical)
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, (int) (12 * dp), 0, 0);

        // ── Title ───────────────────────────────────────────────
        TextView title = new TextView(requireContext());
        title.setText(R.string.faditor_speed_title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding((int) (20 * dp), (int) (12 * dp),
                (int) (20 * dp), (int) (16 * dp));
        root.addView(title);

        // ── Scrollable content ──────────────────────────────────
        android.widget.ScrollView scrollView = new android.widget.ScrollView(requireContext());
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        scrollView.setLayoutParams(scrollLp);

        LinearLayout scrollContent = new LinearLayout(requireContext());
        scrollContent.setOrientation(LinearLayout.VERTICAL);
        scrollContent.setPadding(0, 0, 0, (int) (24 * dp));

        for (float speed : SPEED_PRESETS) {
            View row = createSpeedRow(speed, materialIcons, dp);
            scrollContent.addView(row);
        }

        scrollView.addView(scrollContent);
        root.addView(scrollView);

        return root;
    }

    /**
     * Creates a single speed row with icon, label, and check indicator.
     */
    private View createSpeedRow(float speed, @Nullable Typeface materialIcons, float dp) {
        boolean isSelected = Math.abs(speed - currentSpeed) < 0.001f;
        boolean isNormal = Math.abs(speed - 1f) < 0.001f;

        // Row container
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.settings_home_row_bg);
        int hPad = (int) (20 * dp);
        int vPad = (int) (14 * dp);
        row.setPadding(hPad, vPad, hPad, vPad);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins((int) (12 * dp), (int) (2 * dp), (int) (12 * dp), (int) (2 * dp));
        row.setLayoutParams(rowLp);

        // Speed icon (material icon)
        TextView icon = new TextView(requireContext());
        icon.setTypeface(materialIcons);
        if (isNormal) {
            icon.setText("play_arrow");
        } else if (speed < 1f) {
            icon.setText("slow_motion_video");
        } else {
            icon.setText("fast_forward");
        }
        icon.setTextSize(20);
        icon.setTextColor(isSelected ? 0xFF4CAF50 : 0xFF888888);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                (int) (28 * dp), (int) (28 * dp));
        iconLp.setMarginEnd((int) (16 * dp));
        icon.setLayoutParams(iconLp);
        icon.setGravity(Gravity.CENTER);
        row.addView(icon);

        // Speed label
        TextView label = new TextView(requireContext());
        label.setText(formatSpeed(speed));
        label.setTextSize(15);
        label.setTextColor(isSelected ? 0xFF4CAF50 : 0xFFCCCCCC);
        label.setTypeface(null, isSelected ? Typeface.BOLD : Typeface.NORMAL);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        label.setLayoutParams(labelLp);
        row.addView(label);

        // Subtitle for normal speed
        if (isNormal) {
            label.setText(formatSpeed(speed) + "  ·  Normal");
        }

        // Check icon for selected
        if (isSelected) {
            TextView check = new TextView(requireContext());
            check.setTypeface(materialIcons);
            check.setText("check");
            check.setTextSize(20);
            check.setTextColor(0xFF4CAF50);
            check.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams checkLp = new LinearLayout.LayoutParams(
                    (int) (24 * dp), (int) (24 * dp));
            check.setLayoutParams(checkLp);
            row.addView(check);
        }

        // Click handler
        row.setOnClickListener(v -> {
            if (callback != null) {
                callback.onSpeedSelected(speed);
            }
            dismiss();
        });

        return row;
    }

    /**
     * Formats a speed value for display (e.g. "2x", "0.5x").
     *
     * @param speed the speed multiplier
     * @return formatted string
     */
    private String formatSpeed(float speed) {
        if (speed == (int) speed) {
            return (int) speed + "x";
        }
        return String.format(java.util.Locale.US, "%.2gx", speed);
    }
}
