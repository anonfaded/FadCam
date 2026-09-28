package com.fadcam.ui.faditor;

import android.app.Dialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Bottom sheet that explains Faditor Mini's features and behaviour.
 *
 * <p>Sections cover: how it works, smart fMP4→MP4 conversion, export naming,
 * temporary files, and recent projects management. Uses the same dark gradient
 * styling as other FadCam bottom sheets.</p>
 */
public class FaditorInfoBottomSheet extends BottomSheetDialogFragment {

    private static final String TAG = "FaditorInfoBottomSheet";

    /** Factory method. */
    @NonNull
    public static FaditorInfoBottomSheet newInstance() {
        return new FaditorInfoBottomSheet();
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
        float dp = getResources().getDisplayMetrics().density;
        Typeface materialIcons = ResourcesCompat.getFont(requireContext(), R.font.materialicons);

        // Root layout
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, (int) (12 * dp), 0, (int) (32 * dp));

        // ── Header row (title + close button) ───────────────────
        LinearLayout headerRow = new LinearLayout(requireContext());
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding((int) (20 * dp), (int) (12 * dp),
                (int) (12 * dp), (int) (4 * dp));
        root.addView(headerRow);

        TextView title = new TextView(requireContext());
        title.setText(R.string.faditor_info_title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        headerRow.addView(title);

        // Close button
        TextView closeBtn = new TextView(requireContext());
        closeBtn.setTypeface(materialIcons);
        closeBtn.setText("close");
        closeBtn.setTextColor(0xFF999999);
        closeBtn.setTextSize(22);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setPadding((int) (8 * dp), (int) (8 * dp),
                (int) (8 * dp), (int) (8 * dp));
        closeBtn.setOnClickListener(v -> dismiss());
        headerRow.addView(closeBtn);

        // Subtitle
        TextView subtitle = new TextView(requireContext());
        subtitle.setText(R.string.faditor_info_subtitle);
        subtitle.setTextColor(0xFF888888);
        subtitle.setTextSize(13);
        subtitle.setPadding((int) (20 * dp), 0, (int) (20 * dp), (int) (16 * dp));
        root.addView(subtitle);

        // ── Scrollable content ──────────────────────────────────
        ScrollView scrollView = new ScrollView(requireContext());
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding((int) (20 * dp), 0, (int) (20 * dp), 0);
        scrollView.addView(content);
        root.addView(scrollView);

        // ── Info sections ───────────────────────────────────────
        addInfoSection(content, materialIcons, dp,
                "movie_edit", 0xFF4CAF50,
                R.string.faditor_info_how_title,
                R.string.faditor_info_how_desc);

        addInfoSection(content, materialIcons, dp,
                "sync", 0xFF42A5F5,
                R.string.faditor_info_convert_title,
                R.string.faditor_info_convert_desc);

        addInfoSection(content, materialIcons, dp,
                "save", 0xFFFF9800,
                R.string.faditor_info_export_title,
                R.string.faditor_info_export_desc);

        addInfoSection(content, materialIcons, dp,
                "cached", 0xFF9E9E9E,
                R.string.faditor_info_temp_title,
                R.string.faditor_info_temp_desc);

        addInfoSection(content, materialIcons, dp,
                "history", 0xFFAB47BC,
                R.string.faditor_info_projects_title,
                R.string.faditor_info_projects_desc);

        return root;
    }

    // ── Helper: add an info section ──────────────────────────────────

    /**
     * Adds a themed info section with an icon, title, and description.
     */
    private void addInfoSection(@NonNull LinearLayout parent,
                                @Nullable Typeface iconFont,
                                float dp,
                                @NonNull String iconLigature,
                                int iconColor,
                                int titleRes,
                                int descRes) {
        // Section container with card background
        LinearLayout section = new LinearLayout(requireContext());
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackgroundResource(R.drawable.settings_group_card_bg);
        int pad = (int) (14 * dp);
        section.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        sectionLp.bottomMargin = (int) (10 * dp);
        section.setLayoutParams(sectionLp);

        // Icon + title row
        LinearLayout titleRow = new LinearLayout(requireContext());
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView icon = new TextView(requireContext());
        icon.setTypeface(iconFont);
        icon.setText(iconLigature);
        icon.setTextColor(iconColor);
        icon.setTextSize(20);
        icon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                (int) (28 * dp), (int) (28 * dp));
        iconLp.setMarginEnd((int) (10 * dp));
        icon.setLayoutParams(iconLp);
        titleRow.addView(icon);

        TextView titleTv = new TextView(requireContext());
        titleTv.setText(titleRes);
        titleTv.setTextColor(0xFFFFFFFF);
        titleTv.setTextSize(15);
        titleTv.setTypeface(null, Typeface.BOLD);
        titleRow.addView(titleTv);

        section.addView(titleRow);

        // Description text
        TextView desc = new TextView(requireContext());
        desc.setText(descRes);
        desc.setTextColor(0xFF999999);
        desc.setTextSize(13);
        desc.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = (int) (8 * dp);
        desc.setLayoutParams(descLp);
        section.addView(desc);

        parent.addView(section);
    }
}
