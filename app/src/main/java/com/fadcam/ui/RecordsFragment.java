package com.fadcam.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.Constants;
import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RecordsFragment extends Fragment implements RecordsAdapter.OnVideoClickListener, RecordsAdapter.OnVideoLongClickListener {

    private RecyclerView recyclerView;
    private RecordsAdapter recordsAdapter;
    private boolean isGridView = true;
    private FloatingActionButton fabToggleView;
    private FloatingActionButton fabDeleteSelected;
    private List<File> selectedVideos = new ArrayList<>();
    private ExecutorService executorService = Executors.newSingleThreadExecutor(); // Executor for background tasks
    private SortOption currentSortOption = SortOption.LATEST_FIRST;
    private List<File> videoFiles = new ArrayList<>();

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_records, container, false);
        setHasOptionsMenu(true);

        recyclerView = view.findViewById(R.id.recycler_view_records);
        fabToggleView = view.findViewById(R.id.fab_toggle_view);
        fabDeleteSelected = view.findViewById(R.id.fab_delete_selected);

        Toolbar toolbar = view.findViewById(R.id.topAppBar);
        ((AppCompatActivity) requireActivity()).setSupportActionBar(toolbar);

        setupRecyclerView();
        setupFabListeners();

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        loadRecordsList(); // Load the list in the background
    }

    private void setupRecyclerView() {
        setLayoutManager();
        recordsAdapter = new RecordsAdapter(getContext(), new ArrayList<>(), this, this);
        recyclerView.setAdapter(recordsAdapter);
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
        // Haptic Feedback
        Vibrator vibrator = (Vibrator) requireContext().getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            VibrationEffect effect = null;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(effect);
            }
        }
        isGridView = !isGridView;
        setLayoutManager();
        updateFabIcons();
    }

    private void updateFabIcons() {
        fabToggleView.setImageResource(isGridView ? R.drawable.ic_list : R.drawable.ic_grid);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void loadRecordsList() {
        executorService.submit(() -> {
            List<File> recordsList = getRecordsList();
            
            // Sort initially by latest first
            recordsList.sort((a, b) -> {
                long timeA = extractVideoTime(a);
                long timeB = extractVideoTime(b);
                return Long.compare(timeB, timeA);
            });

            videoFiles = new ArrayList<>(recordsList);
            
            requireActivity().runOnUiThread(() -> {
                recordsAdapter.updateRecords(recordsList);
                Log.d("RecordsFragment", "Initial records loaded and sorted. Count: " + recordsList.size());
            });
        });
    }

    private List<File> getRecordsList() {
        List<File> recordsList = new ArrayList<>();
        File recordsDir = new File(requireContext().getExternalFilesDir(null), Constants.RECORDING_DIRECTORY);
        if (recordsDir.exists()) {
            File[] files = recordsDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith("." + Constants.RECORDING_FILE_EXTENSION)) {
                        recordsList.add(file);
                    }
                }
            }
        }
        return recordsList;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        loadRecordsList(); // Load the records when the view is created
    }

    @Override
    public void onVideoClick(File video) {
        Intent intent = new Intent(getActivity(), VideoPlayerActivity.class);
        intent.putExtra("VIDEO_PATH", video.getAbsolutePath());
        startActivity(intent);
    }

    @Override
    public void onVideoLongClick(File video, boolean isSelected) {
        if (isSelected) {
            selectedVideos.add(video);
        } else {
            selectedVideos.remove(video);
        }
        updateDeleteButtonVisibility();
        // Haptic Feedback
        Vibrator vibrator = (Vibrator) requireContext().getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            VibrationEffect effect = null;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(effect);
            }
        }
    }

    private void updateDeleteButtonVisibility() {
        fabDeleteSelected.setVisibility(selectedVideos.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void confirmDeleteSelected() {
        // Haptic Feedback
        Vibrator vibrator = (Vibrator) requireContext().getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            VibrationEffect effect = null;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(effect);
            }
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getResources().getString(R.string.dialog_multi_video_del_title))
                .setMessage(getResources().getString(R.string.dialog_multi_video_del_note))
                .setNegativeButton(getResources().getString(R.string.dialog_multi_video_del_no), null)
                .setPositiveButton(getResources().getString(R.string.dialog_multi_video_del_yes), (dialog, which) -> deleteSelectedVideos())
                .show();
    }

    private void deleteSelectedVideos() {
        for (File video : selectedVideos) {
            video.delete();
        }
        selectedVideos.clear();
        updateDeleteButtonVisibility();
        loadRecordsList();
    }

    private void showRecordsSidebar() {
        View bottomSheetView = getLayoutInflater().inflate(R.layout.bottom_sheet_records_options, null);
        BottomSheetDialog bottomSheetDialog = new BottomSheetDialog(requireContext());
        bottomSheetDialog.setContentView(bottomSheetView);

        RadioGroup sortOptionsGroup = bottomSheetView.findViewById(R.id.sort_options_group);
        LinearLayout deleteAllOption = bottomSheetView.findViewById(R.id.option_delete_all);

        if (sortOptionsGroup != null) {
            // Preselect current sort option
            switch (currentSortOption) {
                case LATEST_FIRST:
                    sortOptionsGroup.check(R.id.sort_latest);
                    break;
                case OLDEST_FIRST:
                    sortOptionsGroup.check(R.id.sort_oldest);
                    break;
                case SMALLEST_FILES:
                    sortOptionsGroup.check(R.id.sort_smallest);
                    break;
                case LARGEST_FILES:
                    sortOptionsGroup.check(R.id.sort_largest);
                    break;
            }

            sortOptionsGroup.setOnCheckedChangeListener((group, checkedId) -> {
                SortOption newSortOption;
                if (checkedId == R.id.sort_latest) {
                    newSortOption = SortOption.LATEST_FIRST;
                } else if (checkedId == R.id.sort_oldest) {
                    newSortOption = SortOption.OLDEST_FIRST;
                } else if (checkedId == R.id.sort_smallest) {
                    newSortOption = SortOption.SMALLEST_FILES;
                } else if (checkedId == R.id.sort_largest) {
                    newSortOption = SortOption.LARGEST_FILES;
                } else {
                    return; // No valid option selected
                }

                // Only sort if the option has changed
                if (newSortOption != currentSortOption) {
                    currentSortOption = newSortOption;
                    performVideoSort();
                }
                
                bottomSheetDialog.dismiss();
            });
        }

        if (deleteAllOption != null) {
            deleteAllOption.setOnClickListener(v -> {
                bottomSheetDialog.dismiss();
                confirmDeleteAll();
            });
        }

        bottomSheetDialog.show();
    }

    private void performVideoSort() {
        if (videoFiles == null || videoFiles.isEmpty()) {
            Log.e("RecordsFragment", "Cannot sort: videoFiles is null or empty");
            return;
        }

        // Create a copy of the original list to preserve original order
        List<File> sortedVideos = new ArrayList<>(videoFiles);

        try {
            switch (currentSortOption) {
                case LATEST_FIRST:
                    Collections.sort(sortedVideos, (a, b) -> {
                        long timeA = extractVideoTime(a);
                        long timeB = extractVideoTime(b);
                        return Long.compare(timeB, timeA);
                    });
                    break;
                case OLDEST_FIRST:
                    Collections.sort(sortedVideos, (a, b) -> {
                        long timeA = extractVideoTime(a);
                        long timeB = extractVideoTime(b);
                        return Long.compare(timeA, timeB);
                    });
                    break;
                case SMALLEST_FILES:
                    Collections.sort(sortedVideos, Comparator.comparingLong(File::length));
                    break;
                case LARGEST_FILES:
                    Collections.sort(sortedVideos, (a, b) -> Long.compare(b.length(), a.length()));
                    break;
            }

            // Update the list and adapter on the main thread
            requireActivity().runOnUiThread(() -> {
                videoFiles = sortedVideos;
                recordsAdapter.updateRecords(videoFiles);
                recyclerView.scrollToPosition(0);
                
                Log.d("RecordsFragment", "Sorted videos. Option: " + currentSortOption + 
                      ", Total videos: " + videoFiles.size());
            });

        } catch (Exception e) {
            Log.e("RecordsFragment", "Error sorting videos", e);
        }
    }

    private long extractVideoTime(File videoFile) {
        try {
            String fileName = videoFile.getName();
            // Assuming filename format: video_2024-01-11_12-30-45.mp4
            int dateStartIndex = fileName.indexOf('_') + 1;
            int dateEndIndex = fileName.indexOf('_', dateStartIndex);
            
            if (dateStartIndex > 0 && dateEndIndex > dateStartIndex) {
                String dateTimeStr = fileName.substring(dateStartIndex, dateEndIndex);
                Log.d("RecordsFragment", "Extracted datetime: " + dateTimeStr + " from " + fileName);
                return videoFile.lastModified(); // Fallback to last modified time
            }
            
            return videoFile.lastModified();
        } catch (Exception e) {
            Log.e("RecordsFragment", "Error extracting time from " + videoFile.getName(), e);
            return 0;
        }
    }

    private void confirmDeleteAll() {
        // Haptic Feedback
        Vibrator vibrator = (Vibrator) requireContext().getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            VibrationEffect effect = null;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(effect);
            }
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.delete_all_videos_title))
                .setMessage(getString(R.string.delete_all_videos_description))
                .setPositiveButton(getString(R.string.dialog_del_confirm), (dialog, which) -> deleteAllVideos())
                .setNegativeButton(getString(R.string.universal_cancel), null)
                .show();
    }

    private void deleteAllVideos() {
        File recordsDir = new File(requireContext().getExternalFilesDir(null), Constants.RECORDING_DIRECTORY);
        if (recordsDir.exists()) {
            File[] files = recordsDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith("." + Constants.RECORDING_FILE_EXTENSION)) {
                        file.delete();
                    }
                }
            }
        }
        loadRecordsList();
    }

    // Enum for sort options
    private enum SortOption {
        LATEST_FIRST,
        OLDEST_FIRST,
        SMALLEST_FILES,
        LARGEST_FILES
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.records_menu, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        
        if (itemId == R.id.action_delete_all) {
            confirmDeleteAll();
            return true;
        } else if (itemId == R.id.action_more_options) {
            showRecordsSidebar();
            return true;
        }
        
        return super.onOptionsItemSelected(item);
    }
}
