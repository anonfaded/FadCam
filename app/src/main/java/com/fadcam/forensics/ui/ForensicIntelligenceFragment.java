package com.fadcam.forensics.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.fadcam.R;

public class ForensicIntelligenceFragment extends Fragment {

    private static final String RESULT_KEY = "lab_sidebar_result";
    private static final String TAG_EVENTS = "lab_events";
    private static final String TAG_GALLERY = "lab_gallery";
    private static final String TAG_EXPORT = "lab_export";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_forensic_intelligence, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (savedInstanceState == null) {
            showEvents();
        }

        ImageButton menuButton = view.findViewById(R.id.button_lab_menu);
        if (menuButton != null) {
            menuButton.setOnClickListener(v -> {
                LabSidebarFragment sidebar = LabSidebarFragment.newInstance(RESULT_KEY);
                sidebar.show(getChildFragmentManager(), "LabSidebar");
            });
        }

        getChildFragmentManager().setFragmentResultListener(RESULT_KEY, getViewLifecycleOwner(), (key, bundle) -> {
            String action = bundle.getString("action", "");
            switch (action) {
                case "open_gallery":
                    showGalleryScreen();
                    break;
                case "open_export":
                    showExportScreen();
                    break;
                default:
                    break;
            }
        });
    }

    private void showEvents() {
        getChildFragmentManager().popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        getChildFragmentManager().beginTransaction()
                .replace(R.id.forensics_content_container, ForensicsEventsFragment.newEmbeddedInstance(), TAG_EVENTS)
                .commitAllowingStateLoss();
    }

    private void showGalleryScreen() {
        getChildFragmentManager().beginTransaction()
                .replace(R.id.forensics_content_container, new ForensicsGalleryFragment(), TAG_GALLERY)
                .addToBackStack(TAG_GALLERY)
                .commitAllowingStateLoss();
    }

    private void showExportScreen() {
        getChildFragmentManager().beginTransaction()
                .replace(R.id.forensics_content_container, new ForensicsExportCenterFragment(), TAG_EXPORT)
                .addToBackStack(TAG_EXPORT)
                .commitAllowingStateLoss();
    }
}
