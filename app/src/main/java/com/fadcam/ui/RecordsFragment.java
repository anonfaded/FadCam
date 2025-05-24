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
import com.fadcam.utils.TrashManager; // <<< ADD IMPORT FOR TrashManager
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout; // ADD THIS IMPORT

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
    private AlertDialog progressDialog; // Field to hold the dialog for Save to Gallery
    private AlertDialog moveTrashProgressDialog; // Changed from ProgressDialog to AlertDialog
    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefreshLayout; // ADD THIS FIELD
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

    // *** NEW: Implement onMoveToTrashRequested from RecordActionListener ***
    @Override
    public void onMoveToTrashRequested(VideoItem videoItem) {
        if (videoItem == null || videoItem.uri == null) {
            Log.e(TAG, "onMoveToTrashRequested: Received null videoItem or URI.");
            if (getContext() != null) {
                Toast.makeText(getContext(), getString(R.string.delete_video_error_null_toast), Toast.LENGTH_SHORT).show();
            }
            onMoveToTrashFinished(false, "Error: Null item provided.");
            return;
        }

        Log.i(TAG, "Delete requested for: " + videoItem.displayName);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.delete_video_dialog_title))
                .setMessage(getString(R.string.delete_video_dialog_message, videoItem.displayName))
                .setNegativeButton(getString(R.string.universal_cancel), (dialog, which) -> {
                    onMoveToTrashFinished(false, getString(R.string.delete_video_cancelled_toast));
                })
                .setPositiveButton(getString(R.string.video_menu_del), (dialog, which) -> {
                    if (executorService == null || executorService.isShutdown()) {
                        executorService = Executors.newSingleThreadExecutor();
                    }
                    executorService.submit(() -> {
                        boolean success = moveToTrashVideoItem(videoItem);
                        final String message = success ? getString(R.string.delete_video_success_toast, videoItem.displayName) :
                                getString(R.string.delete_video_fail_toast, videoItem.displayName);
                        onMoveToTrashFinished(success, message);

                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                if (success) {
                                    loadRecordsList(); // Refresh the list
                                }
                                if (isInSelectionMode && selectedUris.contains(videoItem.uri)) {
                                     selectedUris.remove(videoItem.uri);
                                     if(selectedUris.isEmpty()) {
                                         exitSelectionMode();
                                     } else {
                                         updateUiForSelectionMode();
                                     }
                                }
                            });
                        }
                    });
                })
                .show();
    }

    @Override
    public void onMoveToTrashStarted(String videoName) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (moveTrashProgressDialog != null && moveTrashProgressDialog.isShowing()) {
                moveTrashProgressDialog.dismiss(); // Dismiss previous if any
            }

            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
            LayoutInflater inflater = LayoutInflater.from(getContext());
            View dialogView = inflater.inflate(R.layout.dialog_progress, null); // Assuming R.layout.dialog_progress exists

            TextView progressText = dialogView.findViewById(R.id.progress_text); // Assuming R.id.progress_text exists in dialog_progress.xml
            if (progressText != null) {
                progressText.setText(getString(R.string.delete_video_progress, videoName));
            }

            builder.setView(dialogView);
            builder.setCancelable(false); // User cannot cancel this

            moveTrashProgressDialog = builder.create();
            if (!moveTrashProgressDialog.isShowing()) {
                moveTrashProgressDialog.show();
            }
        });
    }

    @Override
    public void onMoveToTrashFinished(boolean success, String message) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (moveTrashProgressDialog != null && moveTrashProgressDialog.isShowing()) {
                moveTrashProgressDialog.dismiss();
                moveTrashProgressDialog = null; // Clear reference
            }
            if (getContext() != null && message != null && !message.isEmpty()) {
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
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
                        // Ensure we are attached and context is valid
                        if (intent == null || !isAdded() || getContext() == null) {
                            return;
                        }

                        if (Constants.ACTION_STORAGE_LOCATION_CHANGED.equals(intent.getAction())) {
                            Log.i(TAG, "Received ACTION_STORAGE_LOCATION_CHANGED. Refreshing list.");
                            // If storage location changed, we should definitely reload the list.
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

        sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());
        toolbar = view.findViewById(R.id.topAppBar); 
        if (toolbar != null && getActivity() instanceof AppCompatActivity) {
            AppCompatActivity activity = (AppCompatActivity) getActivity();
            activity.setSupportActionBar(toolbar);
            originalToolbarTitle = getString(R.string.records_title); 
            toolbar.setTitle(originalToolbarTitle);
        } else {
            Log.e(TAG, "Toolbar is null or activity is not AppCompatActivity in RecordsFragment.");
        }
        setHasOptionsMenu(true); 

        loadingIndicator = view.findViewById(R.id.loading_indicator); 
        recyclerView = view.findViewById(R.id.recycler_view_records);
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh_layout); 
        emptyStateContainer = view.findViewById(R.id.empty_state_container);
        fabToggleView = view.findViewById(R.id.fab_toggle_view);
        fabDeleteSelected = view.findViewById(R.id.fab_delete_selected);

        setupRecyclerView();
        setupFabListeners();
        updateFabIcons(); // Set initial FAB icon based on isGridView

        // Setup SwipeRefreshLayout
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(() -> {
                Log.d(TAG, "Swipe to refresh triggered.");
                loadRecordsList(); // Call your existing method to load/refresh records
                // The setRefreshing(false) will be called at the end of loadRecordsList
            });
        } else {
            Log.e(TAG, "SwipeRefreshLayout is null after findViewById!");
        }

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
        Log.i(TAG, "LOG_LIFECYCLE: onResume called.");

        // ----- Fix Start for this method (onResume_toolbar_and_menu_refresh) -----
        // Re-assert toolbar and invalidate options menu
        if (toolbar != null && getActivity() instanceof AppCompatActivity) {
            AppCompatActivity activity = (AppCompatActivity) getActivity();
            activity.setSupportActionBar(toolbar); // Re-set the support action bar
            toolbar.setTitle(originalToolbarTitle != null ? originalToolbarTitle : getString(R.string.records_title));
            activity.invalidateOptionsMenu(); // Tell the activity to redraw the options menu
            Log.d(TAG, "Toolbar re-set and options menu invalidated in onResume.");
        } else {
            Log.w(TAG, "Could not re-set toolbar in onResume - toolbar or activity null/invalid.");
        }
        // ----- Fix Ended for this method (onResume_toolbar_and_menu_refresh) -----

        // Add null check for safety
        if (sharedPreferencesManager == null && getContext() != null) {
            sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());
        }

        Log.i(TAG, "LOG_REFRESH: Calling loadRecordsList() from onResume.");
        loadRecordsList(); // RESTORED: Always reload the list when the fragment resumes
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
        Log.i(TAG, "LOG_LOAD_RECORDS: loadRecordsList START. Current sort: " + currentSortOption);
        if (loadingIndicator != null) {
            loadingIndicator.setVisibility(View.VISIBLE);
            Log.d(TAG, "LOG_LOAD_RECORDS: Loading indicator VISIBLE.");
        }
        if (recyclerView != null) {
            recyclerView.setVisibility(View.GONE);
            Log.d(TAG, "LOG_LOAD_RECORDS: RecyclerView GONE.");
        }
        if (emptyStateContainer != null) {
            emptyStateContainer.setVisibility(View.GONE);
            Log.d(TAG, "LOG_LOAD_RECORDS: Empty state GONE.");
        }

        executorService.execute(() -> {
            Log.d(TAG, "LOG_LOAD_RECORDS_BG: Background execution START.");
            final List<VideoItem> loadedItems = new ArrayList<>();
            List<VideoItem> tempCacheVideos = getTempCacheRecordsList(); // Get temp videos first, as they might be relevant for both internal and SAF paths
            Log.d(TAG, "LOG_LOAD_RECORDS_BG: Fetched " + tempCacheVideos.size() + " temp cache videos.");

            String safUriString = sharedPreferencesManager.getCustomStorageUri();
            boolean loadedFromSaf = false;

            if (safUriString != null) {
                Log.d(TAG, "LOG_LOAD_RECORDS_BG: SAF URI configured: " + safUriString);
                try {
                    Uri treeUri = Uri.parse(safUriString);
                    if (hasSafPermission(treeUri)) {
                        Log.i(TAG, "LOG_LOAD_RECORDS_BG: Valid SAF custom location. Loading ONLY from SAF.");
                        List<VideoItem> safVideos = getSafRecordsList(treeUri);
                        Log.d(TAG, "LOG_LOAD_RECORDS_BG: Fetched " + safVideos.size() + " SAF videos.");
                        loadedItems.addAll(safVideos);
                        loadedFromSaf = true;
                    } else {
                        Log.w(TAG, "LOG_LOAD_RECORDS_BG: No persistent permission for SAF URI: " + safUriString + ". Falling back to internal storage.");
                        // Optionally, notify the user or clear the invalid preference here.
                    }
                } catch (Exception e) {
                    Log.e(TAG, "LOG_LOAD_RECORDS_BG: Error processing SAF URI: " + safUriString + ". Falling back to internal storage.", e);
                }
            } else {
                Log.d(TAG, "LOG_LOAD_RECORDS_BG: No SAF URI configured. Using internal storage.");
            }

            if (!loadedFromSaf) {
                Log.i(TAG, "LOG_LOAD_RECORDS_BG: Loading from internal storage.");
                List<VideoItem> internalVideos = getInternalRecordsList();
                Log.d(TAG, "LOG_LOAD_RECORDS_BG: Fetched " + internalVideos.size() + " internal videos.");
                loadedItems.addAll(internalVideos);
            }

            // Add temp cache videos to the primary list (SAF or Internal)
            // Ensure no duplicates if a temp video somehow also got listed by primary storage methods (unlikely but good practice)
            for(VideoItem tempVideo : tempCacheVideos){
                if(!loadedItems.contains(tempVideo)){
                    loadedItems.add(tempVideo);
                    Log.d(TAG, "LOG_LOAD_RECORDS_BG: Added temp cache video " + tempVideo.displayName + " to final list.");
                }
            }
            Log.d(TAG, "LOG_LOAD_RECORDS_BG: Total items before sort (after potentially adding temp cache): " + loadedItems.size());

            sortItems(loadedItems, currentSortOption);
            Log.d(TAG, "LOG_LOAD_RECORDS_BG: Total items after sort: " + loadedItems.size());

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    Log.i(TAG, "LOG_LOAD_RECORDS_UI: Updating UI on main thread. Item count to display: " + loadedItems.size());
                    videoItems.clear();
                    videoItems.addAll(loadedItems);
                    Log.d(TAG, "LOG_LOAD_RECORDS_UI: videoItems list updated in fragment. Size: " + videoItems.size());
                    if (recordsAdapter != null) {
                        recordsAdapter.updateRecords(videoItems); // <--- Ensure this call happens
                        // notifyDataSetChanged is called within updateRecords in the adapter, so not strictly needed here again if that's the case.
                        // However, explicitly calling it here can be a safeguard or if adapter's updateRecords changes.
                        // For now, let's assume updateRecords handles the notification.
                        // recordsAdapter.notifyDataSetChanged(); // This might be redundant if updateRecords already does it.
                        Log.d(TAG, "LOG_LOAD_RECORDS_UI: Adapter updated with new records. Adapter item count: " + (recordsAdapter != null ? recordsAdapter.getItemCount() : "null adapter"));
                    } else {
                        Log.w(TAG, "LOG_LOAD_RECORDS_UI: recordsAdapter is NULL when trying to update and notify.");
                    }
                    updateUiVisibility();
                    if (loadingIndicator != null) {
                        loadingIndicator.setVisibility(View.GONE);
                        Log.d(TAG, "LOG_LOAD_RECORDS_UI: Loading indicator GONE.");
                    }
                    if (swipeRefreshLayout != null) {
                        swipeRefreshLayout.setRefreshing(false);
                        Log.d(TAG, "LOG_LOAD_RECORDS_UI: SwipeRefreshLayout refreshing set to false.");
                    }
                });
            } else {
                Log.w(TAG,"LOG_LOAD_RECORDS_BG: Activity is null, cannot update UI after loading records.");
            }
            Log.d(TAG, "LOG_LOAD_RECORDS_BG: Background execution END.");
        });
        Log.d(TAG, "LOG_LOAD_RECORDS: loadRecordsList END (background task launched).");
    }


    // Ensure updateUiVisibility is defined correctly:
    private void updateUiVisibility() {
        if (getView() == null) {
            Log.w(TAG, "LOG_UI_VISIBILITY: getView() is null. Cannot update UI visibility.");
            return;
        }

        boolean isEmpty;
        if (recordsAdapter != null) {
            isEmpty = recordsAdapter.getItemCount() == 0;
            Log.d(TAG, "LOG_UI_VISIBILITY: Adapter found. Item count: " + recordsAdapter.getItemCount() + ". Is empty: " + isEmpty);
        } else {
            isEmpty = videoItems.isEmpty();
            Log.d(TAG, "LOG_UI_VISIBILITY: Adapter is NULL. videoItems list size: " + videoItems.size() + ". Is empty: " + isEmpty);
        }

        Log.i(TAG, "LOG_UI_VISIBILITY: updateUiVisibility called. Final decision: isEmpty = " + isEmpty);

        if (isEmpty) {
            if (recyclerView != null) recyclerView.setVisibility(View.GONE);
            if (emptyStateContainer != null) emptyStateContainer.setVisibility(View.VISIBLE);
            Log.d(TAG,"LOG_UI_VISIBILITY: Showing empty state (Recycler GONE, Empty VISIBLE).");
        } else {
            if (emptyStateContainer != null) emptyStateContainer.setVisibility(View.GONE);
            if (recyclerView != null) recyclerView.setVisibility(View.VISIBLE);
            Log.d(TAG,"LOG_UI_VISIBILITY: Showing recycler view (Empty GONE, Recycler VISIBLE).");
        }
        if (loadingIndicator != null && loadingIndicator.getVisibility() == View.VISIBLE) {
            loadingIndicator.setVisibility(View.GONE);
            Log.d(TAG,"LOG_UI_VISIBILITY: Loading indicator was visible, set to GONE.");
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
        Log.d(TAG, "LOG_GET_INTERNAL: getInternalRecordsList START.");
        List<VideoItem> items = new ArrayList<>();
        File recordsDir = getContext() != null ? getContext().getExternalFilesDir(null) : null;
        if (recordsDir == null) {
            Log.e(TAG, "LOG_GET_INTERNAL: Could not get ExternalFilesDir for internal storage.");
            return items;
        }
        File fadCamDir = new File(recordsDir, Constants.RECORDING_DIRECTORY);
        Log.d(TAG, "LOG_GET_INTERNAL: Checking directory: " + fadCamDir.getAbsolutePath());

        if (fadCamDir.exists() && fadCamDir.isDirectory()) {
            Log.d(TAG, "LOG_GET_INTERNAL: Directory exists. Listing files.");
            File[] files = fadCamDir.listFiles();
            if (files != null) {
                Log.d(TAG, "LOG_GET_INTERNAL: Found " + files.length + " files/dirs in internal storage.");
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith("." + Constants.RECORDING_FILE_EXTENSION) && !file.getName().startsWith("temp_")) {
                        long lastModifiedMeta = file.lastModified();
                        long timestampFromFile = Utils.parseTimestampFromFilename(file.getName());
                        long finalTimestamp = lastModifiedMeta; 

                        if (lastModifiedMeta <= 0 && timestampFromFile > 0) { 
                            finalTimestamp = timestampFromFile;
                        } else if (lastModifiedMeta <= 0 && timestampFromFile <= 0) {
                            finalTimestamp = System.currentTimeMillis(); 
                        }
                        Uri uri = Uri.fromFile(file);
                        items.add(new VideoItem(uri, file.getName(), file.length(), finalTimestamp));
                        Log.v(TAG, "LOG_GET_INTERNAL: Added internal item: " + file.getName());
                    } else {
                        Log.v(TAG, "LOG_GET_INTERNAL: Skipped item (not a valid video file or is temp): " + file.getName());
                    }
                }
            } else {
                Log.w(TAG, "LOG_GET_INTERNAL: Internal FadCam directory listFiles returned null.");
            }
        } else {
            Log.i(TAG, "LOG_GET_INTERNAL: Internal FadCam directory does not exist yet: " + fadCamDir.getAbsolutePath());
        }
        Log.i(TAG, "LOG_GET_INTERNAL: Found " + items.size() + " internal records. END.");
        return items;
    }

    private List<VideoItem> getSafRecordsList(Uri treeUri) {
        Log.d(TAG, "LOG_GET_SAF: getSafRecordsList START for URI: " + treeUri);
        List<VideoItem> items = new ArrayList<>();
        Context context = getContext();
        if (context == null) {
            Log.e(TAG,"LOG_GET_SAF: Context is null."); return items;
        }

        DocumentFile dir = DocumentFile.fromTreeUri(context, treeUri);

        if (dir != null && dir.isDirectory() && dir.canRead()) {
            Log.d(TAG, "LOG_GET_SAF: SAF Directory " + dir.getName() + " is accessible. Listing files.");
            try {
                DocumentFile[] files = dir.listFiles();
                Log.d(TAG, "LOG_GET_SAF: Found " + (files != null ? files.length : "null (listFiles failed?)") + " files/dirs in SAF location.");
                for (DocumentFile file : files) {
                    if (file != null && file.isFile() && file.getName() != null &&
                            (file.getName().endsWith("." + Constants.RECORDING_FILE_EXTENSION) || "video/mp4".equals(file.getType())) && 
                            !file.getName().startsWith("temp_")) 
                    {
                        long lastModifiedMeta = file.lastModified();
                        String name = file.getName();
                        long timestampFromFile = Utils.parseTimestampFromFilename(name);
                        long finalTimestamp = lastModifiedMeta; 

                        if (lastModifiedMeta <= 0 && timestampFromFile > 0) { 
                            finalTimestamp = timestampFromFile;
                        } else if (lastModifiedMeta <= 0 && timestampFromFile <= 0) {
                            finalTimestamp = System.currentTimeMillis(); 
                        }
                        Uri uri = file.getUri();
                        items.add(new VideoItem(uri, name, file.length(), finalTimestamp));
                        Log.v(TAG, "LOG_GET_SAF: Added SAF item: " + name);
                    } else {
                        if (file != null) {
                             Log.v(TAG, "LOG_GET_SAF: Skipped item (not a valid video file or is temp): " + file.getName() + " | isFile: " + file.isFile() + " | type: " + file.getType());
                        } else {
                             Log.v(TAG, "LOG_GET_SAF: Skipped a null DocumentFile entry in listFiles result.");
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "LOG_GET_SAF: Error listing SAF files in " + treeUri, e);
                if(getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(context, "Error reading custom location content.", Toast.LENGTH_SHORT).show();
                    });
                }
            }
        } else {
            Log.e(TAG, "LOG_GET_SAF: Cannot read or access SAF directory: " + treeUri +
                    ", Dir Exists=" + (dir != null && dir.exists()) +
                    ", IsDir=" + (dir != null && dir.isDirectory()) +
                    ", CanRead=" + (dir != null && dir.canRead()));
        }
        Log.i(TAG, "LOG_GET_SAF: Found " + items.size() + " SAF records. END.");
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
        final List<Uri> itemsToDeleteUris = new ArrayList<>(selectedUris);
        if(itemsToDeleteUris.isEmpty()){ Log.d(TAG,"Deletion requested but selectedUris is empty."); exitSelectionMode(); return; }

        Log.i(TAG, getString(R.string.delete_videos_log, itemsToDeleteUris.size()));
        exitSelectionMode();

        if (executorService == null || executorService.isShutdown()){ executorService = Executors.newSingleThreadExecutor(); }
        executorService.submit(() -> {
            int successCount = 0; int failCount = 0;
            List<VideoItem> allCurrentItems = new ArrayList<>(videoItems); // Copy for safe iteration

            for(Uri uri: itemsToDeleteUris) {
                VideoItem itemToTrash = findVideoItemByUri(allCurrentItems, uri);
                if (itemToTrash != null) {
                    if (moveToTrashVideoItem(itemToTrash)) {
                        successCount++;
                    } else {
                        failCount++;
                    }
                } else {
                    Log.w(TAG, "Could not find VideoItem for URI: " + uri + " to move to trash.");
                    failCount++;
                }
            }
            Log.d(TAG,"BG Trash Operation Finished. Success: "+successCount+", Fail: "+failCount);
            // Post results and UI refresh back to main thread
            final int finalSuccessCount = successCount; final int finalFailCount = failCount;
            if(getActivity()!=null){
                getActivity().runOnUiThread(()->{
                    String message = (finalFailCount > 0) ?
                             getString(R.string.delete_videos_partial_success_toast, finalSuccessCount, finalFailCount) :
                             getString(R.string.delete_videos_success_toast, finalSuccessCount);
                    Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
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

    // Inside RecordsFragment.java
    private void deleteAllVideos() {
        List<VideoItem> itemsToTrash = new ArrayList<>(videoItems);
        if (itemsToTrash.isEmpty()) {
             if(getContext() != null) Toast.makeText(requireContext(), "No videos to move to trash.", Toast.LENGTH_SHORT).show();
             return;
        }

        Log.i(TAG, "Moving all " + itemsToTrash.size() + " videos to trash...");

        if (executorService == null || executorService.isShutdown()) {
            executorService = Executors.newSingleThreadExecutor();
        }

        executorService.submit(() -> {
            int successCount = 0;
            int failCount = 0;
            for (VideoItem item : itemsToTrash) {
                if (item != null && item.uri != null) {
                    if (moveToTrashVideoItem(item)) { // Pass the whole VideoItem
                        successCount++;
                    } else {
                        failCount++;
                    }
                } else {
                    Log.w(TAG, "Encountered a null item or item with null URI in deleteAllVideos list.");
                    failCount++;
                }
            }

            // Final status update on main thread
            final int finalSuccessCount = successCount;
            final int finalFailCount = failCount;
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    String message = (finalFailCount > 0) ?
                            getString(R.string.delete_videos_partial_success_toast, finalSuccessCount, finalFailCount) :
                            getString(R.string.delete_videos_success_toast, finalSuccessCount);
                    Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                    loadRecordsList(); // Refresh the list from storage
                });
            }
        });
    }

    // Helper to find VideoItem by URI from a list
    private VideoItem findVideoItemByUri(List<VideoItem> items, Uri uri) {
        if (uri == null || items == null) return null;
        for (VideoItem item : items) {
            if (item != null && uri.equals(item.uri)) {
                return item;
            }
        }
        return null;
    }

    /**
     * Moves a single VideoItem to the trash.
     * This replaces the old deleteVideoUri functionality.
     *
     * @param videoItem The VideoItem to move to trash.
     * @return true if the video was successfully moved to trash, false otherwise.
     */
    private boolean moveToTrashVideoItem(VideoItem videoItem) {
        Context context = getContext();
        if (context == null || videoItem == null || videoItem.uri == null || videoItem.displayName == null) {
            Log.e(TAG, "moveToTrashVideoItem: Invalid arguments (context, videoItem, URI, or displayName is null).");
            return false;
        }

        Uri uri = videoItem.uri;
        String originalDisplayName = videoItem.displayName;
        // Determine if it's an SAF source. Content URIs are typically from SAF.
        boolean isSafSource = "content".equals(uri.getScheme());

        Log.i(TAG, "Attempting to move to trash: " + originalDisplayName + " (URI: " + uri + ", isSAF: " + isSafSource + ")");

        if (TrashManager.moveToTrash(context, uri, originalDisplayName, isSafSource)) {
            Log.i(TAG, "Successfully moved to trash: " + originalDisplayName);
            return true;
        } else {
            Log.e(TAG, "Failed to move to trash: " + originalDisplayName);
            // Optionally, show a specific toast for this failure if needed,
            // but batch operations will show a summary.
            // if(getActivity() != null) getActivity().runOnUiThread(() -> Toast.makeText(context, "Failed to move '" + originalDisplayName + "' to trash.", Toast.LENGTH_SHORT).show());
            return false;
        }
    }

    // OLD deleteVideoUri - to be removed or commented out after confirming moveToTrashVideoItem is used everywhere
    private boolean deleteVideoUri(Uri uri) {
        // THIS METHOD IS NOW REPLACED BY moveToTrashVideoItem(VideoItem item)
        // To use the new method, you need the VideoItem object, not just the URI,
        // because we need the originalDisplayName and to determine if it's an SAF source.

        // Find the VideoItem corresponding to this URI from your main list (videoItems)
        VideoItem itemToTrash = null;
        List<VideoItem> currentItems = new ArrayList<>(videoItems); // Use a copy to avoid issues if list is modified elsewhere
        itemToTrash = findVideoItemByUri(currentItems, uri);

        if (itemToTrash != null) {
            Log.d(TAG, "Redirecting deleteVideoUri for " + uri + " to moveToTrashVideoItem.");
            return moveToTrashVideoItem(itemToTrash);
        } else {
            Log.e(TAG, "deleteVideoUri called for URI not found in videoItems: " + uri + ". Attempting direct delete as fallback (SHOULD NOT HAPPEN).");
             // Fallback to old deletion logic if item not found (should not happen ideally)
            // This part should ideally be removed if moveToTrashVideoItem is robustly used.
            Context context = getContext();
            if(context == null) {
                Log.e(TAG, "Fallback delete failed: context is null for URI: " + uri);
                return false;
            }
            Log.w(TAG, "Fallback: Attempting direct delete for URI: " + uri + " (VideoItem not found)");
            try {
                if ("file".equals(uri.getScheme())) {
                    File file = new File(uri.getPath());
                    if (file.exists() && file.delete()) {
                        Log.i(TAG, "Fallback: Deleted internal file: " + file.getName());
                        return true;
                    } else {
                        Log.e(TAG, "Fallback: Failed to delete internal file: " + uri.getPath() + " Exists=" + file.exists());
                        return false;
                    }
                } else if ("content".equals(uri.getScheme())) {
                    if (DocumentsContract.deleteDocument(context.getContentResolver(), uri)) {
                        Log.i(TAG, "Fallback: Deleted SAF document: " + uri);
                        return true;
                    } else {
                        DocumentFile doc = DocumentFile.fromSingleUri(context, uri);
                        if(doc == null || !doc.exists()){
                            Log.w(TAG,"Fallback: deleteDocument returned false, but file doesn't exist or became null. Treating as success. URI: "+ uri);
                            return true; // If it's gone, it's 'deleted'
                        }
                        Log.e(TAG, "Fallback: Failed to delete SAF document (deleteDocument returned false and file still exists): " + uri);
                        return false;
                    }
                }
                 Log.w(TAG, "Fallback: Unknown URI scheme for direct delete: " + uri.getScheme());
                return false;
            } catch (SecurityException se) {
                 Log.e(TAG, "Fallback: SecurityException deleting URI: " + uri, se);
                if(getActivity() != null) getActivity().runOnUiThread(() -> Toast.makeText(context, "Permission denied during fallback delete.", Toast.LENGTH_SHORT).show());
                return false;
            } catch (Exception e) {
                Log.e(TAG, "Fallback: Exception deleting URI: " + uri, e);
                return false;
            }
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