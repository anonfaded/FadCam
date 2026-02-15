package com.fadcam.forensics.ui;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.fadcam.R;
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

        bindAction(view, R.id.row_lab_gallery, resultKey, "open_gallery");
        bindAction(view, R.id.row_lab_export, resultKey, "open_export");
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
