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
import android.widget.ImageView;    // Import ImageView
import android.widget.TextView;     // Import TextView

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog; // Import AlertDialog
import android.widget.ProgressBar;
import androidx.appcompat.widget.Toolbar; // Import Toolbar

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import com.fadcam.Utils;
import com.fadcam.ui.VideoItem; // Import the new VideoItem class
import com.fadcam.ui.RecordsAdapter; // Ensure adapter import is correct
import com.google.android.material.appbar.MaterialToolbar;
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
import androidx.recyclerview.widget.RecyclerView;
import android.graphics.Rect;
import android.view.View;

import android.content.BroadcastReceiver;  // ** ADD **
import android.content.Context;          // ** ADD **
import android.content.Intent;          // ** ADD **
import android.content.IntentFilter;     // ** ADD **
import androidx.localbroadcastmanager.content.LocalBroadcastManager; // Use this for app-internal
import androidx.core.content.ContextCompat;  // ** ADD **
import androidx.core.content.ContextCompat; // Use ContextCompat for receiver reg
import android.content.IntentFilter;

import java.util.Set; // Need Set import
import java.util.HashSet; // Need HashSet import

public class RecordsFragment extends Fragment implements
        RecordsAdapter.OnVideoClickListener,
        RecordsAdapter.OnVideoLongClickListener,
        RecordActionListener {

    private BroadcastReceiver recordingCompleteReceiver; // ** ADD field for the receiver **
    private boolean isReceiverRegistered = false; // Track registration status
    // ** NEW: Fields for storage change receiver **
    private BroadcastReceiver storageLocationChangedReceiver;
    private boolean isStorageReceiverRegistered = false;
    // ** NEW: Set to track URIs currently being processed **
    private Set<Uri> currentlyProcessingUris = new HashSet<>();
    private static final String TAG = "RecordsFragment";
    private AlertDialog progressDialog; // Field to hold the dialog
    private RecyclerView recyclerView;
    private LinearLayout emptyStateContainer; // Add field for the empty state layout
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
    private SpacesItemDecoration itemDecoration; // Keep a reference
    private ProgressBar loadingIndicator; // *** ADD field for ProgressBar ***

    private MaterialToolbar toolbar;
    private CharSequence originalToolbarTitle;

    // --- Selection State ---
    private boolean isInSelectionMode = false;
    private List<Uri> selectedUris = new ArrayList<>(); // Manage the actual selected URIs here
    // *** IMPLEMENT THE NEW INTERFACE METHOD ***
    @Override
    public void onDeletionFinishedCheckEmptyState() {
        Log.d(TAG, "Adapter signaled deletion finished. Checking empty state...");
        // This method is called AFTER the adapter has removed the item
        // and notified itself. Now, we update the Fragment's overall UI visibility.
        if(getView() != null){ // Ensure view is available
            getActivity().runOnUiThread(this::updateUiVisibility); // Use the existing helper
        } else {
            Log.w(TAG,"onDeletionFinishedCheckEmptyState called but view is null.");
        }
    }

    // *** Register in onStart ***
    @Override
    public void onStart() {
        super.onStart();
        registerRecordingCompleteReceiver(); // Call registration helper
        registerStorageLocationChangedReceiver(); // ** ADD registration for new receiver **
        registerProcessingStateReceivers(); // ** ADD **
    }

    // *** Unregister in onStop ***
    @Override
    public void onStop() {
        super.onStop();
        unregisterRecordingCompleteReceiver(); // Call unregistration helper
        unregisterStorageLocationChangedReceiver(); // ** ADD unregistration for new receiver **
        unregisterProcessingStateReceivers(); // ** ADD **
    }

    // ** NEW: Method to register the storage location change receiver **
    private void registerStorageLocationChangedReceiver() {
        if (!isStorageReceiverRegistered && getContext() != null) {
            if (storageLocationChangedReceiver == null) {
                storageLocationChangedReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (intent != null && Constants.ACTION_STORAGE_LOCATION_CHANGED.equals(intent.getAction())) {
                            Log.i(TAG, "Received ACTION_STORAGE_LOCATION_CHANGED broadcast. Refreshing records list...");
                            // ** Simply reload the list. It will use the *new* preference. **
                            loadRecordsList();
                        }
                    }
                };
            }
            IntentFilter filter = new IntentFilter(Constants.ACTION_STORAGE_LOCATION_CHANGED);
            ContextCompat.registerReceiver(requireContext(), storageLocationChangedReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            isStorageReceiverRegistered = true;
            Log.d(TAG, "ACTION_STORAGE_LOCATION_CHANGED receiver registered.");
        }
    }

    // ** NEW: Method to unregister the storage location change receiver **
    private void unregisterStorageLocationChangedReceiver() {
        if (isStorageReceiverRegistered && storageLocationChangedReceiver != null && getContext() != null) {
            try {
                requireContext().unregisterReceiver(storageLocationChangedReceiver);
                isStorageReceiverRegistered = false;
                Log.d(TAG, "ACTION_STORAGE_LOCATION_CHANGED receiver unregistered.");
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Attempted to unregister storage receiver but it wasn't registered?");
                isStorageReceiverRegistered = false;
            }
        }
        // Optional: Nullify receiver instance here if desired
        // storageLocationChangedReceiver = null;
    }

    // --- Method to register the new receiver ---
    private void registerRecordingCompleteReceiver() {
        // Prevent double registration
        if (isReceiverRegistered || getContext() == null) return;

        if (recordingCompleteReceiver == null) {
            recordingCompleteReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    // Ensure we are attached and context is valid
                    if (intent == null || !isAdded() || getContext() == null) {
                        return;
                    }

                    if (Constants.ACTION_RECORDING_COMPLETE.equals(intent.getAction())) {
                        boolean success = intent.getBooleanExtra(Constants.EXTRA_RECORDING_SUCCESS, false);
                        String uriString = intent.getStringExtra(Constants.EXTRA_RECORDING_URI_STRING);
                        Log.i(TAG, "Received ACTION_RECORDING_COMPLETE broadcast. Success: " + success + ", URI: " + uriString);

                        // ** Refresh the list **
                        // The received URI could be used for highlighting, but reload is simpler/safer.
                        Log.d(TAG,"Refreshing records list due to broadcast.");
                        loadRecordsList();
                    }
                }
            };
        }
        IntentFilter filter = new IntentFilter(Constants.ACTION_RECORDING_COMPLETE);
        // Register using ContextCompat for modern Android (ensures correct flags)
        ContextCompat.registerReceiver(requireContext(), recordingCompleteReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        isReceiverRegistered = true; // Mark as registered
        Log.d(TAG, "ACTION_RECORDING_COMPLETE receiver registered.");
    }

    // --- Method to unregister the new receiver ---
    private void unregisterRecordingCompleteReceiver() {
        if (isReceiverRegistered && recordingCompleteReceiver != null && getContext() != null) {
            try {
                requireContext().unregisterReceiver(recordingCompleteReceiver);
                isReceiverRegistered = false; // Mark as unregistered
                Log.d(TAG, "ACTION_RECORDING_COMPLETE receiver unregistered.");
            } catch (IllegalArgumentException e){
                Log.w(TAG,"Attempted to unregister recording complete receiver but it wasn't registered?");
                isReceiverRegistered = false; // Ensure flag is reset even on error
            }
        }
        // Don't nullify receiver here, just ensure it's not registered
        // recordingCompleteReceiver = null;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Now perform other fragment setup
        setHasOptionsMenu(true); // Enable options menu for this fragment

        // Initialize SharedPreferencesManager (can be done here or later if needed)
        // If it's definitely needed before onCreateView, initialize here.
        // Ensure context is available - using requireContext() is safe within/after onCreate.
        if (sharedPreferencesManager == null) {
            try {
                sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());
                Log.d(TAG, "SharedPreferencesManager initialized in onCreate.");
            } catch (IllegalStateException e) {
                Log.e(TAG,"Error getting context in onCreate: Fragment not attached?", e);
                // Handle error appropriately - maybe defer init to onViewCreated?
            }
        }

        // Initialize ExecutorService if needed early
        if (executorService == null || executorService.isShutdown()) {
            executorService = Executors.newSingleThreadExecutor();
            Log.d(TAG, "ExecutorService initialized in onCreate.");
        }
        // --- Back Press Handling ---
        requireActivity().getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (isInSelectionMode) exitSelectionMode();
                else { if (isEnabled()) { setEnabled(false); requireActivity().onBackPressed(); }}
            }
        });
        // *** Other initialization code specific to the fragment's creation ***
        // (like setting arguments, retaining instance, etc., if applicable)
    }


    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Log.d(TAG, "onCreateView: Inflating layout R.layout.fragment_records");
        // --- ONLY INFLATE AND RETURN ---
        View view = inflater.inflate(R.layout.fragment_records, container, false);
        return view;
        // --- REMOVE findViewByid and setup calls from here ---
    }

    // Inside RecordsFragment.java

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Log.d(TAG, "onViewCreated: View hierarchy created. Finding views and setting up.");

        // --- Perform ALL findViewById calls here ---
        recyclerView = view.findViewById(R.id.recycler_view_records);
        fabToggleView = view.findViewById(R.id.fab_toggle_view);
        fabDeleteSelected = view.findViewById(R.id.fab_delete_selected);
        emptyStateContainer = view.findViewById(R.id.empty_state_container);
        loadingIndicator = view.findViewById(R.id.loading_indicator);
        toolbar = view.findViewById(R.id.topAppBar);

        // --- Basic safety checks ---
        if (recyclerView == null || fabToggleView == null || fabDeleteSelected == null || toolbar == null) {
            Log.e(TAG, "onViewCreated: CRITICAL - One or more essential views not found!");
            // You might want to show an error message or prevent further setup
            Toast.makeText(getContext(),"Layout Error", Toast.LENGTH_SHORT).show();
            return;
        }


        // --- Setup Components using the found views ---
        if (getActivity() instanceof AppCompatActivity) {
            ((AppCompatActivity) getActivity()).setSupportActionBar(toolbar);
            if (originalToolbarTitle == null) { originalToolbarTitle = toolbar.getTitle(); }
            if (originalToolbarTitle == null || originalToolbarTitle.length() == 0){ originalToolbarTitle = getString(R.string.records_title); } // Use default/string res
            // Initial toolbar state might need to be set depending on selection mode persistence logic
            updateUiForSelectionMode(); // Set initial toolbar based on mode
        }

        // Initialize adapter and RecyclerView
        setupRecyclerView(); // Safe to call now

        // Setup FAB listeners
        setupFabListeners(); // Safe to call now

        // Set initial FAB icons
        updateFabIcons();

        // --- Initial Data Load ---
        // Load data *after* adapter is set up
        if (videoItems == null || videoItems.isEmpty()) { // videoItems might be retained across config changes
            if(videoItems == null) videoItems = new ArrayList<>(); // Ensure list exists
            Log.d(TAG, "onViewCreated: No existing data, initiating loadRecordsList.");
            loadRecordsList();
        } else {
            Log.d(TAG, "onViewCreated: Existing data found ("+videoItems.size()+" items), updating UI visibility.");
            // If data exists (e.g., fragment recreated), update adapter & UI
            if(recordsAdapter != null) recordsAdapter.updateRecords(videoItems); // Make sure adapter has the data
            updateUiVisibility();
        }
    } // End onViewCreated

    @Override
    public void onResume() {
        super.onResume();
        // ** REMOVED loadRecordsList() from here **
        Log.d(TAG, "onResume: Fragment resumed. Data loaded state: " + (!videoItems.isEmpty()));
        // If you implement manual refresh later, you might re-enable it here or check timestamps
    }
    @Override
    public void onSaveToGalleryStarted(String fileName) {
        if (getContext() == null) return; // Check context
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss(); // Dismiss any previous dialog
        }

        // Create and show the progress dialog
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getContext());
        LayoutInflater inflater = LayoutInflater.from(getContext());
        View dialogView = inflater.inflate(R.layout.dialog_progress, null);

        TextView progressText = dialogView.findViewById(R.id.progress_text);
        if (progressText != null) {
            progressText.setText("Saving '" + fileName + "'..."); // Indicate which file
        }

        builder.setView(dialogView);
        builder.setCancelable(false); // Prevent user from dismissing during save

        progressDialog = builder.create();
        progressDialog.show();
        Log.d(TAG, "Showing progress dialog for saving: " + fileName);
    }

    @Override
    public void onSaveToGalleryFinished(boolean success, String message, Uri outputUri) {
        // Dismiss the dialog
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
            progressDialog = null; // Clear reference
        }
        Log.d(TAG, "Save finished. Success: " + success + ", Message: " + message);

        // Show result Toast
        if (getContext() != null) {
            Toast.makeText(getContext(), message, success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
        }

        // Optional: Refresh the list if needed, e.g., if saving could somehow affect displayed items,
        // though usually not necessary for 'Save to Gallery' which is a copy operation.
        // if (success) { loadRecordsList(); }
    }

    // --- End Implementation of RecordActionListener ---

// ** In RecordsFragment.java **

    private void setupRecyclerView() {
        if (getContext() == null) {
            Log.e(TAG,"Cannot setup RecyclerView, context is null.");
            return;
        }
        if(recyclerView == null){
            Log.e(TAG, "RecyclerView is null in setupRecyclerView");
            return;
        }

        // Set the layout manager (Grid or Linear)
        setLayoutManager(); // Uses the isGridView flag

        // Ensure necessary dependencies are available
        if (executorService == null || executorService.isShutdown()) {
            executorService = Executors.newSingleThreadExecutor();
        }
        if (sharedPreferencesManager == null) {
            sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());
        }

        // Create the adapter instance, passing all dependencies
        // Note: videoItems might be empty here initially, adapter handles that.
        // The 'this' refers to RecordsFragment implementing the necessary listener interfaces.
        recordsAdapter = new RecordsAdapter(
                getContext(),           // Context
                videoItems,             // Initial (likely empty) data list
                executorService,        // Background thread executor
                sharedPreferencesManager, // Preferences for 'opened' status
                this,                   // OnVideoClickListener
                this,                   // OnVideoLongClickListener
                this                    // RecordActionListener
        );

        // Set the adapter on the RecyclerView
        recyclerView.setAdapter(recordsAdapter);
        Log.d(TAG, "RecyclerView setup complete. Adapter assigned.");
    }

    // Need to update the Adapter constructor signature check


    // Update Adapter Constructor call in setupRecyclerView
    // Ensure constructor exists and matches this signature:
    // RecordsAdapter(Context, List, ExecutorService, SharedPreferencesManager, OnClick, OnLongClick, ActionListener)
    // **Add SharedPreferencesManager parameter to RecordsAdapter constructor:**


    // Keep your existing setLayoutManager method which switches between grid/linear
    private void setLayoutManager() {
        boolean currentlyGrid = (recyclerView.getLayoutManager() instanceof GridLayoutManager);

        // Only change if the desired state is different or no layout manager set yet
        if (isGridView != currentlyGrid || recyclerView.getLayoutManager() == null) {
            RecyclerView.LayoutManager layoutManager = isGridView ?
                    new GridLayoutManager(getContext(), 2) : // 2 columns for grid
                    new LinearLayoutManager(getContext());
            recyclerView.setLayoutManager(layoutManager);
            Log.d(TAG, "LayoutManager set to: " + (isGridView ? "GridLayout" : "LinearLayout"));
        }
    }
    // Inner class for simple ItemDecoration
    public static class SpacesItemDecoration extends RecyclerView.ItemDecoration {
        private final int space;

        public SpacesItemDecoration(int space) {
            this.space = space;
        }

        @Override
        public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                                   @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {

            // Apply consistent spacing to all sides for simplicity here
            // More complex logic needed for perfect grid edge spacing
            outRect.left = space / 2;
            outRect.right = space / 2;
            outRect.bottom = space;
            outRect.top = 0; // Add space mostly at the bottom and sides


            // Example for more complex grid spacing (adjust as needed):
            /*
            int position = parent.getChildAdapterPosition(view); // item position
            RecyclerView.LayoutManager layoutManager = parent.getLayoutManager();

            if (layoutManager instanceof GridLayoutManager) {
                 GridLayoutManager gridLayoutManager = (GridLayoutManager) layoutManager;
                 int spanCount = gridLayoutManager.getSpanCount();
                 GridLayoutManager.LayoutParams lp = (GridLayoutManager.LayoutParams) view.getLayoutParams();
                 int column = lp.getSpanIndex(); // item column

                 outRect.left = space - column * space / spanCount;
                 outRect.right = (column + 1) * space / spanCount;
                 if (position < spanCount) { // top edge
                    outRect.top = space;
                }
                outRect.bottom = space; // item bottom
             } else { // For LinearLayoutManager
                 if (position > 0) {
                     outRect.top = space;
                 }
             }
             */
        }
    }
    private void setupFabListeners() {
        Log.d(TAG,"Setting up FAB listeners.");
        // Ensure FABs are not null before setting listeners
        if (fabToggleView != null) {
            fabToggleView.setOnClickListener(v -> {
                Log.d(TAG, "fabToggleView clicked!");
                toggleViewMode();
            });
            Log.d(TAG,"FAB Toggle listener set.");
        } else {
            Log.e(TAG, "fabToggleView is null in setupFabListeners!");
        }

        if (fabDeleteSelected != null) {
            fabDeleteSelected.setOnClickListener(v -> {
                // *** ADD LOGGING to confirm click registration ***
                Log.d(TAG, "fabDeleteSelected CLICKED!");
                // Check fragment state and context just before acting
                if (!isAdded() || getContext() == null) {
                    Log.e(TAG, "fabDeleteSelected clicked but fragment not ready!");
                    return;
                }
                confirmDeleteSelected(); // Call the confirmation dialog method
            });
            Log.d(TAG,"FAB Delete listener set.");
        } else {
            Log.w(TAG, "fabDeleteSelected is null in setupFabListeners (Might be initially GONE).");
        }
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


// ** In RecordsFragment.java **

    @SuppressLint("NotifyDataSetChanged")
    private void loadRecordsList() {
        Log.d(TAG, "loadRecordsList: Starting.");
        // 0. PREPARE UI FOR LOADING (Show spinner, hide content)
        if (getView() == null) { // Extra safety check: ensure view is available
            Log.e(TAG,"loadRecordsList called but fragment view is null.");
            return;
        }

        // Show loading, hide others - Perform UI updates immediately on the current thread
        if (loadingIndicator != null) loadingIndicator.setVisibility(View.VISIBLE);
        if (recyclerView != null) recyclerView.setVisibility(View.GONE);
        if (emptyStateContainer != null) emptyStateContainer.setVisibility(View.GONE);
        if (fabDeleteSelected != null) fabDeleteSelected.setVisibility(View.GONE); // Hide delete fab during load
        selectedVideosUris.clear(); // Clear any existing selection state

        // Ensure executor service is ready
        if (executorService == null || executorService.isShutdown()) {
            Log.w(TAG, "loadRecordsList: ExecutorService was null or shutdown, re-initializing.");
            executorService = Executors.newSingleThreadExecutor();
        }

        executorService.submit(() -> {
            Log.d(TAG, "loadRecordsList (Background Thread): Started loading files.");
            // 1. Load from Primary Location (Internal or SAF)
            List<VideoItem> primaryItems; // Declare list
            String storageMode = sharedPreferencesManager.getStorageMode();
            String customUriString = sharedPreferencesManager.getCustomStorageUri();
            Log.d(TAG, "loadRecordsList (Background Thread): Mode="+storageMode+", URI="+customUriString);

            if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode) && customUriString != null) {
                Uri treeUri = null;
                boolean isValidUri = false;
                try {
                    treeUri = Uri.parse(customUriString);
                    isValidUri = true;
                } catch (Exception e) { Log.e(TAG,"Error parsing custom storage URI string", e);}

                if (isValidUri && hasSafPermission(treeUri)) {
                    primaryItems = getSafRecordsList(treeUri);
                } else {
                    // Handle permission or invalid URI error (show toast/dialog later on UI thread if needed)
                    if(isValidUri) Log.e(TAG, "Permission/Read error for custom SAF location: " + customUriString);
                    else Log.e(TAG,"Invalid Custom URI string: " + customUriString);
                    primaryItems = new ArrayList<>();
                }
            } else { // Internal Storage mode or Custom mode with null URI
                primaryItems = getInternalRecordsList();
            }

            // 2. Load from Temp Cache Location
            List<VideoItem> tempItems = getTempCacheRecordsList();

            // 3. Combine Lists
            List<VideoItem> combinedItems = combineVideoLists(primaryItems, tempItems);

            // 4. Sort the combined list
            sortItems(combinedItems, currentSortOption);

            // 5. Create final list copy for the UI thread
            final List<VideoItem> finalItems = new ArrayList<>(combinedItems);
            Log.d(TAG, "loadRecordsList (Background Thread): Loading complete. Total items: " + finalItems.size());


            // 6. UPDATE UI ON COMPLETION (Post back to Main Thread)
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    Log.d(TAG, "loadRecordsList (UI Thread): Updating UI.");
                    videoItems = finalItems; // Update the main list reference in the fragment

                    // Update adapter data FIRST
                    if (recordsAdapter != null) {
                        // Clear adapter's processing state before feeding new data
                        currentlyProcessingUris.clear(); // Also clear fragment's tracker on full reload
                        recordsAdapter.updateProcessingUris(currentlyProcessingUris);
                        recordsAdapter.updateRecords(videoItems);
                        Log.d(TAG, "loadRecordsList (UI Thread): Adapter updated.");
                    } else {
                        Log.e(TAG, "Adapter was null during UI update.");
                        // Try to set it up again if it became null somehow
                        setupRecyclerView();
                        if (recordsAdapter != null) recordsAdapter.updateRecords(videoItems);
                    }

                    // Then update visibility based on the final data
                    updateUiVisibility();

                    // Hide loading indicator LAST
                    if (loadingIndicator != null) loadingIndicator.setVisibility(View.GONE);

                    Log.i(TAG, "Records list updated. Total Count: " + videoItems.size());
                });
            } else {
                Log.e(TAG, "Activity was null when trying to update UI post-load.");
            }
        }); // End of submit lambda
    } // End of loadRecordsList


    // Ensure updateUiVisibility is defined correctly:
    private void updateUiVisibility() {
        if (getView() == null) return; // Check if fragment view exists

        // Get the current count directly from the adapter if possible, or fragment's list
        boolean isEmpty = (recordsAdapter != null) ?
                recordsAdapter.getItemCount() == 0 :
                videoItems.isEmpty();

        Log.d(TAG, "updateUiVisibility called. Is list empty? " + isEmpty);

        if (isEmpty) {
            if (recyclerView != null) recyclerView.setVisibility(View.GONE);
            if (emptyStateContainer != null) emptyStateContainer.setVisibility(View.VISIBLE);
            Log.d(TAG,"Showing empty state.");
        } else {
            if (emptyStateContainer != null) emptyStateContainer.setVisibility(View.GONE);
            if (recyclerView != null) recyclerView.setVisibility(View.VISIBLE);
            Log.d(TAG,"Showing recycler view.");
        }
        // Ensure loading indicator is hidden
        if (loadingIndicator != null && loadingIndicator.getVisibility() == View.VISIBLE) {
            loadingIndicator.setVisibility(View.GONE);
        }
    }

    // --- Listing Helpers ---

    // Helper to combine lists, avoiding duplicates
    private List<VideoItem> combineVideoLists(List<VideoItem> primary, List<VideoItem> temp) {
        List<VideoItem> combined = new ArrayList<>();
        java.util.Set<Uri> existingUris = new java.util.HashSet<>();

        for (VideoItem item : primary) {
            if (item != null && item.uri != null && existingUris.add(item.uri)) {
                combined.add(item);
            }
        }
        for (VideoItem item : temp) {
            if (item != null && item.uri != null && existingUris.add(item.uri)) {
                combined.add(item);
            }
        }
        return combined;
    }

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
                        long lastModifiedMeta = file.lastModified();
                        long timestampFromFile = Utils.parseTimestampFromFilename(file.getName());
                        long finalTimestamp = lastModifiedMeta; // Default to metadata

                        if (lastModifiedMeta <= 0 && timestampFromFile > 0) { // If metadata is bad, use filename timestamp
                            finalTimestamp = timestampFromFile;
                            Log.d(TAG,"Internal: Used filename timestamp for " + file.getName());
                        } else if (lastModifiedMeta <= 0 && timestampFromFile <= 0) {
                            Log.w(TAG,"Internal: Both metadata and filename parse failed for " + file.getName() + ", using current time as fallback.");
                            finalTimestamp = System.currentTimeMillis(); // Fallback needed
                        } else {
                            // Use metadata by default if valid
                            // Log.v(TAG,"Internal: Using metadata timestamp for " + file.getName());
                        }
                        Uri uri = Uri.fromFile(file);
                        Log.d(TAG, "Internal Added: " + file.getName() + " | Using Timestamp: " + finalTimestamp);
                        items.add(new VideoItem(uri, file.getName(), file.length(), finalTimestamp));
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
                        long lastModifiedMeta = file.lastModified();
                        String name = file.getName();
                        long timestampFromFile = Utils.parseTimestampFromFilename(name);
                        long finalTimestamp = lastModifiedMeta; // Default to metadata

                        if (lastModifiedMeta <= 0 && timestampFromFile > 0) { // If metadata is bad (often 0 for SAF), use filename timestamp
                            finalTimestamp = timestampFromFile;
                            Log.d(TAG,"SAF: Used filename timestamp for " + name);
                        } else if (lastModifiedMeta <= 0 && timestampFromFile <= 0) {
                            Log.w(TAG,"SAF: Both metadata and filename parse failed for " + name + ", using current time as fallback.");
                            finalTimestamp = System.currentTimeMillis(); // Fallback needed
                        } else {
                            // Use metadata by default if valid (it might be valid sometimes)
                            // Log.v(TAG,"SAF: Using metadata timestamp for " + name);
                        }

                        Uri uri = file.getUri();
                        Log.d(TAG, "SAF Added: " + name + " | Using Timestamp: " + finalTimestamp);
                        items.add(new VideoItem(uri, name, file.length(), finalTimestamp));
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
        if (videoItem == null || videoItem.uri == null) return;

        if (isInSelectionMode) {
            // Mode ACTIVE: Click toggles selection
            Log.d(TAG,"Fragment onVideoClick: Toggling selection for " + videoItem.displayName);
            toggleSelection(videoItem.uri); // Toggle the item
        } else {
            // Mode INACTIVE: Click plays video
            Log.d(TAG,"Fragment onVideoClick: Playing video " + videoItem.displayName);
            String uriString = videoItem.uri.toString();
            sharedPreferencesManager.addOpenedVideoUri(uriString);
            if(recordsAdapter != null){ int pos = recordsAdapter.findPositionByUri(videoItem.uri); if(pos!=-1) recordsAdapter.notifyItemChanged(pos);}
            Intent intent = new Intent(getActivity(), VideoPlayerActivity.class); intent.setData(videoItem.uri); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(intent); } catch (Exception e) { Log.e(TAG, "Failed to start player", e); Toast.makeText(getContext(), "Error opening video", Toast.LENGTH_SHORT).show();}
        }
    }

    // *** NEW: Receiver for Processing Start/End ***
    private BroadcastReceiver processingStateReceiver;
    private boolean isProcessingReceiverRegistered = false;

    private void registerProcessingStateReceivers() {
        if (!isProcessingReceiverRegistered && getContext() != null) {
            processingStateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent != null && intent.getAction() != null) {
                        String uriString = intent.getStringExtra(Constants.EXTRA_PROCESSING_URI_STRING);
                        if (uriString == null) { Log.w(TAG, "Received processing broadcast without URI."); return; }
                        Uri fileUri = Uri.parse(uriString);
                        boolean changed = false;

                        if (Constants.ACTION_PROCESSING_STARTED.equals(intent.getAction())) {
                            Log.i(TAG, "Processing started for: " + fileUri);
                            changed = currentlyProcessingUris.add(fileUri);
                        } else if (Constants.ACTION_PROCESSING_FINISHED.equals(intent.getAction())) {
                            Log.i(TAG, "Processing finished for: " + fileUri);
                            changed = currentlyProcessingUris.remove(fileUri);
                            // Trigger a full list reload AFTER processing finishes to show the result
                            // (as the file state on disk has changed).
                            loadRecordsList();
                            return; // Exit here, loadRecordsList handles the final UI update
                        }

                        // If processing started/stopped and the set changed, update adapter state
                        if (changed && recordsAdapter != null) {
                            Log.d(TAG, "Updating adapter processing state. Processing count: " + currentlyProcessingUris.size());
                            recordsAdapter.updateProcessingUris(currentlyProcessingUris);
                            // Note: updateProcessingUris in adapter calls notifyDataSetChanged,
                            // which might be slightly inefficient but ensures consistency for now.
                            // A targeted notifyItemChanged would be better if performance becomes an issue.
                        }
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Constants.ACTION_PROCESSING_STARTED);
            filter.addAction(Constants.ACTION_PROCESSING_FINISHED);
            ContextCompat.registerReceiver(requireContext(), processingStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            isProcessingReceiverRegistered = true;
            Log.d(TAG, "Processing state receivers registered.");
        }
    }

    private void unregisterProcessingStateReceivers() {
        if (isProcessingReceiverRegistered && processingStateReceiver != null && getContext() != null) {
            try { requireContext().unregisterReceiver(processingStateReceiver); } catch (Exception e) { /* ignore */}
            isProcessingReceiverRegistered = false;
            Log.d(TAG, "Processing state receivers unregistered.");
        }
    }

    @Override
    public void onVideoLongClick(VideoItem videoItem, boolean isSelectedIntention) { // isSelectedIntention is less relevant here
        if (videoItem == null || videoItem.uri == null) return;
        Log.d(TAG,"Fragment onVideoLongClick: " + videoItem.displayName);

        if (!isInSelectionMode) {
            enterSelectionMode(); // Enter selection mode on the FIRST long press
        }
        // Whether entering or already in, toggle the long-pressed item
        toggleSelection(videoItem.uri);
        vibrate(); // Haptic feedback
    }


    // --- Selection Management ---
    private void enterSelectionMode() {
        if (isInSelectionMode) return;
        isInSelectionMode = true;
        selectedUris.clear(); // Start fresh
        Log.i(TAG, ">>> Entering Selection Mode <<<");
        if (recordsAdapter != null) recordsAdapter.setSelectionModeActive(true, selectedUris);
        updateUiForSelectionMode(); // Update toolbar/FABs
    }

    private void exitSelectionMode() {
        if (!isInSelectionMode) return;
        isInSelectionMode = false;
        selectedUris.clear();
        Log.i(TAG, "<<< Exiting Selection Mode >>>");
        if (recordsAdapter != null) recordsAdapter.setSelectionModeActive(false, selectedUris);
        updateUiForSelectionMode(); // Update toolbar/FABs
    }

    private void updateDeleteButtonVisibility() {
        if(fabDeleteSelected != null) {
            fabDeleteSelected.setVisibility(selectedVideosUris.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }


    private void toggleSelection(Uri videoUri) {
        if (!isInSelectionMode) { // Should theoretically not happen if called correctly
            Log.w(TAG,"toggleSelection called but not in selection mode!");
            enterSelectionMode(); // Enter mode if accidentally called outside
            // return; // Or return after entering? Let's proceed to select/deselect.
        }

        boolean changed = false;
        if (selectedUris.contains(videoUri)) {
            selectedUris.remove(videoUri); changed = true;
            Log.d(TAG,"Deselected URI: "+videoUri);
        } else {
            selectedUris.add(videoUri); changed = true;
            Log.d(TAG,"Selected URI: "+videoUri);
        }

        if (changed) {
            // ** CRITICAL: Update the adapter with the new selection list **
            if(recordsAdapter != null) recordsAdapter.setSelectionModeActive(true, selectedUris);
            updateUiForSelectionMode(); // Update toolbar count and FAB visibility
        }
        // Exit selection mode if list becomes empty again? User preference.
        // if(selectedUris.isEmpty()){ exitSelectionMode(); }
    }

    // --- UI Updates ---
    /** Updates Toolbar, FABs based on whether selection mode is active */
    private void updateUiForSelectionMode() {
        if (!isAdded() || toolbar == null || getActivity() == null) { Log.w(TAG,"Cannot update selection UI - not ready"); return;}

        if (isInSelectionMode) {
            int count = selectedUris.size();
            toolbar.setTitle(count > 0 ? count + " selected" : "Select items");
            toolbar.setNavigationIcon(R.drawable.ic_close); // Ensure you have ic_close drawable
            toolbar.setNavigationContentDescription("Exit selection mode");
            toolbar.setNavigationOnClickListener(v -> exitSelectionMode());
            fabDeleteSelected.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
            fabToggleView.setVisibility(View.GONE);
        } else {
            toolbar.setTitle(originalToolbarTitle);
            toolbar.setNavigationIcon(null);
            toolbar.setNavigationOnClickListener(null);
            fabDeleteSelected.setVisibility(View.GONE);
            fabToggleView.setVisibility(View.VISIBLE);
        }
        // Refresh the options menu (to show/hide "More Options")
        getActivity().invalidateOptionsMenu();
    }
    // --- Deletion Logic ---

    // Add null check in confirmDeleteSelected just in case
    private void confirmDeleteSelected() {
        vibrate();
        if (!isAdded() || getContext() == null || selectedUris.isEmpty()) { // Added safety checks
            Log.w(TAG,"confirmDeleteSelected called but cannot proceed (not attached, context null, or selection empty).");
            return;
        }
        int count = selectedUris.size();
        Log.d(TAG,"Showing confirm delete dialog for " + count + " items.");
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getResources().getString(R.string.dialog_multi_video_del_title) + " ("+count+")")
                .setMessage(getResources().getString(R.string.dialog_multi_video_del_note))
                .setNegativeButton(getResources().getString(R.string.dialog_multi_video_del_no), null)
                .setPositiveButton(getResources().getString(R.string.dialog_multi_video_del_yes), (dialog, which) -> deleteSelectedVideos()) // Calls corrected delete method
                .show();
    }

    // --- deleteSelectedVideos (Corrected version from previous step) ---
    /** Handles deletion of selected videos */
    private void deleteSelectedVideos() {
        final List<Uri> itemsToDelete = new ArrayList<>(selectedUris); // Use fragment's list
        if(itemsToDelete.isEmpty()){ Log.d(TAG,"Deletion requested but selectedUris is empty."); exitSelectionMode(); return; }

        Log.i(TAG,"Deleting "+itemsToDelete.size()+" selected videos...");
        exitSelectionMode(); // Exit selection mode UI first

        if (executorService == null || executorService.isShutdown()){ executorService = Executors.newSingleThreadExecutor(); }
        executorService.submit(() -> { /* ... background deletion loop (calling deleteVideoUri) ... */
            int successCount = 0; int failCount = 0;
            for(Uri uri: itemsToDelete) { if(deleteVideoUri(uri)) successCount++; else failCount++; }
            Log.d(TAG,"BG Deletion Finished. Success: "+successCount+", Fail: "+failCount);
            // Post results and UI refresh back to main thread
            final int finalSuccessCount = successCount; final int finalFailCount = failCount;
            if(getActivity()!=null){ getActivity().runOnUiThread(()->{/* ... Show Toast ... */ loadRecordsList(); }); }
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

    // Inside RecordsFragment.java
    private void deleteAllVideos() {
        List<VideoItem> itemsToDelete = new ArrayList<>(videoItems);
        if (itemsToDelete.isEmpty()) { /* ... show toast, return ... */ return; }

        // Visually clear immediately (optimistic)
        videoItems.clear();
        if(recordsAdapter != null) recordsAdapter.updateRecords(videoItems);
        // *** FIX: Update visibility immediately after clearing for UI responsiveness ***
        updateUiVisibility(); // Show empty state now
        // --- End FIX ---

        executorService.submit(() -> {
            int successCount = 0;
            int failCount = 0;
            for (VideoItem item : itemsToDelete) {
                if (item != null && item.uri != null) {
                    if (deleteVideoUri(item.uri)) successCount++; else failCount++;
                } else { failCount++; }
            }

            // Final status update on main thread
            final int finalSuccessCount = successCount;
            final int finalFailCount = failCount;
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    String message = (finalFailCount > 0) ?
                            "Deleted " + finalSuccessCount + ", Failed " + finalFailCount :
                            "Deleted all " + finalSuccessCount + " videos.";
                    Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();

                    // *** Optional: Double-check UI visibility here, though should already be set ***
                    // updateUiVisibility();
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

    // --- Menu Handling ---
    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater); // Call super
        menu.clear(); // Clear previous items first
        inflater.inflate(R.menu.records_menu, menu);
        MenuItem moreItem = menu.findItem(R.id.action_more_options);
        MenuItem deleteAllItem = menu.findItem(R.id.action_delete_all); // Find delete all

        // Visibility depends on selection mode
        if(moreItem != null) moreItem.setVisible(!isInSelectionMode);
        if(deleteAllItem != null) deleteAllItem.setVisible(false); // Always hide from toolbar menu

        Log.d(TAG,"onCreateOptionsMenu called, More Options Visible: " + !isInSelectionMode);
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

    // In RecordsFragment.java

    /**
     * Scans the relevant external cache directories for lingering temporary video files.
     * @return A List of VideoItem objects representing the found temp files.
     */
    private List<VideoItem> getTempCacheRecordsList() {
        List<VideoItem> items = new ArrayList<>();
        Context context = getContext();
        if (context == null) {
            Log.e(TAG,"Context is null in getTempCacheRecordsList");
            return items;
        }

        File cacheBaseDir = context.getExternalCacheDir();
        if (cacheBaseDir == null) {
            Log.e(TAG, "External cache dir is null, cannot scan for temp files.");
            return items;
        }

        // Directory where MediaRecorder saves the initial temp file
        File recordingTempDir = new File(cacheBaseDir, "recording_temp");
        Log.d(TAG, "Scanning for temp files in: " + recordingTempDir.getAbsolutePath());
        scanDirectoryForTempVideos(recordingTempDir, items);

        // --- Optional: Scan the processed temp directory ---
        // Only include this if your RecordingService FFmpeg command explicitly writes
        // *another* temp file to a different cache location *before* the final move/copy.
        // If FFmpeg writes directly to the final destination (internal) or creates
        // its temp processed file in the *same* recording_temp dir, this second scan is NOT needed.
        /*
        File processedTempDir = new File(cacheBaseDir, "processed_temp");
        if (!processedTempDir.equals(recordingTempDir)) { // Avoid scanning same dir twice
             Log.d(TAG, "Scanning for temp files in: " + processedTempDir.getAbsolutePath());
             scanDirectoryForTempVideos(processedTempDir, items);
        }
        */

        Log.d(TAG, "Found " + items.size() + " temporary video files in cache.");
        return items;
    }

    /**
     * Helper method to scan a specific directory for files starting with "temp_"
     * and ending with the video extension. Adds found files as VideoItems to the list.
     * @param directory The directory to scan.
     * @param items The list to add found VideoItems to.
     */
    private void scanDirectoryForTempVideos(File directory, List<VideoItem> items) {
        if (directory.exists() && directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()
                            && file.getName().startsWith("temp_")
                            && file.getName().endsWith("." + Constants.RECORDING_FILE_EXTENSION))
                    {
                        // Basic check if file has content
                        if (file.length() > 0) {
                            Log.d(TAG, "Found temp video: " + file.getName());
                            items.add(new VideoItem(
                                    Uri.fromFile(file), // Cache files are standard files
                                    file.getName(),
                                    file.length(),
                                    file.lastModified()
                            ));
                        } else {
                            Log.w(TAG,"Skipping empty temp file: "+file.getName());
                        }
                    }
                }
            } else {
                Log.w(TAG, "Could not list files in cache directory: "+directory.getPath());
            }
        } else {
            Log.d(TAG, "Cache directory does not exist or is not a directory: "+directory.getPath());
        }
    }
}