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
import com.fadcam.ui.picker.OptionItem;
import com.fadcam.ui.picker.PickerBottomSheetFragment;

import java.util.ArrayList;

public class ForensicIntelligenceFragment extends Fragment {

    private static final String RESULT_KEY = "lab_sidebar_result";
    private static final String RESULT_KEY_CLIP_STYLE = "lab_clip_style_picker";
    private static final String RESULT_KEY_TAPE_STYLE = "lab_tape_style_picker";
    private static final String TAG_GALLERY = "lab_gallery";
    private android.widget.TextView titleView;
    private android.view.View closeSelectionButton;
    private android.view.View menuButton;
    private android.view.View selectAllContainer;
    private android.widget.ImageView selectAllBg;
    private android.widget.ImageView selectAllCheck;
    private android.widget.TextView statCountView;
    private android.widget.TextView statSizeView;

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
        statCountView = view.findViewById(R.id.text_lab_stat_count);
        statSizeView = view.findViewById(R.id.text_lab_stat_size);

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
                case "open_info":
                    ForensicsGalleryFragment gallery = findGallery();
                    if (gallery != null) {
                        gallery.showInfoBottomPicker();
                    }
                    break;
                case "open_insights":
                    OverlayNavUtil.show(requireActivity(), new ForensicsInsightsFragment(), "forensics_insights");
                    break;
                case "open_clip_style":
                    showClipStylePicker();
                    break;
                case "open_tape_style":
                    showTapeStylePicker();
                    break;
                case "hide_thumbnails_toggled":
                    ForensicsGalleryFragment galleryHide = findGallery();
                    if (galleryHide != null) {
                        galleryHide.refreshHideThumbnails(bundle.getBoolean("hide_thumbnails", false));
                    }
                    break;
                default:
                    break;
            }
        });
    }

    private void showGallery() {
        ForensicsGalleryFragment gallery = ForensicsGalleryFragment.newEmbeddedInstance();
        gallery.setHostSelectionUi(new ForensicsGalleryFragment.HostSelectionUi() {
            @Override
            public void onSelectionStateChanged(boolean active, int selectedCount, boolean allSelected) {
                requireActivity().runOnUiThread(() -> updateHeaderForSelection(active, selectedCount, allSelected));
            }

            @Override
            public void onSummaryChanged(int totalCount, long totalBytes) {
                requireActivity().runOnUiThread(() -> {
                    if (statCountView != null) statCountView.setText(String.valueOf(totalCount));
                    if (statSizeView != null) {
                        statSizeView.setText(android.text.format.Formatter.formatShortFileSize(requireContext(), Math.max(0L, totalBytes)));
                    }
                });
            }
        });
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

    private void showClipStylePicker() {
        ForensicsGalleryFragment gallery = findGallery();
        if (gallery == null) return;
        ArrayList<OptionItem> items = new ArrayList<>();
        items.add(new OptionItem(ForensicsGalleryAdapter.CLIP_STYLE_BLACK, getString(R.string.forensics_clip_black), null, null, R.drawable.binder_clip_glossy_black_asset));
        items.add(new OptionItem(ForensicsGalleryAdapter.CLIP_STYLE_RED, getString(R.string.forensics_clip_red), null, null, R.drawable.binder_clip_red_asset));
        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstanceGradient(
                getString(R.string.forensics_clip_style_title),
                items,
                gallery.getClipStyle(),
                RESULT_KEY_CLIP_STYLE,
                getString(R.string.forensics_clip_style_helper),
                true
        );
        getChildFragmentManager().setFragmentResultListener(RESULT_KEY_CLIP_STYLE, getViewLifecycleOwner(), (key, bundle) -> {
            String selected = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID, ForensicsGalleryAdapter.CLIP_STYLE_BLACK);
            ForensicsGalleryFragment g = findGallery();
            if (g != null) g.applyClipStyleFromHost(selected);
        });
        sheet.show(getChildFragmentManager(), RESULT_KEY_CLIP_STYLE + "_sheet");
    }

    private void showTapeStylePicker() {
        ForensicsGalleryFragment gallery = findGallery();
        if (gallery == null) return;
        ArrayList<OptionItem> items = new ArrayList<>();
        items.add(new OptionItem(ForensicsGalleryAdapter.TAPE_STYLE_TORN, getString(R.string.forensics_tape_torn), null, null, R.drawable.forensics_index_tape));
        items.add(new OptionItem(ForensicsGalleryAdapter.TAPE_STYLE_CLASSIC, getString(R.string.forensics_tape_classic), null, null, R.drawable.forensics_index_tape_alt));
        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstanceGradient(
                getString(R.string.forensics_tape_style_title),
                items,
                gallery.getTapeStyle(),
                RESULT_KEY_TAPE_STYLE,
                getString(R.string.forensics_tape_style_helper),
                true
        );
        getChildFragmentManager().setFragmentResultListener(RESULT_KEY_TAPE_STYLE, getViewLifecycleOwner(), (key, bundle) -> {
            String selected = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID, ForensicsGalleryAdapter.TAPE_STYLE_TORN);
            ForensicsGalleryFragment g = findGallery();
            if (g != null) g.applyTapeStyleFromHost(selected);
        });
        sheet.show(getChildFragmentManager(), RESULT_KEY_TAPE_STYLE + "_sheet");
    }
}
