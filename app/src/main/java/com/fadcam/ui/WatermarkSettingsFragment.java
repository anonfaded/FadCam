package com.fadcam.ui;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.fadcam.MainActivity;
import com.fadcam.ui.OverlayNavUtil;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.Constants;

import org.json.JSONObject;
import com.fadcam.ui.utils.NewFeatureManager;

/**
 * WatermarkSettingsFragment
 * Unified design migration: bottom sheet picker + live preview replacing spinner.
 */
public class WatermarkSettingsFragment extends Fragment {

    private static final String TAG = "WatermarkSettings";

    private SharedPreferencesManager prefs;
    private TextView valueLocationWatermark;
    private TextView valueWatermarkStyle;
    private TextView valueCustomText;
    private TextView valueLocationFormat;
    private TextView valueLocationInterval;
    private TextView previewText;
    private LocationHelper locationHelper;
    private ActivityResultLauncher<String> permissionLauncher;
    private Runnable pendingGrantAction;
    private View locationRow;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_watermark, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Mark main watermark feature as seen (dismisses NEW badge on Quick Access)
        NewFeatureManager.markFeatureAsSeen(requireContext(), "watermark");
        
        prefs = SharedPreferencesManager.getInstance(requireContext());
        valueLocationWatermark = view.findViewById(R.id.value_location_watermark);
        valueWatermarkStyle = view.findViewById(R.id.value_watermark_style);
        valueCustomText = view.findViewById(R.id.value_custom_text);
        valueLocationFormat = view.findViewById(R.id.value_location_format);
        valueLocationInterval = view.findViewById(R.id.value_location_interval);
        previewText = view.findViewById(R.id.text_watermark_preview);
        View rowStyle = view.findViewById(R.id.row_watermark_option);
        if(rowStyle!=null){ rowStyle.setOnClickListener(v -> showWatermarkStyleBottomSheet()); }
        locationRow = view.findViewById(R.id.row_location_watermark);
        if(locationRow!=null){ locationRow.setOnClickListener(v -> { if(locationRow.isEnabled()) showLocationWatermarkSheet(); }); }
        View rowLocationFormat = view.findViewById(R.id.row_location_format);
        if(rowLocationFormat!=null){ rowLocationFormat.setOnClickListener(v -> showLocationFormatBottomSheet()); }
        View rowLocationInterval = view.findViewById(R.id.row_location_interval);
        if(rowLocationInterval!=null){ rowLocationInterval.setOnClickListener(v -> showLocationIntervalBottomSheet()); }
        View rowCustomText = view.findViewById(R.id.row_custom_text);
        if(rowCustomText!=null){ 
            rowCustomText.setOnClickListener(v -> {
                // Mark custom text badge as seen when clicking this specific row
                NewFeatureManager.markFeatureAsSeen(requireContext(), "watermark_custom_text");
                // Hide badge immediately
                TextView badgeCustomText = view.findViewById(R.id.badge_custom_text);
                if (badgeCustomText != null) {
                    badgeCustomText.setVisibility(View.GONE);
                }
                // Show the bottom sheet
                showCustomTextBottomSheet();
            });
        }
        View back = view.findViewById(R.id.back_button);
        if (back != null) {
            back.setOnClickListener(v -> OverlayNavUtil.dismiss(requireActivity()));
        }
        permissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                onPermissionGrantedPostRequest();
            } else {
                onPermissionDeniedPostRequest();
            }
        });
        
    // Manage badge visibility for custom text row
    TextView badgeCustomText = view.findViewById(R.id.badge_custom_text);
    if (badgeCustomText != null) {
        boolean shouldShowCustomTextBadge = NewFeatureManager.shouldShowBadge(requireContext(), "watermark_custom_text");
        badgeCustomText.setVisibility(shouldShowCustomTextBadge ? View.VISIBLE : View.GONE);
    }
        
    refreshLocationValue();
    refreshWatermarkStyleValue();
    refreshCustomTextValue();
    refreshLocationFormatValue();
    refreshLocationIntervalValue();
    updateLocationRowState();
    updatePreview();
    }

    // Removed duplicate manual back handling; centralized via OverlayNavUtil

    private void refreshLocationValue() {
        if (valueLocationWatermark != null) {
            valueLocationWatermark.setText(prefs.isLocalisationEnabled() ? "Enabled" : "Disabled");
        }
    }

    private void toggleLocationDirect(boolean target){
        if(target){
            ensurePermissionThen(() -> {
                prefs.setLocationEnabled(true);
                startLocationHelperIfNeeded();
                refreshLocationValue();
                updatePreview();
                updateLocationRowState();
                FLog.d(TAG, "Location watermark enabled via sheet.");
            });
        } else {
            prefs.setLocationEnabled(false);
            stopLocationIfAllDisabled();
            refreshLocationValue();
            updatePreview();
            updateLocationRowState();
            FLog.d(TAG, "Location watermark disabled via sheet.");
        }
    }

    private void showLocationWatermarkSheet(){
        final String resultKey = "picker_result_location_watermark";
        boolean enabled = prefs.isLocalisationEnabled();
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k,b)->{
            if(b.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE)){
                boolean state = b.getBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE);
                toggleLocationDirect(state);
            }
        });
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceWithSwitch(
            getString(R.string.location_watermark_sheet_title), new java.util.ArrayList<>(), null, resultKey,
            getString(R.string.helper_location_overlay_short), getString(R.string.location_watermark_switch_label), enabled);
        sheet.show(getParentFragmentManager(), "location_watermark_sheet");
    }

    private void ensurePermissionThen(Runnable onGranted) {
        Context ctx = getContext();
        if (ctx == null) return;
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            onGranted.run();
        } else {
            showPermissionDialog(onGranted);
        }
    }

    private void showPermissionDialog(Runnable proceed) {
        new AlertDialog.Builder(requireContext(), com.google.android.material.R.style.MaterialAlertDialog_Material3)
                .setTitle(R.string.location_permission_title)
                .setMessage(R.string.location_permission_description)
                .setPositiveButton("Grant", (d, w) -> {
                    pendingGrantAction = proceed;
                    permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
                })
                .setNegativeButton(R.string.universal_cancel, (d, w) -> { })
                .show();
    }

    private void onPermissionGrantedPostRequest() {
        if (pendingGrantAction != null) {
            pendingGrantAction.run();
            pendingGrantAction = null;
        }
        Toast.makeText(requireContext(), R.string.location_permission_title, Toast.LENGTH_SHORT).show();
    }

    private void onPermissionDeniedPostRequest() {
        pendingGrantAction = null;
        Toast.makeText(requireContext(), R.string.location_permission_denied, Toast.LENGTH_SHORT).show();
    }

    private void startLocationHelperIfNeeded() {
        if (locationHelper == null) {
            locationHelper = new LocationHelper(requireContext());
        }
    }

    private void stopLocationIfAllDisabled() {
        if (!prefs.isLocalisationEnabled() && !prefs.isLocationEmbeddingEnabled()) {
            if (locationHelper != null) {
                locationHelper.stopLocationUpdates();
                locationHelper = null;
            }
        }
    }

    private void showWatermarkStyleBottomSheet(){
        final String resultKey = "picker_result_watermark_style";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k,b)->{
            if(b.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID)){
                String id = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                if(id!=null){
                    prefs.sharedPreferences.edit().putString(Constants.PREF_WATERMARK_OPTION, id).apply();
                    refreshWatermarkStyleValue();
                    updateLocationRowState();
                    updatePreview();
                }
            }
        });
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
    items.add(new com.fadcam.ui.picker.OptionItem("timestamp_fadcam", getString(R.string.watermark_style_time_fadcam_label), (String) null));
    items.add(new com.fadcam.ui.picker.OptionItem("timestamp", getString(R.string.watermark_style_timeonly_label), (String) null));
    items.add(new com.fadcam.ui.picker.OptionItem("no_watermark", getString(R.string.watermark_style_none_label), (String) null));
        String current = prefs.getWatermarkOption();
    com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
        getString(R.string.watermark_style_row_title), items, current, resultKey, getString(R.string.helper_watermark_option));
        sheet.show(getParentFragmentManager(), "watermark_style_sheet");
    }

    private void refreshWatermarkStyleValue(){
        if(valueWatermarkStyle==null) return;
        String v = prefs.getWatermarkOption();
        if("timestamp_fadcam".equals(v)) valueWatermarkStyle.setText(getString(R.string.watermark_style_time_fadcam_label));
        else if("timestamp".equals(v)) valueWatermarkStyle.setText(getString(R.string.watermark_style_timeonly_label));
        else valueWatermarkStyle.setText(getString(R.string.watermark_style_none_label));
    }

    private void updateLocationRowState(){
        if(locationRow==null) return;
        boolean watermarkNone = "no_watermark".equals(prefs.getWatermarkOption());
        if(watermarkNone){
            if(prefs.isLocalisationEnabled()){
                prefs.setLocationEnabled(false);
                refreshLocationValue();
            }
            locationRow.setEnabled(false);
            locationRow.setAlpha(0.4f);
        } else {
            locationRow.setEnabled(true);
            locationRow.setAlpha(1f);
        }
    }

    private void updatePreview(){
        if(previewText==null) return;
        String v = prefs.getWatermarkOption();
        // Static sample timestamp (preview only; not live updating)
    String formatted = "10/Jul/2024 04:47:00 PM"; // static sample in original format
        String baseLine = null;
        if("timestamp_fadcam".equals(v)){
            baseLine = getString(R.string.watermark_preview_sample_fadcam, formatted);
        } else if("timestamp".equals(v)) {
            baseLine = getString(R.string.watermark_preview_sample_timeonly, formatted);
        }
    TextView helper = getView()!=null? getView().findViewById(R.id.text_preview_helper):null;
        if(baseLine==null){
            // Show a UI-only placeholder instead of hiding the preview when no watermark is selected.
            previewText.setText(getString(R.string.watermark_preview_funny));
            previewText.setVisibility(View.VISIBLE);
            if(helper!=null){ helper.setText(getString(R.string.watermark_disabled_message)); }
            return;
        } else if(helper!=null){
            helper.setText(getString(R.string.helper_watermark_preview));
        }
        if(prefs.isLocalisationEnabled()){
            // Show location format preview with anonymized/fictional values
            String format = prefs.getWatermarkLocationFormat();
            if("address".equals(format)){
                // Preview with fictional full address (mirrors Nominatim display_name style)
                baseLine += "\nLat= 48.XXX, Lon= 2.XXX\nMain Street, Sample City, Region, Country";
            } else {
                // Coordinates only (default) — partially censored
                baseLine += "\nLat= 48.XXX, Lon= 2.XXX";
            }
        }
        // Add custom text on line 2 (or line 3 if location enabled)
        String customText = prefs.getWatermarkCustomText();
        if(customText != null && !customText.isEmpty()){
            baseLine += "\n" + customText;
        }
        previewText.setText(baseLine);
        previewText.setVisibility(View.VISIBLE);
    }

    private void refreshCustomTextValue(){
        if(valueCustomText==null) return;
        String customText = prefs.getWatermarkCustomText();
        if(customText == null || customText.isEmpty()){
            valueCustomText.setText(getString(R.string.watermark_custom_text_empty));
        } else {
            valueCustomText.setText(customText);
        }
    }

    private void showCustomTextBottomSheet(){
        String currentText = prefs.getWatermarkCustomText();
        
        InputActionBottomSheetFragment sheet = InputActionBottomSheetFragment.newInput(
            getString(R.string.watermark_custom_text_title),
            currentText != null ? currentText : "",
            getString(R.string.watermark_custom_text_hint),
            getString(R.string.shortcuts_rename_action_title),
            getString(R.string.helper_watermark_custom_text),
            R.drawable.ic_draw_edit,
            getString(R.string.helper_watermark_custom_text)
        );

        sheet.setCallbacks(new InputActionBottomSheetFragment.Callbacks() {
            @Override
            public void onImportConfirmed(JSONObject json) {
                // Not used for custom text input
            }
            
            @Override
            public void onResetConfirmed() {
                // Not used for custom text input
            }
            
            @Override
            public void onInputConfirmed(String input) {
                if(input != null){
                    prefs.setWatermarkCustomText(input.trim());
                    refreshCustomTextValue();
                    updatePreview();
                    FLog.d(TAG, "Custom watermark text set: " + input);
                }
                sheet.dismiss();
            }
        });
        
        sheet.show(getParentFragmentManager(), "custom_text_sheet");
    }

    private String formatNow(){
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MMM/yyyy hh:mm:ss a", java.util.Locale.getDefault());
        return sdf.format(new java.util.Date());
    }

    private void refreshLocationFormatValue(){
        if(valueLocationFormat==null) return;
        String format = prefs.getWatermarkLocationFormat();
        if("address".equals(format)){
            valueLocationFormat.setText(getString(R.string.location_format_address));
        } else {
            valueLocationFormat.setText(getString(R.string.location_format_coordinates));
        }
    }

    private void refreshLocationIntervalValue(){
        if(valueLocationInterval==null) return;
        long intervalMs = prefs.getWatermarkUpdateInterval();
        double seconds = intervalMs / 1000.0;
        valueLocationInterval.setText(String.format("%.1f seconds", seconds));
    }

    private void showLocationFormatBottomSheet(){
        final String resultKey = "picker_result_location_format";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k,b)->{
            if(b.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID)){
                String id = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                if(id!=null){
                    prefs.setWatermarkLocationFormat(id);
                    refreshLocationFormatValue();
                    updatePreview();
                    FLog.d(TAG, "Location format set: " + id);
                }
            }
        });
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem("coordinates", getString(R.string.location_format_coordinates), (String) null));
        items.add(new com.fadcam.ui.picker.OptionItem("address", getString(R.string.location_format_address), (String) null));
        String current = prefs.getWatermarkLocationFormat();
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
            getString(R.string.location_format_title), items, current, resultKey, getString(R.string.helper_location_format));
        sheet.show(getParentFragmentManager(), "location_format_sheet");
    }

    private void showLocationIntervalBottomSheet(){
        final String resultKey = "picker_result_location_interval";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k,b)->{
            if(b.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID)){
                String id = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                if(id!=null){
                    try {
                        long intervalMs = Long.parseLong(id);
                        prefs.setWatermarkUpdateInterval(intervalMs);
                        refreshLocationIntervalValue();
                        FLog.d(TAG, "Location update interval set: " + intervalMs + "ms");
                    } catch(NumberFormatException e){
                        FLog.w(TAG, "Invalid interval value: " + id);
                    }
                }
            }
        });
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem("1000", "1 second (minimum — respects Nominatim API rate limit)", (String) null));
        items.add(new com.fadcam.ui.picker.OptionItem("2000", "2 seconds", (String) null));
        items.add(new com.fadcam.ui.picker.OptionItem("5000", "5 seconds (Default)", (String) null));
        items.add(new com.fadcam.ui.picker.OptionItem("10000", "10 seconds", (String) null));
        items.add(new com.fadcam.ui.picker.OptionItem("15000", "15 seconds", (String) null));
        items.add(new com.fadcam.ui.picker.OptionItem("30000", "30 seconds", (String) null));
        items.add(new com.fadcam.ui.picker.OptionItem("60000", "1 minute", (String) null));
        items.add(new com.fadcam.ui.picker.OptionItem("180000", "3 minutes", (String) null));
        items.add(new com.fadcam.ui.picker.OptionItem("300000", "5 minutes", (String) null));
        items.add(new com.fadcam.ui.picker.OptionItem("600000", "10 minutes", (String) null));
        items.add(new com.fadcam.ui.picker.OptionItem("3600000", "1 hour", (String) null));
        String currentMs = String.valueOf(prefs.getWatermarkUpdateInterval());
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
            getString(R.string.location_update_interval_title), items, currentMs, resultKey, getString(R.string.location_update_interval_helper));
        sheet.show(getParentFragmentManager(), "location_interval_sheet");
    }

    // Removed legacy spinner index/value helpers (unified bottom sheet now)
}
