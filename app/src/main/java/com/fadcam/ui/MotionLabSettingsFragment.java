package com.fadcam.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.motion.data.SharedPrefsMotionSettingsRepository;
import com.fadcam.motion.domain.model.MotionTriggerMode;
import com.fadcam.motion.presentation.MotionLabViewModel;
import com.fadcam.motion.presentation.MotionLabViewModelFactory;
import com.fadcam.motion.presentation.MotionLabViewState;
import com.fadcam.ui.picker.NumberInputBottomSheetFragment;
import com.fadcam.ui.picker.OptionItem;
import com.fadcam.ui.picker.PickerBottomSheetFragment;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;

public class MotionLabSettingsFragment extends Fragment {

    private MotionLabViewModel viewModel;

    private TextView valueMotionStatus;
    private TextView valueTriggerMode;
    private TextView valueSensitivity;
    private TextView valueAnalysisFps;
    private TextView valueDebounce;
    private TextView valuePostRoll;
    private TextView valuePreRoll;
    private TextView valueLowFpsTarget;
    private SwitchMaterial switchMotionEnabled;
    private SwitchMaterial switchLowFpsFallback;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_motion_lab, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initViewModel();
        bindViews(view);
        setupClickListeners(view);
        observeState();
    }

    private void initViewModel() {
        SharedPrefsMotionSettingsRepository repository = new SharedPrefsMotionSettingsRepository(
            SharedPreferencesManager.getInstance(requireContext())
        );
        MotionLabViewModelFactory factory = new MotionLabViewModelFactory(repository);
        viewModel = new ViewModelProvider(this, factory).get(MotionLabViewModel.class);
    }

    private void bindViews(@NonNull View view) {
        valueMotionStatus = view.findViewById(R.id.value_motion_status);
        valueTriggerMode = view.findViewById(R.id.value_motion_trigger_mode);
        valueSensitivity = view.findViewById(R.id.value_motion_sensitivity);
        valueAnalysisFps = view.findViewById(R.id.value_motion_analysis_fps);
        valueDebounce = view.findViewById(R.id.value_motion_debounce);
        valuePostRoll = view.findViewById(R.id.value_motion_post_roll);
        valuePreRoll = view.findViewById(R.id.value_motion_pre_roll);
        valueLowFpsTarget = view.findViewById(R.id.value_motion_low_fps_target);
        switchMotionEnabled = view.findViewById(R.id.switch_motion_enabled);
        switchLowFpsFallback = view.findViewById(R.id.switch_motion_low_fps_fallback);
    }

    private void setupClickListeners(@NonNull View view) {
        View back = view.findViewById(R.id.back_button);
        if (back != null) {
            back.setOnClickListener(v -> handleBack());
        }

        switchMotionEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> viewModel.onEnabledChanged(isChecked));
        switchLowFpsFallback.setOnCheckedChangeListener((buttonView, isChecked) -> viewModel.onLowFpsFallbackChanged(isChecked));

        view.findViewById(R.id.row_motion_trigger_mode).setOnClickListener(v -> showTriggerModePicker());
        view.findViewById(R.id.row_motion_sensitivity).setOnClickListener(v -> showSensitivityInput());
        view.findViewById(R.id.row_motion_analysis_fps).setOnClickListener(v -> showAnalysisFpsPicker());
        view.findViewById(R.id.row_motion_debounce).setOnClickListener(v -> showDebounceInput());
        view.findViewById(R.id.row_motion_post_roll).setOnClickListener(v -> showPostRollInput());
        view.findViewById(R.id.row_motion_pre_roll).setOnClickListener(v -> showPreRollInput());
        view.findViewById(R.id.row_motion_low_fps_target).setOnClickListener(v -> showLowFpsTargetPicker());
    }

    private void observeState() {
        viewModel.getState().observe(getViewLifecycleOwner(), this::render);
    }

    private void render(MotionLabViewState state) {
        switchMotionEnabled.setOnCheckedChangeListener(null);
        switchMotionEnabled.setChecked(state.enabled);
        switchMotionEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> viewModel.onEnabledChanged(isChecked));

        switchLowFpsFallback.setOnCheckedChangeListener(null);
        switchLowFpsFallback.setChecked(state.lowFpsFallbackEnabled);
        switchLowFpsFallback.setOnCheckedChangeListener((buttonView, isChecked) -> viewModel.onLowFpsFallbackChanged(isChecked));

        valueMotionStatus.setText(state.enabled
            ? getString(R.string.motion_lab_status_enabled)
            : getString(R.string.motion_lab_status_disabled));
        valueTriggerMode.setText(state.triggerMode == MotionTriggerMode.ANY_MOTION
            ? getString(R.string.motion_lab_trigger_any_motion)
            : getString(R.string.motion_lab_trigger_person_confirmed));
        valueSensitivity.setText(getString(R.string.motion_lab_percent_value, state.sensitivity));
        valueAnalysisFps.setText(getString(R.string.motion_lab_fps_value, state.analysisFps));
        valueDebounce.setText(getString(R.string.motion_lab_ms_value, state.debounceMs));
        valuePostRoll.setText(getString(R.string.motion_lab_seconds_value, state.postRollMs / 1000));
        valuePreRoll.setText(getString(R.string.motion_lab_seconds_value, state.preRollSeconds));
        valueLowFpsTarget.setText(getString(R.string.motion_lab_fps_value, state.lowFpsTarget));

        View targetRow = requireView().findViewById(R.id.row_motion_low_fps_target);
        targetRow.setAlpha(state.lowFpsFallbackEnabled ? 1f : 0.5f);
        targetRow.setEnabled(state.lowFpsFallbackEnabled);
    }

    private void showTriggerModePicker() {
        final String resultKey = "rk_motion_trigger_mode";
        ArrayList<OptionItem> items = new ArrayList<>();
        items.add(new OptionItem("any_motion", getString(R.string.motion_lab_trigger_any_motion), getString(R.string.motion_lab_trigger_any_motion_desc)));
        items.add(new OptionItem("person_confirmed", getString(R.string.motion_lab_trigger_person_confirmed), getString(R.string.motion_lab_trigger_person_confirmed_desc)));

        MotionLabViewState current = viewModel.getState().getValue();
        String selected = current != null && current.triggerMode == MotionTriggerMode.ANY_MOTION ? "any_motion" : "person_confirmed";
        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstance(
            getString(R.string.motion_lab_trigger_mode), items, selected, resultKey,
            getString(R.string.motion_lab_trigger_mode_helper)
        );

        getParentFragmentManager().setFragmentResultListener(resultKey, this, (key, bundle) -> {
            String id = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if ("any_motion".equals(id)) {
                viewModel.onTriggerModeChanged(MotionTriggerMode.ANY_MOTION);
            } else if ("person_confirmed".equals(id)) {
                viewModel.onTriggerModeChanged(MotionTriggerMode.PERSON_CONFIRMED);
            }
        });

        sheet.show(getParentFragmentManager(), "motion_trigger_mode_picker");
    }

    private void showAnalysisFpsPicker() {
        final String resultKey = "rk_motion_analysis_fps";
        ArrayList<OptionItem> items = new ArrayList<>();
        items.add(new OptionItem("2", "2 fps", getString(R.string.motion_lab_analysis_2_desc)));
        items.add(new OptionItem("3", "3 fps", getString(R.string.motion_lab_analysis_3_desc)));
        items.add(new OptionItem("5", "5 fps", getString(R.string.motion_lab_analysis_5_desc)));

        MotionLabViewState current = viewModel.getState().getValue();
        String selected = current == null ? "3" : String.valueOf(current.analysisFps);
        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstance(
            getString(R.string.motion_lab_analysis_fps), items, selected, resultKey,
            getString(R.string.motion_lab_analysis_helper)
        );

        getParentFragmentManager().setFragmentResultListener(resultKey, this, (key, bundle) -> {
            String id = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            try {
                if (id != null) {
                    viewModel.onAnalysisFpsChanged(Integer.parseInt(id));
                }
            } catch (NumberFormatException ignored) {
            }
        });

        sheet.show(getParentFragmentManager(), "motion_analysis_fps_picker");
    }

    private void showLowFpsTargetPicker() {
        MotionLabViewState currentState = viewModel.getState().getValue();
        if (currentState != null && !currentState.lowFpsFallbackEnabled) {
            return;
        }

        final String resultKey = "rk_motion_low_fps_target";
        ArrayList<OptionItem> items = new ArrayList<>();
        items.add(new OptionItem("1", "1 fps", getString(R.string.motion_lab_low_fps_1_desc)));
        items.add(new OptionItem("2", "2 fps", getString(R.string.motion_lab_low_fps_2_desc)));
        items.add(new OptionItem("5", "5 fps", getString(R.string.motion_lab_low_fps_5_desc)));

        MotionLabViewState current = viewModel.getState().getValue();
        String selected = current == null ? "1" : String.valueOf(current.lowFpsTarget);
        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstance(
            getString(R.string.motion_lab_low_fps_target), items, selected, resultKey,
            getString(R.string.motion_lab_low_fps_target_helper)
        );

        getParentFragmentManager().setFragmentResultListener(resultKey, this, (key, bundle) -> {
            String id = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            try {
                if (id != null) {
                    viewModel.onLowFpsTargetChanged(Integer.parseInt(id));
                }
            } catch (NumberFormatException ignored) {
            }
        });

        sheet.show(getParentFragmentManager(), "motion_low_fps_target_picker");
    }

    private void showSensitivityInput() {
        MotionLabViewState current = viewModel.getState().getValue();
        int initial = current == null ? 60 : current.sensitivity;
        showNumericInput(
            "rk_motion_sensitivity",
            getString(R.string.motion_lab_sensitivity),
            0,
            100,
            initial,
            getString(R.string.motion_lab_sensitivity_hint),
            value -> viewModel.onSensitivityChanged(value)
        );
    }

    private void showDebounceInput() {
        MotionLabViewState current = viewModel.getState().getValue();
        int initial = current == null ? 700 : current.debounceMs;
        showNumericInput(
            "rk_motion_debounce",
            getString(R.string.motion_lab_debounce),
            100,
            3000,
            initial,
            getString(R.string.motion_lab_debounce_hint),
            value -> viewModel.onDebounceMsChanged(value)
        );
    }

    private void showPostRollInput() {
        MotionLabViewState current = viewModel.getState().getValue();
        int initialSeconds = current == null ? 12 : current.postRollMs / 1000;
        showNumericInput(
            "rk_motion_post_roll",
            getString(R.string.motion_lab_post_roll),
            1,
            180,
            initialSeconds,
            getString(R.string.motion_lab_post_roll_hint),
            value -> viewModel.onPostRollMsChanged(value * 1000)
        );
    }

    private void showPreRollInput() {
        MotionLabViewState current = viewModel.getState().getValue();
        int initial = current == null ? 3 : current.preRollSeconds;
        showNumericInput(
            "rk_motion_pre_roll",
            getString(R.string.motion_lab_pre_roll),
            0,
            15,
            initial,
            getString(R.string.motion_lab_pre_roll_hint),
            value -> viewModel.onPreRollSecondsChanged(value)
        );
    }

    private void showNumericInput(
        String resultKey,
        String title,
        int min,
        int max,
        int initial,
        String helper,
        IntConsumer consumer
    ) {
        NumberInputBottomSheetFragment sheet = NumberInputBottomSheetFragment.newInstance(
            title,
            min,
            max,
            initial,
            getString(R.string.universal_enter_number),
            min,
            max,
            helper,
            helper,
            resultKey
        );

        getParentFragmentManager().setFragmentResultListener(resultKey, this, (key, bundle) -> {
            int value = bundle.getInt(NumberInputBottomSheetFragment.RESULT_NUMBER, initial);
            consumer.accept(value);
        });

        sheet.show(getParentFragmentManager(), resultKey + "_sheet");
    }

    private void handleBack() {
        if (getActivity() != null) {
            OverlayNavUtil.dismiss(requireActivity());
        }
    }

    private interface IntConsumer {
        void accept(int value);
    }
}
