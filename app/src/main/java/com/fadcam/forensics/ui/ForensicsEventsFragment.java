package com.fadcam.forensics.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.R;
import com.fadcam.forensics.data.local.ForensicsDatabase;
import com.fadcam.forensics.data.local.model.AiEventWithMedia;
import com.fadcam.ui.OverlayNavUtil;
import com.fadcam.ui.VideoPlayerActivity;
import com.google.android.material.chip.Chip;

import android.graphics.Color;
import android.text.TextUtils;
import android.widget.Toast;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ForensicsEventsFragment extends Fragment implements ForensicsEventsAdapter.Listener {

    public static final String EXTRA_OPEN_AT_MS = "com.fadcam.extra.OPEN_AT_MS";
    public static final String EXTRA_OPEN_PAUSED = "com.fadcam.extra.OPEN_PAUSED";
    private static final String ARG_EMBEDDED = "arg_embedded";

    private RecyclerView recycler;
    private TextView empty;
    private TextView sortValue;
    private TextView mediaStateValue;
    private Chip chipAll;
    private Chip chipPerson;
    private Chip chipVehicle;
    private Chip chipPet;
    private Chip chipObject;
    private Chip chipHighConf;
    private Chip chipSubtypeAll;
    private LinearLayout subtypeContainer;
    private HorizontalScrollView subtypeScroll;
    private HorizontalScrollView eventChipScroll;
    private String selectedSubtype;
    private ForensicsEventsAdapter adapter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean embeddedMode;
    private boolean isLoading;
    private boolean dataLoaded;
    private String selectedSort = "newest";
    @Nullable
    private String selectedMediaState = null;

    public static ForensicsEventsFragment newEmbeddedInstance() {
        ForensicsEventsFragment fragment = new ForensicsEventsFragment();
        Bundle args = new Bundle();
        args.putBoolean(ARG_EMBEDDED, true);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_forensics_events, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        embeddedMode = getArguments() != null && getArguments().getBoolean(ARG_EMBEDDED, false);
        View header = view.findViewById(R.id.header_bar);
        if (header != null && embeddedMode) {
            header.setVisibility(View.GONE);
        }
        View backButton = view.findViewById(R.id.back_button);
        if (backButton != null) {
            if (embeddedMode) {
                backButton.setVisibility(View.GONE);
            } else {
                backButton.setOnClickListener(v -> OverlayNavUtil.popLevel(requireActivity()));
            }
        }

        recycler = view.findViewById(R.id.recycler_events);
        empty = view.findViewById(R.id.text_empty);
        sortValue = view.findViewById(R.id.text_sort_value);
        mediaStateValue = view.findViewById(R.id.text_media_state_value);
        chipAll = view.findViewById(R.id.chip_event_all);
        chipPerson = view.findViewById(R.id.chip_event_person);
        chipVehicle = view.findViewById(R.id.chip_event_vehicle);
        chipPet = view.findViewById(R.id.chip_event_pet);
        chipObject = view.findViewById(R.id.chip_event_object);
        chipHighConf = view.findViewById(R.id.chip_event_high_conf);
        chipSubtypeAll = view.findViewById(R.id.chip_subtype_all);
        subtypeContainer = view.findViewById(R.id.chips_subtype_container);
        subtypeScroll = view.findViewById(R.id.subtype_chip_scroll);
        eventChipScroll = view.findViewById(R.id.event_chip_scroll);
        styleAndIconChips();

        adapter = new ForensicsEventsAdapter(this);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setAdapter(adapter);

        chipAll.setOnClickListener(v -> selectEventTypeChip(chipAll));
        chipPerson.setOnClickListener(v -> selectEventTypeChip(chipPerson));
        chipVehicle.setOnClickListener(v -> selectEventTypeChip(chipVehicle));
        chipPet.setOnClickListener(v -> selectEventTypeChip(chipPet));
        chipObject.setOnClickListener(v -> selectEventTypeChip(chipObject));
        chipHighConf.setOnCheckedChangeListener((buttonView, isChecked) -> loadEvents());
        ImageView sortButton = view.findViewById(R.id.button_sort_events);
        if (sortButton != null) {
            sortButton.setOnClickListener(v -> showSortMenu(v));
        }
        if (sortValue != null) {
            sortValue.setOnClickListener(this::showSortMenu);
        }
        if (mediaStateValue != null) {
            mediaStateValue.setOnClickListener(this::showSortMenu);
        }
        if (chipSubtypeAll != null) {
            chipSubtypeAll.setOnClickListener(v -> {
                selectedSubtype = null;
                chipSubtypeAll.setChecked(true);
                syncSubtypeSelection();
                loadEvents();
            });
        }
        installHorizontalSwipeGuards();

        loadEvents();
    }

    private void installHorizontalSwipeGuards() {
        View.OnTouchListener guard = (v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                    || event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            }
            return false;
        };
        if (eventChipScroll != null) {
            eventChipScroll.setOnTouchListener(guard);
        }
        if (subtypeScroll != null) {
            subtypeScroll.setOnTouchListener(guard);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (isAdded() && !dataLoaded && !isLoading) {
            loadEvents();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (adapter != null) adapter.shutdown();
        executor.shutdownNow();
    }

    private void styleAndIconChips() {
        styleChip(chipAll);
        styleChip(chipPerson);
        styleChip(chipVehicle);
        styleChip(chipPet);
        styleChip(chipObject);
        styleChip(chipHighConf);
        applyChipIcon(chipAll, R.drawable.ic_list);
        applyChipIcon(chipPerson, R.drawable.ic_broadcast_on_personal_24);
        applyChipIcon(chipVehicle, R.drawable.ic_forensics_vehicle);
        applyChipIcon(chipPet, R.drawable.ic_forensics_pet);
        applyChipIcon(chipObject, R.drawable.ic_grid);
        applyChipIcon(chipHighConf, R.drawable.ic_focus_target);
        chipHighConf.setCheckedIconVisible(false);
        styleChip(chipSubtypeAll);
        applyChipIcon(chipSubtypeAll, R.drawable.ic_list);
        if (chipSubtypeAll != null) {
            chipSubtypeAll.setCheckedIconVisible(false);
        }
    }

    private void selectEventTypeChip(@NonNull Chip selected) {
        chipAll.setChecked(selected == chipAll);
        chipPerson.setChecked(selected == chipPerson);
        chipVehicle.setChecked(selected == chipVehicle);
        chipPet.setChecked(selected == chipPet);
        chipObject.setChecked(selected == chipObject);
        selectedSubtype = null;
        if (chipSubtypeAll != null) {
            chipSubtypeAll.setChecked(true);
        }
        syncSubtypeSelection();
        loadEvents();
    }

    private void applyChipIcon(@Nullable Chip chip, int drawableRes) {
        if (chip == null) return;
        chip.setChipIconResource(drawableRes);
        chip.setChipIconVisible(true);
        chip.setIconStartPadding(dpToPx(2));
        chip.setChipIconSize(dpToPx(16));
    }

    private void styleChip(@Nullable Chip chip) {
        if (chip == null || getContext() == null) return;
        int checkedBg = resolveThemeColor(R.attr.colorButton);
        int uncheckedBg = androidx.core.graphics.ColorUtils.setAlphaComponent(checkedBg, 77);
        int checkedText = isDarkColor(checkedBg) ? Color.WHITE : Color.BLACK;
        int uncheckedText = Color.WHITE;
        int[][] states = new int[][]{
            new int[]{android.R.attr.state_checked},
            new int[]{}
        };
        chip.setChipBackgroundColor(new ColorStateList(states, new int[]{checkedBg, uncheckedBg}));
        chip.setTextColor(new ColorStateList(states, new int[]{checkedText, uncheckedText}));
        chip.setChipIconTint(new ColorStateList(states, new int[]{checkedText, uncheckedText}));
        chip.setChipStrokeWidth(0f);
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

    private void loadEvents() {
        isLoading = true;
        final String eventType = resolveSelectedEventType();
        final boolean objectMode = "OBJECT".equals(eventType);
        final String className = (objectMode && !TextUtils.isEmpty(selectedSubtype)) ? selectedSubtype : null;
        final float minConf = (chipHighConf != null && chipHighConf.isChecked()) ? 0.75f : 0f;
        final long since = System.currentTimeMillis() - (7L * 24L * 60L * 60L * 1000L);
        // Capture application context on main thread to avoid requireContext() crash inside executor
        final android.content.Context appContext = requireContext().getApplicationContext();
        executor.execute(() -> {
            ForensicsDatabase db = ForensicsDatabase.getInstance(appContext);
            String sortOrder = "confidence".equals(selectedSort) ? "confidence" : "newest";
            List<AiEventWithMedia> rows = db.aiEventDao().getTimeline(
                    eventType,
                    className,
                    minConf,
                    since,
                    selectedMediaState,
                    sortOrder,
                    400
            );
            reconcileMediaState(rows, db, appContext);
            List<String> dynamicSubtypes = objectMode
                    ? db.aiEventDao().getTopClassNames(since, "OBJECT", 10)
                    : null;
            if (!isAdded()) {
                isLoading = false;
                return;
            }
            requireActivity().runOnUiThread(() -> {
                adapter.submit(rows);
                boolean hasRows = rows != null && !rows.isEmpty();
                empty.setVisibility(hasRows ? View.GONE : View.VISIBLE);
                renderSubtypeChips(dynamicSubtypes, objectMode);
                refreshSortUi();
                isLoading = false;
                dataLoaded = true;
            });
        });
    }

    private void reconcileMediaState(@Nullable List<AiEventWithMedia> rows,
                                     @NonNull ForensicsDatabase db,
                                     @NonNull android.content.Context appContext) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (AiEventWithMedia row : rows) {
            boolean missing = isMediaMissing(row.mediaUri, appContext);
            if (row.mediaMissing != missing && row.mediaUid != null && !row.mediaUid.isEmpty()) {
                row.mediaMissing = missing;
                try {
                    db.aiEventDao().updateMediaMissingByMediaUid(row.mediaUid, missing);
                } catch (Exception ignored) {
                }
            } else {
                row.mediaMissing = missing;
            }
        }
    }

    private boolean isMediaMissing(@Nullable String mediaUri, @NonNull android.content.Context appContext) {
        if (mediaUri == null || mediaUri.isEmpty()) {
            return true;
        }
        try {
            Uri uri = Uri.parse(mediaUri);
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                String path = uri.getPath();
                return path == null || !new java.io.File(path).exists();
            }
            try (android.content.res.AssetFileDescriptor afd =
                         appContext.getContentResolver().openAssetFileDescriptor(uri, "r")) {
                return afd == null;
            }
        } catch (Exception e) {
            return true;
        }
    }

    private void showSortMenu(View anchor) {
        if (!isAdded()) {
            return;
        }
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        Menu menu = popup.getMenu();
        menu.add(0, 1, 0, getString(R.string.forensics_sort_newest));
        menu.add(0, 2, 1, getString(R.string.forensics_sort_confidence));
        menu.add(0, 3, 2, getString(R.string.forensics_sort_evidence_rich));
        menu.add(0, 4, 3, getString(R.string.forensics_media_all));
        menu.add(0, 5, 4, getString(R.string.forensics_media_available));
        menu.add(0, 6, 5, getString(R.string.forensics_media_missing_only));
        popup.setOnMenuItemClickListener(this::onSortMenuItem);
        popup.show();
    }

    private boolean onSortMenuItem(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                selectedSort = "newest";
                break;
            case 2:
                selectedSort = "confidence";
                break;
            case 3:
                selectedSort = "evidence";
                break;
            case 4:
                selectedMediaState = null;
                break;
            case 5:
                selectedMediaState = "AVAILABLE";
                break;
            case 6:
                selectedMediaState = "MISSING";
                break;
            default:
                return false;
        }
        refreshSortUi();
        loadEvents();
        return true;
    }

    private void refreshSortUi() {
        if (sortValue != null) {
            String label;
            switch (selectedSort) {
                case "confidence":
                    label = getString(R.string.forensics_sort_confidence);
                    break;
                case "evidence":
                    label = getString(R.string.forensics_sort_evidence_rich);
                    break;
                default:
                    label = getString(R.string.forensics_sort_newest);
                    break;
            }
            sortValue.setText(getString(R.string.forensics_sort_label, label));
        }
        if (mediaStateValue != null) {
            String label;
            if ("AVAILABLE".equals(selectedMediaState)) {
                label = getString(R.string.forensics_media_available);
            } else if ("MISSING".equals(selectedMediaState)) {
                label = getString(R.string.forensics_media_missing_only);
            } else {
                label = getString(R.string.forensics_media_all);
            }
            mediaStateValue.setText(getString(R.string.forensics_source_label, label));
        }
    }

    private void renderSubtypeChips(@Nullable List<String> subtypes, boolean objectMode) {
        if (subtypeContainer == null || chipSubtypeAll == null) {
            return;
        }
        if (subtypeScroll != null) {
            subtypeScroll.setVisibility(objectMode ? View.VISIBLE : View.GONE);
        }
        if (!objectMode) {
            selectedSubtype = null;
            return;
        }
        subtypeContainer.removeAllViews();
        subtypeContainer.addView(chipSubtypeAll);
        chipSubtypeAll.setChecked(TextUtils.isEmpty(selectedSubtype));
        if (subtypes == null || subtypes.isEmpty()) {
            return;
        }
        for (String subtype : subtypes) {
            if (TextUtils.isEmpty(subtype)) {
                continue;
            }
            final String value = subtype.trim().toLowerCase();
            Chip chip = new Chip(requireContext(), null, com.google.android.material.R.style.Widget_MaterialComponents_Chip_Choice);
            chip.setCheckable(true);
            chip.setText(value);
            chip.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            ((LinearLayout.LayoutParams) chip.getLayoutParams()).setMarginStart((int) dpToPx(8));
            styleChip(chip);
            applyChipIcon(chip, R.drawable.ic_grid);
            chip.setCheckedIconVisible(false);
            chip.setOnClickListener(v -> {
                selectedSubtype = value;
                syncSubtypeSelection();
                loadEvents();
            });
            chip.setChecked(value.equals(selectedSubtype));
            subtypeContainer.addView(chip);
        }
        syncSubtypeSelection();
    }

    private void syncSubtypeSelection() {
        if (subtypeContainer == null) {
            return;
        }
        int count = subtypeContainer.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = subtypeContainer.getChildAt(i);
            if (!(child instanceof Chip)) {
                continue;
            }
            Chip chip = (Chip) child;
            if (chip == chipSubtypeAll) {
                chip.setChecked(TextUtils.isEmpty(selectedSubtype));
            } else {
                String subtype = chip.getText() == null ? null : chip.getText().toString().toLowerCase();
                chip.setChecked(!TextUtils.isEmpty(selectedSubtype) && selectedSubtype.equals(subtype));
            }
        }
    }

    @Nullable
    private String resolveSelectedEventType() {
        if (chipPerson != null && chipPerson.isChecked()) {
            return "PERSON";
        }
        if (chipVehicle != null && chipVehicle.isChecked()) {
            return "VEHICLE";
        }
        if (chipPet != null && chipPet.isChecked()) {
            return "PET";
        }
        if (chipObject != null && chipObject.isChecked()) {
            return "OBJECT";
        }
        return null;
    }

    @Override
    public void onEventClicked(AiEventWithMedia row) {
        if (row != null && row.mediaMissing) {
            Toast.makeText(requireContext(), R.string.forensics_media_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        if (row == null || row.mediaUri == null || row.mediaUri.isEmpty()) {
            return;
        }
        Intent intent = new Intent(requireContext(), VideoPlayerActivity.class);
        intent.setData(Uri.parse(row.mediaUri));
        intent.putExtra(EXTRA_OPEN_AT_MS, row.startMs);
        intent.putExtra(EXTRA_OPEN_PAUSED, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }

    @Override
    public void onEventFrameClicked(AiEventWithMedia row, long targetMs) {
        if (row != null && row.mediaMissing) {
            Toast.makeText(requireContext(), R.string.forensics_media_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        if (row == null || row.mediaUri == null || row.mediaUri.isEmpty()) {
            return;
        }
        Intent intent = new Intent(requireContext(), VideoPlayerActivity.class);
        intent.setData(Uri.parse(row.mediaUri));
        intent.putExtra(EXTRA_OPEN_AT_MS, Math.max(0L, targetMs));
        intent.putExtra(EXTRA_OPEN_PAUSED, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }
}
