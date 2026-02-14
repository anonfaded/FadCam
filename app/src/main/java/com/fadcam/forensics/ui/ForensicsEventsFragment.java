package com.fadcam.forensics.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ForensicsEventsFragment extends Fragment implements ForensicsEventsAdapter.Listener {

    public static final String EXTRA_OPEN_AT_MS = "com.fadcam.extra.OPEN_AT_MS";
    public static final String EXTRA_OPEN_PAUSED = "com.fadcam.extra.OPEN_PAUSED";

    private RecyclerView recycler;
    private TextView empty;
    private Chip chipAll;
    private Chip chipPerson;
    private Chip chipVehicle;
    private Chip chipPet;
    private Chip chipObject;
    private Chip chipHighConf;
    private ForensicsEventsAdapter adapter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_forensics_events, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        view.findViewById(R.id.back_button).setOnClickListener(v -> OverlayNavUtil.popLevel(requireActivity()));

        recycler = view.findViewById(R.id.recycler_events);
        empty = view.findViewById(R.id.text_empty);
        chipAll = view.findViewById(R.id.chip_event_all);
        chipPerson = view.findViewById(R.id.chip_event_person);
        chipVehicle = view.findViewById(R.id.chip_event_vehicle);
        chipPet = view.findViewById(R.id.chip_event_pet);
        chipObject = view.findViewById(R.id.chip_event_object);
        chipHighConf = view.findViewById(R.id.chip_event_high_conf);
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

        loadEvents();
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
        applyChipIcon(chipVehicle, R.drawable.ic_records);
        applyChipIcon(chipPet, R.drawable.ic_theme);
        applyChipIcon(chipObject, R.drawable.ic_grid);
        applyChipIcon(chipHighConf, R.drawable.ic_focus_target);
        chipHighConf.setCheckedIconVisible(false);
    }

    private void selectEventTypeChip(@NonNull Chip selected) {
        chipAll.setChecked(selected == chipAll);
        chipPerson.setChecked(selected == chipPerson);
        chipVehicle.setChecked(selected == chipVehicle);
        chipPet.setChecked(selected == chipPet);
        chipObject.setChecked(selected == chipObject);
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

    private void loadEvents() {
        final String eventType = resolveSelectedEventType();
        final float minConf = (chipHighConf != null && chipHighConf.isChecked()) ? 0.75f : 0f;
        final long since = System.currentTimeMillis() - (7L * 24L * 60L * 60L * 1000L);
        executor.execute(() -> {
            List<AiEventWithMedia> rows = ForensicsDatabase.getInstance(requireContext())
                    .aiEventDao()
                    .getTimeline(eventType, minConf, since, 400);
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                adapter.submit(rows);
                boolean hasRows = rows != null && !rows.isEmpty();
                empty.setVisibility(hasRows ? View.GONE : View.VISIBLE);
            });
        });
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
