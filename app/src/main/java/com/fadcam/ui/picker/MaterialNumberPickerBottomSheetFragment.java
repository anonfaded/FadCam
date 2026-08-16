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
    public static final int MODE_MBGB = 2; // two-column MB | GB (video split size)

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
    public static final String ARG_DEFAULT_VALUE_2 = "default_value_2";
    public static final String ARG_SHOW_RESET = "show_reset";
    public static final String ARG_ENABLE_TIMER_CALC = "enable_timer_calc";
    public static final String ARG_SHOW_PREVIEW = "show_preview";
    // ── Two-value (MB|GB, torch beats…) configuration ─────────────────────
    public static final String ARG_TWO_LABEL1 = "two_label1";
    public static final String ARG_TWO_LABEL2 = "two_label2";
    public static final String ARG_TWO_MIN1 = "two_min1";
    public static final String ARG_TWO_MAX1 = "two_max1";
    public static final String ARG_TWO_MIN2 = "two_min2";
    public static final String ARG_TWO_MAX2 = "two_max2";
    public static final String ARG_TWO_VALUE1 = "two_value1";
    public static final String ARG_TWO_VALUE2 = "two_value2";
    public static final String ARG_TWO_SEPARATE_FORMAT = "two_separate_format";
    /** When true, the FIRST value's column is moved to the LEFT (GB/MB layout
     *  keeps GB first by default; torch pulses put beat 1 on the left). */
    public static final String ARG_TWO_SWAP_ORDER = "two_swap_order";

    public static final String RESULT_NUMBER = "number_value";
    public static final String RESULT_DURATION_SECONDS = "duration_seconds";
    public static final String RESULT_VALUE_A = "two_value_a";
    public static final String RESULT_VALUE_B = "two_value_b";

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

    // ── MB/GB-mode factory (video split size) ────────────────────────────
    public static MaterialNumberPickerBottomSheetFragment newMbGbInstance(
            String title, int initialTotalMb, String resultKey) {
        MaterialNumberPickerBottomSheetFragment f =
                new MaterialNumberPickerBottomSheetFragment();
        Bundle b = new Bundle();
        b.putInt(ARG_MODE, MODE_MBGB);
        b.putString(ARG_TITLE, title);
        b.putInt(ARG_VALUE, Math.max(0, initialTotalMb));
        b.putString(ARG_RESULT_KEY, resultKey);
        f.setArguments(b);
        return f;
    }

    // ── Generic two-wheel factory (torch heartbeat pulses…) ─────────────
    /** Both values are editable together; results arrive as {@link #RESULT_VALUE_A}
     *  and {@link #RESULT_VALUE_B} (RESULT_NUMBER keeps the MB/GB combination). */
    public static MaterialNumberPickerBottomSheetFragment newTwoValueInstance(
            String title, String label1, String label2,
            int value1, int min1, int max1,
            int value2, int min2, int max2,
            String resultKey) {
        MaterialNumberPickerBottomSheetFragment f =
                new MaterialNumberPickerBottomSheetFragment();
        Bundle b = new Bundle();
        b.putInt(ARG_MODE, MODE_MBGB);
        b.putString(ARG_TITLE, title);
        b.putString(ARG_RESULT_KEY, resultKey);
        b.putString(ARG_TWO_LABEL1, label1);
        b.putString(ARG_TWO_LABEL2, label2);
        b.putInt(ARG_TWO_MIN1, min1);
        b.putInt(ARG_TWO_MAX1, max1);
        b.putInt(ARG_TWO_MIN2, min2);
        b.putInt(ARG_TWO_MAX2, max2);
        b.putInt(ARG_TWO_VALUE1, value1);
        b.putInt(ARG_TWO_VALUE2, value2);
        b.putBoolean(ARG_TWO_SEPARATE_FORMAT, true);
        b.putBoolean(ARG_TWO_SWAP_ORDER, true);
        f.setArguments(b);
        return f;
    }

    private int mode;
    private String title, hint, lowMsg, highMsg, resultKey;
    private int min, max, value, lowTh, highTh, defaultValue;
    private boolean showReset, enableTimerCalc;
    private boolean showPreview;
    private String descriptionText;

    private NumberPicker npSingle;
    private NumberPicker npHours;
    private NumberPicker npMinutes;
    private NumberPicker npSeconds;
    private NumberPicker npMb;
    private NumberPicker npGb;

    private int measuredContentHeightPx;
    private TextView summaryView;
    private TextView helperView;
    private TextView footerView;
    private MaterialButton resetButton;
    private MaterialButton okButton;

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
                // Native sheet navigation: the sheet follows the finger and
                // settles by velocity. Collapsed peek mirrors the natural
                // content height (capped at 60% of the screen so zoomed
                // phones still get a drag-to-expand sheet); expanded stops
                // 8% short of the top so it never looks like a full-screen
                // takeover. NumberPicker wheels opt out of parent
                // interception so wheel scrolling never moves the sheet.
                try {
                    com.google.android.material.bottomsheet.BottomSheetBehavior<View> behavior =
                            com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet);
                    int screenHeightPx = bottomSheet.getResources()
                            .getDisplayMetrics().heightPixels;
                    int peek = measuredContentHeightPx > 0
                            ? Math.min(measuredContentHeightPx, (int) (screenHeightPx * 0.6f))
                            : (int) (screenHeightPx * 0.6f);
                    behavior.setFitToContents(false);
                    behavior.setSkipCollapsed(false);
                    behavior.setHideable(true);
                    behavior.setPeekHeight(peek);
                    behavior.setExpandedOffset((int) (screenHeightPx * 0.08f));
                } catch (Exception ignored) {
                }
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
        View view = inflater.inflate(R.layout.material_number_picker_bottom_sheet,
                container, false);
        // Measure the sheet's natural (unconstrained) height so the collapsed
        // peek can mirror wrap-content on normal phones.
        view.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        measuredContentHeightPx = view.getMeasuredHeight();
        return view;
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
        showPreview = a.getBoolean(ARG_SHOW_PREVIEW, false);
        enableTimerCalc = a.getBoolean(ARG_ENABLE_TIMER_CALC, false);
        descriptionText = a.getString(ARG_DESCRIPTION, null);

        TextView titleView = view.findViewById(R.id.picker_title);
        if (titleView != null) titleView.setText(title);

        summaryView = view.findViewById(R.id.picker_summary);
        helperView = view.findViewById(R.id.picker_helper);
        footerView = view.findViewById(R.id.picker_footer);
        okButton = view.findViewById(R.id.btn_picker_ok);
        MaterialButton cancelButton = view.findViewById(R.id.btn_picker_cancel);
        resetButton = view.findViewById(R.id.btn_picker_reset);

        View intWheels = view.findViewById(R.id.picker_int_wheel_container);
        View timeWheels = view.findViewById(R.id.picker_time_wheel_container);
        View mbgbWheels = view.findViewById(R.id.picker_mbgb_wheel_container);

        npSingle = view.findViewById(R.id.np_picker_single);
        npHours = view.findViewById(R.id.np_duration_hours);
        npMinutes = view.findViewById(R.id.np_duration_minutes);
        npSeconds = view.findViewById(R.id.np_duration_seconds);
        npMb = view.findViewById(R.id.np_mb);
        npGb = view.findViewById(R.id.np_gb);

        // Close button: consistent with every other bottom sheet (X icon).
        View closeBtn = view.findViewById(R.id.picker_close_btn);
        if (closeBtn != null) {
            closeBtn.setOnClickListener(v -> dismiss());
        }

        // Native sheet dragging handles the handle strip + empty areas (drag
        // up expands, drag down dismisses, sheet follows the finger). The
        // wheels opt out of parent interception so scrolling them never
        // moves the sheet.
        protectWheelFromSheetDrag(npSingle);
        protectWheelFromSheetDrag(npHours);
        protectWheelFromSheetDrag(npMinutes);
        protectWheelFromSheetDrag(npSeconds);
        protectWheelFromSheetDrag(npMb);
        protectWheelFromSheetDrag(npGb);

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

            // INT-mode helper is replaced by the hint inside the summary card.
            if (helperView != null) helperView.setVisibility(View.GONE);
            TextView timeHelper = view.findViewById(R.id.picker_time_helper);
            if (timeHelper != null) {
                timeHelper.setText(hint != null && !hint.isEmpty()
                        ? hint : getString(R.string.duration_picker_helper_hint));
                timeHelper.setVisibility(View.VISIBLE);
            }
            View summaryCard = view.findViewById(R.id.picker_summary_card);
            if (summaryCard != null) summaryCard.setVisibility(View.VISIBLE);
            if (resetButton != null) resetButton.setVisibility(View.GONE);

            // Center-cell band drawn INSIDE each wheel (background): digits
            // scroll through it and the selected value sits in the highlighted
            // cell in real time. Tap the center row → type-in dialog.
            styleCenterCellPicker(npHours, 0, 24, getString(R.string.duration_picker_hours));
            styleCenterCellPicker(npMinutes, 0, 59, getString(R.string.duration_picker_minutes));
            styleCenterCellPicker(npSeconds, 0, 59, getString(R.string.duration_picker_seconds));

            // Caption above the hero summary: labels what is being chosen.
            TextView caption = view.findViewById(R.id.picker_summary_caption);
            if (caption != null) caption.setVisibility(View.VISIBLE);

            updateTimeSummary();
        } else if (mode == MODE_MBGB) {
            if (intWheels != null) intWheels.setVisibility(View.GONE);
            if (timeWheels != null) timeWheels.setVisibility(View.GONE);
            if (mbgbWheels != null) mbgbWheels.setVisibility(View.VISIBLE);

            // Two-value mode: MB/GB by default (video split), or caller-provided
            // labels/ranges (e.g. torch heartbeat pulses).
            String label1 = a.getString(ARG_TWO_LABEL1, getString(R.string.video_split_unit_mb));
            String label2 = a.getString(ARG_TWO_LABEL2, getString(R.string.video_split_unit_gb));
            int min1 = a.getInt(ARG_TWO_MIN1, 0);
            int max1 = a.getInt(ARG_TWO_MAX1, 1023);
            int min2 = a.getInt(ARG_TWO_MIN2, 0);
            int max2 = a.getInt(ARG_TWO_MAX2, 100);

            int v1;
            int v2;
            if (a.containsKey(ARG_TWO_VALUE1)) {
                v1 = a.getInt(ARG_TWO_VALUE1);
                v2 = a.getInt(ARG_TWO_VALUE2);
            } else {
                int totalMb = Math.max(0, value);
                v1 = totalMb % 1024;
                v2 = totalMb / 1024;
            }
            setupWheel(npMb, min1, max1, v1, dividerColor, selectedColor);
            setupWheel(npGb, min2, max2, v2, dividerColor, selectedColor);
            npMb.setOnValueChangedListener((p, o, n) -> {
                tickHaptic();
                updateMbGbSummary();
            });
            npGb.setOnValueChangedListener((p, o, n) -> {
                tickHaptic();
                updateMbGbSummary();
            });
            // Same center-cell design as TIME mode: tap to type, drags scroll.
            styleCenterCellPicker(npMb, min1, max1, label1);
            styleCenterCellPicker(npGb, min2, max2, label2);

            // Column labels from the caller (MB/GB by default).
            TextView labelView1 = view.findViewById(R.id.np_label_value1);
            if (labelView1 != null) labelView1.setText(label1);
            TextView labelView2 = view.findViewById(R.id.np_label_value2);
            if (labelView2 != null) labelView2.setText(label2);

            // Optional column order swap: the caller can request the FIRST
            // value's column on the LEFT (torch: beat 1 | beat 2), while the
            // default layout keeps GB | MB for the video-split sheet.
            if (getArguments() != null
                    && getArguments().getBoolean(ARG_TWO_SWAP_ORDER, false)) {
                reorderColumnsToLeft(view);
            }

            // Summary card: compact single-line hero. The column labels already
            // explain each wheel, so the caption + tap hint are suppressed here
            // (they belong to the TIME/INT sheets); the caller's description
            // goes to the footer.
            View summaryCard = view.findViewById(R.id.picker_summary_card);
            if (summaryCard != null) summaryCard.setVisibility(View.VISIBLE);
            TextView caption = view.findViewById(R.id.picker_summary_caption);
            if (caption != null) caption.setVisibility(View.GONE);
            TextView timeHelper = view.findViewById(R.id.picker_time_helper);
            if (timeHelper != null) timeHelper.setVisibility(View.GONE);
            if (summaryView != null) {
                summaryView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f);
                summaryView.setVisibility(View.VISIBLE);
            }
            updateMbGbSummary();

            // Optional live preview: play the REAL heartbeat with the currently
            // selected pulse lengths (1st beat = left wheel, 2nd = right).
            View previewBtn = view.findViewById(R.id.btn_picker_preview);
            if (previewBtn != null) {
                if (showPreview) {
                    previewBtn.setVisibility(View.VISIBLE);
                    previewBtn.setOnClickListener(v -> {
                        int strong;
                        int soft;
                        try {
                            com.fadcam.SharedPreferencesManager prefs =
                                    com.fadcam.SharedPreferencesManager.getInstance(requireContext());
                            String preset = prefs.getHapticTorchPreset();
                            if (com.fadcam.SharedPreferencesManager.HAPTIC_PRESET_SOFT.equals(preset)) {
                                strong = 96;
                                soft = 48;
                            } else if (com.fadcam.SharedPreferencesManager.HAPTIC_PRESET_STRONG.equals(preset)) {
                                strong = 255;
                                soft = 128;
                            } else {
                                strong = 192;
                                soft = 96;
                            }
                        } catch (Exception e) {
                            strong = 192;
                            soft = 96;
                        }
                        com.fadcam.Utils.vibrateTorchPulses(
                                requireContext(), npMb.getValue(), npGb.getValue(), true, strong, soft);
                    });
                } else {
                    previewBtn.setVisibility(View.GONE);
                }
            }

            // Reset → ARG_DEFAULT_VALUE (MB/GB: e.g. 2048 MB = 2 GB) or, in
            // separate mode, ARG_DEFAULT_VALUE_2 for the second wheel.
            if (resetButton != null) {
                if (showReset) {
                    resetButton.setVisibility(View.VISIBLE);
                    resetButton.setOnClickListener(v -> {
                        int def = Math.max(0, defaultValue);
                        if (getArguments() != null
                                && getArguments().getBoolean(ARG_TWO_SEPARATE_FORMAT, false)) {
                            npMb.setValue(def);
                            npGb.setValue(Math.max(0, a.getInt(ARG_DEFAULT_VALUE_2, 0)));
                        } else {
                            npMb.setValue(def % 1024);
                            npGb.setValue(def / 1024);
                        }
                        updateMbGbSummary();
                        confirmHaptic();
                    });
                } else {
                    resetButton.setVisibility(View.GONE);
                }
            }
            if (helperView != null) helperView.setVisibility(View.GONE);
        } else {
            if (timeWheels != null) timeWheels.setVisibility(View.GONE);
            if (intWheels != null) intWheels.setVisibility(View.VISIBLE);

            setupWheel(npSingle, min, max, value, dividerColor, selectedColor);
            npSingle.setOnValueChangedListener((p, o, n) -> {
                tickHaptic();
                updateIntSummary();
                validate();
            });
            // Same center-cell design as TIME mode: highlighted middle row,
            // tap to type, drags scroll.
            styleCenterCellPicker(npSingle, min, max, title);

            // Summary card, INT flavour: short heading, hero value, tap hint.
            // The caller's description (if any) goes to the FOOTER, never the
            // caption — same as TIME mode's "Selected duration" heading.
            View summaryCard = view.findViewById(R.id.picker_summary_card);
            if (summaryCard != null) summaryCard.setVisibility(View.VISIBLE);
            TextView caption = view.findViewById(R.id.picker_summary_caption);
            if (caption != null) {
                caption.setText(getString(R.string.number_input_selected_caption));
                caption.setVisibility(View.VISIBLE);
            }
            TextView timeHelper = view.findViewById(R.id.picker_time_helper);
            if (timeHelper != null) {
                timeHelper.setText(getString(R.string.duration_picker_tap_hint));
                timeHelper.setVisibility(View.VISIBLE);
            }
            if (summaryView != null) summaryView.setVisibility(View.VISIBLE);
            updateIntSummary();

            if (resetButton != null) {
                if (showReset) {
                    resetButton.setVisibility(View.VISIBLE);
                    resetButton.setOnClickListener(v -> {
                        npSingle.setValue(defaultValue);
                        updateIntSummary();
                        validate();
                        confirmHaptic();
                    });
                } else {
                    resetButton.setVisibility(View.GONE);
                }
            }
            // Optional live preview: vibrate the currently selected value so the
            // user can feel the duration before committing (used by haptics settings).
            View previewBtn = view.findViewById(R.id.btn_picker_preview);
            if (previewBtn != null) {
                if (showPreview) {
                    previewBtn.setVisibility(View.VISIBLE);
                    previewBtn.setOnClickListener(v -> {
                        try {
                            android.os.Vibrator vibrator = (android.os.Vibrator)
                                    requireContext().getSystemService(
                                            android.content.Context.VIBRATOR_SERVICE);
                            if (vibrator == null || !vibrator.hasVibrator()) {
                                return;
                            }
                            long ms = Math.max(0L, npSingle.getValue());
                            if (ms > 0L) {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(
                                            ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                                } else {
                                    vibrator.vibrate(ms);
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    });
                } else {
                    previewBtn.setVisibility(View.GONE);
                }
            }
            // The old colored helper messages are gone — INT validation is
            // enforced by the wheel and the input dialog's range.
            if (helperView != null) helperView.setVisibility(View.GONE);
            validate();
        }

        // Footer ("photo text"): the caller's description wins (it explains
        // what the option does), then an explicit ARG_FOOTER, else hidden.
        String footer = a.getString(ARG_FOOTER, null);
        if (descriptionText != null && !descriptionText.isEmpty()) {
            footer = descriptionText;
        }
        if (footerView != null) {
            if (footer == null || footer.isEmpty()) {
                footerView.setVisibility(View.GONE);
            } else {
                footerView.setText(footer);
                footerView.setVisibility(View.VISIBLE);
            }
        }

        if (cancelButton != null) cancelButton.setOnClickListener(v -> dismiss());
        if (okButton != null) okButton.setOnClickListener(v -> {
            confirmHaptic();
            deliverResult();
        });
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
            if (getView() != null && hapticsEnabled()) {
                getView().performHapticFeedback(
                        android.view.HapticFeedbackConstants.CLOCK_TICK);
            }
        } catch (Exception ignored) {
        }
    }

    private void confirmHaptic() {
        try {
            if (getView() != null && hapticsEnabled()) {
                getView().performHapticFeedback(
                        android.view.HapticFeedbackConstants.CONFIRM);
            }
        } catch (Exception ignored) {
        }
    }

    /** Whether the app's Haptic feedback + picker haptics toggles are on (default ON). */
    private boolean hapticsEnabled() {
        try {
            com.fadcam.SharedPreferencesManager prefs =
                    com.fadcam.SharedPreferencesManager.getInstance(requireContext());
            return prefs.isHapticFeedbackEnabled() && prefs.isHapticPickerEnabled();
        } catch (Exception e) {
            return true;
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
        } else if (mode == MODE_MBGB) {
            result.putInt(RESULT_NUMBER, npGb.getValue() * 1024 + npMb.getValue());
            result.putInt(RESULT_VALUE_A, npMb.getValue());
            result.putInt(RESULT_VALUE_B, npGb.getValue());
        } else {
            result.putInt(RESULT_NUMBER, npSingle.getValue());
        }
        getParentFragmentManager().setFragmentResult(resultKey, result);
        dismiss();
    }

    private void updateTimeSummary() {
        // TIME-mode only (npHours/npMinutes/npSeconds are null in INT mode).
        if (mode != MODE_TIME_HMS) {
            return;
        }
        refreshResetIconVisibility();
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

    /**
     * Styles a wheel's center cell: a translucent accent band drawn as the
     * wheel's background at the middle row, so the wheel's own digits render
     * on top and scroll THROUGH the band in real time (fully integrated —
     * no overlay text). A clean CLICK on the center cell flashes a ripple
     * inside the rounded cell and opens the type-in dialog; drags scroll
     * natively and never show press feedback.
     */
    private void styleCenterCellPicker(final NumberPicker picker,
            final int minValue, final int maxValue, final String titleText) {
        final float density = getResources().getDisplayMetrics().density;
        final android.util.TypedValue tv = new android.util.TypedValue();
        int accent = 0xFF000000;
        if (getContext() != null && getContext().getTheme()
                .resolveAttribute(R.attr.pickerButtonBackground, tv, true)) {
            accent = tv.data;
        }
        final int bandColor = (accent & 0x00FFFFFF) | 0x73000000; // ~45% accent
        final com.fadcam.ui.picker.CenterCellBandDrawable band =
                new com.fadcam.ui.picker.CenterCellBandDrawable(bandColor,
                        8f * density, 12f * density);
        band.setRowCount(Math.max(3, picker.getChildCount()));
        picker.setBackground(band);

        // Tap the center cell → flash + type dialog; drags fall through.
        final int slop = android.view.ViewConfiguration.get(requireContext())
                .getScaledTouchSlop();
        final float[] down = new float[2];
        final boolean[] dragged = {false};
        picker.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    down[0] = ev.getX();
                    down[1] = ev.getY();
                    dragged[0] = false;
                    return false;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (Math.abs(ev.getX() - down[0]) > slop
                            || Math.abs(ev.getY() - down[1]) > slop) {
                        dragged[0] = true;
                    }
                    return false;
                case android.view.MotionEvent.ACTION_UP: {
                    int h = picker.getHeight();
                    int rows = Math.max(3, picker.getChildCount());
                    if (!dragged[0] && h > 0) {
                        float top = (rows - 1) / 2f * h / rows;
                        float bottom = ((rows - 1) / 2f + 1f) * h / rows;
                        if (ev.getY() >= top && ev.getY() <= bottom) {
                            // Single clean press flash (ripple-like) inside the
                            // cell, then open the dialog.
                            picker.setPressed(false);
                            band.press(true);
                            v.postDelayed(() -> {
                                band.press(false);
                                showColumnInputDialog(picker, minValue, maxValue, titleText);
                            }, 90L);
                            return true;
                        }
                    }
                    return false;
                }
                case android.view.MotionEvent.ACTION_CANCEL:
                    return false;
                default:
                    return false;
            }
        });
    }

    /** Type-in dialog for a single column, using the official Material dialog
     * (themed with the app) and the same range forcing as the wheels:
     * hours 0..24, minutes/seconds 0..59 — out-of-range input shows an error
     * and is clamped on OK.
     */
    private void showColumnInputDialog(final NumberPicker wheel,
            final int minValue, final int maxValue, String titleText) {
        final View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_column_input, null);
        final com.google.android.material.textfield.TextInputLayout inputLayout =
                content.findViewById(R.id.column_input_layout);
        final com.google.android.material.textfield.TextInputEditText inputField =
                content.findViewById(R.id.column_input_field);
        final String rangeText = minValue + " – " + maxValue;

        inputField.setText(String.valueOf(wheel.getValue()));
        inputField.setSelection(inputField.getText().length());
        inputLayout.setHelperText(rangeText);
        // Live range check: error state while the typed value is out of range.
        inputField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                int v = parseColumnInput(s);
                inputLayout.setError((v < minValue || v > maxValue) ? rangeText : null);
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(titleText)
                .setView(content)
                .setNegativeButton(R.string.duration_picker_cancel, null)
                .setPositiveButton(R.string.duration_picker_ok, (d, w) -> {
                    int v = parseColumnInput(inputField.getText());
                    if (v < 0) {
                        v = minValue;
                    }
                    v = Math.max(minValue, Math.min(maxValue, v));
                    wheel.setValue(v);
                    // 24h is the max — nothing can follow it.
                    if (wheel == npHours && v >= 24) {
                        npMinutes.setValue(0);
                        npSeconds.setValue(0);
                    }
                    confirmHaptic();
                    updateTimeSummary();
                    validate();
                })
                .show();
    }

    /** Parses dialog input; -1 for empty or non-numeric. */
    private static int parseColumnInput(CharSequence s) {
        if (s == null) {
            return -1;
        }
        String t = s.toString().trim();
        if (t.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(t);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Echoes the single wheel's value into the summary card (INT mode). */
    private void updateIntSummary() {
        if (mode != MODE_INT || summaryView == null) {
            return;
        }
        summaryView.setText(String.valueOf(npSingle.getValue()));
    }

    /** Combines MB + GB wheels into the summary hero (MB/GB mode). */
    private void updateMbGbSummary() {
        if (mode != MODE_MBGB || summaryView == null) {
            return;
        }
        boolean separate = getArguments() != null
                && getArguments().getBoolean(ARG_TWO_SEPARATE_FORMAT, false);
        String label1 = getArguments() != null
                ? getArguments().getString(ARG_TWO_LABEL1, getString(R.string.video_split_unit_mb))
                : getString(R.string.video_split_unit_mb);
        String label2 = getArguments() != null
                ? getArguments().getString(ARG_TWO_LABEL2, getString(R.string.video_split_unit_gb))
                : getString(R.string.video_split_unit_gb);
        if (separate) {
            summaryView.setText(npMb.getValue() + " " + label1 + " · "
                    + npGb.getValue() + " " + label2);
            return;
        }
        int gb = npGb.getValue();
        int mb = npMb.getValue();
        if (gb > 0) {
            summaryView.setText(gb + " GB" + (mb > 0 ? " " + mb + " MB" : ""));
        } else {
            summaryView.setText(mb + " MB");
        }
    }

    /**
     * INT-mode validation: the wheel and the input dialog already enforce
     * [min, max], so OK is always enabled. Legacy colored helper messages
     * ("looks good, press OK…") were removed for design consistency.
     */
    private void validate() {
        if (okButton != null) {
            okButton.setEnabled(true);
        }
    }

    /** Moves the FIRST value's column (wheel + its label column) to the leftmost
     *  position, without touching any value logic (all reads/writes go through
     *  the picker ids). Labels live inside FrameLayout wrappers, so the whole
     *  wrapper column is moved — not just the TextView. */
    private void reorderColumnsToLeft(View view) {
        try {
            android.view.ViewGroup pickerRow = (android.view.ViewGroup) npMb.getParent();
            if (pickerRow != null && pickerRow.indexOfChild(npMb) != 0) {
                pickerRow.removeView(npMb);
                pickerRow.addView(npMb, 0);
            }
            TextView labelA = view.findViewById(R.id.np_label_value1);
            if (labelA != null) {
                android.view.View labelColumn = labelA.getParent() instanceof android.view.ViewGroup
                        ? (android.view.View) labelA.getParent()
                        : labelA;
                android.view.ViewGroup labelRow =
                        (android.view.ViewGroup) labelColumn.getParent();
                if (labelRow != null && labelRow.indexOfChild(labelColumn) != 0) {
                    labelRow.removeView(labelColumn);
                    labelRow.addView(labelColumn, 0);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Opts a wheel out of the sheet's native drag interception: once the
     * wheel receives the down event, the BottomSheetBehavior cannot steal
     * the gesture, so scrolling a wheel never moves/dismisses the sheet.
     * Returns false so the picker keeps its own click/scroll handling.
     */
    private void protectWheelFromSheetDrag(NumberPicker picker) {
        if (picker == null) return;
        picker.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            }
            return false;
        });
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
