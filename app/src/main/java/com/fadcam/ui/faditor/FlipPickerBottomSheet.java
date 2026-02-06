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
 * Material bottom sheet for flip (horizontal/vertical) selection.
 */
public class FlipPickerBottomSheet extends BottomSheetDialogFragment {

    /** Callback when user changes flip state. */
    public interface Callback {
        void onFlipChanged(boolean flipH, boolean flipV);
    }

    @Nullable
    private Callback callback;
    private boolean flipH = false;
    private boolean flipV = false;

    /**
     * Create a new FlipPickerBottomSheet with current flip state.
     *
     * @param flipH whether horizontal flip is active
     * @param flipV whether vertical flip is active
     * @return a new instance
     */
    public static FlipPickerBottomSheet newInstance(boolean flipH, boolean flipV) {
        FlipPickerBottomSheet sheet = new FlipPickerBottomSheet();
        Bundle args = new Bundle();
        args.putBoolean("flipH", flipH);
        args.putBoolean("flipV", flipV);
        sheet.setArguments(args);
        return sheet;
    }

    /**
     * Set the callback for flip changes.
     *
     * @param callback the callback to notify
     */
    public void setCallback(@Nullable Callback callback) {
        this.callback = callback;
    }

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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        if (getArguments() != null) {
            flipH = getArguments().getBoolean("flipH", false);
            flipV = getArguments().getBoolean("flipV", false);
        }

        float dp = getResources().getDisplayMetrics().density;
        Typeface materialIcons = ResourcesCompat.getFont(requireContext(), R.font.materialicons);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, (int) (12 * dp), 0, (int) (24 * dp));

        // Title
        TextView title = new TextView(requireContext());
        title.setText(R.string.faditor_flip_title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding((int) (20 * dp), (int) (12 * dp),
                (int) (20 * dp), (int) (16 * dp));
        root.addView(title);

        // Horizontal flip row
        root.addView(createFlipRow("Flip Horizontal", "flip",
                flipH, materialIcons, dp, true));

        // Vertical flip row
        root.addView(createFlipRow("Flip Vertical", "flip",
                flipV, materialIcons, dp, false));

        // Reset row
        if (flipH || flipV) {
            root.addView(createResetRow(materialIcons, dp));
        }

        return root;
    }

    /**
     * Creates a flip option row.
     */
    private View createFlipRow(String label, String icon, boolean isActive,
                               @Nullable Typeface materialIcons, float dp,
                               boolean isHorizontal) {
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

        // Icon
        TextView iconView = new TextView(requireContext());
        iconView.setTypeface(materialIcons);
        iconView.setText(icon);
        iconView.setTextSize(20);
        iconView.setTextColor(isActive ? 0xFF4CAF50 : 0xFF888888);
        // Rotate 90 degrees for vertical flip icon
        if (!isHorizontal) {
            iconView.setRotation(90);
        }
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                (int) (28 * dp), (int) (28 * dp));
        iconLp.setMarginEnd((int) (16 * dp));
        iconView.setLayoutParams(iconLp);
        iconView.setGravity(Gravity.CENTER);
        row.addView(iconView);

        // Label
        TextView labelView = new TextView(requireContext());
        labelView.setText(label);
        labelView.setTextSize(15);
        labelView.setTextColor(isActive ? 0xFF4CAF50 : 0xFFCCCCCC);
        labelView.setTypeface(null, isActive ? Typeface.BOLD : Typeface.NORMAL);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        labelView.setLayoutParams(labelLp);
        row.addView(labelView);

        // Check/status
        if (isActive) {
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

        row.setOnClickListener(v -> {
            if (isHorizontal) {
                flipH = !flipH;
            } else {
                flipV = !flipV;
            }
            if (callback != null) {
                callback.onFlipChanged(flipH, flipV);
            }
            dismiss();
        });

        return row;
    }

    /**
     * Creates a reset row to clear all flips.
     */
    private View createResetRow(@Nullable Typeface materialIcons, float dp) {
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
        rowLp.setMargins((int) (12 * dp), (int) (8 * dp), (int) (12 * dp), (int) (2 * dp));
        row.setLayoutParams(rowLp);

        // Icon
        TextView iconView = new TextView(requireContext());
        iconView.setTypeface(materialIcons);
        iconView.setText("refresh");
        iconView.setTextSize(20);
        iconView.setTextColor(0xFFF44336);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                (int) (28 * dp), (int) (28 * dp));
        iconLp.setMarginEnd((int) (16 * dp));
        iconView.setLayoutParams(iconLp);
        iconView.setGravity(Gravity.CENTER);
        row.addView(iconView);

        // Label
        TextView labelView = new TextView(requireContext());
        labelView.setText("Reset");
        labelView.setTextSize(15);
        labelView.setTextColor(0xFFF44336);
        labelView.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        labelView.setLayoutParams(labelLp);
        row.addView(labelView);

        row.setOnClickListener(v -> {
            flipH = false;
            flipV = false;
            if (callback != null) {
                callback.onFlipChanged(false, false);
            }
            dismiss();
        });

        return row;
    }
}
