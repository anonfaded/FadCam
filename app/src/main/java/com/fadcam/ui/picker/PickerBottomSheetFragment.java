package com.fadcam.ui.picker;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ImageView;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * PickerBottomSheetFragment
 * Generic, minimal bottom sheet list. Preserves legacy logic by delegating persistence
 * to caller via fragment result API. No behavioral changes vs legacy dialogs.
 */
public class PickerBottomSheetFragment extends BottomSheetDialogFragment {

    public static final String ARG_TITLE = "title";
    public static final String ARG_ITEMS = "items"; // ArrayList<OptionItem>
    public static final String ARG_SELECTED_ID = "selected_id";
    public static final String RESULT_KEY = "picker_result_key"; // require caller-specific key in args
    public static final String ARG_RESULT_KEY = "result_key";
    public static final String BUNDLE_SELECTED_ID = "selected_id";
    public static final String ARG_HELPER_TEXT = "helper_text";
    public static final String ARG_SWITCH_PRESENT = "switch_present";
    public static final String ARG_SWITCH_TITLE = "switch_title";
    public static final String ARG_SWITCH_STATE = "switch_state";
    public static final String BUNDLE_SWITCH_STATE = "switch_state";
    public static final String ARG_SWITCH_DEPENDENT_IDS = "switch_dependent_ids"; // ArrayList<String> of option ids disabled when switch is off
    public static final String ARG_USE_GRADIENT = "use_gradient_bg";
    public static final String ARG_GRID_MODE = "grid_mode"; // for icon grid
    public static final String ARG_HIDE_CHECK = "hide_check"; // hide selection checkmark UI
    // Slider mode args
    public static final String ARG_SLIDER_MODE = "slider_mode";
    public static final String ARG_SLIDER_MIN = "slider_min";
    public static final String ARG_SLIDER_MAX = "slider_max";
    public static final String ARG_SLIDER_STEP = "slider_step"; // integer step (index step)
    public static final String ARG_SLIDER_STEP_FLOAT = "slider_step_float"; // EV per index as float (e.g., 0.5)
    public static final String ARG_SLIDER_INITIAL = "slider_initial";
    public static final String ARG_SLIDER_ZOOM_MODE = "slider_zoom_mode"; // Special zoom formatting
    public static final String BUNDLE_SLIDER_VALUE = "slider_value";

    public static PickerBottomSheetFragment newInstance(String title, ArrayList<OptionItem> items, String selectedId, String resultKey){
        PickerBottomSheetFragment f = new PickerBottomSheetFragment();
        Bundle b = new Bundle();
        b.putString(ARG_TITLE, title);
        b.putParcelableArrayList(ARG_ITEMS, items);
        b.putString(ARG_SELECTED_ID, selectedId);
        b.putString(ARG_RESULT_KEY, resultKey);
        f.setArguments(b);
        return f;
    }

    public static PickerBottomSheetFragment newInstance(String title, ArrayList<OptionItem> items, String selectedId, String resultKey, String helper){
        PickerBottomSheetFragment f = newInstance(title, items, selectedId, resultKey);
        if(f.getArguments()!=null){ f.getArguments().putString(ARG_HELPER_TEXT, helper); }
        return f;
    }

    public static PickerBottomSheetFragment newInstanceWithSwitch(String title, ArrayList<OptionItem> items, String selectedId, String resultKey, String helper,
                                                                  String switchTitle, boolean switchState){
        PickerBottomSheetFragment f = newInstance(title, items, selectedId, resultKey);
        if(f.getArguments()!=null){
            f.getArguments().putString(ARG_HELPER_TEXT, helper);
            f.getArguments().putBoolean(ARG_SWITCH_PRESENT, true);
            f.getArguments().putString(ARG_SWITCH_TITLE, switchTitle);
            f.getArguments().putBoolean(ARG_SWITCH_STATE, switchState);
        }
        return f;
    }

    public static PickerBottomSheetFragment newInstanceWithSwitchDependencies(String title, ArrayList<OptionItem> items, String selectedId, String resultKey, String helper,
                                                                              String switchTitle, boolean switchState, ArrayList<String> dependentIds){
        PickerBottomSheetFragment f = newInstanceWithSwitch(title, items, selectedId, resultKey, helper, switchTitle, switchState);
        if(f.getArguments()!=null && dependentIds!=null){ f.getArguments().putStringArrayList(ARG_SWITCH_DEPENDENT_IDS, dependentIds); }
        return f;
    }

    public static PickerBottomSheetFragment newInstanceGradient(String title, ArrayList<OptionItem> items, String selectedId, String resultKey, String helper, boolean useGradient){
        PickerBottomSheetFragment f = newInstance(title, items, selectedId, resultKey, helper);
        if(f.getArguments()!=null){ f.getArguments().putBoolean(ARG_USE_GRADIENT, useGradient); }
        return f;
    }

    public static PickerBottomSheetFragment newInstanceGrid(String title, ArrayList<OptionItem> items, String selectedId, String resultKey, String helper){
        PickerBottomSheetFragment f = newInstance(title, items, selectedId, resultKey, helper);
        if(f.getArguments()!=null){ f.getArguments().putBoolean(ARG_GRID_MODE, true); }
        return f;
    }

    public static PickerBottomSheetFragment newInstanceSlider(String title, int min, int max, int step, float stepFloat, int initialValue, String resultKey, String helper){
        PickerBottomSheetFragment f = new PickerBottomSheetFragment();
        Bundle b = new Bundle();
        b.putString(ARG_TITLE, title);
        b.putString(ARG_RESULT_KEY, resultKey);
        b.putBoolean(ARG_SLIDER_MODE, true);
        b.putInt(ARG_SLIDER_MIN, min);
        b.putInt(ARG_SLIDER_MAX, max);
        b.putInt(ARG_SLIDER_STEP, step);
        b.putFloat(ARG_SLIDER_STEP_FLOAT, stepFloat);
        b.putInt(ARG_SLIDER_INITIAL, initialValue);
        b.putString(ARG_HELPER_TEXT, helper);
        f.setArguments(b);
        return f;
    }

    public static PickerBottomSheetFragment newInstanceSliderWithSwitch(String title, int min, int max, int step, float stepFloat, int initialValue, String resultKey, String helper, String switchTitle, boolean switchState){
        PickerBottomSheetFragment f = new PickerBottomSheetFragment();
        Bundle b = new Bundle();
        b.putString(ARG_TITLE, title);
        b.putString(ARG_RESULT_KEY, resultKey);
        b.putBoolean(ARG_SLIDER_MODE, true);
        b.putInt(ARG_SLIDER_MIN, min);
        b.putInt(ARG_SLIDER_MAX, max);
        b.putInt(ARG_SLIDER_STEP, step);
        b.putFloat(ARG_SLIDER_STEP_FLOAT, stepFloat);
        b.putInt(ARG_SLIDER_INITIAL, initialValue);
        b.putString(ARG_HELPER_TEXT, helper);
        b.putBoolean(ARG_SWITCH_PRESENT, true);
        b.putString(ARG_SWITCH_TITLE, switchTitle);
        b.putBoolean(ARG_SWITCH_STATE, switchState);
        f.setArguments(b);
        return f;
    }

    public static PickerBottomSheetFragment newInstanceSliderZoom(String title, List<Float> zoomRatios, float currentZoom, String resultKey, String helper){
        PickerBottomSheetFragment f = new PickerBottomSheetFragment();
        Bundle b = new Bundle();
        b.putString(ARG_TITLE, title);
        b.putString(ARG_RESULT_KEY, resultKey);
        b.putBoolean(ARG_SLIDER_MODE, true);
        b.putBoolean(ARG_SLIDER_ZOOM_MODE, true);
        // For zoom, we'll map indices to actual zoom values
        b.putInt(ARG_SLIDER_MIN, 0);
        b.putInt(ARG_SLIDER_MAX, zoomRatios.size() - 1);
        b.putInt(ARG_SLIDER_STEP, 1);
        b.putFloat(ARG_SLIDER_STEP_FLOAT, 1.0f); // Will be overridden by zoom logic
        // Find current zoom index
        int currentIndex = 0;
        for (int i = 0; i < zoomRatios.size(); i++) {
            if (Math.abs(zoomRatios.get(i) - currentZoom) < 0.01f) {
                currentIndex = i;
                break;
            }
        }
        b.putInt(ARG_SLIDER_INITIAL, currentIndex);
        b.putString(ARG_HELPER_TEXT, helper);
        // Store zoom ratios as a float array for use in formatting
        float[] ratioArray = new float[zoomRatios.size()];
        for (int i = 0; i < zoomRatios.size(); i++) {
            ratioArray[i] = zoomRatios.get(i);
        }
        b.putFloatArray("zoom_ratios", ratioArray);
        f.setArguments(b);
        return f;
    }


    private ArrayList<OptionItem> items = new ArrayList<>();
    private String selectedId;
    private String title;
    private String resultKey;
    private String helperText;
    private boolean switchPresent = false; private String switchTitle; private boolean switchState;
    private ArrayList<String> switchDependentIds = new ArrayList<>();
    private LinearLayout containerLayoutRef; private android.widget.CompoundButton switchRef;
    private boolean useGradientBg = true; // default enabled globally
    private boolean gridMode = false;
    private boolean hideCheck = false;
    private boolean sliderMode = false;
    private boolean sliderZoomMode = false;
    private float[] zoomRatios = null;
    private int sliderMin = 0, sliderMax = 0, sliderStep = 1, sliderInitial = 0;
    private float sliderStepFloat = 1f; // EV per index
    private static android.graphics.Typeface MATERIAL_ICONS_TF = null; // cached

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Bundle args = getArguments();
        if(args!=null && args.getBoolean(ARG_SLIDER_MODE, false)){
            return inflater.inflate(R.layout.picker_bottom_sheet_slider, container, false);
        }
        return inflater.inflate(R.layout.picker_bottom_sheet, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // -------------- Fix Start for this method(onViewCreated)-----------
        Bundle args = getArguments();
        android.util.Log.d("PickerBottomSheet", "onViewCreated called, args=" + args);
        com.fadcam.Log.d("PickerBottomSheet", "onViewCreated called, args=" + args);
    if(args!=null){
            title = args.getString(ARG_TITLE, "");
            selectedId = args.getString(ARG_SELECTED_ID, null);
            resultKey = args.getString(ARG_RESULT_KEY, RESULT_KEY);
            ArrayList<OptionItem> list = args.getParcelableArrayList(ARG_ITEMS);
            if(list!=null) items = list;
            helperText = args.getString(ARG_HELPER_TEXT, null);
            switchPresent = args.getBoolean(ARG_SWITCH_PRESENT, false);
            switchTitle = args.getString(ARG_SWITCH_TITLE, "");
            switchState = args.getBoolean(ARG_SWITCH_STATE, false);
        ArrayList<String> dep = args.getStringArrayList(ARG_SWITCH_DEPENDENT_IDS);
        if(dep!=null) switchDependentIds = dep;
        if(args.containsKey(ARG_USE_GRADIENT)){
            useGradientBg = args.getBoolean(ARG_USE_GRADIENT, true);
        }
    gridMode = args.getBoolean(ARG_GRID_MODE, false);
        hideCheck = args.getBoolean(ARG_HIDE_CHECK, false);
        sliderMode = args.getBoolean(ARG_SLIDER_MODE, false);
        android.util.Log.d("PickerBottomSheet", "Slider mode detected: " + sliderMode + ", title: " + title + ", resultKey: " + resultKey);
        com.fadcam.Log.d("PickerBottomSheet", "Slider mode detected: " + sliderMode + ", title: " + title + ", resultKey: " + resultKey);
        if(sliderMode){
            sliderZoomMode = args.getBoolean(ARG_SLIDER_ZOOM_MODE, false);
            zoomRatios = args.getFloatArray("zoom_ratios");
            sliderMin = args.getInt(ARG_SLIDER_MIN, 0);
            sliderMax = args.getInt(ARG_SLIDER_MAX, 0);
            sliderStep = args.getInt(ARG_SLIDER_STEP, 1);
            sliderStepFloat = args.getFloat(ARG_SLIDER_STEP_FLOAT, 1f);
            sliderInitial = args.getInt(ARG_SLIDER_INITIAL, 0);
        }
        }
        TextView titleView = view.findViewById(R.id.picker_title);
        if(titleView!=null) titleView.setText(title);
        if(useGradientBg){
            View root = view.findViewById(R.id.picker_root);
            if(root!=null){
                root.setBackgroundResource(R.drawable.picker_bottom_sheet_gradient_bg_dynamic);
            }
        }
        View divider = view.findViewById(R.id.picker_title_divider);
        if(divider!=null){
            divider.setAlpha(0f);
            divider.animate().alpha(1f).setDuration(260).start();
        }
    LinearLayout containerLayout = view.findViewById(R.id.picker_list_container);
    // If the slider layout was inflated it doesn't include picker_list_container.
    // Fallback to the slider root or the overall picker root so we can add rows
    // (switch row / dividers) without NPEs.
        if(containerLayout == null){
        View sliderRoot = view.findViewById(R.id.picker_slider_root);
        if(sliderRoot instanceof LinearLayout){
            containerLayout = (LinearLayout) sliderRoot;
        } else {
            View pickerRoot = view.findViewById(R.id.picker_root);
            if(pickerRoot instanceof LinearLayout){
                containerLayout = (LinearLayout) pickerRoot;
            }
        }
    }
        // If this fragment contains the AE Lock switch, prefer the persisted lock state so the UI
        // stays disabled/enabled consistently across openings.
        try {
            if (switchPresent && "Lock AE".equals(switchTitle) && getContext() != null) {
                boolean saved = com.fadcam.SharedPreferencesManager.getInstance(requireContext()).isAeLockedSaved();
                switchState = saved;
            }
        } catch (Exception ignored) {}
    containerLayoutRef = containerLayout;
    // Make an effectively-final reference for use inside lambdas below
    final LinearLayout listContainer = containerLayoutRef;
    // If slider mode, wire up slider controls
    if(sliderMode){
        try {
            View sliderRoot = view.findViewById(R.id.picker_slider_root);
            com.google.android.material.slider.Slider slider = view.findViewById(R.id.picker_slider);
            TextView tvVal = view.findViewById(R.id.picker_slider_value);
            TextView minus = view.findViewById(R.id.picker_slider_minus);
            TextView plus = view.findViewById(R.id.picker_slider_plus);
            android.widget.ImageView reset = view.findViewById(R.id.picker_slider_reset_icon);
            if(slider!=null){
                android.util.Log.d("PickerBottomSheet", "Setting up slider: min=" + sliderMin + ", max=" + sliderMax + ", step=" + sliderStep + ", initial=" + sliderInitial);
                com.fadcam.Log.d("PickerBottomSheet", "Setting up slider: min=" + sliderMin + ", max=" + sliderMax + ", step=" + sliderStep + ", initial=" + sliderInitial);
                // Configure slider to map to integer steps between min..max
                int steps = (sliderMax - sliderMin) / Math.max(1, sliderStep);
                slider.setValueFrom(0f);
                slider.setValueTo((float) steps);
                slider.setStepSize(1f);
                float startPos = (float) (sliderInitial - sliderMin) / Math.max(1, sliderStep);
                // clamp startPos
                if (startPos < 0f) startPos = 0f;
                if (startPos > steps) startPos = (float) steps;
                slider.setValue(startPos);
                com.fadcam.Log.d("PickerBottomSheet", "Slider configured: steps=" + steps + ", startPos=" + startPos);
                // Configure initial enabled state for +/- controls
                try {
                    int curIndex = sliderMin + Math.round(slider.getValue()) * sliderStep;
                    if (minus != null) minus.setEnabled(curIndex > sliderMin);
                    if (plus != null) plus.setEnabled(curIndex < sliderMax);
                    if (minus != null) minus.setAlpha((minus.isEnabled()) ? 1f : 0.4f);
                    if (plus != null) plus.setAlpha((plus.isEnabled()) ? 1f : 0.4f);
                } catch (Exception ignored) {}
                // Set initial text value based on mode
                if (sliderZoomMode && zoomRatios != null && sliderInitial >= 0 && sliderInitial < zoomRatios.length) {
                    tvVal.setText(String.format(java.util.Locale.US, "%.1fx", zoomRatios[sliderInitial]));
                } else {
                    tvVal.setText(String.valueOf(sliderInitial));
                }
                // Configure label row (min / mid / max)
                try {
                    TextView lblMin = view.findViewById(R.id.picker_slider_label_min);
                    TextView lblMid = view.findViewById(R.id.picker_slider_label_mid);
                    TextView lblMax = view.findViewById(R.id.picker_slider_label_max);
                    View labelsRow = view.findViewById(R.id.picker_slider_labels);
                    if (labelsRow != null && lblMin != null && lblMid != null && lblMax != null) {
                        if (sliderZoomMode && zoomRatios != null && zoomRatios.length > 0) {
                            // Show zoom min/max only (hide mid label to avoid misleading values)
                            lblMin.setText(String.format(java.util.Locale.US, "%.1fx", zoomRatios[0]));
                            lblMid.setVisibility(View.GONE);
                            lblMax.setText(String.format(java.util.Locale.US, "%.1fx", zoomRatios[zoomRatios.length-1]));
                            labelsRow.setVisibility(View.VISIBLE);
                        } else {
                            // EV mode: show min / 0 / max using sliderStepFloat
                            float evMin = sliderMin * sliderStepFloat;
                            float evMax = sliderMax * sliderStepFloat;
                            lblMin.setText(String.format(java.util.Locale.US, "%.1f", evMin));
                            lblMid.setText(String.format(java.util.Locale.US, "%.1f", 0f));
                            lblMax.setText(String.format(java.util.Locale.US, "%.1f", evMax));
                            labelsRow.setVisibility(View.VISIBLE);
                        }
                    }
                } catch (Exception ignored) {}

                slider.setLabelFormatter(value -> {
                    int intVal = sliderMin + Math.round(value) * sliderStep;
                    if (sliderZoomMode && zoomRatios != null && intVal >= 0 && intVal < zoomRatios.length) {
                        // Zoom mode: show zoom ratio with 'x' suffix
                        return String.format(java.util.Locale.US, "%.1fx", zoomRatios[intVal]);
                    } else {
                        // EV mode: show with +/- sign
                        float evFloat = intVal * sliderStepFloat;
                        String sign = evFloat > 0 ? "+" : "";
                        return sign + String.format(java.util.Locale.US, "%.1f", evFloat);
                    }
                });

                android.util.Log.d("PickerBottomSheet", "Attaching OnChangeListener to slider, resultKey=" + resultKey);
                com.fadcam.Log.d("PickerBottomSheet", "Attaching OnChangeListener to slider, resultKey=" + resultKey);
                slider.addOnChangeListener((s, value, fromUser) -> {
                    android.util.Log.d("PickerBottomSheet", "Slider onChange triggered: value=" + value + ", fromUser=" + fromUser);
                    com.fadcam.Log.d("PickerBottomSheet", "Slider onChange triggered: value=" + value + ", fromUser=" + fromUser);
                    int intVal = sliderMin + Math.round(value) * sliderStep;
                    com.fadcam.Log.d("PickerBottomSheet", "Calculated intVal=" + intVal + ", sliderMin=" + sliderMin + ", sliderStep=" + sliderStep);
                    if (sliderZoomMode && zoomRatios != null && intVal >= 0 && intVal < zoomRatios.length) {
                        // Zoom mode: show zoom ratio
                        tvVal.setText(String.format(java.util.Locale.US, "%.1fx", zoomRatios[intVal]));
                    } else {
                        // EV mode: show with +/- sign
                        float evFloat = intVal * sliderStepFloat;
                        String sign = evFloat > 0 ? "+" : "";
                        tvVal.setText(sign + String.format(java.util.Locale.US, "%.1f", evFloat));
                    }
                    // Live update: post result so callers can react immediately while recording
                    Bundle result = new Bundle();
                    result.putInt(BUNDLE_SLIDER_VALUE, intVal);
                    android.util.Log.d("PickerBottomSheet", "Picker sending fragment result: key=" + resultKey + ", sliderValue=" + intVal);
                    android.util.Log.d("PickerBottomSheet", "Using FragmentManager: " + getParentFragmentManager() + ", this fragment: " + this);
                    com.fadcam.Log.d("PickerBottomSheet", "Picker sending fragment result: key=" + resultKey + ", sliderValue=" + intVal);
                    getParentFragmentManager().setFragmentResult(resultKey, result);
                    // Disable/enable +/- buttons based on current slider position
                    try {
                        if (minus != null) minus.setEnabled(intVal > sliderMin);
                        if (plus != null) plus.setEnabled(intVal < sliderMax);
                        // reflect visual alpha for disabled state
                        if (minus != null) minus.setAlpha((minus.isEnabled()) ? 1f : 0.4f);
                        if (plus != null) plus.setAlpha((plus.isEnabled()) ? 1f : 0.4f);
                    } catch (Exception ignored) {}
                });

                // +/- buttons
                if(minus!=null){ minus.setOnClickListener(v-> { slider.setValue(Math.max(0f, slider.getValue()-1f)); }); }
                if(plus!=null){ plus.setOnClickListener(v-> { slider.setValue(Math.min(slider.getValueTo(), slider.getValue()+1f)); }); }
                if(reset!=null){ reset.setOnClickListener(v-> {
                    float resetPos;
                    if (sliderZoomMode && zoomRatios != null) {
                        // For zoom mode, reset to 1.0x
                        int resetIndex = 0;
                        for (int i = 0; i < zoomRatios.length; i++) {
                            if (Math.abs(zoomRatios[i] - 1.0f) < 0.01f) {
                                resetIndex = i;
                                break;
                            }
                        }
                        resetPos = (float)(resetIndex - sliderMin) / Math.max(1, sliderStep);
                    } else {
                        // For EV mode, reset to 0
                        resetPos = (float)(0 - sliderMin) / Math.max(1, sliderStep);
                    }
                    resetPos = Math.max(0f, Math.min(resetPos, slider.getValueTo()));
                    slider.setValue(resetPos);
                }); }
            }
        } catch (Exception ignored) {}
    }
    TextView helperView = view.findViewById(R.id.picker_helper);
        LayoutInflater li = LayoutInflater.from(view.getContext());
    // Optional switch row: the slider layout has a dedicated switch row and divider
    if(switchPresent){
        View root = view;
        View switchRow = root.findViewById(R.id.picker_switch_row);
        View switchDivider = root.findViewById(R.id.picker_switch_divider);
        androidx.appcompat.widget.SwitchCompat swc = root.findViewById(R.id.picker_switch);
        TextView switchLabel = root.findViewById(R.id.picker_switch_label);
        if(switchRow!=null && swc!=null && switchLabel!=null){
            switchRow.setVisibility(View.VISIBLE);
            // Only show divider if there are items below the switch (not for switch-only bottom sheets)
            if(switchDivider!=null && !items.isEmpty()) switchDivider.setVisibility(View.VISIBLE);
            switchLabel.setText(switchTitle);
            swc.setChecked(switchState);
            switchRef = swc;
            // initial apply to slider controls
            try {
                com.google.android.material.slider.Slider s = root.findViewById(R.id.picker_slider);
                TextView tvVal = root.findViewById(R.id.picker_slider_value);
                TextView minus = root.findViewById(R.id.picker_slider_minus);
                TextView plus = root.findViewById(R.id.picker_slider_plus);
                android.widget.ImageView reset = root.findViewById(R.id.picker_slider_reset_icon);
                if(s!=null){ s.setEnabled(!switchState); s.setAlpha(switchState?0.4f:1f); }
                if(tvVal!=null) tvVal.setAlpha(switchState?0.4f:1f);
                if(minus!=null) { minus.setEnabled(!switchState); minus.setClickable(!switchState); minus.setAlpha(switchState?0.4f:1f); }
                if(plus!=null) { plus.setEnabled(!switchState); plus.setClickable(!switchState); plus.setAlpha(switchState?0.4f:1f); }
                if(reset!=null) { reset.setEnabled(!switchState); reset.setClickable(!switchState); reset.setAlpha(switchState?0.4f:1f); }
            } catch (Exception ignored) {}
            swc.setOnCheckedChangeListener((b,checked) -> {
                // send fragment result - use RK_AE_LOCK for AE Lock switch
                Bundle result = new Bundle();
                result.putBoolean(BUNDLE_SWITCH_STATE, checked);
                String switchResultKey = "Lock AE".equals(switchTitle) ? com.fadcam.Constants.RK_AE_LOCK : resultKey;
                getParentFragmentManager().setFragmentResult(switchResultKey, result);
                // Gray out slider controls when AE lock is on
                try {
                    com.google.android.material.slider.Slider s = root.findViewById(R.id.picker_slider);
                    TextView tvVal = root.findViewById(R.id.picker_slider_value);
                    TextView minus = root.findViewById(R.id.picker_slider_minus);
                    TextView plus = root.findViewById(R.id.picker_slider_plus);
                    android.widget.ImageView reset = root.findViewById(R.id.picker_slider_reset_icon);
                    if(s!=null){ s.setEnabled(!checked); s.setAlpha(checked?0.4f:1f); }
                    if(tvVal!=null) tvVal.setAlpha(checked?0.4f:1f);
                    if(minus!=null) { minus.setEnabled(!checked); minus.setClickable(!checked); minus.setAlpha(checked?0.4f:1f); }
                    if(plus!=null) { plus.setEnabled(!checked); plus.setClickable(!checked); plus.setAlpha(checked?0.4f:1f); }
                    if(reset!=null) { reset.setEnabled(!checked); reset.setClickable(!checked); reset.setAlpha(checked?0.4f:1f); }
                } catch (Exception ignored) {}
                updateDependentRows(checked);
            });
        }
    }
    if(gridMode){
        buildGrid(containerLayout, view);
    } else {
        int index=0; int last = items.size()-1;
        for(OptionItem item: items){
            View row = li.inflate(R.layout.picker_bottom_sheet_item, containerLayout, false);
            TextView tvTitle = row.findViewById(R.id.picker_item_title);
            TextView tvSubtitle = row.findViewById(R.id.picker_item_subtitle);
            TextView tvBadge = row.findViewById(R.id.picker_item_badge);
            View colorSwatch = row.findViewById(R.id.picker_item_color_swatch);
            View checkContainer = row.findViewById(R.id.picker_item_check_container);
            ImageView checkIcon = row.findViewById(R.id.picker_item_check);
            ImageView leadingIcon = row.findViewById(R.id.picker_item_leading_icon);
            TextView leadingSymbol = row.findViewById(R.id.picker_item_leading_symbol);
            ImageView trailingIcon = row.findViewById(R.id.picker_item_trailing_icon);
            androidx.appcompat.widget.SwitchCompat itemSwitch = row.findViewById(R.id.picker_item_switch);
            row.setTag(item.id); // tag row with its id for dependency handling
            tvTitle.setText(item.title);
            // Danger styling for destructive actions
            if("action_delete".equals(item.id)){
                try {
                    row.setBackgroundResource(R.drawable.settings_home_row_bg);
                    // overlay red tint for danger row
                    row.getBackground().mutate();
                    row.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x33FF3B30));
                } catch (Exception ignored) {}
            }
            // Badge rendering
            if(tvBadge!=null){
                if(item.badgeText!=null && !item.badgeText.isEmpty()){
                    tvBadge.setText(item.badgeText);
                    if(item.badgeBgResId!=null){ tvBadge.setBackgroundResource(item.badgeBgResId); }
                    tvBadge.setVisibility(View.VISIBLE);
                } else { tvBadge.setVisibility(View.GONE); }
            }
            
            // Handle switch vs subtitle display
            if(item.hasSwitch != null && item.hasSwitch){
                // Show switch instead of subtitle
                tvSubtitle.setVisibility(View.GONE);
                itemSwitch.setVisibility(View.VISIBLE);
                itemSwitch.setChecked(item.switchState != null && item.switchState);
                // Make the switch functional but prevent row click
                itemSwitch.setOnCheckedChangeListener((switchView, isChecked) -> {
                    // Handle dependencies: if this is show_date, control date_format availability
                    if("show_date".equals(item.id)) {
                        updateDateFormatDependency(isChecked);
                    }
                    // Handle dependencies: if this is arabic_date, control arabic_date_format availability
                    else if("arabic_date".equals(item.id)) {
                        updateArabicDateFormatDependency(isChecked);
                    }
                    
                    // Post switch result without dismissing
                    Bundle result = new Bundle();
                    result.putString(BUNDLE_SELECTED_ID, item.id);
                    getParentFragmentManager().setFragmentResult(resultKey, result);
                });
            } else {
                // Show normal subtitle
                itemSwitch.setVisibility(View.GONE);
                if(item.subtitle!=null && !item.subtitle.isEmpty()){
                    // If disabled, we don't display helper text in row (but keep it for toast)
                    if(item.disabled != null && item.disabled){
                        tvSubtitle.setVisibility(View.GONE);
                    } else {
                        tvSubtitle.setText(item.subtitle);
                        tvSubtitle.setVisibility(View.VISIBLE);
                    }
                } else { 
                    tvSubtitle.setVisibility(View.GONE); 
                }
            }
            if(leadingSymbol!=null && item.iconLigature != null){
                // Lazy-load materialicons.ttf from res/font
                if(MATERIAL_ICONS_TF == null){
                    try {
                        MATERIAL_ICONS_TF = androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.materialicons);
                    } catch (Exception e){
                        MATERIAL_ICONS_TF = android.graphics.Typeface.DEFAULT;
                    }
                }
                leadingSymbol.setTypeface(MATERIAL_ICONS_TF);
                leadingSymbol.setText(item.iconLigature);
                leadingSymbol.setVisibility(View.VISIBLE);
                if(leadingIcon!=null) leadingIcon.setVisibility(View.GONE);
            } else if(leadingIcon!=null){
                if(item.iconResId!=null){ 
                    leadingIcon.setImageResource(item.iconResId);
                    leadingIcon.setImageTintList(null);
                    leadingIcon.setVisibility(View.VISIBLE);
                } else { 
                    leadingIcon.setVisibility(View.GONE);
                } 
            }
            if(trailingIcon!=null){
                if(item.trailingIconResId!=null){ trailingIcon.setImageResource(item.trailingIconResId); trailingIcon.setVisibility(View.VISIBLE);} else { trailingIcon.setVisibility(View.GONE);} }
            if(colorSwatch!=null){
                if(item.colorInt!=null){
                    android.graphics.drawable.GradientDrawable gd = (android.graphics.drawable.GradientDrawable)colorSwatch.getBackground();
                    gd.setColor(item.colorInt);
                    // If color perceived as dark, add white stroke for contrast
                    int r = (item.colorInt >> 16) & 0xFF;
                    int g = (item.colorInt >> 8) & 0xFF;
                    int b = item.colorInt & 0xFF;
                    double luminance = (0.299*r + 0.587*g + 0.114*b) / 255.0;
                    if(luminance < 0.25){ gd.setStroke((int)(1 * getResources().getDisplayMetrics().density), 0xFFFFFFFF); }
                    else { gd.setStroke(0, 0); }
                    colorSwatch.setVisibility(View.VISIBLE);
                } else {
                    colorSwatch.setVisibility(View.GONE);
                }
            }
            boolean isSel = item.id!=null && item.id.equals(selectedId);
            if(isSel){
                if(hideCheck){
                    checkContainer.setVisibility(View.GONE);
                } else {
                    checkContainer.setVisibility(View.VISIBLE);
                    checkIcon.setScaleX(1f); checkIcon.setScaleY(1f); checkIcon.setAlpha(1f);
                }
            } else {
                // If hideCheck or a trailing icon is present (confirmation style), drop the check container entirely to align trailing icon flush right
                if(hideCheck || (trailingIcon!=null && trailingIcon.getVisibility()==View.VISIBLE)){
                    checkContainer.setVisibility(View.GONE);
                } else {
                    checkContainer.setVisibility(View.INVISIBLE); // keep space to avoid layout shift
                }
                checkIcon.setScaleX(0f); checkIcon.setScaleY(0f); checkIcon.setAlpha(0f);
            }
            // Apply disabled visual state
            if(item.disabled != null && item.disabled){
                row.setEnabled(false);
                row.setAlpha(0.5f);
                tvTitle.setTextColor(0xFFAAAAAA);
                tvSubtitle.setTextColor(0xFF777777);
                if(leadingSymbol!=null){ leadingSymbol.setTextColor(0xFFAAAAAA); }
                if(leadingIcon!=null){ leadingIcon.setColorFilter(0xFFAAAAAA); }
            }

            row.setOnClickListener(v -> {
                // Don't handle click for switch items - they handle their own toggle
                if(item.hasSwitch != null && item.hasSwitch) {
                    return;
                }
                // If disabled, show a subtle bounce and optional toast via subtitle, but don't select
                if(item.disabled != null && item.disabled){
                    v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(80).withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(80)).start();
                    try {
                        CharSequence msg = (item.subtitle!=null && !item.subtitle.isEmpty()) ? item.subtitle : getString(R.string.remote_toast_coming_soon);
                        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Exception ignored) {}
                    // Still send a result for disabled? No, ignore selection
                    return;
                }
                
                // Clear old selection visual
                if(!hideCheck){
                    int childCount = listContainer.getChildCount();
                    for(int i=0;i<childCount;i++){
                        View child = listContainer.getChildAt(i);
                        View cc = child.findViewById(R.id.picker_item_check_container);
                        ImageView ci = child.findViewById(R.id.picker_item_check);
                        if(cc!=null && ci!=null){
                            cc.setVisibility(View.INVISIBLE);
                            ci.setScaleX(0f); ci.setScaleY(0f); ci.setAlpha(0f);
                        }
                    }
                    // Animate new selection
                    checkContainer.setVisibility(View.VISIBLE);
                    AnimatorSet set = new AnimatorSet();
                    ObjectAnimator sx = ObjectAnimator.ofFloat(checkIcon, View.SCALE_X, 0f, 1f);
                    ObjectAnimator sy = ObjectAnimator.ofFloat(checkIcon, View.SCALE_Y, 0f, 1f);
                    ObjectAnimator a = ObjectAnimator.ofFloat(checkIcon, View.ALPHA, 0f, 1f);
                    sx.setDuration(140); sy.setDuration(140); a.setDuration(140);
                    set.playTogether(sx, sy, a);
                    set.start();
                }
                // Post result then dismiss slightly later
                Bundle result = new Bundle();
                result.putString(BUNDLE_SELECTED_ID, item.id);
                getParentFragmentManager().setFragmentResult(resultKey, result);
                row.postDelayed(() -> { if(isAdded()) dismissAllowingStateLoss(); }, 160);
            });
            containerLayout.addView(row);
            // Add divider between rows replicating settings group style
            if(index<last){
                View rowDivider = new View(view.getContext());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
                // -------------- Fix Start for this method(onViewCreated)-----------
                // Add horizontal margins to match SettingsDivider (14dp start, 12dp end)
                // Row now has 12dp outer margins + 14dp inner start and 12dp inner end paddings
                // Keep divider aligned with inner paddings, but also inset by the outer margins
                lp.setMargins(dp(14)+dp(12), dp(2), dp(12)+dp(12), dp(2));
                // -------------- Fix Ended for this method(onViewCreated)-----------
                rowDivider.setLayoutParams(lp);
                rowDivider.setBackgroundColor(0x33FFFFFF);
                containerLayout.addView(rowDivider);
            }
            index++;
        }
    }
        // Only show helper if the caller explicitly provided one
        if(helperView!=null && helperText!=null && !helperText.isEmpty()){
            helperView.setText(helperText);
            helperView.setVisibility(View.VISIBLE);
        }
        // Apply initial dependent disable state
        if(switchPresent){ updateDependentRows(switchState); }
        
    // Apply initial dependency states
        applyInitialDateFormatDependency();
        applyInitialArabicDateFormatDependency();
        
        // Apply Snow Veil theme text color fixes for dark backgrounds
        applySnowVeilThemeTextFixes(view);
        // -------------- Fix Ended for this method(onViewCreated)-----------
    }

    // -------------- Fix Start for this method(dp)-----------
    private int dp(int value){
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
    // -------------- Fix Ended for this method(dp)-----------

    private void buildGrid(LinearLayout containerLayout, View root){
        // Build a simple wrapping grid manually (3 columns)
        int columns = 3;
        LinearLayout currentRow = null;
        int count=0;
        for(OptionItem item: items){
            if(currentRow==null || count % columns ==0){
                currentRow = new LinearLayout(requireContext());
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                currentRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                currentRow.setPadding(0,8,0,8);
                containerLayout.addView(currentRow);
            }
            View cell = LayoutInflater.from(requireContext()).inflate(R.layout.picker_bottom_sheet_icon_grid_item, currentRow, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            cell.setLayoutParams(lp);
            android.widget.ImageView icon = cell.findViewById(R.id.icon_image);
            View iconRoot = cell.findViewById(R.id.icon_root_frame);
            View checkContainer = cell.findViewById(R.id.icon_check_container);
            android.widget.ImageView check = cell.findViewById(R.id.icon_check);
            android.widget.TextView label = cell.findViewById(R.id.icon_label);
            label.setText(item.title);
            if(item.iconResId!=null){ 
                icon.setImageResource(item.iconResId);
                icon.setImageTintList(null);
            }
            boolean isSel = item.id!=null && item.id.equals(selectedId);
            if(checkContainer!=null){
                if(isSel){
                    checkContainer.setVisibility(View.VISIBLE);
                    if(check!=null){ check.setScaleX(1f); check.setScaleY(1f); check.setAlpha(1f); }
                } else {
                    checkContainer.setVisibility(View.GONE);
                    if(check!=null){ check.setScaleX(0f); check.setScaleY(0f); check.setAlpha(0f); }
                }
            }
            if(iconRoot!=null){
                if(isSel){
                    // Apply a subtle accent stroke
                    android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                    bg.setColor(0x00000000); // transparent fill (background already present)
                    bg.setStroke((int)(2*getResources().getDisplayMetrics().density), 0xFF9CCC65);
                    bg.setCornerRadius(24f);
                    iconRoot.setForeground(bg);
                } else {
                    iconRoot.setForeground(null);
                }
            }
            cell.setOnClickListener(v -> {
                // clear previous
                for(int i=0;i<containerLayout.getChildCount();i++){
                    View row = containerLayout.getChildAt(i);
                    if(row instanceof LinearLayout){
                        LinearLayout lr = (LinearLayout) row;
                        for(int j=0;j<lr.getChildCount();j++){
                            View c = lr.getChildAt(j);
                            View cc = c.findViewById(R.id.icon_check_container);
                            View ir = c.findViewById(R.id.icon_root_frame);
                            if(cc!=null) cc.setVisibility(View.GONE);
                            if(ir!=null) ir.setForeground(null);
                        }
                    }
                }
                if(checkContainer!=null){
                    checkContainer.setVisibility(View.VISIBLE);
                    if(check!=null){
                        check.setScaleX(0f); check.setScaleY(0f); check.setAlpha(0f);
                        android.animation.AnimatorSet set = new android.animation.AnimatorSet();
                        android.animation.ObjectAnimator sx = android.animation.ObjectAnimator.ofFloat(check, View.SCALE_X, 0f, 1f);
                        android.animation.ObjectAnimator sy = android.animation.ObjectAnimator.ofFloat(check, View.SCALE_Y, 0f, 1f);
                        android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofFloat(check, View.ALPHA, 0f, 1f);
                        sx.setDuration(140); sy.setDuration(140); a.setDuration(140);
                        set.playTogether(sx, sy, a);
                        set.start();
                    }
                }
                if(iconRoot!=null){
                    android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                    bg.setColor(0x00000000);
                    bg.setStroke((int)(2*getResources().getDisplayMetrics().density), 0xFF9CCC65);
                    bg.setCornerRadius(24f);
                    iconRoot.setForeground(bg);
                }
                Bundle result = new Bundle();
                result.putString(BUNDLE_SELECTED_ID, item.id);
                getParentFragmentManager().setFragmentResult(resultKey, result);
                cell.postDelayed(() -> { if(isAdded()) dismissAllowingStateLoss(); }, 130);
            });
            currentRow.addView(cell);
            count++;
        }
    }

    // -------------- Fix Start for this method(updateDependentRows)-----------
    private void updateDependentRows(boolean enabled){
        if(containerLayoutRef==null || switchDependentIds.isEmpty()) return;
        int childCount = containerLayoutRef.getChildCount();
        for(int i=0;i<childCount;i++){
            View child = containerLayoutRef.getChildAt(i);
            Object tag = child.getTag();
            if(tag instanceof String && switchDependentIds.contains(tag)){
                boolean active = enabled;
                child.setEnabled(active);
                child.setAlpha(active?1f:0.4f);
                if(active){
                    // Rebind click listener to ensure functionality after re-enable
                    String id = (String) tag;
                    OptionItem bound = null;
                    for(OptionItem oi: items){ if(oi.id!=null && oi.id.equals(id)){ bound = oi; break; } }
                    if(bound!=null){
                        final OptionItem item = bound;
                        // Replace listener
                        child.setOnClickListener(v -> {
                            // Clear previous selection visuals
                            int total = containerLayoutRef.getChildCount();
                            for(int c=0;c<total;c++){
                                View possible = containerLayoutRef.getChildAt(c);
                                View cc = possible.findViewById(R.id.picker_item_check_container);
                                ImageView ci = possible.findViewById(R.id.picker_item_check);
                                if(cc!=null && ci!=null){
                                    cc.setVisibility(View.INVISIBLE);
                                    ci.setScaleX(0f); ci.setScaleY(0f); ci.setAlpha(0f);
                                }
                            }
                            View checkContainer = child.findViewById(R.id.picker_item_check_container);
                            ImageView checkIcon = child.findViewById(R.id.picker_item_check);
                            if(checkContainer!=null && checkIcon!=null){
                                checkContainer.setVisibility(View.VISIBLE);
                                AnimatorSet set = new AnimatorSet();
                                ObjectAnimator sx = ObjectAnimator.ofFloat(checkIcon, View.SCALE_X, 0f, 1f);
                                ObjectAnimator sy = ObjectAnimator.ofFloat(checkIcon, View.SCALE_Y, 0f, 1f);
                                ObjectAnimator a = ObjectAnimator.ofFloat(checkIcon, View.ALPHA, 0f, 1f);
                                sx.setDuration(140); sy.setDuration(140); a.setDuration(140);
                                set.playTogether(sx, sy, a);
                                set.start();
                            }
                            Bundle result = new Bundle();
                            result.putString(BUNDLE_SELECTED_ID, item.id);
                            getParentFragmentManager().setFragmentResult(resultKey, result);
                            child.postDelayed(this::dismiss, 160);
                        });
                    }
                }
            }
            // If slider mode and switchPresent, wire switch row (optional AE lock switch)
            try {
                View root = getView();
                if (root != null) {
                    View switchRow = root.findViewById(R.id.picker_switch_row);
                    TextView switchLabel = root.findViewById(R.id.picker_switch_label);
                    androidx.appcompat.widget.SwitchCompat swc = root.findViewById(R.id.picker_switch);
                    if(switchPresent && switchRow!=null && switchLabel!=null && swc!=null){
                        switchRow.setVisibility(View.VISIBLE);
                        switchLabel.setText(switchTitle);
                        swc.setChecked(switchState);
                        swc.setOnCheckedChangeListener((b, checked) -> {
                            Bundle result = new Bundle();
                            result.putBoolean(BUNDLE_SWITCH_STATE, checked);
                            getParentFragmentManager().setFragmentResult(resultKey, result);
                            // Gray out slider controls when switch (AE lock) is on
                            try {
                                com.google.android.material.slider.Slider s = root.findViewById(R.id.picker_slider);
                                TextView tvVal = root.findViewById(R.id.picker_slider_value);
                                TextView minus = root.findViewById(R.id.picker_slider_minus);
                                TextView plus = root.findViewById(R.id.picker_slider_plus);
                                android.widget.ImageView reset = root.findViewById(R.id.picker_slider_reset_icon);
                                if(s!=null){ s.setEnabled(!checked); s.setAlpha(checked?0.4f:1f); }
                                if(tvVal!=null) tvVal.setAlpha(checked?0.4f:1f);
                                if(minus!=null) minus.setAlpha(checked?0.4f:1f); if(plus!=null) plus.setAlpha(checked?0.4f:1f); if(reset!=null) reset.setAlpha(checked?0.4f:1f);
                            } catch (Exception ignored) {}
                        });
                        // Apply initial disabled state
                        if(switchState){
                            try { com.google.android.material.slider.Slider s = root.findViewById(R.id.picker_slider); if(s!=null){ s.setEnabled(false); s.setAlpha(0.4f); } } catch (Exception ignored) {}
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }
    // -------------- Fix Ended for this method(updateDependentRows)-----------
    
    // Branding dependency removed: branding is independent of background
    
    /**
     * Update date format dependency based on show date state
     */
    private void updateDateFormatDependency(boolean showDateEnabled) {
        if(containerLayoutRef == null) return;
        
        int childCount = containerLayoutRef.getChildCount();
        for(int i = 0; i < childCount; i++) {
            View child = containerLayoutRef.getChildAt(i);
            Object tag = child.getTag();
            if("date_format".equals(tag)) {
                child.setEnabled(showDateEnabled);
                child.setAlpha(showDateEnabled ? 1f : 0.4f);
                break;
            }
        }
    }
    
    /**
     * Apply initial date format dependency state based on current show date setting
     */
    private void applyInitialDateFormatDependency() {
        if(containerLayoutRef == null) return;
        
        // Find show date switch state
        boolean showDateEnabled = false;
        int childCount = containerLayoutRef.getChildCount();
        for(int i = 0; i < childCount; i++) {
            View child = containerLayoutRef.getChildAt(i);
            Object tag = child.getTag();
            if("show_date".equals(tag)) {
                androidx.appcompat.widget.SwitchCompat dateSwitch = child.findViewById(R.id.picker_item_switch);
                if(dateSwitch != null) {
                    showDateEnabled = dateSwitch.isChecked();
                }
                break;
            }
        }
        
        // Apply dependency to date format option
        updateDateFormatDependency(showDateEnabled);
    }
    
    /**
     * Update Arabic date format dependency based on Arabic date state
     */
    private void updateArabicDateFormatDependency(boolean arabicDateEnabled) {
        if(containerLayoutRef == null) return;
        
        int childCount = containerLayoutRef.getChildCount();
        for(int i = 0; i < childCount; i++) {
            View child = containerLayoutRef.getChildAt(i);
            Object tag = child.getTag();
            if("arabic_date_format".equals(tag)) {
                child.setEnabled(arabicDateEnabled);
                child.setAlpha(arabicDateEnabled ? 1f : 0.4f);
                break;
            }
        }
    }
    
    /**
     * Apply initial Arabic date format dependency state based on current Arabic date setting
     */
    private void applyInitialArabicDateFormatDependency() {
        if(containerLayoutRef == null) return;
        
        // Find Arabic date switch state
        boolean arabicDateEnabled = false;
        int childCount = containerLayoutRef.getChildCount();
        for(int i = 0; i < childCount; i++) {
            View child = containerLayoutRef.getChildAt(i);
            Object tag = child.getTag();
            if("arabic_date".equals(tag)) {
                androidx.appcompat.widget.SwitchCompat arabicDateSwitch = child.findViewById(R.id.picker_item_switch);
                if(arabicDateSwitch != null) {
                    arabicDateEnabled = arabicDateSwitch.isChecked();
                }
                break;
            }
        }
        
        // Apply dependency to Arabic date format option
        updateArabicDateFormatDependency(arabicDateEnabled);
    }
    
    /**
     * Apply Snow Veil theme text color fixes for dark backgrounds in bottom pickers
     */
    private void applySnowVeilThemeTextFixes(View view) {
        try {
            // Check if we're using Snow Veil theme
            if (getContext() == null) return;
            
            String currentTheme = com.fadcam.SharedPreferencesManager.getInstance(getContext())
                    .sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME, com.fadcam.Constants.DEFAULT_APP_THEME);
            
            if (!"Snow Veil".equals(currentTheme)) return;
            
            // Fix text colors for Snow Veil theme on dark backgrounds
            // The picker has dark areas where we need white text instead of black
            
            // Fix slider labels and values (exposure compensation numbers)
            TextView sliderValue = view.findViewById(R.id.picker_slider_value);
            TextView minusButton = view.findViewById(R.id.picker_slider_minus);
            TextView plusButton = view.findViewById(R.id.picker_slider_plus);
            
            if (sliderValue != null) {
                sliderValue.setTextColor(android.graphics.Color.WHITE);
            }
            if (minusButton != null) {
                minusButton.setTextColor(android.graphics.Color.WHITE);
            }
            if (plusButton != null) {
                plusButton.setTextColor(android.graphics.Color.WHITE);
            }
            
            // Fix switch label (Lock AE text)
            TextView switchLabel = view.findViewById(R.id.picker_switch_label);
            if (switchLabel != null) {
                switchLabel.setTextColor(android.graphics.Color.WHITE);
            }
            
            // Fix helper text at bottom
            TextView helperText = view.findViewById(R.id.picker_helper);
            if (helperText != null) {
                helperText.setTextColor(android.graphics.Color.parseColor("#E0E0E0")); // Light gray for better readability
            }
            
            // Fix any other text views in the picker that might be black on dark background
            fixTextViewsRecursively(view);
            
        } catch (Exception e) {
            // Silently handle any errors to avoid crashes
            android.util.Log.w("PickerBottomSheet", "Error applying Snow Veil theme fixes", e);
        }
    }
    
    /**
     * Recursively fix text colors in Snow Veil theme for dark backgrounds
     */
    private void fixTextViewsRecursively(View view) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                fixTextViewsRecursively(group.getChildAt(i));
            }
        } else if (view instanceof TextView) {
            TextView textView = (TextView) view;
            // Check if this TextView has black text (which would be unreadable on dark background)
            int currentColor = textView.getCurrentTextColor();
            if (currentColor == android.graphics.Color.BLACK || 
                currentColor == android.graphics.Color.parseColor("#000000") ||
                (android.graphics.Color.red(currentColor) < 50 && 
                 android.graphics.Color.green(currentColor) < 50 && 
                 android.graphics.Color.blue(currentColor) < 50)) {
                // This is dark text, make it white for Snow Veil theme
                textView.setTextColor(android.graphics.Color.WHITE);
            }
        }
    }
}
