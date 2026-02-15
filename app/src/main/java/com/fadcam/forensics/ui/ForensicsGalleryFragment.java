package com.fadcam.forensics.ui;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.R;
import com.fadcam.forensics.data.local.ForensicsDatabase;
import com.fadcam.forensics.data.local.model.ForensicsSnapshotWithMedia;
import com.google.android.material.chip.Chip;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ForensicsGalleryFragment extends Fragment {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ForensicsGalleryAdapter adapter = new ForensicsGalleryAdapter();

    private RecyclerView recycler;
    private TextView empty;
    private TextView sortValue;
    private TextView sourceValue;
    private Chip chipAll;
    private Chip chipPerson;
    private Chip chipVehicle;
    private Chip chipPet;
    private Chip chipObject;

    private String selectedSort = "newest";
    @Nullable
    private String selectedMediaState = null;

    public ForensicsGalleryFragment() {
        super(R.layout.fragment_forensics_gallery);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        recycler = view.findViewById(R.id.recycler_gallery);
        empty = view.findViewById(R.id.text_gallery_empty);
        sortValue = view.findViewById(R.id.text_gallery_sort_value);
        sourceValue = view.findViewById(R.id.text_gallery_source_value);
        chipAll = view.findViewById(R.id.chip_gallery_all);
        chipPerson = view.findViewById(R.id.chip_gallery_person);
        chipVehicle = view.findViewById(R.id.chip_gallery_vehicle);
        chipPet = view.findViewById(R.id.chip_gallery_pet);
        chipObject = view.findViewById(R.id.chip_gallery_object);

        recycler.setHasFixedSize(true);
        recycler.setItemViewCacheSize(30);
        recycler.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        recycler.setAdapter(adapter);

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
        refreshSortUi();
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
        final String eventType = resolveType();
        final long since = System.currentTimeMillis() - (30L * 24L * 60L * 60L * 1000L);
        executor.execute(() -> {
            List<ForensicsSnapshotWithMedia> rows = ForensicsDatabase.getInstance(requireContext())
                    .aiEventSnapshotDao()
                    .getGallerySnapshots(eventType, 0f, selectedMediaState, since, 2000);
            sortRows(rows);
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                adapter.submit(rows);
                empty.setVisibility(rows == null || rows.isEmpty() ? View.VISIBLE : View.GONE);
                refreshSortUi();
            });
        });
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
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        Menu menu = popup.getMenu();
        menu.add(0, 1, 0, getString(R.string.forensics_sort_newest));
        menu.add(0, 2, 1, getString(R.string.sort_oldest_first));
        menu.add(0, 3, 2, getString(R.string.forensics_sort_confidence));
        menu.add(0, 4, 3, getString(R.string.forensics_gallery_sort_event_type));
        menu.add(0, 5, 4, getString(R.string.forensics_media_all));
        menu.add(0, 6, 5, getString(R.string.forensics_media_available));
        menu.add(0, 7, 6, getString(R.string.forensics_media_missing_only));
        popup.setOnMenuItemClickListener(this::onSortMenuItem);
        popup.show();
    }

    private boolean onSortMenuItem(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                selectedSort = "newest";
                break;
            case 2:
                selectedSort = "oldest";
                break;
            case 3:
                selectedSort = "confidence";
                break;
            case 4:
                selectedSort = "event";
                break;
            case 5:
                selectedMediaState = null;
                break;
            case 6:
                selectedMediaState = "AVAILABLE";
                break;
            case 7:
                selectedMediaState = "MISSING";
                break;
            default:
                return false;
        }
        loadData();
        return true;
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
