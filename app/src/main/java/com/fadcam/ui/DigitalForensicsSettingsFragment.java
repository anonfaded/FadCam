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
import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * DigitalForensicsSettingsFragment
 * Phase 0 shell for Digital Forensics advanced controls.
 */
public class DigitalForensicsSettingsFragment extends Fragment {

    private SharedPreferencesManager prefs;
    private SwitchMaterial switchEnabled;
    private SwitchMaterial switchPerson;
    private SwitchMaterial switchVehicle;
    private SwitchMaterial switchPet;
    private SwitchMaterial switchDangerous;
    private SwitchMaterial switchOverlay;
    private SwitchMaterial switchDailySummary;
    private SwitchMaterial switchHeatmap;
    private TextView valueStatus;

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
            back.setOnClickListener(v -> OverlayNavUtil.dismiss(requireActivity()));
        }

        switchEnabled = view.findViewById(R.id.switch_df_enabled);
        switchPerson = view.findViewById(R.id.switch_df_event_person);
        switchVehicle = view.findViewById(R.id.switch_df_event_vehicle);
        switchPet = view.findViewById(R.id.switch_df_event_pet);
        switchDangerous = view.findViewById(R.id.switch_df_event_dangerous_object);
        switchOverlay = view.findViewById(R.id.switch_df_overlay);
        switchDailySummary = view.findViewById(R.id.switch_df_daily_summary);
        switchHeatmap = view.findViewById(R.id.switch_df_heatmap);
        valueStatus = view.findViewById(R.id.value_df_status);

        bindCurrentValues();
        wireListeners(view);
    }

    private void bindCurrentValues() {
        switchEnabled.setOnCheckedChangeListener(null);
        switchPerson.setOnCheckedChangeListener(null);
        switchVehicle.setOnCheckedChangeListener(null);
        switchPet.setOnCheckedChangeListener(null);
        switchDangerous.setOnCheckedChangeListener(null);
        switchOverlay.setOnCheckedChangeListener(null);
        switchDailySummary.setOnCheckedChangeListener(null);
        switchHeatmap.setOnCheckedChangeListener(null);

        boolean enabled = prefs.isDigitalForensicsEnabled();
        switchEnabled.setChecked(enabled);
        switchPerson.setChecked(prefs.isDfEventPersonEnabled());
        switchVehicle.setChecked(prefs.isDfEventVehicleEnabled());
        switchPet.setChecked(prefs.isDfEventPetEnabled());
        switchDangerous.setChecked(prefs.isDfDangerousObjectEnabled());
        switchOverlay.setChecked(prefs.isDfOverlayEnabled());
        switchDailySummary.setChecked(prefs.isDfDailySummaryEnabled());
        switchHeatmap.setChecked(prefs.isDfHeatmapEnabled());
        valueStatus.setText(enabled
            ? getString(R.string.digital_forensics_status_enabled)
            : getString(R.string.digital_forensics_status_disabled));

        float alpha = enabled ? 1f : 0.45f;
        switchPerson.setAlpha(alpha);
        switchVehicle.setAlpha(alpha);
        switchPet.setAlpha(alpha);
        switchDangerous.setAlpha(alpha);
        switchOverlay.setAlpha(alpha);
        switchDailySummary.setAlpha(alpha);
        switchHeatmap.setAlpha(alpha);
        switchPerson.setEnabled(enabled);
        switchVehicle.setEnabled(enabled);
        switchPet.setEnabled(enabled);
        switchDangerous.setEnabled(enabled);
        switchOverlay.setEnabled(enabled);
        switchDailySummary.setEnabled(enabled);
        switchHeatmap.setEnabled(enabled);
    }

    private void wireListeners(@NonNull View view) {
        switchEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.setDigitalForensicsEnabled(isChecked);
            bindCurrentValues();
        });
        switchPerson.setOnCheckedChangeListener((buttonView, isChecked) -> prefs.setDfEventPersonEnabled(isChecked));
        switchVehicle.setOnCheckedChangeListener((buttonView, isChecked) -> prefs.setDfEventVehicleEnabled(isChecked));
        switchPet.setOnCheckedChangeListener((buttonView, isChecked) -> prefs.setDfEventPetEnabled(isChecked));
        switchDangerous.setOnCheckedChangeListener((buttonView, isChecked) -> prefs.setDfDangerousObjectEnabled(isChecked));
        switchOverlay.setOnCheckedChangeListener((buttonView, isChecked) -> prefs.setDfOverlayEnabled(isChecked));
        switchDailySummary.setOnCheckedChangeListener((buttonView, isChecked) -> prefs.setDfDailySummaryEnabled(isChecked));
        switchHeatmap.setOnCheckedChangeListener((buttonView, isChecked) -> prefs.setDfHeatmapEnabled(isChecked));

        View rowDiscord = view.findViewById(R.id.row_df_discord_coming_soon);
        if (rowDiscord != null) {
            rowDiscord.setOnClickListener(v -> Toast.makeText(
                requireContext(),
                getString(R.string.digital_forensics_discord_coming_soon_toast),
                Toast.LENGTH_SHORT
            ).show());
        }
    }
}

