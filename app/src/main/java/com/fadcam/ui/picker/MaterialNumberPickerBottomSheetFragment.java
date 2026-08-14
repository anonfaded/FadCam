package com.fadcam.ui.picker;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.NumberPicker;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import com.fadcam.MaximumRecordingDuration;
import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;

/**
 * Reusable Material wheel picker bottom sheet.
 *
 * <p>Two modes:
 * <ul>
 *   <li>{@link #MODE_INT} — a single numeric wheel with min/max validation,
 *       threshold hints, optional reset and description (drop-in replacement
 *       for the legacy {@code NumberInputBottomSheetFragment}).</li>
 *   <li>{@link #MODE_TIME_HMS} — Hours : Minutes : Seconds wheels for
 *       durations (0 s = no timer, maximum 24 h), used by the maximum recording
 *       duration setting and the home quick-action timer. Each column has a
 *       rounded, accent-colored value pill that opens typed input for that
 *       column; "0:00:00" (or the No-limit row in the options sheet) means no
 *       timer.</li>
 * </ul>
 *
 * <p>Result is delivered through {@link FragmentManager#setFragmentResult} under
 * {@link #RESULT_NUMBER} (INT mode) or {@link #RESULT_DURATION_SECONDS} (TIME mode).
 */
public class MaterialNumberPickerBottomSheetFragment extends BottomSheetDialogFragment {

    public static final String ARG_MODE = "mode";
    public static final int MODE_INT = 0;
    public static final int MODE_TIME_HMS = 1;

    public static final String ARG_TITLE = "title";
    public static final String ARG_MIN = "min";
    public static final String ARG_MAX = "max";
    public static final String ARG_VALUE = "value";
    public static final String ARG_RESULT_KEY = "result_key";
    public static final String ARG_HINT = "hint";
    public static final String ARG_LOW_MSG = "low_msg";
    public static final String ARG_HIGH_MSG = "high_msg";
    public static final String ARG_LOW_THRESHOLD = "low_threshold";
    public static final String ARG_HIGH_THRESHOLD = "high_threshold";
    public static final String ARG_DESCRIPTION = "description";
    public static final String ARG_FOOTER = "footer";
    public static final String ARG_DEFAULT_VALUE = "default_value";
    public static final String ARG_SHOW_RESET = "show_reset";
    public static final String ARG_ENABLE_TIMER_CALC = "enable_timer_calc";

    public static final String RESULT_NUMBER = "number_value";
    public static final String RESULT_DURATION_SECONDS = "duration_seconds";

    // ── INT-mode factory (legacy-compatible) ────────────────────────────────
    public static MaterialNumberPickerBottomSheetFragment newInstance(
            String title, int min, int max, int value, String hint,
            int lowThreshold, int highThreshold, String lowMsg, String highMsg,
            String resultKey) {
        MaterialNumberPickerBottomSheetFragment f =
                new MaterialNumberPickerBottomSheetFragment();
        Bundle b = new Bundle();
        b.putInt(ARG_MODE, MODE_INT);
        b.putString(ARG_TITLE, title);
        b.putInt(ARG_MIN, min);
        b.putInt(ARG_MAX, max);
        b.putInt(ARG_VALUE, value);
        b.putString(ARG_HINT, hint);
        b.putInt(ARG_LOW_THRESHOLD, lowThreshold);
        b.putInt(ARG_HIGH_THRESHOLD, highThreshold);
        b.putString(ARG_LOW_MSG, lowMsg);
        b.putString(ARG_HIGH_MSG, highMsg);
        b.putString(ARG_RESULT_KEY, resultKey);
        f.setArguments(b);
        return f;
    }

    // ── TIME_HMS-mode factory ───────────────────────────────────────────────
    public static MaterialNumberPickerBottomSheetFragment newTimeInstance(
            String title, int initialSeconds, String resultKey) {
        MaterialNumberPickerBottomSheetFragment f =
                new MaterialNumberPickerBottomSheetFragment();
        Bundle b = new Bundle();
        b.putInt(ARG_MODE, MODE_TIME_HMS);
        b.putString(ARG_TITLE, title);
        b.putInt(ARG_VALUE, initialSeconds);
        b.putString(ARG_RESULT_KEY, resultKey);
        f.setArguments(b);
        return f;
    }

    private int mode;
    private String title, hint, lowMsg, highMsg, resultKey;
    private int min, max, value, lowTh, highTh, defaultValue;
    private boolean showReset, enableTimerCalc;
    private String descriptionText;

    private NumberPicker npSingle;
    private NumberPicker npHours;
    private NumberPicker npMinutes;
    private NumberPicker npSeconds;
    private TextView summaryView;
    private TextView helperView;
    private TextView descriptionView;
    private TextView footerView;
    private MaterialButton resetButton;
    private MaterialButton okButton;
    private com.google.android.material.textfield.TextInputLayout editContainer;
    private com.google.android.material.textfield.TextInputEditText editField;
    private boolean syncingField; // guards wheel↔field echo
    private TextView pillHours;
    private TextView pillMinutes;
    private TextView pillSeconds;

    @Override
    public int getTheme() {
        return R.style.CustomBottomSheetDialogTheme;
    }

    @Override
    public android.app.Dialog onCreateDialog(Bundle savedInstanceState) {
        android.app.Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((com.google.android.material.bottomsheet.BottomSheetDialog) dialog)
                .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackgroundResource(R.drawable.picker_bottom_sheet_gradient_bg_dynamic);
            }
        });
        if (dialog.getWindow() != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            dialog.getWindow().setNavigationBarColor(android.graphics.Color.BLACK);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                dialog.getWindow().setNavigationBarContrastEnforced(false);
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                int flags = dialog.getWindow().getDecorView().getSystemUiVisibility();
                dialog.getWindow().getDecorView().setSystemUiVisibility(
                    flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                );
            }
        }
        return dialog;
    }

    private Integer previousActivityNavBarColor;
    private Boolean previousActivityNavContrastEnforced;

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().setDimAmount(0.5f);
            dialog.getWindow().setBackgroundDrawableResource(
                    android.R.color.transparent);
        }
        if (getActivity() != null && getActivity().getWindow() != null
                && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            android.view.Window window = getActivity().getWindow();
            previousActivityNavBarColor = window.getNavigationBarColor();
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                previousActivityNavContrastEnforced = window.isNavigationBarContrastEnforced();
                window.setNavigationBarContrastEnforced(false);
            }
            window.setNavigationBarColor(android.graphics.Color.BLACK);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                int flags = window.getDecorView().getSystemUiVisibility();
                window.getDecorView().setSystemUiVisibility(flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            }
        }
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        if (getActivity() != null && getActivity().getWindow() != null
                && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP
                && previousActivityNavBarColor != null) {
            android.view.Window window = getActivity().getWindow();
            window.setNavigationBarColor(previousActivityNavBarColor);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
                    && previousActivityNavContrastEnforced != null) {
                window.setNavigationBarContrastEnforced(previousActivityNavContrastEnforced);
            }
        }
        super.onDismiss(dialog);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.material_number_picker_bottom_sheet,
                container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Bundle a = getArguments();
        if (a == null) return;

        mode = a.getInt(ARG_MODE, MODE_INT);
        title = a.getString(ARG_TITLE, "");
        min = a.getInt(ARG_MIN, 0);
        max = a.getInt(ARG_MAX, Integer.MAX_VALUE);
        value = a.getInt(ARG_VALUE, min);
        hint = a.getString(ARG_HINT, "");
        lowTh = a.getInt(ARG_LOW_THRESHOLD, -1);
        highTh = a.getInt(ARG_HIGH_THRESHOLD, -1);
        lowMsg = a.getString(ARG_LOW_MSG, "");
        highMsg = a.getString(ARG_HIGH_MSG, "");
        resultKey = a.getString(ARG_RESULT_KEY, "number_input_result");
        defaultValue = a.getInt(ARG_DEFAULT_VALUE, value);
        showReset = a.getBoolean(ARG_SHOW_RESET, false);
        enableTimerCalc = a.getBoolean(ARG_ENABLE_TIMER_CALC, false);
        descriptionText = a.getString(ARG_DESCRIPTION, null);

        TextView titleView = view.findViewById(R.id.picker_title);
        if (titleView != null) titleView.setText(title);

        descriptionView = view.findViewById(R.id.picker_description);
        if (descriptionView != null) {
            if (descriptionText != null && !descriptionText.isEmpty()) {
                descriptionView.setText(descriptionText);
                descriptionView.setVisibility(View.VISIBLE);
            } else {
                descriptionView.setVisibility(View.GONE);
            }
        }

        summaryView = view.findViewById(R.id.picker_summary);
        helperView = view.findViewById(R.id.picker_helper);
        footerView = view.findViewById(R.id.picker_footer);
        okButton = view.findViewById(R.id.btn_picker_ok);
        MaterialButton cancelButton = view.findViewById(R.id.btn_picker_cancel);
        resetButton = view.findViewById(R.id.btn_picker_reset);
        editContainer = view.findViewById(R.id.picker_edit_container);
        editField = view.findViewById(R.id.picker_edit_field);

        View intWheels = view.findViewById(R.id.picker_int_wheel_container);
        View timeWheels = view.findViewById(R.id.picker_time_wheel_container);

        npSingle = view.findViewById(R.id.np_picker_single);
        npHours = view.findViewById(R.id.np_duration_hours);
        npMinutes = view.findViewById(R.id.np_duration_minutes);
        npSeconds = view.findViewById(R.id.np_duration_seconds);

        pillHours = view.findViewById(R.id.picker_pill_value_hours);
        pillMinutes = view.findViewById(R.id.picker_pill_value_minutes);
        pillSeconds = view.findViewById(R.id.picker_pill_value_seconds);

        // Back chevron: consistent with the options sheet — dismiss returns
        // to the previous sheet.
        View backBtn = view.findViewById(R.id.picker_back_btn);
        if (backBtn != null) {
            backBtn.setOnClickListener(v -> dismiss());
        }

        int dividerColor = 0x40FFFFFF;
        int selectedColor = 0xFFFFFFFF;
        try {
            if (getContext() != null) {
                dividerColor = getContext().getColor(R.color.picker_divider_color);
                selectedColor = getContext().getColor(R.color.picker_selected_text_color);
            }
        } catch (Exception ignored) {
        }

        if (mode == MODE_TIME_HMS) {
            if (intWheels != null) intWheels.setVisibility(View.GONE);
            if (timeWheels != null) timeWheels.setVisibility(View.VISIBLE);

            int[] hms = MaximumRecordingDuration.splitToHms(
                    Math.max(0, Math.min(MaximumRecordingDuration.MAX_CUSTOM_SECONDS, value)));
            setupWheel(npHours, 0, 24, hms[0], dividerColor, selectedColor);
            setupWheel(npMinutes, 0, 59, hms[1], dividerColor, selectedColor);
            setupWheel(npSeconds, 0, 59, hms[2], dividerColor, selectedColor);

            npHours.setOnValueChangedListener((p, o, n) -> {
                if (n >= 24) { npHours.setValue(24); npMinutes.setValue(0); npSeconds.setValue(0); }
                tickHaptic();
                updateTimeSummary();
            });
            npMinutes.setOnValueChangedListener((p, o, n) -> {
                if (npHours.getValue() >= 24) { npMinutes.setValue(0); npSeconds.setValue(0); }
                tickHaptic();
                updateTimeSummary();
            });
            npSeconds.setOnValueChangedListener((p, o, n) -> {
                if (npHours.getValue() >= 24) { npSeconds.setValue(0); }
                tickHaptic();
                updateTimeSummary();
            });

            // Per-column undo/reset: show icon only when the column differs from 0.
            final int[] resetIds = {R.id.btn_reset_hours, R.id.btn_reset_minutes, R.id.btn_reset_seconds};
            final NumberPicker[] wheels = {npHours, npMinutes, npSeconds};
            for (int i = 0; i < resetIds.length; i++) {
                final int idx = i;
                final View reset = view.findViewById(resetIds[i]);
                if (reset != null) {
                    reset.setOnClickListener(v -> {
                        wheels[idx].setValue(0);
                        confirmHaptic();
                        updateTimeSummary();
                    });
                }
            }
            refreshResetIconVisibility();

            if (helperView != null) {
                helperView.setText(hint != null && !hint.isEmpty()
                        ? hint : getString(R.string.duration_picker_helper_hint));
            }
            if (resetButton != null) resetButton.setVisibility(View.GONE);

            // TIME mode uses per-column pills for typed input; the shared
            // field below is INT-mode only.
            if (editContainer != null) editContainer.setVisibility(View.GONE);

            // Rounded, accent-colored value pills: tap one to type a value
            // for that column (hours 0..24, minutes/seconds 0..59).
            bindPill(view, R.id.picker_pill_hours, npHours, 24, R.string.duration_picker_hours);
            bindPill(view, R.id.picker_pill_minutes, npMinutes, 59, R.string.duration_picker_minutes);
            bindPill(view, R.id.picker_pill_seconds, npSeconds, 59, R.string.duration_picker_seconds);
            updatePillValues();

            // Caption above the hero summary: labels what is being chosen.
            TextView caption = view.findViewById(R.id.picker_summary_caption);
            if (caption != null) caption.setVisibility(View.VISIBLE);

            // Optional footer (e.g. "Auto-stops & saves…") — subtle info line.
            String footer = a.getString(ARG_FOOTER, null);
            if (footerView != null) {
                if (footer != null && !footer.isEmpty()) {
                    footerView.setText(footer);
                    footerView.setVisibility(View.VISIBLE);
                } else {
                    footerView.setVisibility(View.GONE);
                }
            }
            updateTimeSummary();
        } else {
            if (timeWheels != null) timeWheels.setVisibility(View.GONE);
            if (intWheels != null) intWheels.setVisibility(View.VISIBLE);

            setupWheel(npSingle, min, max, value, dividerColor, selectedColor);
            npSingle.setOnValueChangedListener((p, o, n) -> {
                tickHaptic();
                syncFieldFromWheel();
                validate();
            });
            // Editable field: typing is validated live and echoed to the wheel
            // (clamped), so large ranges (e.g. debug lines) don't need wheel scrolling.
            if (editContainer != null && editField != null) {
                editField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                editField.setText(String.valueOf(value));
                editField.addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                    @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                        if (syncingField) return;
                        int parsed = parseFieldText();
                        if (parsed >= 0) {
                            syncingField = true;
                            npSingle.setValue(Math.max(min, Math.min(max, parsed)));
                            syncingField = false;
                        }
                        validate();
                    }
                    @Override public void afterTextChanged(android.text.Editable s) {}
                });
                editField.setOnEditorActionListener((v, actionId, event) -> {
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                        editContainer.setError(null);
                        v.clearFocus();
                        android.view.inputmethod.InputMethodManager imm =
                                (android.view.inputmethod.InputMethodManager) requireContext()
                                        .getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                        deliverResult();
                        return true;
                    }
                    return false;
                });
            }
            if (resetButton != null) {
                if (showReset) {
                    resetButton.setVisibility(View.VISIBLE);
                    resetButton.setOnClickListener(v -> {
                        npSingle.setValue(defaultValue);
                        syncFieldFromWheel();
                        validate();
                        confirmHaptic();
                    });
                } else {
                    resetButton.setVisibility(View.GONE);
                }
            }
            if (summaryView != null) summaryView.setVisibility(View.GONE);
            TextView caption = view.findViewById(R.id.picker_summary_caption);
            if (caption != null) caption.setVisibility(View.GONE);
            if (helperView != null) {
                helperView.setText(getString(R.string.number_input_default_helper));
            }
            validate();
        }

        if (cancelButton != null) cancelButton.setOnClickListener(v -> dismiss());
        if (okButton != null) okButton.setOnClickListener(v -> {
            confirmHaptic();
            deliverResult();
        });
    }

    private int parseFieldText() {
        try {
            String t = editField != null && editField.getText() != null
                    ? editField.getText().toString().trim() : "";
            return t.isEmpty() ? -1 : Integer.parseInt(t);
        } catch (Exception e) {
            return -1;
        }
    }

    private void syncFieldFromWheel() {
        if (editField == null) return;
        syncingField = true;
        editField.setText(String.valueOf(npSingle.getValue()));
        editField.setSelection(editField.getText().length());
        syncingField = false;
    }

    private void refreshResetIconVisibility() {
        if (npHours == null) return;
        updateResetIcon(R.id.btn_reset_hours, npHours.getValue() != 0);
        updateResetIcon(R.id.btn_reset_minutes, npMinutes.getValue() != 0);
        updateResetIcon(R.id.btn_reset_seconds, npSeconds.getValue() != 0);
    }

    private void updateResetIcon(int id, boolean visible) {
        View v = getView() == null ? null : getView().findViewById(id);
        if (v != null) v.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    private void tickHaptic() {
        try {
            if (getView() != null) {
                getView().performHapticFeedback(
                        android.view.HapticFeedbackConstants.CLOCK_TICK);
            }
        } catch (Exception ignored) {
        }
    }

    private void confirmHaptic() {
        try {
            if (getView() != null) {
                getView().performHapticFeedback(
                        android.view.HapticFeedbackConstants.CONFIRM);
            }
        } catch (Exception ignored) {
        }
    }

    private void deliverResult() {
        if (resultKey == null) return;
        Bundle result = new Bundle();
        if (mode == MODE_TIME_HMS) {
            int total = MaximumRecordingDuration.combineFromHms(
                    npHours.getValue(), npMinutes.getValue(), npSeconds.getValue());
            if (total > MaximumRecordingDuration.MAX_CUSTOM_SECONDS) {
                total = MaximumRecordingDuration.MAX_CUSTOM_SECONDS;
            }
            result.putInt(RESULT_DURATION_SECONDS, total);
        } else {
            result.putInt(RESULT_NUMBER, npSingle.getValue());
        }
        getParentFragmentManager().setFragmentResult(resultKey, result);
        dismiss();
    }

    private void updateTimeSummary() {
        refreshResetIconVisibility();
        updatePillValues();
        if (summaryView == null) return;
        int total = MaximumRecordingDuration.combineFromHms(
                npHours.getValue(), npMinutes.getValue(), npSeconds.getValue());
        int h = total / 3600;
        int m = (total % 3600) / 60;
        int s = total % 60;
        String text;
        if (h > 0 && m > 0 && s > 0) text = h + "h " + m + "m " + s + "s";
        else if (h > 0 && m > 0) text = h + "h " + m + "m";
        else if (h > 0 && s > 0) text = h + "h " + s + "s";
        else if (h > 0) text = h + "h";
        else if (m > 0 && s > 0) text = m + "m " + s + "s";
        else if (m > 0) text = m + "m";
        else text = s + "s";
        summaryView.setTextColor(getResources().getColor(
                R.color.picker_selected_text_color, requireContext().getTheme()));
        // Zero on every column means "no timer".
        summaryView.setText(total == 0
                ? getString(R.string.maximum_recording_duration_no_limit)
                : text);
    }

    /** Wires a column's value pill: tap opens a type-in dialog for that column. */
    private void bindPill(View root, int pillId, final NumberPicker wheel,
            final int maxValue, int titleRes) {
        View pill = root.findViewById(pillId);
        if (pill != null) {
            pill.setOnClickListener(v -> showColumnInputDialog(wheel, maxValue, titleRes));
        }
    }

    /** Echoes the wheel values into the per-column pills (padded, e.g. "05"). */
    private void updatePillValues() {
        if (pillHours != null) {
            pillHours.setText(String.format(java.util.Locale.US, "%02d", npHours.getValue()));
        }
        if (pillMinutes != null) {
            pillMinutes.setText(String.format(java.util.Locale.US, "%02d", npMinutes.getValue()));
        }
        if (pillSeconds != null) {
            pillSeconds.setText(String.format(java.util.Locale.US, "%02d", npSeconds.getValue()));
        }
    }

    /** Type-in dialog for a single column (hours 0..24, minutes/seconds 0..59). */
    private void showColumnInputDialog(final NumberPicker wheel,
            final int maxValue, int titleRes) {
        final android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(wheel.getValue()));
        input.setSelection(input.getText().length());
        input.setSingleLine(true);
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle(titleRes)
                .setView(input)
                .setNegativeButton(R.string.duration_picker_cancel, null)
                .setPositiveButton(R.string.duration_picker_ok, (d, w) -> {
                    try {
                        int v = Integer.parseInt(input.getText().toString().trim());
                        v = Math.max(0, Math.min(maxValue, v));
                        wheel.setValue(v);
                        // 24h is the max — nothing can follow it.
                        if (wheel == npHours && v >= 24) {
                            npMinutes.setValue(0);
                            npSeconds.setValue(0);
                        }
                        confirmHaptic();
                        updateTimeSummary();
                    } catch (Exception ignored) {
                    }
                })
                .show();
    }

    private void validate() {
        if (npSingle == null || helperView == null || okButton == null) return;
        int val = npSingle.getValue();
        if (val < min) {
            helperView.setText(getString(R.string.universal_min_value, min));
            helperView.setTextColor(getResources().getColor(
                    android.R.color.holo_red_light, requireContext().getTheme()));
            okButton.setEnabled(false);
            return;
        }
        if (val > max) {
            helperView.setText(getString(R.string.universal_max_value, max));
            helperView.setTextColor(getResources().getColor(
                    android.R.color.holo_red_light, requireContext().getTheme()));
            okButton.setEnabled(false);
            return;
        }
        if (lowTh > 0 && val < lowTh && !lowMsg.isEmpty()) {
            helperView.setText(lowMsg);
            helperView.setTextColor(getResources().getColor(
                    android.R.color.holo_orange_light, requireContext().getTheme()));
        } else if (highTh > 0 && val > highTh && !highMsg.isEmpty()) {
            helperView.setText(highMsg);
            helperView.setTextColor(getResources().getColor(
                    android.R.color.holo_red_light, requireContext().getTheme()));
        } else {
            helperView.setText(getString(R.string.number_input_ok_helper));
            helperView.setTextColor(getResources().getColor(
                    android.R.color.holo_green_light, requireContext().getTheme()));
        }
        okButton.setEnabled(true);
        // Clear any stale field error once valid.
        if (editContainer != null) {
            editContainer.setError(null);
        }
    }

    private void setupWheel(NumberPicker picker, int minV, int maxV, int init,
            int dividerColor, int selectedColor) {
        picker.setMinValue(minV);
        picker.setMaxValue(maxV);
        picker.setValue(Math.max(minV, Math.min(maxV, init)));
        picker.setWrapSelectorWheel(false);
        try {
            picker.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);
        } catch (Exception ignored) {
        }
        try {
            android.graphics.drawable.GradientDrawable divider =
                    new android.graphics.drawable.GradientDrawable();
            divider.setColor(dividerColor);
            divider.setSize(1, 2);
            java.lang.reflect.Method md = NumberPicker.class.getMethod(
                    "setSelectionDivider", android.graphics.drawable.Drawable.class);
            md.invoke(picker, divider);
        } catch (Exception ignored) {
            try {
                picker.setSelectionDividerHeight(2);
            } catch (Exception ignored2) {
            }
        }
        try {
            java.lang.reflect.Method m = NumberPicker.class.getMethod(
                    "setTextColor", int.class);
            m.invoke(picker, selectedColor);
        } catch (Exception ignored) {
        }
    }

    public void show(FragmentManager manager) {
        show(manager, "material_number_picker");
    }
}
