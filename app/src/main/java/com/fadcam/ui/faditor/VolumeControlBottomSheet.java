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
 * Material bottom sheet for controlling audio volume with a slider.
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Slider 0 – 200% (0.0 – 2.0)</li>
 *   <li>Visual red zone for values above 100% with quality warning</li>
 *   <li>Mute toggle button</li>
 *   <li>Live preview: calls back on every slider change</li>
 * </ul>
 */
public class VolumeControlBottomSheet extends BottomSheetDialogFragment {

    /** Callback for volume / mute changes. */
    public interface Callback {
        void onVolumeChanged(float volume, boolean muted);
    }

    @Nullable
    private Callback callback;
    private float currentVolume = 1.0f;
    private boolean currentMuted = false;

    /**
     * Create a new instance with the current volume and mute state.
     *
     * @param volume current volume (0.0 – 2.0)
     * @param muted  current mute state
     * @return new instance
     */
    public static VolumeControlBottomSheet newInstance(float volume, boolean muted) {
        VolumeControlBottomSheet sheet = new VolumeControlBottomSheet();
        Bundle args = new Bundle();
        args.putFloat("volume", volume);
        args.putBoolean("muted", muted);
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
            currentVolume = getArguments().getFloat("volume", 1.0f);
            currentMuted = getArguments().getBoolean("muted", false);
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
        titleRow.setPadding(0, 0, 0, (int) (8 * dp));
        root.addView(titleRow);

        TextView title = new TextView(requireContext());
        title.setText(R.string.faditor_volume_title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleLp);
        titleRow.addView(title);

        // Volume percentage display
        TextView percentText = new TextView(requireContext());
        percentText.setTextColor(0xFFCCCCCC);
        percentText.setTextSize(16);
        percentText.setTypeface(null, Typeface.BOLD);
        updatePercentText(percentText, currentVolume, currentMuted);
        titleRow.addView(percentText);

        // ── Volume icon + slider row ─────────────────────────────────
        LinearLayout sliderRow = new LinearLayout(requireContext());
        sliderRow.setOrientation(LinearLayout.HORIZONTAL);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        sliderRow.setPadding(0, (int) (8 * dp), 0, (int) (4 * dp));
        root.addView(sliderRow);

        // Volume icon (acts as mute toggle)
        TextView volumeIcon = new TextView(requireContext());
        volumeIcon.setTypeface(materialIcons);
        volumeIcon.setTextSize(24);
        volumeIcon.setGravity(Gravity.CENTER);
        updateVolumeIcon(volumeIcon, currentVolume, currentMuted);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                (int) (36 * dp), (int) (36 * dp));
        iconLp.setMarginEnd((int) (8 * dp));
        volumeIcon.setLayoutParams(iconLp);
        sliderRow.addView(volumeIcon);

        // Material Slider
        Slider slider = new Slider(requireContext());
        slider.setValueFrom(0f);
        slider.setValueTo(200f); // 0% – 200%
        slider.setStepSize(1f);
        slider.setValue(currentMuted ? 0f : currentVolume * 100f);

        // Colors: green for normal, red for >100%
        updateSliderColors(slider, currentVolume, currentMuted);

        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        slider.setLayoutParams(sliderLp);
        sliderRow.addView(slider);

        // ── Warning text for >100% ───────────────────────────────────
        TextView warningText = new TextView(requireContext());
        warningText.setText(R.string.faditor_volume_warning);
        warningText.setTextColor(0xFFF44336);
        warningText.setTextSize(11);
        warningText.setPadding((int) (44 * dp), (int) (2 * dp), 0, 0);
        warningText.setVisibility(currentVolume > 1.0f && !currentMuted ? View.VISIBLE : View.GONE);
        root.addView(warningText);

        // ── Mute toggle button ───────────────────────────────────────
        LinearLayout muteRow = new LinearLayout(requireContext());
        muteRow.setOrientation(LinearLayout.HORIZONTAL);
        muteRow.setGravity(Gravity.CENTER_VERTICAL);
        muteRow.setBackgroundResource(R.drawable.settings_home_row_bg);
        muteRow.setPadding((int) (16 * dp), (int) (14 * dp),
                (int) (16 * dp), (int) (14 * dp));
        LinearLayout.LayoutParams muteRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        muteRowLp.topMargin = (int) (12 * dp);
        muteRow.setLayoutParams(muteRowLp);
        root.addView(muteRow);

        TextView muteIcon = new TextView(requireContext());
        muteIcon.setTypeface(materialIcons);
        muteIcon.setText("volume_off");
        muteIcon.setTextSize(20);
        muteIcon.setTextColor(currentMuted ? 0xFFF44336 : 0xFF888888);
        LinearLayout.LayoutParams muteIconLp = new LinearLayout.LayoutParams(
                (int) (28 * dp), (int) (28 * dp));
        muteIconLp.setMarginEnd((int) (16 * dp));
        muteIcon.setLayoutParams(muteIconLp);
        muteIcon.setGravity(Gravity.CENTER);
        muteRow.addView(muteIcon);

        TextView muteLabel = new TextView(requireContext());
        muteLabel.setText(currentMuted
                ? R.string.faditor_volume_unmute
                : R.string.faditor_volume_mute);
        muteLabel.setTextSize(15);
        muteLabel.setTextColor(currentMuted ? 0xFFF44336 : 0xFFCCCCCC);
        muteLabel.setTypeface(null, currentMuted ? Typeface.BOLD : Typeface.NORMAL);
        LinearLayout.LayoutParams muteLabelLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        muteLabel.setLayoutParams(muteLabelLp);
        muteRow.addView(muteLabel);

        if (currentMuted) {
            TextView check = new TextView(requireContext());
            check.setTypeface(materialIcons);
            check.setText("check");
            check.setTextSize(20);
            check.setTextColor(0xFFF44336);
            check.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams checkLp = new LinearLayout.LayoutParams(
                    (int) (24 * dp), (int) (24 * dp));
            check.setLayoutParams(checkLp);
            muteRow.addView(check);
        }

        // ── Listeners ────────────────────────────────────────────────
        // Holder for resetRow so lambdas can reference it before creation
        final LinearLayout[] resetRowRef = new LinearLayout[1];

        slider.addOnChangeListener((sl, value, fromUser) -> {
            if (!fromUser) return;
            float vol = value / 100f;
            currentVolume = vol;
            if (currentMuted && vol > 0) {
                currentMuted = false;
            }
            updatePercentText(percentText, vol, currentMuted);
            updateVolumeIcon(volumeIcon, vol, currentMuted);
            updateSliderColors(slider, vol, currentMuted);
            warningText.setVisibility(vol > 1.0f ? View.VISIBLE : View.GONE);
            muteIcon.setTextColor(currentMuted ? 0xFFF44336 : 0xFF888888);
            muteLabel.setText(currentMuted
                    ? R.string.faditor_volume_unmute
                    : R.string.faditor_volume_mute);
            muteLabel.setTextColor(currentMuted ? 0xFFF44336 : 0xFFCCCCCC);
            muteLabel.setTypeface(null, currentMuted ? Typeface.BOLD : Typeface.NORMAL);
            if (resetRowRef[0] != null) {
                boolean show = currentMuted || Math.abs(vol - 1.0f) > 0.01f;
                resetRowRef[0].setVisibility(show ? View.VISIBLE : View.GONE);
            }

            if (callback != null) callback.onVolumeChanged(vol, false);
        });

        muteRow.setOnClickListener(v -> {
            currentMuted = !currentMuted;
            updatePercentText(percentText, currentVolume, currentMuted);
            updateVolumeIcon(volumeIcon, currentVolume, currentMuted);
            if (currentMuted) {
                slider.setValue(0f);
            } else {
                slider.setValue(currentVolume * 100f);
            }
            updateSliderColors(slider, currentVolume, currentMuted);
            warningText.setVisibility(
                    currentVolume > 1.0f && !currentMuted ? View.VISIBLE : View.GONE);
            muteIcon.setTextColor(currentMuted ? 0xFFF44336 : 0xFF888888);
            muteLabel.setText(currentMuted
                    ? R.string.faditor_volume_unmute
                    : R.string.faditor_volume_mute);
            muteLabel.setTextColor(currentMuted ? 0xFFF44336 : 0xFFCCCCCC);
            muteLabel.setTypeface(null, currentMuted ? Typeface.BOLD : Typeface.NORMAL);
            if (resetRowRef[0] != null) {
                boolean show = currentMuted || Math.abs(currentVolume - 1.0f) > 0.01f;
                resetRowRef[0].setVisibility(show ? View.VISIBLE : View.GONE);
            }

            if (callback != null) callback.onVolumeChanged(currentVolume, currentMuted);
        });

        // Tap volume icon to toggle mute
        volumeIcon.setOnClickListener(v -> muteRow.performClick());

        // ── Reset row (matches mute row style) ──────────────────────
        boolean needsReset = currentMuted || Math.abs(currentVolume - 1.0f) > 0.01f;
        LinearLayout resetRow = new LinearLayout(requireContext());
        resetRowRef[0] = resetRow;
        resetRow.setOrientation(LinearLayout.HORIZONTAL);
        resetRow.setGravity(Gravity.CENTER_VERTICAL);
        resetRow.setBackgroundResource(R.drawable.settings_home_row_bg);
        resetRow.setPadding((int) (16 * dp), (int) (14 * dp),
                (int) (16 * dp), (int) (14 * dp));
        LinearLayout.LayoutParams resetRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        resetRowLp.topMargin = (int) (8 * dp);
        resetRow.setLayoutParams(resetRowLp);

        TextView resetIcon = new TextView(requireContext());
        resetIcon.setTypeface(materialIcons);
        resetIcon.setText("refresh");
        resetIcon.setTextSize(20);
        resetIcon.setTextColor(0xFFF44336);
        LinearLayout.LayoutParams resetIconLp = new LinearLayout.LayoutParams(
                (int) (28 * dp), (int) (28 * dp));
        resetIconLp.setMarginEnd((int) (16 * dp));
        resetIcon.setLayoutParams(resetIconLp);
        resetIcon.setGravity(Gravity.CENTER);
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
            currentVolume = 1.0f;
            currentMuted = false;
            slider.setValue(100f);
            updatePercentText(percentText, 1.0f, false);
            updateVolumeIcon(volumeIcon, 1.0f, false);
            updateSliderColors(slider, 1.0f, false);
            warningText.setVisibility(View.GONE);
            muteIcon.setTextColor(0xFF888888);
            muteLabel.setText(R.string.faditor_volume_mute);
            muteLabel.setTextColor(0xFFCCCCCC);
            muteLabel.setTypeface(null, Typeface.NORMAL);
            resetRow.setVisibility(View.GONE);
            if (callback != null) callback.onVolumeChanged(1.0f, false);
        });

        resetRow.setVisibility(needsReset ? View.VISIBLE : View.GONE);
        root.addView(resetRow);

        return root;
    }

    private void updatePercentText(TextView tv, float volume, boolean muted) {
        if (muted) {
            tv.setText(R.string.faditor_tool_muted);
            tv.setTextColor(0xFFF44336);
        } else {
            int percent = Math.round(volume * 100);
            tv.setText(percent + "%");
            tv.setTextColor(volume > 1.0f ? 0xFFF44336 : 0xFFCCCCCC);
        }
    }

    private void updateVolumeIcon(TextView icon, float volume, boolean muted) {
        if (muted || volume == 0f) {
            icon.setText("volume_off");
            icon.setTextColor(0xFFF44336);
        } else if (volume < 0.5f) {
            icon.setText("volume_mute");
            icon.setTextColor(0xFF888888);
        } else if (volume <= 1.0f) {
            icon.setText("volume_up");
            icon.setTextColor(0xFF4CAF50);
        } else {
            icon.setText("volume_up");
            icon.setTextColor(0xFFF44336);
        }
    }

    private void updateSliderColors(Slider slider, float volume, boolean muted) {
        int trackColor;
        int thumbColor;
        if (muted) {
            trackColor = 0xFFF44336;
            thumbColor = 0xFFF44336;
        } else if (volume > 1.0f) {
            trackColor = 0xFFF44336;
            thumbColor = 0xFFF44336;
        } else {
            trackColor = 0xFF4CAF50;
            thumbColor = 0xFF4CAF50;
        }

        slider.setTrackActiveTintList(
                android.content.res.ColorStateList.valueOf(trackColor));
        slider.setThumbTintList(
                android.content.res.ColorStateList.valueOf(thumbColor));
        slider.setTrackInactiveTintList(
                android.content.res.ColorStateList.valueOf(0xFF333333));
    }
}
