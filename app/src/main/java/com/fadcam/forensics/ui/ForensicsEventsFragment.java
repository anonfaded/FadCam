package com.fadcam.forensics.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
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

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ForensicsEventsFragment extends Fragment implements ForensicsEventsAdapter.Listener {

    public static final String EXTRA_OPEN_AT_MS = "com.fadcam.extra.OPEN_AT_MS";

    private RecyclerView recycler;
    private TextView empty;
    private Chip chipAll;
    private Chip chipPerson;
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
        view.findViewById(R.id.back_button).setOnClickListener(v -> OverlayNavUtil.dismiss(requireActivity()));

        recycler = view.findViewById(R.id.recycler_events);
        empty = view.findViewById(R.id.text_empty);
        chipAll = view.findViewById(R.id.chip_event_all);
        chipPerson = view.findViewById(R.id.chip_event_person);
        chipHighConf = view.findViewById(R.id.chip_event_high_conf);

        adapter = new ForensicsEventsAdapter(this);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setAdapter(adapter);

        chipAll.setOnClickListener(v -> {
            chipAll.setChecked(true);
            chipPerson.setChecked(false);
            loadEvents();
        });
        chipPerson.setOnClickListener(v -> {
            chipPerson.setChecked(true);
            chipAll.setChecked(false);
            loadEvents();
        });
        chipHighConf.setOnCheckedChangeListener((buttonView, isChecked) -> loadEvents());

        loadEvents();
    }

    private void loadEvents() {
        final String eventType = chipPerson != null && chipPerson.isChecked() ? "PERSON" : null;
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

    @Override
    public void onEventClicked(AiEventWithMedia row) {
        if (row == null || row.mediaUri == null || row.mediaUri.isEmpty()) {
            return;
        }
        Intent intent = new Intent(requireContext(), VideoPlayerActivity.class);
        intent.setData(Uri.parse(row.mediaUri));
        intent.putExtra(EXTRA_OPEN_AT_MS, row.startMs);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }
}
