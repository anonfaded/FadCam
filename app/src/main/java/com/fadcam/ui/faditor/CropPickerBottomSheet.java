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
 * Material bottom sheet for choosing crop aspect ratio.
 */
public class CropPickerBottomSheet extends BottomSheetDialogFragment {

    /** Callback when user picks a crop preset. */
    public interface Callback {
        void onCropSelected(@NonNull String preset);
    }

    private static final String[][] CROP_OPTIONS = {
        {"none",    "Original",        "crop_free"},
        {"custom",  "Free Crop",       "crop"},
    };

    @Nullable
    private Callback callback;
    @NonNull
    private String currentPreset = "none";

    /**
     * Create a new CropPickerBottomSheet with the given current crop preset.
     *
     * @param currentPreset the currently selected crop preset
     * @return a new instance
     */
    public static CropPickerBottomSheet newInstance(@NonNull String currentPreset) {
        CropPickerBottomSheet sheet = new CropPickerBottomSheet();
        Bundle args = new Bundle();
        args.putString("currentPreset", currentPreset);
        sheet.setArguments(args);
        return sheet;
    }

    /**
     * Set the callback for crop selection events.
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
            currentPreset = getArguments().getString("currentPreset", "none");
        }

        float dp = getResources().getDisplayMetrics().density;
        Typeface materialIcons = ResourcesCompat.getFont(requireContext(), R.font.materialicons);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, (int) (12 * dp), 0, (int) (24 * dp));

        // Title
        TextView title = new TextView(requireContext());
        title.setText(R.string.faditor_crop_title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding((int) (20 * dp), (int) (12 * dp),
                (int) (20 * dp), (int) (16 * dp));
        root.addView(title);

        for (String[] option : CROP_OPTIONS) {
            String key = option[0];
            String label = option[1];
            String icon = option[2];
            boolean selected = key.equals(currentPreset);

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
            iconView.setTextColor(selected ? 0xFF4CAF50 : 0xFF888888);
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
            labelView.setTextColor(selected ? 0xFF4CAF50 : 0xFFCCCCCC);
            labelView.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            labelView.setLayoutParams(labelLp);
            row.addView(labelView);

            // Check
            if (selected) {
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
                if (callback != null) {
                    callback.onCropSelected(key);
                }
                dismiss();
            });

            root.addView(row);
        }

        return root;
    }
}
