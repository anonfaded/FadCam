package com.fadcam.ui;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.fadcam.R;
import com.fadcam.ui.picker.OptionItem;
import com.fadcam.ui.picker.PickerBottomSheetFragment;
import androidx.fragment.app.DialogFragment;
import com.google.android.material.sidesheet.SideSheetDialog;

import java.util.ArrayList;

/**
 * RecordsSidebarFragment
 * Side overlay with settings-style grouped rows for Records options (sort, delete all).
 */
public class RecordsSidebarFragment extends DialogFragment {

    private static final String ARG_SELECTED_SORT_ID = "selected_sort_id";
    private String resultKey = "records_sidebar_result";
    private String selectedSortId;
    private boolean isGridViewInitial = true;

    public static RecordsSidebarFragment newInstance(String selectedSortId){
        RecordsSidebarFragment f = new RecordsSidebarFragment();
        Bundle b = new Bundle();
        b.putString(ARG_SELECTED_SORT_ID, selectedSortId);
        f.setArguments(b);
        return f;
    }

    public static RecordsSidebarFragment newInstance(String selectedSortId, boolean isGrid){
        RecordsSidebarFragment f = new RecordsSidebarFragment();
        Bundle b = new Bundle();
        b.putString(ARG_SELECTED_SORT_ID, selectedSortId);
        b.putBoolean("is_grid_view", isGrid);
        f.setArguments(b);
        return f;
    }

    public void setResultKey(String key){ this.resultKey = key; }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        // -------------- Fix Start for this method(onCreateDialog)-----------
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
        // -------------- Fix Ended for this method(onCreateDialog)-----------
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // -------------- Fix Start for this method(onCreateView)-----------
        // Inflate the side sheet content (modal side sheet provided by Material components)
        return inflater.inflate(R.layout.fragment_records_sidebar, container, false);
        // -------------- Fix Ended for this method(onCreateView)-----------
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if(getArguments()!=null){
            selectedSortId = getArguments().getString(ARG_SELECTED_SORT_ID, "latest");
            isGridViewInitial = getArguments().getBoolean("is_grid_view", true);
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

        // View mode row
        View viewModeRow = view.findViewById(R.id.row_view_mode);
        TextView viewModeSub = view.findViewById(R.id.row_view_mode_subtitle);
        android.widget.ImageView viewModeIcon = view.findViewById(R.id.row_view_mode_icon);
        if(viewModeSub!=null){ viewModeSub.setText(isGridViewInitial ? R.string.view_mode_grid : R.string.view_mode_list); }
        if(viewModeIcon!=null){ viewModeIcon.setImageResource(isGridViewInitial ? R.drawable.ic_grid : R.drawable.ic_list); }
        if(viewModeRow!=null){
            viewModeRow.setOnClickListener(v -> {
                Bundle b = new Bundle();
                b.putString("action", "toggle_view_mode");
                getParentFragmentManager().setFragmentResult(resultKey, b);
                dismiss();
            });
        }
    }

    private void openSortPicker(){
    ArrayList<OptionItem> options = new ArrayList<>();
    // -------------- Fix Start for this method(openSortPicker_material_symbols)-----------
    // Use Material Symbols ligatures for cleaner, consistent icons
    options.add(OptionItem.withLigature("latest", getString(R.string.sort_latest_first), "arrow_upward"));
    options.add(OptionItem.withLigature("oldest", getString(R.string.sort_oldest_first), "arrow_downward"));
    // Use size icons to imply file size ordering
    // -------------- Fix Start for this method(openSortPicker_size_icons)-----------
    options.add(OptionItem.withLigature("smallest", getString(R.string.sort_smallest_first), "trending_down"));
    options.add(OptionItem.withLigature("largest", getString(R.string.sort_largest_first), "trending_up"));
    // -------------- Fix Ended for this method(openSortPicker_size_icons)-----------
    // -------------- Fix Ended for this method(openSortPicker_material_symbols)-----------

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
    // -------------- Fix Start for this method(openSortPicker_helper_text)-----------
    PickerBottomSheetFragment picker = PickerBottomSheetFragment.newInstance(
        getString(R.string.sort_by), options, selectedSortId, pickerKey, getString(R.string.records_sort_helper)
    );
        picker.show(getParentFragmentManager(), "RecordsSortPicker");
    }

    @Override
    public void onStart() {
        super.onStart();
        // -------------- Fix Start for this method(onStart_clearContainerBackgrounds)-----------
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
        // -------------- Fix Ended for this method(onStart_clearContainerBackgrounds)-----------
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
