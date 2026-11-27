package com.fadcam.ui;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Explains that the Watermark screen holds two independent settings sets.
 *
 * <p>Camera recording and screen recording are different things, so each keeps its own
 * watermark style and its own enabled lines. This sheet states that plainly, because the tab
 * strip alone does not tell the user that changing one mode leaves the other untouched.</p>
 */
public class WatermarkInfoBottomSheet extends BottomSheetDialogFragment {

    public static WatermarkInfoBottomSheet newInstance() {
        return new WatermarkInfoBottomSheet();
    }

    @Override
    public android.app.Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        android.app.Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((com.google.android.material.bottomsheet.BottomSheetDialog) dialog)
                    .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackground(new ColorDrawable(Color.TRANSPARENT));
            }
        });
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottomsheet_watermark_info, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Match the other sheets: the gradient is themed, so it follows the app theme.
        View root = view.findViewById(R.id.picker_root);
        if (root != null) {
            root.setBackgroundResource(R.drawable.picker_bottom_sheet_gradient_bg_dynamic);
        }

        View closeBtn = view.findViewById(R.id.picker_close_btn);
        if (closeBtn != null) {
            closeBtn.setOnClickListener(v -> dismiss());
        }
    }
}
