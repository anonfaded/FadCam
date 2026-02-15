package com.fadcam.forensics.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.R;
import com.fadcam.forensics.data.local.ForensicsDatabase;
import com.fadcam.forensics.data.local.model.ForensicsSnapshotWithMedia;
import com.fadcam.model.TrashItem;
import com.fadcam.ui.OverlayNavUtil;
import com.fadcam.ui.InputActionBottomSheetFragment;
import com.fadcam.ui.picker.OptionItem;
import com.fadcam.ui.picker.PickerBottomSheetFragment;
import com.fadcam.ui.ImageViewerActivity;
import com.fadcam.utils.TrashManager;
import com.google.android.material.chip.Chip;

import android.content.res.ColorStateList;
import android.graphics.Color;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ForensicsGalleryFragment extends Fragment {
    private static final String ARG_EMBEDDED = "arg_embedded";
    private static final String SORT_PICKER_RESULT = "forensics_gallery_sort_picker";
    private static final String TAG = "ForensicsGallery";
    private static final String DELETE_SHEET_TAG = "forensics_delete_sheet";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ForensicsGalleryAdapter adapter = new ForensicsGalleryAdapter();

    private RecyclerView recycler;
    private TextView empty;
    private TextView sortValue;
    private TextView sourceValue;
    private TextView statsValue;
    private HorizontalScrollView chipScroll;
    private Chip chipAll;
    private Chip chipPerson;
    private Chip chipVehicle;
    private Chip chipPet;
    private Chip chipObject;
    private LinearLayout selectionBar;
    private TextView selectedCount;

    private String selectedSort = "newest";
    @Nullable
    private String selectedMediaState = null;
    private boolean embeddedMode;
    private int currentGridSpan = 2;
    @Nullable
    private HostSelectionUi hostSelectionUi;

    public interface HostSelectionUi {
        void onSelectionStateChanged(boolean active, int selectedCount, boolean allSelected);
    }

    public static ForensicsGalleryFragment newEmbeddedInstance() {
        ForensicsGalleryFragment fragment = new ForensicsGalleryFragment();
        Bundle args = new Bundle();
        args.putBoolean(ARG_EMBEDDED, true);
        fragment.setArguments(args);
        return fragment;
    }

    public ForensicsGalleryFragment() {
        super(R.layout.fragment_forensics_gallery);
    }

    public void setHostSelectionUi(@Nullable HostSelectionUi hostSelectionUi) {
        this.hostSelectionUi = hostSelectionUi;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        embeddedMode = getArguments() != null && getArguments().getBoolean(ARG_EMBEDDED, false);
        recycler = view.findViewById(R.id.recycler_gallery);
        empty = view.findViewById(R.id.text_gallery_empty);
        sortValue = view.findViewById(R.id.text_gallery_sort_value);
        sourceValue = view.findViewById(R.id.text_gallery_source_value);
        statsValue = view.findViewById(R.id.text_gallery_stats);
        chipScroll = view.findViewById(R.id.chip_scroll_gallery);
        chipAll = view.findViewById(R.id.chip_gallery_all);
        chipPerson = view.findViewById(R.id.chip_gallery_person);
        chipVehicle = view.findViewById(R.id.chip_gallery_vehicle);
        chipPet = view.findViewById(R.id.chip_gallery_pet);
        chipObject = view.findViewById(R.id.chip_gallery_object);
        selectionBar = view.findViewById(R.id.gallery_selection_bar);
        selectedCount = view.findViewById(R.id.text_gallery_selected_count);
        applyChipIcon(chipAll, R.drawable.ic_list);
        applyChipIcon(chipPerson, R.drawable.ic_broadcast_on_personal_24);
        applyChipIcon(chipVehicle, R.drawable.ic_forensics_vehicle);
        applyChipIcon(chipPet, R.drawable.ic_forensics_pet);
        applyChipIcon(chipObject, R.drawable.ic_grid);
        styleFilterChip(chipAll);
        styleFilterChip(chipPerson);
        styleFilterChip(chipVehicle);
        styleFilterChip(chipPet);
        styleFilterChip(chipObject);
        View header = view.findViewById(R.id.header_bar);
        if (header != null && embeddedMode) {
            header.setVisibility(View.GONE);
        }
        View back = view.findViewById(R.id.back_button);
        if (back != null) {
            if (embeddedMode) {
                back.setVisibility(View.GONE);
            } else {
                back.setOnClickListener(v -> OverlayNavUtil.popLevel(requireActivity()));
            }
        }

        recycler.setHasFixedSize(true);
        recycler.setItemViewCacheSize(30);
        recycler.setLayoutManager(new GridLayoutManager(requireContext(), currentGridSpan));
        recycler.setAdapter(adapter);
        recycler.setNestedScrollingEnabled(true);
        adapter.setListener(new ForensicsGalleryAdapter.Listener() {
            @Override
            public void onSelectionChanged(int selected) {
                renderSelectionUi(selected);
            }

            @Override
            public void onOpenViewer(@NonNull ForensicsSnapshotWithMedia row, boolean mediaMissing) {
                openViewer(row, mediaMissing);
            }
        });

        if (chipScroll != null) {
            chipScroll.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN || event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                }
                return false;
            });
        }

        chipAll.setOnClickListener(v -> selectTypeChip(chipAll));
        chipPerson.setOnClickListener(v -> selectTypeChip(chipPerson));
        chipVehicle.setOnClickListener(v -> selectTypeChip(chipVehicle));
        chipPet.setOnClickListener(v -> selectTypeChip(chipPet));
        chipObject.setOnClickListener(v -> selectTypeChip(chipObject));

        ImageView sortButton = view.findViewById(R.id.button_gallery_sort);
        if (sortButton != null) {
            sortButton.setOnClickListener(this::showSortMenu);
        }
        if (sortValue != null) {
            sortValue.setOnClickListener(this::showSortMenu);
        }
        if (sourceValue != null) {
            sourceValue.setOnClickListener(this::showSortMenu);
        }
        View selectAll = view.findViewById(R.id.button_gallery_select_all);
        if (selectAll != null) {
            selectAll.setOnClickListener(v -> adapter.selectAll());
        }
        View cancelSelection = view.findViewById(R.id.button_gallery_cancel_selection);
        if (cancelSelection != null) {
            cancelSelection.setOnClickListener(v -> adapter.clearSelection());
        }
        View deleteSelection = view.findViewById(R.id.button_gallery_delete);
        if (deleteSelection != null) {
            deleteSelection.setOnClickListener(v -> deleteSelectedEvidence());
        }
        refreshSortUi();
        renderSelectionUi(0);
        loadData();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadData();
    }

    private void selectTypeChip(@NonNull Chip selected) {
        chipAll.setChecked(selected == chipAll);
        chipPerson.setChecked(selected == chipPerson);
        chipVehicle.setChecked(selected == chipVehicle);
        chipPet.setChecked(selected == chipPet);
        chipObject.setChecked(selected == chipObject);
        loadData();
    }

    @Nullable
    private String resolveType() {
        if (chipPerson != null && chipPerson.isChecked()) return "PERSON";
        if (chipVehicle != null && chipVehicle.isChecked()) return "VEHICLE";
        if (chipPet != null && chipPet.isChecked()) return "PET";
        if (chipObject != null && chipObject.isChecked()) return "OBJECT";
        return null;
    }

    private void loadData() {
        if (!isAdded()) {
            return;
        }
        final android.content.Context context = requireContext().getApplicationContext();
        final String eventType = resolveType();
        final long since = 0L;
        executor.execute(() -> {
            List<ForensicsSnapshotWithMedia> rows = ForensicsDatabase.getInstance(context)
                    .aiEventSnapshotDao()
                    .getGallerySnapshots(eventType, 0f, selectedMediaState, since, 2000);
            reconcileMediaState(rows);
            sortRows(rows);
            Log.i(TAG, "loadData result: rows=" + (rows == null ? 0 : rows.size())
                    + ", eventType=" + eventType + ", mediaState=" + selectedMediaState);
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                adapter.submit(rows);
                empty.setVisibility(rows == null || rows.isEmpty() ? View.VISIBLE : View.GONE);
                renderStats(rows);
                refreshSortUi();
            });
        });
    }

    private void openViewer(@NonNull ForensicsSnapshotWithMedia row, boolean mediaMissing) {
        if (row.imageUri == null || row.imageUri.isEmpty()) {
            Toast.makeText(requireContext(), R.string.photo_capture_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(requireContext(), ImageViewerActivity.class);
        intent.setData(Uri.parse(row.imageUri));
        intent.putExtra(ImageViewerActivity.EXTRA_OPEN_AT_MS, Math.max(0L, row.timelineMs));
        intent.putExtra(ImageViewerActivity.EXTRA_EVENT_TYPE, ForensicsGalleryAdapter.safe(row.eventType));
        intent.putExtra(ImageViewerActivity.EXTRA_CLASS_NAME, ForensicsGalleryAdapter.safe(row.className));
        intent.putExtra(ImageViewerActivity.EXTRA_CONFIDENCE, row.confidence);
        intent.putExtra(ImageViewerActivity.EXTRA_CAPTURED_AT, row.capturedEpochMs);
        intent.putExtra(ImageViewerActivity.EXTRA_SOURCE_LABEL, ForensicsGalleryAdapter.deriveDisplayName(row));
        if (row.mediaUri != null && !row.mediaUri.isEmpty() && !mediaMissing) {
            intent.putExtra(ImageViewerActivity.EXTRA_SOURCE_VIDEO_URI, row.mediaUri);
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }

    private void renderSelectionUi(int selected) {
        boolean active = selected > 0;
        if (selectionBar == null || selectedCount == null) {
            notifyHostSelectionState(active, selected);
            return;
        }
        if (embeddedMode) {
            selectionBar.setVisibility(View.GONE);
        } else {
            if (selected <= 0) {
                selectionBar.setVisibility(View.GONE);
                selectedCount.setText("");
            } else {
                selectionBar.setVisibility(View.VISIBLE);
                selectedCount.setText(getString(R.string.forensics_gallery_selected_count, selected));
            }
        }
        notifyHostSelectionState(active, selected);
    }

    private void notifyHostSelectionState(boolean active, int selected) {
        if (hostSelectionUi != null) {
            boolean allSelected = adapter.getItemCount() > 0 && selected == adapter.getItemCount();
            hostSelectionUi.onSelectionStateChanged(active, selected, allSelected);
        }
    }

    private void deleteSelectedEvidence() {
        List<ForensicsSnapshotWithMedia> selectedRows = adapter.getSelectedRows();
        if (selectedRows.isEmpty()) {
            Toast.makeText(requireContext(), R.string.trash_no_items_selected_toast, Toast.LENGTH_SHORT).show();
            return;
        }
        showDeleteConfirmationSheet(selectedRows.size());
    }

    private void showDeleteConfirmationSheet(int count) {
        String title = getString(R.string.forensics_delete_selected_title, count);
        String action = getString(R.string.dialog_del_confirm);
        String cancel = getString(R.string.universal_cancel);
        String helper = getString(R.string.forensics_delete_selected_helper);
        InputActionBottomSheetFragment sheet = InputActionBottomSheetFragment.newConfirm(
                title,
                action,
                cancel,
                R.drawable.ic_delete
        ).withHelperText(helper);
        sheet.setCallbacks(new InputActionBottomSheetFragment.Callbacks() {
            @Override
            public void onImportConfirmed(org.json.JSONObject json) {
            }

            @Override
            public void onResetConfirmed() {
                executeDeleteSelectedEvidence();
            }
        });
        sheet.show(getParentFragmentManager(), DELETE_SHEET_TAG);
    }

    private void executeDeleteSelectedEvidence() {
        List<ForensicsSnapshotWithMedia> selectedRows = adapter.getSelectedRows();
        if (selectedRows.isEmpty()) {
            return;
        }
        if (!isAdded()) {
            return;
        }
        final android.content.Context context = requireContext().getApplicationContext();
        executor.execute(() -> {
            int movedCount = 0;
            int deletedRows = 0;
            ForensicsDatabase db = ForensicsDatabase.getInstance(context);
            for (ForensicsSnapshotWithMedia row : selectedRows) {
                if (row.imageUri == null || row.imageUri.isEmpty()) {
                    continue;
                }
                String displayName = "evidence_" + row.timelineMs + ".jpg";
                try {
                    Uri uri = Uri.parse(row.imageUri);
                    String candidate = uri.getLastPathSegment();
                    if (candidate != null && !candidate.isEmpty()) {
                        displayName = candidate;
                    }
                } catch (Exception ignored) {
                }
                boolean moved = TrashManager.moveToTrash(
                        context,
                        Uri.parse(row.imageUri),
                        displayName,
                        false,
                        com.fadcam.Constants.TRASH_SUBDIR_FORENSICS_EVIDENCE
                );
                if (moved) {
                    movedCount++;
                    if (row.snapshotUid != null && !row.snapshotUid.isEmpty()) {
                        db.aiEventSnapshotDao().deleteBySnapshotUid(row.snapshotUid);
                        deletedRows++;
                    }
                }
            }
            int finalMoved = movedCount;
            int finalDeleted = deletedRows;
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                adapter.clearSelection();
                loadData();
                Toast.makeText(
                        requireContext(),
                        getString(R.string.forensics_gallery_deleted_toast, finalMoved, finalDeleted),
                        Toast.LENGTH_SHORT
                ).show();
            });
        });
    }

    public void clearSelectionFromHost() {
        adapter.clearSelection();
    }

    public void toggleSelectAllFromHost() {
        if (!adapter.isSelectionMode()) {
            return;
        }
        if (adapter.getSelectedCount() >= adapter.getItemCount()) {
            adapter.clearSelection();
        } else {
            adapter.selectAll();
        }
    }

    private void renderStats(@Nullable List<ForensicsSnapshotWithMedia> rows) {
        if (statsValue == null) {
            return;
        }
        int count = rows == null ? 0 : rows.size();
        long totalBytes = 0L;
        if (rows != null) {
            for (ForensicsSnapshotWithMedia row : rows) {
                totalBytes += resolveSize(row.imageUri);
            }
        }
        String size = Formatter.formatFileSize(requireContext(), Math.max(0L, totalBytes));
        statsValue.setText(getString(R.string.forensics_gallery_stats, count, size));
    }

    private void applyChipIcon(@Nullable Chip chip, int drawableRes) {
        if (chip == null) {
            return;
        }
        chip.setChipIconResource(drawableRes);
        chip.setChipIconVisible(true);
        chip.setCheckedIconVisible(false);
        chip.setChipIconSize(dpToPx(16));
    }

    private void styleFilterChip(@Nullable Chip chip) {
        if (chip == null || getContext() == null) {
            return;
        }
        int checkedBg = resolveThemeColor(R.attr.colorButton);
        int uncheckedBg = resolveThemeColor(R.attr.colorDialog);
        int stroke = resolveThemeColor(R.attr.colorToggle);
        int checkedText = isDarkColor(checkedBg) ? Color.WHITE : Color.BLACK;
        int uncheckedText = isDarkColor(uncheckedBg) ? Color.WHITE : Color.BLACK;
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        chip.setChipBackgroundColor(new ColorStateList(states, new int[]{checkedBg, uncheckedBg}));
        chip.setTextColor(new ColorStateList(states, new int[]{checkedText, uncheckedText}));
        chip.setChipIconTint(new ColorStateList(states, new int[]{checkedText, uncheckedText}));
        chip.setChipStrokeColor(ColorStateList.valueOf(stroke));
        chip.setChipStrokeWidth(dpToPx(1));
        chip.setEnsureMinTouchTargetSize(false);
    }

    private int resolveThemeColor(int attr) {
        TypedValue typedValue = new TypedValue();
        requireContext().getTheme().resolveAttribute(attr, typedValue, true);
        return typedValue.data;
    }

    private boolean isDarkColor(int color) {
        double luminance = (0.299 * Color.red(color)
                + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color)) / 255d;
        return luminance < 0.55d;
    }

    private float dpToPx(int dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    private long resolveSize(@Nullable String uriString) {
        if (TextUtils.isEmpty(uriString)) {
            return 0L;
        }
        try {
            android.net.Uri uri = android.net.Uri.parse(uriString);
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                java.io.File file = new java.io.File(uri.getPath());
                return file.exists() ? file.length() : 0L;
            }
            try (android.content.res.AssetFileDescriptor afd = requireContext().getContentResolver().openAssetFileDescriptor(uri, "r")) {
                if (afd != null && afd.getLength() > 0L) {
                    return afd.getLength();
                }
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    private void reconcileMediaState(@Nullable List<ForensicsSnapshotWithMedia> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        ForensicsDatabase db = ForensicsDatabase.getInstance(requireContext());
        for (ForensicsSnapshotWithMedia row : rows) {
            boolean missing = isMediaMissing(row.mediaUri);
            row.mediaMissing = missing;
            if (row.mediaUid != null && !row.mediaUid.isEmpty()) {
                try {
                    db.aiEventDao().updateMediaMissingByMediaUid(row.mediaUid, missing);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private boolean isMediaMissing(@Nullable String mediaUri) {
        if (mediaUri == null || mediaUri.isEmpty()) {
            return true;
        }
        try {
            android.net.Uri uri = android.net.Uri.parse(mediaUri);
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                String path = uri.getPath();
                return path == null || !new java.io.File(path).exists();
            }
            try (android.content.res.AssetFileDescriptor afd =
                         requireContext().getContentResolver().openAssetFileDescriptor(uri, "r")) {
                return afd == null;
            }
        } catch (Exception e) {
            return true;
        }
    }

    private void sortRows(@Nullable List<ForensicsSnapshotWithMedia> rows) {
        if (rows == null || rows.size() < 2) {
            return;
        }
        switch (selectedSort) {
            case "confidence":
                Collections.sort(rows, (a, b) -> Float.compare(b.confidence, a.confidence));
                break;
            case "oldest":
                Collections.sort(rows, Comparator.comparingLong(a -> a.capturedEpochMs));
                break;
            case "event":
                Collections.sort(rows, (a, b) -> {
                    int cmp = safeString(a.eventType).compareTo(safeString(b.eventType));
                    if (cmp != 0) {
                        return cmp;
                    }
                    return Long.compare(b.capturedEpochMs, a.capturedEpochMs);
                });
                break;
            default:
                Collections.sort(rows, (a, b) -> Long.compare(b.capturedEpochMs, a.capturedEpochMs));
                break;
        }
    }

    private String safeString(@Nullable String value) {
        return value == null ? "" : value;
    }

    private void showSortMenu(View anchor) {
        ArrayList<OptionItem> items = new ArrayList<>();
        items.add(new OptionItem("sort_newest", getString(R.string.forensics_sort_newest), null, null, R.drawable.ic_sort_amount_down));
        items.add(new OptionItem("sort_oldest", getString(R.string.sort_oldest_first), null, null, R.drawable.ic_sort_amount_up));
        items.add(new OptionItem("sort_confidence", getString(R.string.forensics_sort_confidence), null, null, R.drawable.ic_focus_target));
        items.add(new OptionItem("sort_event", getString(R.string.forensics_gallery_sort_event_type), null, null, R.drawable.ic_grid));
        items.add(new OptionItem("src_all", getString(R.string.forensics_media_all), null, null, R.drawable.ic_list));
        items.add(new OptionItem("src_available", getString(R.string.forensics_media_available), null, null, R.drawable.ic_play));
        items.add(new OptionItem("src_missing", getString(R.string.forensics_media_missing_only), null, null, R.drawable.ic_error));
        items.add(new OptionItem("view_2", "View: 2-column", null, null, R.drawable.ic_grid));
        items.add(new OptionItem("view_3", "View: 3-column", null, null, R.drawable.ic_grid));
        items.add(new OptionItem("view_4", "View: 4-column", null, null, R.drawable.ic_grid));
        String selectedId = "sort_newest";
        if ("oldest".equals(selectedSort)) selectedId = "sort_oldest";
        if ("confidence".equals(selectedSort)) selectedId = "sort_confidence";
        if ("event".equals(selectedSort)) selectedId = "sort_event";
        if ("AVAILABLE".equals(selectedMediaState)) selectedId = "src_available";
        if ("MISSING".equals(selectedMediaState)) selectedId = "src_missing";
        if (currentGridSpan == 3) selectedId = "view_3";
        if (currentGridSpan == 4) selectedId = "view_4";
        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstanceGradient(
                getString(R.string.sort_by),
                items,
                selectedId,
                SORT_PICKER_RESULT,
                getString(R.string.forensics_events_subtitle),
                true
        );
        getParentFragmentManager().setFragmentResultListener(SORT_PICKER_RESULT, getViewLifecycleOwner(), (key, bundle) -> {
            String picked = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID, "");
            switch (picked) {
                case "sort_newest":
                    selectedSort = "newest";
                    break;
                case "sort_oldest":
                    selectedSort = "oldest";
                    break;
                case "sort_confidence":
                    selectedSort = "confidence";
                    break;
                case "sort_event":
                    selectedSort = "event";
                    break;
                case "src_all":
                    selectedMediaState = null;
                    break;
                case "src_available":
                    selectedMediaState = "AVAILABLE";
                    break;
                case "src_missing":
                    selectedMediaState = "MISSING";
                    break;
                case "view_2":
                    currentGridSpan = 2;
                    applyGridSpan();
                    break;
                case "view_3":
                    currentGridSpan = 3;
                    applyGridSpan();
                    break;
                case "view_4":
                    currentGridSpan = 4;
                    applyGridSpan();
                    break;
                default:
                    return;
            }
            loadData();
        });
        sheet.show(getParentFragmentManager(), SORT_PICKER_RESULT + "_sheet");
    }

    private void applyGridSpan() {
        if (recycler == null) {
            return;
        }
        RecyclerView.LayoutManager layoutManager = recycler.getLayoutManager();
        if (layoutManager instanceof GridLayoutManager) {
            ((GridLayoutManager) layoutManager).setSpanCount(currentGridSpan);
        } else {
            recycler.setLayoutManager(new GridLayoutManager(requireContext(), currentGridSpan));
        }
    }

    private void refreshSortUi() {
        if (sortValue != null) {
            String label;
            switch (selectedSort) {
                case "oldest":
                    label = getString(R.string.sort_oldest_first);
                    break;
                case "confidence":
                    label = getString(R.string.forensics_sort_confidence);
                    break;
                case "event":
                    label = getString(R.string.forensics_gallery_sort_event_type);
                    break;
                default:
                    label = getString(R.string.forensics_sort_newest);
                    break;
            }
            sortValue.setText(getString(R.string.forensics_sort_label, label));
        }
        if (sourceValue != null) {
            String label;
            if ("AVAILABLE".equals(selectedMediaState)) {
                label = getString(R.string.forensics_media_available);
            } else if ("MISSING".equals(selectedMediaState)) {
                label = getString(R.string.forensics_media_missing_only);
            } else {
                label = getString(R.string.forensics_media_all);
            }
            sourceValue.setText(getString(R.string.forensics_source_label, label));
        }
    }
}
