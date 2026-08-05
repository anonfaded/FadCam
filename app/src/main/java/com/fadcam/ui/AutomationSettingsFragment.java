package com.fadcam.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * AutomationSettingsFragment
 * Hosts automation preferences. Currently provides the Volume Shutter toggle
 * (volume keys act as a camera shutter on the home screen). Future automation
 * options (e.g. button mapping usable even when the app is closed) will be
 * added here.
 */
public class AutomationSettingsFragment extends BaseFragment {

    private SharedPreferencesManager prefs;
    private AvatarToggleView toggleVolumeShutter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_automation, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        prefs = SharedPreferencesManager.getInstance(requireContext());
        toggleVolumeShutter = view.findViewById(R.id.toggle_volume_shutter);
        View back = view.findViewById(R.id.back_button);
        if (back != null) back.setOnClickListener(v -> OverlayNavUtil.dismiss(requireActivity()));

        if (toggleVolumeShutter != null) {
            toggleVolumeShutter.setChecked(prefs.isVolumeShutterEnabled());
            toggleVolumeShutter.setOnCheckedChangeListener((buttonView, isChecked) ->
                    prefs.setVolumeShutterEnabled(isChecked));
            View row = view.findViewById(R.id.row_volume_shutter);
            if (row != null) row.setOnClickListener(v -> toggleVolumeShutter.performClick());
        }
    }
}
