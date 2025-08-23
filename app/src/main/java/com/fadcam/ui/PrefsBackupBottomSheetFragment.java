package com.fadcam.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.util.PreferencesBackupUtil;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.json.JSONObject;

/**
 * PrefsBackupBottomSheetFragment
 * Provides Import / Export / Reset (with double confirmation) for preferences.
 */
public class PrefsBackupBottomSheetFragment extends BottomSheetDialogFragment {

    private ActivityResultLauncher<Intent> exportLauncher;
    private ActivityResultLauncher<Intent> importLauncher;
    private ActivityResultLauncher<Intent> previewLauncher;
    private JSONObject lastLoadedJson; // can be from import or preview

    public static PrefsBackupBottomSheetFragment newInstance(){
        return new PrefsBackupBottomSheetFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.picker_bottom_sheet, container, false);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        exportLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if(result.getResultCode() == Activity.RESULT_OK && result.getData()!=null){
                Uri uri = result.getData().getData();
                if(uri!=null){
                    try {
                        JSONObject json = PreferencesBackupUtil.buildBackupJson(requireContext());
                        PreferencesBackupUtil.writeJsonToUri(requireContext(), uri, json);
                        Toast.makeText(requireContext(), getString(R.string.prefs_export_success), Toast.LENGTH_SHORT).show();
                    } catch (Exception e){
                        Toast.makeText(requireContext(), getString(R.string.prefs_export_failed)+": "+e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            }
        });
        importLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if(result.getResultCode() == Activity.RESULT_OK && result.getData()!=null){
                Uri uri = result.getData().getData();
                if(uri!=null){
                    try {
                        JSONObject json = PreferencesBackupUtil.readJsonFromUri(requireContext(), uri);
                        PreferencesBackupUtil.applyFromJson(requireContext(), json);
                        lastLoadedJson = json;
                        Toast.makeText(requireContext(), getString(R.string.prefs_import_success), Toast.LENGTH_SHORT).show();
                        restartAppUI();
                    } catch (Exception e){
                        Toast.makeText(requireContext(), getString(R.string.prefs_import_failed)+": "+e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            }
        });
        previewLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if(result.getResultCode() == Activity.RESULT_OK && result.getData()!=null){
                Uri uri = result.getData().getData();
                if(uri!=null){
                    try {
                        JSONObject json = PreferencesBackupUtil.readJsonFromUri(requireContext(), uri);
                        lastLoadedJson = json;
                        renderPreviewDialog(json);
                    } catch (Exception e){
                        Toast.makeText(requireContext(), getString(R.string.prefs_import_failed)+": "+e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            }
        });
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        TextView title = view.findViewById(R.id.picker_title);
        if(title!=null){ title.setText(R.string.prefs_backup_title); }
        View helper = view.findViewById(R.id.picker_helper);
        if(helper!=null){ helper.setVisibility(View.GONE); }
        ViewGroup listContainer = view.findViewById(R.id.picker_list_container);
        if(listContainer!=null){
            listContainer.removeAllViews();
            // Export
            listContainer.addView(buildActionRow(R.drawable.ic_content_copy, getString(R.string.prefs_export_label), getString(R.string.prefs_export_subtitle), v->startExport()));
            listContainer.addView(buildDivider());
            // Import
            listContainer.addView(buildActionRow(R.drawable.ic_content_copy, getString(R.string.prefs_import_label), getString(R.string.prefs_import_subtitle), v->startImport()));
            listContainer.addView(buildDivider());
            // Preview (choose any JSON file)
            listContainer.addView(buildActionRow(R.drawable.ic_info, getString(R.string.prefs_preview_label), getString(R.string.prefs_preview_subtitle), v->startPreview()));
            listContainer.addView(buildDivider());
            // Reset
            listContainer.addView(buildActionRow(R.drawable.ic_delete, getString(R.string.prefs_reset_label), getString(R.string.prefs_reset_subtitle), v->promptResetDialog()));
        }
    }

    private View buildDivider(){
        View divider = new View(requireContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        // Match SettingsDivider horizontal insets (14dp start / 12dp end)
        lp.setMargins(dp(14), dp(2), dp(12), dp(2));
        divider.setLayoutParams(lp);
    divider.setBackgroundColor(0xFF262626);
        return divider;
    }

    private LinearLayout buildActionRow(int iconRes, String label, String helper, View.OnClickListener click){
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.settings_home_row_bg);
    // Match gutters 14dp start / 12dp end
    row.setPadding(dp(14), dp(8), dp(12), dp(8));
        row.setOnClickListener(click);
        android.widget.ImageView icon = new android.widget.ImageView(requireContext());
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(24), dp(24));
        iconLp.setMarginEnd(dp(16));
        icon.setLayoutParams(iconLp);
        icon.setImageResource(iconRes);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(getResources().getColor(android.R.color.darker_gray)));
        row.addView(icon);
        LinearLayout text = new LinearLayout(requireContext());
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        text.setLayoutParams(textLp);
        TextView primary = new TextView(requireContext());
        primary.setText(label);
        primary.setTextColor(getResources().getColor(R.color.colorHeading));
        primary.setTypeface(primary.getTypeface(), android.graphics.Typeface.BOLD);
        primary.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        TextView secondary = new TextView(requireContext());
        secondary.setText(helper);
        secondary.setTextColor(getResources().getColor(android.R.color.darker_gray));
        secondary.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        text.addView(primary);
        text.addView(secondary);
        row.addView(text);
        android.widget.ImageView arrow = new android.widget.ImageView(requireContext());
    LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(dp(14), dp(14));
    arrowLp.setMarginStart(dp(12));
    arrow.setLayoutParams(arrowLp);
        arrow.setImageResource(R.drawable.ic_arrow_right);
        arrow.setImageTintList(android.content.res.ColorStateList.valueOf(getResources().getColor(android.R.color.darker_gray)));
        row.addView(arrow);
        return row;
    }

    private void startExport(){
        try {
            String suggested = PreferencesBackupUtil.buildSuggestedFileName();
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, suggested);
            exportLauncher.launch(intent);
        } catch (Exception e){
            Toast.makeText(requireContext(), getString(R.string.prefs_export_failed)+": "+e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void startImport(){
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            importLauncher.launch(intent);
        } catch (Exception e){
            Toast.makeText(requireContext(), getString(R.string.prefs_import_failed)+": "+e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void promptResetDialog(){
        InputActionBottomSheetFragment f = InputActionBottomSheetFragment.newReset(getString(R.string.prefs_reset_label), "DELETE");
        f.setCallbacks(new InputActionBottomSheetFragment.Callbacks() {
            @Override public void onImportConfirmed(JSONObject json) { /* not used in reset mode */ }
            @Override public void onResetConfirmed() {
                try {
                    PreferencesBackupUtil.resetAll(requireContext());
                    Toast.makeText(requireContext(), getString(R.string.prefs_reset_done), Toast.LENGTH_LONG).show();
                    restartAppUI();
                } catch (Exception e){
                    Toast.makeText(requireContext(), getString(R.string.prefs_reset_failed)+": "+e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
        f.show(getParentFragmentManager(), "reset_sheet_confirm");
    }

    private void restartAppUI(){ if(getActivity()!=null) getActivity().recreate(); }

    private void startPreview(){
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            previewLauncher.launch(intent);
        } catch (Exception e){
            Toast.makeText(requireContext(), getString(R.string.prefs_import_failed)+": "+e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void renderPreviewDialog(JSONObject json){
        if(json==null){ Toast.makeText(requireContext(), getString(R.string.prefs_preview_none), Toast.LENGTH_SHORT).show(); return; }
        InputActionBottomSheetFragment f = InputActionBottomSheetFragment.newPreview(getString(R.string.prefs_preview_label), json.toString());
        f.setCallbacks(new InputActionBottomSheetFragment.Callbacks() {
            @Override public void onImportConfirmed(JSONObject j) {
                try {
                    PreferencesBackupUtil.applyFromJson(requireContext(), j);
                    Toast.makeText(requireContext(), getString(R.string.prefs_import_success), Toast.LENGTH_SHORT).show();
                    restartAppUI();
                } catch (Exception ex){
                    Toast.makeText(requireContext(), getString(R.string.prefs_import_failed)+": "+ex.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
            @Override public void onResetConfirmed() { /* not used here */ }
        });
        f.show(getParentFragmentManager(), "preview_json_sheet");
    }

    private int dp(int v){
        float d = getResources().getDisplayMetrics().density;
        return (int)(v * d + 0.5f);
    }

    @Override
    public int getTheme() { return R.style.CustomBottomSheetDialogTheme; }

    @Override
    public android.app.Dialog onCreateDialog(Bundle savedInstanceState) {
        android.app.Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((com.google.android.material.bottomsheet.BottomSheetDialog) dialog)
                    .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if(bottomSheet!=null){
                bottomSheet.setBackgroundResource(R.drawable.picker_bottom_sheet_gradient_bg_dynamic);
            }
        });
        return dialog;
    }
}
