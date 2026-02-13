package com.fadcam.ui;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.Constants;
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
    private TextView valueDebugState;
    private TextView valueDebugScore;
    private TextView valueDebugThreshold;
    private TextView valueDebugAction;
    private TextView valueDebugPerson;
    private TextView valueDebugMetrics;
    private ImageView imageDebugFrame;
    private View buttonDebugCopy;
    private SwitchMaterial switchMotionEnabled;
    private SwitchMaterial switchMotionAutoTorch;
    private String latestDebugSnapshot = "";
    private boolean motionDebugReceiverRegistered = false;
    private final BroadcastReceiver motionDebugReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Constants.BROADCAST_MOTION_LAB_DEBUG.equals(intent.getAction())) {
                return;
            }
            updateDebugValues(intent);
        }
    };

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
        valueDebugState = view.findViewById(R.id.value_motion_debug_state);
        valueDebugScore = view.findViewById(R.id.value_motion_debug_score);
        valueDebugThreshold = view.findViewById(R.id.value_motion_debug_threshold);
        valueDebugAction = view.findViewById(R.id.value_motion_debug_action);
        valueDebugPerson = view.findViewById(R.id.value_motion_debug_person);
        valueDebugMetrics = view.findViewById(R.id.value_motion_debug_metrics);
        imageDebugFrame = view.findViewById(R.id.image_motion_debug_frame);
        buttonDebugCopy = view.findViewById(R.id.button_motion_debug_copy);
        switchMotionEnabled = view.findViewById(R.id.switch_motion_enabled);
        switchMotionAutoTorch = view.findViewById(R.id.switch_motion_auto_torch);
    }

    private void setupClickListeners(@NonNull View view) {
        View back = view.findViewById(R.id.back_button);
        if (back != null) {
            back.setOnClickListener(v -> handleBack());
        }

        switchMotionEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> viewModel.onEnabledChanged(isChecked));
        switchMotionAutoTorch.setOnCheckedChangeListener((buttonView, isChecked) -> viewModel.onAutoTorchChanged(isChecked));

        view.findViewById(R.id.row_motion_trigger_mode).setOnClickListener(v -> showTriggerModePicker());
        view.findViewById(R.id.row_motion_sensitivity).setOnClickListener(v -> showSensitivityInput());
        view.findViewById(R.id.row_motion_analysis_fps).setOnClickListener(v -> showAnalysisFpsPicker());
        view.findViewById(R.id.row_motion_debounce).setOnClickListener(v -> showDebounceInput());
        view.findViewById(R.id.row_motion_post_roll).setOnClickListener(v -> showPostRollInput());
        view.findViewById(R.id.row_motion_pre_roll).setOnClickListener(v -> showPreRollInput());
        View info = view.findViewById(R.id.info_button);
        if (info != null) {
            info.setOnClickListener(v -> showMotionLabInfoBottomSheet());
        }
        if (buttonDebugCopy != null) {
            buttonDebugCopy.setOnClickListener(v -> copyDebugSnapshot());
        }
    }

    private void observeState() {
        viewModel.getState().observe(getViewLifecycleOwner(), this::render);
    }

    private void render(MotionLabViewState state) {
        switchMotionEnabled.setOnCheckedChangeListener(null);
        switchMotionEnabled.setChecked(state.enabled);
        switchMotionEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> viewModel.onEnabledChanged(isChecked));

        switchMotionAutoTorch.setOnCheckedChangeListener(null);
        switchMotionAutoTorch.setChecked(state.autoTorchEnabled);
        switchMotionAutoTorch.setOnCheckedChangeListener((buttonView, isChecked) -> viewModel.onAutoTorchChanged(isChecked));

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
        MotionLabViewState current = viewModel.getState().getValue();
        int initial = current == null ? 8 : current.analysisFps;
        showNumericInput(
            "rk_motion_analysis_fps",
            getString(R.string.motion_lab_analysis_fps),
            1,
            120,
            initial,
            getString(R.string.motion_lab_analysis_helper),
            value -> viewModel.onAnalysisFpsChanged(value)
        );
    }

    private void showSensitivityInput() {
        MotionLabViewState current = viewModel.getState().getValue();
        int initial = current == null ? 80 : current.sensitivity;
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
        int initial = current == null ? 220 : current.debounceMs;
        showNumericInput(
            "rk_motion_debounce",
            getString(R.string.motion_lab_debounce),
            0,
            60000,
            initial,
            getString(R.string.motion_lab_debounce_hint),
            value -> viewModel.onDebounceMsChanged(value)
        );
    }

    private void showPostRollInput() {
        MotionLabViewState current = viewModel.getState().getValue();
        int initialSeconds = current == null ? 10 : current.postRollMs / 1000;
        showNumericInput(
            "rk_motion_post_roll",
            getString(R.string.motion_lab_post_roll),
            0,
            3600,
            initialSeconds,
            getString(R.string.motion_lab_post_roll_hint),
            value -> viewModel.onPostRollMsChanged(value * 1000)
        );
    }

    private void showPreRollInput() {
        MotionLabViewState current = viewModel.getState().getValue();
        int initial = current == null ? 2 : current.preRollSeconds;
        showNumericInput(
            "rk_motion_pre_roll",
            getString(R.string.motion_lab_pre_roll),
            0,
            120,
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
        final String pickerResultKey = resultKey + "_picker";
        final String inputResultKey = resultKey + "_input";

        ArrayList<OptionItem> items = new ArrayList<>();
        items.add(new OptionItem(
            "set_value",
            getString(R.string.motion_lab_set_value_row_title),
            getString(R.string.motion_lab_set_value_row_subtitle, initial),
            null,
            null,
            R.drawable.ic_arrow_right
        ));

        PickerBottomSheetFragment pickerSheet = PickerBottomSheetFragment.newInstance(
            title,
            items,
            null,
            pickerResultKey,
            helper
        );
        if (pickerSheet.getArguments() != null) {
            pickerSheet.getArguments().putBoolean(PickerBottomSheetFragment.ARG_HIDE_CHECK, true);
        }

        getParentFragmentManager().setFragmentResultListener(pickerResultKey, this, (key, bundle) -> {
            String selected = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (!"set_value".equals(selected)) {
                return;
            }
            NumberInputBottomSheetFragment inputSheet = NumberInputBottomSheetFragment.newInstance(
                title,
                min,
                max,
                initial,
                getString(R.string.universal_enter_number),
                min,
                max,
                helper,
                helper,
                inputResultKey
            );
            getParentFragmentManager().setFragmentResultListener(inputResultKey, this, (inputKey, inputBundle) -> {
                int value = inputBundle.getInt(NumberInputBottomSheetFragment.RESULT_NUMBER, initial);
                consumer.accept(value);
            });
            inputSheet.show(getParentFragmentManager(), inputResultKey + "_sheet");
        });

        pickerSheet.show(getParentFragmentManager(), pickerResultKey + "_sheet");
    }

    private void handleBack() {
        if (getActivity() != null) {
            OverlayNavUtil.dismiss(requireActivity());
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        registerMotionDebugReceiverIfNeeded();
    }

    @Override
    public void onStop() {
        unregisterMotionDebugReceiverIfNeeded();
        super.onStop();
    }

    private void registerMotionDebugReceiverIfNeeded() {
        if (motionDebugReceiverRegistered || getContext() == null) {
            return;
        }
        IntentFilter filter = new IntentFilter(Constants.BROADCAST_MOTION_LAB_DEBUG);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().registerReceiver(
                    motionDebugReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                );
            } else {
                requireContext().registerReceiver(motionDebugReceiver, filter);
            }
            motionDebugReceiverRegistered = true;
        } catch (Exception ignored) {
        }
    }

    private void unregisterMotionDebugReceiverIfNeeded() {
        if (!motionDebugReceiverRegistered || getContext() == null) {
            return;
        }
        try {
            requireContext().unregisterReceiver(motionDebugReceiver);
        } catch (Exception ignored) {
        } finally {
            motionDebugReceiverRegistered = false;
        }
    }

    private void updateDebugValues(@NonNull Intent intent) {
        String state = intent.getStringExtra(Constants.EXTRA_MOTION_DEBUG_STATE);
        String action = intent.getStringExtra(Constants.EXTRA_MOTION_DEBUG_ACTION);
        float score = intent.getFloatExtra(Constants.EXTRA_MOTION_DEBUG_SCORE, 0f);
        float raw = intent.getFloatExtra(Constants.EXTRA_MOTION_DEBUG_RAW_SCORE, 0f);
        float startThreshold = intent.getFloatExtra(Constants.EXTRA_MOTION_DEBUG_START_THRESHOLD, 0f);
        float stopThreshold = intent.getFloatExtra(Constants.EXTRA_MOTION_DEBUG_STOP_THRESHOLD, 0f);
        float personConf = intent.getFloatExtra(Constants.EXTRA_MOTION_DEBUG_PERSON_CONF, 0f);
        float changedArea = intent.getFloatExtra(Constants.EXTRA_MOTION_DEBUG_CHANGED_AREA, 0f);
        float strongArea = intent.getFloatExtra(Constants.EXTRA_MOTION_DEBUG_STRONG_AREA, 0f);
        float meanDelta = intent.getFloatExtra(Constants.EXTRA_MOTION_DEBUG_MEAN_DELTA, 0f);
        float bgDelta = intent.getFloatExtra(Constants.EXTRA_MOTION_DEBUG_BG_DELTA, 0f);
        float maxDelta = intent.getFloatExtra(Constants.EXTRA_MOTION_DEBUG_MAX_DELTA, 0f);
        boolean globalSuppressed = intent.getBooleanExtra(Constants.EXTRA_MOTION_DEBUG_GLOBAL_SUPPRESSED, false);
        boolean person = intent.getBooleanExtra(Constants.EXTRA_MOTION_DEBUG_PERSON, false);

        valueDebugState.setText(state == null ? "-" : state);
        valueDebugScore.setText(getString(R.string.motion_lab_debug_score_value, raw, score));
        valueDebugThreshold.setText(getString(R.string.motion_lab_debug_threshold_value, startThreshold, stopThreshold));
        valueDebugAction.setText(action == null ? "-" : action);
        valueDebugPerson.setText(getString(R.string.motion_lab_debug_person_value, person ? "YES" : "NO", personConf));
        valueDebugMetrics.setText(getString(
            R.string.motion_lab_debug_metrics_value,
            changedArea,
            strongArea,
            meanDelta,
            bgDelta,
            maxDelta,
            globalSuppressed ? "YES" : "NO"
        ));
        applyDebugTerminalColors(state, action, score, person, personConf);

        latestDebugSnapshot = "state=" + (state == null ? "-" : state)
            + ", action=" + (action == null ? "-" : action)
            + ", raw=" + String.format(java.util.Locale.US, "%.3f", raw)
            + ", smoothed=" + String.format(java.util.Locale.US, "%.3f", score)
            + ", startThreshold=" + String.format(java.util.Locale.US, "%.3f", startThreshold)
            + ", stopThreshold=" + String.format(java.util.Locale.US, "%.3f", stopThreshold)
            + ", person=" + (person ? "YES" : "NO")
            + ", personConf=" + String.format(java.util.Locale.US, "%.3f", personConf)
            + ", area=" + String.format(java.util.Locale.US, "%.3f", changedArea)
            + ", strong=" + String.format(java.util.Locale.US, "%.3f", strongArea)
            + ", meanDelta=" + String.format(java.util.Locale.US, "%.3f", meanDelta)
            + ", bgDelta=" + String.format(java.util.Locale.US, "%.3f", bgDelta)
            + ", maxDelta=" + String.format(java.util.Locale.US, "%.3f", maxDelta)
            + ", globalSuppressed=" + (globalSuppressed ? "YES" : "NO");

        byte[] frameJpeg = intent.getByteArrayExtra(Constants.EXTRA_MOTION_DEBUG_FRAME_JPEG);
        if (frameJpeg != null && frameJpeg.length > 0 && imageDebugFrame != null) {
            imageDebugFrame.setImageBitmap(BitmapFactory.decodeByteArray(frameJpeg, 0, frameJpeg.length));
        }
    }

    private void applyDebugTerminalColors(String state, String action, float score, boolean person, float personConf) {
        int green = Color.parseColor("#7CFF8A");
        int cyan = Color.parseColor("#67E8F9");
        int amber = Color.parseColor("#FBBF24");
        int red = Color.parseColor("#F87171");
        int bright = Color.parseColor("#B2FF59");

        TextView[] views = new TextView[]{
            valueDebugState, valueDebugScore, valueDebugThreshold, valueDebugAction, valueDebugPerson, valueDebugMetrics
        };
        for (TextView tv : views) {
            if (tv != null) {
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextColor(green);
            }
        }
        if (valueDebugState != null) {
            if ("RECORDING".equals(state)) {
                valueDebugState.setTextColor(bright);
            } else if ("POST_ROLL".equals(state)) {
                valueDebugState.setTextColor(amber);
            } else {
                valueDebugState.setTextColor(cyan);
            }
        }
        if (valueDebugAction != null) {
            if ("START_RECORDING".equals(action)) {
                valueDebugAction.setTextColor(bright);
            } else if ("STOP_RECORDING".equals(action)) {
                valueDebugAction.setTextColor(red);
            }
        }
        if (valueDebugScore != null && score >= 0.22f) {
            valueDebugScore.setTextColor(bright);
        }
        if (valueDebugPerson != null && person && personConf >= 0.65f) {
            valueDebugPerson.setTextColor(cyan);
        }
    }

    private void showMotionLabInfoBottomSheet() {
        if (!isAdded() || getParentFragmentManager().isStateSaved()) {
            return;
        }
        MotionLabInfoBottomSheetFragment.newInstance()
            .show(getParentFragmentManager(), "motion_lab_info_sheet");
    }

    private void copyDebugSnapshot() {
        if (!isAdded()) {
            return;
        }
        String snapshot = latestDebugSnapshot == null || latestDebugSnapshot.isEmpty()
            ? getString(R.string.motion_lab_debug_no_snapshot)
            : latestDebugSnapshot;
        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("Motion monitor snapshot", snapshot));
        Toast.makeText(requireContext(), getString(R.string.motion_lab_debug_copy_done), Toast.LENGTH_SHORT).show();
    }

    private interface IntConsumer {
        void accept(int value);
    }
}
