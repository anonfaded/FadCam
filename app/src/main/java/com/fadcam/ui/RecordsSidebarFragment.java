package com.fadcam.ui;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.fadcam.R;
import com.fadcam.ui.picker.OptionItem;
import com.fadcam.ui.picker.PickerBottomSheetFragment;
import androidx.fragment.app.DialogFragment;
import com.google.android.material.sidesheet.SideSheetDialog;
import com.fadcam.SharedPreferencesManager;

import java.util.ArrayList;

/**
 * RecordsSidebarFragment
 * Side overlay with settings-style grouped rows for Records options (sort, delete all).
 */
public class RecordsSidebarFragment extends DialogFragment {

    private static final String ARG_SELECTED_SORT_ID = "selected_sort_id";
    private String resultKey = "records_sidebar_result";
    private String selectedSortId;
    private int currentGridSpan = 2;

    public static RecordsSidebarFragment newInstance(String selectedSortId){
        RecordsSidebarFragment f = new RecordsSidebarFragment();
        Bundle b = new Bundle();
        b.putString(ARG_SELECTED_SORT_ID, selectedSortId);
        f.setArguments(b);
        return f;
    }

    public static RecordsSidebarFragment newInstance(String selectedSortId, int gridSpan){
        RecordsSidebarFragment f = new RecordsSidebarFragment();
        Bundle b = new Bundle();
        b.putString(ARG_SELECTED_SORT_ID, selectedSortId);
        b.putInt("grid_span", gridSpan);
        f.setArguments(b);
        return f;
    }

    public void setResultKey(String key){ this.resultKey = key; }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        // Create a Material SideSheetDialog to host the sidebar content
        SideSheetDialog dialog = new SideSheetDialog(requireContext());
        // Make window background fully transparent so our gradient shape shows without gray corners
        if (dialog.getWindow() != null) {
            android.view.Window window = dialog.getWindow();
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            // Remove decor view padding/insets that can cause gray strips
            android.view.View decor = window.getDecorView();
            if (decor instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) decor).setPadding(0, 0, 0, 0);
                decor.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            }
        }
    // No-op: window background already transparent; layout clips to outline.
        return dialog;
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Inflate the side sheet content (modal side sheet provided by Material components)
        return inflater.inflate(R.layout.fragment_records_sidebar, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if(getArguments()!=null){
            selectedSortId = getArguments().getString(ARG_SELECTED_SORT_ID, "latest");
            currentGridSpan = getArguments().getInt("grid_span", 2);
        }

        // Handle close button
        ImageView closeButton = view.findViewById(R.id.records_sidebar_close_btn);
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> dismiss());
        }

        // Sort row container opens unified picker
        View sortRow = view.findViewById(R.id.row_sort);
        TextView sortSubtitle = view.findViewById(R.id.row_sort_subtitle);
        updateSortSubtitle(sortSubtitle, selectedSortId);
        if(sortRow!=null){
            sortRow.setOnClickListener(v -> openSortPicker());
        }

        // Delete all row
        View deleteRow = view.findViewById(R.id.row_delete_all);
        if(deleteRow!=null){
            deleteRow.setOnClickListener(v -> {
                Bundle b = new Bundle();
                b.putString("action", "delete_all");
                getParentFragmentManager().setFragmentResult(resultKey, b);
                dismiss();
            });
        }

        // View mode row - open a picker instead of direct toggle for consistency with other rows
        View viewModeRow = view.findViewById(R.id.row_view_mode);
        TextView viewModeSub = view.findViewById(R.id.row_view_mode_subtitle);
        android.widget.ImageView viewModeIcon = view.findViewById(R.id.row_view_mode_icon);
        if(viewModeSub!=null){ viewModeSub.setText(getGridSpanLabel(currentGridSpan)); }
        if(viewModeIcon!=null){ viewModeIcon.setImageResource(R.drawable.ic_grid); }
        if(viewModeRow!=null){
            viewModeRow.setOnClickListener(v -> openViewModePicker());
        }

        // Hide Thumbnails row and switch - update state label and keep toggle behavior
        View hideRow = view.findViewById(R.id.row_hide_thumbnails);
        androidx.appcompat.widget.SwitchCompat hideSwitch = view.findViewById(R.id.row_hide_thumbnails_switch);
        TextView hideState = view.findViewById(R.id.row_hide_thumbnails_state);
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(requireContext());
        boolean currentHide = prefs.isHideThumbnailsEnabled();
        if (hideSwitch != null) {
            hideSwitch.setChecked(currentHide);
            if(hideState!=null){ hideState.setText(currentHide ? getString(R.string.enabled) : getString(R.string.disabled)); }
            hideSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.setHideThumbnailsEnabled(isChecked);
                if(hideState!=null){ hideState.setText(isChecked ? getString(R.string.enabled) : getString(R.string.disabled)); }
                // Notify parent fragment to update UI
                Bundle b = new Bundle();
                b.putString("action", "hide_thumbnails_toggled");
                b.putBoolean("hide_thumbnails", isChecked);
                getParentFragmentManager().setFragmentResult(resultKey, b);
            });
        }
    // Intentionally do not set a click listener on the whole row so only the Switch toggles the setting.

    }

    private void openViewModePicker(){
        final String pickerKey = "records_view_mode_picker";
        ArrayList<OptionItem> options = new ArrayList<>();
        options.add(OptionItem.withLigature("view_1", getString(R.string.records_grid_1), "view_list"));
        options.add(OptionItem.withLigature("view_2", getString(R.string.records_grid_2), "grid_view"));
        options.add(OptionItem.withLigature("view_3", getString(R.string.records_grid_3), "grid_view"));
        options.add(OptionItem.withLigature("view_4", getString(R.string.records_grid_4), "grid_view"));
        options.add(OptionItem.withLigature("view_5", getString(R.string.records_grid_5), "grid_view"));
        String selectedId = "view_" + currentGridSpan;

        getParentFragmentManager().setFragmentResultListener(pickerKey, this, (k, bundle) -> {
            String selId = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if(selId!=null){
                int span = 2; // default
                switch (selId) {
                    case "view_1": span = 1; break;
                    case "view_2": span = 2; break;
                    case "view_3": span = 3; break;
                    case "view_4": span = 4; break;
                    case "view_5": span = 5; break;
                }
                Bundle out = new Bundle();
                out.putString("action", "set_view_mode");
                out.putInt("grid_span", span);
                getParentFragmentManager().setFragmentResult(resultKey, out);
                dismiss();
            }
        });

        PickerBottomSheetFragment picker = PickerBottomSheetFragment.newInstance(
                getString(R.string.records_grid_option), options, selectedId, pickerKey, getString(R.string.records_grid_helper)
        );
        picker.show(getParentFragmentManager(), "RecordsViewModePicker");
    }

    /** Returns the display label for a grid span count. */
    private String getGridSpanLabel(int span) {
        switch (span) {
            case 1: return getString(R.string.records_grid_1);
            case 3: return getString(R.string.records_grid_3);
            case 4: return getString(R.string.records_grid_4);
            case 5: return getString(R.string.records_grid_5);
            default: return getString(R.string.records_grid_2);
        }
    }

    private void openSortPicker(){
    ArrayList<OptionItem> options = new ArrayList<>();
    // Use Material Symbols ligatures for cleaner, consistent icons
    options.add(OptionItem.withLigature("latest", getString(R.string.sort_latest_first), "arrow_upward"));
    options.add(OptionItem.withLigature("oldest", getString(R.string.sort_oldest_first), "arrow_downward"));
    // Use size icons to imply file size ordering
    options.add(OptionItem.withLigature("smallest", getString(R.string.sort_smallest_first), "trending_down"));
    options.add(OptionItem.withLigature("largest", getString(R.string.sort_largest_first), "trending_up"));

        final String pickerKey = "records_sort_picker";
        getParentFragmentManager().setFragmentResultListener(pickerKey, this, (k, bundle)->{
            String selId = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if(selId!=null){
                selectedSortId = selId;
                Bundle out = new Bundle();
                out.putString("action", "sort");
                out.putString("sort_id", selId);
                getParentFragmentManager().setFragmentResult(resultKey, out);
                View root = getView();
                if(root!=null){
                    TextView sub = root.findViewById(R.id.row_sort_subtitle);
                    updateSortSubtitle(sub, selId);
                }
            }
        });
    PickerBottomSheetFragment picker = PickerBottomSheetFragment.newInstance(
        getString(R.string.sort_by), options, selectedSortId, pickerKey, getString(R.string.records_sort_helper)
    );
        picker.show(getParentFragmentManager(), "RecordsSortPicker");
    }

    @Override
    public void onStart() {
        super.onStart();
        // Clear any default container backgrounds from the SideSheet host views to avoid gray edges around rounded corners
        View root = getView();
        if(root != null){
            View p = (View) root.getParent();
            int guard = 0;
            while(p != null && guard < 5){ // climb a few levels safely
                try {
                    if(p.getBackground() != null){ p.setBackgroundColor(android.graphics.Color.TRANSPARENT); }
                } catch (Exception ignored) { }
                if(!(p.getParent() instanceof View)) break;
                p = (View) p.getParent();
                guard++;
            }
        }
    }

    private void updateSortSubtitle(TextView tv, String id){
        if(tv==null) return;
        switch (id){
            case "oldest": tv.setText(R.string.sort_oldest_first); break;
            case "smallest": tv.setText(R.string.sort_smallest_first); break;
            case "largest": tv.setText(R.string.sort_largest_first); break;
            default: tv.setText(R.string.sort_latest_first);
        }
    }
}
