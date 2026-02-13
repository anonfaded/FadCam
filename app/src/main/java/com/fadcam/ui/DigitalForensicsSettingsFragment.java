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
import com.fadcam.forensics.ui.ForensicsEventsFragment;
import com.fadcam.forensics.ui.ForensicsInsightsFragment;
import com.fadcam.ui.picker.OptionItem;
import com.fadcam.ui.picker.PickerBottomSheetFragment;

import java.util.ArrayList;

public class DigitalForensicsSettingsFragment extends Fragment {

    private SharedPreferencesManager prefs;

    private TextView valueStatus;
    private TextView valuePerson;
    private TextView valueVehicle;
    private TextView valuePet;
    private TextView valueDangerous;
    private TextView valueOverlay;
    private TextView valueDaily;
    private TextView valueHeatmap;

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
        valuePerson = view.findViewById(R.id.value_df_event_person);
        valueVehicle = view.findViewById(R.id.value_df_event_vehicle);
        valuePet = view.findViewById(R.id.value_df_event_pet);
        valueDangerous = view.findViewById(R.id.value_df_event_dangerous);
        valueOverlay = view.findViewById(R.id.value_df_overlay);
        valueDaily = view.findViewById(R.id.value_df_daily_summary);
        valueHeatmap = view.findViewById(R.id.value_df_heatmap);

        bindCurrentValues();
        wireListeners(view);
    }

    private void bindCurrentValues() {
        valueStatus.setText(prefs.isDigitalForensicsEnabled()
            ? getString(R.string.digital_forensics_status_enabled_short)
            : getString(R.string.digital_forensics_status_disabled));
        valuePerson.setText(enabledLabel(prefs.isDfEventPersonEnabled()));
        valueVehicle.setText(enabledLabel(prefs.isDfEventVehicleEnabled()));
        valuePet.setText(enabledLabel(prefs.isDfEventPetEnabled()));
        valueDangerous.setText(enabledLabel(prefs.isDfDangerousObjectEnabled()));
        valueOverlay.setText(enabledLabel(prefs.isDfOverlayEnabled()));
        valueDaily.setText(enabledLabel(prefs.isDfDailySummaryEnabled()));
        valueHeatmap.setText(enabledLabel(prefs.isDfHeatmapEnabled()));
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
        view.findViewById(R.id.row_df_event_person).setOnClickListener(v -> showTogglePicker(
            "rk_df_person",
            getString(R.string.digital_forensics_event_person),
            getString(R.string.digital_forensics_person_helper),
            prefs.isDfEventPersonEnabled(),
            value -> {
                prefs.setDfEventPersonEnabled(value);
                bindCurrentValues();
            }
        ));
        view.findViewById(R.id.row_df_event_vehicle).setOnClickListener(v -> showTogglePicker(
            "rk_df_vehicle",
            getString(R.string.digital_forensics_event_vehicle),
            getString(R.string.digital_forensics_vehicle_helper),
            prefs.isDfEventVehicleEnabled(),
            value -> {
                prefs.setDfEventVehicleEnabled(value);
                bindCurrentValues();
            }
        ));
        view.findViewById(R.id.row_df_event_pet).setOnClickListener(v -> showTogglePicker(
            "rk_df_pet",
            getString(R.string.digital_forensics_event_pet),
            getString(R.string.digital_forensics_pet_helper),
            prefs.isDfEventPetEnabled(),
            value -> {
                prefs.setDfEventPetEnabled(value);
                bindCurrentValues();
            }
        ));
        view.findViewById(R.id.row_df_event_dangerous_object).setOnClickListener(v -> showTogglePicker(
            "rk_df_dangerous",
            getString(R.string.digital_forensics_event_dangerous_object),
            getString(R.string.digital_forensics_dangerous_helper),
            prefs.isDfDangerousObjectEnabled(),
            value -> {
                prefs.setDfDangerousObjectEnabled(value);
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
        view.findViewById(R.id.row_df_daily_summary).setOnClickListener(v -> showTogglePicker(
            "rk_df_daily",
            getString(R.string.digital_forensics_daily_summary),
            getString(R.string.digital_forensics_daily_helper),
            prefs.isDfDailySummaryEnabled(),
            value -> {
                prefs.setDfDailySummaryEnabled(value);
                bindCurrentValues();
            }
        ));
        view.findViewById(R.id.row_df_heatmap).setOnClickListener(v -> showTogglePicker(
            "rk_df_heatmap",
            getString(R.string.digital_forensics_heatmap),
            getString(R.string.digital_forensics_heatmap_helper),
            prefs.isDfHeatmapEnabled(),
            value -> {
                prefs.setDfHeatmapEnabled(value);
                bindCurrentValues();
            }
        ));

        View rowEvents = view.findViewById(R.id.row_df_open_events);
        View rowInsights = view.findViewById(R.id.row_df_open_insights);
        View rowDiscord = view.findViewById(R.id.row_df_discord_coming_soon);

        if (rowEvents != null) {
            rowEvents.setOnClickListener(v -> OverlayNavUtil.show(
                requireActivity(),
                new ForensicsEventsFragment(),
                "ForensicsEventsFragment"
            ));
        }
        if (rowInsights != null) {
            rowInsights.setOnClickListener(v -> OverlayNavUtil.show(
                requireActivity(),
                new ForensicsInsightsFragment(),
                "ForensicsInsightsFragment"
            ));
        }
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
        items.add(new OptionItem("enabled", getString(R.string.universal_enabled), getString(R.string.digital_forensics_picker_enabled_desc)));
        items.add(new OptionItem("disabled", getString(R.string.universal_disabled), getString(R.string.digital_forensics_picker_disabled_desc)));

        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstance(
            title,
            items,
            currentValue ? "enabled" : "disabled",
            resultKey,
            helper
        );
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (key, bundle) -> {
            String id = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            consumer.accept("enabled".equals(id));
        });
        sheet.show(getParentFragmentManager(), resultKey + "_sheet");
    }

    private interface ToggleConsumer {
        void accept(boolean value);
    }
}
