package com.fadcam.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.fadcam.R;

/**
 * HapticsSettingsFragment
 * Fine-grained control over vibration and haptic feedback app-wide.
 * The header holds the MASTER switch; the rows below are enabled/disabled
 * with it so the hierarchy is always clear. Recording events offer strength
 * presets (Off / Soft / Default / Strong / Custom ms); UI feedback groups
 * have their own toggles. A footer states the defaults and a reset row
 * restores them.
 */
public class HapticsSettingsFragment extends Fragment {

    private static final String PRESET_RESULT_PREFIX = "haptics_preset_";
    private static final String CUSTOM_RESULT_PREFIX = "haptics_custom_";

    private com.fadcam.SharedPreferencesManager prefs;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_haptics, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        com.fadcam.Utils.attachPressScaleToClickableRows(view);
        super.onViewCreated(view, savedInstanceState);

        prefs = com.fadcam.SharedPreferencesManager.getInstance(requireContext());

        // ── No-vibrator device: explain instead of letting the user guess ──
        boolean hasVibrator = false;
        try {
            android.os.Vibrator vib = (android.os.Vibrator)
                    requireContext().getSystemService(android.content.Context.VIBRATOR_SERVICE);
            hasVibrator = vib != null && vib.hasVibrator();
        } catch (Exception ignored) {
        }
        View noVibratorBanner = view.findViewById(R.id.haptics_no_vibrator_banner);
        if (noVibratorBanner != null) {
            noVibratorBanner.setVisibility(hasVibrator ? View.GONE : View.VISIBLE);
        }

        View back = view.findViewById(R.id.back_button);
        if (back != null) {
            back.setOnClickListener(v -> handleBack());
        }

        // ── Master switch (header) ──────────────────────────────────────────
        final com.fadcam.ui.AvatarToggleView master = view.findViewById(R.id.toggle_haptics_master);
        if (master != null) {
            // Without a vibration motor, the master is meaningless — disable it
            // so the banner + dimmed rows tell the whole story.
            master.setEnabled(hasVibrator);
            master.setAlpha(hasVibrator ? 1.0f : 0.45f);
            master.setChecked(prefs.isHapticFeedbackEnabled());
            master.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.setHapticFeedbackEnabled(isChecked);
                applyMasterState(view, isChecked);
            });
        }

        // ── Recording event rows: tap → preset sheet (incl. Custom ms) ─────
        final String startPresetKey = PRESET_RESULT_PREFIX + "start";
        getParentFragmentManager().setFragmentResultListener(startPresetKey, this, (key, result) -> {
            String selected = result.getString(
                    com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (selected == null) return;
            if (com.fadcam.SharedPreferencesManager.HAPTIC_PRESET_CUSTOM.equals(selected)) {
                openCustomMsSheet(true);
                return;
            }
            prefs.setHapticStartPreset(selected);
            refreshValues(view);
            previewStart();
        });
        view.findViewById(R.id.row_haptics_start).setOnClickListener(v -> {
            if (!prefs.isHapticFeedbackEnabled()) return;
            openPresetSheet(getString(R.string.settings_haptics_start_title),
                    prefs.getHapticStartPreset(), startPresetKey);
        });
        // Preview button: replay the current start vibration immediately.
        View previewStartBtn = view.findViewById(R.id.btn_preview_start);
        if (previewStartBtn != null) {
            previewStartBtn.setOnClickListener(v -> previewStart());
        }

        final String stopPresetKey = PRESET_RESULT_PREFIX + "stop";
        getParentFragmentManager().setFragmentResultListener(stopPresetKey, this, (key, result) -> {
            String selected = result.getString(
                    com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (selected == null) return;
            if (com.fadcam.SharedPreferencesManager.HAPTIC_PRESET_CUSTOM.equals(selected)) {
                openCustomMsSheet(false);
                return;
            }
            prefs.setHapticStopPreset(selected);
            refreshValues(view);
            previewStop();
        });
        view.findViewById(R.id.row_haptics_stop).setOnClickListener(v -> {
            if (!prefs.isHapticFeedbackEnabled()) return;
            openPresetSheet(getString(R.string.settings_haptics_stop_title),
                    prefs.getHapticStopPreset(), stopPresetKey);
        });
        // Preview button: replay the current stop vibration immediately.
        View previewStopBtn = view.findViewById(R.id.btn_preview_stop);
        if (previewStopBtn != null) {
            previewStopBtn.setOnClickListener(v -> previewStop());
        }

        // Custom-milliseconds result listeners (start / stop).
        getParentFragmentManager().setFragmentResultListener(
                CUSTOM_RESULT_PREFIX + "start", this, (key, result) -> {
                    if (result.containsKey(
                            com.fadcam.ui.picker.MaterialNumberPickerBottomSheetFragment.RESULT_NUMBER)) {
                        prefs.setHapticStartCustomMs(result.getInt(
                                com.fadcam.ui.picker.MaterialNumberPickerBottomSheetFragment.RESULT_NUMBER));
                        prefs.setHapticStartPreset(
                                com.fadcam.SharedPreferencesManager.HAPTIC_PRESET_CUSTOM);
                        refreshValues(view);
                        previewStart();
                    }
                });
        getParentFragmentManager().setFragmentResultListener(
                CUSTOM_RESULT_PREFIX + "stop", this, (key, result) -> {
                    if (result.containsKey(
                            com.fadcam.ui.picker.MaterialNumberPickerBottomSheetFragment.RESULT_NUMBER)) {
                        prefs.setHapticStopCustomMs(result.getInt(
                                com.fadcam.ui.picker.MaterialNumberPickerBottomSheetFragment.RESULT_NUMBER));
                        prefs.setHapticStopPreset(
                                com.fadcam.SharedPreferencesManager.HAPTIC_PRESET_CUSTOM);
                        refreshValues(view);
                        previewStop();
                    }
                });

        // ── UI feedback toggles (whole row clickable, switch is the feedback) ──
        final com.fadcam.ui.AvatarToggleView uiToggle = view.findViewById(R.id.toggle_haptics_ui);
        if (uiToggle != null) {
            uiToggle.setChecked(prefs.isHapticUiEnabled());
            uiToggle.setOnCheckedChangeListener((b, checked) -> prefs.setHapticUiEnabled(checked));
        }
        View uiRow = view.findViewById(R.id.row_haptics_ui);
        if (uiRow != null && uiToggle != null) {
            uiRow.setClickable(true);
            uiRow.setOnClickListener(v -> uiToggle.performClick());
        }

        final com.fadcam.ui.AvatarToggleView pickerToggle = view.findViewById(R.id.toggle_haptics_picker);
        if (pickerToggle != null) {
            pickerToggle.setChecked(prefs.isHapticPickerEnabled());
            pickerToggle.setOnCheckedChangeListener((b, checked) -> prefs.setHapticPickerEnabled(checked));
        }
        View pickerRow = view.findViewById(R.id.row_haptics_picker);
        if (pickerRow != null && pickerToggle != null) {
            pickerRow.setClickable(true);
            pickerRow.setOnClickListener(v -> pickerToggle.performClick());
        }

        // ── Torch shortcut: intensity presets (double pulse) ───────────────
        final String torchPresetKey = PRESET_RESULT_PREFIX + "torch";
        getParentFragmentManager().setFragmentResultListener(torchPresetKey, this, (key, result) -> {
            String selected = result.getString(
                    com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (selected == null) return;
            prefs.setHapticTorchPreset(selected);
            refreshValues(view);
            previewTorch();
        });
        view.findViewById(R.id.row_haptics_torch).setOnClickListener(v -> {
            if (!prefs.isHapticFeedbackEnabled()) return;
            openTorchPresetSheet();
        });
        View previewTorchBtn = view.findViewById(R.id.btn_preview_torch);
        if (previewTorchBtn != null) {
            previewTorchBtn.setOnClickListener(v -> previewTorch());
        }

        // ── Reset to defaults (with confirmation) ──────────────────────────
        view.findViewById(R.id.row_haptics_reset).setOnClickListener(v -> {
            if (!prefs.isHapticFeedbackEnabled()) return;
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.settings_haptics_reset_confirm_title)
                    .setMessage(R.string.settings_haptics_reset_confirm_message)
                    .setNegativeButton(R.string.duration_picker_cancel, null)
                    .setPositiveButton(R.string.settings_haptics_reset, (d, w) -> {
                        prefs.resetHapticSettings();
                        com.fadcam.Utils.vibrateRecordingStop(requireContext());
                        applyMasterState(view, true);
                        if (master != null) master.setChecked(true);
                        if (uiToggle != null) uiToggle.setChecked(true);
                        if (pickerToggle != null) pickerToggle.setChecked(true);
                        refreshValues(view);
                    })
                    .show();
        });

        applyMasterState(view, hasVibrator && prefs.isHapticFeedbackEnabled());
        refreshValues(view);
    }

    /** Enables/disables every row below the master switch with it. */
    private void applyMasterState(View view, boolean enabled) {
        float alpha = enabled ? 1.0f : 0.45f;
        int[] rowIds = {R.id.row_haptics_start, R.id.row_haptics_stop,
                R.id.row_haptics_ui, R.id.row_haptics_picker, R.id.row_haptics_torch};
        for (int id : rowIds) {
            View row = view.findViewById(id);
            if (row != null) {
                row.setEnabled(enabled);
                row.setAlpha(alpha);
            }
        }
        View reset = view.findViewById(R.id.row_haptics_reset);
        if (reset != null) {
            reset.setEnabled(enabled);
            reset.setAlpha(enabled ? 1.0f : 0.45f);
        }
    }

    /** Refreshes the per-event value subtitles (e.g. "Short pulse · 100 ms · Default"). */
    private void refreshValues(View view) {
        TextView startValue = view.findViewById(R.id.start_value);
        if (startValue != null) {
            startValue.setText(describeEvent(prefs.getHapticStartPreset(),
                    prefs.getHapticStartDurationMs()));
        }
        TextView stopValue = view.findViewById(R.id.stop_value);
        if (stopValue != null) {
            stopValue.setText(describeEvent(prefs.getHapticStopPreset(),
                    prefs.getHapticStopDurationMs()));
        }
        TextView torchValue = view.findViewById(R.id.torch_value);
        if (torchValue != null) {
            torchValue.setText(describePresetOnly(prefs.getHapticTorchPreset()));
        }
    }

    private String describePresetOnly(String preset) {
        if (com.fadcam.SharedPreferencesManager.HAPTIC_PRESET_OFF.equals(preset)) {
            return getString(R.string.settings_haptics_preset_off);
        }
        if (com.fadcam.SharedPreferencesManager.HAPTIC_PRESET_SOFT.equals(preset)) {
            return getString(R.string.settings_haptics_preset_soft);
        }
        if (com.fadcam.SharedPreferencesManager.HAPTIC_PRESET_STRONG.equals(preset)) {
            return getString(R.string.settings_haptics_preset_strong);
        }
        return getString(R.string.settings_haptics_preset_default);
    }

    private String describeEvent(String preset, long ms) {
        String presetLabel;
        if (com.fadcam.SharedPreferencesManager.HAPTIC_PRESET_OFF.equals(preset)) {
            presetLabel = getString(R.string.settings_haptics_preset_off);
        } else if (com.fadcam.SharedPreferencesManager.HAPTIC_PRESET_SOFT.equals(preset)) {
            presetLabel = getString(R.string.settings_haptics_preset_soft);
        } else if (com.fadcam.SharedPreferencesManager.HAPTIC_PRESET_STRONG.equals(preset)) {
            presetLabel = getString(R.string.settings_haptics_preset_strong);
        } else if (com.fadcam.SharedPreferencesManager.HAPTIC_PRESET_CUSTOM.equals(preset)) {
            presetLabel = getString(R.string.settings_haptics_preset_custom);
        } else {
            presetLabel = getString(R.string.settings_haptics_preset_default);
        }
        if (ms <= 0) {
            return presetLabel;
        }
        return getString(R.string.settings_haptics_value_format, presetLabel, ms);
    }

    /** Preset chooser (Off / Soft / Default / Strong / Custom…) for start/stop. */
    private void openPresetSheet(String title, String selected, String resultKey) {
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        items.add(com.fadcam.ui.picker.OptionItem.withLigature(
                com.fadcam.SharedPreferencesManager.HAPTIC_PRESET_OFF,
                getString(R.string.settings_haptics_preset_off), "block"));
        items.add(com.fadcam.ui.picker.OptionItem.withLigature(
                com.fadcam.SharedPreferencesManager.HAPTIC_PRESET_SOFT,
                getString(R.string.settings_haptics_preset_soft), "graphic_eq"));
        items.add(com.fadcam.ui.picker.OptionItem.withLigature(
                com.fadcam.SharedPreferencesManager.HAPTIC_PRESET_DEFAULT,
                getString(R.string.settings_haptics_preset_default), "equalizer"));
        items.add(com.fadcam.ui.picker.OptionItem.withLigature(
                com.fadcam.SharedPreferencesManager.HAPTIC_PRESET_STRONG,
                getString(R.string.settings_haptics_preset_strong), "surround_sound"));
        items.add(com.fadcam.ui.picker.OptionItem.withLigature(
                com.fadcam.SharedPreferencesManager.HAPTIC_PRESET_CUSTOM,
                getString(R.string.settings_haptics_preset_custom), "tune"));
        // Helper states the default for THIS event.
        String helper = resultKey.endsWith("start")
                ? getString(R.string.settings_haptics_start_helper)
                : getString(R.string.settings_haptics_stop_helper);
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
                com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
                        title, items, selected, resultKey, helper);
        sheet.show(getParentFragmentManager(), resultKey);
    }

    /** Torch shortcut: intensity presets (double pulse). */
    private void openTorchPresetSheet() {
        String title = getString(R.string.settings_haptics_torch_title);
        String resultKey = PRESET_RESULT_PREFIX + "torch";
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        items.add(com.fadcam.ui.picker.OptionItem.withLigature(
                com.fadcam.SharedPreferencesManager.HAPTIC_PRESET_OFF,
                getString(R.string.settings_haptics_preset_off), "block"));
        items.add(com.fadcam.ui.picker.OptionItem.withLigature(
                com.fadcam.SharedPreferencesManager.HAPTIC_PRESET_SOFT,
                getString(R.string.settings_haptics_preset_soft), "graphic_eq"));
        items.add(com.fadcam.ui.picker.OptionItem.withLigature(
                com.fadcam.SharedPreferencesManager.HAPTIC_PRESET_DEFAULT,
                getString(R.string.settings_haptics_preset_default), "equalizer"));
        items.add(com.fadcam.ui.picker.OptionItem.withLigature(
                com.fadcam.SharedPreferencesManager.HAPTIC_PRESET_STRONG,
                getString(R.string.settings_haptics_preset_strong), "surround_sound"));
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
                com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
                        title, items, prefs.getHapticTorchPreset(), resultKey,
                        getString(R.string.settings_haptics_torch_preset_helper));
        sheet.show(getParentFragmentManager(), resultKey);
    }

    /** Custom millisecond input via the app's number picker (with live preview). */
    private void openCustomMsSheet(boolean isStart) {
        final String resultKey = CUSTOM_RESULT_PREFIX + (isStart ? "start" : "stop");
        final int current = isStart ? prefs.getHapticStartCustomMs() : prefs.getHapticStopCustomMs();
        com.fadcam.ui.picker.MaterialNumberPickerBottomSheetFragment sheet =
                com.fadcam.ui.picker.MaterialNumberPickerBottomSheetFragment.newInstance(
                        getString(R.string.settings_haptics_custom_title),
                        0, 10000, current,
                        getString(R.string.settings_haptics_custom_hint),
                        0, 0, null, null, resultKey);
        if (sheet.getArguments() != null) {
            sheet.getArguments().putBoolean(
                    com.fadcam.ui.picker.MaterialNumberPickerBottomSheetFragment.ARG_SHOW_RESET, true);
            sheet.getArguments().putInt(
                    com.fadcam.ui.picker.MaterialNumberPickerBottomSheetFragment.ARG_DEFAULT_VALUE,
                    isStart ? 100 : 300);
            sheet.getArguments().putString(
                    com.fadcam.ui.picker.MaterialNumberPickerBottomSheetFragment.ARG_FOOTER,
                    getString(isStart
                            ? R.string.settings_haptics_custom_start_footer
                            : R.string.settings_haptics_custom_stop_footer));
            // Live preview inside the sheet: the play button vibrates the typed
            // value so the user never has to close the sheet to try it.
            sheet.getArguments().putBoolean(
                    com.fadcam.ui.picker.MaterialNumberPickerBottomSheetFragment.ARG_SHOW_PREVIEW, true);
        }
        sheet.show(getParentFragmentManager(), resultKey);
    }

    /** Replays the current torch double-pulse so the user feels the selection. */
    private void previewTorch() {
        com.fadcam.Utils.vibrateTorchShortcut(requireContext());
    }

    /** Replays the current start vibration so the user feels the selection. */
    private void previewStart() {
        com.fadcam.Utils.vibrateRecordingStart(requireContext());
    }

    /** Replays the current stop vibration so the user feels the selection. */
    private void previewStop() {
        com.fadcam.Utils.vibrateRecordingStop(requireContext());
    }

    private void handleBack() {
        if (getActivity() != null) {
            // Same navigation as every other settings sub-screen (no blank page).
            OverlayNavUtil.dismiss(requireActivity());
        }
    }
}
