package com.fadcam.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.fadcam.R;
import com.fadcam.pro.ProReminderManager;
import com.google.android.material.button.MaterialButton;

/** The one-shot dossier-style confirmation shown after returning from the Pro screen. */
public class ProReminderAfterDialogFragment extends DialogFragment {

    public static final String TAG = "ProReminderAfterDialog";
    private Context appContext;
    private boolean acknowledged;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        appContext = context.getApplicationContext();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        setStyle(STYLE_NO_TITLE, R.style.ProReminderDialogTheme);
        super.onCreate(savedInstanceState);
        // This is the one-shot acknowledgement: only GOT IT closes it. A stray Back event
        // must not silently consume the confirmation.
        setCancelable(false);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        return new Dialog(requireContext(), getTheme()) {
            @Override
            public void onBackPressed() {
                // Deliberately inert; the dossier is acknowledged with the button.
            }
        };
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_pro_reminder_after, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Dialog dialog = getDialog();
        if (dialog != null) {
            dialog.setCanceledOnTouchOutside(false);
        }

        TextView title = view.findViewById(R.id.proAfterTitle);
        TextView body = view.findViewById(R.id.proAfterBody);
        title.setText(ProReminderManager.getAfterProTitle(requireContext()));
        body.setText(ProReminderManager.getAfterProBody(requireContext()));

        MaterialButton gotIt = view.findViewById(R.id.proAfterGotIt);
        gotIt.setOnClickListener(v -> {
            acknowledged = true;
            dismiss();
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window == null) return;

        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setGravity(Gravity.CENTER);
        window.setDimAmount(0.66f);

        int width = Math.min(
                getResources().getDisplayMetrics().widthPixels - dp(32),
                dp(420));
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = width;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        window.setAttributes(params);
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        // A system teardown (activity recreation, process pressure) must not swallow the
        // confirmation: only an explicit GOT IT clears the pending flag.
        if (acknowledged && appContext != null) {
            ProReminderManager.clearAfterProPending(appContext);
        }
        super.onDismiss(dialog);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
