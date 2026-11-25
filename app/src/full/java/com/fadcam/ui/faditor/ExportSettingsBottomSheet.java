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
import com.fadcam.ui.faditor.model.ExportSettings;
import com.fadcam.ui.faditor.util.FaditorPalette;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Bottom sheet that picks the export resolution and quality before starting an export.
 * The values live on the project, so a re-export keeps the last choice.
 */
public class ExportSettingsBottomSheet extends BottomSheetDialogFragment {

    /** Called when the user confirms the export. */
    public interface Callback {
        void onExportRequested(@NonNull ExportSettings.Resolution resolution,
                               @NonNull ExportSettings.Quality quality);
    }

    private static final ExportSettings.Resolution[] RESOLUTIONS = {
            ExportSettings.Resolution.ORIGINAL,
            ExportSettings.Resolution.FHD_1080P,
            ExportSettings.Resolution.HD_720P,
            ExportSettings.Resolution.SD_480P,
    };

    private static final ExportSettings.Quality[] QUALITIES = {
            ExportSettings.Quality.HIGH,
            ExportSettings.Quality.MEDIUM,
            ExportSettings.Quality.LOW,
    };

    @Nullable
    private Callback callback;

    private ExportSettings.Resolution resolution = ExportSettings.Resolution.ORIGINAL;
    private ExportSettings.Quality quality = ExportSettings.Quality.HIGH;

    private LinearLayout resolutionSection;
    private LinearLayout qualitySection;

    public static ExportSettingsBottomSheet newInstance(
            @NonNull ExportSettings.Resolution resolution,
            @NonNull ExportSettings.Quality quality) {
        ExportSettingsBottomSheet sheet = new ExportSettingsBottomSheet();
        Bundle args = new Bundle();
        args.putString("resolution", resolution.name());
        args.putString("quality", quality.name());
        sheet.setArguments(args);
        return sheet;
    }

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
        Bundle args = getArguments();
        if (args != null) {
            try {
                resolution = ExportSettings.Resolution.valueOf(
                        args.getString("resolution", ExportSettings.Resolution.ORIGINAL.name()));
                quality = ExportSettings.Quality.valueOf(
                        args.getString("quality", ExportSettings.Quality.HIGH.name()));
            } catch (IllegalArgumentException ignored) {
                // Unknown persisted value: keep the defaults.
            }
        }

        float dp = getResources().getDisplayMetrics().density;

        ScrollView scroll = new ScrollView(requireContext());
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, (int) (12 * dp), 0, (int) (24 * dp));
        scroll.addView(root);

        TextView title = new TextView(requireContext());
        title.setText(R.string.faditor_export_settings_title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding((int) (20 * dp), (int) (12 * dp),
                (int) (20 * dp), (int) (8 * dp));
        root.addView(title);

        TextView subtitle = new TextView(requireContext());
        subtitle.setText(R.string.faditor_export_confirm_subtitle);
        subtitle.setTextColor(0xFF999999);
        subtitle.setTextSize(13);
        subtitle.setPadding((int) (20 * dp), 0, (int) (20 * dp), (int) (8 * dp));
        root.addView(subtitle);

        root.addView(sectionLabel(getString(R.string.faditor_export_resolution), dp));
        resolutionSection = new LinearLayout(requireContext());
        resolutionSection.setOrientation(LinearLayout.VERTICAL);
        root.addView(resolutionSection);

        root.addView(sectionLabel(getString(R.string.faditor_export_quality), dp));
        qualitySection = new LinearLayout(requireContext());
        qualitySection.setOrientation(LinearLayout.VERTICAL);
        root.addView(qualitySection);

        renderOptions();

        TextView export = new TextView(requireContext());
        export.setText(R.string.faditor_export_confirm_action);
        export.setTextColor(0xFFFFFFFF);
        export.setTextSize(15);
        export.setTypeface(null, Typeface.BOLD);
        export.setGravity(Gravity.CENTER);
        export.setBackgroundResource(R.drawable.settings_home_row_bg);
        export.setPadding(0, (int) (16 * dp), 0, (int) (16 * dp));
        LinearLayout.LayoutParams exportLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        exportLp.setMargins((int) (20 * dp), (int) (18 * dp), (int) (20 * dp), 0);
        export.setLayoutParams(exportLp);
        export.setOnClickListener(v -> {
            if (callback != null) callback.onExportRequested(resolution, quality);
            dismiss();
        });
        root.addView(export);

        return scroll;
    }

    @NonNull
    private TextView sectionLabel(@NonNull String text, float dp) {
        TextView label = new TextView(requireContext());
        label.setText(text);
        label.setTextColor(FaditorPalette.ACCENT);
        label.setTextSize(13);
        label.setTypeface(null, Typeface.BOLD);
        label.setPadding((int) (20 * dp), (int) (16 * dp), (int) (20 * dp), (int) (6 * dp));
        return label;
    }

    private void renderOptions() {
        Typeface materialIcons = ResourcesCompat.getFont(requireContext(), R.font.materialicons);

        resolutionSection.removeAllViews();
        for (ExportSettings.Resolution option : RESOLUTIONS) {
            resolutionSection.addView(optionRow(
                    resolutionLabel(option), option == resolution, materialIcons,
                    () -> {
                        resolution = option;
                        renderOptions();
                    }));
        }

        qualitySection.removeAllViews();
        for (ExportSettings.Quality option : QUALITIES) {
            qualitySection.addView(optionRow(
                    qualityLabel(option), option == quality, materialIcons,
                    () -> {
                        quality = option;
                        renderOptions();
                    }));
        }
    }

    @NonNull
    private View optionRow(@NonNull String label, boolean selected,
                           @Nullable Typeface materialIcons, @NonNull Runnable onSelected) {
        float dp = getResources().getDisplayMetrics().density;

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.settings_home_row_bg);
        int hPad = (int) (20 * dp);
        int vPad = (int) (14 * dp);
        row.setPadding(hPad, vPad, hPad, vPad);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins((int) (12 * dp), (int) (2 * dp), (int) (12 * dp), (int) (2 * dp));
        row.setLayoutParams(lp);

        TextView text = new TextView(requireContext());
        text.setText(label);
        text.setTextSize(15);
        text.setTextColor(selected ? FaditorPalette.ACCENT : 0xFFCCCCCC);
        text.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        text.setLayoutParams(textLp);
        row.addView(text);

        if (selected && materialIcons != null) {
            TextView check = new TextView(requireContext());
            check.setTypeface(materialIcons);
            check.setText("check");
            check.setTextSize(20);
            check.setTextColor(FaditorPalette.ACCENT);
            check.setGravity(Gravity.CENTER);
            check.setLayoutParams(new LinearLayout.LayoutParams(
                    (int) (24 * dp), (int) (24 * dp)));
            row.addView(check);
        }

        row.setOnClickListener(v -> onSelected.run());
        return row;
    }

    @NonNull
    private String resolutionLabel(@NonNull ExportSettings.Resolution option) {
        switch (option) {
            case FHD_1080P: return getString(R.string.faditor_export_resolution_1080p);
            case HD_720P: return getString(R.string.faditor_export_resolution_720p);
            case SD_480P: return getString(R.string.faditor_export_resolution_480p);
            case ORIGINAL:
            default: return getString(R.string.faditor_export_resolution_original);
        }
    }

    @NonNull
    private String qualityLabel(@NonNull ExportSettings.Quality option) {
        switch (option) {
            case MEDIUM: return getString(R.string.faditor_export_quality_medium);
            case LOW: return getString(R.string.faditor_export_quality_low);
            case HIGH:
            default: return getString(R.string.faditor_export_quality_high);
        }
    }
}
