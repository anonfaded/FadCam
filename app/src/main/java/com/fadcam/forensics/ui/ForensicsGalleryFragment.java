package com.fadcam.forensics.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
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
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.forensics.data.local.ForensicsDatabase;
import com.fadcam.forensics.data.local.model.ForensicsSnapshotWithMedia;
import com.fadcam.ui.ImageViewerActivity;
import com.fadcam.ui.InputActionBottomSheetFragment;
import com.fadcam.ui.OverlayNavUtil;
import com.fadcam.ui.picker.OptionItem;
import com.fadcam.ui.picker.PickerBottomSheetFragment;
import com.fadcam.service.FileOperationService;
import com.fadcam.utils.RealtimeMediaInvalidationCoordinator;
import com.fadcam.utils.TrashManager;
import com.google.android.material.chip.Chip;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
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
    private String clipStyle = ForensicsGalleryAdapter.CLIP_STYLE_BLACK;
    private String tapeStyle = ForensicsGalleryAdapter.TAPE_STYLE_TORN;
    private boolean embeddedMode;
    private boolean allSelectedState;
    /** True while background data load is in progress — prevents redundant onResume reloads. */
    private boolean isLoading;
    /** True after first successful data load — prevents redundant onResume reloads. */
    private boolean dataLoaded;
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    @Nullable
    private RealtimeMediaInvalidationCoordinator invalidationCoordinator;
    private boolean pendingRealtimeRefresh;
    @Nullable
    private Runnable batchFabShrinkRunnable;
    private boolean isScrollingDown;
    @Nullable
    private ObjectAnimator scrollFabRotationAnimator;
    @Nullable
    private HostSelectionUi hostSelectionUi;
    @Nullable
    private ActivityResultLauncher<Uri> customExportTreePickerLauncher;
    private List<Uri> pendingCustomExportUris = new ArrayList<>();
    @Nullable
    private com.fadcam.ui.GalleryFastScroller fastScroller;

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

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (customExportTreePickerLauncher == null) {
            customExportTreePickerLauncher = registerForActivityResult(
                    new ActivityResultContracts.OpenDocumentTree(),
                    uri -> {
                        if (uri == null) {
                            pendingCustomExportUris.clear();
                            return;
                        }
                        if (getContext() == null) return;
                        try {
                            final int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                            requireContext().getContentResolver().takePersistableUriPermission(uri, flags);
                        } catch (Exception ignored) {
                        }
                        exportSelectedEvidenceToCustomTree(uri);
                    }
            );
        }
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
        applyChipIcon(chipPerson, R.drawable.ic_person_24);
        applyChipIcon(chipVehicle, R.drawable.ic_vehicle_24);
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
        recycler.setItemAnimator(null);
        recycler.addItemDecoration(new ForensicsHangingStringDecoration(adapter));
        recycler.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View child, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                int position = parent.getChildAdapterPosition(child);
                if (position == RecyclerView.NO_POSITION) return;
                if (adapter.getItemViewType(position) == 0) {
                    outRect.set(0, dpToPxInt(6), 0, dpToPxInt(4));
                    return;
                }
                int hGap = dpToPxInt(7);
                int vGap = dpToPxInt(12);
                outRect.left = hGap / 2;
                outRect.right = hGap / 2;
                outRect.bottom = vGap;
                outRect.top = dpToPxInt(12);
            }
        });
        recycler.setAdapter(adapter);

        // ── Fast scroller setup ──
        fastScroller = view.findViewById(R.id.fast_scroller);
        if (fastScroller != null) {
            fastScroller.attachTo(recycler);
            fastScroller.setSectionIndexer(position -> adapter.getSectionText(position));
        }

        loadVisualPrefs();
        adapter.setGridSpan(currentGridSpan);
        adapter.setVisualStyles(clipStyle, tapeStyle);
        // Load Classified Mode preference
        adapter.setHideThumbnails(
                SharedPreferencesManager.getInstance(requireContext()).isLabHideThumbnailsEnabled()
        );
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
                RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
                int firstVisible = 0;
                int lastVisible = 0;
                int total = 0;
                if (layoutManager instanceof GridLayoutManager) {
                    firstVisible = ((GridLayoutManager) layoutManager).findFirstVisibleItemPosition();
                    lastVisible = ((GridLayoutManager) layoutManager).findLastVisibleItemPosition();
                    total = layoutManager.getItemCount();
                }
                if (firstVisible > 0 && lastVisible < total - 1) {
                    showNavigationFab();
                } else if (scrollFab != null) {
                    scrollFab.animate().alpha(0f).setDuration(180)
                            .withEndAction(() -> scrollFab.setVisibility(View.GONE)).start();
                }
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
            swipeRefresh.setOnRefreshListener(() -> loadData(true));
        }

        if (batchFab != null) {
            batchFab.setOnClickListener(v -> showBatchActions());
        }
        if (scrollFab != null) {
            scrollFab.setOnClickListener(v -> handleNavigationFabClick());
        }

        renderSelectionState(0);
        invalidationCoordinator = new RealtimeMediaInvalidationCoordinator(requireContext());
        invalidationCoordinator.addListener(reason -> requestRealtimeRefresh("coordinator:" + reason));
        invalidationCoordinator.start();

        loadData(true);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!dataLoaded && !isLoading) {
            loadData(true);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (fastScroller != null) {
            fastScroller.detach();
            fastScroller = null;
        }
        if (invalidationCoordinator != null) {
            invalidationCoordinator.stop();
            invalidationCoordinator = null;
        }
        executor.shutdownNow();
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

    public void applyClipStyleFromHost(@Nullable String style) {
        String resolved = style == null ? ForensicsGalleryAdapter.CLIP_STYLE_BLACK : style;
        if (clipStyle.equals(resolved)) return;
        clipStyle = resolved;
        persistVisualPrefs();
        adapter.setVisualStyles(clipStyle, tapeStyle);
    }

    public void applyTapeStyleFromHost(@Nullable String style) {
        String resolved = style == null ? ForensicsGalleryAdapter.TAPE_STYLE_TORN : style;
        if (tapeStyle.equals(resolved)) return;
        tapeStyle = resolved;
        persistVisualPrefs();
        adapter.setVisualStyles(clipStyle, tapeStyle);
    }

    /**
     * Called by host when Classified Mode toggle changes from the sidebar.
     * Updates the adapter to hide or show evidence thumbnails.
     *
     * @param hide true to enable Classified Mode (redacted thumbnails)
     */
    public void refreshHideThumbnails(boolean hide) {
        adapter.setHideThumbnails(hide);
    }

    @NonNull
    public String getClipStyle() {
        return clipStyle;
    }

    @NonNull
    public String getTapeStyle() {
        return tapeStyle;
    }

    private void showBatchActions() {
        if (adapter.getSelectedCount() <= 0) {
            Toast.makeText(requireContext(), R.string.records_batch_select_items_first, Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<OptionItem> items = new ArrayList<>();
        boolean allSel = adapter.isAllSelected();
        items.add(OptionItem.withLigature("toggle_all",
                getString(allSel ? R.string.records_batch_deselect_all : R.string.records_batch_select_all),
                "check"));
        items.add(new OptionItem(
                "save_gallery",
                getString(R.string.video_menu_save),
                getString(R.string.records_batch_save_desc),
                null,
                null,
                R.drawable.ic_arrow_right,
                null,
                null,
                "download"));
        items.add(OptionItem.withLigature("delete", getString(R.string.records_batch_delete), "delete"));
        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstanceGradient(
                getString(R.string.records_batch_actions_title),
                items,
                null,
                BATCH_PICKER_RESULT,
                getString(R.string.records_batch_delete_desc),
                true
        );
        Bundle args = sheet.getArguments();
        if (args != null) args.putBoolean(PickerBottomSheetFragment.ARG_HIDE_CHECK, true);
        getParentFragmentManager().setFragmentResultListener(BATCH_PICKER_RESULT, getViewLifecycleOwner(), (key, bundle) -> {
            String id = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID, "");
            if ("toggle_all".equals(id)) {
                if (adapter.isAllSelected()) adapter.clearSelection(); else adapter.selectAll();
            } else if ("save_gallery".equals(id)) {
                showBatchSaveOptionsSheet();
            } else if ("delete".equals(id)) {
                deleteSelectedEvidence();
            }
        });
        sheet.show(getParentFragmentManager(), BATCH_PICKER_RESULT + "_sheet");
    }

    private void showBatchSaveOptionsSheet() {
        if (!isAdded() || getContext() == null || getActivity() == null) return;
        if (!(getActivity() instanceof FragmentActivity)) return;
        List<ForensicsSnapshotWithMedia> selectedRows = adapter.getSelectedRows();
        if (selectedRows.isEmpty()) {
            Toast.makeText(requireContext(), R.string.records_batch_select_items_first, Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<OptionItem> items = new ArrayList<>();
        String destinationLabel = getGalleryDestinationLabel();
        items.add(new OptionItem(
                "save_copy",
                getString(R.string.video_menu_save_copy),
                getString(R.string.records_batch_save_copy_helper, destinationLabel),
                null, null, null, null, null, "content_copy"));
        items.add(new OptionItem(
                "save_move",
                getString(R.string.video_menu_save_move),
                getString(R.string.records_batch_save_move_helper, destinationLabel),
                null, null, null, null, null, "drive_file_move"));
        items.add(new OptionItem(
                "save_custom_location",
                getString(R.string.records_batch_save_custom_location),
                getString(R.string.records_batch_save_custom_location_desc),
                null, null, null, null, null, "folder_open"));
        FragmentActivity activity = (FragmentActivity) getActivity();
        String resultKey = "forensics_batch_save_options";
        activity.getSupportFragmentManager().setFragmentResultListener(resultKey, activity, (requestKey, bundle) -> {
            String id = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (id == null) return;
            if ("save_copy".equals(id)) {
                queueBatchSaveToGallery(false);
            } else if ("save_move".equals(id)) {
                queueBatchSaveToGallery(true);
            } else if ("save_custom_location".equals(id)) {
                if (customExportTreePickerLauncher != null) {
                    pendingCustomExportUris = selectedEvidenceUris(selectedRows);
                    customExportTreePickerLauncher.launch(null);
                }
            }
        });
        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstanceGradient(
                getString(R.string.video_menu_save_copy_or_move_title),
                items,
                "save_copy",
                resultKey,
                null,
                true
        );
        Bundle args = sheet.getArguments();
        if (args != null) args.putBoolean(PickerBottomSheetFragment.ARG_HIDE_CHECK, true);
        sheet.show(activity.getSupportFragmentManager(), "forensics_batch_save_sheet");
    }

    @NonNull
    private String getGalleryDestinationLabel() {
        return "Downloads/" + Constants.RECORDING_DIRECTORY;
    }

    private void queueBatchSaveToGallery(boolean moveFiles) {
        List<ForensicsSnapshotWithMedia> selectedRows = adapter.getSelectedRows();
        int queued = 0;
        for (ForensicsSnapshotWithMedia row : selectedRows) {
            if (row == null || row.imageUri == null || row.imageUri.isEmpty()) continue;
            Uri source = Uri.parse(row.imageUri);
            String fileName = deriveEvidenceName(row);
            if (moveFiles) {
                FileOperationService.startMoveToGallery(requireContext(), source, fileName, fileName);
            } else {
                FileOperationService.startCopyToGallery(requireContext(), source, fileName, fileName);
            }
            queued++;
        }
        Toast.makeText(requireContext(), getString(R.string.records_batch_save_queued, queued), Toast.LENGTH_SHORT).show();
    }

    @NonNull
    private List<Uri> selectedEvidenceUris(@NonNull List<ForensicsSnapshotWithMedia> selectedRows) {
        List<Uri> out = new ArrayList<>();
        for (ForensicsSnapshotWithMedia row : selectedRows) {
            if (row == null || row.imageUri == null || row.imageUri.isEmpty()) continue;
            try {
                out.add(Uri.parse(row.imageUri));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    @NonNull
    private String deriveEvidenceName(@NonNull ForensicsSnapshotWithMedia row) {
        String fallback = "evidence_" + Math.max(0L, row.timelineMs) + ".jpg";
        try {
            Uri uri = Uri.parse(row.imageUri);
            String name = uri.getLastPathSegment();
            return (name == null || name.isEmpty()) ? fallback : name;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void exportSelectedEvidenceToCustomTree(@NonNull Uri treeUri) {
        if (pendingCustomExportUris == null || pendingCustomExportUris.isEmpty()) {
            return;
        }
        if (getContext() == null) return;
        final List<Uri> urisToExport = new ArrayList<>(pendingCustomExportUris);
        pendingCustomExportUris.clear();
        Toast.makeText(requireContext(), getString(R.string.records_batch_custom_export_started, urisToExport.size()),
                Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            int success = 0;
            int failed = 0;
            DocumentFile targetDir = DocumentFile.fromTreeUri(requireContext(), treeUri);
            if (targetDir == null || !targetDir.isDirectory() || !targetDir.canWrite()) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> Toast.makeText(requireContext(),
                            getString(R.string.records_batch_custom_export_invalid_folder), Toast.LENGTH_LONG).show());
                }
                return;
            }
            for (Uri uri : urisToExport) {
                try {
                    DocumentFile out = createUniqueSafFile(targetDir, deriveNameFromUri(uri));
                    if (out == null) {
                        failed++;
                        continue;
                    }
                    try (java.io.InputStream in = requireContext().getContentResolver().openInputStream(uri);
                         java.io.OutputStream os = requireContext().getContentResolver().openOutputStream(out.getUri(), "w")) {
                        if (in == null || os == null) {
                            failed++;
                            continue;
                        }
                        byte[] buffer = new byte[16 * 1024];
                        int len;
                        while ((len = in.read(buffer)) > 0) {
                            os.write(buffer, 0, len);
                        }
                        os.flush();
                        success++;
                    }
                } catch (Exception e) {
                    failed++;
                }
            }
            final int successFinal = success;
            final int failedFinal = failed;
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> Toast.makeText(requireContext(),
                        getString(R.string.records_batch_custom_export_done, successFinal, failedFinal),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    @Nullable
    private DocumentFile createUniqueSafFile(@NonNull DocumentFile targetDir, @NonNull String originalName) {
        String base = originalName;
        String ext = "";
        int dot = originalName.lastIndexOf('.');
        if (dot > 0) {
            base = originalName.substring(0, dot);
            ext = originalName.substring(dot);
        }
        String candidate = originalName;
        int suffix = 1;
        while (targetDir.findFile(candidate) != null) {
            candidate = base + " (" + suffix + ")" + ext;
            suffix++;
        }
        String mime = "image/jpeg";
        if (candidate.toLowerCase(Locale.US).endsWith(".png")) mime = "image/png";
        return targetDir.createFile(mime, candidate);
    }

    @NonNull
    private String deriveNameFromUri(@NonNull Uri uri) {
        String path = uri.getLastPathSegment();
        return (path == null || path.trim().isEmpty()) ? "evidence.jpg" : path;
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

    private void requestRealtimeRefresh(@NonNull String reason) {
        if (!isAdded()) return;
        if (isLoading) {
            pendingRealtimeRefresh = true;
            return;
        }
        loadData(true);
    }

    private void loadData() {
        loadData(false);
    }

    private void loadData(boolean forceReload) {
        if (!isAdded()) return;
        if (isLoading) {
            if (forceReload) pendingRealtimeRefresh = true;
            return;
        }
        if (!forceReload && dataLoaded && !allRows.isEmpty()) {
            return;
        }
        isLoading = true;
        final android.content.Context context = requireContext().getApplicationContext();
        executor.execute(() -> {
            List<ForensicsSnapshotWithMedia> rows = ForensicsDatabase.getInstance(context)
                    .aiEventSnapshotDao()
                    .getGallerySnapshots(null, 0f, null, 0L, 3000);
            rows = dedupeRowsByStableId(rows);
            final List<ForensicsSnapshotWithMedia> finalRows = rows;
            reconcileMediaState(rows);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                allRows.clear();
                if (finalRows != null) allRows.addAll(finalRows);
                applyFiltersAndRender();
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                isLoading = false;
                dataLoaded = true;
                if (pendingRealtimeRefresh) {
                    pendingRealtimeRefresh = false;
                    loadData(true);
                }
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
        // Don't flash empty state while still loading initial data
        if (isLoading && filtered.isEmpty()) {
            empty.setVisibility(View.GONE);
        } else {
            empty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        }
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
        if (statCount != null) statCount.setText(String.valueOf(count));
        // Show cached value immediately, compute actual in background
        long cachedBytes = adapter.getTotalImageBytes();
        if (statSize != null) statSize.setText(Formatter.formatShortFileSize(requireContext(), cachedBytes));
        if (hostSelectionUi != null) {
            hostSelectionUi.onSummaryChanged(count, cachedBytes);
        }
        // Compute actual file sizes in background to avoid main thread I/O
        executor.execute(() -> {
            adapter.computeTotalImageBytesAsync(null);
            if (!isAdded()) return;
            long totalBytes = adapter.getTotalImageBytes();
            requireActivity().runOnUiThread(() -> {
                if (statSize != null) statSize.setText(Formatter.formatShortFileSize(requireContext(), totalBytes));
                if (hostSelectionUi != null) {
                    hostSelectionUi.onSummaryChanged(count, totalBytes);
                }
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
        items.add(new OptionItem("sort_newest", getString(R.string.forensics_sort_newest), null, null, R.drawable.ic_arrow_downward));
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
        items.add(new OptionItem("view_1", getString(R.string.forensics_grid_1), null, null, R.drawable.ic_grid));
        items.add(new OptionItem("view_2", getString(R.string.forensics_grid_2), null, null, R.drawable.ic_grid));
        items.add(new OptionItem("view_3", getString(R.string.forensics_grid_3), null, null, R.drawable.ic_grid));
        items.add(new OptionItem("view_4", getString(R.string.forensics_grid_4), null, null, R.drawable.ic_grid));
        items.add(new OptionItem("view_5", getString(R.string.forensics_grid_5), null, null, R.drawable.ic_grid));
        String selectedId = "view_" + currentGridSpan;
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
            switch (picked) {
                case "view_1": currentGridSpan = 1; break;
                case "view_3": currentGridSpan = 3; break;
                case "view_4": currentGridSpan = 4; break;
                case "view_5": currentGridSpan = 5; break;
                default: currentGridSpan = 2; break;
            }
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
        adapter.setGridSpan(currentGridSpan);
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

    @NonNull
    private List<ForensicsSnapshotWithMedia> dedupeRowsByStableId(@Nullable List<ForensicsSnapshotWithMedia> rows) {
        if (rows == null || rows.isEmpty()) return new ArrayList<>();
        LinkedHashMap<String, ForensicsSnapshotWithMedia> unique = new LinkedHashMap<>();
        for (ForensicsSnapshotWithMedia row : rows) {
            if (row == null) continue;
            String id = row.snapshotUid != null && !row.snapshotUid.isEmpty()
                    ? row.snapshotUid
                    : (ForensicsGalleryAdapter.safe(row.imageUri)
                    + "|" + ForensicsGalleryAdapter.safe(row.mediaUri)
                    + "|" + row.timelineMs
                    + "|" + row.capturedEpochMs
                    + "|" + ForensicsGalleryAdapter.safe(row.eventUid));
            unique.put(id, row);
        }
        return new ArrayList<>(unique.values());
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
        int uncheckedBg = androidx.core.graphics.ColorUtils.setAlphaComponent(checkedBg, 77);
        int checkedText = isDarkColor(checkedBg) ? Color.WHITE : Color.BLACK;
        int uncheckedText = Color.WHITE;
        int[][] states = new int[][]{ new int[]{android.R.attr.state_checked}, new int[]{} };
        chip.setChipBackgroundColor(new ColorStateList(states, new int[]{checkedBg, uncheckedBg}));
        chip.setTextColor(new ColorStateList(states, new int[]{checkedText, uncheckedText}));
        chip.setChipIconTint(new ColorStateList(states, new int[]{checkedText, uncheckedText}));
        chip.setChipStrokeWidth(0f);
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
        if (total <= 5) {
            scrollFab.setVisibility(View.GONE);
            return;
        }
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

    private void loadVisualPrefs() {
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(requireContext());
        clipStyle = prefs.sharedPreferences.getString(
                ForensicsGalleryAdapter.PREF_CLIP_STYLE,
                ForensicsGalleryAdapter.CLIP_STYLE_BLACK
        );
        tapeStyle = prefs.sharedPreferences.getString(
                ForensicsGalleryAdapter.PREF_TAPE_STYLE,
                ForensicsGalleryAdapter.TAPE_STYLE_TORN
        );
        if (clipStyle == null || clipStyle.trim().isEmpty()) {
            clipStyle = ForensicsGalleryAdapter.CLIP_STYLE_BLACK;
        }
        if (tapeStyle == null || tapeStyle.trim().isEmpty()) {
            tapeStyle = ForensicsGalleryAdapter.TAPE_STYLE_TORN;
        }
    }

    private void persistVisualPrefs() {
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(requireContext());
        prefs.sharedPreferences.edit()
                .putString(ForensicsGalleryAdapter.PREF_CLIP_STYLE, clipStyle)
                .putString(ForensicsGalleryAdapter.PREF_TAPE_STYLE, tapeStyle)
                .apply();
    }

    private static class ForensicsCardItemAnimator extends DefaultItemAnimator {
        private int runningAnimations;

        ForensicsCardItemAnimator() {
            setSupportsChangeAnimations(false);
            setAddDuration(150L);
            setRemoveDuration(120L);
            setMoveDuration(160L);
            setChangeDuration(0L);
        }

        @Override
        public boolean animateAdd(@NonNull RecyclerView.ViewHolder holder) {
            resetAnimation(holder);
            holder.itemView.setAlpha(0f);
            holder.itemView.setTranslationY(10f);
            dispatchAddStarting(holder);
            runningAnimations++;
            holder.itemView.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(getAddDuration())
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> {
                        runningAnimations = Math.max(0, runningAnimations - 1);
                        dispatchAddFinished(holder);
                        if (runningAnimations == 0) {
                            dispatchAnimationsFinished();
                        }
                    })
                    .start();
            return true;
        }

        @Override
        public boolean animateRemove(@NonNull RecyclerView.ViewHolder holder) {
            resetAnimation(holder);
            dispatchRemoveStarting(holder);
            runningAnimations++;
            holder.itemView.animate()
                    .alpha(0f)
                    .translationY(6f)
                    .setDuration(getRemoveDuration())
                    .setInterpolator(new AccelerateInterpolator())
                    .withEndAction(() -> {
                        holder.itemView.setAlpha(1f);
                        holder.itemView.setTranslationY(0f);
                        runningAnimations = Math.max(0, runningAnimations - 1);
                        dispatchRemoveFinished(holder);
                        if (runningAnimations == 0) {
                            dispatchAnimationsFinished();
                        }
                    })
                    .start();
            return true;
        }

        @Override
        public void runPendingAnimations() {
            // Animations are started immediately in animateAdd/animateRemove.
        }

        @Override
        public void endAnimation(RecyclerView.ViewHolder item) {
            item.itemView.animate().cancel();
            item.itemView.setAlpha(1f);
            item.itemView.setTranslationY(0f);
        }

        @Override
        public void endAnimations() {
            runningAnimations = 0;
            dispatchAnimationsFinished();
        }

        @Override
        public boolean isRunning() {
            return runningAnimations > 0;
        }

        private void resetAnimation(@NonNull RecyclerView.ViewHolder holder) {
            holder.itemView.animate().cancel();
            holder.itemView.setAlpha(1f);
            holder.itemView.setTranslationY(0f);
        }
    }

    private static final class ForensicsHangingStringDecoration extends RecyclerView.ItemDecoration {
        private final ForensicsGalleryAdapter adapter;
        private final Paint stringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Map<String, CachedPath> pathCache = new HashMap<>();
        private int cachedWidth = -1;

        ForensicsHangingStringDecoration(@NonNull ForensicsGalleryAdapter adapter) {
            this.adapter = adapter;
            stringPaint.setStyle(Paint.Style.STROKE);
            stringPaint.setStrokeWidth(1.4f);
            stringPaint.setColor(Color.argb(145, 219, 224, 232));
            stringPaint.setStrokeCap(Paint.Cap.ROUND);
            stringPaint.setStrokeJoin(Paint.Join.ROUND);

            dropPaint.setStyle(Paint.Style.STROKE);
            dropPaint.setStrokeWidth(1.15f);
            dropPaint.setColor(Color.argb(128, 198, 205, 215));
            dropPaint.setStrokeCap(Paint.Cap.ROUND);

            knotPaint.setStyle(Paint.Style.FILL);
            knotPaint.setColor(Color.argb(170, 232, 235, 240));
        }

        @Override
        public void onDraw(@NonNull android.graphics.Canvas canvas, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            if (parent.getWidth() != cachedWidth) {
                pathCache.clear();
                cachedWidth = parent.getWidth();
            }

            Map<String, RowBlock> blocks = collectVisibleRowBlocks(parent);
            for (RowBlock block : blocks.values()) {
                if (block.itemCount <= 0 || block.left >= block.right) {
                    continue;
                }

                float left = block.left + dp(parent, 6f);
                float right = block.right - dp(parent, 6f);
                float y = block.top - dp(parent, 9f);
                float sag = dp(parent, 2.2f + (Math.abs(block.rowKey.hashCode()) % 3));

                CachedPath cached = getOrCreatePath(block.rowKey, left, right, y, sag);
                canvas.drawPath(cached.path, stringPaint);
                canvas.drawCircle(left, y, dp(parent, 1.55f), knotPaint);
                canvas.drawCircle(right, y, dp(parent, 1.55f), knotPaint);

                for (float pinX : block.pinXs) {
                    float t = clamp((pinX - left) / Math.max(1f, right - left), 0f, 1f);
                    float stringY = cubicY(y, y + sag, y + sag, y, t);
                    float pinY = block.top + dp(parent, 25f);
                    canvas.drawLine(pinX, stringY, pinX, pinY, dropPaint);
                    canvas.drawCircle(pinX, stringY, dp(parent, 1.35f), knotPaint);
                }
            }
        }

        @NonNull
        private CachedPath getOrCreatePath(@NonNull String monthKey, float left, float right, float y, float sag) {
            String key = monthKey + "|" + Math.round(left) + "|" + Math.round(right) + "|" + Math.round(y) + "|" + Math.round(sag);
            CachedPath cached = pathCache.get(key);
            if (cached != null) {
                return cached;
            }
            Path path = new Path();
            float c1x = left + ((right - left) * 0.32f);
            float c2x = left + ((right - left) * 0.68f);
            path.moveTo(left, y);
            path.cubicTo(c1x, y + sag, c2x, y + sag, right, y);
            cached = new CachedPath(path);
            pathCache.put(key, cached);
            return cached;
        }

        @NonNull
        private Map<String, RowBlock> collectVisibleRowBlocks(@NonNull RecyclerView parent) {
            Map<String, RowBlock> blocks = new HashMap<>();
            int childCount = parent.getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = parent.getChildAt(i);
                int adapterPos = parent.getChildAdapterPosition(child);
                if (adapterPos == RecyclerView.NO_POSITION || !adapter.isItemPosition(adapterPos)) {
                    continue;
                }
                String monthKey = adapter.monthKeyAt(adapterPos);
                if (monthKey == null) {
                    continue;
                }
                int rowBucket = Math.round(child.getTop() / Math.max(1f, dp(parent, 22f)));
                String rowKey = monthKey + "|" + rowBucket;
                RowBlock block = blocks.get(rowKey);
                if (block == null) {
                    block = new RowBlock(rowKey);
                    blocks.put(rowKey, block);
                }
                block.itemCount++;
                block.left = Math.min(block.left, child.getLeft());
                block.right = Math.max(block.right, child.getRight());
                block.top = Math.min(block.top, child.getTop());
                block.pinXs.add(child.getLeft() + (child.getWidth() / 2f));
            }
            return blocks;
        }

        private static float cubicY(float y0, float y1, float y2, float y3, float t) {
            float u = 1f - t;
            return (u * u * u * y0)
                    + (3f * u * u * t * y1)
                    + (3f * u * t * t * y2)
                    + (t * t * t * y3);
        }

        private static float dp(@NonNull View view, float valueDp) {
            return valueDp * view.getResources().getDisplayMetrics().density;
        }

        private static float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }

        private static final class CachedPath {
            final Path path;

            CachedPath(@NonNull Path path) {
                this.path = path;
            }
        }

        private static final class RowBlock {
            final String rowKey;
            float left = Float.MAX_VALUE;
            float right = -Float.MAX_VALUE;
            float top = Float.MAX_VALUE;
            int itemCount;
            final List<Float> pinXs = new ArrayList<>();

            RowBlock(@NonNull String rowKey) {
                this.rowKey = rowKey;
            }
        }
    }
}
