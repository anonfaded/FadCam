package com.fadcam.ui;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.fadcam.R;

/**
 * WatchSettingsFragment — Page 3 of the watch ViewPager.
 * Acts as a navigation hub: each row opens the full phone settings sub-screen
 * (VideoSettingsFragment, AudioSettingsFragment, SecuritySettingsFragment) via
 * OverlayNavUtil so the user has access to every setting from the watch.
 */
public class WatchSettingsFragment extends Fragment {

    private static final String TAG = "WatchSettingsFragment";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_watch_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Video settings row → VideoSettingsFragment overlay
        View videoRow = view.findViewById(R.id.row_watch_settings_video);
        if (videoRow != null) {
            videoRow.setOnClickListener(v -> openOverlay(new VideoSettingsFragment(), "watch_video_settings"));
        }

        // Audio settings row → AudioSettingsFragment overlay
        View audioRow = view.findViewById(R.id.row_watch_settings_audio);
        if (audioRow != null) {
            audioRow.setOnClickListener(v -> openOverlay(new AudioSettingsFragment(), "watch_audio_settings"));
        }

        // Security / Privacy row → SecuritySettingsFragment overlay
        View securityRow = view.findViewById(R.id.row_watch_settings_security);
        if (securityRow != null) {
            securityRow.setOnClickListener(v -> openOverlay(new SecuritySettingsFragment(), "watch_security_settings"));
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void openOverlay(Fragment fragment, String tag) {
        try {
            OverlayNavUtil.show(requireActivity(), fragment, tag);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open overlay: " + tag, e);
        }
    }
}
