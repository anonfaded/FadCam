package com.fadcam.ui;

import android.os.Bundle;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * PlayerInfoBottomSheetFragment
 * Bottom sheet displaying helpful information about the video player
 * and fragmented MP4 format behavior.
 */
public class PlayerInfoBottomSheetFragment extends BottomSheetDialogFragment {

    public static PlayerInfoBottomSheetFragment newInstance() {
        return new PlayerInfoBottomSheetFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Reuse unified picker layout for consistent styling
        return inflater.inflate(R.layout.picker_bottom_sheet, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 1. Set title text
        TextView title = view.findViewById(R.id.picker_title);
        if (title != null) {
            title.setText(R.string.player_info_title);
        }

        // 2. Apply transparent background (gradient comes from theme)
        View root = view.findViewById(R.id.picker_root);
        if (root != null) {
            root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        }

        // 3. Inject info content into existing card container
        ViewGroup listContainer = view.findViewById(R.id.picker_list_container);
        if (listContainer != null) {
            listContainer.removeAllViews();

            // Create content text view
            TextView contentView = new TextView(requireContext());
            contentView.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            int padding = dp(12);
            contentView.setPadding(padding, dp(4), padding, dp(4));
            contentView.setTextColor(android.graphics.Color.WHITE);
            contentView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
            contentView.setLineSpacing(dp(2), 1.0f);

            // Set HTML content
            String htmlContent = getString(R.string.player_info_content);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                contentView.setText(Html.fromHtml(htmlContent, Html.FROM_HTML_MODE_LEGACY));
            } else {
                contentView.setText(Html.fromHtml(htmlContent));
            }

            listContainer.addView(contentView);
        }

        // 4. Hide helper if present
        View helper = view.findViewById(R.id.picker_helper);
        if (helper != null) {
            helper.setVisibility(View.GONE);
        }

        // 5. Handle close button
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
            View bottomSheet = ((com.google.android.material.bottomsheet.BottomSheetDialog) dialog)
                    .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackgroundResource(R.drawable.picker_bottom_sheet_gradient_bg_dynamic);
            }
        });
        return dialog;
    }
}
