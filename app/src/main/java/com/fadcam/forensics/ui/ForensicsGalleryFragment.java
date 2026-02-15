package com.fadcam.forensics.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.animation.ObjectAnimator;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Rect;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fadcam.R;
import com.fadcam.forensics.data.local.ForensicsDatabase;
import com.fadcam.forensics.data.local.model.ForensicsSnapshotWithMedia;
import com.fadcam.ui.ImageViewerActivity;
import com.fadcam.ui.InputActionBottomSheetFragment;
import com.fadcam.ui.OverlayNavUtil;
import com.fadcam.ui.picker.OptionItem;
import com.fadcam.ui.picker.PickerBottomSheetFragment;
import com.fadcam.utils.TrashManager;
import com.google.android.material.chip.Chip;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ForensicsGalleryFragment extends Fragment {
    private static final String ARG_EMBEDDED = "arg_embedded";
    private static final String SORT_PICKER_RESULT = "forensics_gallery_sort_picker";
    private static final String GRID_PICKER_RESULT = "forensics_gallery_grid_picker";
    private static final String BATCH_PICKER_RESULT = "forensics_gallery_batch_picker";
    private static final String INFO_PICKER_RESULT = "forensics_gallery_info_picker";
    private static final String DELETE_SHEET_TAG = "forensics_delete_sheet";

    public interface HostSelectionUi {
        void onSelectionStateChanged(boolean active, int selectedCount, boolean allSelected);
        void onSummaryChanged(int totalCount, long totalBytes);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ForensicsGalleryAdapter adapter = new ForensicsGalleryAdapter();

    private RecyclerView recycler;
    private SwipeRefreshLayout swipeRefresh;
    private TextView empty;
    private TextView statCount;
    private TextView statSize;
    private ExtendedFloatingActionButton batchFab;
    private FloatingActionButton scrollFab;
    private HorizontalScrollView chipScroll;
    private Chip chipAll;
    private Chip chipPerson;
    private Chip chipVehicle;
    private Chip chipPet;
    private Chip chipObject;

    private int currentGridSpan = 2;
    private String selectedSort = "newest";
    private String selectedType = null;
    private boolean embeddedMode;
    private boolean allSelectedState;
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    @Nullable
    private Runnable batchFabShrinkRunnable;
    private boolean isScrollingDown;
    @Nullable
    private ObjectAnimator scrollFabRotationAnimator;
    @Nullable
    private HostSelectionUi hostSelectionUi;

    private final List<ForensicsSnapshotWithMedia> allRows = new ArrayList<>();

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
        swipeRefresh = view.findViewById(R.id.swipe_gallery);
        empty = view.findViewById(R.id.text_gallery_empty);
        statCount = view.findViewById(R.id.text_gallery_stat_count);
        statSize = view.findViewById(R.id.text_gallery_stat_size);
        batchFab = view.findViewById(R.id.fab_gallery_batch);
        scrollFab = view.findViewById(R.id.fab_gallery_scroll_navigation);
        chipScroll = view.findViewById(R.id.chip_scroll_gallery);
        chipAll = view.findViewById(R.id.chip_gallery_all);
        chipPerson = view.findViewById(R.id.chip_gallery_person);
        chipVehicle = view.findViewById(R.id.chip_gallery_vehicle);
        chipPet = view.findViewById(R.id.chip_gallery_pet);
        chipObject = view.findViewById(R.id.chip_gallery_object);

        View header = view.findViewById(R.id.header_bar);
        View back = view.findViewById(R.id.back_button);
        if (embeddedMode) {
            if (header != null) header.setVisibility(View.GONE);
            if (back != null) back.setVisibility(View.GONE);
            View statRow = view.findViewById(R.id.row_gallery_stats_icons);
            if (statRow != null) statRow.setVisibility(View.GONE);
        } else if (back != null) {
            back.setOnClickListener(v -> OverlayNavUtil.popLevel(requireActivity()));
        }

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

        GridLayoutManager grid = new GridLayoutManager(requireContext(), currentGridSpan);
        grid.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter.getItemViewType(position) == 0 ? currentGridSpan : 1;
            }
        });
        recycler.setLayoutManager(grid);
        recycler.setHasFixedSize(true);
        recycler.setItemViewCacheSize(40);
        recycler.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View child, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                int position = parent.getChildAdapterPosition(child);
                if (position == RecyclerView.NO_POSITION) return;
                if (adapter.getItemViewType(position) == 0) {
                    outRect.set(0, dpToPxInt(6), 0, dpToPxInt(4));
                    return;
                }
                int hGap = dpToPxInt(8);
                int vGap = dpToPxInt(10);
                outRect.left = hGap / 2;
                outRect.right = hGap / 2;
                outRect.bottom = vGap;
                outRect.top = dpToPxInt(2);
            }
        });
        recycler.setAdapter(adapter);
        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (dy > 0) {
                    isScrollingDown = true;
                } else if (dy < 0) {
                    isScrollingDown = false;
                }
                updateNavigationFab();
                showNavigationFab();
            }
        });

        adapter.setListener(new ForensicsGalleryAdapter.Listener() {
            @Override
            public void onSelectionChanged(int selectedCount) {
                allSelectedState = adapter.isAllSelected();
                renderSelectionState(selectedCount);
            }

            @Override
            public void onOpenViewer(@NonNull ForensicsSnapshotWithMedia row, boolean mediaMissing) {
                openViewer(row, mediaMissing);
            }

            @Override
            public void onMonthSelectionRequested(@NonNull String monthKey) {
                adapter.toggleMonthSelection(monthKey);
            }
        });

        if (chipScroll != null) {
            chipScroll.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                        || event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                }
                return false;
            });
        }
        chipAll.setOnClickListener(v -> { selectedType = null; applyFiltersAndRender(); });
        chipPerson.setOnClickListener(v -> { selectedType = "PERSON"; applyFiltersAndRender(); });
        chipVehicle.setOnClickListener(v -> { selectedType = "VEHICLE"; applyFiltersAndRender(); });
        chipPet.setOnClickListener(v -> { selectedType = "PET"; applyFiltersAndRender(); });
        chipObject.setOnClickListener(v -> { selectedType = "OBJECT"; applyFiltersAndRender(); });

        ImageView sortButton = view.findViewById(R.id.button_gallery_sort);
        if (sortButton != null) sortButton.setOnClickListener(this::showSortMenu);
        ImageView gridButton = view.findViewById(R.id.button_gallery_grid);
        if (gridButton != null) gridButton.setOnClickListener(this::showGridMenu);

        if (swipeRefresh != null) {
            swipeRefresh.setOnRefreshListener(this::loadData);
        }

        if (batchFab != null) {
            batchFab.setOnClickListener(v -> showBatchActions());
        }
        if (scrollFab != null) {
            scrollFab.setOnClickListener(v -> handleNavigationFabClick());
        }

        renderSelectionState(0);
        loadData();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadData();
    }

    public void clearSelectionFromHost() {
        adapter.clearSelection();
    }

    public void toggleSelectAllFromHost() {
        if (!adapter.isSelectionMode()) return;
        if (adapter.isAllSelected()) {
            adapter.clearSelection();
        } else {
            adapter.selectAll();
        }
    }

    public void showInfoBottomPicker() {
        if (!isAdded() || getParentFragmentManager().isStateSaved()) {
            return;
        }
        ForensicsGalleryInfoBottomSheetFragment.newInstance()
                .show(getParentFragmentManager(), INFO_PICKER_RESULT + "_sheet");
    }

    private void showBatchActions() {
        if (adapter.getSelectedCount() <= 0) {
            Toast.makeText(requireContext(), R.string.records_batch_select_items_first, Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<OptionItem> items = new ArrayList<>();
        boolean allSel = adapter.isAllSelected();
        items.add(new OptionItem("toggle_all",
                getString(allSel ? R.string.records_batch_deselect_all : R.string.records_batch_select_all),
                null, null, R.drawable.ic_check));
        items.add(new OptionItem("delete", getString(R.string.records_batch_delete), null, null, R.drawable.ic_delete));
        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstanceGradient(
                getString(R.string.records_batch_actions_title),
                items,
                "delete",
                BATCH_PICKER_RESULT,
                getString(R.string.records_batch_delete_desc),
                true
        );
        getParentFragmentManager().setFragmentResultListener(BATCH_PICKER_RESULT, getViewLifecycleOwner(), (key, bundle) -> {
            String id = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID, "");
            if ("toggle_all".equals(id)) {
                if (adapter.isAllSelected()) adapter.clearSelection(); else adapter.selectAll();
            } else if ("delete".equals(id)) {
                deleteSelectedEvidence();
            }
        });
        sheet.show(getParentFragmentManager(), BATCH_PICKER_RESULT + "_sheet");
    }

    private void renderSelectionState(int selectedCount) {
        boolean active = selectedCount > 0;
        if (batchFab != null) {
            if (active) {
                if (batchFab.getVisibility() != View.VISIBLE) {
                    batchFab.setVisibility(View.VISIBLE);
                    batchFab.setAlpha(0f);
                    batchFab.setScaleX(0.88f);
                    batchFab.setScaleY(0.88f);
                    batchFab.setTranslationY(dpToPx(16));
                    batchFab.extend();
                    batchFab.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .translationY(0f)
                            .setDuration(220)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                    if (batchFabShrinkRunnable != null) {
                        uiHandler.removeCallbacks(batchFabShrinkRunnable);
                    }
                    batchFabShrinkRunnable = () -> {
                        if (batchFab != null && batchFab.getVisibility() == View.VISIBLE) {
                            batchFab.shrink();
                        }
                    };
                    uiHandler.postDelayed(batchFabShrinkRunnable, 1300L);
                }
            } else if (batchFab.getVisibility() == View.VISIBLE) {
                if (batchFabShrinkRunnable != null) {
                    uiHandler.removeCallbacks(batchFabShrinkRunnable);
                }
                batchFab.extend();
                batchFab.animate()
                        .alpha(0f)
                        .scaleX(0.9f)
                        .scaleY(0.9f)
                        .translationY(dpToPx(14))
                        .setDuration(170)
                        .setInterpolator(new AccelerateInterpolator())
                        .withEndAction(() -> {
                            batchFab.setVisibility(View.GONE);
                            batchFab.setAlpha(1f);
                            batchFab.setScaleX(1f);
                            batchFab.setScaleY(1f);
                            batchFab.setTranslationY(0f);
                        })
                        .start();
            }
        }
        if (hostSelectionUi != null) {
            allSelectedState = adapter.isAllSelected();
            hostSelectionUi.onSelectionStateChanged(active, selectedCount, allSelectedState);
        }
    }

    private void loadData() {
        if (!isAdded()) return;
        final android.content.Context context = requireContext().getApplicationContext();
        executor.execute(() -> {
            List<ForensicsSnapshotWithMedia> rows = ForensicsDatabase.getInstance(context)
                    .aiEventSnapshotDao()
                    .getGallerySnapshots(null, 0f, null, 0L, 3000);
            reconcileMediaState(rows);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                allRows.clear();
                if (rows != null) allRows.addAll(rows);
                applyFiltersAndRender();
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            });
        });
    }

    private void applyFiltersAndRender() {
        List<ForensicsSnapshotWithMedia> filtered = new ArrayList<>();
        for (ForensicsSnapshotWithMedia row : allRows) {
            if (selectedType == null || selectedType.equalsIgnoreCase(row.eventType)) {
                filtered.add(row);
            }
        }
        sortRows(filtered);
        adapter.submit(filtered);
        empty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        renderChipCounts(allRows);
        renderStats(filtered);
        updateNavigationFab();
    }

    private void renderChipCounts(@NonNull List<ForensicsSnapshotWithMedia> rows) {
        int person = 0, vehicle = 0, pet = 0, object = 0;
        for (ForensicsSnapshotWithMedia row : rows) {
            String t = row.eventType == null ? "" : row.eventType.toUpperCase(Locale.US);
            switch (t) {
                case "PERSON": person++; break;
                case "VEHICLE": vehicle++; break;
                case "PET": pet++; break;
                default: object++; break;
            }
        }
        if (chipAll != null) chipAll.setText(getString(R.string.forensics_filter_with_count, getString(R.string.forensics_filter_all), rows.size()));
        if (chipPerson != null) chipPerson.setText(getString(R.string.forensics_filter_with_count, getString(R.string.forensics_filter_person), person));
        if (chipVehicle != null) chipVehicle.setText(getString(R.string.forensics_filter_with_count, getString(R.string.forensics_filter_vehicle), vehicle));
        if (chipPet != null) chipPet.setText(getString(R.string.forensics_filter_with_count, getString(R.string.forensics_filter_pet), pet));
        if (chipObject != null) chipObject.setText(getString(R.string.forensics_filter_with_count, getString(R.string.forensics_filter_object), object));
    }

    private void renderStats(@NonNull List<ForensicsSnapshotWithMedia> rows) {
        int count = rows.size();
        long sizeBytes = adapter.getTotalImageBytes();
        if (statCount != null) statCount.setText(String.valueOf(count));
        if (statSize != null) statSize.setText(Formatter.formatShortFileSize(requireContext(), Math.max(0L, sizeBytes)));
        if (hostSelectionUi != null) {
            hostSelectionUi.onSummaryChanged(count, sizeBytes);
        }
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

    private void deleteSelectedEvidence() {
        List<ForensicsSnapshotWithMedia> selectedRows = adapter.getSelectedRows();
        if (selectedRows.isEmpty()) {
            Toast.makeText(requireContext(), R.string.trash_no_items_selected_toast, Toast.LENGTH_SHORT).show();
            return;
        }
        InputActionBottomSheetFragment sheet = InputActionBottomSheetFragment.newConfirm(
                getString(R.string.forensics_delete_selected_title, selectedRows.size()),
                getString(R.string.dialog_del_confirm),
                getString(R.string.universal_cancel),
                R.drawable.ic_delete
        ).withHelperText(getString(R.string.forensics_delete_selected_helper));
        sheet.setCallbacks(new InputActionBottomSheetFragment.Callbacks() {
            @Override public void onImportConfirmed(org.json.JSONObject json) {}
            @Override public void onResetConfirmed() { executeDeleteSelectedEvidence(); }
        });
        sheet.show(getParentFragmentManager(), DELETE_SHEET_TAG);
    }

    private void executeDeleteSelectedEvidence() {
        List<ForensicsSnapshotWithMedia> selectedRows = adapter.getSelectedRows();
        if (selectedRows.isEmpty() || !isAdded()) return;
        final android.content.Context context = requireContext().getApplicationContext();
        executor.execute(() -> {
            int movedCount = 0;
            int deletedRows = 0;
            ForensicsDatabase db = ForensicsDatabase.getInstance(context);
            for (ForensicsSnapshotWithMedia row : selectedRows) {
                if (row.imageUri == null || row.imageUri.isEmpty()) continue;
                String displayName = "evidence_" + row.timelineMs + ".jpg";
                try {
                    Uri uri = Uri.parse(row.imageUri);
                    String candidate = uri.getLastPathSegment();
                    if (candidate != null && !candidate.isEmpty()) displayName = candidate;
                } catch (Exception ignored) {}
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
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                adapter.clearSelection();
                loadData();
                Toast.makeText(requireContext(), getString(R.string.forensics_gallery_deleted_toast, finalMoved, finalDeleted), Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void showSortMenu(View anchor) {
        ArrayList<OptionItem> items = new ArrayList<>();
        items.add(new OptionItem("sort_newest", getString(R.string.forensics_sort_newest), null, null, R.drawable.ic_arrow_down));
        items.add(new OptionItem("sort_oldest", getString(R.string.sort_oldest_first), null, null, R.drawable.ic_arrow_up));
        items.add(new OptionItem("sort_confidence", getString(R.string.forensics_sort_confidence), null, null, R.drawable.ic_focus_target));
        String selectedId = "sort_newest";
        if ("oldest".equals(selectedSort)) selectedId = "sort_oldest";
        if ("confidence".equals(selectedSort)) selectedId = "sort_confidence";
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
                case "sort_oldest": selectedSort = "oldest"; break;
                case "sort_confidence": selectedSort = "confidence"; break;
                default: selectedSort = "newest"; break;
            }
            applyFiltersAndRender();
        });
        sheet.show(getParentFragmentManager(), SORT_PICKER_RESULT + "_sheet");
    }

    private void showGridMenu(View anchor) {
        ArrayList<OptionItem> items = new ArrayList<>();
        items.add(new OptionItem("view_2", getString(R.string.forensics_grid_2), null, null, R.drawable.ic_grid));
        items.add(new OptionItem("view_3", getString(R.string.forensics_grid_3), null, null, R.drawable.ic_grid));
        items.add(new OptionItem("view_4", getString(R.string.forensics_grid_4), null, null, R.drawable.ic_grid));
        String selectedId = currentGridSpan == 4 ? "view_4" : (currentGridSpan == 3 ? "view_3" : "view_2");
        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstanceGradient(
                getString(R.string.forensics_grid_option),
                items,
                selectedId,
                GRID_PICKER_RESULT,
                getString(R.string.forensics_grid_helper),
                true
        );
        getParentFragmentManager().setFragmentResultListener(GRID_PICKER_RESULT, getViewLifecycleOwner(), (key, bundle) -> {
            String picked = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID, "");
            currentGridSpan = "view_4".equals(picked) ? 4 : ("view_3".equals(picked) ? 3 : 2);
            applyGridSpan();
        });
        sheet.show(getParentFragmentManager(), GRID_PICKER_RESULT + "_sheet");
    }

    private void applyGridSpan() {
        RecyclerView.LayoutManager lm = recycler.getLayoutManager();
        if (!(lm instanceof GridLayoutManager)) {
            return;
        }
        GridLayoutManager grid = (GridLayoutManager) lm;
        grid.setSpanCount(currentGridSpan);
        grid.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) {
                return adapter.getItemViewType(position) == 0 ? currentGridSpan : 1;
            }
        });
        grid.requestLayout();
    }

    private void sortRows(@NonNull List<ForensicsSnapshotWithMedia> rows) {
        if (rows.size() < 2) return;
        switch (selectedSort) {
            case "confidence":
                Collections.sort(rows, (a, b) -> Float.compare(b.confidence, a.confidence));
                break;
            case "oldest":
                Collections.sort(rows, Comparator.comparingLong(a -> a.capturedEpochMs));
                break;
            default:
                Collections.sort(rows, (a, b) -> Long.compare(b.capturedEpochMs, a.capturedEpochMs));
                break;
        }
    }

    private void reconcileMediaState(@Nullable List<ForensicsSnapshotWithMedia> rows) {
        if (rows == null || rows.isEmpty() || !isAdded()) return;
        ForensicsDatabase db = ForensicsDatabase.getInstance(requireContext());
        for (ForensicsSnapshotWithMedia row : rows) {
            boolean missing = isMediaMissing(row.mediaUri);
            row.mediaMissing = missing;
            if (row.mediaUid != null && !row.mediaUid.isEmpty()) {
                try { db.aiEventDao().updateMediaMissingByMediaUid(row.mediaUid, missing); } catch (Exception ignored) {}
            }
        }
    }

    private boolean isMediaMissing(@Nullable String mediaUri) {
        if (mediaUri == null || mediaUri.isEmpty()) return true;
        try {
            Uri uri = Uri.parse(mediaUri);
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                String path = uri.getPath();
                return path == null || !(new java.io.File(path).exists());
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private void applyChipIcon(@Nullable Chip chip, int drawableRes) {
        if (chip == null) return;
        chip.setChipIconResource(drawableRes);
        chip.setChipIconVisible(true);
        chip.setCheckedIconVisible(false);
        chip.setChipIconSize(dpToPx(16));
    }

    private void styleFilterChip(@Nullable Chip chip) {
        if (chip == null || getContext() == null) return;
        int checkedBg = resolveThemeColor(R.attr.colorButton);
        int uncheckedBg = resolveThemeColor(R.attr.colorDialog);
        int stroke = resolveThemeColor(R.attr.colorToggle);
        int checkedText = isDarkColor(checkedBg) ? Color.WHITE : Color.BLACK;
        int uncheckedText = isDarkColor(uncheckedBg) ? Color.WHITE : Color.BLACK;
        int[][] states = new int[][]{ new int[]{android.R.attr.state_checked}, new int[]{} };
        chip.setChipBackgroundColor(new ColorStateList(states, new int[]{checkedBg, uncheckedBg}));
        chip.setTextColor(new ColorStateList(states, new int[]{checkedText, uncheckedText}));
        chip.setChipIconTint(new ColorStateList(states, new int[]{checkedText, uncheckedText}));
        chip.setChipStrokeColor(ColorStateList.valueOf(stroke));
        chip.setChipStrokeWidth(dpToPx(1));
        chip.setEnsureMinTouchTargetSize(false);
    }

    private int resolveThemeColor(int attr) {
        TypedValue tv = new TypedValue();
        requireContext().getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    private boolean isDarkColor(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255d;
        return luminance < 0.55d;
    }

    private float dpToPx(int dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    private int dpToPxInt(int dp) {
        return Math.round(dpToPx(dp));
    }

    private void updateNavigationFab() {
        if (scrollFab == null || recycler == null) return;
        RecyclerView.LayoutManager lm = recycler.getLayoutManager();
        if (lm == null) return;
        int total = lm.getItemCount();
        if (total <= 8) {
            scrollFab.setVisibility(View.GONE);
            return;
        }
        scrollFab.setVisibility(View.VISIBLE);
        float targetRotation = isScrollingDown ? 90f : -90f;
        String cd = getString(isScrollingDown ? R.string.scroll_to_bottom : R.string.scroll_to_top);
        if (scrollFabRotationAnimator != null && scrollFabRotationAnimator.isRunning()) {
            scrollFabRotationAnimator.cancel();
        }
        float current = scrollFab.getRotation();
        if (Math.abs(current - targetRotation) > 1f) {
            scrollFabRotationAnimator = ObjectAnimator.ofFloat(scrollFab, "rotation", current, targetRotation);
            scrollFabRotationAnimator.setDuration(280);
            scrollFabRotationAnimator.setInterpolator(new android.view.animation.OvershootInterpolator(0.3f));
            scrollFabRotationAnimator.start();
        } else {
            scrollFab.setRotation(targetRotation);
        }
        scrollFab.setContentDescription(cd);
    }

    private void handleNavigationFabClick() {
        if (recycler == null) return;
        RecyclerView.LayoutManager lm = recycler.getLayoutManager();
        if (lm == null) return;
        int total = lm.getItemCount();
        if (total <= 0) return;
        if (isScrollingDown) {
            recycler.smoothScrollToPosition(total - 1);
        } else {
            recycler.smoothScrollToPosition(0);
        }
        uiHandler.postDelayed(this::updateNavigationFab, 500L);
    }

    private void showNavigationFab() {
        if (scrollFab == null) return;
        if (scrollFab.getVisibility() != View.VISIBLE) {
            scrollFab.setAlpha(0f);
            scrollFab.setVisibility(View.VISIBLE);
            scrollFab.animate().alpha(1f).setDuration(200).start();
        } else {
            scrollFab.animate().alpha(1f).setDuration(120).start();
        }
    }
}
