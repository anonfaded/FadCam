package com.fadcam.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

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
    private TextView valueOverlay;

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
        valueOverlay = view.findViewById(R.id.value_df_overlay);

        bindCurrentValues();
        wireListeners(view);
    }

    private void bindCurrentValues() {
        // Event classes are now always detected together by default.
        prefs.setDfEventPersonEnabled(true);
        prefs.setDfEventVehicleEnabled(true);
        prefs.setDfEventPetEnabled(true);
        prefs.setDfDangerousObjectEnabled(true);
        prefs.setDfDailySummaryEnabled(true);
        prefs.setDfHeatmapEnabled(true);
        valueStatus.setText(prefs.isDigitalForensicsEnabled()
            ? getString(R.string.digital_forensics_status_enabled_short)
            : getString(R.string.digital_forensics_status_disabled));
        valueOverlay.setText(enabledLabel(prefs.isDfOverlayEnabled()));
    }

    private String enabledLabel(boolean enabled) {
        return enabled
            ? getString(R.string.digital_forensics_status_enabled_short)
            : getString(R.string.digital_forensics_status_disabled);
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
        View rowDiscord = view.findViewById(R.id.row_df_discord_coming_soon);

        if (rowDiscord != null) {
            rowDiscord.setOnClickListener(v -> Toast.makeText(
                requireContext(),
                getString(R.string.digital_forensics_discord_coming_soon_toast),
                Toast.LENGTH_SHORT
            ).show());
        }
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
