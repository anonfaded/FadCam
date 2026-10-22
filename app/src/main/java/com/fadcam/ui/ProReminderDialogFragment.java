package com.fadcam.ui;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.fadcam.R;
import com.fadcam.pro.ProReminderManager;
import com.fadcam.ui.widgets.ProBundleShowcaseView;
import com.google.android.material.button.MaterialButton;

/**
 * The weekly Pro prompt. One action only, and it stays disabled for three seconds so the
 * button never reads as a reflex "dismiss" control. Back explains itself; outside taps do
 * nothing. Only Continue opens Pro and completes the impression.
 */
public class ProReminderDialogFragment extends DialogFragment {

    public static final String TAG = "ProReminderDialog";
    private static final String ARG_COPY_INDEX = "copy_index";
    private static final String STATE_COUNTDOWN_END = "countdown_end";
    private static final long COUNTDOWN_MS = 3000L;

    private MaterialButton continueButton;
    private CountDownTimer countdownTimer;
    private long countdownEndsAt;
    private long lastCountdownSecond = -1L;
    private Toast backToast;
    private int copyIndex;
    private boolean completed;

    public static ProReminderDialogFragment newInstance(int copyIndex) {
        ProReminderDialogFragment fragment = new ProReminderDialogFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_COPY_INDEX, copyIndex);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        setStyle(STYLE_NO_TITLE, R.style.ProReminderDialogTheme);
        super.onCreate(savedInstanceState);
        // Outside taps and the back key must never dismiss this prompt.
        setCancelable(false);
        copyIndex = getArguments() == null ? 0 : getArguments().getInt(ARG_COPY_INDEX, 0);
        if (savedInstanceState != null) {
            countdownEndsAt = savedInstanceState.getLong(STATE_COUNTDOWN_END, 0L);
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        return new Dialog(requireContext(), getTheme()) {
            @Override
            public void onBackPressed() {
                // Predictive back and the legacy key path both land here. Do not cancel.
                showBackToast();
            }
        };
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_pro_reminder, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        installBackGuard(getDialog());

        ProBundleShowcaseView showcase = view.findViewById(R.id.proReminderShowcase);
        ProReminderManager.DialogCopy copy =
                ProReminderManager.getDialogCopy(requireContext(), copyIndex);
        // The dossier frame already carries the copy; the showcase is the device exhibit only.
        showcase.setPhoneOnlyMode(true, 120);
        showcase.setSplashEnabled(true);
        showcase.setLiveClockEnabled(true);
        showcase.setRealBatteryEnabled(true);
        showcase.setDragEnabled(true);

        android.widget.TextView title = view.findViewById(R.id.proReminderTitle);
        android.widget.TextView subtitle = view.findViewById(R.id.proReminderSubtitle);
        android.widget.TextView fileNo = view.findViewById(R.id.proReminderFileNo);
        android.widget.TextView cycle = view.findViewById(R.id.proReminderCycle);
        title.setText(copy.title);
        subtitle.setText(copy.subtitle);
        fileNo.setText(getString(R.string.pro_dossier_file_value, copyIndex + 1));
        cycle.setText(getString(R.string.pro_dossier_cycle, copyIndex + 1));

        continueButton = view.findViewById(R.id.proReminderContinue);
        continueButton.setOnClickListener(v -> openPro());
        startCountdown();
    }

    private void startCountdown() {
        long now = SystemClock.elapsedRealtime();
        if (countdownEndsAt <= 0L) {
            countdownEndsAt = now + COUNTDOWN_MS;
        }
        long remaining = countdownEndsAt - now;
        if (remaining <= 0L) {
            enableContinue();
            return;
        }
        updateCountdown(remaining);
        countdownTimer = new CountDownTimer(remaining, 200L) {
            @Override
            public void onTick(long millisUntilFinished) {
                updateCountdown(millisUntilFinished);
            }

            @Override
            public void onFinish() {
                enableContinue();
            }
        }.start();
    }

    private void updateCountdown(long millisUntilFinished) {
        if (continueButton == null) return;
        long seconds = Math.max(1L, (millisUntilFinished + 999L) / 1000L);
        if (seconds == lastCountdownSecond) return;
        lastCountdownSecond = seconds;
        continueButton.setEnabled(false);
        continueButton.setAlpha(0.72f);
        continueButton.setText(getString(R.string.pro_reminder_button_countdown, seconds));
    }

    private void enableContinue() {
        if (continueButton == null) return;
        lastCountdownSecond = 0L;
        continueButton.setEnabled(true);
        continueButton.setAlpha(1f);
        continueButton.setText(R.string.pro_reminder_button_continue);
        continueButton.announceForAccessibility(
                getString(R.string.pro_reminder_button_continue));
    }

    private void openPro() {
        if (!isAdded() || continueButton == null || !continueButton.isEnabled()) return;
        Intent intent = new Intent(requireContext(), FadCamProActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(intent);
            if (getActivity() != null) {
                // The Pro screen owns the entrance cinematic; never slide Home in first.
                getActivity().overridePendingTransition(0, 0);
            }
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.pro_screen_link_error,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        completed = true;
        ProReminderManager.completePresentation(requireContext());
        dismissAllowingStateLoss();
    }

    private void showBackToast() {
        if (!isAdded()) return;
        // One back press = one new line. Cancel the previous toast so messages never queue.
        if (backToast != null) backToast.cancel();
        backToast = Toast.makeText(requireContext(),
                ProReminderManager.getNextBackToast(requireContext()), Toast.LENGTH_SHORT);
        backToast.show();
    }

    private void installBackGuard(Dialog dialog) {
        if (dialog == null) return;
        dialog.setCanceledOnTouchOutside(false);
        dialog.setCancelable(false);
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog == null) return;
        installBackGuard(dialog);
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
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(STATE_COUNTDOWN_END, countdownEndsAt);
    }

    @Override
    public void onDestroyView() {
        if (countdownTimer != null) {
            countdownTimer.cancel();
            countdownTimer = null;
        }
        if (backToast != null) {
            backToast.cancel();
            backToast = null;
        }
        if (!completed && isAdded() && getActivity() != null
                && !getActivity().isChangingConfigurations()) {
            ProReminderManager.releaseClaim(requireContext());
        }
        continueButton = null;
        super.onDestroyView();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
