package com.fadcam.ui;

import android.graphics.Color;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Picker-styled info sheet for Motion Lab, matching in-app player/settings sheet visuals.
 */
public class MotionLabInfoBottomSheetFragment extends BottomSheetDialogFragment {

    public static MotionLabInfoBottomSheetFragment newInstance() {
        return new MotionLabInfoBottomSheetFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.picker_bottom_sheet, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        TextView title = view.findViewById(R.id.picker_title);
        if (title != null) {
            title.setText(R.string.motion_lab_info_title);
        }

        View root = view.findViewById(R.id.picker_root);
        if (root != null) {
            root.setBackgroundColor(Color.TRANSPARENT);
        }

        ViewGroup listContainer = view.findViewById(R.id.picker_list_container);
        if (listContainer != null) {
            listContainer.removeAllViews();
            TextView body = new TextView(requireContext());
            body.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            int hPad = dp(14);
            body.setPadding(hPad, dp(6), hPad, dp(8));
            body.setTextColor(Color.WHITE);
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            body.setLineSpacing(dp(3), 1.0f);
            body.setText(getString(R.string.motion_lab_info_body));
            body.setMovementMethod(LinkMovementMethod.getInstance());
            listContainer.addView(body);
        }

        View helper = view.findViewById(R.id.picker_helper);
        if (helper != null) {
            helper.setVisibility(View.GONE);
        }

        ImageView closeButton = view.findViewById(R.id.picker_close_btn);
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> dismiss());
        }
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
    }

    @Override
    public int getTheme() {
        return R.style.CustomBottomSheetDialogTheme;
    }

    @Override
    public android.app.Dialog onCreateDialog(Bundle savedInstanceState) {
        android.app.Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((BottomSheetDialog) dialog)
                .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackgroundResource(R.drawable.picker_bottom_sheet_gradient_bg_dynamic);
            }
        });
        return dialog;
    }
}
