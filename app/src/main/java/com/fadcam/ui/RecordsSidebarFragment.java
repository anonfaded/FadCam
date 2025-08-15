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

    public static RecordsSidebarFragment newInstance(String selectedSortId){
        RecordsSidebarFragment f = new RecordsSidebarFragment();
        Bundle b = new Bundle();
        b.putString(ARG_SELECTED_SORT_ID, selectedSortId);
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
        // Make window background transparent so layout background with rounded corners shows
        if(dialog.getWindow()!=null){
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }
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
        if(getArguments()!=null){ selectedSortId = getArguments().getString(ARG_SELECTED_SORT_ID, "latest"); }

        // Back button in header
        View back = view.findViewById(R.id.sidebar_back);
    if(back!=null){ back.setOnClickListener(v -> dismiss()); }

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
    }

    private void openSortPicker(){
        ArrayList<OptionItem> options = new ArrayList<>();
        options.add(new OptionItem("latest", getString(R.string.sort_latest_first)));
        options.add(new OptionItem("oldest", getString(R.string.sort_oldest_first)));
        options.add(new OptionItem("smallest", getString(R.string.sort_smallest_first)));
        options.add(new OptionItem("largest", getString(R.string.sort_largest_first)));

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
