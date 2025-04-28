package com.fadcam.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager; // Import your manager
import com.fadcam.ui.VideoItem; // Import the new VideoItem class
import com.fadcam.ui.RecordsAdapter; // Ensure adapter import is correct
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

    private static final String TAG = "RecordsFragment";

    private RecyclerView recyclerView;
    private RecordsAdapter recordsAdapter;
    private boolean isGridView = true;
    private FloatingActionButton fabToggleView;
    private FloatingActionButton fabDeleteSelected;

    // Use VideoItem and store Uris for selection
    private List<VideoItem> videoItems = new ArrayList<>();
    private List<Uri> selectedVideosUris = new ArrayList<>();

    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    private SortOption currentSortOption = SortOption.LATEST_FIRST;
    private SharedPreferencesManager sharedPreferencesManager;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_records, container, false);

        recyclerView = view.findViewById(R.id.recycler_view_records);
        fabToggleView = view.findViewById(R.id.fab_toggle_view);
        fabDeleteSelected = view.findViewById(R.id.fab_delete_selected);

        Toolbar toolbar = view.findViewById(R.id.topAppBar);
        if (getActivity() instanceof AppCompatActivity) {
            ((AppCompatActivity) getActivity()).setSupportActionBar(toolbar);
        }

        setupRecyclerView(); // Initialize RecyclerView structure
        setupFabListeners();
        updateFabIcons(); // Set initial FAB icon

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "onResume: Loading records list...");
        loadRecordsList(); // Load/Refresh list
    }

    private void setupRecyclerView() {
        setLayoutManager();
        // Adapter initialized here, list updated in loadRecordsList callback
        recordsAdapter = new RecordsAdapter(getContext(), new ArrayList<>(), this, this); // Start empty
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
        vibrate();
        isGridView = !isGridView;
        setLayoutManager();
        updateFabIcons();
    }

    private void updateFabIcons() {
        fabToggleView.setImageResource(isGridView ? R.drawable.ic_list : R.drawable.ic_grid);
    }

    // Load records from Internal or SAF based on preference
    @SuppressLint("NotifyDataSetChanged") // Suppressing for initial adapter update
    private void loadRecordsList() {
        selectedVideosUris.clear(); // Clear selection on reload
        updateDeleteButtonVisibility(); // Hide FAB

        executorService.submit(() -> {
            List<VideoItem> loadedItems;
            String storageMode = sharedPreferencesManager.getStorageMode();
            String customUriString = sharedPreferencesManager.getCustomStorageUri();

            Log.i(TAG, "Loading records. Mode: " + storageMode);

            if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode) && customUriString != null) {
                Uri treeUri = Uri.parse(customUriString);
                // Verify permission BEFORE attempting to list
                if (hasSafPermission(treeUri)) {
                    Log.d(TAG, "Loading from Custom SAF location: " + treeUri);
                    loadedItems = getSafRecordsList(treeUri);
                } else {
                    Log.e(TAG, "Permission lost for custom SAF location or invalid URI: " + customUriString);
                    requireActivity().runOnUiThread(() -> {
                        new MaterialAlertDialogBuilder(requireContext())
                                .setTitle("Permission Issue")
                                .setMessage("Could not access the custom storage location. Permission might have been revoked. Please reselect the folder in Settings or switch back to Internal Storage.")
                                .setPositiveButton("OK", null)
                                .show();
                        // Clear the UI to reflect the issue
                        if(recordsAdapter != null) recordsAdapter.updateRecords(new ArrayList<>());
                    });
                    loadedItems = new ArrayList<>(); // Empty list on permission error
                }
            } else {
                Log.d(TAG, "Loading from Internal App Storage.");
                loadedItems = getInternalRecordsList();
                if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode) && customUriString == null) {
                    Log.w(TAG, "Storage mode is Custom, but URI is null. Loading from Internal instead.");
                    // Optionally notify user or reset mode in SharedPreferences
                }
            }

            // Initial sort
            sortItems(loadedItems, currentSortOption); // Use initial sort option

            videoItems = new ArrayList<>(loadedItems); // Update the main list

            // Update UI on the main thread
            requireActivity().runOnUiThread(() -> {
                if (recordsAdapter != null) {
                    recordsAdapter.updateRecords(videoItems); // Update adapter with VideoItems
                } else {
                    Log.e(TAG, "Adapter is null when trying to update UI.");
                    setupRecyclerView(); // Try to re-setup (less ideal)
                    if (recordsAdapter != null) recordsAdapter.updateRecords(videoItems);
                }
                Log.i(TAG, "Records loaded. Mode: " + storageMode + ", Count: " + videoItems.size());
            });
        });
    }

    // --- Listing Helpers ---

    private List<VideoItem> getInternalRecordsList() {
        List<VideoItem> items = new ArrayList<>();
        File recordsDir = getContext() != null ? getContext().getExternalFilesDir(null) : null;
        if (recordsDir == null) {
            Log.e(TAG, "Could not get ExternalFilesDir for internal storage.");
            return items; // Return empty list
        }
        File fadCamDir = new File(recordsDir, Constants.RECORDING_DIRECTORY);

        if (fadCamDir.exists() && fadCamDir.isDirectory()) {
            File[] files = fadCamDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    // Check for correct extension and ignore temp files if they somehow linger
                    if (file.isFile() && file.getName().endsWith("." + Constants.RECORDING_FILE_EXTENSION) && !file.getName().startsWith("temp_")) {
                        items.add(new VideoItem(
                                Uri.fromFile(file), // Internal files use file:// URI
                                file.getName(),
                                file.length(),
                                file.lastModified()
                        ));
                    }
                }
            } else {
                Log.w(TAG, "Internal FadCam directory listFiles returned null.");
            }
        } else {
            Log.i(TAG, "Internal FadCam directory does not exist yet.");
        }
        Log.d(TAG, "Found " + items.size() + " internal records.");
        return items;
    }

    private List<VideoItem> getSafRecordsList(Uri treeUri) {
        List<VideoItem> items = new ArrayList<>();
        Context context = getContext();
        if (context == null) {
            Log.e(TAG,"Context is null in getSafRecordsList"); return items;
        }

        DocumentFile dir = DocumentFile.fromTreeUri(context, treeUri);

        if (dir != null && dir.isDirectory() && dir.canRead()) {
            try {
                for (DocumentFile file : dir.listFiles()) {
                    // Added checks for isFile and valid name
                    if (file != null && file.isFile() && file.getName() != null &&
                            (file.getName().endsWith("." + Constants.RECORDING_FILE_EXTENSION) || "video/mp4".equals(file.getType())) && // Check name or MIME
                            !file.getName().startsWith("temp_")) // Ignore temporary files
                    {
                        items.add(new VideoItem(
                                file.getUri(), // SAF files use content:// URI
                                file.getName(),
                                file.length(),
                                file.lastModified()
                        ));
                    }
                }
                Log.d(TAG, "Found " + items.size() + " SAF records in '" + dir.getName()+"'");
            } catch (Exception e) {
                Log.e(TAG, "Error listing SAF files in " + treeUri, e);
                if(getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(context, "Error reading custom location content.", Toast.LENGTH_SHORT).show();
                    });
                }
            }
        } else {
            Log.e(TAG, "Cannot read or access SAF directory: " + treeUri +
                    ", Exists=" + (dir != null && dir.exists()) +
                    ", IsDir=" + (dir != null && dir.isDirectory()) +
                    ", CanRead=" + (dir != null && dir.canRead()));
        }
        return items;
    }

    // --- Permission Check ---

    private boolean hasSafPermission(Uri treeUri) {
        Context context = getContext();
        if (context == null || treeUri == null) return false;

        try {
            // Check if we still have persistent permission
            List<UriPermission> persistedUris = context.getContentResolver().getPersistedUriPermissions();
            boolean permissionFound = false;
            for (UriPermission uriPermission : persistedUris) {
                if (uriPermission.getUri().equals(treeUri) && uriPermission.isReadPermission() && uriPermission.isWritePermission()) {
                    permissionFound = true;
                    break;
                }
            }

            if (!permissionFound) {
                Log.w(TAG,"No persisted R/W permission found for URI: " + treeUri);
                return false;
            }

            // Additionally, try a quick read check via DocumentFile
            DocumentFile docDir = DocumentFile.fromTreeUri(context, treeUri);
            if (docDir != null && docDir.canRead()) {
                return true; // Both permission entry exists and basic read check passes
            } else {
                Log.w(TAG, "Persisted permission found, but DocumentFile check failed (cannot read or null). URI: "+ treeUri);
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking SAF permission for URI: "+ treeUri, e);
            return false;
        }
    }


    // --- Click Listeners & Selection ---

    // In RecordsFragment.java

    @Override
    public void onVideoClick(VideoItem videoItem) {
        if (getActivity() == null || videoItem == null || videoItem.uri == null) {
            Log.e(TAG, "Cannot start player - activity, video item, or URI is null.");
            Toast.makeText(getContext(), "Error opening video.", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(getActivity(), VideoPlayerActivity.class);
        intent.setData(videoItem.uri); // Use setData for content:// or file:// URIs

        // *** FIX: Grant temporary read permission to the player activity ***
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        Log.d(TAG, "Starting player for URI: " + videoItem.uri + " with read permission flag.");
        try {
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start VideoPlayerActivity for URI: " + videoItem.uri, e);
            // Provide more specific feedback if possible
            if (e instanceof SecurityException) {
                Toast.makeText(getContext(), "Permission denied opening video.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(), "Could not open video player.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onVideoLongClick(VideoItem videoItem, boolean isSelected) {
        Uri videoUri = videoItem.uri;
        if (isSelected) {
            if (!selectedVideosUris.contains(videoUri)) {
                selectedVideosUris.add(videoUri);
                Log.d(TAG, "Selected: " + videoUri);
            }
        } else {
            selectedVideosUris.remove(videoUri);
            Log.d(TAG, "Deselected: " + videoUri);
        }
        updateDeleteButtonVisibility();
        vibrate();
    }

    private void updateDeleteButtonVisibility() {
        if(fabDeleteSelected != null) {
            fabDeleteSelected.setVisibility(selectedVideosUris.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }


    // --- Deletion Logic ---

    private void confirmDeleteSelected() {
        vibrate();
        if (selectedVideosUris.isEmpty()) { return; }
        int count = selectedVideosUris.size();
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getResources().getString(R.string.dialog_multi_video_del_title) + " ("+count+")")
                .setMessage(getResources().getString(R.string.dialog_multi_video_del_note))
                .setNegativeButton(getResources().getString(R.string.dialog_multi_video_del_no), null)
                .setPositiveButton(getResources().getString(R.string.dialog_multi_video_del_yes), (dialog, which) -> deleteSelectedVideos())
                .show();
    }

    private void deleteSelectedVideos() {
        List<Uri> itemsToDelete = new ArrayList<>(selectedVideosUris); // Copy to avoid issues
        if(itemsToDelete.isEmpty()){
            Log.d(TAG,"deleteSelectedVideos called but selection is empty.");
            return; // Nothing to do
        }
        selectedVideosUris.clear(); // Clear selection UI immediately
        updateDeleteButtonVisibility();

        executorService.submit(() -> {
            int successCount = 0;
            int failCount = 0;
            for (Uri uri : itemsToDelete) {
                if(uri == null) { // Added null check for URIs
                    Log.w(TAG,"Skipping null URI during deleteSelected");
                    failCount++;
                    continue;
                }
                if (deleteVideoUri(uri)) {
                    successCount++;
                } else {
                    failCount++;
                }
            }

            // --- FIX START ---
            // Create final variables to capture the counts
            final int finalSuccessCount = successCount;
            final int finalFailCount = failCount;
            // --- FIX END ---

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    String message;
                    // Use the final variables inside the lambda
                    if (finalFailCount > 0) {
                        message = finalSuccessCount + " deleted, " + finalFailCount + " failed.";
                    } else {
                        message = finalSuccessCount + " video(s) deleted.";
                    }
                    Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                    // Refresh the list to show remaining items
                    loadRecordsList();
                });
            }
        });
    }

    private void confirmDeleteAll() {
        vibrate();
        if (videoItems.isEmpty()){
            Toast.makeText(requireContext(),"No videos to delete.",Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.delete_all_videos_title))
                .setMessage(getString(R.string.delete_all_videos_description) + "\n(" + videoItems.size() + " videos will be removed)")
                .setPositiveButton(getString(R.string.dialog_del_confirm), (dialog, which) -> deleteAllVideos())
                .setNegativeButton(getString(R.string.universal_cancel), null)
                .show();
    }

    private void deleteAllVideos() {
        List<VideoItem> itemsToDelete = new ArrayList<>(videoItems); // Copy current list
        if (itemsToDelete.isEmpty()) {
            Log.d(TAG,"deleteAllVideos called but list is empty.");
            if(getActivity() != null) {
                getActivity().runOnUiThread(()-> Toast.makeText(getContext(), "No videos to delete.", Toast.LENGTH_SHORT).show());
            }
            return;
        }
        videoItems.clear(); // Clear UI list immediately
        if(recordsAdapter != null) recordsAdapter.updateRecords(videoItems); // Update adapter immediately with empty list


        executorService.submit(() -> {
            int successCount = 0;
            int failCount = 0;
            for (VideoItem item : itemsToDelete) {
                if (item != null && item.uri != null) { // Added null checks
                    if (deleteVideoUri(item.uri)) { // Use URI helper
                        successCount++;
                    } else {
                        failCount++;
                    }
                } else {
                    Log.w(TAG,"Skipping null item or item with null URI during deleteAll");
                    failCount++; // Count it as a failure to delete
                }
            }

            // --- FIX START ---
            // Create final variables to capture the counts after the loop
            final int finalSuccessCount = successCount;
            final int finalFailCount = failCount;
            // --- FIX END ---

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    String message;
                    // Use the final variables inside the lambda
                    if (finalFailCount > 0) {
                        message = "Deleted " + finalSuccessCount + ", Failed " + finalFailCount;
                    } else {
                        message = "Deleted all " + finalSuccessCount + " videos.";
                    }
                    Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                    // No need to call loadRecordsList here as the list should be empty.
                    // If there were failures, they are already reflected in the counts.
                });
            }
        });
    }

    // Helper to delete a video via URI (Internal or SAF)
    private boolean deleteVideoUri(Uri uri) {
        Context context = getContext();
        if(context == null || uri == null) return false;

        Log.d(TAG, "Attempting to delete URI: " + uri);
        try {
            if ("file".equals(uri.getScheme())) {
                File file = new File(uri.getPath());
                if (file.exists() && file.delete()) {
                    Log.d(TAG, "Deleted internal file: " + file.getName());
                    return true;
                } else {
                    Log.e(TAG, "Failed to delete internal file: " + uri.getPath() + " Exists=" + file.exists());
                    return false;
                }
            } else if ("content".equals(uri.getScheme())) {
                if (DocumentsContract.deleteDocument(context.getContentResolver(), uri)) {
                    Log.d(TAG, "Deleted SAF document: " + uri);
                    return true;
                } else {
                    // Check if it was already deleted or if it's really an error
                    DocumentFile doc = DocumentFile.fromSingleUri(context, uri);
                    if(doc == null || !doc.exists()){
                        Log.w(TAG,"deleteDocument returned false, but file doesn't exist. Treating as success. URI: "+ uri);
                        return true;
                    } else {
                        Log.e(TAG, "Failed to delete SAF document (deleteDocument returned false): " + uri);
                        return false;
                    }
                }
            } else {
                Log.w(TAG, "Cannot delete URI with unknown scheme: " + uri.getScheme());
                return false;
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException deleting URI: " + uri, e);
            if(getActivity() != null) getActivity().runOnUiThread(() -> Toast.makeText(context, "Permission denied deleting file.", Toast.LENGTH_SHORT).show());
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Exception deleting URI: " + uri, e);
            return false;
        }
    }

    // --- Options Menu & Sorting ---

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.records_menu, menu);
        // We handle the "Delete All" option inside the bottom sheet now.
        MenuItem deleteAllItem = menu.findItem(R.id.action_delete_all);
        if (deleteAllItem != null) {
            deleteAllItem.setVisible(false); // Hide from overflow menu
        }
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.action_more_options) {
            showRecordsSidebar();
            return true;
        }
        // R.id.action_delete_all is handled in the bottom sheet now
        return super.onOptionsItemSelected(item);
    }


    private void showRecordsSidebar() {
        if (getContext() == null) return;

        View bottomSheetView = getLayoutInflater().inflate(R.layout.bottom_sheet_records_options, null);
        BottomSheetDialog bottomSheetDialog = new BottomSheetDialog(requireContext());
        bottomSheetDialog.setContentView(bottomSheetView);

        RadioGroup sortOptionsGroup = bottomSheetView.findViewById(R.id.sort_options_group);
        LinearLayout deleteAllOption = bottomSheetView.findViewById(R.id.option_delete_all);

        // Pre-select current sort option
        if (sortOptionsGroup != null) {
            switch (currentSortOption) {
                case LATEST_FIRST: sortOptionsGroup.check(R.id.sort_latest); break;
                case OLDEST_FIRST: sortOptionsGroup.check(R.id.sort_oldest); break;
                case SMALLEST_FILES: sortOptionsGroup.check(R.id.sort_smallest); break;
                case LARGEST_FILES: sortOptionsGroup.check(R.id.sort_largest); break;
            }
            Log.d(TAG,"Sidebar sort options pre-checked: "+currentSortOption);

            sortOptionsGroup.setOnCheckedChangeListener((group, checkedId) -> {
                SortOption newSortOption = currentSortOption; // Start assuming no change
                if (checkedId == R.id.sort_latest) newSortOption = SortOption.LATEST_FIRST;
                else if (checkedId == R.id.sort_oldest) newSortOption = SortOption.OLDEST_FIRST;
                else if (checkedId == R.id.sort_smallest) newSortOption = SortOption.SMALLEST_FILES;
                else if (checkedId == R.id.sort_largest) newSortOption = SortOption.LARGEST_FILES;

                if (newSortOption != currentSortOption) {
                    Log.i(TAG,"Sort option changed to: "+ newSortOption);
                    currentSortOption = newSortOption;
                    performVideoSort(); // Call the sorting method
                } else {
                    Log.d(TAG,"Sort option clicked, but no change: "+currentSortOption);
                }
                bottomSheetDialog.dismiss();
            });
        }

        if (deleteAllOption != null) {
            deleteAllOption.setOnClickListener(v -> {
                bottomSheetDialog.dismiss();
                confirmDeleteAll(); // Call delete all confirmation
            });
        }

        bottomSheetDialog.show();
    }


    // Updated sorting logic to work with List<VideoItem>
    private void performVideoSort() {
        if (videoItems == null || videoItems.isEmpty()) {
            Log.w(TAG, "No items to sort.");
            return;
        }
        sortItems(videoItems, currentSortOption);

        // Update adapter on the main thread
        if (getActivity() != null && recordsAdapter != null) {
            getActivity().runOnUiThread(() -> {
                recordsAdapter.updateRecords(videoItems); // Update with the sorted list
                recyclerView.scrollToPosition(0); // Scroll to top after sorting
                Log.i(TAG, "Video list sorted by: " + currentSortOption);
            });
        }
    }

    // Centralized sorting logic
    private void sortItems(List<VideoItem> items, SortOption sortOption) {
        if(items == null) return;
        try {
            switch (sortOption) {
                case LATEST_FIRST:
                    Collections.sort(items, (a, b) -> Long.compare(b.lastModified, a.lastModified));
                    break;
                case OLDEST_FIRST:
                    Collections.sort(items, (a, b) -> Long.compare(a.lastModified, b.lastModified));
                    break;
                case SMALLEST_FILES:
                    Collections.sort(items, Comparator.comparingLong(item -> item.size));
                    break;
                case LARGEST_FILES:
                    Collections.sort(items, (a, b) -> Long.compare(b.size, a.size));
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during sorting", e);
        }
    }

    // Enum for sort options
    private enum SortOption { LATEST_FIRST, OLDEST_FIRST, SMALLEST_FILES, LARGEST_FILES }

    // --- Utility ---

    private void vibrate() {
        Context context = getContext();
        if(context == null) return;
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                //noinspection deprecation
                vibrator.vibrate(50);
            }
        }
    }
}