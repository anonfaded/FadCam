package com.fadcam.ui;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.UriPermission;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.animation.ObjectAnimator;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.RadioButton;
import android.widget.Toast;
import android.widget.CheckBox;
// Import ImageView
import android.widget.TextView; // Import TextView

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog; // Import AlertDialog
import android.widget.ProgressBar;
// Import Toolbar

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.Constants;
import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager; // Import your manager
import com.fadcam.Utils;
// Import the new VideoItem class
// Ensure adapter import is correct
import com.fadcam.utils.TrashManager; // <<< ADD IMPORT FOR TrashManager
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

// Add AppLock imports
import com.guardanis.applock.AppLock;
import com.guardanis.applock.dialogs.UnlockDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import android.graphics.Rect;

import android.content.BroadcastReceiver; // ** ADD **
// ** ADD **
// ** ADD **
import android.content.IntentFilter; // ** ADD **
// Use this for app-internal
import androidx.core.content.ContextCompat; // ** ADD **
// Use ContextCompat for receiver reg

import java.util.Set; // Need Set import
import java.util.HashSet; // Need HashSet import
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout; // ADD THIS IMPORT

import androidx.appcompat.widget.SearchView;
import com.bumptech.glide.Glide;
import java.util.concurrent.TimeUnit;

// Import additional classes for optimization
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.fadcam.utils.DebouncedRunnable;
import android.util.SparseArray;
import android.content.res.ColorStateList;
import android.graphics.Color;

public class RecordsFragment extends BaseFragment implements
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

    // Remove duplicate session cache - now using shared VideoSessionCache utility
    // (Moved to com.fadcam.utils.VideoSessionCache for cross-fragment sharing)

    // -------------- Fix Start (Skeleton Loading Methods) --------------

    /**
     * Shows skeleton loading placeholders immediately for professional UX
     */
    private void showSkeletonLoading(int estimatedCount) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (!isAdded())
                    return;

                Log.d(TAG, "Showing skeleton loading with " + estimatedCount + " items");

                // Create skeleton items
                List<VideoItem> skeletonItems = new ArrayList<>();
                for (int i = 0; i < estimatedCount; i++) {
                    skeletonItems.add(VideoItem.createSkeleton());
                }

                // -------------- Fix Start (showSkeletonLoading) - Proper skeleton setup
                // -----------

                // Show skeleton items immediately without triggering updateRecords
                if (recordsAdapter != null) {
                    recordsAdapter.setSkeletonMode(true);
                    allLoadedItems.clear();
                    allLoadedItems.addAll(skeletonItems);
                    videoItems.clear();
                    videoItems.addAll(skeletonItems);

                    // Directly set skeleton data without calling updateRecords to preserve skeleton
                    // mode
                    recordsAdapter.setSkeletonData(skeletonItems);
                }

                // -------------- Fix End (showSkeletonLoading) -----------

                // CRITICAL: Show RecyclerView immediately to prevent empty flash
                if (recyclerView != null) {
                    recyclerView.setVisibility(View.VISIBLE);
                }
                if (emptyStateContainer != null) {
                    emptyStateContainer.setVisibility(View.GONE);
                }
                if (loadingIndicator != null) {
                    loadingIndicator.setVisibility(View.GONE);
                }
            });
        }
    }

    /**
     * Replaces skeleton items with actual video data in one smooth transition
     */
    private void replaceSkeletonsWithData(List<VideoItem> actualItems) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (!isAdded())
                    return;

                Log.d(TAG, "Replacing " + allLoadedItems.size() + " skeletons with " + actualItems.size()
                        + " actual videos");

                // -------------- Fix Start (replaceSkeletonsWithData) - Fixed positioning and
                // ordering -----------

                // Disable skeleton mode
                if (recordsAdapter != null) {
                    recordsAdapter.setSkeletonMode(false);
                }

                // Replace data in all collections
                allLoadedItems.clear();
                allLoadedItems.addAll(actualItems);
                videoItems.clear();
                videoItems.addAll(actualItems);

                // CRITICAL FIX: Update adapter's internal records list
                if (recordsAdapter != null) {
                    recordsAdapter.updateRecords(actualItems);
                    Log.d(TAG, "Adapter updated with " + actualItems.size() + " videos");
                }

                // Update UI visibility
                updateUiVisibility();
                isLoading = false;

                // CRITICAL FIX: Ensure RecyclerView scrolls to top after loading
                if (recyclerView != null && actualItems.size() > 0) {
                    recyclerView.scrollToPosition(0);
                    Log.d(TAG, "RecyclerView scrolled to position 0 to show first video");
                }

                // Ensure refresh indicator is stopped
                if (swipeRefreshLayout != null && swipeRefreshLayout.isRefreshing()) {
                    swipeRefreshLayout.setRefreshing(false);
                    Log.d(TAG, "Refresh indicator stopped in replaceSkeletonsWithData");
                }

                Log.d(TAG, "Skeleton replacement complete - proper positioning and ordering achieved");

                // -------------- Fix End (replaceSkeletonsWithData) -----------

                // -------------- Fix End (replaceSkeletonsWithData) -----------
            });
        }
    }

    /**
     * Hides skeleton loading and shows error or empty state
     */
    private void hideSkeletonLoading() {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (!isAdded())
                    return;

                // -------------- Fix Start (hideSkeletonLoading) - Proper state cleanup
                // -----------

                if (recordsAdapter != null) {
                    recordsAdapter.setSkeletonMode(false);
                }

                allLoadedItems.clear();
                videoItems.clear();
                if (recordsAdapter != null) {
                    recordsAdapter.notifyDataSetChanged();
                }

                updateUiVisibility();
                isLoading = false;

                // Ensure refresh indicator is stopped on error/empty state
                if (swipeRefreshLayout != null && swipeRefreshLayout.isRefreshing()) {
                    swipeRefreshLayout.setRefreshing(false);
                    Log.d(TAG, "Refresh indicator stopped in hideSkeletonLoading");
                }

                // -------------- Fix End (hideSkeletonLoading) -----------
            });
        }
    }

    /**
     * Gets primary video files without progressive callbacks for silent background
     * loading
     */
    private List<VideoItem> getPrimaryVideoFiles() {
        String safUriString = sharedPreferencesManager.getCustomStorageUri();

        if (safUriString != null) {
            try {
                Uri treeUri = Uri.parse(safUriString);
                if (hasSafPermission(treeUri)) {
                    // Use progressive method but without callbacks for silent loading
                    return getSafRecordsListProgressive(treeUri, null);
                } else {
                    Log.w(TAG, "No persistent permission for SAF URI: " + safUriString);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing SAF URI", e);
            }
        }

        // Fallback to internal storage
        return getInternalRecordsList();
    }

    // -------------- Fix Ended (Skeleton Loading Methods) --------------

    private AlertDialog progressDialog; // Field to hold the dialog for Save to Gallery
    private AlertDialog moveTrashProgressDialog; // Changed from ProgressDialog to AlertDialog
    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefreshLayout; // ADD THIS FIELD
    private LinearLayout emptyStateContainer; // Add field for the empty state layout
    private RecordsAdapter recordsAdapter;
    private boolean isGridView = true;
    private FloatingActionButton fabDeleteSelected;
    private FloatingActionButton fabScrollNavigation; // Navigation FAB for scroll to top/bottom
    private boolean isScrollingDown = true; // Track scroll direction for FAB icon
    private ObjectAnimator currentRotationAnimator; // Smooth rotation animation for FAB icon

    // ----- Fix Start: Add AppLock overlay view field -----
    private View applockOverlay;
    // ----- Fix End: Add AppLock overlay view field -----

    // Use VideoItem and store Uris for selection
    private List<VideoItem> videoItems = new ArrayList<>();
    private List<Uri> selectedVideosUris = new ArrayList<>();

    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    private SortOption currentSortOption = SortOption.LATEST_FIRST;
    private SharedPreferencesManager sharedPreferencesManager;
    private SpacesItemDecoration itemDecoration; // Keep a reference
    private ProgressBar loadingIndicator; // *** ADD field for ProgressBar ***
    // -------------- Fix Start for this method(updateHeaderFields)-----------
    private TextView titleText;
    private ImageView menuButton;
    private ImageView closeButton;
    private View selectAllContainer;
    private android.widget.ImageView selectAllCheck;
    private CharSequence originalToolbarTitle;
    // -------------- Fix Ended for this method(updateHeaderFields)-----------

    // --- Selection State ---
    private boolean isInSelectionMode = false;
    private List<Uri> selectedUris = new ArrayList<>(); // Manage the actual selected URIs here
    // *** IMPLEMENT THE NEW INTERFACE METHOD ***

    @Override
    public void onDeletionFinishedCheckEmptyState() {
        Log.d(TAG, "Adapter signaled deletion finished. Checking empty state...");
        // This method is called AFTER the adapter has removed the item
        // and notified itself. Now, we update the Fragment's overall UI visibility.
        if (getView() != null) { // Ensure view is available
            getActivity().runOnUiThread(this::updateUiVisibility); // Use the existing helper
        } else {
            Log.w(TAG, "onDeletionFinishedCheckEmptyState called but view is null.");
        }
    }

    // --- NEW: Implement onMoveToTrashRequested from RecordActionListener ---
    @Override
    public void onMoveToTrashRequested(VideoItem videoItem) {
        if (videoItem == null || videoItem.uri == null) {
            Log.e(TAG, "onMoveToTrashRequested: Received null videoItem or URI.");
            if (getContext() != null) {
                Toast.makeText(getContext(), getString(R.string.delete_video_error_null_toast), Toast.LENGTH_SHORT)
                        .show();
            }
            onMoveToTrashFinished(false, "Error: Null item provided.");
            return;
        }

        Log.i(TAG, "Delete requested for: " + videoItem.displayName);

        // Show confirm-only bottom sheet (no typing). Proceed on confirm.
        InputActionBottomSheetFragment sheet = InputActionBottomSheetFragment.newConfirm(
                getString(R.string.delete_video_dialog_title),
                getString(R.string.video_menu_del),
                getString(R.string.delete_single_video_subtitle),
                R.drawable.ic_delete,
                getString(R.string.delete_video_dialog_message, videoItem.displayName)
        );
        sheet.setCallbacks(new InputActionBottomSheetFragment.Callbacks() {
            @Override public void onImportConfirmed(org.json.JSONObject json) { /* not used */ }
            @Override public void onResetConfirmed() {
                onMoveToTrashStarted(videoItem.displayName);
                if (executorService == null || executorService.isShutdown()) {
                    executorService = Executors.newSingleThreadExecutor();
                }
                executorService.submit(() -> {
                    boolean success = moveToTrashVideoItem(videoItem);
                    final String message = success
                            ? getString(R.string.delete_video_success_toast, videoItem.displayName)
                            : getString(R.string.delete_video_fail_toast, videoItem.displayName);
                    onMoveToTrashFinished(success, message);

                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (success) {
                                // Invalidate cache when video is deleted
                                com.fadcam.utils.VideoSessionCache.invalidateOnNextAccess(sharedPreferencesManager);

                                // Perform a complete refresh to ensure serial numbers are updated
                                Log.d(TAG, "Single video deleted, performing full refresh to update serial numbers");
                                if (recordsAdapter != null) {
                                    recordsAdapter.clearCaches();
                                }
                                loadRecordsList();
                            }
                            if (isInSelectionMode && selectedUris.contains(videoItem.uri)) {
                                selectedUris.remove(videoItem.uri);
                                if (selectedUris.isEmpty()) {
                                    exitSelectionMode();
                                } else {
                                    updateUiForSelectionMode();
                                }
                            }
                        });
                    }
                });
            }
        });
        sheet.show(getParentFragmentManager(), "delete_single_confirm_sheet");
    }

    @Override
    public void onMoveToTrashStarted(String videoName) {
        if (getActivity() == null)
            return;
        getActivity().runOnUiThread(() -> {
            if (moveTrashProgressDialog != null && moveTrashProgressDialog.isShowing()) {
                moveTrashProgressDialog.dismiss(); // Dismiss previous if any
            }

            // Check for Snow Veil theme
            String currentTheme = sharedPreferencesManager.sharedPreferences
                    .getString(com.fadcam.Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
            boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
            int dialogTheme = isSnowVeilTheme ? R.style.ThemeOverlay_FadCam_SnowVeil_Dialog
                    : R.style.ThemeOverlay_FadCam_Dialog;

            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext(), dialogTheme);
            LayoutInflater inflater = LayoutInflater.from(getContext());
            View dialogView = inflater.inflate(R.layout.dialog_progress, null); // Assuming R.layout.dialog_progress
                                                                                // exists

            TextView progressText = dialogView.findViewById(R.id.progress_text); // Assuming R.id.progress_text exists
                                                                                 // in dialog_progress.xml
            if (progressText != null) {
                progressText.setText(getString(R.string.delete_video_progress, videoName));
                // Set text color based on theme
                progressText.setTextColor(ContextCompat.getColor(requireContext(),
                        isSnowVeilTheme ? android.R.color.black : android.R.color.white));
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
        if (success) {
            // Invalidate caches when videos are deleted
            com.fadcam.utils.VideoStatsCache.invalidateStats(sharedPreferencesManager);
            com.fadcam.utils.VideoSessionCache.invalidateOnNextAccess();
            Log.d(TAG, "Invalidated video caches after successful video deletion");
        }

        if (getActivity() == null)
            return;
        getActivity().runOnUiThread(() -> {
            if (moveTrashProgressDialog != null && moveTrashProgressDialog.isShowing()) {
                moveTrashProgressDialog.dismiss();
                moveTrashProgressDialog = null; // Clear reference
            }
            if (getContext() != null && message != null && !message.isEmpty()) {
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
        Log.d(TAG, "onMoveToTrashFinished. Success: " + success + ", Msg: " + message);
    }

    // *** Register in onStart ***
    @Override
    public void onStart() {
        super.onStart();
        registerRecordingCompleteReceiver();
        registerStorageLocationChangedReceiver();
        registerProcessingStateReceivers();
        registerSegmentCompleteReceiver();
    }

    // *** Unregister in onStop ***
    @Override
    public void onStop() {
        super.onStop();
        unregisterRecordingCompleteReceiver();
        unregisterStorageLocationChangedReceiver();
        unregisterProcessingStateReceivers();
        unregisterSegmentCompleteReceiver();
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
                            Log.i(TAG, "Received ACTION_STORAGE_LOCATION_CHANGED broadcast. Refreshing records list.");
                            // Clear caches to ensure fresh data from new storage location
                            com.fadcam.utils.VideoSessionCache.invalidateOnNextAccess(sharedPreferencesManager);
                            if (recordsAdapter != null) {
                                recordsAdapter.clearCaches();
                            }
                            // If storage location changed, we should definitely reload the list.
                            loadRecordsList();
                        } else if (Constants.ACTION_FILES_RESTORED.equals(intent.getAction())) {
                            Log.i(TAG, "Received ACTION_FILES_RESTORED broadcast. Refreshing records list.");
                            // Clear caches to ensure fresh data
                            com.fadcam.utils.VideoSessionCache.invalidateOnNextAccess(sharedPreferencesManager);
                            if (recordsAdapter != null) {
                                recordsAdapter.clearCaches();
                            }
                            // Files were restored from trash, refresh the list to show them
                            loadRecordsList();
                        }
                    }
                };
            }
            IntentFilter filter = new IntentFilter();
            filter.addAction(Constants.ACTION_STORAGE_LOCATION_CHANGED);
            filter.addAction(Constants.ACTION_FILES_RESTORED);
            ContextCompat.registerReceiver(requireContext(), storageLocationChangedReceiver, filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            isStorageReceiverRegistered = true;
            Log.d(TAG, "Storage and file restore broadcast receiver registered for actions: " + 
                  Constants.ACTION_STORAGE_LOCATION_CHANGED + ", " + Constants.ACTION_FILES_RESTORED);
        }
    }

    // ** NEW: Method to unregister the storage location change receiver **
    private void unregisterStorageLocationChangedReceiver() {
        if (isStorageReceiverRegistered && storageLocationChangedReceiver != null && getContext() != null) {
            try {
                requireContext().unregisterReceiver(storageLocationChangedReceiver);
                isStorageReceiverRegistered = false;
                Log.d(TAG, "Storage and file restore broadcast receiver unregistered.");
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
        if (!isReceiverRegistered && getContext() != null) {
            if (recordingCompleteReceiver == null) {
                recordingCompleteReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (intent == null || !Constants.ACTION_RECORDING_COMPLETE.equals(intent.getAction())) {
                            return;
                        }
                        Log.d(TAG, "Received ACTION_RECORDING_COMPLETE broadcast. Current items: " + videoItems.size());
                        boolean success = intent.getBooleanExtra(Constants.EXTRA_RECORDING_SUCCESS, false);
                        String finalUriString = intent.getStringExtra(Constants.EXTRA_RECORDING_URI_STRING);
                        String originalTempSafUriString = intent
                                .getStringExtra(Constants.EXTRA_ORIGINAL_TEMP_SAF_URI_STRING);

                        Log.d(TAG, "  Success: " + success + ", Final URI: " + finalUriString + ", OriginalTempSAF: "
                                + originalTempSafUriString);

                        if (originalTempSafUriString != null) {
                            Uri originalTempSafUri = Uri.parse(originalTempSafUriString);
                            boolean foundAndRemoved = false;
                            for (int i = videoItems.size() - 1; i >= 0; i--) {
                                if (videoItems.get(i).uri.equals(originalTempSafUri)) {
                                    videoItems.remove(i);
                                    foundAndRemoved = true;
                                    Log.d(TAG, "Removed temporary SAF VideoItem: " + originalTempSafUri);
                                    break;
                                }
                            }

                            if (success && finalUriString != null) {
                                Uri finalUri = Uri.parse(finalUriString);
                                DocumentFile finalDocFile = DocumentFile.fromSingleUri(context, finalUri);
                                if (finalDocFile != null && finalDocFile.exists()) {
                                    VideoItem newItem = new VideoItem(
                                            finalUri,
                                            finalDocFile.getName(),
                                            finalDocFile.length(),
                                            finalDocFile.lastModified());
                                    newItem.isTemporary = false;
                                    newItem.isNew = true;
                                    videoItems.add(0, newItem); // Add to top, assuming latest
                                    Log.d(TAG, "Added final SAF VideoItem: " + finalUriString);
                                } else {
                                    Log.w(TAG, "Final SAF DocumentFile does not exist or is null: " + finalUriString);
                                }
                            } else if (!success) {
                                Log.w(TAG, "Processing failed for original temp SAF URI: " + originalTempSafUriString
                                        + ". It was removed from list if present.");
                                // If processing failed, the temp SAF item (if it was ever added) is removed.
                                // The actual temp file on disk might still be there if deletion in service
                                // failed.
                            }

                            if (foundAndRemoved || (success && finalUriString != null)) {
                                // Sort and update UI only if changes were made
                                performVideoSort(); // Re-sort the list
                                if (recordsAdapter != null) {
                                    recordsAdapter.notifyDataSetChanged(); // Consider more specific notifications
                                }
                                updateUiVisibility();
                            }
                            return; // Handled SAF replacement case
                        }

                        // Existing logic for non-SAF replacement (mostly internal storage)
                        if (success && finalUriString != null) {
                            Log.d(TAG, "ACTION_RECORDING_COMPLETE: Success, URI: " + finalUriString
                                    + ". Refreshing list.");
                            // -------------- Fix Start (invalidate cache for new recording) -----------
                            // Clear adapter caches to prevent stale duration data
                            if (recordsAdapter != null) {
                                recordsAdapter.clearCaches();
                                Log.d(TAG, "Cleared adapter caches for new recording");
                            }
                            // Invalidate cache so loadRecordsList will load fresh data including the new video
                            com.fadcam.utils.VideoSessionCache.invalidateOnNextAccess(sharedPreferencesManager);
                            Log.d(TAG, "Cache invalidated for new recording, forcing fresh load");
                            // -------------- Fix End (invalidate cache for new recording) -----------
                            // For non-SAF or if originalTempSafUriString was null, a full refresh is often
                            // simplest
                            // as the new item should be discoverable by loadRecordsList.
                            loadRecordsList(); // This will re-scan and update everything.
                        } else if (!success) {
                            Log.w(TAG, "ACTION_RECORDING_COMPLETE: Failed or no URI. URI: " + finalUriString
                                    + ". Refreshing list.");
                            // -------------- Fix Start (invalidate cache for failed recording) -----------
                            // Clear adapter caches to prevent stale data
                            if (recordsAdapter != null) {
                                recordsAdapter.clearCaches();
                                Log.d(TAG, "Cleared adapter caches for failed recording");
                            }
                            // Invalidate cache so loadRecordsList will load fresh data and clear any temp states
                            com.fadcam.utils.VideoSessionCache.invalidateOnNextAccess(sharedPreferencesManager);
                            Log.d(TAG, "Cache invalidated for failed recording, forcing fresh load");
                            // -------------- Fix End (invalidate cache for failed recording) -----------
                            // Still refresh, as a temp file might need its processing state cleared
                            loadRecordsList();
                        } else if (success && finalUriString == null) {
                            // -------------- Fix Start (handle success without URI) -----------
                            // Recording was successful but no specific URI provided - refresh anyway
                            Log.d(TAG, "ACTION_RECORDING_COMPLETE: Success without URI. Refreshing list to detect new video.");
                            // Clear adapter caches to prevent stale duration data
                            if (recordsAdapter != null) {
                                recordsAdapter.clearCaches();
                                Log.d(TAG, "Cleared adapter caches for successful recording without URI");
                            }
                            com.fadcam.utils.VideoSessionCache.invalidateOnNextAccess(sharedPreferencesManager);
                            Log.d(TAG, "Cache invalidated for successful recording without URI, forcing fresh load");
                            loadRecordsList(); // This will re-scan and find the new video
                            // -------------- Fix End (handle success without URI) -----------
                        }
                    }
                };
            }
            // LocalBroadcastManager.getInstance(getContext()).registerReceiver(recordingCompleteReceiver,
            // new IntentFilter(Constants.ACTION_RECORDING_COMPLETE));
            ContextCompat.registerReceiver(getContext(), recordingCompleteReceiver,
                    new IntentFilter(Constants.ACTION_RECORDING_COMPLETE), ContextCompat.RECEIVER_NOT_EXPORTED);

            isReceiverRegistered = true;
            Log.d(TAG, "RecordingCompleteReceiver registered.");
        }
    }

    private void unregisterRecordingCompleteReceiver() {
        if (isReceiverRegistered && recordingCompleteReceiver != null && getContext() != null) {
            try {
                requireContext().unregisterReceiver(recordingCompleteReceiver);
                isReceiverRegistered = false; // Mark as unregistered
                Log.d(TAG, "ACTION_RECORDING_COMPLETE receiver unregistered.");
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Attempted to unregister recording complete receiver but it wasn't registered?");
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
        // Ensure context is available - using requireContext() is safe within/after
        // onCreate.
        if (sharedPreferencesManager == null) {
            try {
                sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());
                Log.d(TAG, "SharedPreferencesManager initialized in onCreate.");
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error getting context in onCreate: Fragment not attached?", e);
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
            @Override
            public void handleOnBackPressed() {
                if (isInSelectionMode)
                    exitSelectionMode();
                else {
                    if (isEnabled()) {
                        setEnabled(false);
                        requireActivity().getOnBackPressedDispatcher().onBackPressed();
                    }
                }
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

        // -------------- Fix Start for this method(onViewCreated)-----------
        sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());

        // Initialize header elements
        titleText = view.findViewById(R.id.title_text);
        menuButton = view.findViewById(R.id.action_more_options);
        closeButton = view.findViewById(R.id.action_close);
        selectAllContainer = view.findViewById(R.id.action_select_all_container);
        selectAllCheck = view.findViewById(R.id.action_select_all_check);

        // Setup menu button click listener
        if (menuButton != null) {
            menuButton.setOnClickListener(v -> showRecordsSidebar());
        }
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> exitSelectionMode());
        }
        if (selectAllContainer != null && selectAllCheck != null) {
            selectAllContainer.setOnClickListener(v -> {
                if (!isInSelectionMode)
                    return; // ignore when not selecting
                boolean willSelectAll = selectedUris.size() != videoItems.size() || videoItems.isEmpty();
                if (willSelectAll) {
                    selectedUris.clear();
                    for (VideoItem item : videoItems) {
                        if (item != null && item.uri != null)
                            selectedUris.add(item.uri);
                    }
                } else {
                    selectedUris.clear();
                }
                if (recordsAdapter != null)
                    recordsAdapter.setSelectionModeActive(true, selectedUris);
                // animate the header check like picker does
                boolean allSelected = !videoItems.isEmpty() && selectedUris.size() == videoItems.size();
                // Use unified bounce+fade animation for header check
                if (allSelected) {
                    selectAllContainer.setVisibility(View.VISIBLE);
                    selectAllCheck.setVisibility(View.VISIBLE);
                    try {
                        android.graphics.drawable.Drawable d = selectAllCheck.getDrawable();
                        if (d instanceof androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat) {
                            androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat avd = (androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat) d;
                            avd.stop();
                            avd.start();
                        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP
                                && d instanceof android.graphics.drawable.AnimatedVectorDrawable) {
                            android.graphics.drawable.AnimatedVectorDrawable av = (android.graphics.drawable.AnimatedVectorDrawable) d;
                            av.stop();
                            av.start();
                        } else {
                            // fallback to a quick fade in
                            selectAllCheck.setAlpha(0f);
                            selectAllCheck.setScaleX(0f);
                            selectAllCheck.setScaleY(0f);
                            android.animation.ObjectAnimator sx = android.animation.ObjectAnimator
                                    .ofFloat(selectAllCheck, View.SCALE_X, 0f, 1f);
                            android.animation.ObjectAnimator sy = android.animation.ObjectAnimator
                                    .ofFloat(selectAllCheck, View.SCALE_Y, 0f, 1f);
                            android.animation.ObjectAnimator a = android.animation.ObjectAnimator
                                    .ofFloat(selectAllCheck, View.ALPHA, 0f, 1f);
                            sx.setDuration(200);
                            sy.setDuration(200);
                            a.setDuration(160);
                            android.animation.AnimatorSet set = new android.animation.AnimatorSet();
                            set.playTogether(sx, sy, a);
                            set.start();
                        }
                    } catch (Exception e) {
                        /* ignore and fallback */ }
                } else {
                    // uncheck: fade/erase fallback; if AVD present, just fade out
                    try {
                        android.graphics.drawable.Drawable d = selectAllCheck.getDrawable();
                        if (d instanceof androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat) {
                            // can't reliably reverse; fade out the view
                            android.animation.ObjectAnimator a = android.animation.ObjectAnimator
                                    .ofFloat(selectAllCheck, View.ALPHA, selectAllCheck.getAlpha(), 0f);
                            a.setDuration(180);
                            a.setInterpolator(new android.view.animation.AccelerateInterpolator());
                            a.start();
                        } else {
                            android.animation.ObjectAnimator a = android.animation.ObjectAnimator
                                    .ofFloat(selectAllCheck, View.ALPHA, selectAllCheck.getAlpha(), 0f);
                            android.animation.ObjectAnimator sx = android.animation.ObjectAnimator
                                    .ofFloat(selectAllCheck, View.SCALE_X, selectAllCheck.getScaleX(), 0f);
                            android.animation.ObjectAnimator sy = android.animation.ObjectAnimator
                                    .ofFloat(selectAllCheck, View.SCALE_Y, selectAllCheck.getScaleY(), 0f);
                            a.setDuration(180);
                            sx.setDuration(180);
                            sy.setDuration(180);
                            android.animation.AnimatorSet set = new android.animation.AnimatorSet();
                            set.playTogether(a, sx, sy);
                            set.start();
                        }
                    } catch (Exception e) {
                        /* ignore */ }
                    selectAllContainer.setVisibility(View.VISIBLE);
                }
                updateUiForSelectionMode();
            });
        }

        originalToolbarTitle = getString(R.string.records_title);
        // -------------- Fix Ended for this method(onViewCreated)-----------

        loadingIndicator = view.findViewById(R.id.loading_indicator);
        recyclerView = view.findViewById(R.id.recycler_view_records);
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh_layout);
        emptyStateContainer = view.findViewById(R.id.empty_state_container);
        fabDeleteSelected = view.findViewById(R.id.fab_delete_selected);
        fabScrollNavigation = view.findViewById(R.id.fab_scroll_navigation);
        applockOverlay = view.findViewById(R.id.applock_overlay);

        setupRecyclerView();
        setupFabListeners();

        // Setup SwipeRefreshLayout
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(() -> {
                Log.d(TAG, "Swipe to refresh triggered.");

                // -------------- Fix Start (onRefresh) - Professional refresh handling with
                // skeleton -----------

                // Clear session cache to force fresh data
                com.fadcam.utils.VideoSessionCache.clearSessionCache();
                com.fadcam.utils.VideoSessionCache.invalidateOnNextAccess(sharedPreferencesManager);

                // Invalidate stats cache for manual refresh
                com.fadcam.utils.VideoStatsCache.invalidateStats(sharedPreferencesManager);
                Log.d(TAG, "Invalidated video caches for manual refresh");

                // Clear adapter caches
                if (recordsAdapter != null) {
                    Log.d(TAG, "Swipe refresh: Clearing adapter caches for hard refresh");
                    recordsAdapter.clearCaches();
                }

                // Reset pagination and state variables
                currentPage = 0;
                allLoadedItems.clear();
                hasMoreItems = true;
                videoItemPositionCache.clear();
                isLoading = false; // Reset loading state

                // Show skeleton immediately for smooth UX
                int estimatedCount = com.fadcam.utils.VideoSessionCache.getCachedVideoCount(sharedPreferencesManager);
                if (estimatedCount <= 0) {
                    estimatedCount = Math.max(6, videoItems.size()); // Use previous count or minimum 6
                }

                showSkeletonLoading(estimatedCount);

                // Load fresh data silently in background (loadRecordsList will handle stopping
                // refresh indicator)
                loadRecordsList();

                // -------------- Fix End (onRefresh) -----------
            });
        } else {
            Log.e(TAG, "SwipeRefreshLayout is null after findViewById!");
        }

        // --- Initial Data Load ---
        // Load data *after* adapter is set up
        if (videoItems == null || videoItems.isEmpty()) { // videoItems might be retained across config changes
            if (videoItems == null)
                videoItems = new ArrayList<>(); // Ensure list exists

            // -------------- CRITICAL FIX: Only load when fragment is actually visible
            // -----------
            androidx.viewpager2.widget.ViewPager2 viewPager2 = getActivity() != null
                    ? getActivity().findViewById(R.id.view_pager)
                    : null;
            boolean isCurrentlyVisible = viewPager2 != null && viewPager2.getCurrentItem() == 1 && isVisible();

            if (!isCurrentlyVisible) {
                Log.d(TAG,
                        "onViewCreated: Fragment not currently visible, deferring load until user navigates here. ViewPager position: "
                                + (viewPager2 != null ? viewPager2.getCurrentItem() : "null"));
                return; // Don't load anything yet
            }

            Log.d(TAG, "onViewCreated: Fragment is visible, initiating loadRecordsList.");

            // -------------- Fix Start (immediate skeleton) - Show skeleton immediately to
            // prevent flash -----------
            // CRITICAL: Show skeleton IMMEDIATELY to prevent empty flash
            int estimatedCount = com.fadcam.utils.VideoSessionCache.getCachedVideoCount(sharedPreferencesManager);
            if (estimatedCount <= 0) {
                estimatedCount = 12; // Default reasonable skeleton count
            }
            Log.d(TAG, "onViewCreated: Showing " + estimatedCount + " skeleton items immediately");
            showSkeletonLoading(estimatedCount);
            // -------------- Fix End (immediate skeleton) -----------

            isInitialLoad = true; // Mark as initial load
            loadRecordsList();
        } else {
            Log.d(TAG, "onViewCreated: Existing data found (" + videoItems.size() + " items), updating UI visibility.");
            // If data exists (e.g., fragment recreated), update adapter & UI
            if (recordsAdapter != null)
                recordsAdapter.updateRecords(videoItems); // Make sure adapter has the data
            updateUiVisibility();
        }
        // ----- Fix Start: Show AppLock overlay immediately if required and not
        // unlocked (session-based) -----
        boolean isAppLockEnabled = sharedPreferencesManager.isAppLockEnabled();
        boolean isSessionUnlocked = sharedPreferencesManager.isAppLockSessionUnlocked();
        if (isAppLockEnabled && !isSessionUnlocked && com.guardanis.applock.AppLock.isEnrolled(requireContext())) {
            fadeOverlay(true);
        } else {
            fadeOverlay(false);
        }
        // ----- Fix End: Show AppLock overlay immediately if required and not unlocked
        // (session-based) -----

        // ----- Fix Start: Apply theme colors to FABs, top bar, and bottom sheet in
        // RecordsFragment -----
        // -------------- Fix Start for this method(applyTheme)-----------
        // Apply theme to top bar - header bar background is handled by
        // ?attr/colorTopBar in XML
        // No need to set background color programmatically
        // -------------- Fix Ended for this method(applyTheme)-----------
        // Apply theme to FABs
        int colorButton = resolveThemeColor(R.attr.colorButton);
        if (fabDeleteSelected != null) {
            fabDeleteSelected.setBackgroundTintList(android.content.res.ColorStateList.valueOf(colorButton));
            fabDeleteSelected
                    .setImageTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE));
        }
        // ----- Fix End: Apply theme colors to FABs, top bar, and bottom sheet in
        // RecordsFragment -----
    } // End onViewCreated

    @Override
    public void onResume() {
        super.onResume();
        Log.i(TAG, "LOG_LIFECYCLE: onResume called.");

        // Always update toolbar/menu and AppLock state
        androidx.viewpager2.widget.ViewPager2 viewPager = getActivity() != null
                ? getActivity().findViewById(R.id.view_pager)
                : null;
        if (viewPager != null && viewPager.getCurrentItem() == 1 && isVisible()) {
            checkAppLock();
        }
        // -------------- Fix Start for this method(onResume)-----------
        // Update title text
        if (titleText != null) {
            titleText.setText(originalToolbarTitle != null ? originalToolbarTitle : getString(R.string.records_title));
            Log.d(TAG, "Title text updated in onResume.");
        } else {
            Log.w(TAG, "Could not update title in onResume - titleText is null.");
        }
        // -------------- Fix Ended for this method(onResume)-----------
        if (sharedPreferencesManager == null && getContext() != null) {
            sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());
        }

        // ----- Fix Start: Check for theme changes and update adapter -----
        if (recordsAdapter != null && sharedPreferencesManager != null) {
            String currentTheme = sharedPreferencesManager.sharedPreferences
                    .getString(com.fadcam.Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
            boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
            recordsAdapter.setSnowVeilTheme(isSnowVeilTheme);
            Log.d(TAG, "onResume: Updated Snow Veil theme flag on adapter: " + isSnowVeilTheme);
        }
        // ----- Fix End: Check for theme changes and update adapter -----

        // -------------- Fix Start (onResume) - Smart loading to prevent duplication
        // -----------

        // -------------- CRITICAL FIX: Only load when fragment is actually visible to
        // user -----------
        androidx.viewpager2.widget.ViewPager2 viewPager2 = getActivity() != null
                ? getActivity().findViewById(R.id.view_pager)
                : null;
        boolean isCurrentlyVisible = viewPager2 != null && viewPager2.getCurrentItem() == 1 && isVisible();

        if (!isCurrentlyVisible) {
            Log.d(TAG, "onResume: Fragment not currently visible to user, skipping load. ViewPager position: "
                    + (viewPager2 != null ? viewPager2.getCurrentItem() : "null") + ", isVisible: " + isVisible());
            return;
        }

        Log.d(TAG, "onResume: Fragment is visible to user, proceeding with load check");

        // -------------- Fix Start (onResume) - Prevent duplicate loading -----------

        // Only reload if we actually need to (no data or cache invalidated)
        boolean hasData = recordsAdapter != null && recordsAdapter.getItemCount() > 0 && !videoItems.isEmpty();
        boolean hasValidCache = com.fadcam.utils.VideoSessionCache.isSessionCacheValid() ||
                com.fadcam.utils.VideoSessionCache.hasCachedData(sharedPreferencesManager);

        if (hasData && hasValidCache && !isLoading) {
            Log.d(TAG, "onResume: Already have " + videoItems.size() + " videos loaded, skipping duplicate load");
            updateUiVisibility(); // Just update visibility state
        } else if (!isLoading) {
            Log.i(TAG, "LOG_REFRESH: Loading from onResume - hasData: " + hasData + ", hasCache: " + hasValidCache);
            loadRecordsList();
        } else {
            Log.d(TAG, "onResume: Already loading, skipping duplicate request");
        }

        // -------------- Fix End (onResume) -----------

        // -------------- Fix End (onResume) -----------
        // no-op: view mode toggle is in Records Options side sheet
        // ----- Fix Start: Always invalidate options menu to ensure correct menu for
        // Records tab -----
        requireActivity().invalidateOptionsMenu();
        // ----- Fix End: Always invalidate options menu to ensure correct menu for
        // Records tab -----
    }

    /**
     * Call this method when the fragment becomes visible to the user for the first
     * time
     * or when they navigate back to it. This handles lazy loading.
     */
    public void onFragmentBecameVisible() {
        Log.d(TAG, "onFragmentBecameVisible: Fragment is now visible to user");

        // Check if we need to load data for the first time
        if ((videoItems == null || videoItems.isEmpty()) && !isLoading) {
            Log.d(TAG, "onFragmentBecameVisible: No data loaded yet, starting initial load");

            if (videoItems == null) {
                videoItems = new ArrayList<>();
            }

            // Show skeleton loading
            int estimatedCount = com.fadcam.utils.VideoSessionCache.getCachedVideoCount(sharedPreferencesManager);
            if (estimatedCount <= 0) {
                estimatedCount = 12;
            }
            Log.d(TAG, "onFragmentBecameVisible: Showing " + estimatedCount + " skeleton items");
            showSkeletonLoading(estimatedCount);

            isInitialLoad = true;
            loadRecordsList();
        } else {
            Log.d(TAG, "onFragmentBecameVisible: Data already loaded (" + videoItems.size() + " items)");
            updateUiVisibility();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // Reset unlock state when leaving the fragment
        // isUnlocked = false;

        // ... existing code ...
    }

    @Override
    public void onSaveToGalleryStarted(String fileName) {
        if (getContext() == null)
            return; // Check context
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss(); // Dismiss any previous dialog
        }

        // Check for Snow Veil theme
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME,
                Constants.DEFAULT_APP_THEME);
        boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
        int dialogTheme = isSnowVeilTheme ? R.style.ThemeOverlay_FadCam_SnowVeil_Dialog
                : R.style.ThemeOverlay_FadCam_Dialog;

        // Create and show the progress dialog
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getContext(), dialogTheme);
        LayoutInflater inflater = LayoutInflater.from(getContext());
        View dialogView = inflater.inflate(R.layout.dialog_progress, null);

        TextView progressText = dialogView.findViewById(R.id.progress_text);
        if (progressText != null) {
            progressText.setText("Saving '" + fileName + "'..."); // Indicate which file
            // Set text color based on theme
            progressText.setTextColor(ContextCompat.getColor(requireContext(),
                    isSnowVeilTheme ? android.R.color.black : android.R.color.white));
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

        // Optional: Refresh the list if needed, e.g., if saving could somehow affect
        // displayed items,
        // though usually not necessary for 'Save to Gallery' which is a copy operation.
        // if (success) { loadRecordsList(); }
    }

    // --- End Implementation of RecordActionListener ---

    // ** In RecordsFragment.java **

    private void setupRecyclerView() {
        Log.d(TAG, "Setting up RecyclerView...");

        // Initialize view components
        emptyStateContainer = getView().findViewById(R.id.empty_state_container);
        recyclerView = getView().findViewById(R.id.recycler_view_records);
        loadingIndicator = getView().findViewById(R.id.loading_indicator);

        // Apply optimized layout settings
        recyclerView.setItemViewCacheSize(20); // Increase view cache size
        recyclerView.setDrawingCacheEnabled(true);
        recyclerView.setDrawingCacheQuality(View.DRAWING_CACHE_QUALITY_HIGH);
        recyclerView.setHasFixedSize(true); // Use if all items have the same size

        recyclerView.addItemDecoration(itemDecoration = new SpacesItemDecoration(4)); // Default spacing

        // Create RecordsAdapter and setup
        recordsAdapter = new RecordsAdapter(
                getContext(),
                videoItems,
                executorService,
                sharedPreferencesManager,
                this, // OnVideoClickListener
                this, // OnVideoLongClickListener
                this // RecordActionListener
        );

        // Set any theme preferences the adapter needs
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(
                com.fadcam.Constants.PREF_APP_THEME, com.fadcam.Constants.DEFAULT_APP_THEME);
        recordsAdapter.setSnowVeilTheme("Snow Veil".equals(currentTheme));

        // Set the layout manager
        setLayoutManager();

        // Set adapter
        recyclerView.setAdapter(recordsAdapter);

        // Setup RecyclerView scroll listener for optimized loading
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                boolean wasScrolling = isScrolling;

                // Update scrolling state for efficient thumbnail loading
                isScrolling = newState != RecyclerView.SCROLL_STATE_IDLE;
                if (recordsAdapter != null) {
                    recordsAdapter.setScrolling(isScrolling);
                }

                // When scrolling stops, refresh visible items for better quality thumbnails
                if (wasScrolling && !isScrolling) {
                    refreshVisibleItems();
                }

                // Show FAB when user starts scrolling from the top downward; pin until we
                // return to top
                if (isScrolling) {
                    // Will be made visible in onScrolled when necessary
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);

                // -------------- Fix Start (onScrolled) - Navigation FAB management with scroll
                // direction tracking -----------

                // Track scroll direction based on dy parameter
                if (dy > 0) {
                    isScrollingDown = true; // User is scrolling down
                } else if (dy < 0) {
                    isScrollingDown = false; // User is scrolling up
                }

                // Update navigation FAB based on current scroll position and direction
                updateNavigationFab();

                // Determine whether to show/pin/hide FAB: if user has scrolled down from top
                // (firstVisible>0) and not at end
                RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
                int firstVisible = 0;
                int lastVisibleItemPosition = 0;
                int totalItemCount = 0;
                if (layoutManager instanceof GridLayoutManager) {
                    firstVisible = ((GridLayoutManager) layoutManager).findFirstVisibleItemPosition();
                    lastVisibleItemPosition = ((GridLayoutManager) layoutManager).findLastVisibleItemPosition();
                    totalItemCount = layoutManager.getItemCount();
                } else if (layoutManager instanceof LinearLayoutManager) {
                    firstVisible = ((LinearLayoutManager) layoutManager).findFirstVisibleItemPosition();
                    lastVisibleItemPosition = ((LinearLayoutManager) layoutManager).findLastVisibleItemPosition();
                    totalItemCount = layoutManager.getItemCount();
                }
                if (firstVisible > 0 && lastVisibleItemPosition < totalItemCount - 1) {
                    // Pinned visible
                    showNavigationFab();
                } else {
                    // At top or at end: hide FAB
                    if (fabScrollNavigation != null) {
                        fabScrollNavigation.animate().alpha(0f).setDuration(180)
                                .withEndAction(() -> fabScrollNavigation.setVisibility(View.GONE)).start();
                    }
                }

                // -------------- Fix End (onScrolled) -----------

                // Don't do any loading if we're in selection mode or search mode
                if (isInSelectionMode() || isSearchActive() || isLoading) {
                    return;
                }

                // ...existing code...
            }
        });

        // Make sure spinner is visible during initial load
        if (loadingIndicator != null) {
            loadingIndicator.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Refreshes the thumbnails of currently visible items for better quality
     * when scrolling stops
     */
    private void refreshVisibleItems() {
        if (recyclerView == null || recordsAdapter == null || getContext() == null)
            return;

        RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
        if (layoutManager == null)
            return;

        int firstVisible, lastVisible;
        if (layoutManager instanceof GridLayoutManager) {
            GridLayoutManager gridLayoutManager = (GridLayoutManager) layoutManager;
            firstVisible = gridLayoutManager.findFirstVisibleItemPosition();
            lastVisible = gridLayoutManager.findLastVisibleItemPosition();
        } else {
            LinearLayoutManager linearLayoutManager = (LinearLayoutManager) layoutManager;
            firstVisible = linearLayoutManager.findFirstVisibleItemPosition();
            lastVisible = linearLayoutManager.findLastVisibleItemPosition();
        }

        // Limit the range to valid indices
        if (firstVisible < 0)
            firstVisible = 0;
        if (lastVisible >= recordsAdapter.getItemCount())
            lastVisible = recordsAdapter.getItemCount() - 1;

        // Notify these items to refresh their thumbnails
        if (lastVisible >= firstVisible) {
            recordsAdapter.notifyItemRangeChanged(firstVisible, lastVisible - firstVisible + 1, "refreshThumbnail");
        }
    }

    // --- End Implementation of RecordActionListener ---

    // ** In RecordsFragment.java **

    private void setupFabListeners() {
        Log.d(TAG, "Setting up FAB listeners.");
        // Ensure FABs are not null before setting listeners
        // removed FAB toggle; use side sheet's View mode row

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
            Log.d(TAG, "FAB Delete listener set.");
        } else {
            Log.w(TAG, "fabDeleteSelected is null in setupFabListeners (Might be initially GONE).");
        }

        // -------------- Fix Start (setupFabListeners) - Navigation FAB setup
        // -----------

        if (fabScrollNavigation != null) {
            fabScrollNavigation.setOnClickListener(v -> {
                Log.d(TAG, "fabScrollNavigation CLICKED!");
                if (!isAdded() || getContext() == null || recyclerView == null) {
                    Log.e(TAG, "fabScrollNavigation clicked but fragment/view not ready!");
                    return;
                }
                handleNavigationFabClick();
            });
            Log.d(TAG, "FAB Navigation listener set.");
        } else {
            Log.w(TAG, "fabScrollNavigation is null in setupFabListeners.");
        }

        // -------------- Fix End (setupFabListeners) -----------
    }

    private void toggleViewMode() {
        vibrate();
        isGridView = !isGridView;
        setLayoutManager();
        updateFabIcons();
    }

    private void updateFabIcons() {
        /* removed FAB */ }

    // Load records from Internal or SAF based on preference

    // ** In RecordsFragment.java **

    // --- Remove the paging related fields ---
    private static final int PAGE_SIZE = 30; // Number of videos to load per page
    private static final int PRELOAD_DISTANCE = 10; // Number of items to preload ahead
    private boolean isLoadingMore = false;
    private boolean hasMoreItems = true;
    private int currentPage = 0;
    private final List<VideoItem> allLoadedItems = new ArrayList<>();
    private final SparseArray<String> videoItemPositionCache = new SparseArray<>();
    private DebouncedRunnable loadMoreDebouncer;

    // --- Replace with lazy loading related fields ---
    private static final int BATCH_SIZE = 15; // Load items in smaller batches
    private static final int SCROLL_THRESHOLD = 8; // Load more when user is this many items from the end
    private boolean isLoading = false;
    private boolean isInitialLoad = true;
    private int totalItems = 0; // Track the total number of items
    private final List<VideoItem> cachedInternalItems = new ArrayList<>(); // Cache internal items
    private final List<VideoItem> cachedSafItems = new ArrayList<>(); // Cache SAF items
    private final List<VideoItem> cachedTempItems = new ArrayList<>(); // Cache temp items

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
        
        // Scan FadCam directory
        File fadCamDir = new File(recordsDir, Constants.RECORDING_DIRECTORY);
        items.addAll(scanDirectory(fadCamDir, "FadCam"));
        
        // Scan FadRec directory
        File fadRecDir = new File(recordsDir, Constants.RECORDING_DIRECTORY_FADREC);
        items.addAll(scanDirectory(fadRecDir, "FadRec"));
        
        Log.i(TAG, "LOG_GET_INTERNAL: Found " + items.size() + " total internal records. END.");
        return items;
    }
    
    /**
     * Helper method to scan a directory for video files.
     */
    private List<VideoItem> scanDirectory(File directory, String dirName) {
        List<VideoItem> items = new ArrayList<>();
        Log.d(TAG, "LOG_GET_INTERNAL: Checking directory: " + directory.getAbsolutePath());

        if (directory.exists() && directory.isDirectory()) {
            Log.d(TAG, "LOG_GET_INTERNAL: Directory exists. Listing files.");
            File[] files = directory.listFiles();
            if (files != null) {
                Log.d(TAG, "LOG_GET_INTERNAL: Found " + files.length + " files/dirs in " + dirName + " storage.");
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith("." + Constants.RECORDING_FILE_EXTENSION)
                            && !file.getName().startsWith("temp_")) {
                        long lastModifiedMeta = file.lastModified();
                        long timestampFromFile = Utils.parseTimestampFromFilename(file.getName());
                        long finalTimestamp = lastModifiedMeta;

                        if (lastModifiedMeta <= 0 && timestampFromFile > 0) {
                            finalTimestamp = timestampFromFile;
                        } else if (lastModifiedMeta <= 0 && timestampFromFile <= 0) {
                            finalTimestamp = System.currentTimeMillis();
                        }
                        Uri uri = Uri.fromFile(file);
                        VideoItem newItem = new VideoItem(uri, file.getName(), file.length(), finalTimestamp);
                        newItem.isTemporary = false;
                        newItem.isNew = Utils.isVideoConsideredNew(finalTimestamp);
                        items.add(newItem);
                        Log.v(TAG, "LOG_GET_INTERNAL: Added internal item: " + file.getName());
                    } else {
                        Log.v(TAG, "LOG_GET_INTERNAL: Skipped item (not a valid video file or is temp): "
                                + file.getName());
                    }
                }
            } else {
                Log.w(TAG, "LOG_GET_INTERNAL: Directory listFiles returned null for " + dirName);
            }
        } else {
            Log.i(TAG, "LOG_GET_INTERNAL: Directory does not exist yet: " + directory.getAbsolutePath());
        }
        return items;
    }

    private List<VideoItem> getSafRecordsList(Uri treeUri) {
        return getSafRecordsListProgressive(treeUri, null);
    }

    /**
     * Progressive SAF loading with chunked processing to avoid main thread blocking
     */
    private List<VideoItem> getSafRecordsListProgressive(Uri treeUri, ProgressCallback callback) {
        Log.d(TAG, "LOG_GET_SAF: getSafRecordsList START for URI: " + treeUri);
        List<VideoItem> safVideoItems = new ArrayList<>();
        if (getContext() == null || treeUri == null) {
            Log.w(TAG, "LOG_GET_SAF: Context or Tree URI is null, returning empty list.");
            return safVideoItems;
        }

        DocumentFile targetDir = DocumentFile.fromTreeUri(getContext(), treeUri);
        if (targetDir == null || !targetDir.isDirectory() || !targetDir.canRead()) {
            Log.e(TAG, "LOG_GET_SAF: Cannot access or read from SAF directory: " + treeUri);
            return safVideoItems;
        }
        Log.d(TAG, "LOG_GET_SAF: SAF Directory " + targetDir.getName() + " is accessible. Listing files.");

        DocumentFile[] files = targetDir.listFiles();
        Log.d(TAG, "LOG_GET_SAF: Found " + files.length + " files/dirs in SAF location.");

        // Progressive processing: process files in chunks to avoid blocking
        final int CHUNK_SIZE = 10; // Process 10 files at a time
        int totalFiles = files.length;

        for (int i = 0; i < totalFiles; i += CHUNK_SIZE) {
            int endIndex = Math.min(i + CHUNK_SIZE, totalFiles);

            // Process chunk
            for (int j = i; j < endIndex; j++) {
                DocumentFile docFile = files[j];
                if (docFile == null || !docFile.isFile()) {
                    Log.d(TAG, "LOG_GET_SAF: Skipped item (not a file or null): "
                            + (docFile != null ? docFile.getName() : "null"));
                    continue;
                }

                String fileName = docFile.getName();
                String mimeType = docFile.getType();

                if (fileName != null && mimeType != null && mimeType.startsWith("video/")) {
                    if (fileName.endsWith(Constants.RECORDING_FILE_EXTENSION)) {
                        if (fileName.startsWith("temp_")) {
                            Log.d(TAG, "LOG_GET_SAF: Found temporary SAF video: " + fileName);
                            VideoItem tempVideoItem = new VideoItem(
                                    docFile.getUri(),
                                    fileName,
                                    docFile.length(),
                                    docFile.lastModified());
                            tempVideoItem.isTemporary = true;
                            tempVideoItem.isNew = false;
                            // Check if this temp file is currently being processed
                            if (currentlyProcessingUris.contains(docFile.getUri())) {
                                tempVideoItem.isProcessingUri = true;
                                Log.d(TAG,
                                        "LOG_GET_SAF: Temporary SAF video " + fileName + " is marked as processing.");
                            }
                            safVideoItems.add(tempVideoItem);
                        } else if (fileName.startsWith(Constants.RECORDING_DIRECTORY + "_")) {
                            Log.d(TAG, "LOG_GET_SAF: Added SAF item: " + fileName);
                            VideoItem newItem = new VideoItem(
                                    docFile.getUri(),
                                    fileName,
                                    docFile.length(),
                                    docFile.lastModified());
                            newItem.isTemporary = false;
                            newItem.isNew = Utils.isVideoConsideredNew(docFile.lastModified());
                            safVideoItems.add(newItem);
                        } else {
                            // Log other video files that don't match temp or standard FadCam prefix but are
                            // video type
                            Log.d(TAG, "LOG_GET_SAF: Added OTHER video item (non-FadCam, non-temp): " + fileName);
                            VideoItem newItem = new VideoItem(
                                    docFile.getUri(),
                                    fileName,
                                    docFile.length(),
                                    docFile.lastModified());
                            newItem.isTemporary = false;
                            newItem.isNew = Utils.isVideoConsideredNew(docFile.lastModified());
                            safVideoItems.add(newItem);
                        }
                    } else {
                        Log.d(TAG, "LOG_GET_SAF: Skipped item (not a video file with correct extension): " + fileName
                                + " | type: " + mimeType);
                    }
                } else {
                    Log.d(TAG, "LOG_GET_SAF: Skipped item (not a valid video file or is temp): " + fileName
                            + " | isFile: " + docFile.isFile() + " | type: " + mimeType);
                }
            }

            // Progressive callback: update UI with current progress
            if (callback != null && !safVideoItems.isEmpty()) {
                int progress = Math.round(((float) endIndex / totalFiles) * 100);
                callback.onProgress(new ArrayList<>(safVideoItems), progress, endIndex < totalFiles);
            }

            // Small delay between chunks to prevent overwhelming the system
            if (endIndex < totalFiles) {
                try {
                    Thread.sleep(10); // 10ms pause between chunks
                } catch (InterruptedException e) {
                    Log.w(TAG, "LOG_GET_SAF: Progressive loading interrupted");
                    break;
                }
            }
        }

        Log.d(TAG, "LOG_GET_SAF: Found " + safVideoItems.size() + " SAF records. END.");
        return safVideoItems;
    }

    /**
     * Callback interface for progressive loading updates
     */
    public interface ProgressCallback {
        void onProgress(List<VideoItem> currentItems, int progressPercent, boolean hasMore);
    }

    // --- Permission Check ---

    private boolean hasSafPermission(Uri treeUri) {
        Context context = getContext();
        if (context == null || treeUri == null)
            return false;

        try {
            // Check if we still have persistent permission
            List<UriPermission> persistedUris = context.getContentResolver().getPersistedUriPermissions();
            boolean permissionFound = false;
            for (UriPermission uriPermission : persistedUris) {
                if (uriPermission.getUri().equals(treeUri) && uriPermission.isReadPermission()
                        && uriPermission.isWritePermission()) {
                    permissionFound = true;
                    break;
                }
            }

            if (!permissionFound) {
                Log.w(TAG, "No persisted R/W permission found for URI: " + treeUri);
                return false;
            }

            // Additionally, try a quick read check via DocumentFile
            DocumentFile docDir = DocumentFile.fromTreeUri(context, treeUri);
            if (docDir != null && docDir.canRead()) {
                return true; // Both permission entry exists and basic read check passes
            } else {
                Log.w(TAG, "Persisted permission found, but DocumentFile check failed (cannot read or null). URI: "
                        + treeUri);
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking SAF permission for URI: " + treeUri, e);
            return false;
        }
    }

    // --- Click Listeners & Selection ---

    // In RecordsFragment.java

    @Override
    public void onVideoClick(VideoItem videoItem) {
        if (videoItem == null || videoItem.uri == null)
            return;

        if (isInSelectionMode) {
            // Mode ACTIVE: Click toggles selection
            Log.d(TAG, "Fragment onVideoClick: Toggling selection for " + videoItem.displayName);
            toggleSelection(videoItem.uri); // Toggle the item
        } else {
            // Mode INACTIVE: Click plays video
            Log.d(TAG, "Fragment onVideoClick: Playing video " + videoItem.displayName);
            String uriString = videoItem.uri.toString();
            sharedPreferencesManager.addOpenedVideoUri(uriString);
            if (recordsAdapter != null) {
                int pos = recordsAdapter.findPositionByUri(videoItem.uri);
                if (pos != -1)
                    recordsAdapter.notifyItemChanged(pos);
            }
            Intent intent = new Intent(getActivity(), VideoPlayerActivity.class);
            intent.setData(videoItem.uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start player", e);
                Toast.makeText(getContext(), "Error opening video", Toast.LENGTH_SHORT).show();
            }
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
                        if (uriString == null) {
                            Log.w(TAG, "Received processing broadcast without URI.");
                            return;
                        }
                        Uri fileUri = Uri.parse(uriString);
                        boolean changed = false;

                        if (Constants.ACTION_PROCESSING_STARTED.equals(intent.getAction())) {
                            Log.i(TAG, "Processing started for: " + fileUri);
                            changed = currentlyProcessingUris.add(fileUri);
                        } else if (Constants.ACTION_PROCESSING_FINISHED.equals(intent.getAction())) {
                            Log.i(TAG, "Processing finished for: " + fileUri);
                            changed = currentlyProcessingUris.remove(fileUri);

                            // Clear our current cache and force a complete refresh of the view
                            if (recordsAdapter != null) {
                                recordsAdapter.clearCaches();
                            }

                            // Force a full refresh of the data
                            currentPage = 0;
                            allLoadedItems.clear();
                            videoItems.clear();

                            // Perform complete data reload from scratch
                            loadRecordsList();

                            // Also update the adapter to ensure the processing indicator is gone
                            if (recordsAdapter != null) {
                                recordsAdapter.updateProcessingUris(currentlyProcessingUris);
                                recordsAdapter.notifyDataSetChanged();
                            }

                            // If using SwipeRefreshLayout, ensure it's not showing refresh state
                            if (swipeRefreshLayout != null && swipeRefreshLayout.isRefreshing()) {
                                swipeRefreshLayout.setRefreshing(false);
                            }

                            return; // Exit early, we've handled everything
                        }

                        // If processing started/stopped and the set changed, update adapter state
                        if (changed && recordsAdapter != null) {
                            Log.d(TAG, "Updating adapter processing state. Processing count: "
                                    + currentlyProcessingUris.size());
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
            ContextCompat.registerReceiver(requireContext(), processingStateReceiver, filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            isProcessingReceiverRegistered = true;
            Log.d(TAG, "Processing state receivers registered.");
        }
    }

    private void unregisterProcessingStateReceivers() {
        if (isProcessingReceiverRegistered && processingStateReceiver != null && getContext() != null) {
            try {
                requireContext().unregisterReceiver(processingStateReceiver);
            } catch (Exception e) {
                /* ignore */}
            isProcessingReceiverRegistered = false;
            Log.d(TAG, "Processing state receivers unregistered.");
        }
    }

    @Override
    public void onVideoLongClick(VideoItem videoItem, boolean isSelectedIntention) { // isSelectedIntention is less
                                                                                     // relevant here
        if (videoItem == null || videoItem.uri == null)
            return;
        Log.d(TAG, "Fragment onVideoLongClick: " + videoItem.displayName);

        if (!isInSelectionMode) {
            enterSelectionMode(); // Enter selection mode on the FIRST long press
        }
        // Whether entering or already in, toggle the long-pressed item
        toggleSelection(videoItem.uri);
        vibrate(); // Haptic feedback
    }

    // --- Selection Management ---
    /** Enters selection mode and updates adapter/UI. */
    private void enterSelectionMode() {
        if (isInSelectionMode) return;
        isInSelectionMode = true;
        if (recordsAdapter != null) {
            recordsAdapter.setSelectionModeActive(true, selectedUris);
        }
        updateUiForSelectionMode();
    }

    /** Exits selection mode, clears selections, and updates adapter/UI. */
    private void exitSelectionMode() {
        if (!isInSelectionMode) return;
        isInSelectionMode = false;
        selectedUris.clear();
        if (recordsAdapter != null) {
            recordsAdapter.setSelectionModeActive(false, selectedUris);
        }
        updateUiForSelectionMode();
    }

    /** Toggles a single item's selection state and refreshes adapter/UI. */
    private void toggleSelection(@Nullable Uri uri) {
        if (uri == null) return;
        if (selectedUris.contains(uri)) {
            selectedUris.remove(uri);
        } else {
            selectedUris.add(uri);
        }
        if (recordsAdapter != null) {
            recordsAdapter.setSelectionModeActive(true, selectedUris);
        }
        updateUiForSelectionMode();
    }

    private void confirmDeleteSelected() {
        vibrate();
        if (!isAdded() || getContext() == null || selectedUris.isEmpty()) {
            Log.w(TAG, "confirmDeleteSelected called but cannot proceed (not attached, context null, or selection empty).");
            return;
        }
        int count = selectedUris.size();
        Log.d(TAG, "Showing confirm delete bottom sheet for " + count + " items.");

        // Title reflects count; helper contains the previous note text.
        String title = getResources().getString(R.string.dialog_multi_video_del_title) + " (" + count + ")";
        String helper = getResources().getString(R.string.dialog_multi_video_del_note);

        InputActionBottomSheetFragment sheet = InputActionBottomSheetFragment.newConfirm(
                title,
                getString(R.string.dialog_multi_video_del_yes),
                getString(R.string.dialog_multi_video_del_no),
                R.drawable.ic_delete
        ).withHelperText(helper);

        sheet.setCallbacks(new InputActionBottomSheetFragment.Callbacks() {
            @Override public void onImportConfirmed(org.json.JSONObject json) { /* not used */ }
            @Override public void onResetConfirmed() {
                deleteSelectedVideos();
            }
        });
        sheet.show(getParentFragmentManager(), "delete_multi_confirm_sheet");
    }

    // --- UI Updates ---
    // -------------- Fix Start for this method(updateUiForSelectionMode)-----------
    /** Updates Title, FABs based on whether selection mode is active */
    private void updateUiForSelectionMode() {
        if (!isAdded() || titleText == null || getActivity() == null) {
            Log.w(TAG, "Cannot update selection UI - not ready");
            return;
        }

        if (isInSelectionMode) {
            int count = selectedUris.size();
            titleText.setText(count > 0 ? count + " selected" : "Select items");
            fabDeleteSelected.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
            // FAB removed
            // Show left-side close button and hide more-options
            if (closeButton != null) {
                closeButton.setVisibility(View.VISIBLE);
                closeButton.setImageResource(R.drawable.ic_close);
                closeButton.setContentDescription(getString(R.string.universal_close));
            }
            if (menuButton != null) {
                menuButton.setVisibility(View.GONE);
            }
            if (selectAllContainer != null && selectAllCheck != null) {
                selectAllContainer.setVisibility(View.VISIBLE);
                boolean allSelected = !videoItems.isEmpty() && selectedUris.size() == videoItems.size();
                int tint = allSelected ? resolveThemeColor(R.attr.colorToggle) : android.graphics.Color.WHITE;
                selectAllCheck.setImageTintList(android.content.res.ColorStateList.valueOf(tint));
                if (allSelected) {
                    selectAllCheck.setScaleX(1f);
                    selectAllCheck.setScaleY(1f);
                    selectAllCheck.setAlpha(1f);
                } else {
                    selectAllCheck.setScaleX(0f);
                    selectAllCheck.setScaleY(0f);
                    selectAllCheck.setAlpha(0f);
                }
            }
        } else {
            titleText.setText(originalToolbarTitle != null ? originalToolbarTitle : getString(R.string.records_title));
            fabDeleteSelected.setVisibility(View.GONE);
            // FAB removed
            // Restore more-options icon and hide close button
            if (menuButton != null) {
                menuButton.setVisibility(View.VISIBLE);
                // -------------- Fix Start for this method(updateHeaderMenuIcon)-----------
                menuButton.setImageResource(R.drawable.ic_two_line_hamburger);
                menuButton.setOnClickListener(v -> showRecordsSidebar());
                menuButton.setContentDescription(getString(R.string.more_options));
                // -------------- Fix Ended for this method(updateHeaderMenuIcon)-----------
            }
            if (closeButton != null) {
                closeButton.setVisibility(View.GONE);
            }
            if (selectAllContainer != null && selectAllCheck != null) {
                selectAllContainer.setVisibility(View.GONE);
                selectAllCheck
                        .setImageTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE));
            }
        }
        // Refresh the options menu (to show/hide "More Options")
        if (getActivity() != null) {
            getActivity().invalidateOptionsMenu();
        }
    }
    // -------------- Fix Ended for this method(updateUiForSelectionMode)-----------
    // --- Deletion Logic ---

    // --- deleteSelectedVideos (Corrected version from previous step) ---
    /** Handles deletion of selected videos */
    private void deleteSelectedVideos() {
        final List<Uri> itemsToDeleteUris = new ArrayList<>(selectedUris);
        if (itemsToDeleteUris.isEmpty()) {
            Log.d(TAG, "Deletion requested but selectedUris is empty.");
            exitSelectionMode();
            return;
        }

        Log.i(TAG, getString(R.string.delete_videos_log, itemsToDeleteUris.size()));
        exitSelectionMode();

        // Show progress dialog for batch deletion with count information
        onMoveToTrashStarted(itemsToDeleteUris.size() + " videos");

        if (executorService == null || executorService.isShutdown()) {
            executorService = Executors.newSingleThreadExecutor();
        }
        executorService.submit(() -> {
            int successCount = 0;
            int failCount = 0;
            List<VideoItem> allCurrentItems = new ArrayList<>(videoItems); // Copy for safe iteration

            for (Uri uri : itemsToDeleteUris) {
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
            Log.d(TAG, "BG Trash Operation Finished. Success: " + successCount + ", Fail: " + failCount);
            // Post results and UI refresh back to main thread
            final int finalSuccessCount = successCount;
            final int finalFailCount = failCount;
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    // Hide the progress dialog
                    onMoveToTrashFinished(finalSuccessCount > 0, null);

                    String message = (finalFailCount > 0)
                            ? getString(R.string.delete_videos_partial_success_toast, finalSuccessCount, finalFailCount)
                            : getString(R.string.delete_videos_success_toast, finalSuccessCount);
                    Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();

                    // Perform a complete refresh of the data to ensure proper serial numbers
                    Log.d(TAG, "Performing full refresh after deletion to update serial numbers");
                    if (recordsAdapter != null) {
                        recordsAdapter.clearCaches(); // Clear any cached data
                    }
                    loadRecordsList(); // This will completely rebuild the list with proper serial numbers
                });
            }
        });
    }

    private void confirmDeleteAll() {
        vibrate();
        int totalVideoCount = videoItems.size();
        if (totalVideoCount == 0) {
            Toast.makeText(requireContext(), "No videos to delete.", Toast.LENGTH_SHORT).show();
            return;
        }
        // Use the typed-confirmation bottom sheet (same as Reset All Preferences)
        // Require the user to type the exact word "DELETE" to proceed.
        InputActionBottomSheetFragment confirm = InputActionBottomSheetFragment.newReset(
                getString(R.string.delete_all_videos_title),
                "DELETE",
                getString(R.string.delete_all_videos_title),
                getString(R.string.delete_all_videos_subtitle_short),
                R.drawable.ic_delete_all);
        confirm.setCallbacks(new InputActionBottomSheetFragment.Callbacks() {
            @Override
            public void onImportConfirmed(org.json.JSONObject json) {
                /* not used */ }

            @Override
            public void onResetConfirmed() {
                deleteAllVideos();
            }
        });
        confirm.show(getParentFragmentManager(), "delete_sheet_confirm");
    }

    // Inside RecordsFragment.java
    private void deleteAllVideos() {
        // ----- Fix Start for this method(deleteAllVideos)-----
        // Check if videoItems has content, if not show toast
        if (videoItems.isEmpty()) {
            if (getContext() != null)
                Toast.makeText(requireContext(), "No videos to move to trash.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Create a copy of videoItems to avoid concurrent modification issues
        List<VideoItem> itemsToTrash = new ArrayList<>(videoItems);
        Log.i(TAG, "Moving all " + itemsToTrash.size() + " videos to trash...");
        // ----- Fix Ended for this method(deleteAllVideos)-----

        // Show progress dialog for deleting all videos with count information
        onMoveToTrashStarted("all " + itemsToTrash.size() + " videos");

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
                    // Hide the progress dialog
                    onMoveToTrashFinished(finalSuccessCount > 0, null);

                    String message = (finalFailCount > 0)
                            ? getString(R.string.delete_videos_partial_success_toast, finalSuccessCount, finalFailCount)
                            : getString(R.string.delete_videos_success_toast, finalSuccessCount);
                    Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();

                    // Perform a complete refresh of the data to ensure proper serial numbers
                    Log.d(TAG, "Performing full refresh after deletion to update serial numbers");
                    if (recordsAdapter != null) {
                        recordsAdapter.clearCaches(); // Clear any cached data
                    }
                    loadRecordsList(); // This will completely rebuild the list with proper serial numbers
                });
            }
        });
    }

    // Helper to find VideoItem by URI from a list
    private VideoItem findVideoItemByUri(List<VideoItem> items, Uri uri) {
        if (uri == null || items == null)
            return null;
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

        Log.i(TAG, "Attempting to move to trash: " + originalDisplayName + " (URI: " + uri + ", isSAF: " + isSafSource
                + ")");

        if (TrashManager.moveToTrash(context, uri, originalDisplayName, isSafSource)) {
            Log.i(TAG, "Successfully moved to trash: " + originalDisplayName);
            return true;
        } else {
            Log.e(TAG, "Failed to move to trash: " + originalDisplayName);
            // Optionally, show a specific toast for this failure if needed,
            // but batch operations will show a summary.
            // if(getActivity() != null) getActivity().runOnUiThread(() ->
            // Toast.makeText(context, "Failed to move '" + originalDisplayName + "' to
            // trash.", Toast.LENGTH_SHORT).show());
            return false;
        }
    }

    // OLD deleteVideoUri - to be removed or commented out after confirming
    // moveToTrashVideoItem is used everywhere
    private boolean deleteVideoUri(Uri uri) {
        // THIS METHOD IS NOW REPLACED BY moveToTrashVideoItem(VideoItem item)
        // To use the new method, you need the VideoItem object, not just the URI,
        // because we need the originalDisplayName and to determine if it's an SAF
        // source.

        // Find the VideoItem corresponding to this URI from your main list (videoItems)
        VideoItem itemToTrash = null;
        List<VideoItem> currentItems = new ArrayList<>(videoItems); // Use a copy to avoid issues if list is modified
                                                                    // elsewhere
        itemToTrash = findVideoItemByUri(currentItems, uri);

        if (itemToTrash != null) {
            Log.d(TAG, "Redirecting deleteVideoUri for " + uri + " to moveToTrashVideoItem.");
            return moveToTrashVideoItem(itemToTrash);
        } else {
            Log.e(TAG, "deleteVideoUri called for URI not found in videoItems: " + uri
                    + ". Attempting direct delete as fallback (SHOULD NOT HAPPEN).");
            // Fallback to old deletion logic if item not found (should not happen ideally)
            // This part should ideally be removed if moveToTrashVideoItem is robustly used.
            Context context = getContext();
            if (context == null) {
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
                        Log.e(TAG, "Fallback: Failed to delete internal file: " + uri.getPath() + " Exists="
                                + file.exists());
                        return false;
                    }
                } else if ("content".equals(uri.getScheme())) {
                    if (DocumentsContract.deleteDocument(context.getContentResolver(), uri)) {
                        Log.i(TAG, "Fallback: Deleted SAF document: " + uri);
                        return true;
                    } else {
                        DocumentFile doc = DocumentFile.fromSingleUri(context, uri);
                        if (doc == null || !doc.exists()) {
                            Log.w(TAG,
                                    "Fallback: deleteDocument returned false, but file doesn't exist or became null. Treating as success. URI: "
                                            + uri);
                            return true; // If it's gone, it's 'deleted'
                        }
                        Log.e(TAG,
                                "Fallback: Failed to delete SAF document (deleteDocument returned false and file still exists): "
                                        + uri);
                        return false;
                    }
                }
                Log.w(TAG, "Fallback: Unknown URI scheme for direct delete: " + uri.getScheme());
                return false;
            } catch (SecurityException se) {
                Log.e(TAG, "Fallback: SecurityException deleting URI: " + uri, se);
                if (getActivity() != null)
                    getActivity().runOnUiThread(() -> Toast
                            .makeText(context, "Permission denied during fallback delete.", Toast.LENGTH_SHORT).show());
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
        menu.clear(); // Always clear previous items first
        inflater.inflate(R.menu.records_menu, menu); // Only inflate the correct menu for Records
        // Hide or show items as needed for Records tab
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
        if (getActivity() == null)
            return;
        // -------------- Fix Start for this method(showRecordsSidebar)-----------
        // Use unified overlay: open a sidebar-style fragment to host row-based options.
        RecordsSidebarFragment sidebar = RecordsSidebarFragment.newInstance(mapSortToId(currentSortOption), isGridView);
        // Listen for result events from rows (sort picker, delete all, etc.)
        final String resultKey = "records_sidebar_result";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (key, bundle) -> {
            if (!resultKey.equals(key))
                return;
            String action = bundle.getString("action");
            if (action == null)
                return;
            switch (action) {
                case "sort": {
                    String sortId = bundle.getString("sort_id");
                    if (sortId != null) {
                        SortOption newOption = mapIdToSort(sortId);
                        if (newOption != currentSortOption) {
                            currentSortOption = newOption;
                            performVideoSort();
                        }
                    }
                    break;
                }
                case "delete_all":
                    confirmDeleteAll();
                    break;
                case "toggle_view_mode":
                    toggleViewMode();
                    break;
                case "set_view_mode":
                    String vm = bundle.getString("view_mode");
                    if (vm != null) {
                        boolean toGrid = "grid".equals(vm);
                        if (isGridView != toGrid) {
                            // reuse existing toggle logic
                            toggleViewMode();
                        }
                    }
                    break;
                case "hide_thumbnails_toggled":
                    boolean hide = false;
                    if (bundle.containsKey("hide_thumbnails"))
                        hide = bundle.getBoolean("hide_thumbnails", false);
                    Log.d(TAG, "Records sidebar: hide_thumbnails_toggled = " + hide);
                    if (recordsAdapter != null) {
                        // Adapter will read prefs but force refresh
                        recordsAdapter.notifyDataSetChanged();
                    }
                    break;
            }
        });
        sidebar.setResultKey(resultKey);
        // Show as a Material side sheet dialog instead of full-screen overlay
        sidebar.show(getParentFragmentManager(), "RecordsSidebar");
        // -------------- Fix Ended for this method(showRecordsSidebar)-----------
    }

    // -------------- Fix Start for this class(mapSortHelpers)-----------
    private String mapSortToId(SortOption opt) {
        switch (opt) {
            case LATEST_FIRST:
                return "latest";
            case OLDEST_FIRST:
                return "oldest";
            case SMALLEST_FILES:
                return "smallest";
            case LARGEST_FILES:
                return "largest";
        }
        return "latest";
    }

    private SortOption mapIdToSort(String id) {
        if ("oldest".equals(id))
            return SortOption.OLDEST_FIRST;
        if ("smallest".equals(id))
            return SortOption.SMALLEST_FILES;
        if ("largest".equals(id))
            return SortOption.LARGEST_FILES;
        return SortOption.LATEST_FIRST;
    }
    // -------------- Fix Ended for this class(mapSortHelpers)-----------

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
        if (items == null)
            return;
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
    private enum SortOption {
        LATEST_FIRST, OLDEST_FIRST, SMALLEST_FILES, LARGEST_FILES
    }

    // --- Utility ---

    private void vibrate() {
        Context context = getContext();
        if (context == null)
            return;
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                // noinspection deprecation
                vibrator.vibrate(50);
            }
        }
    }

    // In RecordsFragment.java

    /**
     * Scans the relevant external cache directories for lingering temporary video
     * files.
     * 
     * @return A List of VideoItem objects representing the found temp files.
     */
    // -------------- Fix Start (getTempCacheRecordsList) - Remove obsolete temp
    // system -----------
    /**
     * OBSOLETE: Temp cache system removed - now using OpenGL real-time processing
     * 
     * @deprecated This method is no longer used as temp file system was replaced
     */
    @Deprecated
    private List<VideoItem> getTempCacheRecordsList() {
        Log.d(TAG, "getTempCacheRecordsList: Obsolete temp cache system - returning empty list");
        return new ArrayList<>();
    }
    // -------------- Fix End (getTempCacheRecordsList) -----------

    // -------------- Fix Start (Remove obsolete temp file system) --------------
    // Removed scanDirectoryForTempVideos method - temp file system is obsolete
    // Now using OpenGL pipeline, no longer need temp file scanning
    // -------------- Fix End (Remove obsolete temp file system) --------------

    // ----- Fix Start for this class (RecordsFragment_segment_receiver_fields)
    // -----
    private BroadcastReceiver segmentCompleteReceiver;
    private boolean isSegmentReceiverRegistered = false;
    // ----- Fix End: Add search-related fields at class level -----

    // ----- Fix Start for this class (RecordsFragment_segment_receiver_methods)
    // -----
    private void registerSegmentCompleteReceiver() {
        if (getContext() == null || isSegmentReceiverRegistered) {
            return;
        }
        segmentCompleteReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent != null && Constants.ACTION_RECORDING_SEGMENT_COMPLETE.equals(intent.getAction())) {
                    String fileUriString = intent.getStringExtra(Constants.INTENT_EXTRA_FILE_URI);
                    String filePath = intent.getStringExtra(Constants.INTENT_EXTRA_FILE_PATH);
                    int segmentNumber = intent.getIntExtra(Constants.INTENT_EXTRA_SEGMENT_NUMBER, -1);

                    Log.i(TAG, "Received ACTION_RECORDING_SEGMENT_COMPLETE for segment " + segmentNumber);
                    if (fileUriString != null) {
                        Log.d(TAG, "Segment URI: " + fileUriString);
                    } else if (filePath != null) {
                        Log.d(TAG, "Segment Path: " + filePath);
                    }

                    // Refresh the list of recordings to show the new segment
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            Log.d(TAG, "Segment complete, reloading records list...");
                            loadRecordsList();
                            // Optionally, you could scroll to the new item if identifiable
                        });
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(Constants.ACTION_RECORDING_SEGMENT_COMPLETE);
        // Use correct flag for the receiver based on Android API level
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(segmentCompleteReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ContextCompat.registerReceiver(requireContext(), segmentCompleteReceiver, filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED);
        }
        isSegmentReceiverRegistered = true;
        Log.d(TAG, "SegmentCompleteReceiver registered.");
    }

    private void unregisterSegmentCompleteReceiver() {
        if (getContext() != null && isSegmentReceiverRegistered && segmentCompleteReceiver != null) {
            try {
                requireContext().unregisterReceiver(segmentCompleteReceiver);
                isSegmentReceiverRegistered = false;
                Log.d(TAG, "SegmentCompleteReceiver unregistered.");
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Error unregistering SegmentCompleteReceiver: " + e.getMessage());
            }
        }
    }
    // ----- Fix Ended for this class (RecordsFragment_segment_receiver_methods)
    // -----

    // ----- Fix Start: Add search-related fields at class level -----
    private SearchView searchView;
    private boolean isSearchViewActive = false;
    // ----- Fix End: Add search-related fields at class level -----

    // ----- Fix Start: Add search-related methods -----
    /**
     * Checks if search functionality is currently active
     * 
     * @return true if search is active, false otherwise
     */
    private boolean isSearchActive() {
        return isSearchViewActive && searchView != null && !searchView.isIconified();
    }

    /**
     * Clears the current search and resets the search view
     */
    private void clearSearch() {
        if (searchView != null) {
            searchView.setQuery("", false);
            searchView.setIconified(true);
            isSearchViewActive = false;
            // Reload original data without filter
            loadRecordsList();
        }
    }
    // ----- Fix End: Add search-related methods -----

    // ----- Fix Start: Add isInSelectionMode() method -----
    /**
     * Checks if the fragment is currently in selection mode
     * 
     * @return true if in selection mode, false otherwise
     */
    private boolean isInSelectionMode() {
        return isInSelectionMode;
    }
    // ----- Fix End: Add isInSelectionMode() method -----

    // Override the onBackPressed method from BaseFragment
    @Override
    protected boolean onBackPressed() {
        // If search is active, clear it instead of navigating back
        if (isSearchActive()) {
            clearSearch();
            return true;
        }

        // If in selection mode, exit selection mode instead of navigating back
        if (isInSelectionMode()) {
            exitSelectionMode();
            return true;
        }

        // Handle any other specific cases here

        // For normal cases, let the default implementation handle it
        return false;
    }

    // ----- Fix Start: Add refreshList method -----
    /**
     * Public method to refresh the records list
     * Can be called from other fragments when they need to update this fragment
     */
    public void refreshList() {
        if (isAdded()) {
            // Clear caches to ensure fresh data
            com.fadcam.utils.VideoSessionCache.invalidateOnNextAccess(sharedPreferencesManager);
            if (recordsAdapter != null) {
                recordsAdapter.clearCaches();
            }
            Log.i(TAG, "refreshList: Clearing caches and reloading records list.");
            loadRecordsList();
        }
    }
    // ----- Fix End: Add refreshList method -----

    private void clearResources() {
        // Shutdown the executor service properly
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            try {
                // Wait a bit for tasks to complete
                if (!executorService.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Clear video lists to free memory
        if (videoItems != null)
            videoItems.clear();
        if (allLoadedItems != null)
            allLoadedItems.clear();
        if (selectedUris != null)
            selectedUris.clear();

        // Clear thumbnail caches
        if (getContext() != null) {
            Glide.get(getContext()).clearMemory();
            // Schedule disk cache clearing on a background thread
            new Thread(() -> {
                try {
                    Glide.get(getContext()).clearDiskCache();
                } catch (Exception e) {
                    Log.e(TAG, "Error clearing Glide disk cache", e);
                }
            }).start();
        }

        // Release references
        recordsAdapter = null;
        Log.d(TAG, "Resources cleared in onDestroy");
    }

    // Restore this important method that was removed
    private void setLayoutManager() {
        if (recyclerView != null) {
            RecyclerView.LayoutManager layoutManager = isGridView ? new GridLayoutManager(getContext(), 2) : // 2
                                                                                                             // columns
                                                                                                             // for grid
                    new LinearLayoutManager(getContext());
            recyclerView.setLayoutManager(layoutManager);
            Log.d(TAG, "LayoutManager set to: " + (isGridView ? "GridLayout" : "LinearLayout"));
        }
    }

    // Restore and optimize the getNextPage method
    private List<VideoItem> getNextPage(int page) {
        int startIndex = page * PAGE_SIZE;
        if (startIndex >= allLoadedItems.size()) {
            hasMoreItems = false;
            return new ArrayList<>();
        }

        int endIndex = Math.min(startIndex + PAGE_SIZE, allLoadedItems.size());
        List<VideoItem> pageItems = new ArrayList<>(allLoadedItems.subList(startIndex, endIndex));

        // If this is the last page, set hasMoreItems to false
        if (endIndex >= allLoadedItems.size()) {
            hasMoreItems = false;
        }

        return pageItems;
    }

    // Restore this important method
    private void updateUiVisibility() {
        if (getView() == null) {
            Log.w(TAG, "LOG_UI_VISIBILITY: getView() is null. Cannot update UI visibility.");
            return;
        }

        boolean isEmpty;
        if (recordsAdapter != null) {
            isEmpty = recordsAdapter.getItemCount() == 0;
            Log.d(TAG, "LOG_UI_VISIBILITY: Adapter found. Item count: " + recordsAdapter.getItemCount() + ". Is empty: "
                    + isEmpty);
        } else {
            isEmpty = videoItems.isEmpty();
            Log.d(TAG, "LOG_UI_VISIBILITY: Adapter is NULL. videoItems list size: " + videoItems.size() + ". Is empty: "
                    + isEmpty);
        }

        Log.i(TAG, "LOG_UI_VISIBILITY: updateUiVisibility called. Final decision: isEmpty = " + isEmpty);

        if (isEmpty) {
            if (recyclerView != null)
                recyclerView.setVisibility(View.GONE);
            if (emptyStateContainer != null)
                emptyStateContainer.setVisibility(View.VISIBLE);
            // Hide navigation FAB when empty
            if (fabScrollNavigation != null)
                fabScrollNavigation.setVisibility(View.GONE);
            Log.d(TAG, "LOG_UI_VISIBILITY: Showing empty state (Recycler GONE, Empty VISIBLE).");
        } else {
            if (emptyStateContainer != null)
                emptyStateContainer.setVisibility(View.GONE);
            if (recyclerView != null)
                recyclerView.setVisibility(View.VISIBLE);
            // Update navigation FAB when videos are visible
            updateNavigationFab();
            Log.d(TAG, "LOG_UI_VISIBILITY: Showing recycler view (Empty GONE, Recycler VISIBLE).");
        }
        if (loadingIndicator != null && loadingIndicator.getVisibility() == View.VISIBLE) {
            loadingIndicator.setVisibility(View.GONE);
            Log.d(TAG, "LOG_UI_VISIBILITY: Loading indicator was visible, set to GONE.");
        }
    }

    // Add the missing SpacesItemDecoration class back into the RecordsFragment
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
        }
    }

    // Add scroll state detection for better performance
    private boolean isScrolling = false;

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        // Clear glide memory cache when memory is low
        if (getContext() != null) {
            Glide.get(getContext()).clearMemory();
        }

        // Clear adapter cache
        if (recordsAdapter != null) {
            recordsAdapter.clearCaches();
        }
    }

    private static final String PREF_APPLOCK_ENABLED = "applock_enabled";

    /**
     * Checks if app lock is enabled and shows the unlock dialog if needed
     */
    private void checkAppLock() {
        SharedPreferencesManager sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());
        boolean isAppLockEnabled = sharedPreferencesManager.isAppLockEnabled();
        boolean isSessionUnlocked = sharedPreferencesManager.isAppLockSessionUnlocked();

        if (isAppLockEnabled && !isSessionUnlocked && AppLock.isEnrolled(requireContext())) {
            // ----- Fix Start: Fade overlay after successful unlock and restore UI state
            // correctly -----
            new UnlockDialogBuilder(requireActivity())
                    .onUnlocked(() -> {
                        sharedPreferencesManager.setAppLockSessionUnlocked(true);
                        fadeOverlay(false);
                        updateUiVisibility();
                        updateFabIcons();
                    })
                    .onCanceled(() -> {
                        if (getActivity() instanceof MainActivity) {
                            ((MainActivity) getActivity()).skipNextBackExitHandling();
                            requireActivity().getOnBackPressedDispatcher().onBackPressed();
                        }
                    })
                    .show();
            // ----- Fix End: Fade overlay after successful unlock and restore UI state
            // correctly -----
        } else {
            fadeOverlay(false);
            updateUiVisibility();
            updateFabIcons();
        }
    }

    /**
     * Sets the visibility of all sensitive content in the Records tab.
     * 
     * @param visible true to show, false to hide (set INVISIBLE)
     */
    private void setSensitiveContentVisibility(boolean visible) {
        int vis = visible ? View.VISIBLE : View.INVISIBLE;
        if (recyclerView != null)
            recyclerView.setVisibility(vis);
        if (emptyStateContainer != null)
            emptyStateContainer.setVisibility(vis);
        // FAB removed
        if (fabDeleteSelected != null)
            fabDeleteSelected.setVisibility(vis);
    }

    /**
     * Fades the AppLock overlay in or out with animation.
     * 
     * @param show true to fade in (show), false to fade out (hide)
     */
    private void fadeOverlay(final boolean show) {
        if (applockOverlay == null)
            return;
        if (show) {
            applockOverlay.setAlpha(0f);
            applockOverlay.setVisibility(View.VISIBLE);
            applockOverlay.animate().alpha(1f).setDuration(250).start();
        } else {
            applockOverlay.animate().alpha(0f).setDuration(250)
                    .withEndAction(() -> applockOverlay.setVisibility(View.GONE))
                    .start();
        }
    }

    private int resolveThemeColor(int attr) {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(attr, typedValue, true);
        return typedValue.data;
    }

    // ----- Fix Start for this method(onDeleteVideo)-----
    @Override
    public void onDeleteVideo(VideoItem videoItem) {
        // Delegate to the existing move-to-trash logic to avoid duplication
        onMoveToTrashRequested(videoItem);
    }
    // ----- Fix Ended for this method(onDeleteVideo)-----

    // Helper to set text colors recursively for all TextViews and RadioButtons
    private void setTextColorsRecursive(View view, int primary, int secondary) {
        if (view instanceof TextView) {
            // Use primary for main titles, secondary for descriptions
            TextView tv = (TextView) view;
            CharSequence text = tv.getText();
            if (text != null && text.length() > 0 && (tv.getTextSize() >= 16f)) {
                tv.setTextColor(primary);
            } else {
                tv.setTextColor(secondary);
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                setTextColorsRecursive(vg.getChildAt(i), primary, secondary);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        clearResources();
    }

    /**
     * Updates only the visible area of the RecyclerView
     * This is more efficient than notifying the entire dataset changed
     */
    private void updateVisibleArea() {
        if (recyclerView == null || recordsAdapter == null)
            return;

        RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
        if (layoutManager == null)
            return;

        int firstVisible, lastVisible;
        if (layoutManager instanceof GridLayoutManager) {
            GridLayoutManager gridLayoutManager = (GridLayoutManager) layoutManager;
            firstVisible = gridLayoutManager.findFirstVisibleItemPosition();
            lastVisible = gridLayoutManager.findLastVisibleItemPosition();
        } else {
            LinearLayoutManager linearLayoutManager = (LinearLayoutManager) layoutManager;
            firstVisible = linearLayoutManager.findFirstVisibleItemPosition();
            lastVisible = linearLayoutManager.findLastVisibleItemPosition();
        }

        // Extend the range slightly to preload adjacent items
        firstVisible = Math.max(0, firstVisible - 2);
        lastVisible = Math.min(recordsAdapter.getItemCount() - 1, lastVisible + 2);

        if (lastVisible >= firstVisible) {
            recordsAdapter.notifyItemRangeChanged(firstVisible, lastVisible - firstVisible + 1);
            Log.d(TAG, "updateVisibleArea: Updated items " + firstVisible + " to " + lastVisible);
        }
    }

    /**
     * Creates optimized Glide options for efficient thumbnail loading
     * 
     * @return RequestOptions for Glide
     */
    public RequestOptions getOptimizedGlideOptions() {
        return new RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.ALL) // Cache both original & resized images
                .skipMemoryCache(false) // Use memory cache for faster loading
                .centerCrop() // Crop images to fit the view
                .override(200, 200) // Standardized thumbnail size for all items
                .placeholder(R.drawable.ic_video_placeholder); // Show placeholder while loading
    }

    /**
     * Loads the list of videos from all sources and updates the UI with
     * professional skeleton loading
     */
    @SuppressLint("NotifyDataSetChanged")
    private void loadRecordsList() {
        Log.i(TAG, "loadRecordsList: Starting optimized progressive loading");

        // -------------- Fix Start (cache debug) - Enhanced cache state logging
        // -----------

        // Debug: Log current cache state with more details
        boolean hasSessionCache = com.fadcam.utils.VideoSessionCache.isSessionCacheValid();
        boolean hasPersistentCache = com.fadcam.utils.VideoSessionCache.hasCachedData(sharedPreferencesManager);
        int cachedCount = com.fadcam.utils.VideoSessionCache.getCachedVideoCount(sharedPreferencesManager);
        boolean hasExistingData = !videoItems.isEmpty();

        Log.d(TAG, "CACHE DEBUG: sessionCache=" + hasSessionCache + ", persistentCache=" + hasPersistentCache +
                ", cachedCount=" + cachedCount + ", existingData=" + hasExistingData +
                ", videoItems.size=" + videoItems.size() + ", isInitialLoad=" + isInitialLoad);

        // -------------- Fix End (cache debug) -----------

        // Step 1: Check in-memory session cache first, with disk fallback
        List<VideoItem> cachedVideos = com.fadcam.utils.VideoSessionCache.getSessionCachedVideos(requireContext());
        if (!cachedVideos.isEmpty()) {
            Log.d(TAG, "CACHE HIT: Using " + cachedVideos.size() + " cached videos (from memory or disk)");

            // Sort cached videos
            sortItems(cachedVideos, currentSortOption);

            // Show skeleton first, then replace
            int estimatedCount = cachedVideos.size();
            if (recordsAdapter == null || !recordsAdapter.isSkeletonMode()) {
                Log.d(TAG, "Showing " + estimatedCount + " skeleton items for cache hit");
                showSkeletonLoading(estimatedCount);
            }

            // Replace skeleton with cached data immediately
            replaceSkeletonsWithData(cachedVideos);

            isLoading = false;
            return;
        }

        // Step 2: Check if we should skip loading entirely (already have data and not
        // initial load)
        if (!videoItems.isEmpty() && !isInitialLoad) {
            Log.d(TAG, "Already have " + videoItems.size() + " videos loaded, skipping reload");
            isLoading = false;
            updateUiVisibility();
            return;
        }

        // Step 3: Show skeleton loading based on cached count
        int estimatedCount = com.fadcam.utils.VideoSessionCache.getCachedVideoCount(sharedPreferencesManager);
        if (estimatedCount <= 0) {
            estimatedCount = 12; // Default skeleton count
        }

        // Show skeleton immediately if not already showing
        if (recordsAdapter == null || !recordsAdapter.isSkeletonMode()) {
            Log.d(TAG, "Showing " + estimatedCount + " skeleton items for fresh load");
            showSkeletonLoading(estimatedCount);
        }

        // -------------- Fix Start (loadRecordsList) - Proper cache utilization
        // -----------

        // Step 2: No cache - load progressively in background
        if (isLoading) {
            Log.d(TAG, "Already loading data, skipping duplicate request");
            return;
        }

        isLoading = true;

        if (executorService == null || executorService.isShutdown()) {
            executorService = Executors.newSingleThreadExecutor();
        }

        // Load data progressively in background
        executorService.submit(() -> {
            try {
                Log.d(TAG, "Background progressive loading started");

                // Load primary videos progressively (temp file system removed)
                List<VideoItem> primaryItems = loadPrimaryVideosProgressively();

                // Use primary items directly (no temp files to combine)
                List<VideoItem> allVideos = new ArrayList<>(primaryItems);

                // Remove duplicates
                List<VideoItem> uniqueItems = removeDuplicateVideos(allVideos);

                // Sort all items
                sortItems(uniqueItems, currentSortOption);
                totalItems = uniqueItems.size();

                Log.d(TAG, "Progressive loading complete: " + uniqueItems.size() + " total videos");

                // Update cache for next time with persistence
                com.fadcam.utils.VideoSessionCache.updateSessionCache(uniqueItems, requireContext());
                com.fadcam.utils.VideoSessionCache.setCachedVideoCount(uniqueItems.size(), sharedPreferencesManager);

                // -------------- Fix Start (shimmer delay) - Show shimmer for minimum time
                // -----------

                // Add minimum delay to show shimmer animation (professional UX)
                long minShimmerTime = 1000; // Minimum 1 second to show shimmer

                Log.d(TAG, "Adding " + minShimmerTime + "ms delay to show shimmer animation");
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    replaceSkeletonsWithData(uniqueItems);
                }, minShimmerTime);

                // -------------- Fix End (shimmer delay) -----------

            } catch (Exception e) {
                Log.e(TAG, "Error in progressive loading", e);
                handleLoadingError();
            }
        });
    }

    /**
     * Load primary videos with progressive updates to UI
     */
    private List<VideoItem> loadPrimaryVideosProgressively() {
        String safUriString = sharedPreferencesManager.getCustomStorageUri();

        if (safUriString != null) {
            try {
                Uri treeUri = Uri.parse(safUriString);
                if (hasSafPermission(treeUri)) {
                    return getSafRecordsListProgressive(treeUri, null);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading SAF videos", e);
            }
        }

        // Fallback to internal storage
        return getInternalRecordsList();
    }

    /**
     * Remove duplicate videos from combined list
     */
    private List<VideoItem> removeDuplicateVideos(List<VideoItem> videos) {
        List<VideoItem> uniqueItems = new ArrayList<>();
        Set<Uri> uniqueUris = new HashSet<>();

        for (VideoItem item : videos) {
            if (item != null && item.uri != null && uniqueUris.add(item.uri)) {
                uniqueItems.add(item);
            }
        }

        return uniqueItems;
    }

    /**
     * Handle loading errors gracefully
     */
    private void handleLoadingError() {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                hideSkeletonLoading();
                updateUiVisibility();

                if (loadingIndicator != null) {
                    loadingIndicator.setVisibility(View.GONE);
                }
                if (swipeRefreshLayout != null && swipeRefreshLayout.isRefreshing()) {
                    swipeRefreshLayout.setRefreshing(false);
                }

                isLoading = false;
            });
        }
    }

    /**
     * Updates the UI with a list of videos, either completely replacing the current
     * list
     * or appending to it.
     * 
     * @param newVideos The list of videos to display
     * @param isPartial Whether this is a partial update (true) or complete update
     *                  (false)
     */
    private void updateUiWithVideos(final List<VideoItem> newVideos, boolean isPartial) {
        if (getActivity() == null)
            return;

        Log.d(TAG, "updateUiWithVideos: Updating UI with " + newVideos.size() + " videos, isPartial=" + isPartial);

        getActivity().runOnUiThread(() -> {
            if (isPartial) {
                // For partial updates (e.g., just temp videos), we want to show them right away
                videoItems.clear();
                videoItems.addAll(newVideos);

                if (recordsAdapter != null) {
                    recordsAdapter.updateRecords(videoItems);
                    if (recyclerView != null)
                        recyclerView.setVisibility(View.VISIBLE);
                }
                // Don't hide loading indicator yet for partial updates
            } else {
                // For complete updates, replace everything
                videoItems.clear();
                videoItems.addAll(newVideos);

                if (recordsAdapter != null) {
                    recordsAdapter.updateRecords(videoItems);
                }

                updateUiVisibility();
                if (loadingIndicator != null) {
                    loadingIndicator.setVisibility(View.GONE);
                }
                if (swipeRefreshLayout != null) {
                    swipeRefreshLayout.setRefreshing(false);
                }
                if (recyclerView != null) {
                    recyclerView.setVisibility(View.VISIBLE);
                }
            }

            isLoading = false;
            isInitialLoad = false;
        });
    }

    // -------------- Fix Start (Navigation FAB Methods) - Scroll navigation
    // functionality -----------

    /**
     * Updates navigation FAB visibility and icon based on scroll direction
     */
    private void updateNavigationFab() {
        if (fabScrollNavigation == null || recyclerView == null)
            return;

        RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
        if (layoutManager == null)
            return;

        int totalItemCount = layoutManager.getItemCount();
        if (totalItemCount <= 5) {
            // Hide FAB if there are too few items to need scrolling
            fabScrollNavigation.setVisibility(View.GONE);
            return;
        }

        // Show FAB (actual visibility controlled by animation helpers)
        // Use single drawable and flip based on scroll direction with smooth animation
        fabScrollNavigation.setVisibility(View.VISIBLE);

        // Cancel any running rotation animation
        if (currentRotationAnimator != null && currentRotationAnimator.isRunning()) {
            currentRotationAnimator.cancel();
        }
        float targetRotation;
        String contentDescription;
        if (isScrollingDown) {
            targetRotation = 90f;
            contentDescription = getString(R.string.scroll_to_bottom);
        } else {
            targetRotation = -90f;
            contentDescription = getString(R.string.scroll_to_top);
        }
        float currentRotation = fabScrollNavigation.getRotation();
        if (Math.abs(currentRotation - targetRotation) > 1f) {
            currentRotationAnimator = ObjectAnimator.ofFloat(fabScrollNavigation, "rotation", currentRotation,
                    targetRotation);
            currentRotationAnimator.setDuration(300);
            currentRotationAnimator.setInterpolator(new android.view.animation.OvershootInterpolator(0.3f));
            currentRotationAnimator.start();
        } else {
            fabScrollNavigation.setRotation(targetRotation);
        }
        fabScrollNavigation.setContentDescription(contentDescription);
    }

    /**
     * Handles navigation FAB click - scrolls based on current scroll direction
     */
    private void handleNavigationFabClick() {
        if (recyclerView == null)
            return;

        RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
        if (layoutManager == null)
            return;

        int totalItemCount = layoutManager.getItemCount();
        if (totalItemCount == 0)
            return;

        // Vibrate for feedback
        vibrate();

        // Scroll based on current scroll direction (what the icon is indicating)
        if (isScrollingDown) {
            // FAB shows down arrow - scroll to bottom
            recyclerView.smoothScrollToPosition(totalItemCount - 1);
            Log.d(TAG, "Navigation FAB: Scrolling to bottom (user was scrolling down)");
        } else {
            // FAB shows up arrow - scroll to top
            recyclerView.smoothScrollToPosition(0);
            Log.d(TAG, "Navigation FAB: Scrolling to top (user was scrolling up)");
        }

        // Update FAB icon after a short delay to reflect new position
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            updateNavigationFab();
        }, 500);
    }

    // (No delayed hide) Fade animation helper
    private void showNavigationFab() {
        if (fabScrollNavigation == null)
            return;
        // Ensure fully visible
        if (fabScrollNavigation.getVisibility() != View.VISIBLE) {
            fabScrollNavigation.setAlpha(0f);
            fabScrollNavigation.setVisibility(View.VISIBLE);
            // Apply dark gray background color
            try {
                int darkGrayColor = ContextCompat.getColor(requireContext(), R.color.gray_button_filled);
                fabScrollNavigation.setBackgroundTintList(android.content.res.ColorStateList.valueOf(darkGrayColor));
            } catch (Exception ignored) {
            }
            fabScrollNavigation.animate().alpha(1f).setDuration(200).start();
        } else {
            fabScrollNavigation.animate().alpha(1f).setDuration(150).start();
        }
    }

    // -------------- Fix End (Navigation FAB Methods) -----------
}