package com.fadcam.ui;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
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

/**
 * WatermarkSettingsFragment
 * Unified design migration: bottom sheet picker + live preview replacing spinner.
 */
public class WatermarkSettingsFragment extends Fragment {

    private static final String TAG = "WatermarkSettings";

    private SharedPreferencesManager prefs;
    private TextView valueLocationWatermark;
    private TextView valueWatermarkStyle;
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
        // -------------- Fix Start for this method(onViewCreated)-----------
        prefs = SharedPreferencesManager.getInstance(requireContext());
    valueLocationWatermark = view.findViewById(R.id.value_location_watermark);
    valueWatermarkStyle = view.findViewById(R.id.value_watermark_style);
    previewText = view.findViewById(R.id.text_watermark_preview);
    View rowStyle = view.findViewById(R.id.row_watermark_option);
    if(rowStyle!=null){ rowStyle.setOnClickListener(v -> showWatermarkStyleBottomSheet()); }
    locationRow = view.findViewById(R.id.row_location_watermark);
    if(locationRow!=null){ locationRow.setOnClickListener(v -> { if(locationRow.isEnabled()) showLocationWatermarkSheet(); }); }
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
    refreshLocationValue();
    refreshWatermarkStyleValue();
    updateLocationRowState();
    updatePreview();
        // -------------- Fix Ended for this method(onViewCreated)-----------
    }

    // Removed duplicate manual back handling; centralized via OverlayNavUtil

    private void refreshLocationValue() {
        // -------------- Fix Start for this method(refreshLocationValue)-----------
        if (valueLocationWatermark != null) {
            valueLocationWatermark.setText(prefs.isLocalisationEnabled() ? "Enabled" : "Disabled");
        }
        // -------------- Fix Ended for this method(refreshLocationValue)-----------
    }

    private void toggleLocationDirect(boolean target){
        if(target){
            ensurePermissionThen(() -> {
                prefs.setLocationEnabled(true);
                startLocationHelperIfNeeded();
                refreshLocationValue();
                updatePreview();
                updateLocationRowState();
                Log.d(TAG, "Location watermark enabled via sheet.");
            });
        } else {
            prefs.setLocationEnabled(false);
            stopLocationIfAllDisabled();
            refreshLocationValue();
            updatePreview();
            updateLocationRowState();
            Log.d(TAG, "Location watermark disabled via sheet.");
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
        // -------------- Fix Start for this method(showPermissionDialog)-----------
        new AlertDialog.Builder(requireContext(), com.google.android.material.R.style.MaterialAlertDialog_Material3)
                .setTitle(R.string.location_permission_title)
                .setMessage(R.string.location_permission_description)
                .setPositiveButton("Grant", (d, w) -> {
                    pendingGrantAction = proceed;
                    permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
                })
                .setNegativeButton(R.string.universal_cancel, (d, w) -> { })
                .show();
        // -------------- Fix Ended for this method(showPermissionDialog)-----------
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

    // -------------- Fix Start for this method(updateLocationRowState)-----------
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
    // -------------- Fix Ended for this method(updateLocationRowState)-----------

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
            // Anonymized dummy coordinates (x placeholders prevent revealing real location structure)
            baseLine += "\nLat: 24.x6xx  Lon: 67.x0xx";
        }
        previewText.setText(baseLine);
        previewText.setVisibility(View.VISIBLE);
    }

    private String formatNow(){
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MMM/yyyy hh:mm:ss a", java.util.Locale.getDefault());
        return sdf.format(new java.util.Date());
    }

    // Removed legacy spinner index/value helpers (unified bottom sheet now)
}
