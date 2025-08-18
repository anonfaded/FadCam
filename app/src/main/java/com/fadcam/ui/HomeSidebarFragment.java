package com.fadcam.ui;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.fadcam.R;
import com.fadcam.ui.picker.OptionItem;
import com.fadcam.ui.picker.PickerBottomSheetFragment;
import com.google.android.material.sidesheet.SideSheetDialog;

import java.util.ArrayList;

/**
 * HomeSidebarFragment
 * Side overlay with settings-style grouped rows for Home options (tips, etc).
 * Based on RecordsSidebarFragment pattern.
 */
public class HomeSidebarFragment extends DialogFragment {

    private String resultKey = "home_sidebar_result";

    public static HomeSidebarFragment newInstance() {
        return new HomeSidebarFragment();
    }

    public void setResultKey(String key) {
        this.resultKey = key;
    }

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
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Inflate the side sheet content (modal side sheet provided by Material components)
        return inflater.inflate(R.layout.fragment_home_sidebar, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Tips row
        View tipsRow = view.findViewById(R.id.row_tips);
        if (tipsRow != null) {
            tipsRow.setOnClickListener(v -> {
                openTipsPicker();
                dismiss();
            });
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        
        // Set the sheet edge to START (left side) after the view is created
        Dialog dialog = getDialog();
        if (dialog instanceof SideSheetDialog) {
            try {
                ((SideSheetDialog) dialog).setSheetEdge(android.view.Gravity.START);
            } catch (Exception ignored) {
                // Fallback if setSheetEdge fails
            }
        }
        
        // Clear any default container backgrounds from the SideSheet host views to avoid gray edges around rounded corners
        View root = getView();
        if (root != null) {
            View p = (View) root.getParent();
            int guard = 0;
            while (p != null && guard < 5) { // climb a few levels safely
                try {
                    if (p.getBackground() != null) { 
                        p.setBackgroundColor(android.graphics.Color.TRANSPARENT); 
                    }
                } catch (Exception ignored) { }
                if (!(p.getParent() instanceof View)) break;
                p = (View) p.getParent();
                guard++;
            }
        }
    }

    private void openTipsPicker() {
        // Use the existing PickerBottomSheetFragment for tips
        ArrayList<OptionItem> tipOptions = new ArrayList<>();
        
        // Get tips from string array
        String[] tips = getResources().getStringArray(R.array.tips_widget);
    for (int i = 0; i < tips.length; i++) {
        // Use the two-arg constructor to avoid ambiguity between (String) and (Integer) overloads
        tipOptions.add(new OptionItem("tip_" + i, tips[i]));
    }

    // Use existing PickerBottomSheetFragment factory that accepts (title, items, selectedId, resultKey, helper)
    PickerBottomSheetFragment tipsPicker = PickerBottomSheetFragment.newInstance(
        getString(R.string.tips_title),
        tipOptions,
        null, // no initial selection
        resultKey,
        getString(R.string.tips_subtitle)
    );

        tipsPicker.show(getParentFragmentManager(), "tips_picker");
    }
}
