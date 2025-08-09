package com.fadcam.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.content.Intent;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.fadcam.R;

/**
 * SettingsHomeFragment
 * Lightweight entry point replacing the monolithic SettingsFragment.
 * Shows grouped navigation rows (placeholder toasts for now).
 */
public class SettingsHomeFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_settings_home, container, false);
        // -------------- Fix Start for this method(onCreateView)-----------
    // Removed redundant Location & Privacy group (location embedding moved into Video settings)
    int[] ids = new int[]{
        R.id.group_appearance,
        R.id.group_video,
        R.id.group_audio,
        R.id.group_storage,
                R.id.group_notifications,
        R.id.group_security,
        R.id.group_behavior,
        R.id.group_advanced,
        R.id.group_about,
        R.id.group_watermark,
        R.id.group_legacy_all
    };
    String[] labels = new String[]{
        "Appearance",
        "Video Recording",
        "Audio",
        "Storage",
                "Notifications",
        "Security",
        "Behavior",
        "Advanced",
        "About",
        "Watermark",
        "Legacy All Settings"
    };
        for (int i = 0; i < ids.length; i++) {
            if(ids[i] == R.id.group_legacy_all){
                LinearLayout row = root.findViewById(ids[i]);
                if(row != null){
                    row.setOnClickListener(v -> startActivity(new Intent(requireContext(), LegacySettingsActivity.class)));
                }
            } else if(ids[i] == R.id.group_appearance){
                LinearLayout row = root.findViewById(ids[i]);
                if(row != null){
                    row.setOnClickListener(v -> openSubFragment(new AppearanceSettingsFragment()));
                }
            } else if(ids[i] == R.id.group_video){
                LinearLayout row = root.findViewById(ids[i]);
                if(row != null){
                    row.setOnClickListener(v -> openSubFragment(new VideoSettingsFragment()));
                }
            } else if(ids[i] == R.id.group_audio){
                LinearLayout row = root.findViewById(ids[i]);
                if(row != null){
                    row.setOnClickListener(v -> openSubFragment(new AudioSettingsFragment()));
                }
            } else if(ids[i] == R.id.group_storage){
                LinearLayout row = root.findViewById(ids[i]);
                if(row != null){
                    row.setOnClickListener(v -> openSubFragment(new StorageSettingsFragment()));
                }
            } else if(ids[i] == R.id.group_security){
                LinearLayout row = root.findViewById(ids[i]);
                if(row != null){
                    row.setOnClickListener(v -> openSubFragment(new SecuritySettingsFragment()));
                }
            } else if(ids[i] == R.id.group_notifications){
                LinearLayout row = root.findViewById(ids[i]);
                if(row != null){
                    row.setOnClickListener(v -> openSubFragment(new NotificationSettingsFragment()));
                }
            } else if(ids[i] == R.id.group_watermark){
                LinearLayout row = root.findViewById(ids[i]);
                if(row != null){
                    row.setOnClickListener(v -> openSubFragment(new WatermarkSettingsFragment()));
                }
            } else if(ids[i] == R.id.group_behavior){
                LinearLayout row = root.findViewById(ids[i]);
                if(row != null){
                    row.setOnClickListener(v -> openSubFragment(new BehaviorSettingsFragment()));
                }
            } else if(ids[i] == R.id.group_advanced){
                LinearLayout row = root.findViewById(ids[i]);
                if(row != null){
                    row.setOnClickListener(v -> openSubFragment(new AdvancedSettingsFragment()));
                }
            } else if(ids[i] == R.id.group_about){
                LinearLayout row = root.findViewById(ids[i]);
                if(row != null){
                    row.setOnClickListener(v -> openSubFragment(new AboutFragment()));
                }
            } else {
                setupNav(root, ids[i], labels[i]);
            }
        }
        // -------------- Fix Ended for this method(onCreateView)-----------
        return root;
    }

    private void setupNav(View root, int id, String label){
        // -------------- Fix Start for this method(setupNav)-----------
        LinearLayout row = root.findViewById(id);
        if (row != null) {
            row.setOnClickListener(v -> Toast.makeText(requireContext(), label, Toast.LENGTH_SHORT).show());
        }
        // -------------- Fix Ended for this method(setupNav)-----------
    }

    private void openSubFragment(Fragment fragment){
    // -------------- Fix Start for this method(openSubFragment)-----------
    OverlayNavUtil.show(requireActivity(), fragment, fragment.getClass().getSimpleName());
    // -------------- Fix Ended for this method(openSubFragment)-----------
    }

    @Override
    public void onResume() {
        super.onResume();
        // Future: update dynamic subtitles (e.g., current theme, language) when preferences change.
    }
}
