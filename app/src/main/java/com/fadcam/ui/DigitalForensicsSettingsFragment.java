package com.fadcam.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.ui.picker.OptionItem;
import com.fadcam.ui.picker.PickerBottomSheetFragment;

import java.util.ArrayList;

public class DigitalForensicsSettingsFragment extends Fragment {

    private SharedPreferencesManager prefs;

    private TextView valueStatus;
    private TextView valueEvidence;
    private TextView valueCaptureScope;
    private TextView valueOverlay;
    private View motionLabBanner;
    private View rowCaptureScope;
    private View previewBox;
    private TextView previewLabel;
    private final Handler previewHandler = new Handler(Looper.getMainLooper());
    private int previewStep = 0;
    private final Runnable previewLoop = new Runnable() {
        @Override
        public void run() {
            animatePreviewStep();
            previewHandler.postDelayed(this, 1200L);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_digital_forensics, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        prefs = SharedPreferencesManager.getInstance(requireContext());

        View back = view.findViewById(R.id.back_button);
        if (back != null) {
            back.setOnClickListener(v -> OverlayNavUtil.popLevel(requireActivity()));
        }

        valueStatus = view.findViewById(R.id.value_df_status);
        valueEvidence = view.findViewById(R.id.value_df_evidence);
        valueCaptureScope = view.findViewById(R.id.value_df_capture_scope);
        valueOverlay = view.findViewById(R.id.value_df_overlay);
        motionLabBanner = view.findViewById(R.id.motion_lab_dependency_banner);
        rowCaptureScope = view.findViewById(R.id.row_df_capture_scope);
        previewBox = view.findViewById(R.id.forensics_preview_bbox);
        previewLabel = view.findViewById(R.id.forensics_preview_label);

        bindCurrentValues();
        wireListeners(view);
        startPreviewLoop();
    }

    @Override
    public void onResume() {
        super.onResume();
        bindCurrentValues();
        startPreviewLoop();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopPreviewLoop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopPreviewLoop();
        // Cancel running animations to prevent withEndAction from firing after view is destroyed
        if (previewBox != null) previewBox.animate().cancel();
        if (previewLabel != null) previewLabel.animate().cancel();
        // Null out view references to prevent handler callbacks from accessing stale views
        previewBox = null;
        previewLabel = null;
        valueStatus = null;
        valueEvidence = null;
        valueCaptureScope = null;
        valueOverlay = null;
        motionLabBanner = null;
        rowCaptureScope = null;
    }

    private void bindCurrentValues() {
        valueStatus.setText(enabledLabel(prefs.isDigitalForensicsEnabled()));
        boolean evidenceEnabled = prefs.isDfEvidenceCollectionEnabled();
        valueEvidence.setText(enabledLabel(evidenceEnabled));
        valueCaptureScope.setText(scopeLabel(prefs.getDfCaptureScope()));
        valueOverlay.setText(enabledLabel(prefs.isDfOverlayEnabled()));
        if (rowCaptureScope != null) {
            rowCaptureScope.setEnabled(evidenceEnabled);
            rowCaptureScope.setAlpha(evidenceEnabled ? 1f : 0.45f);
        }
        boolean motionLabEnabled = prefs.isMotionModeEnabled();
        if (motionLabBanner != null) {
            motionLabBanner.setVisibility(motionLabEnabled ? View.GONE : View.VISIBLE);
        }
    }

    private String enabledLabel(boolean enabled) {
        return enabled
                ? getString(R.string.digital_forensics_status_enabled_short)
                : getString(R.string.digital_forensics_status_disabled);
    }

    private String scopeLabel(@NonNull String scope) {
        switch (scope) {
            case "people":
                return getString(R.string.digital_forensics_scope_people);
            case "objects":
                return getString(R.string.digital_forensics_scope_objects);
            default:
                return getString(R.string.digital_forensics_scope_both);
        }
    }

    private void wireListeners(@NonNull View view) {
        view.findViewById(R.id.row_df_enabled).setOnClickListener(v -> showTogglePicker(
                "rk_df_enabled",
                getString(R.string.digital_forensics_enable),
                getString(R.string.digital_forensics_enable_helper),
                prefs.isDigitalForensicsEnabled(),
                value -> {
                    prefs.setDigitalForensicsEnabled(value);
                    bindCurrentValues();
                }
        ));

        view.findViewById(R.id.row_df_evidence_enabled).setOnClickListener(v -> showTogglePicker(
                "rk_df_evidence_enabled",
                getString(R.string.digital_forensics_evidence_enable),
                getString(R.string.digital_forensics_evidence_helper),
                prefs.isDfEvidenceCollectionEnabled(),
                value -> {
                    prefs.setDfEvidenceCollectionEnabled(value);
                    bindCurrentValues();
                }
        ));

        view.findViewById(R.id.row_df_capture_scope).setOnClickListener(v -> showScopePicker());

        view.findViewById(R.id.row_df_overlay).setOnClickListener(v -> showTogglePicker(
                "rk_df_overlay",
                getString(R.string.digital_forensics_overlay),
                getString(R.string.digital_forensics_overlay_helper),
                prefs.isDfOverlayEnabled(),
                value -> {
                    prefs.setDfOverlayEnabled(value);
                    bindCurrentValues();
                }
        ));
    }

    private void showScopePicker() {
        String resultKey = "rk_df_scope";
        ArrayList<OptionItem> items = new ArrayList<>();
        items.add(new OptionItem("both", getString(R.string.digital_forensics_scope_both), getString(R.string.digital_forensics_scope_both_helper)));
        items.add(new OptionItem("people", getString(R.string.digital_forensics_scope_people), getString(R.string.digital_forensics_scope_people_helper)));
        items.add(new OptionItem("objects", getString(R.string.digital_forensics_scope_objects), getString(R.string.digital_forensics_scope_objects_helper)));

        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstance(
                getString(R.string.digital_forensics_capture_scope),
                items,
                prefs.getDfCaptureScope(),
                resultKey,
                getString(R.string.digital_forensics_scope_picker_helper)
        );
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (key, bundle) -> {
            String selectedId = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID, prefs.getDfCaptureScope());
            prefs.setDfCaptureScope(selectedId);
            bindCurrentValues();
        });
        sheet.show(getParentFragmentManager(), resultKey + "_sheet");
    }

    private void showTogglePicker(String resultKey, String title, String helper,
                                  boolean currentValue, ToggleConsumer consumer) {
        ArrayList<OptionItem> items = new ArrayList<>();
        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstanceWithSwitch(
                title,
                items,
                "",
                resultKey,
                helper,
                getString(R.string.digital_forensics_picker_switch_label),
                currentValue
        );
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (key, bundle) -> {
            boolean enabled = bundle.getBoolean(PickerBottomSheetFragment.BUNDLE_SWITCH_STATE, currentValue);
            consumer.accept(enabled);
        });
        sheet.show(getParentFragmentManager(), resultKey + "_sheet");
    }

    private void startPreviewLoop() {
        stopPreviewLoop();
        previewHandler.post(previewLoop);
    }

    private void stopPreviewLoop() {
        previewHandler.removeCallbacks(previewLoop);
    }

    private void animatePreviewStep() {
        if (previewBox == null || previewLabel == null || !isAdded()) {
            return;
        }
        final float x;
        final float y;
        final float scale;
        final String label;
        final int color;
        switch (previewStep % 3) {
            case 1:
                x = dp(120);
                y = dp(24);
                scale = 0.86f;
                label = "PERSON 92%";
                color = 0xFF29B6F6;
                break;
            case 2:
                x = dp(60);
                y = dp(78);
                scale = 0.96f;
                label = "VEHICLE 87%";
                color = 0xFFFFB300;
                break;
            default:
                x = dp(20);
                y = dp(36);
                scale = 1.0f;
                label = "PET 89%";
                color = 0xFF4CAF50;
                break;
        }
        previewStep++;
        previewLabel.setText(label);
        previewLabel.setBackgroundTintList(ColorStateList.valueOf(color));
        if (previewBox.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) previewBox.getBackground()).setStroke((int) dp(2), color);
        }
        previewBox.animate()
                .translationX(x)
                .translationY(y)
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(460L)
                .start();
        previewLabel.animate()
                .translationX(x)
                .translationY(y - dp(22))
                .alpha(0.25f)
                .setDuration(150L)
                .withEndAction(() -> {
                    if (previewLabel != null) {
                        previewLabel.animate().alpha(1f).setDuration(220L).start();
                    }
                })
                .start();
    }

    private float dp(int value) {
        return value * requireContext().getResources().getDisplayMetrics().density;
    }

    private interface ToggleConsumer {
        void accept(boolean value);
    }
}
