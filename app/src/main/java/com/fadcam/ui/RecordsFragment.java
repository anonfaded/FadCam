package com.fadcam.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.fadcam.R;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class RecordsFragment extends Fragment implements RecordsAdapter.OnVideoClickListener, RecordsAdapter.OnVideoLongClickListener {

    private RecyclerView recyclerView;
    private RecordsAdapter adapter;
    private boolean isGridView = true;
    private FloatingActionButton fabToggleView;
    private FloatingActionButton fabDeleteSelected;
    private List<File> selectedVideos = new ArrayList<>();

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_records, container, false);
        setHasOptionsMenu(true);

        recyclerView = view.findViewById(R.id.recycler_view_records);
        fabToggleView = view.findViewById(R.id.fab_toggle_view);
        fabDeleteSelected = view.findViewById(R.id.fab_delete_selected);

        setupRecyclerView();
        setupFabListeners();

        return view;
    }

    private void setupRecyclerView() {
        setLayoutManager();
        List<File> recordsList = getRecordsList();
        adapter = new RecordsAdapter(recordsList, this, this);
        recyclerView.setAdapter(adapter);
    }

    private void setLayoutManager() {
        RecyclerView.LayoutManager layoutManager = isGridView ?
                new GridLayoutManager(getContext(), 2) :
                new LinearLayoutManager(getContext());
        recyclerView.setLayoutManager(layoutManager);
    }

    private void setupFabListeners() {
        fabToggleView.setOnClickListener(v -> toggleViewMode());
        fabDeleteSelected.setOnClickListener(v -> confirmDeleteSelected());
    }

    private void toggleViewMode() {
        isGridView = !isGridView;
        setLayoutManager();
        updateFabIcons();
    }

    private void updateFabIcons() {
        fabToggleView.setImageResource(isGridView ? R.drawable.ic_list : R.drawable.ic_grid);
    }

    private List<File> getRecordsList() {
        List<File> recordsList = new ArrayList<>();
        File recordsDir = new File(getContext().getExternalFilesDir(null), "FadCam");
        if (recordsDir.exists()) {
            File[] files = recordsDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith(".mp4")) {
                        recordsList.add(file);
                    }
                }
            }
        }
        return recordsList;
    }

    @Override
    public void onVideoClick(File video) {
        VideoPlayerFragment playerFragment = VideoPlayerFragment.newInstance(video.getAbsolutePath());
        getParentFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, playerFragment)
                .addToBackStack(null)
                .commit();
    }

    @Override
    public void onVideoLongClick(File video, boolean isSelected) {
        if (isSelected) {
            selectedVideos.add(video);
        } else {
            selectedVideos.remove(video);
        }
        updateDeleteButtonVisibility();
    }

    private void updateDeleteButtonVisibility() {
        fabDeleteSelected.setVisibility(selectedVideos.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void confirmDeleteSelected() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete Selected Videos")
                .setMessage("Are you sure you want to delete the selected videos?")
                .setPositiveButton("Delete", (dialog, which) -> deleteSelectedVideos())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteSelectedVideos() {
        for (File video : selectedVideos) {
            video.delete();
        }
        selectedVideos.clear();
        updateDeleteButtonVisibility();
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.records_menu, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_delete_all) {
            confirmDeleteAll();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void confirmDeleteAll() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete All Videos")
                .setMessage("Are you sure you want to delete all videos?")
                .setPositiveButton("Delete", (dialog, which) -> deleteAllVideos())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteAllVideos() {
        File recordsDir = new File(getContext().getExternalFilesDir(null), "FadCam");
        if (recordsDir.exists()) {
            File[] files = recordsDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith(".mp4")) {
                        file.delete();
                    }
                }
            }
        }
        adapter.notifyDataSetChanged();
    }
}