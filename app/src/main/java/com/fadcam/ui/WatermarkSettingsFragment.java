package com.fadcam.ui;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

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
    /** 0 = FadCam page, 1 = FadRec page */
    private int currentPreviewPage = 0;
    private ViewPager2 previewPager;
    private PreviewPagerAdapter previewAdapter;
    private View dotFadCam, dotFadRec;
    private LocationHelper locationHelper;
    private ActivityResultLauncher<String> permissionLauncher;
    private Runnable pendingGrantAction;
    private View locationRow;
    private GpsProviderReceiver gpsProviderReceiver;

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
        // previewText will be set up in setupButtonHandlers (using new preview card IDs)
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

        // Timezone toggle
        com.fadcam.ui.AvatarToggleView switchTimezone = view.findViewById(R.id.switch_timezone);
        if (switchTimezone != null) {
            switchTimezone.setChecked(prefs.isTimezoneEnabled());
            switchTimezone.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.setTimezoneEnabled(isChecked);
                refreshExtendedRowValues();
                updateTimezoneFormatRowState();
            });
        }
        View rowTimezone = view.findViewById(R.id.row_timezone);
        if (rowTimezone != null) rowTimezone.setOnClickListener(v -> {
            if (switchTimezone != null) switchTimezone.performClick();
        });

        // Timezone format picker
        View rowTimezoneFormat = view.findViewById(R.id.row_timezone_format);
        if (rowTimezoneFormat != null) rowTimezoneFormat.setOnClickListener(v -> showTimezoneFormatBottomSheet());
        updateTimezoneFormatRowState();

        // UTM toggle
        com.fadcam.ui.AvatarToggleView switchUtm = view.findViewById(R.id.switch_utm);
        if (switchUtm != null) {
            switchUtm.setChecked(prefs.isUtmEnabled());
            switchUtm.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.setUtmEnabled(isChecked);
                updateUtmHelperVisibility();
                refreshExtendedRowValues();
            });
        }
        View rowUtm = view.findViewById(R.id.row_utm);
        if (rowUtm != null) rowUtm.setOnClickListener(v -> {
            if (switchUtm != null) switchUtm.performClick();
        });
        updateUtmHelperVisibility();

        View rowAccuracy = view.findViewById(R.id.row_accuracy);
        if (rowAccuracy != null) rowAccuracy.setOnClickListener(v -> {
            com.fadcam.ui.AvatarToggleView sw = view.findViewById(R.id.switch_accuracy);
            if (sw != null) sw.performClick();
        });

        // Extended watermark feature toggles
        com.fadcam.ui.AvatarToggleView switchSpeed = view.findViewById(R.id.switch_speed);
        if (switchSpeed != null) {
            switchSpeed.setChecked(prefs.isSpeedEnabled());
            switchSpeed.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.setSpeedEnabled(isChecked);
                refreshExtendedRowValues();
            });
        }
        com.fadcam.ui.AvatarToggleView switchAltitude = view.findViewById(R.id.switch_altitude);
        if (switchAltitude != null) {
            switchAltitude.setChecked(prefs.isAltitudeEnabled());
            switchAltitude.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.setAltitudeEnabled(isChecked);
                refreshExtendedRowValues();
            });
        }
        com.fadcam.ui.AvatarToggleView switchAccuracy = view.findViewById(R.id.switch_accuracy);
        if (switchAccuracy != null) {
            switchAccuracy.setChecked(prefs.isAccuracyEnabled());
            switchAccuracy.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.setAccuracyEnabled(isChecked);
                refreshExtendedRowValues();
            });
        }
        com.fadcam.ui.AvatarToggleView switchCompass = view.findViewById(R.id.switch_compass);
        if (switchCompass != null) {
            switchCompass.setChecked(prefs.isCompassEnabled());
            switchCompass.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.setCompassEnabled(isChecked);
                refreshExtendedRowValues();
            });
        }
        com.fadcam.ui.AvatarToggleView switchNoise = view.findViewById(R.id.switch_noise);
        if (switchNoise != null) {
            switchNoise.setChecked(prefs.isNoiseEnabled());
            switchNoise.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    ensureAudioPermissionThen(() -> {
                        prefs.setNoiseEnabled(true);
                        refreshExtendedRowValues();
                    });
                } else {
                    prefs.setNoiseEnabled(false);
                    refreshExtendedRowValues();
                }
            });
        }
        com.fadcam.ui.AvatarToggleView switchWeather = view.findViewById(R.id.switch_weather);
        if (switchWeather != null) {
            switchWeather.setChecked(prefs.isWeatherEnabled());
            switchWeather.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked && !prefs.isNetworkWarningShown()) {
                    showNetworkWarningDialog(() -> {
                        prefs.setWeatherEnabled(true);
                        prefs.setNetworkWarningShown(true);
                        refreshExtendedRowValues();
                    });
                } else {
                    prefs.setWeatherEnabled(isChecked);
                    refreshExtendedRowValues();
                }
            });
        }

        // Make whole row clickable for toggle rows
        View rowSpeed = view.findViewById(R.id.row_speed);
        if (rowSpeed != null) rowSpeed.setOnClickListener(v -> {
            if (switchSpeed != null) switchSpeed.performClick();
        });
        View rowAltitude = view.findViewById(R.id.row_altitude);
        if (rowAltitude != null) rowAltitude.setOnClickListener(v -> {
            if (switchAltitude != null) switchAltitude.performClick();
        });
        View rowCompass = view.findViewById(R.id.row_compass);
        if (rowCompass != null) rowCompass.setOnClickListener(v -> {
            if (switchCompass != null) switchCompass.performClick();
        });
        View rowNoise = view.findViewById(R.id.row_noise);
        if (rowNoise != null) rowNoise.setOnClickListener(v -> {
            if (switchNoise != null) switchNoise.performClick();
        });
        View rowWeather = view.findViewById(R.id.row_weather);
        if (rowWeather != null) rowWeather.setOnClickListener(v -> {
            if (switchWeather != null) switchWeather.performClick();
        });

        refreshExtendedRowValues();

        // Register GPS provider change listener for real-time subtitle updates
        gpsProviderReceiver = new GpsProviderReceiver();
        android.content.IntentFilter gpsFilter = new android.content.IntentFilter(
                android.location.LocationManager.PROVIDERS_CHANGED_ACTION);
        requireContext().registerReceiver(gpsProviderReceiver, gpsFilter);

        permissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                onPermissionGrantedPostRequest();
            } else {
                onPermissionDeniedPostRequest();
            }
        });
        
        // Setup preview pager (ViewPager2 — snappy paging)
        previewPager = view.findViewById(R.id.preview_pager);
        dotFadCam = view.findViewById(R.id.dot_fadcam);
        dotFadRec = view.findViewById(R.id.dot_fadrec);
        previewAdapter = new PreviewPagerAdapter();
        if (previewPager != null) {
            previewPager.setAdapter(previewAdapter);
            previewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageSelected(int position) {
                    currentPreviewPage = position;
                    updateDots(position);
                    // Sync tab selection without triggering tab listener recursion
                    com.google.android.material.tabs.TabLayout tabs = getView() != null
                            ? getView().findViewById(R.id.preview_mode_tabs) : null;
                    if (tabs != null && tabs.getSelectedTabPosition() != position) {
                        com.google.android.material.tabs.TabLayout.Tab t = tabs.getTabAt(position);
                        if (t != null) t.select();
                    }
                }
            });
        }

        // Tab clicks navigate the pager
        com.google.android.material.tabs.TabLayout previewModeTabs = view.findViewById(R.id.preview_mode_tabs);
        if (previewModeTabs != null) {
            previewModeTabs.addOnTabSelectedListener(new com.google.android.material.tabs.TabLayout.OnTabSelectedListener() {
                @Override
                public void onTabSelected(com.google.android.material.tabs.TabLayout.Tab tab) {
                    if (previewPager != null) {
                        previewPager.setCurrentItem(tab.getPosition(), true);
                    }
                }
                @Override public void onTabUnselected(com.google.android.material.tabs.TabLayout.Tab tab) {}
                @Override public void onTabReselected(com.google.android.material.tabs.TabLayout.Tab tab) {}
            });
        }
        
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
            valueLocationWatermark.setText(prefs.isLocalisationEnabled() ? getString(R.string.setting_enabled) : getString(R.string.setting_disabled));
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

    private void ensureAudioPermissionThen(Runnable onGranted) {
        Context ctx = getContext();
        if (ctx == null) return;
        // App already requires RECORD_AUDIO for video recording, so check if granted
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onGranted.run();
        } else {
            // Just show toast - app can't work without mic anyway
            Toast.makeText(ctx, R.string.watermark_audio_permission_required, Toast.LENGTH_SHORT).show();
            View switchNoise = getView().findViewById(R.id.switch_noise);
            if (switchNoise instanceof com.fadcam.ui.AvatarToggleView) {
                ((com.fadcam.ui.AvatarToggleView) switchNoise).setChecked(false);
            }
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
    items.add(new com.fadcam.ui.picker.OptionItem("badge_fadcam", getString(R.string.watermark_style_badge_label), (String) null));
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
        else if("badge_fadcam".equals(v)) valueWatermarkStyle.setText(getString(R.string.watermark_style_badge_label));
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

    private void updateDots(int page) {
        if (dotFadCam != null && dotFadRec != null) {
            dotFadCam.setBackgroundResource(page == 0
                    ? R.drawable.indicator_dot_active : R.drawable.indicator_dot_inactive);
            dotFadRec.setBackgroundResource(page == 1
                    ? R.drawable.indicator_dot_active : R.drawable.indicator_dot_inactive);
        }
    }

    private void updatePreview(){
        String v = prefs.getWatermarkOption();
        String formatted = "10/Jul/2024 04:47:00 PM"; // static sample

        // Build FadCam preview text — [FADCAM_ICON] triggers ImageSpan in adapter
        String fadcamBase = null;
        if ("timestamp_fadcam".equals(v)) {
            fadcamBase = "Captured by [FADCAM_ICON] - " + formatted;
        } else if ("badge_fadcam".equals(v)) {
            fadcamBase = "Captured by [FADCAM_ICON]";
        } else if ("timestamp".equals(v)) {
            fadcamBase = formatted;
        }

        // Build FadRec preview text — [ICON] triggers ImageSpan in adapter
        String fadrecBase = null;
        if ("timestamp_fadcam".equals(v)) {
            fadrecBase = "Recorded by [ICON] - " + formatted;
        } else if ("badge_fadcam".equals(v)) {
            fadrecBase = "Recorded by [ICON]";
        } else if ("timestamp".equals(v)) {
            fadrecBase = formatted;
        }

        // Append timezone after timestamp
        if (prefs.isTimezoneEnabled()) {
            java.util.TimeZone tz = java.util.TimeZone.getDefault();
            String tzLabel = buildTimezoneLabel(tz, prefs.getTimezoneFormat());
            if (fadcamBase != null) fadcamBase += " " + tzLabel;
            if (fadrecBase != null) fadrecBase += " " + tzLabel;
        }

        TextView helper = getView() != null ? getView().findViewById(R.id.text_preview_helper) : null;

        if (fadcamBase == null) {
            if (previewAdapter != null) {
                previewAdapter.setContent(
                        getString(R.string.watermark_preview_funny),
                        getString(R.string.watermark_preview_funny),
                        null, null);
            }
            if (helper != null) helper.setText(getString(R.string.watermark_disabled_message));
            return;
        }
        if (helper != null) helper.setText(getString(R.string.helper_watermark_preview));

        // Append location lines if enabled
        if (prefs.isLocalisationEnabled()) {
            String format = prefs.getWatermarkLocationFormat();
            String locationLines = "address".equals(format)
                    ? "\nLat: 48.XXX, Long: 2.XXX\nMain Street, Sample City, Region, Country"
                    : "\nLat: 48.XXX, Long: 2.XXX";
            fadcamBase += locationLines;
            fadrecBase += locationLines;
        }

        // Append custom text
        String customText = prefs.getWatermarkCustomText();
        if (customText != null && !customText.isEmpty()) {
            fadcamBase += "\n" + customText;
            fadrecBase += "\n" + customText;
        }

        // Append UTM preview
        if (prefs.isUtmEnabled()) {
            fadcamBase += "\nUTM Zone 42N - 372,684m E, 3,512,334m N";
            fadrecBase += "\nUTM Zone 42N - 372,684m E, 3,512,334m N";
        }

        // Append extended sensor data preview
        String extendedPreview = "";
        if (prefs.isSpeedEnabled()) extendedPreview += "\nSpeed: 80km/h";
        if (prefs.isAltitudeEnabled()) extendedPreview += "\nAlt: 59m";
        if (prefs.isAccuracyEnabled()) extendedPreview += "\nAccuracy: 12m";
        if (prefs.isCompassEnabled()) extendedPreview += "\nCompass: 220° SW";
        if (prefs.isNoiseEnabled()) extendedPreview += "\nNoise: 45dB";
        if (prefs.isWeatherEnabled()) extendedPreview += "\n25°C Clear\nWind: 15 km/h";
        fadcamBase += extendedPreview;
        fadrecBase += extendedPreview;

        if (previewAdapter != null) {
            previewAdapter.setContent(fadcamBase, fadrecBase,
                    buildIconDrawable(R.drawable.menu_icon_unknown),
                    buildIconDrawable(R.drawable.fadrec));
        }
    }

    /**
     * Loads and scales the given drawable resource for inline use as an {@link ImageSpan}.
     * Height is fixed at 16dp; width preserves the native aspect ratio.
     */
    @Nullable
    private Drawable buildIconDrawable(int resId) {
        try {
            Context ctx = getContext();
            if (ctx == null) return null;
            Bitmap src = BitmapFactory.decodeResource(ctx.getResources(), resId);
            if (src == null) return null;
            float density = ctx.getResources().getDisplayMetrics().density;
            int iconH = Math.round(20 * density); // 20dp — matches visual weight in preview
            int iconW = Math.round(iconH * ((float) src.getWidth() / src.getHeight()));
            Bitmap scaled = Bitmap.createScaledBitmap(src, iconW, iconH, true);
            BitmapDrawable d = new BitmapDrawable(ctx.getResources(), scaled);
            d.setBounds(0, 0, iconW, iconH);
            return d;
        } catch (Exception e) {
            FLog.w(TAG, "Could not load preview icon res=" + resId, e);
            return null;
        }
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

    private String buildTimezoneLabel(java.util.TimeZone tz, String format) {
        int offsetMs = tz.getOffset(System.currentTimeMillis());
        int totalMinutes = offsetMs / 60000;
        int hours = totalMinutes / 60;
        int minutes = Math.abs(totalMinutes % 60);
        String sign = offsetMs >= 0 ? "+" : "";
        String gmt;
        if (minutes == 0) {
            gmt = "GMT" + sign + hours;
        } else {
            gmt = "GMT" + sign + hours + ":" + String.format(java.util.Locale.US, "%02d", minutes);
        }
        if ("gmt_name".equals(format)) {
            return gmt + " (" + tz.getID() + ")";
        }
        return gmt;
    }

    private void updateTimezoneFormatRowState() {
        View view = getView();
        if (view == null) return;
        View rowFormat = view.findViewById(R.id.row_timezone_format);
        if (rowFormat != null) {
            boolean enabled = prefs.isTimezoneEnabled();
            rowFormat.setEnabled(enabled);
            rowFormat.setAlpha(enabled ? 1f : 0.4f);
        }
        refreshTimezoneFormatValue();
    }

    private void refreshTimezoneFormatValue() {
        View view = getView();
        if (view == null) return;
        TextView valueFormat = view.findViewById(R.id.value_timezone_format);
        if (valueFormat != null) {
            String format = prefs.getTimezoneFormat();
            if ("gmt_name".equals(format)) {
                valueFormat.setText(getString(R.string.watermark_timezone_format_gmt_name));
            } else {
                valueFormat.setText(getString(R.string.watermark_timezone_format_gmt_only));
            }
        }
    }

    private void showTimezoneFormatBottomSheet() {
        final String resultKey = "picker_result_timezone_format";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k, b) -> {
            if (b.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID)) {
                String id = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                if (id != null) {
                    prefs.setTimezoneFormat(id);
                    refreshTimezoneFormatValue();
                    updatePreview();
                    refreshExtendedRowValues();
                }
            }
        });
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem("gmt_only", getString(R.string.watermark_timezone_format_gmt_only), (String) null));
        items.add(new com.fadcam.ui.picker.OptionItem("gmt_name", getString(R.string.watermark_timezone_format_gmt_name), (String) null));
        String current = prefs.getTimezoneFormat();
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
            getString(R.string.watermark_timezone_format_title), items, current, resultKey, null);
        sheet.show(getParentFragmentManager(), "timezone_format_sheet");
    }

    private void updateUtmHelperVisibility() {
        View view = getView();
        if (view == null) return;
        View helperRow = view.findViewById(R.id.row_utm_helper);
        if (helperRow != null) {
            helperRow.setVisibility(prefs.isUtmEnabled() ? View.VISIBLE : View.GONE);
        }
    }

    private void refreshExtendedRowValues() {
        View view = getView();
        if (view == null) return;

        android.hardware.SensorManager sm = (android.hardware.SensorManager)
                requireContext().getSystemService(Context.SENSOR_SERVICE);
        boolean hasMagnetometer = sm != null && sm.getDefaultSensor(android.hardware.Sensor.TYPE_MAGNETIC_FIELD) != null;
        boolean hasRotVec = sm != null && sm.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR) != null;

        android.location.LocationManager lm = (android.location.LocationManager)
                requireContext().getSystemService(Context.LOCATION_SERVICE);
        boolean gpsEnabled = lm != null && lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER);

        int greenColor = getResources().getColor(android.R.color.holo_green_dark, null);
        int orangeColor = getResources().getColor(android.R.color.holo_orange_dark, null);
        int grayColor = getResources().getColor(android.R.color.darker_gray, null);
        int redColor = getResources().getColor(android.R.color.holo_red_dark, null);

        // Timezone subtitle
        TextView valueTimezone = view.findViewById(R.id.value_timezone);
        AvatarToggleView switchTimezone = view.findViewById(R.id.switch_timezone);
        if (valueTimezone != null) {
            if (prefs.isTimezoneEnabled()) {
                java.util.TimeZone tz = java.util.TimeZone.getDefault();
                String tzLabel = buildTimezoneLabel(tz, prefs.getTimezoneFormat());
                valueTimezone.setText(getString(R.string.watermark_timezone_on_gmt) + " — " + tzLabel);
                valueTimezone.setTextColor(greenColor);
            } else {
                valueTimezone.setText(getString(R.string.watermark_timezone_off));
                valueTimezone.setTextColor(grayColor);
            }
        }
        if (switchTimezone != null && switchTimezone.isChecked() != prefs.isTimezoneEnabled()) {
            switchTimezone.setChecked(prefs.isTimezoneEnabled());
        }

        // UTM subtitle
        TextView valueUtm = view.findViewById(R.id.value_utm);
        AvatarToggleView switchUtm = view.findViewById(R.id.switch_utm);
        if (valueUtm != null) {
            if (prefs.isUtmEnabled()) {
                valueUtm.setText(getString(R.string.watermark_utm_on));
                valueUtm.setTextColor(greenColor);
            } else {
                valueUtm.setText(getString(R.string.watermark_utm_off));
                valueUtm.setTextColor(grayColor);
            }
        }
        if (switchUtm != null && switchUtm.isChecked() != prefs.isUtmEnabled()) {
            switchUtm.setChecked(prefs.isUtmEnabled());
        }
        updateUtmHelperVisibility();

        TextView valueSpeed = view.findViewById(R.id.value_speed);
        if (valueSpeed != null) {
            if (prefs.isSpeedEnabled()) {
                if (!gpsEnabled) {
                    valueSpeed.setText(getString(R.string.watermark_gps_disabled_warning));
                    valueSpeed.setTextColor(redColor);
                } else {
                    valueSpeed.setText(getString(R.string.watermark_speed_on));
                    valueSpeed.setTextColor(greenColor);
                }
            } else {
                valueSpeed.setText(getString(R.string.watermark_speed_off));
                valueSpeed.setTextColor(grayColor);
            }
        }
        AvatarToggleView switchSpeed = view.findViewById(R.id.switch_speed);
        if (switchSpeed != null && switchSpeed.isChecked() != prefs.isSpeedEnabled()) {
            switchSpeed.setChecked(prefs.isSpeedEnabled());
        }

        TextView valueAltitude = view.findViewById(R.id.value_altitude);
        if (valueAltitude != null) {
            if (prefs.isAltitudeEnabled()) {
                if (!gpsEnabled) {
                    valueAltitude.setText(getString(R.string.watermark_gps_disabled_warning));
                    valueAltitude.setTextColor(redColor);
                } else {
                    valueAltitude.setText(getString(R.string.watermark_altitude_on));
                    valueAltitude.setTextColor(greenColor);
                }
            } else {
                valueAltitude.setText(getString(R.string.watermark_altitude_off));
                valueAltitude.setTextColor(grayColor);
            }
        }
        AvatarToggleView switchAltitude = view.findViewById(R.id.switch_altitude);
        if (switchAltitude != null && switchAltitude.isChecked() != prefs.isAltitudeEnabled()) {
            switchAltitude.setChecked(prefs.isAltitudeEnabled());
        }

        TextView valueAccuracy = view.findViewById(R.id.value_accuracy);
        if (valueAccuracy != null) {
            if (prefs.isAccuracyEnabled()) {
                if (!gpsEnabled) {
                    valueAccuracy.setText(getString(R.string.watermark_gps_disabled_warning));
                    valueAccuracy.setTextColor(redColor);
                } else {
                    valueAccuracy.setText(getString(R.string.watermark_accuracy_on));
                    valueAccuracy.setTextColor(greenColor);
                }
            } else {
                valueAccuracy.setText(getString(R.string.watermark_accuracy_off));
                valueAccuracy.setTextColor(grayColor);
            }
        }
        AvatarToggleView switchAccuracy = view.findViewById(R.id.switch_accuracy);
        if (switchAccuracy != null && switchAccuracy.isChecked() != prefs.isAccuracyEnabled()) {
            switchAccuracy.setChecked(prefs.isAccuracyEnabled());
        }

        TextView valueCompass = view.findViewById(R.id.value_compass);
        if (valueCompass != null) {
            if (prefs.isCompassEnabled()) {
                if (!gpsEnabled && !hasRotVec && !hasMagnetometer) {
                    valueCompass.setText(getString(R.string.watermark_gps_disabled_warning));
                    valueCompass.setTextColor(redColor);
                } else if (hasRotVec || hasMagnetometer) {
                    valueCompass.setText(getString(R.string.watermark_compass_on_sensor));
                    valueCompass.setTextColor(greenColor);
                } else {
                    valueCompass.setText(getString(R.string.watermark_compass_no_sensor));
                    valueCompass.setTextColor(orangeColor);
                }
            } else {
                valueCompass.setText(getString(R.string.watermark_compass_off));
                valueCompass.setTextColor(grayColor);
            }
        }
        AvatarToggleView switchCompass = view.findViewById(R.id.switch_compass);
        if (switchCompass != null && switchCompass.isChecked() != prefs.isCompassEnabled()) {
            switchCompass.setChecked(prefs.isCompassEnabled());
        }

        TextView valueNoise = view.findViewById(R.id.value_noise);
        if (valueNoise != null) {
            if (prefs.isNoiseEnabled()) {
                valueNoise.setText(getString(R.string.watermark_noise_on));
                valueNoise.setTextColor(greenColor);
            } else {
                valueNoise.setText(getString(R.string.watermark_noise_off));
                valueNoise.setTextColor(grayColor);
            }
        }
        AvatarToggleView switchNoise = view.findViewById(R.id.switch_noise);
        if (switchNoise != null && switchNoise.isChecked() != prefs.isNoiseEnabled()) {
            switchNoise.setChecked(prefs.isNoiseEnabled());
        }

        TextView valueWeather = view.findViewById(R.id.value_weather);
        if (valueWeather != null) {
            if (prefs.isWeatherEnabled()) {
                if (!gpsEnabled) {
                    valueWeather.setText(getString(R.string.watermark_gps_disabled_warning));
                    valueWeather.setTextColor(redColor);
                } else {
                    valueWeather.setText(getString(R.string.watermark_weather_on));
                    valueWeather.setTextColor(greenColor);
                }
            } else {
                valueWeather.setText(getString(R.string.watermark_weather_off));
                valueWeather.setTextColor(grayColor);
            }
        }
        AvatarToggleView switchWeather = view.findViewById(R.id.switch_weather);
        if (switchWeather != null && switchWeather.isChecked() != prefs.isWeatherEnabled()) {
            switchWeather.setChecked(prefs.isWeatherEnabled());
        }

        updatePreview();
    }

    private void showNetworkWarningDialog(Runnable onConfirm) {
        new AlertDialog.Builder(requireContext(), com.google.android.material.R.style.MaterialAlertDialog_Material3)
                .setTitle(R.string.watermark_network_warning_title)
                .setMessage(R.string.watermark_network_warning_message)
                .setPositiveButton(R.string.watermark_network_warning_enable, (d, w) -> {
                    onConfirm.run();
                })
                .setNegativeButton("Cancel", (d, w) -> {
                    // Reset the switch
                    View switchWeather = getView().findViewById(R.id.switch_weather);
                    if (switchWeather instanceof com.fadcam.ui.AvatarToggleView) {
                        ((com.fadcam.ui.AvatarToggleView) switchWeather).setChecked(false);
                    }
                    prefs.setWeatherEnabled(false);
                })
                .show();
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

    // ---- Inner adapter for the preview ViewPager2 ----

    /**
     * Two-page adapter: page 0 = FadCam preview, page 1 = FadRec preview.
     * Content is set via {@link #setContent} and pages update themselves via
     * {@link RecyclerView.Adapter#notifyItemChanged}.
     */
    private class PreviewPagerAdapter extends RecyclerView.Adapter<PreviewPagerAdapter.VH> {

        private CharSequence fadcamContent = "";
        private CharSequence fadrecContent = "";

        void setContent(String fadcamText, String fadrecRaw,
                        @Nullable Drawable fadcamIconDrawable,
                        @Nullable Drawable fadrecIconDrawable) {
            fadcamContent = buildSpannable(fadcamText, "[FADCAM_ICON]", fadcamIconDrawable, "FadCam");
            fadrecContent = buildSpannable(fadrecRaw, "[ICON]", fadrecIconDrawable, "FadRec");
            notifyItemChanged(0);
            notifyItemChanged(1);
        }

        private CharSequence buildSpannable(String raw, String token,
                                            @Nullable Drawable icon, String fallback) {
            if (raw == null) return "";
            if (raw.contains(token)) {
                if (icon != null) {
                    int idx = raw.indexOf(token);
                    SpannableString span = new SpannableString(raw.replace(token, "\uFFFD"));
                    span.setSpan(new ImageSpan(icon, ImageSpan.ALIGN_BASELINE),
                            idx, idx + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    return span;
                }
                return raw.replace(token, fallback);
            }
            return raw;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_watermark_preview, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            holder.text.setText(position == 0 ? fadcamContent : fadrecContent);
        }

        @Override
        public int getItemCount() { return 2; }

        class VH extends RecyclerView.ViewHolder {
            final TextView text;
            VH(View itemView) {
                super(itemView);
                text = itemView.findViewById(R.id.text_watermark_preview);
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (gpsProviderReceiver != null) {
            try {
                requireContext().unregisterReceiver(gpsProviderReceiver);
            } catch (IllegalArgumentException ignored) {}
            gpsProviderReceiver = null;
        }
    }

    private class GpsProviderReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            refreshExtendedRowValues();
        }
    }
}
