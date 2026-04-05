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
                    ? "\nLat= 48.XXX, Lon= 2.XXX\nMain Street, Sample City, Region, Country"
                    : "\nLat= 48.XXX, Lon= 2.XXX";
            fadcamBase += locationLines;
            fadrecBase += locationLines;
        }

        // Append custom text
        String customText = prefs.getWatermarkCustomText();
        if (customText != null && !customText.isEmpty()) {
            fadcamBase += "\n" + customText;
            fadrecBase += "\n" + customText;
        }

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
}
