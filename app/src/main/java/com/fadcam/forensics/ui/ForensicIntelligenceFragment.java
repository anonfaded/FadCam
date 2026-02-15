package com.fadcam.forensics.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.fadcam.R;
import com.fadcam.ui.OverlayNavUtil;

public class ForensicIntelligenceFragment extends Fragment {

    private static final String RESULT_KEY = "lab_sidebar_result";
    private static final String TAG_GALLERY = "lab_gallery";
    private android.widget.TextView titleView;
    private android.view.View closeSelectionButton;
    private android.view.View menuButton;
    private android.view.View selectAllContainer;
    private android.widget.ImageView selectAllBg;
    private android.widget.ImageView selectAllCheck;

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
            showGallery();
        }

        titleView = view.findViewById(R.id.text_lab_title);
        closeSelectionButton = view.findViewById(R.id.button_lab_close_selection);
        menuButton = view.findViewById(R.id.button_lab_menu);
        selectAllContainer = view.findViewById(R.id.button_lab_select_all_container);
        selectAllBg = view.findViewById(R.id.button_lab_select_all_bg);
        selectAllCheck = view.findViewById(R.id.button_lab_select_all_check);

        if (menuButton != null) {
            menuButton.setOnClickListener(v -> {
                LabSidebarFragment sidebar = LabSidebarFragment.newInstance(RESULT_KEY);
                sidebar.show(getChildFragmentManager(), "LabSidebar");
            });
        }
        if (closeSelectionButton != null) {
            closeSelectionButton.setOnClickListener(v -> {
                ForensicsGalleryFragment gallery = findGallery();
                if (gallery != null) {
                    gallery.clearSelectionFromHost();
                }
            });
        }
        if (selectAllContainer != null) {
            selectAllContainer.setOnClickListener(v -> {
                ForensicsGalleryFragment gallery = findGallery();
                if (gallery != null) {
                    gallery.toggleSelectAllFromHost();
                }
            });
        }

        getChildFragmentManager().setFragmentResultListener(RESULT_KEY, getViewLifecycleOwner(), (key, bundle) -> {
            String action = bundle.getString("action", "");
            switch (action) {
                case "open_export":
                    OverlayNavUtil.show(requireActivity(), new ForensicsExportCenterFragment(), "forensics_export");
                    break;
                default:
                    break;
            }
        });
    }

    private void showGallery() {
        ForensicsGalleryFragment gallery = ForensicsGalleryFragment.newEmbeddedInstance();
        gallery.setHostSelectionUi((active, selectedCount, allSelected) ->
                requireActivity().runOnUiThread(() -> updateHeaderForSelection(active, selectedCount, allSelected)));
        getChildFragmentManager().popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        getChildFragmentManager().beginTransaction()
                .replace(R.id.forensics_content_container, gallery, TAG_GALLERY)
                .commitAllowingStateLoss();
        updateHeaderForSelection(false, 0, false);
    }

    @Nullable
    private ForensicsGalleryFragment findGallery() {
        androidx.fragment.app.Fragment fragment = getChildFragmentManager().findFragmentByTag(TAG_GALLERY);
        if (fragment instanceof ForensicsGalleryFragment) {
            return (ForensicsGalleryFragment) fragment;
        }
        return null;
    }

    private void updateHeaderForSelection(boolean active, int selectedCount, boolean allSelected) {
        if (titleView != null) {
            if (active) {
                titleView.setText(selectedCount > 0
                        ? getResources().getQuantityString(R.plurals.forensics_gallery_selected_count_plural, selectedCount, selectedCount)
                        : getString(R.string.records_batch_select_items_first));
            } else {
                titleView.setText(R.string.forensic_intelligence_title);
            }
        }
        if (menuButton != null) {
            menuButton.setVisibility(active ? View.GONE : View.VISIBLE);
        }
        if (closeSelectionButton != null) {
            closeSelectionButton.setVisibility(active ? View.VISIBLE : View.GONE);
        }
        if (selectAllContainer != null) {
            selectAllContainer.setVisibility(active ? View.VISIBLE : View.GONE);
        }
        if (selectAllBg != null) {
            selectAllBg.setVisibility(active ? View.VISIBLE : View.INVISIBLE);
        }
        if (selectAllCheck != null) {
            selectAllCheck.setVisibility(active && allSelected ? View.VISIBLE : View.INVISIBLE);
            if (active && allSelected) {
                try {
                    android.graphics.drawable.Drawable d = selectAllCheck.getDrawable();
                    if (d instanceof androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat) {
                        ((androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat) d).start();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
