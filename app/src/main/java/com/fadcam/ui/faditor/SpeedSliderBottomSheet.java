package com.fadcam.ui.faditor;

import android.app.Dialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.slider.Slider;

/**
 * Material bottom sheet for controlling playback speed with a slider.
 *
 * <p>Range: 0.25x – 4.0x (practical range), with logarithmic feel.
 * Shows a prominent speed display and preset buttons for common speeds.</p>
 */
public class SpeedSliderBottomSheet extends BottomSheetDialogFragment {

    /** Callback for speed changes. */
    public interface Callback {
        void onSpeedChanged(float speed);
    }

    @Nullable
    private Callback callback;
    private float currentSpeed = 1.0f;

    /** Common preset speeds. */
    private static final float[] PRESETS = {0.25f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f, 6.0f, 8.0f, 10.0f};

    /**
     * Create a new instance with the current speed.
     *
     * @param speed current playback speed
     * @return new instance
     */
    public static SpeedSliderBottomSheet newInstance(float speed) {
        SpeedSliderBottomSheet sheet = new SpeedSliderBottomSheet();
        Bundle args = new Bundle();
        args.putFloat("speed", speed);
        sheet.setArguments(args);
        return sheet;
    }

    public void setCallback(@Nullable Callback callback) {
        this.callback = callback;
    }

    @Override
    public int getTheme() {
        return R.style.CustomBottomSheetDialogTheme;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((BottomSheetDialog) dialog)
                    .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackgroundResource(R.drawable.picker_bottom_sheet_dark_gradient_bg);
            }
        });
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        if (getArguments() != null) {
            currentSpeed = getArguments().getFloat("speed", 1.0f);
        }

        float dp = getResources().getDisplayMetrics().density;
        Typeface materialIcons = ResourcesCompat.getFont(requireContext(), R.font.materialicons);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (20 * dp), (int) (16 * dp),
                (int) (20 * dp), (int) (28 * dp));

        // ── Title row ────────────────────────────────────────────────
        LinearLayout titleRow = new LinearLayout(requireContext());
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, 0, 0, (int) (4 * dp));
        root.addView(titleRow);

        TextView title = new TextView(requireContext());
        title.setText(R.string.faditor_speed_title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleLp);
        titleRow.addView(title);

        // Speed display
        TextView speedDisplay = new TextView(requireContext());
        speedDisplay.setTextSize(18);
        speedDisplay.setTypeface(null, Typeface.BOLD);
        updateSpeedDisplay(speedDisplay, currentSpeed);
        titleRow.addView(speedDisplay);

        // ── Slider row ───────────────────────────────────────────────
        LinearLayout sliderRow = new LinearLayout(requireContext());
        sliderRow.setOrientation(LinearLayout.HORIZONTAL);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        sliderRow.setPadding(0, (int) (12 * dp), 0, (int) (4 * dp));
        root.addView(sliderRow);

        // Speed icon
        TextView speedIcon = new TextView(requireContext());
        speedIcon.setTypeface(materialIcons);
        speedIcon.setText("speed");
        speedIcon.setTextSize(24);
        speedIcon.setTextColor(0xFF4CAF50);
        speedIcon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                (int) (36 * dp), (int) (36 * dp));
        iconLp.setMarginEnd((int) (8 * dp));
        speedIcon.setLayoutParams(iconLp);
        sliderRow.addView(speedIcon);

        // Material Slider (use step-free for smooth control, 0.25 to 4.0)
        Slider slider = new Slider(requireContext());
        slider.setValueFrom(25f);   // 0.25x * 100
        slider.setValueTo(1000f);  // 10.0x * 100
        slider.setStepSize(5f);    // 0.05x increments
        slider.setValue(Math.max(25f, Math.min(currentSpeed * 100f, 1000f)));

        int trackColor = Math.abs(currentSpeed - 1f) < 0.01f ? 0xFF888888 : 0xFF4CAF50;
        slider.setTrackActiveTintList(
                android.content.res.ColorStateList.valueOf(trackColor));
        slider.setThumbTintList(
                android.content.res.ColorStateList.valueOf(trackColor));
        slider.setTrackInactiveTintList(
                android.content.res.ColorStateList.valueOf(0xFF333333));

        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        slider.setLayoutParams(sliderLp);
        sliderRow.addView(slider);

        // ── Preset chips ─────────────────────────────────────────────
        android.widget.HorizontalScrollView chipScroll =
                new android.widget.HorizontalScrollView(requireContext());
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setPadding(0, (int) (8 * dp), 0, 0);
        root.addView(chipScroll);

        LinearLayout chipRow = new LinearLayout(requireContext());
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);
        chipScroll.addView(chipRow);

        // List of preset TextViews so we can update their selection state
        java.util.List<TextView> chipViews = new java.util.ArrayList<>();
        final LinearLayout[] resetHolder = new LinearLayout[1];

        for (float presetSpeed : PRESETS) {
            TextView chip = new TextView(requireContext());
            String label = formatSpeed(presetSpeed);
            chip.setText(label);
            chip.setTextSize(13);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding((int) (14 * dp), (int) (8 * dp),
                    (int) (14 * dp), (int) (8 * dp));
            chip.setBackgroundResource(R.drawable.settings_home_row_bg);

            boolean selected = Math.abs(presetSpeed - currentSpeed) < 0.01f;
            chip.setTextColor(selected ? 0xFF4CAF50 : 0xFFAAAAAA);
            chip.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);

            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            chipLp.setMarginEnd((int) (6 * dp));
            chip.setLayoutParams(chipLp);
            chipViews.add(chip);

            chip.setOnClickListener(v -> {
                currentSpeed = presetSpeed;
                slider.setValue(presetSpeed * 100f);
                updateSpeedDisplay(speedDisplay, presetSpeed);
                updateSliderColor(slider, presetSpeed);
                updateChipSelection(chipViews, presetSpeed);
                if (resetHolder[0] != null) {
                    resetHolder[0].setVisibility(Math.abs(presetSpeed - 1f) < 0.01f ? View.GONE : View.VISIBLE);
                }
                if (callback != null) callback.onSpeedChanged(presetSpeed);
            });

            chipRow.addView(chip);
        }

        // ── Reset button (consistent row style) ─────────────────────
        LinearLayout resetRow = new LinearLayout(requireContext());
        resetHolder[0] = resetRow;
        resetRow.setOrientation(LinearLayout.HORIZONTAL);
        resetRow.setGravity(Gravity.CENTER_VERTICAL);
        resetRow.setBackgroundResource(R.drawable.settings_home_row_bg);
        resetRow.setPadding((int) (16 * dp), (int) (14 * dp),
                (int) (16 * dp), (int) (14 * dp));
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        resetLp.topMargin = (int) (12 * dp);
        resetRow.setLayoutParams(resetLp);

        TextView resetIcon = new TextView(requireContext());
        resetIcon.setTypeface(materialIcons);
        resetIcon.setText("refresh");
        resetIcon.setTextSize(20);
        resetIcon.setTextColor(0xFFF44336);
        resetIcon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams resetIconLp = new LinearLayout.LayoutParams(
                (int) (28 * dp), (int) (28 * dp));
        resetIconLp.setMarginEnd((int) (16 * dp));
        resetIcon.setLayoutParams(resetIconLp);
        resetRow.addView(resetIcon);

        TextView resetLabel = new TextView(requireContext());
        resetLabel.setText("Reset");
        resetLabel.setTextSize(15);
        resetLabel.setTextColor(0xFFF44336);
        resetLabel.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams resetLabelLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        resetLabel.setLayoutParams(resetLabelLp);
        resetRow.addView(resetLabel);

        resetRow.setOnClickListener(v -> {
            currentSpeed = 1.0f;
            slider.setValue(100f);
            updateSpeedDisplay(speedDisplay, 1.0f);
            updateSliderColor(slider, 1.0f);
            updateChipSelection(chipViews, 1.0f);
            resetRow.setVisibility(View.GONE);
            if (callback != null) callback.onSpeedChanged(1.0f);
        });

        // Only show reset if speed != 1.0
        resetRow.setVisibility(Math.abs(currentSpeed - 1f) < 0.01f ? View.GONE : View.VISIBLE);
        root.addView(resetRow);

        // ── Slider listener ──────────────────────────────────────────
        slider.addOnChangeListener((sl, value, fromUser) -> {
            if (!fromUser) return;
            float speed = value / 100f;
            currentSpeed = speed;
            updateSpeedDisplay(speedDisplay, speed);
            updateSliderColor(slider, speed);
            updateChipSelection(chipViews, speed);
            resetRow.setVisibility(Math.abs(speed - 1f) < 0.01f ? View.GONE : View.VISIBLE);
            if (callback != null) callback.onSpeedChanged(speed);
        });

        return root;
    }

    private void updateSpeedDisplay(TextView tv, float speed) {
        tv.setText(formatSpeed(speed));
        tv.setTextColor(Math.abs(speed - 1f) < 0.01f ? 0xFF888888 : 0xFF4CAF50);
    }

    private void updateSliderColor(Slider slider, float speed) {
        int color = Math.abs(speed - 1f) < 0.01f ? 0xFF888888 : 0xFF4CAF50;
        slider.setTrackActiveTintList(
                android.content.res.ColorStateList.valueOf(color));
        slider.setThumbTintList(
                android.content.res.ColorStateList.valueOf(color));
    }

    private void updateChipSelection(java.util.List<TextView> chips, float speed) {
        for (int i = 0; i < chips.size(); i++) {
            boolean selected = Math.abs(PRESETS[i] - speed) < 0.01f;
            chips.get(i).setTextColor(selected ? 0xFF4CAF50 : 0xFFAAAAAA);
            chips.get(i).setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private static String formatSpeed(float speed) {
        if (speed == (int) speed) {
            return (int) speed + "x";
        }
        return String.format(java.util.Locale.US, "%.2gx", speed);
    }
}
