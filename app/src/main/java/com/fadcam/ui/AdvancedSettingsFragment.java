package com.fadcam.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.Constants;
import com.fadcam.MainActivity;
import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * AdvancedSettingsFragment
 * Hosts debug logging toggle. About screen now opens directly from home via its own row.
 */
public class AdvancedSettingsFragment extends Fragment {

    private SharedPreferencesManager prefs;
    private SwitchMaterial debugSwitch;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_advanced, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // -------------- Fix Start for this method(onViewCreated)-----------
        prefs = SharedPreferencesManager.getInstance(requireContext());
        debugSwitch = view.findViewById(R.id.switch_debug_logging);
        if (debugSwitch != null) {
            debugSwitch.setChecked(prefs.isDebugLoggingEnabled());
            debugSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.sharedPreferences.edit().putBoolean(Constants.PREF_DEBUG_DATA, isChecked).apply();
                // Eagerly toggle logger runtime so file is created right away when enabled
                com.fadcam.Log.setDebugEnabled(isChecked);
            });
        }
        View back = view.findViewById(R.id.back_button);
        if (back != null) {
            back.setOnClickListener(v -> handleBack());
        }
        View tools = view.findViewById(R.id.row_debug_log_tools);
        if (tools != null) {
            tools.setOnClickListener(v -> {
                DebugLogBottomSheetFragment f = DebugLogBottomSheetFragment.newInstance();
                f.show(getParentFragmentManager(), "debug_log_tools");
            });
        }
        // -------------- Fix Ended for this method(onViewCreated)-----------
    }

    private void handleBack() {
        if (getActivity() != null) {
            requireActivity().getSupportFragmentManager().popBackStack();
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).hideOverlayIfNoFragments();
            }
        }
    }
}
