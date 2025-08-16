package com.fadcam.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.MainActivity;
import com.fadcam.ui.OverlayNavUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

/**
 * BehaviorSettingsFragment
 * Hosts general behavior preferences (notification customization relocated from legacy SettingsFragment).
 * NOTE: This currently implements a simplified notification preset dialog; full legacy
 * customization (including custom text editing & preview) will be migrated verbatim later.
 */
public class BehaviorSettingsFragment extends BaseFragment {

    private SharedPreferencesManager prefs;
    private TextView valueOnboardingState;
    private TextView valueAutoUpdateState;
    private com.google.android.material.materialswitch.MaterialSwitch toggleOnboarding;
    private com.google.android.material.materialswitch.MaterialSwitch toggleAutoUpdate;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_behavior, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // -------------- Fix Start for this method(onViewCreated)-----------
        prefs = SharedPreferencesManager.getInstance(requireContext());
        valueOnboardingState = view.findViewById(R.id.value_onboarding_state);
        valueAutoUpdateState = view.findViewById(R.id.value_auto_update_state);
        toggleOnboarding = view.findViewById(R.id.toggle_onboarding);
        toggleAutoUpdate = view.findViewById(R.id.toggle_auto_update);
    View back = view.findViewById(R.id.back_button);
    if (back != null) back.setOnClickListener(v -> OverlayNavUtil.dismiss(requireActivity()));
        // -------------- Fix Start for this method(onViewCreated - onboarding & auto update toggles)-----------
        if (toggleOnboarding != null) {
            boolean show = prefs.isShowOnboarding();
            toggleOnboarding.setChecked(show);
            toggleOnboarding.setOnCheckedChangeListener((buttonView, isChecked) -> {
                // If enabling onboarding again, also clear FIRST_INSTALL_CHECKED_KEY so onboarding shows
                if (isChecked) {
                    prefs.sharedPreferences.edit().putBoolean(com.fadcam.Constants.FIRST_INSTALL_CHECKED_KEY, false).apply();
                }
                prefs.setShowOnboarding(isChecked);
                refreshValues();
            });
        }
        if (toggleAutoUpdate != null) {
            boolean enabled = prefs.sharedPreferences.getBoolean("auto_update_check_enabled", true);
            toggleAutoUpdate.setChecked(enabled);
            toggleAutoUpdate.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.sharedPreferences.edit().putBoolean("auto_update_check_enabled", isChecked).apply();
                refreshValues();
            });
        }
        // -------------- Fix Ended for this method(onViewCreated - onboarding & auto update toggles)-----------
        refreshValues();
        // -------------- Fix Ended for this method(onViewCreated)-----------
    }

    // Removed duplicate manual back handling; centralized via OverlayNavUtil

    private void refreshValues() {
        // -------------- Fix Start for this method(refreshValues)-----------
        if (valueOnboardingState != null) {
            valueOnboardingState.setText(prefs.isShowOnboarding() ? "Enabled" : "Disabled");
        }
        if (valueAutoUpdateState != null) {
            boolean enabled = prefs.sharedPreferences.getBoolean("auto_update_check_enabled", true);
            valueAutoUpdateState.setText(enabled ? "Enabled" : "Disabled");
        }
        // -------------- Fix Ended for this method(refreshValues)-----------
    }

}
