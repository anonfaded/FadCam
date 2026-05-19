package com.fadcam.ui.miniapps;

import android.app.Dialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import org.json.JSONObject;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.Constants;
import com.fadcam.FLog;
import com.fadcam.R;
import com.fadcam.ui.AvatarToggleView;
import com.fadcam.ui.BaseFragment;
import com.fadcam.ui.OverlayNavUtil;
import com.google.android.material.slider.Slider;

import java.util.ArrayList;

/**
 * TorchToolFragment - Modern industry-standard torch screen.
 * Hero tap-to-toggle button, animated glow, Material 3 pattern chips,
 * and fullscreen screen-light overlay with brightness slider.
 */
public class TorchToolFragment extends BaseFragment {

    private TorchManager torchManager;

    // Views
    private AvatarToggleView torchToggle;
    private View torchGlow;
    private TextView torchStatusLabel;
    private LinearLayout patternChipsContainer;
    private TextView patternInfoBtn;
    private LinearLayout screenLightRow;
    private TextView screenLightStatus;

    // Screen-light dialog
    private Dialog screenLightDialog;
    private boolean isScreenLightOn = false;

    // Animation guard: skip wake/sleep animation on the very first draw
    private boolean isFirstStateUpdate = true;

    // Toggle guard: prevents AvatarToggle listener from firing during programmatic setChecked
    private boolean isUpdatingToggle = false;

    // Auto-off timer
    private android.os.CountDownTimer autoOffTimer;
    private boolean isTimerActive = false;
    private LinearLayout timerRow;
    private TextView timerStatus;

    // Torch notification
    private static final String TORCH_NOTIFICATION_CHANNEL_ID = "torch_active_channel";
    private static final int TORCH_NOTIFICATION_ID = 9001;
    private NotificationManager notificationManager;

    // BroadcastReceiver to sync UI when torch state changes externally (e.g., from notification)
    private BroadcastReceiver torchStateReceiver;

    public static TorchToolFragment newInstance() {
        return new TorchToolFragment();
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_torch_tool, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Init TorchManager - use singleton instance for app-wide state consistency
        torchManager = TorchManager.getInstance(requireContext());
        notificationManager = (NotificationManager) requireContext().getSystemService(Context.NOTIFICATION_SERVICE);
        createTorchNotificationChannel();
        torchManager.setStateListener(new TorchManager.TorchStateListener() {
            @Override
            public void onTorchStateChanged(boolean isOn) {
                updateTorchState();
            }

            @Override
            public void onBrightnessChanged(float brightness) { /* not used */ }

            @Override
            public void onPatternChanged(TorchManager.FlashPattern pattern) {
                updatePatternChips();
            }

            @Override
            public void onError(String message) {
                FLog.e("TorchToolFragment", "Torch error: " + message);
            }
        });

        // Bind views
        FrameLayout torchButtonArea = view.findViewById(R.id.torch_button_area);
        torchToggle = view.findViewById(R.id.torch_toggle);
        torchGlow = view.findViewById(R.id.torch_glow);
        torchStatusLabel = view.findViewById(R.id.torch_status_label);
        patternChipsContainer = view.findViewById(R.id.pattern_chips_container);
        patternInfoBtn = view.findViewById(R.id.pattern_info_btn);
        screenLightRow = view.findViewById(R.id.screen_light_row);
        screenLightStatus = view.findViewById(R.id.screen_light_status);
        ImageView closeBtn = view.findViewById(R.id.torch_close_btn);

        // Close
        if (closeBtn != null) {
            closeBtn.setOnClickListener(v -> {
                dismissScreenLight();
                // Note: Don't release torchManager since it's a singleton and persists across fragments
                OverlayNavUtil.dismiss(requireActivity());
            });
        }

        // Hero tap area
        if (torchButtonArea != null) {
            torchButtonArea.setOnClickListener(v -> toggleTorch());
        }

        // Toggle as fallback kill switch (emergency response always available)
        if (torchToggle != null) {
            torchToggle.setClickable(true);
            torchToggle.setFocusable(true);
            torchToggle.setOnCheckedChangeListener((v, isChecked) -> {
                if (isUpdatingToggle) return; // Ignore programmatic setChecked calls
                FLog.d("TorchToggle", "Toggle clicked: " + isChecked + " (was: " + torchManager.isTorchOn() + ")");
                torchManager.setTorchEnabled(isChecked);
            });
        }

        // Pattern chips
        setupPatternChips();

        // Pattern info
        if (patternInfoBtn != null) {
            patternInfoBtn.setOnClickListener(v -> showPatternInfoSheet());
        }

        // Screen light row
        if (screenLightRow != null) {
            screenLightRow.setOnClickListener(v -> toggleScreenLight());
        }

        // Timer row
        timerRow = view.findViewById(R.id.torch_timer_row);
        timerStatus = view.findViewById(R.id.torch_timer_status);
        if (timerRow != null) {
            timerRow.setOnClickListener(v -> {
                // Only allow timer picker if torch is on
                if (torchManager.isTorchOn()) {
                    showTimerPicker();
                }
            });
        }

        // Initial draw (no animation on first render)
        updateTorchState();

        // Register BroadcastReceiver to sync UI when torch state changes externally (e.g., from notification)
        // This ensures the UI updates when torch is turned off via notification or other external means
        torchStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Constants.BROADCAST_ON_TORCH_STATE_CHANGED.equals(intent.getAction())) {
                    // Reload state from TorchManager and update UI
                    updateTorchState();
                    FLog.d("TorchToolFragment", "Torch state changed externally, UI synced");
                }
            }
        };
        IntentFilter filter = new IntentFilter(Constants.BROADCAST_ON_TORCH_STATE_CHANGED);
        ContextCompat.registerReceiver(requireContext(), torchStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    // ── Torch toggle ─────────────────────────────────────────────────────────

    private void toggleTorch() {
        torchManager.setTorchEnabled(!torchManager.isTorchOn());
    }

    // ── State update ──────────────────────────────────────────────────────────

    private void updateTorchState() {
        boolean isOn = torchManager.isTorchOn();

        // AvatarToggle with animation (skip on first draw)
        if (torchToggle != null) {
            isUpdatingToggle = true;
            torchToggle.setChecked(isOn, !isFirstStateUpdate);
            isUpdatingToggle = false;
        }
        isFirstStateUpdate = false;

        // Update timer row enabled state - disable when torch is off
        View timerRow = getView() != null ? getView().findViewById(R.id.torch_timer_row) : null;
        if (timerRow != null) {
            timerRow.setEnabled(isOn);
            timerRow.setAlpha(isOn ? 1.0f : 0.5f);
        }

        // Glow ring
        if (torchGlow != null) {
            if (isOn) {
                torchGlow.setVisibility(View.VISIBLE);
                torchGlow.setScaleX(0.8f);
                torchGlow.setScaleY(0.8f);
                torchGlow.setAlpha(0f);
                torchGlow.animate()
                        .scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(300)
                        .start();
            } else {
                torchGlow.animate()
                        .scaleX(0.85f).scaleY(0.85f).alpha(0f)
                        .setDuration(250)
                        .withEndAction(() -> torchGlow.setVisibility(View.GONE))
                        .start();
            }
        }

        // Status label
        if (torchStatusLabel != null) {
            if (isOn) {
                torchStatusLabel.setText(getString(R.string.torch_status_on));
                torchStatusLabel.setTextColor(0xFFFFA726);
            } else {
                torchStatusLabel.setText(getString(R.string.torch_tap_to_enable));
                torchStatusLabel.setTextColor(0x80FFFFFF);
            }
        }

        updatePatternChips();

        // Auto-dismiss screen light when torch turns off
        if (!isOn && isScreenLightOn) {
            dismissScreenLight();
        }

        // Cancel auto-off timer if torch was turned off externally (e.g., by another app)
        if (!isOn && isTimerActive) {
            cancelAutoOffTimer();
        }

        // Update persistent notification
        if (isOn) {
            postTorchNotification();
        } else {
            cancelTorchNotification();
        }
    }

    // ── Pattern chips ─────────────────────────────────────────────────────────

    private void setupPatternChips() {
        if (patternChipsContainer == null) return;
        patternChipsContainer.removeAllViews();

        for (TorchManager.FlashPattern pattern : TorchManager.FlashPattern.values()) {
            View chip = createPatternChip(pattern);
            patternChipsContainer.addView(chip);
        }
    }

    private View createPatternChip(TorchManager.FlashPattern pattern) {
        LinearLayout chip = new LinearLayout(requireContext());
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(android.view.Gravity.CENTER);

        int chipH = dpToPx(48);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, chipH, 1f);
        lp.setMargins(dpToPx(4), 0, dpToPx(4), 0);
        chip.setLayoutParams(lp);

        // Icon
        TextView icon = new TextView(requireContext());
        icon.setTypeface(ResourcesCompat.getFont(requireContext(), R.font.materialicons));
        icon.setText(getPatternIcon(pattern));
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        icon.setGravity(android.view.Gravity.CENTER);
        chip.addView(icon);

        // Label
        TextView label = new TextView(requireContext());
        label.setText(getPatternName(pattern));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.setMarginStart(dpToPx(6));
        label.setLayoutParams(labelLp);
        chip.addView(label);

        chip.setTag(pattern);
        chip.setOnClickListener(v -> {
            // Only change pattern if not already the current pattern (avoid re-triggering same pattern)
            if (torchManager.getCurrentPattern() != pattern) {
                FLog.d("TorchPattern", "Changed pattern from " + torchManager.getCurrentPattern() + " to " + pattern);
                torchManager.setFlashPattern(pattern);
            } else {
                FLog.d("TorchPattern", "Pattern already active: " + pattern);
            }
        });

        stylePatternChip(chip, pattern == torchManager.getCurrentPattern());
        return chip;
    }

    private void stylePatternChip(LinearLayout chip, boolean isActive) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dpToPx(16));
        if (isActive) {
            bg.setColor(0xFFFFA726);
        } else {
            bg.setColor(0xFF1E1E1E);
            bg.setStroke(dpToPx(1), 0xFF444444);
        }
        chip.setBackground(bg);
        chip.setAlpha(isActive ? 1.0f : 0.65f);

        int textColor = isActive ? 0xFF1A1A1A : 0xFFCCCCCC;
        for (int i = 0; i < chip.getChildCount(); i++) {
            if (chip.getChildAt(i) instanceof TextView) {
                ((TextView) chip.getChildAt(i)).setTextColor(textColor);
            }
        }
    }

    private void updatePatternChips() {
        if (patternChipsContainer == null) return;
        TorchManager.FlashPattern current = torchManager.getCurrentPattern();
        for (int i = 0; i < patternChipsContainer.getChildCount(); i++) {
            View child = patternChipsContainer.getChildAt(i);
            if (child instanceof LinearLayout && child.getTag() instanceof TorchManager.FlashPattern) {
                TorchManager.FlashPattern p = (TorchManager.FlashPattern) child.getTag();
                stylePatternChip((LinearLayout) child, p == current);
            }
        }
    }

    private String getPatternIcon(TorchManager.FlashPattern pattern) {
        switch (pattern) {
            case STEADY:  return "flashlight_on";
            case STROBE:  return "bolt";
            case SOS:     return "sos";
            default:      return "flashlight_on";
        }
    }

    private String getPatternName(TorchManager.FlashPattern pattern) {
        switch (pattern) {
            case STEADY:  return getString(R.string.torch_pattern_steady);
            case STROBE:  return getString(R.string.torch_pattern_strobe);
            case SOS:     return getString(R.string.torch_pattern_sos);
            default:      return pattern.name();
        }
    }

    // ── Screen light ──────────────────────────────────────────────────────────

    private void toggleScreenLight() {
        if (isScreenLightOn) {
            dismissScreenLight();
        } else {
            showScreenLight();
        }
    }

    private void showScreenLight() {
        screenLightDialog = new Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        screenLightDialog.setContentView(R.layout.overlay_torch_screen_light);

        WindowManager.LayoutParams lp = screenLightDialog.getWindow().getAttributes();
        screenLightDialog.getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        screenLightDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        screenLightDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        screenLightDialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        // Load saved brightness or default to 50%
        float savedBrightness = loadScreenLightBrightness();
        lp.screenBrightness = savedBrightness;
        screenLightDialog.getWindow().setAttributes(lp);

        Slider slider = screenLightDialog.findViewById(R.id.screen_brightness_slider);
        if (slider != null) {
            // Allow full range without gating
            slider.setValueFrom(0.0f);
            slider.setValueTo(1.0f);
            slider.setValue(savedBrightness);
            slider.addOnChangeListener((s, value, fromUser) -> {
                if (fromUser && screenLightDialog != null && screenLightDialog.getWindow() != null) {
                    WindowManager.LayoutParams attrs = screenLightDialog.getWindow().getAttributes();
                    attrs.screenBrightness = value;
                    screenLightDialog.getWindow().setAttributes(attrs);
                    // Save as user changes
                    saveScreenLightBrightness(value);
                }
            });
        }

        View closeLightBtn = screenLightDialog.findViewById(R.id.screen_light_close_btn);
        if (closeLightBtn != null) {
            closeLightBtn.setOnClickListener(v -> dismissScreenLight());
        }

        // Use setCancelable(true) with setOnCancelListener to properly handle back button on fullscreen dialogs
        // This is the documented way to intercept back in dialogs (setOnKeyListener doesn't reliably work)
        screenLightDialog.setOnCancelListener(dialog -> dismissScreenLight());
        screenLightDialog.setCancelable(true);
        screenLightDialog.show();

        isScreenLightOn = true;
        updateScreenLightStatus();
    }

    private void dismissScreenLight() {
        if (screenLightDialog != null) {
            try {
                if (screenLightDialog.getWindow() != null) {
                    screenLightDialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    WindowManager.LayoutParams lp = screenLightDialog.getWindow().getAttributes();
                    lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                    screenLightDialog.getWindow().setAttributes(lp);
                }
                if (screenLightDialog.isShowing()) {
                    screenLightDialog.dismiss();
                }
            } catch (Exception e) {
                FLog.e("TorchToolFragment", "Error dismissing screen light", e);
            }
            screenLightDialog = null;
        }
        isScreenLightOn = false;
        updateScreenLightStatus();
    }

    private void updateScreenLightStatus() {
        if (screenLightStatus != null) {
            if (isScreenLightOn) {
                screenLightStatus.setText(getString(R.string.torch_status_on));
                screenLightStatus.setTextColor(0xFFFFA726);
            } else {
                screenLightStatus.setText(getString(R.string.torch_status_off));
                screenLightStatus.setTextColor(0x66FFFFFF);
            }
        }
    }

    // ── Pattern info sheet ────────────────────────────────────────────────────

    private void showPatternInfoSheet() {
        ArrayList<com.fadcam.ui.picker.OptionItem> infoItems = new ArrayList<>();
        for (TorchManager.FlashPattern pattern : TorchManager.FlashPattern.values()) {
            infoItems.add(new com.fadcam.ui.picker.OptionItem(
                    pattern.name(),
                    getPatternName(pattern),
                    getPatternDescription(pattern),
                    null, null, null, null, null,
                    getPatternIcon(pattern)
            ));
        }
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
                com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
                        getString(R.string.mini_app_torch_patterns_title),
                        infoItems,
                        null,
                        "torch_pattern_info"
                );
        if (sheet.getArguments() != null) {
            sheet.getArguments().putBoolean(
                    com.fadcam.ui.picker.PickerBottomSheetFragment.ARG_INFO_MODE, true);
        }
        sheet.show(getParentFragmentManager(), "torch_pattern_info_sheet");
    }

    private String getPatternDescription(TorchManager.FlashPattern pattern) {
        switch (pattern) {
            case STEADY: return getString(R.string.pattern_steady_desc);
            case STROBE: return getString(R.string.pattern_strobe_desc);
            case SOS:    return getString(R.string.pattern_sos_desc);
            default:     return "";
        }
    }

    // ── Auto-off timer ────────────────────────────────────────────────────────

    private void showTimerPicker() {
        final long[] durations = {
            30_000L,           // 30 seconds
            60_000L,           // 1 minute
            2 * 60_000L,       // 2 minutes
            5 * 60_000L,       // 5 minutes
            10 * 60_000L,      // 10 minutes
            15 * 60_000L,      // 15 minutes
            30 * 60_000L,      // 30 minutes
            60 * 60_000L       // 1 hour
        };
        final String[] labels = {
            getString(R.string.torch_timer_30s),
            getString(R.string.torch_timer_1m),
            getString(R.string.torch_timer_2m),
            getString(R.string.torch_timer_5m),
            getString(R.string.torch_timer_10m),
            getString(R.string.torch_timer_15m),
            getString(R.string.torch_timer_30m),
            getString(R.string.torch_timer_1h)
        };

        ArrayList<com.fadcam.ui.picker.OptionItem> items = new ArrayList<>();
        // Show cancel option when timer is already active
        if (isTimerActive) {
            items.add(com.fadcam.ui.picker.OptionItem.withLigature(
                    "cancel",
                    getString(R.string.torch_timer_cancel),
                    "timer_off"
            ));
        }
        for (int i = 0; i < durations.length; i++) {
            items.add(com.fadcam.ui.picker.OptionItem.withLigature(
                    String.valueOf(durations[i]),
                    labels[i],
                    "timer"
            ));
        }
        // Custom option
        items.add(com.fadcam.ui.picker.OptionItem.withLigature(
                "custom",
                getString(R.string.torch_timer_custom_duration),
                "edit"
        ));

        getParentFragmentManager().setFragmentResultListener(
                "torch_timer_pick", getViewLifecycleOwner(), (key, result) -> {
                    String selected = result.getString(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                    if ("cancel".equals(selected)) {
                        cancelAutoOffTimer();
                    } else if ("custom".equals(selected)) {
                        showCustomTimerInput();
                    } else if (selected != null) {
                        try {
                            long duration = Long.parseLong(selected);
                            startAutoOffTimer(duration);
                        } catch (NumberFormatException e) {
                            FLog.e("TorchTimer", "Invalid timer duration: " + selected);
                        }
                    }
                });

        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
                com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
                        getString(R.string.torch_timer_label),
                        items,
                        null,
                        "torch_timer_pick",
                        getString(R.string.torch_timer_picker_helper)
                );
        sheet.show(getParentFragmentManager(), "torch_timer_picker_sheet");
    }

    private void showCustomTimerInput() {
        // Show a picker with unit options: seconds, minutes, hours
        ArrayList<com.fadcam.ui.picker.OptionItem> unitItems = new ArrayList<>();
        unitItems.add(com.fadcam.ui.picker.OptionItem.withLigature("seconds", getString(R.string.torch_custom_unit_seconds), "hourglass_bottom"));
        unitItems.add(com.fadcam.ui.picker.OptionItem.withLigature("minutes", getString(R.string.torch_custom_unit_minutes), "schedule"));
        unitItems.add(com.fadcam.ui.picker.OptionItem.withLigature("hours", getString(R.string.torch_custom_unit_hours), "schedule"));

        getParentFragmentManager().setFragmentResultListener(
                "torch_timer_unit", getViewLifecycleOwner(), (key, result) -> {
                    String unit = result.getString(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                    if (unit != null) {
                        showCustomTimerValueInput(unit);
                    }
                });

        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
                com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
                        getString(R.string.torch_timer_custom_title),
                        unitItems,
                        null,
                        "torch_timer_unit",
                        getString(R.string.torch_timer_custom_unit_helper)
                );
        sheet.show(getParentFragmentManager(), "torch_timer_unit_picker_sheet");
    }

    private void showCustomTimerValueInput(String unit) {
        String hint = getString(R.string.torch_timer_custom_hint);
        if (unit.equals("minutes")) {
            hint = String.format("%s (%s)", hint, getString(R.string.torch_timer_custom_minutes_example));
        } else if (unit.equals("hours")) {
            hint = String.format("%s (%s)", hint, getString(R.string.torch_timer_custom_hours_example));
        }

        com.fadcam.ui.InputActionBottomSheetFragment sheet =
                com.fadcam.ui.InputActionBottomSheetFragment.newInput(
                        getString(R.string.torch_timer_custom_title),
                        "",
                        hint,
                        getString(R.string.torch_timer_custom_apply),
                        null,
                        R.drawable.ic_check_circle,
                        hint
                );
        
        sheet.setCallbacks(new com.fadcam.ui.InputActionBottomSheetFragment.Callbacks() {
            @Override
            public void onInputConfirmed(String input) {
                if (input != null && !input.isEmpty()) {
                    try {
                        long value = Long.parseLong(input.trim());
                        if (value <= 0) {
                            showTimerInputError(getString(R.string.torch_timer_custom_error_positive));
                            return;
                        }
                        long seconds;
                        if ("minutes".equals(unit)) {
                            seconds = value * 60;
                        } else if ("hours".equals(unit)) {
                            seconds = value * 3600;
                        } else {
                            seconds = value;
                        }
                        startAutoOffTimer(seconds * 1000L);
                    } catch (NumberFormatException e) {
                        showTimerInputError(getString(R.string.torch_timer_custom_error_invalid));
                    }
                }
            }

            @Override
            public void onImportConfirmed(org.json.JSONObject json) {
                // Not used for this input
            }

            @Override
            public void onResetConfirmed() {
                // Not used for this input
            }
        });
        
        sheet.show(getParentFragmentManager(), "torch_timer_value_input_sheet");
    }

    private void showTimerInputError(String message) {
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void startAutoOffTimer(long durationMs) {
        cancelAutoOffTimer();
        if (!torchManager.isTorchOn()) {
            // Timer is only meaningful when torch is on; silently ignore if already off
            FLog.d("TorchTimer", "Torch is off — skipping timer start");
            return;
        }
        isTimerActive = true;
        updateTimerStatus(durationMs);
        autoOffTimer = new android.os.CountDownTimer(durationMs, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                updateTimerStatus(millisUntilFinished);
            }

            @Override
            public void onFinish() {
                isTimerActive = false;
                updateTimerStatus(0);
                // Realtime guard: only turn off if torch is still on
                if (torchManager != null && torchManager.isTorchOn()) {
                    FLog.d("TorchTimer", "Auto-off timer fired — turning off torch");
                    torchManager.setTorchEnabled(false);
                } else {
                    FLog.d("TorchTimer", "Auto-off timer fired — torch already off, nothing to do");
                }
            }
        };
        autoOffTimer.start();
        FLog.d("TorchTimer", "Auto-off timer started: " + durationMs + "ms");
    }

    private void cancelAutoOffTimer() {
        if (autoOffTimer != null) {
            autoOffTimer.cancel();
            autoOffTimer = null;
        }
        isTimerActive = false;
        updateTimerStatus(-1);
    }

    private void updateTimerStatus(long remainingMs) {
        if (timerStatus == null) return;
        if (!isTimerActive || remainingMs < 0) {
            timerStatus.setText(getString(R.string.torch_timer_off));
            timerStatus.setTextColor(0x66FFFFFF);
        } else {
            long totalSeconds = remainingMs / 1000;
            long hours = totalSeconds / 3600;
            long minutes = (totalSeconds % 3600) / 60;
            long seconds = totalSeconds % 60;
            String display;
            if (hours > 0) {
                display = String.format(java.util.Locale.getDefault(),
                        "%d:%02d:%02d", hours, minutes, seconds);
            } else if (minutes > 0) {
                display = String.format(java.util.Locale.getDefault(),
                        "%d:%02d", minutes, seconds);
            } else {
                display = String.format(java.util.Locale.getDefault(), "%ds", seconds);
            }
            timerStatus.setText(display);
            timerStatus.setTextColor(0xFFFFA726);
        }
    }

    // ── Torch notification ────────────────────────────────────────────────────

    private void createTorchNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    TORCH_NOTIFICATION_CHANNEL_ID,
                    getString(R.string.torch_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.torch_notification_channel_desc));
            channel.enableVibration(false);
            channel.setSound(null, null);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private void postTorchNotification() {
        if (notificationManager == null) return;
        try {
            // Create intent to turn off torch when notification is tapped
            Intent torchOffIntent = new Intent(requireContext(), TorchNotificationReceiver.class);
            torchOffIntent.setAction("com.fadcam.TORCH_OFF");
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    requireContext(),
                    0,
                    torchOffIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            
            androidx.core.app.NotificationCompat.Builder builder =
                    new androidx.core.app.NotificationCompat.Builder(
                            requireContext(),
                            TORCH_NOTIFICATION_CHANNEL_ID
                    )
                            .setContentTitle(getString(R.string.torch_notification_title))
                            .setContentText(getString(R.string.torch_notification_text))
                            .setSmallIcon(R.drawable.ic_flashlight_on)
                            .setContentIntent(pendingIntent)
                            .setOngoing(true)
                            .setAutoCancel(false)
                            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW);

            notificationManager.notify(TORCH_NOTIFICATION_ID, builder.build());
            FLog.d("TorchNotification", "Torch notification posted");
        } catch (Exception e) {
            FLog.e("TorchNotification", "Failed to post notification", e);
        }
    }

    private void cancelTorchNotification() {
        if (notificationManager != null) {
            notificationManager.cancel(TORCH_NOTIFICATION_ID);
            FLog.d("TorchNotification", "Torch notification cancelled");
        }
    }

    // ── Back press ────────────────────────────────────────────────────────────

    @Override
    protected boolean onBackPressed() {
        dismissScreenLight();
        torchManager.release();
        OverlayNavUtil.dismiss(requireActivity());
        return true;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        dismissScreenLight();
        cancelTorchNotification();
        if (autoOffTimer != null) {
            autoOffTimer.cancel();
            autoOffTimer = null;
        }
        if (torchStateReceiver != null) {
            try {
                requireContext().unregisterReceiver(torchStateReceiver);
            } catch (IllegalArgumentException e) {
                FLog.e("TorchToolFragment", "Error unregistering torchStateReceiver", e);
            }
            torchStateReceiver = null;
        }
        // Note: Don't release torchManager since it's a singleton and persists across fragments
    }

    // ── Util ──────────────────────────────────────────────────────────────────

    private float loadScreenLightBrightness() {
        android.content.SharedPreferences prefs = requireContext()
                .getSharedPreferences("torch_prefs", android.content.Context.MODE_PRIVATE);
        return prefs.getFloat("screen_light_brightness", 0.5f); // Default 50%
    }

    private void saveScreenLightBrightness(float brightness) {
        android.content.SharedPreferences prefs = requireContext()
                .getSharedPreferences("torch_prefs", android.content.Context.MODE_PRIVATE);
        prefs.edit().putFloat("screen_light_brightness", brightness).apply();
    }

    private int dpToPx(int dp) {
        return Math.round(dp * requireContext().getResources().getDisplayMetrics().density);
    }
}
