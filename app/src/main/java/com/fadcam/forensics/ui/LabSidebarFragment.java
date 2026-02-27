package com.fadcam.forensics.ui;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.DialogFragment;

import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.google.android.material.sidesheet.SideSheetDialog;

public class LabSidebarFragment extends DialogFragment {

    private static final String ARG_RESULT_KEY = "result_key";

    public static LabSidebarFragment newInstance(String resultKey) {
        LabSidebarFragment fragment = new LabSidebarFragment();
        Bundle args = new Bundle();
        args.putString(ARG_RESULT_KEY, resultKey);
        fragment.setArguments(args);
        return fragment;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        SideSheetDialog dialog = new SideSheetDialog(requireContext());
        if (dialog.getWindow() != null) {
            android.view.Window window = dialog.getWindow();
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            View decor = window.getDecorView();
            if (decor instanceof ViewGroup) {
                ((ViewGroup) decor).setPadding(0, 0, 0, 0);
                decor.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            }
        }
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_lab_sidebar, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        String resultKey = getArguments() != null ? getArguments().getString(ARG_RESULT_KEY, "lab_sidebar_result") : "lab_sidebar_result";

        View close = view.findViewById(R.id.lab_sidebar_close_btn);
        if (close != null) {
            close.setOnClickListener(v -> dismiss());
        }

        bindAction(view, R.id.row_lab_export, resultKey, "open_export");
        bindAction(view, R.id.row_lab_info, resultKey, "open_info");
        bindAction(view, R.id.row_lab_insights, resultKey, "open_insights");
        bindAction(view, R.id.row_lab_clip_style, resultKey, "open_clip_style");
        bindAction(view, R.id.row_lab_tape_style, resultKey, "open_tape_style");

        // Hide Thumbnails toggle (Classified Mode)
        SwitchCompat hideSwitch = view.findViewById(R.id.row_lab_hide_thumbnails_switch);
        TextView hideState = view.findViewById(R.id.row_lab_hide_thumbnails_state);
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(requireContext());
        boolean currentHide = prefs.isLabHideThumbnailsEnabled();
        if (hideSwitch != null) {
            hideSwitch.setChecked(currentHide);
            if (hideState != null) {
                hideState.setText(currentHide ? getString(R.string.enabled) : getString(R.string.disabled));
            }
            hideSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.setLabHideThumbnailsEnabled(isChecked);
                if (hideState != null) {
                    hideState.setText(isChecked ? getString(R.string.enabled) : getString(R.string.disabled));
                }
                Bundle b = new Bundle();
                b.putString("action", "hide_thumbnails_toggled");
                b.putBoolean("hide_thumbnails", isChecked);
                getParentFragmentManager().setFragmentResult(resultKey, b);
            });
        }
    }

    private void bindAction(@NonNull View root, int rowId, @NonNull String resultKey, @NonNull String action) {
        View row = root.findViewById(rowId);
        if (row == null) {
            return;
        }
        row.setOnClickListener(v -> {
            Bundle out = new Bundle();
            out.putString("action", action);
            getParentFragmentManager().setFragmentResult(resultKey, out);
            dismiss();
        });
    }
}
