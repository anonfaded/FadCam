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
import com.fadcam.SharedPreferencesManager;
import com.fadcam.ui.picker.OptionItem;
import com.fadcam.ui.picker.PickerBottomSheetFragment;

import java.util.ArrayList;

public class DigitalForensicsSettingsFragment extends Fragment {

    private SharedPreferencesManager prefs;

    private android.view.View rootView;
    private TextView valueStatus;
    private TextView valueEvidence;
    private TextView valueCaptureScope;
    private TextView valueOverlay;
    private View motionLabBanner;
    private View rowCaptureScope;
    /** Onboarding forensics animation shown at the top of this screen. */
    private com.fadcam.ui.animations.AnimationView previewAnimation;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_digital_forensics, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        com.fadcam.Utils.attachPressScaleToClickableRows(view);
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
        previewAnimation = view.findViewById(R.id.animation_forensics_preview);
        if (previewAnimation != null) {
            // Same two calls onboarding makes. playOnce() is what marks the view as
            // started; without it shouldAnimate() stays false and the scene never
            // attaches to the shared clock, so it renders a single frozen frame.
            previewAnimation.setAmbientEnabled(true);
            previewAnimation.playOnce(0L);
        }

        this.rootView = view;
        bindCurrentValues();
        wireListeners(view);
    }

    @Override
    public void onResume() {
        super.onResume();
        bindCurrentValues();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        previewAnimation = null;
        valueStatus = null;
        valueEvidence = null;
        valueCaptureScope = null;
        valueOverlay = null;
        motionLabBanner = null;
        rowCaptureScope = null;
    }

    /**
     * Explains, in the forensics screen itself, when this phone's GPU is too slow for
     * the full detection cadence. Evidence still works; it is simply sampled less
     * often. Shown from the measured value so it is never a guess about the hardware.
     */
    private void bindSlowDeviceNotice(Object view) {
        if (!(view instanceof android.view.View)) {
            return;
        }
        android.widget.TextView notice = ((android.view.View) view)
                .findViewById(R.id.text_df_slow_device_notice);
        if (notice == null) {
            return;
        }
        String tooSlow = com.fadcam.motion.domain.detector.ObjectDetectionRuntime
                .getTooSlowReason();
        long measuredMs = com.fadcam.motion.domain.detector.ObjectDetectionRuntime
                .getLastInferenceMs();
        if (tooSlow == null || measuredMs <= 0L) {
            notice.setVisibility(android.view.View.GONE);
            return;
        }
        long seconds = Math.max(1L, Math.round(measuredMs / 1000.0));
        notice.setText(getString(R.string.digital_forensics_slow_device_notice, seconds));
        notice.setVisibility(android.view.View.VISIBLE);
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
        if (rootView != null) {
            bindSlowDeviceNotice(rootView);
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
                    com.fadcam.motion.domain.detector.ObjectDetectionRuntime.sync(requireContext());
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
                    com.fadcam.motion.domain.detector.ObjectDetectionRuntime.sync(requireContext());
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
                    com.fadcam.motion.domain.detector.ObjectDetectionRuntime.sync(requireContext());
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

    private interface ToggleConsumer {
        void accept(boolean value);
    }
}
