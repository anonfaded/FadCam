package com.fadcam.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.fadcam.R;

/**
 * AdvancedSettingsFragment
 * Hosts power-user settings entry points.
 */
public class AdvancedSettingsFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_advanced, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        View back = view.findViewById(R.id.back_button);
        if (back != null) {
            back.setOnClickListener(v -> handleBack());
        }
        View motionLab = view.findViewById(R.id.row_motion_lab);
        if (motionLab != null) {
            motionLab.setOnClickListener(v -> OverlayNavUtil.show(
                requireActivity(),
                new MotionLabSettingsFragment(),
                "MotionLabSettingsFragment"
            ));
        }
        View digitalForensics = view.findViewById(R.id.row_digital_forensics);
        if (digitalForensics != null) {
            digitalForensics.setOnClickListener(v -> OverlayNavUtil.show(
                requireActivity(),
                new DigitalForensicsSettingsFragment(),
                "DigitalForensicsSettingsFragment"
            ));
        }
        View thermalGuardian = view.findViewById(R.id.row_thermal_guardian);
        if (thermalGuardian != null) {
            thermalGuardian.setOnClickListener(v ->
                android.widget.Toast.makeText(requireContext(), R.string.mini_app_coming_soon_desc, android.widget.Toast.LENGTH_SHORT).show()
            );
        }
        View audioVision = view.findViewById(R.id.row_audio_vision);
        if (audioVision != null) {
            audioVision.setOnClickListener(v ->
                android.widget.Toast.makeText(requireContext(), R.string.mini_app_coming_soon_desc, android.widget.Toast.LENGTH_SHORT).show()
            );
        }
        View scheduledRecording = view.findViewById(R.id.row_scheduled_recording);
        if (scheduledRecording != null) {
            scheduledRecording.setOnClickListener(v ->
                android.widget.Toast.makeText(requireContext(), R.string.mini_app_coming_soon_desc, android.widget.Toast.LENGTH_SHORT).show()
            );
        }
        View profiles = view.findViewById(R.id.row_profiles);
        if (profiles != null) {
            profiles.setOnClickListener(v ->
                android.widget.Toast.makeText(requireContext(), R.string.mini_app_coming_soon_desc, android.widget.Toast.LENGTH_SHORT).show()
            );
        }
        View fadcrypt = view.findViewById(R.id.row_fadcrypt);
        if (fadcrypt != null) {
            fadcrypt.setOnClickListener(v ->
                android.widget.Toast.makeText(requireContext(), R.string.mini_app_coming_soon_desc, android.widget.Toast.LENGTH_SHORT).show()
            );
        }
    }

    private void handleBack() {
        if (getActivity() != null) {
            OverlayNavUtil.dismiss(requireActivity());
        }
    }
}
