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
 * Material bottom sheet for choosing an asset type to add (image or video).
 * Follows the same design pattern as FlipPickerBottomSheet / CropPickerBottomSheet.
 */
public class AddAssetBottomSheet extends BottomSheetDialogFragment {

    /** Callback when user picks an asset type. */
    public interface Callback {
        /**
         * Called when the user selects an asset type.
         *
         * @param isImage true for image, false for video
         */
        void onAssetTypeSelected(boolean isImage);
    }

    @Nullable
    private Callback callback;

    /**
     * Create a new AddAssetBottomSheet instance.
     *
     * @return a new instance
     */
    public static AddAssetBottomSheet newInstance() {
        return new AddAssetBottomSheet();
    }

    /**
     * Set the callback for asset type selection.
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
        float dp = getResources().getDisplayMetrics().density;
        Typeface materialIcons = ResourcesCompat.getFont(requireContext(), R.font.materialicons);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, (int) (12 * dp), 0, (int) (24 * dp));

        // Title
        TextView title = new TextView(requireContext());
        title.setText(R.string.faditor_add_asset_title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding((int) (20 * dp), (int) (12 * dp),
                (int) (20 * dp), (int) (16 * dp));
        root.addView(title);

        // Image row
        root.addView(createOptionRow(
                getString(R.string.faditor_add_asset_image),
                "image", materialIcons, dp, true));

        // Video row
        root.addView(createOptionRow(
                getString(R.string.faditor_add_asset_video),
                "videocam", materialIcons, dp, false));

        return root;
    }

    /**
     * Creates an option row matching the existing Faditor bottom sheet style.
     *
     * @param label         row label text
     * @param icon          Material Icon ligature name
     * @param materialIcons Material Icons typeface
     * @param dp            density factor
     * @param isImage       true for image option, false for video
     * @return the constructed row View
     */
    private View createOptionRow(String label, String icon,
                                 @Nullable Typeface materialIcons, float dp,
                                 boolean isImage) {
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
        iconView.setTextColor(0xFF888888);
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
        labelView.setTextColor(0xFFCCCCCC);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        labelView.setLayoutParams(labelLp);
        row.addView(labelView);

        // Arrow
        TextView arrow = new TextView(requireContext());
        arrow.setTypeface(materialIcons);
        arrow.setText("chevron_right");
        arrow.setTextSize(18);
        arrow.setTextColor(0xFF666666);
        arrow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(
                (int) (24 * dp), (int) (24 * dp));
        arrow.setLayoutParams(arrowLp);
        row.addView(arrow);

        row.setOnClickListener(v -> {
            if (callback != null) {
                callback.onAssetTypeSelected(isImage);
            }
            dismiss();
        });

        return row;
    }
}
