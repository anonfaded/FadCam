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


    private ArrayList<OptionItem> items = new ArrayList<>();
    private String selectedId;
    private String title;
    private String resultKey;
    private String helperText;
    private boolean switchPresent = false; private String switchTitle; private boolean switchState;
    private ArrayList<String> switchDependentIds = new ArrayList<>();
    private LinearLayout containerLayoutRef; private android.widget.Switch switchRef;
    private boolean useGradientBg = true; // default enabled globally
    private boolean gridMode = false;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.picker_bottom_sheet, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // -------------- Fix Start for this method(onViewCreated)-----------
        Bundle args = getArguments();
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
        }
        TextView titleView = view.findViewById(R.id.picker_title);
        if(titleView!=null) titleView.setText(title);
        if(useGradientBg){
            View root = view.findViewById(R.id.picker_root);
            if(root!=null){
                root.setBackgroundResource(R.drawable.picker_bottom_sheet_gradient_bg);
            }
        }
        View divider = view.findViewById(R.id.picker_title_divider);
        if(divider!=null){
            divider.setAlpha(0f);
            divider.animate().alpha(1f).setDuration(260).start();
        }
    LinearLayout containerLayout = view.findViewById(R.id.picker_list_container);
    containerLayoutRef = containerLayout;
    TextView helperView = view.findViewById(R.id.picker_helper);
        LayoutInflater li = LayoutInflater.from(view.getContext());
    // Optional switch row
    if(switchPresent){
        LinearLayout switchRow = new LinearLayout(requireContext());
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        switchRow.setPadding(4, 12, 4, 12);
    TextView label = new TextView(requireContext());
        label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        label.setText(switchTitle);
    label.setTextColor(getResources().getColor(android.R.color.white, requireContext().getTheme()));
        label.setTextSize(16f);
        label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
        android.widget.Switch sw = new android.widget.Switch(requireContext());
        sw.setChecked(switchState);
        switchRef = sw;
        sw.setOnCheckedChangeListener((btn,checked)->{
            Bundle result = new Bundle();
            result.putBoolean(BUNDLE_SWITCH_STATE, checked);
            getParentFragmentManager().setFragmentResult(resultKey, result);
            updateDependentRows(checked);
        });
        switchRow.addView(label);
        switchRow.addView(sw);
        containerLayout.addView(switchRow);
        if(!items.isEmpty()){
            View switchDivider = new View(view.getContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
            switchDivider.setLayoutParams(lp);
            switchDivider.setBackgroundColor(0x33FFFFFF);
            containerLayout.addView(switchDivider);
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
            View colorSwatch = row.findViewById(R.id.picker_item_color_swatch);
            View checkContainer = row.findViewById(R.id.picker_item_check_container);
            ImageView checkIcon = row.findViewById(R.id.picker_item_check);
            row.setTag(item.id); // tag row with its id for dependency handling
            tvTitle.setText(item.title);
            if(item.subtitle!=null && !item.subtitle.isEmpty()){
                tvSubtitle.setText(item.subtitle);
                tvSubtitle.setVisibility(View.VISIBLE);
            } else { tvSubtitle.setVisibility(View.GONE); }
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
                checkContainer.setVisibility(View.VISIBLE);
                checkIcon.setScaleX(1f); checkIcon.setScaleY(1f); checkIcon.setAlpha(1f);
            } else {
                checkContainer.setVisibility(View.INVISIBLE); // keep space to avoid layout shift
                checkIcon.setScaleX(0f); checkIcon.setScaleY(0f); checkIcon.setAlpha(0f);
            }
            row.setOnClickListener(v -> {
                // Clear old selection visual
                int childCount = containerLayout.getChildCount();
                for(int i=0;i<childCount;i++){
                    View child = containerLayout.getChildAt(i);
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
                rowDivider.setLayoutParams(lp);
                rowDivider.setBackgroundColor(0x33FFFFFF);
                containerLayout.addView(rowDivider);
            }
            index++;
        }
    }
        if(helperView!=null && helperText!=null && !helperText.isEmpty()){
            helperView.setText(helperText);
            helperView.setVisibility(View.VISIBLE);
        }
        // Apply initial dependent disable state
        if(switchPresent){ updateDependentRows(switchState); }
        // -------------- Fix Ended for this method(onViewCreated)-----------
    }

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
            if(item.iconResId!=null){ icon.setImageResource(item.iconResId); }
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
        }
    }
    // -------------- Fix Ended for this method(updateDependentRows)-----------
}
