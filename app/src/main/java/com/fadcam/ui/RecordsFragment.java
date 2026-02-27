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
import android.text.format.Formatter;
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
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog; // Import AlertDialog
import android.widget.ProgressBar;
// Import Toolbar

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.Constants;
import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager; // Import your manager
import com.fadcam.Utils;
import com.fadcam.utils.RecordingStoragePaths;
import com.fadcam.ui.picker.OptionItem;
import com.fadcam.ui.picker.PickerBottomSheetFragment;
import com.fadcam.forensics.service.DigitalForensicsIndexCoordinator;
// Import the new VideoItem class
// Ensure adapter import is correct
import com.fadcam.utils.TrashManager; // <<< ADD IMPORT FOR TrashManager
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

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
import com.fadcam.utils.RealtimeMediaInvalidationCoordinator;
import android.util.SparseArray;
import android.content.res.ColorStateList;
import android.graphics.Color;

public class RecordsFragment extends BaseFragment implements
        RecordsAdapter.OnVideoClickListener,
        RecordsAdapter.OnVideoLongClickListener,
        RecordActionListener {

    /**
     * Static flag set by other components (e.g. Faditor export) to signal
     * that the records list should refresh on next resume.
     */
    private static volatile boolean sPendingRefresh = false;

    /**
     * Request a refresh of the records list on next resume.
     * Safe to call from any thread.
     */
    public static void requestRefresh() {
        sPendingRefresh = true;
    }

    private BroadcastReceiver recordingCompleteReceiver; // ** ADD field for the receiver **
    private boolean isReceiverRegistered = false; // Track registration status
    // ** NEW: Fields for storage change receiver **
    private BroadcastReceiver storageLocationChangedReceiver;
    private boolean isStorageReceiverRegistered = false;
    // ** NEW: Set to track URIs currently being processed **
    private Set<Uri> currentlyProcessingUris = new HashSet<>();
    private static final String TAG = "RecordsFragment";

    // Video index is now backed by Room DB via VideoIndexRepository
    // (Replaced com.fadcam.utils.VideoSessionCache with persistent SQLite index)


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

                // ordering -----------

                // Disable skeleton mode
                if (recordsAdapter != null) {
                    recordsAdapter.setSkeletonMode(false);
                }

                // Replace source data, then apply active filter to visible list.
                allLoadedItems.clear();
                allLoadedItems.addAll(normalizeVideoCategories(actualItems));
                applyActiveFilterToUi();
                Log.d(TAG, "Applied active filter to " + actualItems.size() + " loaded videos");

                // Update UI visibility
                updateUiVisibility();
                isLoading = false;
                isInitialLoad = false;
                drainPendingRealtimeRefresh();

                // Hide progress indicator
                if (progressHandler != null && showProgressRunnable != null) {
                    progressHandler.removeCallbacks(showProgressRunnable);
                }
                if (loadingProgress != null) {
                    loadingProgress.setVisibility(View.GONE);
                }

                // Ensure refresh indicator is stopped
                if (swipeRefreshLayout != null && swipeRefreshLayout.isRefreshing()) {
                    swipeRefreshLayout.setRefreshing(false);
                    Log.d(TAG, "Refresh indicator stopped in replaceSkeletonsWithData");
                }

                Log.d(TAG, "Skeleton replacement complete - proper positioning and ordering achieved");


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
                drainPendingRealtimeRefresh();

                // Hide progress indicator on error
                if (progressHandler != null && showProgressRunnable != null) {
                    progressHandler.removeCallbacks(showProgressRunnable);
                }
                if (loadingProgress != null) {
                    loadingProgress.setVisibility(View.GONE);
                }

                // Ensure refresh indicator is stopped on error/empty state
                if (swipeRefreshLayout != null && swipeRefreshLayout.isRefreshing()) {
                    swipeRefreshLayout.setRefreshing(false);
                    Log.d(TAG, "Refresh indicator stopped in hideSkeletonLoading");
                }

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


    private AlertDialog progressDialog; // Field to hold the dialog for Save to Gallery
    private AlertDialog moveTrashProgressDialog; // Changed from ProgressDialog to AlertDialog
    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefreshLayout; // ADD THIS FIELD
    private LinearLayout emptyStateContainer; // Add field for the empty state layout
    private RecordsAdapter recordsAdapter;
    private GalleryFastScroller fastScroller;
    private int currentGridSpan = 2;
    private ExtendedFloatingActionButton fabDeleteSelected;
    private FloatingActionButton fabScrollNavigation; // Navigation FAB for scroll to top/bottom
    private boolean isScrollingDown = true; // Track scroll direction for FAB icon
    private ObjectAnimator currentRotationAnimator; // Smooth rotation animation for FAB icon

    private View applockOverlay;

    // Use VideoItem and store Uris for selection
    private List<VideoItem> videoItems = new ArrayList<>();
    private List<Uri> selectedVideosUris = new ArrayList<>();

    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    /** Separate executor for background delta scans — never blocks the main executor. */
    private volatile ExecutorService deltaExecutor = Executors.newSingleThreadExecutor();
    private SortOption currentSortOption = SortOption.LATEST_FIRST;
    private SharedPreferencesManager sharedPreferencesManager;
    private SpacesItemDecoration itemDecoration; // Keep a reference
    private ProgressBar loadingIndicator; // *** ADD field for ProgressBar ***
    private ProgressBar loadingProgress; // Thin progress bar for data loading feedback
    private Handler progressHandler; // Handler for delayed progress show
    private Runnable showProgressRunnable; // Runnable to show progress after delay
    private TextView titleText;
    private TextView statsPhotosText;
    private TextView statsVideosText;
    private TextView statsSizeText;
    private ImageView menuButton;
    private ImageView closeButton;
    private View selectAllContainer;
    private android.widget.ImageView selectAllCheck;
    private CharSequence originalToolbarTitle;
    private Chip chipFilterAll;
    private Chip chipFilterCamera;
    private Chip chipFilterScreen;
    private Chip chipFilterFaditor;
    private Chip chipFilterStream;
    private Chip chipFilterShot;
    private ChipGroup chipGroupRecordsFilter;
    private View cameraFilterRow;
    private ChipGroup chipGroupCameraFilter;
    private Chip chipCameraAll;
    private Chip chipCameraBack;
    private Chip chipCameraFront;
    private Chip chipCameraDual;
    private View faditorFilterRow;
    private ChipGroup chipGroupFaditorFilter;
    private Chip chipFaditorAll;
    private Chip chipFaditorConverted;
    private Chip chipFaditorMerge;
    private View shotFilterRow;
    private ChipGroup chipGroupShotFilter;
    private Chip chipShotAll;
    private Chip chipShotBack;
    private Chip chipShotSelfie;
    private Chip chipShotFadrec;
    private TextView filterHelperText;
    private TextView filterChecklistButton;
    private View selectionActionsRow;
    private TextView btnActionSelectAll;
    private TextView btnActionBatchSave;
    private TextView btnActionBatchFaditor;
    private TextView btnActionBatchDelete;
    private VideoItem.Category activeFilter = VideoItem.Category.ALL;
    private VideoItem.CameraSubtype activeCameraSubtype = VideoItem.CameraSubtype.ALL;
    private VideoItem.FaditorSubtype activeFaditorSubtype = VideoItem.FaditorSubtype.ALL;
    private VideoItem.ShotSubtype activeShotSubtype = VideoItem.ShotSubtype.ALL;
    private ActivityResultLauncher<Uri> customExportTreePickerLauncher;
    private List<Uri> pendingCustomExportUris = new ArrayList<>();
    private BroadcastReceiver batchMediaCompletedReceiver;
    private boolean isBatchMediaReceiverRegistered = false;

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
                                // Remove deleted video from persistent index, then refresh
                                com.fadcam.data.VideoIndexRepository.getInstance(requireContext())
                                        .removeFromIndex(videoItem.uri.toString());

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
            // Invalidate stats cache when videos are deleted — index will delta-sync on next load
            com.fadcam.utils.VideoStatsCache.invalidateStats(sharedPreferencesManager);
            Log.d(TAG, "Invalidated video stats cache after successful video deletion");
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
        registerBatchMediaCompletedReceiver();
        if (invalidationCoordinator == null && getContext() != null) {
            invalidationCoordinator = new RealtimeMediaInvalidationCoordinator(requireContext());
            invalidationCoordinator.addListener(reason -> requestRealtimeRefresh("coordinator:" + reason));
        }
        if (invalidationCoordinator != null) {
            invalidationCoordinator.start();
        }
    }

    // *** Unregister in onStop ***
    @Override
    public void onStop() {
        super.onStop();
        if (invalidationCoordinator != null) {
            invalidationCoordinator.stop();
        }
        unregisterRecordingCompleteReceiver();
        unregisterStorageLocationChangedReceiver();
        unregisterProcessingStateReceivers();
        unregisterSegmentCompleteReceiver();
        unregisterBatchMediaCompletedReceiver();
    }

    private void registerBatchMediaCompletedReceiver() {
        if (isBatchMediaReceiverRegistered || getContext() == null) return;
        if (batchMediaCompletedReceiver == null) {
            batchMediaCompletedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!isAdded() || intent == null) return;
                    if (!Constants.ACTION_BATCH_MEDIA_COMPLETED.equals(intent.getAction())) return;
                    String message = intent.getStringExtra(Constants.EXTRA_BATCH_COMPLETED_MESSAGE);
                    if (message == null || message.trim().isEmpty()) {
                        message = getString(R.string.records_batch_completed);
                    }
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                    // Delta scan will pick up new/changed files automatically
                    loadRecordsList();
                }
            };
        }
        ContextCompat.registerReceiver(
                requireContext(),
                batchMediaCompletedReceiver,
                new IntentFilter(Constants.ACTION_BATCH_MEDIA_COMPLETED),
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
        isBatchMediaReceiverRegistered = true;
    }

    private void unregisterBatchMediaCompletedReceiver() {
        if (!isBatchMediaReceiverRegistered || batchMediaCompletedReceiver == null || getContext() == null) return;
        try {
            requireContext().unregisterReceiver(batchMediaCompletedReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        isBatchMediaReceiverRegistered = false;
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
                            // Wipe index since storage location changed — completely different files
                            com.fadcam.data.VideoIndexRepository.getInstance(requireContext()).invalidateIndex();
                            if (recordsAdapter != null) {
                                recordsAdapter.clearCaches();
                            }
                            // If storage location changed, we should definitely reload the list.
                            loadRecordsList();
                        } else if (Constants.ACTION_FILES_RESTORED.equals(intent.getAction())) {
                            Log.i(TAG, "Received ACTION_FILES_RESTORED broadcast. Invalidating index and refreshing.");
                            // Invalidate index so restored files are discovered on full re-scan
                            com.fadcam.data.VideoIndexRepository.getInstance(requireContext()).invalidateIndex();
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
                            for (int i = allLoadedItems.size() - 1; i >= 0; i--) {
                                if (allLoadedItems.get(i).uri.equals(originalTempSafUri)) {
                                    allLoadedItems.remove(i);
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
                                    newItem.isNew = true;
                                    videoItems.add(0, newItem); // Add to top, assuming latest
                                    allLoadedItems.add(0, newItem);
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
                                // Sort source list and re-apply current filter.
                                sortItems(allLoadedItems, currentSortOption);
                                applyActiveFilterToUi();
                                updateUiVisibility();
                            }
                            return; // Handled SAF replacement case
                        }

                        // Existing logic for non-SAF replacement (mostly internal storage)
                        if (success && finalUriString != null) {
                            Log.d(TAG, "ACTION_RECORDING_COMPLETE: Success, URI: " + finalUriString
                                    + ". Refreshing list.");
                            // Clear adapter caches to prevent stale duration data
                            if (recordsAdapter != null) {
                                recordsAdapter.clearCaches();
                                Log.d(TAG, "Cleared adapter caches for new recording");
                            }
                            // Invalidate cache so loadRecordsList will load fresh data including the new video
                            Log.d(TAG, "New recording complete, delta scan will pick it up");
                            // Delta scan will pick up the new file automatically
                            loadRecordsList(); // This will delta-scan and find the new video.
                        } else if (!success) {
                            Log.w(TAG, "ACTION_RECORDING_COMPLETE: Failed or no URI. URI: " + finalUriString
                                    + ". Refreshing list.");
                            // Clear adapter caches to prevent stale data
                            if (recordsAdapter != null) {
                                recordsAdapter.clearCaches();
                                Log.d(TAG, "Cleared adapter caches for failed recording");
                            }
                            Log.d(TAG, "Failed recording, refreshing via delta scan");
                            // Still refresh, as a temp file might need its processing state cleared
                            loadRecordsList();
                        } else if (success && finalUriString == null) {
                            // Recording was successful but no specific URI provided - refresh anyway
                            Log.d(TAG, "ACTION_RECORDING_COMPLETE: Success without URI. Refreshing list to detect new video.");
                            // Clear adapter caches to prevent stale duration data
                            if (recordsAdapter != null) {
                                recordsAdapter.clearCaches();
                                Log.d(TAG, "Cleared adapter caches for successful recording without URI");
                            }
                            Log.d(TAG, "Successful recording without URI, delta scan will find it");
                            loadRecordsList(); // This will delta-scan and find the new video
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

        if (customExportTreePickerLauncher == null) {
            customExportTreePickerLauncher = registerForActivityResult(
                    new ActivityResultContracts.OpenDocumentTree(),
                    uri -> {
                        if (uri == null) {
                            pendingCustomExportUris.clear();
                            return;
                        }
                        if (getContext() == null) return;
                        try {
                            final int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                            requireContext().getContentResolver().takePersistableUriPermission(uri, flags);
                        } catch (Exception ignored) {
                        }
                        exportSelectedToCustomTree(uri);
                    });
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

        sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());

        // Initialize header elements
        titleText = view.findViewById(R.id.title_text);
        statsPhotosText = view.findViewById(R.id.text_records_stat_photos);
        statsVideosText = view.findViewById(R.id.text_records_stat_videos);
        statsSizeText = view.findViewById(R.id.text_records_stat_size);
        menuButton = view.findViewById(R.id.action_more_options);
        closeButton = view.findViewById(R.id.action_close);
        selectAllContainer = view.findViewById(R.id.action_select_all_container);
        selectAllCheck = view.findViewById(R.id.action_select_all_check);
        chipFilterAll = view.findViewById(R.id.chip_filter_all);
        chipFilterCamera = view.findViewById(R.id.chip_filter_camera);
        chipFilterScreen = view.findViewById(R.id.chip_filter_screen);
        chipFilterFaditor = view.findViewById(R.id.chip_filter_faditor);
        chipFilterStream = view.findViewById(R.id.chip_filter_stream);
        chipFilterShot = view.findViewById(R.id.chip_filter_shot);
        chipGroupRecordsFilter = view.findViewById(R.id.chip_group_records_filter);
        cameraFilterRow = view.findViewById(R.id.records_camera_filter_row);
        chipGroupCameraFilter = view.findViewById(R.id.chip_group_camera_filter);
        chipCameraAll = view.findViewById(R.id.chip_camera_all);
        chipCameraBack = view.findViewById(R.id.chip_camera_back);
        chipCameraFront = view.findViewById(R.id.chip_camera_front);
        chipCameraDual = view.findViewById(R.id.chip_camera_dual);
        faditorFilterRow = view.findViewById(R.id.records_faditor_filter_row);
        chipGroupFaditorFilter = view.findViewById(R.id.chip_group_faditor_filter);
        chipFaditorAll = view.findViewById(R.id.chip_faditor_all);
        chipFaditorConverted = view.findViewById(R.id.chip_faditor_converted);
        chipFaditorMerge = view.findViewById(R.id.chip_faditor_merge);
        shotFilterRow = view.findViewById(R.id.records_shot_filter_row);
        chipGroupShotFilter = view.findViewById(R.id.chip_group_shot_filter);
        chipShotAll = view.findViewById(R.id.chip_shot_all);
        chipShotBack = view.findViewById(R.id.chip_shot_back);
        chipShotSelfie = view.findViewById(R.id.chip_shot_selfie);
        chipShotFadrec = view.findViewById(R.id.chip_shot_fadrec);
        filterHelperText = view.findViewById(R.id.filter_helper_text);
        filterChecklistButton = view.findViewById(R.id.btn_filter_checklist);
        selectionActionsRow = view.findViewById(R.id.selection_actions_row);
        btnActionSelectAll = view.findViewById(R.id.btn_action_select_all);
        btnActionBatchSave = view.findViewById(R.id.btn_action_batch_save);
        btnActionBatchFaditor = view.findViewById(R.id.btn_action_batch_faditor);
        btnActionBatchDelete = view.findViewById(R.id.btn_action_batch_delete);

        // Setup menu button click listener
        if (menuButton != null) {
            menuButton.setOnClickListener(v -> showRecordsSidebar());
        }
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> exitSelectionMode());
        }
        if (selectAllContainer != null) {
            selectAllContainer.setVisibility(View.GONE);
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
        setupFilterUi();
        setupSelectionActionsUi();

        originalToolbarTitle = getString(R.string.records_title);

        loadingIndicator = view.findViewById(R.id.loading_indicator);
        loadingProgress = view.findViewById(R.id.loading_progress);
        progressHandler = new Handler(Looper.getMainLooper());
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
                Log.d(TAG, "Swipe to refresh triggered — silent background refresh (no skeleton)");

                // Production pattern: keep existing data visible, refresh silently
                // in background, swap data when ready. No skeleton, no UI thrashing.

                // Cancel any in-progress delta scan from a previous refresh
                if (deltaExecutor != null && !deltaExecutor.isShutdown()) {
                    deltaExecutor.shutdownNow();
                }
                deltaExecutor = Executors.newSingleThreadExecutor();

                // Invalidate stats cache
                com.fadcam.utils.VideoStatsCache.invalidateStats(sharedPreferencesManager);

                // Silent background refresh — keep current content visible
                final com.fadcam.data.VideoIndexRepository repo =
                        com.fadcam.data.VideoIndexRepository.getInstance(requireContext());

                deltaExecutor.submit(() -> {
                    try {
                        // Phase 1: Quick DB read (sub-50ms) — no skeleton needed
                        List<VideoItem> dbItems = repo.getVideos(sharedPreferencesManager);
                        List<VideoItem> normalized = normalizeVideoCategories(dbItems);
                        sortItems(normalized, currentSortOption);

                        // Phase 2: Delta scan for freshness (picks up new/deleted files)
                        List<VideoItem> deltaItems = repo.deltaScan(sharedPreferencesManager);
                        List<VideoItem> deltaNormalized = normalizeVideoCategories(deltaItems);
                        sortItems(deltaNormalized, currentSortOption);
                        totalItems = deltaNormalized.size();

                        // Keep caches in sync
                        com.fadcam.utils.VideoSessionCache.updateSessionCache(deltaNormalized);

                        // Push final data to UI in one smooth update
                        new Handler(Looper.getMainLooper()).post(() -> {
                            if (!isAdded()) return;

                            allLoadedItems.clear();
                            allLoadedItems.addAll(deltaNormalized);
                            applyActiveFilterToUi();

                            if (swipeRefreshLayout != null) {
                                swipeRefreshLayout.setRefreshing(false);
                            }
                            Log.i(TAG, "Silent refresh complete: " + deltaNormalized.size() + " items");
                        });

                        // Enrich any new unresolved durations
                        repo.startBackgroundEnrichment((uri, dur) ->
                            Log.d(TAG, "Duration enriched: " + uri + " → " + dur + "ms"));

                    } catch (Exception e) {
                        Log.w(TAG, "Silent refresh failed: " + e.getMessage());
                        new Handler(Looper.getMainLooper()).post(() -> {
                            if (swipeRefreshLayout != null) {
                                swipeRefreshLayout.setRefreshing(false);
                            }
                        });
                    }
                });

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
            boolean isCurrentlyVisible = false;
            if (getActivity() instanceof com.fadcam.MainActivity) {
                com.fadcam.MainActivity mainActivity = (com.fadcam.MainActivity) getActivity();
                isCurrentlyVisible = mainActivity.getCurrentFragmentPosition() == 1 && isVisible();
            }

            if (!isCurrentlyVisible) {
                int currentPos = -1;
                if (getActivity() instanceof com.fadcam.MainActivity) {
                    currentPos = ((com.fadcam.MainActivity) getActivity()).getCurrentFragmentPosition();
                }
                Log.d(TAG,
                        "onViewCreated: Fragment not currently visible, deferring load until user navigates here. Fragment position: "
                                + currentPos);
                return; // Don't load anything yet
            }

            Log.d(TAG, "onViewCreated: Fragment is visible, initiating loadRecordsList.");

            // loadRecordsList() handles everything:
            // - If DB has data → synchronous fast path (~26ms), no skeleton
            // - If DB empty → shows skeleton, runs async SAF scan
            // No need to show skeleton here — it causes a wasteful
            // skeleton→data transition (800ms+ Davey frame drop).

            isInitialLoad = true; // Mark as initial load
            loadRecordsList();
        } else {
            Log.d(TAG, "onViewCreated: Existing data found (" + videoItems.size() + " items), updating UI visibility.");
            // If data exists (e.g., fragment recreated), ensure source list + active filter are applied.
            if (allLoadedItems.isEmpty()) {
                allLoadedItems.addAll(videoItems);
            }
            applyActiveFilterToUi();
            updateUiVisibility();
        }
        // unlocked (session-based) -----
        boolean isAppLockEnabled = sharedPreferencesManager.isAppLockEnabled();
        boolean isSessionUnlocked = sharedPreferencesManager.isAppLockSessionUnlocked();
        if (isAppLockEnabled && !isSessionUnlocked && com.guardanis.applock.AppLock.isEnrolled(requireContext())) {
            fadeOverlay(true);
        } else {
            fadeOverlay(false);
        }
        // (session-based) -----

        // RecordsFragment -----
        // Apply theme to top bar - header bar background is handled by
        // ?attr/colorTopBar in XML
        // No need to set background color programmatically
        // Apply theme to FABs
        int colorButton = resolveThemeColor(R.attr.colorToggle);
        if (fabDeleteSelected != null) {
            fabDeleteSelected.setBackgroundTintList(android.content.res.ColorStateList.valueOf(colorButton));
            fabDeleteSelected.setIconTint(android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE));
            fabDeleteSelected.setTextColor(android.graphics.Color.WHITE);
        }
        // RecordsFragment -----
    } // End onViewCreated

    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "lifecycle onResume: activeFilter=" + activeFilter + ", loadedItems=" + allLoadedItems.size());
        Log.i(TAG, "LOG_LIFECYCLE: onResume called.");

        // Check if an external event (e.g. Faditor export, FadShot capture) requested a refresh
        boolean needsForceReload = false;
        if (sPendingRefresh) {
            sPendingRefresh = false;
            needsForceReload = true;
            Log.i(TAG, "onResume: Pending refresh flag set, invalidating index and forcing reload");
            com.fadcam.data.VideoIndexRepository.getInstance(requireContext()).invalidateIndex();
            if (recordsAdapter != null) {
                recordsAdapter.clearCaches();
            }
        }

        // Always update toolbar/menu and AppLock state
        if (getActivity() instanceof com.fadcam.MainActivity) {
            com.fadcam.MainActivity mainActivity = (com.fadcam.MainActivity) getActivity();
            if (mainActivity.getCurrentFragmentPosition() == 1 && isVisible()) {
                checkAppLock();
            }
        }
        // Update title text
        if (titleText != null) {
            titleText.setText(originalToolbarTitle != null ? originalToolbarTitle : getString(R.string.records_title));
            Log.d(TAG, "Title text updated in onResume.");
        } else {
            Log.w(TAG, "Could not update title in onResume - titleText is null.");
        }
        if (sharedPreferencesManager == null && getContext() != null) {
            sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());
        }

        if (recordsAdapter != null && sharedPreferencesManager != null) {
            String currentTheme = sharedPreferencesManager.sharedPreferences
                    .getString(com.fadcam.Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
            boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
            recordsAdapter.setSnowVeilTheme(isSnowVeilTheme);
            Log.d(TAG, "onResume: Updated Snow Veil theme flag on adapter: " + isSnowVeilTheme);
        }

        // -----------

        // -------------- CRITICAL FIX: Only load when fragment is actually visible to
        // user -----------
        boolean isCurrentlyVisible = false;
        int currentPos = -1;
        if (getActivity() instanceof com.fadcam.MainActivity) {
            com.fadcam.MainActivity mainActivity = (com.fadcam.MainActivity) getActivity();
            currentPos = mainActivity.getCurrentFragmentPosition();
            isCurrentlyVisible = currentPos == 1 && isVisible();
        }

        if (!isCurrentlyVisible) {
            Log.d(TAG, "onResume: Fragment not currently visible to user, skipping load. Fragment position: "
                    + currentPos + ", isVisible: " + isVisible());
            return;
        }

        Log.d(TAG, "onResume: Fragment is visible to user, proceeding with load check");


        // Only reload if we actually need to (no data or cache invalidated)
        boolean hasData = recordsAdapter != null && recordsAdapter.getItemCount() > 0 && !videoItems.isEmpty();
        boolean hasDbIndex = com.fadcam.data.VideoIndexRepository.getInstance(requireContext()).getIndexedCount() > 0;

        if (needsForceReload && !isLoading) {
            Log.i(TAG, "LOG_REFRESH: Force reload from onResume (pending refresh consumed)");
            loadRecordsList(true);
        } else if (hasData && (hasDbIndex || !videoItems.isEmpty()) && !isLoading) {
            Log.d(TAG, "onResume: Already have " + videoItems.size() + " videos loaded, skipping duplicate load");
            updateUiVisibility(); // Just update visibility state
        } else if (!isLoading) {
            Log.i(TAG, "LOG_REFRESH: Loading from onResume - hasData: " + hasData + ", hasDbIndex: " + hasDbIndex);
            loadRecordsList();
        } else {
            Log.d(TAG, "onResume: Already loading, skipping duplicate request");
        }


        // no-op: view mode toggle is in Records Options side sheet
        // Records tab -----
        requireActivity().invalidateOptionsMenu();
        // Records tab -----
    }

    /**
     * Handle visibility changes from hide/show navigation.
     * With hide/show, onResume is NOT called on tab switches — only onHiddenChanged is.
     * This ensures data refresh and UI updates happen when the tab becomes visible.
     */
    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        Log.d(TAG, "onHiddenChanged: hidden=" + hidden + ", isResumed=" + isResumed());
        
        if (!hidden && isResumed() && isAdded() && getContext() != null) {
            Log.d(TAG, "onHiddenChanged: Fragment shown, checking for data refresh");
            
            // Check pending refresh (e.g., Faditor export)
            if (sPendingRefresh) {
                sPendingRefresh = false;
                Log.i(TAG, "onHiddenChanged: Pending refresh flag set, invalidating index");
                com.fadcam.data.VideoIndexRepository.getInstance(requireContext()).invalidateIndex();
                if (recordsAdapter != null) {
                    recordsAdapter.clearCaches();
                }
            }
            
            // Update AppLock state
            checkAppLock();
            
            // Update title
            if (titleText != null) {
                titleText.setText(originalToolbarTitle != null ? originalToolbarTitle : getString(R.string.records_title));
            }
            
            // Check if data needs refreshing
            boolean hasData = recordsAdapter != null && recordsAdapter.getItemCount() > 0 && !videoItems.isEmpty();
            boolean hasDbIndex = com.fadcam.data.VideoIndexRepository.getInstance(requireContext()).getIndexedCount() > 0;
            
            if (hasData && hasDbIndex && !isLoading) {
                Log.d(TAG, "onHiddenChanged: Data valid, updating UI visibility only");
                updateUiVisibility();
            } else if (!isLoading) {
                Log.i(TAG, "onHiddenChanged: Need to refresh data");
                loadRecordsList();
            }
        }
    }

    /**
     * Call this method when the fragment becomes visible to the user for the first
     * time
     * or when they navigate back to it. This handles lazy loading.
     */
    public void onFragmentBecameVisible() {
        Log.d(TAG, "onFragmentBecameVisible: Fragment is now visible to user");
        boolean shouldLoadData = (videoItems == null || videoItems.isEmpty()) && !isLoading;
        Log.d(TAG, "visibility_event: shouldLoadData=" + shouldLoadData + ", currentCount="
                + (videoItems == null ? 0 : videoItems.size()));

        // Check if we need to load data for the first time
        if ((videoItems == null || videoItems.isEmpty()) && !isLoading) {
            Log.d(TAG, "onFragmentBecameVisible: No data loaded yet, starting initial load");

            if (videoItems == null) {
                videoItems = new ArrayList<>();
            }

            // loadRecordsList() handles skeleton display internally:
            // - DB has data → synchronous fast path, no skeleton needed
            // - DB empty → shows skeleton, runs async SAF scan
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
        recyclerView.setHasFixedSize(false); // Mixed item types (month headers + records)

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
        recordsAdapter.setGridSpan(currentGridSpan);

        // Set the layout manager
        setLayoutManager();

        // Set adapter
        recyclerView.setAdapter(recordsAdapter);

        // Set up month selection listener
        recordsAdapter.setOnMonthActionListener((monthKey, items) -> {
            if (items == null || items.isEmpty()) return;
            // Enter selection mode if not already
            if (!isInSelectionMode) {
                isInSelectionMode = true;
            }
            // Check if all items in the month are already selected
            boolean allSelected = true;
            for (VideoItem item : items) {
                if (!selectedUris.contains(item.uri)) {
                    allSelected = false;
                    break;
                }
            }
            // Toggle: if all selected → deselect all; otherwise → select all
            for (VideoItem item : items) {
                if (allSelected) {
                    selectedUris.remove(item.uri);
                } else {
                    if (!selectedUris.contains(item.uri)) {
                        selectedUris.add(item.uri);
                    }
                }
            }
            if (selectedUris.isEmpty()) {
                exitSelectionMode();
            } else {
                updateUiForSelectionMode();
                recordsAdapter.setSelectionModeActive(true, selectedUris);
            }
        });

        // Setup fast scroller
        fastScroller = getView().findViewById(R.id.fast_scroller);
        if (fastScroller != null) {
            fastScroller.attachTo(recyclerView);
            fastScroller.setSectionIndexer(position -> recordsAdapter.getSectionText(position));
        }

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
                showBatchActionsSheet();
            });
            Log.d(TAG, "FAB Delete listener set.");
        } else {
            Log.w(TAG, "fabDeleteSelected is null in setupFabListeners (Might be initially GONE).");
        }

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

    }

    private void applyGridSpan(int newSpan) {
        vibrate();
        currentGridSpan = newSpan;
        setLayoutManager();
        if (recordsAdapter != null) {
            recordsAdapter.setGridSpan(currentGridSpan);
        }
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
    private boolean pendingForcedRealtimeReload = false;
    @Nullable
    private RealtimeMediaInvalidationCoordinator invalidationCoordinator;
    private int totalItems = 0; // Track the total number of items
    private final List<VideoItem> cachedInternalItems = new ArrayList<>(); // Cache internal items
    private final List<VideoItem> cachedSafItems = new ArrayList<>(); // Cache SAF items
    private final List<VideoItem> cachedTempItems = new ArrayList<>(); // Cache temp items

    private static final class SafCandidate {
        final DocumentFile file;
        final VideoItem.Category category;
        final VideoItem.ShotSubtype shotSubtype;
        final VideoItem.CameraSubtype cameraSubtype;
        final VideoItem.FaditorSubtype faditorSubtype;

        SafCandidate(@NonNull DocumentFile file, @NonNull VideoItem.Category category) {
            this(
                    file,
                    category,
                    VideoItem.ShotSubtype.UNKNOWN,
                    VideoItem.CameraSubtype.UNKNOWN,
                    VideoItem.FaditorSubtype.UNKNOWN);
        }

        SafCandidate(
                @NonNull DocumentFile file,
                @NonNull VideoItem.Category category,
                @NonNull VideoItem.ShotSubtype shotSubtype
        ) {
            this(file, category, shotSubtype, VideoItem.CameraSubtype.UNKNOWN, VideoItem.FaditorSubtype.UNKNOWN);
        }

        SafCandidate(
                @NonNull DocumentFile file,
                @NonNull VideoItem.Category category,
                @NonNull VideoItem.ShotSubtype shotSubtype,
                @NonNull VideoItem.CameraSubtype cameraSubtype
        ) {
            this(file, category, shotSubtype, cameraSubtype, VideoItem.FaditorSubtype.UNKNOWN);
        }

        SafCandidate(
                @NonNull DocumentFile file,
                @NonNull VideoItem.Category category,
                @NonNull VideoItem.ShotSubtype shotSubtype,
                @NonNull VideoItem.CameraSubtype cameraSubtype,
                @NonNull VideoItem.FaditorSubtype faditorSubtype
        ) {
            this.file = file;
            this.category = category;
            this.shotSubtype = shotSubtype;
            this.cameraSubtype = cameraSubtype;
            this.faditorSubtype = faditorSubtype;
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
        
        File baseDir = new File(recordsDir, Constants.RECORDING_DIRECTORY);
        if (!baseDir.exists() || !baseDir.isDirectory()) {
            Log.i(TAG, "LOG_GET_INTERNAL: Base directory does not exist yet: " + baseDir.getAbsolutePath());
            return items;
        }

        // Preferred folder-based categorization.
        scanCategoryDirectoryInternal(items, new File(baseDir, Constants.RECORDING_SUBDIR_CAMERA), VideoItem.Category.CAMERA);
        // Legacy dual root folder support (mapped under CAMERA category)
        scanCategoryDirectoryInternal(items, new File(baseDir, Constants.RECORDING_SUBDIR_DUAL), VideoItem.Category.CAMERA);
        scanCategoryDirectoryInternal(items, new File(baseDir, Constants.RECORDING_SUBDIR_SCREEN), VideoItem.Category.SCREEN);
        scanCategoryDirectoryInternal(items, new File(baseDir, Constants.RECORDING_SUBDIR_FADITOR), VideoItem.Category.FADITOR);
        scanCategoryDirectoryInternal(items, new File(baseDir, Constants.RECORDING_SUBDIR_STREAM), VideoItem.Category.STREAM);
        scanCategoryDirectoryInternal(items, new File(baseDir, Constants.RECORDING_SUBDIR_SHOT), VideoItem.Category.SHOT);

        // Backward compatibility: include legacy root-level files under FadCam.
        scanLegacyRootInternal(items, baseDir);
        
        Log.i(TAG, "LOG_GET_INTERNAL: Found " + items.size() + " total internal records. END.");
        return items;
    }
    
    /**
     * Helper method to scan a directory for video files.
     */
    private void scanCategoryDirectoryInternal(List<VideoItem> out, File directory, VideoItem.Category category) {
        Log.d(TAG, "LOG_GET_INTERNAL: Checking category dir: " + directory.getAbsolutePath() + " (" + category + ")");
        if (!directory.exists() || !directory.isDirectory()) {
            return;
        }
        if (category == VideoItem.Category.SHOT) {
            scanShotDirectoryInternal(out, directory);
            return;
        }
        if (category == VideoItem.Category.CAMERA) {
            scanCameraDirectoryInternal(out, directory);
            return;
        }
        if (category == VideoItem.Category.FADITOR) {
            scanFaditorDirectoryInternal(out, directory);
            return;
        }
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!file.isFile()) continue;
            VideoItem item = buildVideoItemFromInternalFile(
                    file,
                    category,
                    VideoItem.ShotSubtype.UNKNOWN,
                    VideoItem.CameraSubtype.UNKNOWN,
                    VideoItem.FaditorSubtype.UNKNOWN);
            if (item != null) out.add(item);
        }
    }

    private void scanFaditorDirectoryInternal(@NonNull List<VideoItem> out, @NonNull File faditorRoot) {
        File[] children = faditorRoot.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child == null) continue;
            if (child.isFile()) {
                VideoItem item = buildVideoItemFromInternalFile(
                        child,
                        VideoItem.Category.FADITOR,
                        VideoItem.ShotSubtype.UNKNOWN,
                        VideoItem.CameraSubtype.UNKNOWN,
                        inferFaditorSubtypeFromName(child.getName()));
                if (item != null) out.add(item);
                continue;
            }
            if (!child.isDirectory()) continue;
            VideoItem.FaditorSubtype subtype = inferFaditorSubtypeFromFolder(child.getName());
            File[] nested = child.listFiles();
            if (nested == null) continue;
            for (File nestedFile : nested) {
                if (nestedFile != null && nestedFile.isFile()) {
                    VideoItem item = buildVideoItemFromInternalFile(
                            nestedFile,
                            VideoItem.Category.FADITOR,
                            VideoItem.ShotSubtype.UNKNOWN,
                            VideoItem.CameraSubtype.UNKNOWN,
                            subtype);
                    if (item != null) out.add(item);
                }
            }
        }
    }

    private void scanCameraDirectoryInternal(@NonNull List<VideoItem> out, @NonNull File cameraRoot) {
        File[] children = cameraRoot.listFiles();
        if (children == null) return;

        for (File child : children) {
            if (child == null) continue;
            if (child.isFile()) {
                VideoItem item = buildVideoItemFromInternalFile(
                        child,
                        VideoItem.Category.CAMERA,
                        VideoItem.ShotSubtype.UNKNOWN,
                        inferCameraSubtypeFromName(child.getName()),
                        VideoItem.FaditorSubtype.UNKNOWN);
                if (item != null) out.add(item);
                continue;
            }
            if (!child.isDirectory()) continue;
            VideoItem.CameraSubtype subtype = inferCameraSubtypeFromFolder(child.getName());
            File[] nested = child.listFiles();
            if (nested == null) continue;
            for (File nestedFile : nested) {
                if (nestedFile != null && nestedFile.isFile()) {
                    VideoItem item = buildVideoItemFromInternalFile(
                            nestedFile,
                            VideoItem.Category.CAMERA,
                            VideoItem.ShotSubtype.UNKNOWN,
                            subtype,
                            VideoItem.FaditorSubtype.UNKNOWN);
                    if (item != null) out.add(item);
                }
            }
        }
    }

    private void scanShotDirectoryInternal(@NonNull List<VideoItem> out, @NonNull File shotRoot) {
        File[] children = shotRoot.listFiles();
        if (children == null) return;

        for (File child : children) {
            if (child == null) continue;
            if (child.isFile()) {
                VideoItem item = buildVideoItemFromInternalFile(
                        child,
                        VideoItem.Category.SHOT,
                        inferShotSubtypeFromName(child.getName()),
                        VideoItem.CameraSubtype.UNKNOWN,
                        VideoItem.FaditorSubtype.UNKNOWN);
                if (item != null) out.add(item);
                continue;
            }
            if (!child.isDirectory()) continue;
            VideoItem.ShotSubtype subtype = inferShotSubtypeFromFolder(child.getName());
            File[] nested = child.listFiles();
            if (nested == null) continue;
            for (File nestedFile : nested) {
                if (nestedFile != null && nestedFile.isFile()) {
                    VideoItem item = buildVideoItemFromInternalFile(
                            nestedFile,
                            VideoItem.Category.SHOT,
                            subtype,
                            VideoItem.CameraSubtype.UNKNOWN,
                            VideoItem.FaditorSubtype.UNKNOWN);
                    if (item != null) out.add(item);
                }
            }
        }
    }

    @Nullable
    private VideoItem buildVideoItemFromInternalFile(
            @NonNull File file,
            @NonNull VideoItem.Category category,
            @NonNull VideoItem.ShotSubtype explicitShotSubtype,
            @NonNull VideoItem.CameraSubtype explicitCameraSubtype,
            @NonNull VideoItem.FaditorSubtype explicitFaditorSubtype
    ) {
        String name = file.getName();
        VideoItem.MediaType mediaType = inferMediaTypeFromName(name);
        if (mediaType == null || name.startsWith("temp_")) {
            return null;
        }
        long lastModifiedMeta = file.lastModified();
        long timestampFromFile = Utils.parseTimestampFromFilename(name);
        long finalTimestamp = lastModifiedMeta > 0
                ? lastModifiedMeta
                : (timestampFromFile > 0 ? timestampFromFile : System.currentTimeMillis());
        VideoItem.ShotSubtype shotSubtype = category == VideoItem.Category.SHOT
                ? resolveShotSubtype(explicitShotSubtype, name)
                : VideoItem.ShotSubtype.UNKNOWN;
        VideoItem.CameraSubtype cameraSubtype = category == VideoItem.Category.CAMERA
                ? resolveCameraSubtype(explicitCameraSubtype, name)
                : VideoItem.CameraSubtype.UNKNOWN;
        VideoItem.FaditorSubtype faditorSubtype = category == VideoItem.Category.FADITOR
                ? resolveFaditorSubtype(explicitFaditorSubtype, name)
                : VideoItem.FaditorSubtype.UNKNOWN;
        VideoItem newItem = new VideoItem(
                Uri.fromFile(file),
                name,
                file.length(),
                finalTimestamp,
                category,
                mediaType,
                shotSubtype,
                cameraSubtype,
                faditorSubtype);
        newItem.isNew = Utils.isVideoConsideredNew(finalTimestamp);
        return newItem;
    }

    private void scanLegacyRootInternal(List<VideoItem> out, File baseDir) {
        File[] files = baseDir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            String name = file.getName();
            VideoItem.MediaType mediaType = inferMediaTypeFromName(name);
            if (mediaType == null || name.startsWith("temp_")) {
                continue;
            }
            VideoItem.Category inferred = inferCategoryFromLegacyName(name);
            long lastModifiedMeta = file.lastModified();
            long timestampFromFile = Utils.parseTimestampFromFilename(name);
            long finalTimestamp = lastModifiedMeta > 0
                    ? lastModifiedMeta
                    : (timestampFromFile > 0 ? timestampFromFile : System.currentTimeMillis());
            VideoItem item = new VideoItem(
                    Uri.fromFile(file),
                    name,
                    file.length(),
                    finalTimestamp,
                    inferred,
                    mediaType,
                    inferred == VideoItem.Category.SHOT
                            ? resolveShotSubtype(VideoItem.ShotSubtype.UNKNOWN, name)
                            : VideoItem.ShotSubtype.UNKNOWN,
                    inferred == VideoItem.Category.CAMERA
                            ? resolveCameraSubtype(VideoItem.CameraSubtype.UNKNOWN, name)
                            : VideoItem.CameraSubtype.UNKNOWN,
                    inferred == VideoItem.Category.FADITOR
                            ? resolveFaditorSubtype(VideoItem.FaditorSubtype.UNKNOWN, name)
                            : VideoItem.FaditorSubtype.UNKNOWN);
            item.isNew = Utils.isVideoConsideredNew(finalTimestamp);
            out.add(item);
        }
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

        List<SafCandidate> bucketedFiles = new ArrayList<>();
        addSafCategoryFiles(bucketedFiles, targetDir, Constants.RECORDING_SUBDIR_CAMERA, VideoItem.Category.CAMERA);
        // Legacy dual root folder support (mapped under CAMERA category)
        addSafCategoryFiles(bucketedFiles, targetDir, Constants.RECORDING_SUBDIR_DUAL, VideoItem.Category.CAMERA);
        addSafCategoryFiles(bucketedFiles, targetDir, Constants.RECORDING_SUBDIR_SCREEN, VideoItem.Category.SCREEN);
        addSafCategoryFiles(bucketedFiles, targetDir, Constants.RECORDING_SUBDIR_FADITOR, VideoItem.Category.FADITOR);
        addSafCategoryFiles(bucketedFiles, targetDir, Constants.RECORDING_SUBDIR_STREAM, VideoItem.Category.STREAM);
        addSafCategoryFiles(bucketedFiles, targetDir, Constants.RECORDING_SUBDIR_SHOT, VideoItem.Category.SHOT);

        // Backward compatibility: include root files and infer category by filename prefix.
        DocumentFile[] rootFiles = targetDir.listFiles();
        if (rootFiles != null) {
            for (DocumentFile rootFile : rootFiles) {
                if (rootFile == null || !rootFile.isFile()) {
                    continue;
                }
                bucketedFiles.add(new SafCandidate(rootFile, VideoItem.Category.UNKNOWN));
            }
        }

        final int CHUNK_SIZE = 12;
        int totalFiles = bucketedFiles.size();
        for (int i = 0; i < totalFiles; i += CHUNK_SIZE) {
            int endIndex = Math.min(i + CHUNK_SIZE, totalFiles);
            for (int j = i; j < endIndex; j++) {
                SafCandidate candidate = bucketedFiles.get(j);
                addSafMediaItem(
                        safVideoItems,
                        candidate.file,
                        candidate.category,
                        candidate.shotSubtype,
                        candidate.cameraSubtype,
                        candidate.faditorSubtype);
            }
            if (callback != null && !safVideoItems.isEmpty()) {
                int progress = totalFiles == 0 ? 100 : Math.round(((float) endIndex / totalFiles) * 100);
                callback.onProgress(new ArrayList<>(safVideoItems), progress, endIndex < totalFiles);
            }
            if (endIndex < totalFiles) {
                try {
                    Thread.sleep(10);
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
            // Mode INACTIVE: Click opens media
            Log.d(TAG, "Fragment onVideoClick: Opening media " + videoItem.displayName);
            String uriString = videoItem.uri.toString();
            sharedPreferencesManager.addOpenedVideoUri(uriString);
            if (recordsAdapter != null) {
                int pos = recordsAdapter.findPositionByUri(videoItem.uri);
                if (pos != -1)
                    recordsAdapter.notifyItemChanged(pos);
            }
            Intent intent = videoItem.mediaType == VideoItem.MediaType.IMAGE
                    ? new Intent(getActivity(), ImageViewerActivity.class)
                    : new Intent(getActivity(), VideoPlayerActivity.class);
            intent.setData(videoItem.uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start player", e);
                Toast.makeText(getContext(), "Error opening media", Toast.LENGTH_SHORT).show();
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
    /** Updates Title, FABs based on whether selection mode is active */
    private void updateUiForSelectionMode() {
        if (!isAdded() || titleText == null || getActivity() == null) {
            Log.w(TAG, "Cannot update selection UI - not ready");
            return;
        }

        if (isInSelectionMode) {
            int count = selectedUris.size();
            titleText.setText(count > 0 ? count + " selected" : "Select items");
            showBatchFabAnimated();
            if (filterChecklistButton != null) {
                filterChecklistButton.setTextColor(resolveThemeColor(R.attr.colorToggle));
            }
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
            if (selectAllContainer != null) {
                selectAllContainer.setVisibility(View.GONE);
            }
        } else {
            titleText.setText(originalToolbarTitle != null ? originalToolbarTitle : getString(R.string.records_title));
            hideBatchFabAnimated();
            if (filterChecklistButton != null) {
                filterChecklistButton.setTextColor(0xFF666666);
            }
            // FAB removed
            // Restore more-options icon and hide close button
            if (menuButton != null) {
                menuButton.setVisibility(View.VISIBLE);
                menuButton.setImageResource(R.drawable.ic_two_line_hamburger);
                menuButton.setOnClickListener(v -> showRecordsSidebar());
                menuButton.setContentDescription(getString(R.string.more_options));
            }
            if (closeButton != null) {
                closeButton.setVisibility(View.GONE);
            }
            if (selectAllContainer != null) {
                selectAllContainer.setVisibility(View.GONE);
            }
        }
        updateSelectionActionRow();
        // Refresh the options menu (to show/hide "More Options")
        if (getActivity() != null) {
            getActivity().invalidateOptionsMenu();
        }
    }

    private void showBatchFabAnimated() {
        if (fabDeleteSelected == null) return;
        if (fabDeleteSelected.getVisibility() == View.VISIBLE && fabDeleteSelected.getAlpha() >= 0.99f) {
            return;
        }
        fabDeleteSelected.setVisibility(View.VISIBLE);
        fabDeleteSelected.setAlpha(0f);
        fabDeleteSelected.setScaleX(0.88f);
        fabDeleteSelected.setScaleY(0.88f);
        fabDeleteSelected.setTranslationY(dpToPx(16));
        fabDeleteSelected.extend();
        fabDeleteSelected.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
        fabDeleteSelected.postDelayed(() -> {
            if (fabDeleteSelected != null && fabDeleteSelected.getVisibility() == View.VISIBLE) {
                fabDeleteSelected.shrink();
            }
        }, 1300L);
    }

    private void hideBatchFabAnimated() {
        if (fabDeleteSelected == null) return;
        if (fabDeleteSelected.getVisibility() != View.VISIBLE) return;
        fabDeleteSelected.extend();
        fabDeleteSelected.animate()
                .alpha(0f)
                .scaleX(0.9f)
                .scaleY(0.9f)
                .translationY(dpToPx(14))
                .setDuration(170)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    if (fabDeleteSelected != null) {
                        fabDeleteSelected.setVisibility(View.GONE);
                        fabDeleteSelected.setAlpha(1f);
                        fabDeleteSelected.setScaleX(1f);
                        fabDeleteSelected.setScaleY(1f);
                        fabDeleteSelected.setTranslationY(0f);
                    }
                })
                .start();
    }

    private void setupFilterUi() {
        styleFilterChips();
        if (chipFilterAll != null) {
            chipFilterAll.setOnClickListener(v -> setActiveFilter(VideoItem.Category.ALL));
        }
        if (chipFilterCamera != null) {
            chipFilterCamera.setOnClickListener(v -> setActiveFilter(VideoItem.Category.CAMERA));
        }
        if (chipFilterScreen != null) {
            chipFilterScreen.setOnClickListener(v -> setActiveFilter(VideoItem.Category.SCREEN));
        }
        if (chipFilterFaditor != null) {
            chipFilterFaditor.setOnClickListener(v -> setActiveFilter(VideoItem.Category.FADITOR));
        }
        if (chipFilterStream != null) {
            chipFilterStream.setOnClickListener(v -> setActiveFilter(VideoItem.Category.STREAM));
        }
        if (chipFilterShot != null) {
            chipFilterShot.setOnClickListener(v -> setActiveFilter(VideoItem.Category.SHOT));
        }
        if (chipCameraAll != null) {
            chipCameraAll.setOnClickListener(v -> setActiveCameraSubtype(VideoItem.CameraSubtype.ALL));
        }
        if (chipCameraBack != null) {
            chipCameraBack.setOnClickListener(v -> setActiveCameraSubtype(VideoItem.CameraSubtype.BACK));
        }
        if (chipCameraFront != null) {
            chipCameraFront.setOnClickListener(v -> setActiveCameraSubtype(VideoItem.CameraSubtype.FRONT));
        }
        if (chipCameraDual != null) {
            chipCameraDual.setOnClickListener(v -> setActiveCameraSubtype(VideoItem.CameraSubtype.DUAL));
        }
        if (chipFaditorAll != null) {
            chipFaditorAll.setOnClickListener(v -> setActiveFaditorSubtype(VideoItem.FaditorSubtype.ALL));
        }
        if (chipFaditorConverted != null) {
            chipFaditorConverted.setOnClickListener(v -> setActiveFaditorSubtype(VideoItem.FaditorSubtype.CONVERTED));
        }
        if (chipFaditorMerge != null) {
            chipFaditorMerge.setOnClickListener(v -> setActiveFaditorSubtype(VideoItem.FaditorSubtype.MERGE));
        }
        if (chipShotAll != null) {
            chipShotAll.setOnClickListener(v -> setActiveShotSubtype(VideoItem.ShotSubtype.ALL));
        }
        if (chipShotBack != null) {
            chipShotBack.setOnClickListener(v -> setActiveShotSubtype(VideoItem.ShotSubtype.BACK));
        }
        if (chipShotSelfie != null) {
            chipShotSelfie.setOnClickListener(v -> setActiveShotSubtype(VideoItem.ShotSubtype.SELFIE));
        }
        if (chipShotFadrec != null) {
            chipShotFadrec.setOnClickListener(v -> setActiveShotSubtype(VideoItem.ShotSubtype.FADREC));
        }
        if (filterChecklistButton != null) {
            filterChecklistButton.setOnClickListener(v -> {
                if (isInSelectionMode) {
                    exitSelectionMode();
                } else {
                    enterSelectionMode();
                }
            });
        }
        updateFilterChipLabels();
        updateFilterChipUi();
        updateCameraFilterRowVisibility();
        updateCameraFilterChipLabels();
        updateCameraFilterChipUi();
        updateFaditorFilterRowVisibility();
        updateFaditorFilterChipLabels();
        updateFaditorFilterChipUi();
        updateShotFilterRowVisibility();
        updateShotFilterChipLabels();
        updateShotFilterChipUi();
        updateFilterHelperText();
    }

    private void setupSelectionActionsUi() {
        if (btnActionSelectAll != null) {
            btnActionSelectAll.setOnClickListener(v -> toggleSelectAllVisibleItems());
        }
        if (btnActionBatchSave != null) {
            btnActionBatchSave.setOnClickListener(v -> Toast.makeText(requireContext(),
                    getString(R.string.records_batch_coming_soon), Toast.LENGTH_SHORT).show());
        }
        if (btnActionBatchFaditor != null) {
            btnActionBatchFaditor.setOnClickListener(v -> Toast.makeText(requireContext(),
                    getString(R.string.records_batch_coming_soon), Toast.LENGTH_SHORT).show());
        }
        if (btnActionBatchDelete != null) {
            btnActionBatchDelete.setOnClickListener(v -> {
                if (selectedUris.isEmpty()) {
                    Toast.makeText(requireContext(), getString(R.string.records_batch_select_items_first), Toast.LENGTH_SHORT)
                            .show();
                    return;
                }
                confirmDeleteSelected();
            });
        }
        updateSelectionActionRow();
    }

    private void setActiveFilter(@NonNull VideoItem.Category filter) {
        if (activeFilter == filter) return;
        activeFilter = filter;
        if (activeFilter != VideoItem.Category.CAMERA) {
            activeCameraSubtype = VideoItem.CameraSubtype.ALL;
        }
        if (activeFilter != VideoItem.Category.FADITOR) {
            activeFaditorSubtype = VideoItem.FaditorSubtype.ALL;
        }
        if (activeFilter != VideoItem.Category.SHOT) {
            activeShotSubtype = VideoItem.ShotSubtype.ALL;
        }
        // Prevent hidden selections when the visible set changes.
        if (isInSelectionMode) {
            exitSelectionMode();
        }
        applyActiveFilterToUi();
    }

    private void setActiveCameraSubtype(@NonNull VideoItem.CameraSubtype cameraSubtype) {
        if (activeCameraSubtype == cameraSubtype) return;
        activeCameraSubtype = cameraSubtype;
        if (isInSelectionMode) {
            exitSelectionMode();
        }
        applyActiveFilterToUi();
    }

    private void setActiveShotSubtype(@NonNull VideoItem.ShotSubtype shotSubtype) {
        if (activeShotSubtype == shotSubtype) return;
        activeShotSubtype = shotSubtype;
        if (isInSelectionMode) {
            exitSelectionMode();
        }
        applyActiveFilterToUi();
    }

    private void setActiveFaditorSubtype(@NonNull VideoItem.FaditorSubtype faditorSubtype) {
        if (activeFaditorSubtype == faditorSubtype) return;
        activeFaditorSubtype = faditorSubtype;
        if (isInSelectionMode) {
            exitSelectionMode();
        }
        applyActiveFilterToUi();
    }

    private void applyActiveFilterToUi() {
        List<VideoItem> filteredItems = new ArrayList<>();
        for (VideoItem item : allLoadedItems) {
            if (activeFilter == VideoItem.Category.ALL || item.category == activeFilter) {
                if (activeFilter == VideoItem.Category.CAMERA && !matchesCameraSubtype(item, activeCameraSubtype)) {
                    continue;
                }
                if (activeFilter == VideoItem.Category.FADITOR && !matchesFaditorSubtype(item, activeFaditorSubtype)) {
                    continue;
                }
                if (activeFilter == VideoItem.Category.SHOT && !matchesShotSubtype(item, activeShotSubtype)) {
                    continue;
                }
                filteredItems.add(item);
            }
        }
        videoItems.clear();
        videoItems.addAll(filteredItems);
        if (recordsAdapter != null) {
            recordsAdapter.updateRecords(videoItems);
        }
        updateFilterChipLabels();
        updateFilterChipUi();
        updateCameraFilterRowVisibility();
        updateCameraFilterChipLabels();
        updateCameraFilterChipUi();
        updateFaditorFilterRowVisibility();
        updateFaditorFilterChipLabels();
        updateFaditorFilterChipUi();
        updateShotFilterRowVisibility();
        updateShotFilterChipLabels();
        updateShotFilterChipUi();
        updateFilterHelperText();
        updateUiVisibility();
        updateSelectionActionRow();
    }

    private void updateFilterChipUi() {
        if (chipFilterAll != null) chipFilterAll.setChecked(activeFilter == VideoItem.Category.ALL);
        if (chipFilterCamera != null) chipFilterCamera.setChecked(activeFilter == VideoItem.Category.CAMERA);
        if (chipFilterScreen != null) chipFilterScreen.setChecked(activeFilter == VideoItem.Category.SCREEN);
        if (chipFilterFaditor != null) chipFilterFaditor.setChecked(activeFilter == VideoItem.Category.FADITOR);
        if (chipFilterStream != null) chipFilterStream.setChecked(activeFilter == VideoItem.Category.STREAM);
        if (chipFilterShot != null) chipFilterShot.setChecked(activeFilter == VideoItem.Category.SHOT);
    }

    private void updateCameraFilterRowVisibility() {
        if (cameraFilterRow == null) return;
        
        boolean shouldShow = activeFilter == VideoItem.Category.CAMERA;
        boolean isCurrentlyVisible = cameraFilterRow.getVisibility() == View.VISIBLE;
        
        if (shouldShow && !isCurrentlyVisible) {
            // Show with fluid amoeba-like animation
            cameraFilterRow.setVisibility(View.VISIBLE);
            cameraFilterRow.setAlpha(0f);
            cameraFilterRow.setScaleY(0.3f);
            cameraFilterRow.setTranslationY(-20f);
            
            cameraFilterRow.animate()
                .alpha(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(400)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.5f))
                .start();
        } else if (!shouldShow && isCurrentlyVisible) {
            // Hide with smooth collapse animation - completes fully before hiding
            cameraFilterRow.animate()
                .alpha(0f)
                .scaleY(0f)
                .translationY(-30f)
                .setDuration(250)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    cameraFilterRow.setVisibility(View.GONE);
                    // Reset for next animation
                    cameraFilterRow.setScaleY(0.3f);
                    cameraFilterRow.setTranslationY(-20f);
                })
                .start();
        }
    }

    private void updateFaditorFilterRowVisibility() {
        if (faditorFilterRow == null) return;
        
        boolean shouldShow = activeFilter == VideoItem.Category.FADITOR;
        boolean isCurrentlyVisible = faditorFilterRow.getVisibility() == View.VISIBLE;
        
        if (shouldShow && !isCurrentlyVisible) {
            // Show with fluid amoeba-like animation
            faditorFilterRow.setVisibility(View.VISIBLE);
            faditorFilterRow.setAlpha(0f);
            faditorFilterRow.setScaleY(0.3f);
            faditorFilterRow.setTranslationY(-20f);
            
            faditorFilterRow.animate()
                .alpha(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(400)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.5f))
                .start();
        } else if (!shouldShow && isCurrentlyVisible) {
            // Hide with smooth collapse animation - completes fully before hiding
            faditorFilterRow.animate()
                .alpha(0f)
                .scaleY(0f)
                .translationY(-30f)
                .setDuration(250)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    faditorFilterRow.setVisibility(View.GONE);
                    // Reset for next animation
                    faditorFilterRow.setScaleY(0.3f);
                    faditorFilterRow.setTranslationY(-20f);
                })
                .start();
        }
    }

    private void updateCameraFilterChipUi() {
        if (chipCameraAll != null) chipCameraAll.setChecked(activeCameraSubtype == VideoItem.CameraSubtype.ALL);
        if (chipCameraBack != null) chipCameraBack.setChecked(activeCameraSubtype == VideoItem.CameraSubtype.BACK);
        if (chipCameraFront != null) chipCameraFront.setChecked(activeCameraSubtype == VideoItem.CameraSubtype.FRONT);
        if (chipCameraDual != null) chipCameraDual.setChecked(activeCameraSubtype == VideoItem.CameraSubtype.DUAL);
    }

    private boolean matchesCameraSubtype(@NonNull VideoItem item, @NonNull VideoItem.CameraSubtype filterSubtype) {
        if (item.category != VideoItem.Category.CAMERA && item.category != VideoItem.Category.DUAL) return false;
        if (filterSubtype == VideoItem.CameraSubtype.ALL) return true;
        VideoItem.CameraSubtype itemSubtype = item.cameraSubtype == null
                ? VideoItem.CameraSubtype.UNKNOWN
                : item.cameraSubtype;
        if (itemSubtype == VideoItem.CameraSubtype.UNKNOWN) {
            itemSubtype = VideoItem.CameraSubtype.BACK;
        }
        return itemSubtype == filterSubtype;
    }

    private void updateFaditorFilterChipUi() {
        if (chipFaditorAll != null) chipFaditorAll.setChecked(activeFaditorSubtype == VideoItem.FaditorSubtype.ALL);
        if (chipFaditorConverted != null)
            chipFaditorConverted.setChecked(activeFaditorSubtype == VideoItem.FaditorSubtype.CONVERTED);
        if (chipFaditorMerge != null) chipFaditorMerge.setChecked(activeFaditorSubtype == VideoItem.FaditorSubtype.MERGE);
    }

    private boolean matchesFaditorSubtype(@NonNull VideoItem item, @NonNull VideoItem.FaditorSubtype filterSubtype) {
        if (item.category != VideoItem.Category.FADITOR) return false;
        if (filterSubtype == VideoItem.FaditorSubtype.ALL) return true;
        VideoItem.FaditorSubtype itemSubtype = item.faditorSubtype == null
                ? VideoItem.FaditorSubtype.UNKNOWN
                : item.faditorSubtype;
        if (itemSubtype == VideoItem.FaditorSubtype.UNKNOWN) {
            itemSubtype = VideoItem.FaditorSubtype.OTHER;
        }
        return itemSubtype == filterSubtype;
    }

    private void updateShotFilterRowVisibility() {
        if (shotFilterRow == null) return;
        
        boolean shouldShow = activeFilter == VideoItem.Category.SHOT;
        boolean isCurrentlyVisible = shotFilterRow.getVisibility() == View.VISIBLE;
        
        if (shouldShow && !isCurrentlyVisible) {
            // Show with fluid amoeba-like animation
            shotFilterRow.setVisibility(View.VISIBLE);
            shotFilterRow.setAlpha(0f);
            shotFilterRow.setScaleY(0.3f);
            shotFilterRow.setTranslationY(-20f);
            
            shotFilterRow.animate()
                .alpha(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(400)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.5f))
                .start();
        } else if (!shouldShow && isCurrentlyVisible) {
            // Hide with smooth collapse animation - completes fully before hiding
            shotFilterRow.animate()
                .alpha(0f)
                .scaleY(0f)
                .translationY(-30f)
                .setDuration(250)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    shotFilterRow.setVisibility(View.GONE);
                    // Reset for next animation
                    shotFilterRow.setScaleY(0.3f);
                    shotFilterRow.setTranslationY(-20f);
                })
                .start();
        }
    }

    private void updateShotFilterChipUi() {
        if (chipShotAll != null) chipShotAll.setChecked(activeShotSubtype == VideoItem.ShotSubtype.ALL);
        if (chipShotBack != null) chipShotBack.setChecked(activeShotSubtype == VideoItem.ShotSubtype.BACK);
        if (chipShotSelfie != null) chipShotSelfie.setChecked(activeShotSubtype == VideoItem.ShotSubtype.SELFIE);
        if (chipShotFadrec != null) chipShotFadrec.setChecked(activeShotSubtype == VideoItem.ShotSubtype.FADREC);
    }

    private boolean matchesShotSubtype(@NonNull VideoItem item, @NonNull VideoItem.ShotSubtype filterSubtype) {
        if (item.category != VideoItem.Category.SHOT) return false;
        if (filterSubtype == VideoItem.ShotSubtype.ALL) return true;
        VideoItem.ShotSubtype itemSubtype = item.shotSubtype == null
                ? VideoItem.ShotSubtype.UNKNOWN
                : item.shotSubtype;
        if (itemSubtype == VideoItem.ShotSubtype.UNKNOWN) {
            itemSubtype = VideoItem.ShotSubtype.BACK; // legacy fallback
        }
        return itemSubtype == filterSubtype;
    }

    private void styleFilterChips() {
        applyChipIcon(chipFilterAll, R.drawable.ic_list);
        applyChipIcon(chipFilterCamera, R.drawable.ic_chip_videocam);
        applyChipIcon(chipFilterScreen, R.drawable.screen_recorder);
        applyChipIcon(chipFilterFaditor, R.drawable.ic_edit_cut);
        applyChipIcon(chipFilterStream, R.drawable.ic_wifi);
        applyChipIcon(chipFilterShot, R.drawable.ic_chip_add_a_photo);
        applyChipIcon(chipCameraAll, R.drawable.ic_list);
        applyChipIcon(chipCameraBack, R.drawable.ic_chip_video_camera_back);
        applyChipIcon(chipCameraFront, R.drawable.ic_chip_video_camera_front);
        applyChipIcon(chipCameraDual, R.drawable.ic_chip_switch_camera);
        applyChipIcon(chipFaditorAll, R.drawable.ic_list);
        applyChipIcon(chipFaditorConverted, R.drawable.ic_update);
        applyChipIcon(chipFaditorMerge, R.drawable.ic_split_file);
        applyChipIcon(chipShotAll, R.drawable.ic_list);
        applyChipIcon(chipShotBack, R.drawable.ic_chip_photo_camera);
        applyChipIcon(chipShotSelfie, R.drawable.ic_chip_photo_camera_front);
        applyChipIcon(chipShotFadrec, R.drawable.ic_chip_mobile_camera);
        styleFilterChip(chipFilterAll);
        styleFilterChip(chipFilterCamera);
        styleFilterChip(chipFilterScreen);
        styleFilterChip(chipFilterFaditor);
        styleFilterChip(chipFilterStream);
        styleFilterChip(chipFilterShot);
        styleFilterChip(chipCameraAll);
        styleFilterChip(chipCameraBack);
        styleFilterChip(chipCameraFront);
        styleFilterChip(chipCameraDual);
        styleFilterChip(chipFaditorAll);
        styleFilterChip(chipFaditorConverted);
        styleFilterChip(chipFaditorMerge);
        styleFilterChip(chipShotAll);
        styleFilterChip(chipShotBack);
        styleFilterChip(chipShotSelfie);
        styleFilterChip(chipShotFadrec);
    }

    private void applyChipIcon(@Nullable Chip chip, int drawableRes) {
        if (chip == null) return;
        chip.setChipIconResource(drawableRes);
        chip.setChipIconVisible(true);
        chip.setIconStartPadding(dpToPx(2));
        chip.setChipIconSize(dpToPx(16));
    }

    private void styleFilterChip(@Nullable Chip chip) {
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

    private boolean isDarkColor(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255;
        return luminance < 0.55;
    }

    private float dpToPx(int dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    private int getCategoryCount(@NonNull VideoItem.Category category) {
        if (category == VideoItem.Category.ALL) return allLoadedItems.size();
        int count = 0;
        for (VideoItem item : allLoadedItems) {
            if (category == VideoItem.Category.CAMERA) {
                if (item.category == VideoItem.Category.CAMERA || item.category == VideoItem.Category.DUAL) {
                    count++;
                }
            } else if (item.category == category) {
                count++;
            }
        }
        return count;
    }

    private void updateFilterChipLabels() {
        int all = getCategoryCount(VideoItem.Category.ALL);
        int camera = getCategoryCount(VideoItem.Category.CAMERA);
        int screen = getCategoryCount(VideoItem.Category.SCREEN);
        int faditor = getCategoryCount(VideoItem.Category.FADITOR);
        int stream = getCategoryCount(VideoItem.Category.STREAM);
        int shot = getCategoryCount(VideoItem.Category.SHOT);

        setChipLabelWithCount(chipFilterAll, R.string.records_filter_all, all);
        setChipLabelWithCount(chipFilterCamera, R.string.records_filter_camera, camera);
        setChipLabelWithCount(chipFilterScreen, R.string.records_filter_screen, screen);
        setChipLabelWithCount(chipFilterFaditor, R.string.records_filter_faditor, faditor);
        setChipLabelWithCount(chipFilterStream, R.string.records_filter_stream, stream);
        setChipLabelWithCount(chipFilterShot, R.string.records_filter_shot, shot);

        reorderFilterChipsByCount(camera, screen, faditor, stream, shot);
    }

    private void updateCameraFilterChipLabels() {
        int all = getCameraSubtypeCount(VideoItem.CameraSubtype.ALL);
        int back = getCameraSubtypeCount(VideoItem.CameraSubtype.BACK);
        int front = getCameraSubtypeCount(VideoItem.CameraSubtype.FRONT);
        int dual = getCameraSubtypeCount(VideoItem.CameraSubtype.DUAL);

        setChipLabelWithCount(chipCameraAll, R.string.records_filter_camera_all, all);
        setChipLabelWithCount(chipCameraBack, R.string.records_filter_camera_back, back);
        setChipLabelWithCount(chipCameraFront, R.string.records_filter_camera_front, front);
        setChipLabelWithCount(chipCameraDual, R.string.records_filter_camera_dual, dual);
    }

    private void updateShotFilterChipLabels() {
        int all = getShotSubtypeCount(VideoItem.ShotSubtype.ALL);
        int back = getShotSubtypeCount(VideoItem.ShotSubtype.BACK);
        int selfie = getShotSubtypeCount(VideoItem.ShotSubtype.SELFIE);
        int fadrec = getShotSubtypeCount(VideoItem.ShotSubtype.FADREC);

        setChipLabelWithCount(chipShotAll, R.string.records_filter_shot_all, all);
        setChipLabelWithCount(chipShotBack, R.string.records_filter_shot_back, back);
        setChipLabelWithCount(chipShotSelfie, R.string.records_filter_shot_selfie, selfie);
        setChipLabelWithCount(chipShotFadrec, R.string.records_filter_shot_fadrec, fadrec);
    }

    private void updateFaditorFilterChipLabels() {
        int all = getFaditorSubtypeCount(VideoItem.FaditorSubtype.ALL);
        int converted = getFaditorSubtypeCount(VideoItem.FaditorSubtype.CONVERTED);
        int merge = getFaditorSubtypeCount(VideoItem.FaditorSubtype.MERGE);
        setChipLabelWithCount(chipFaditorAll, R.string.records_filter_faditor_all, all);
        setChipLabelWithCount(chipFaditorConverted, R.string.records_filter_faditor_converted, converted);
        setChipLabelWithCount(chipFaditorMerge, R.string.records_filter_faditor_merge, merge);
    }

    private void setChipLabelWithCount(@Nullable Chip chip, int baseLabelRes, int count) {
        if (chip == null) return;
        chip.setText(getString(baseLabelRes) + " (" + count + ")");
    }

    private int getShotSubtypeCount(@NonNull VideoItem.ShotSubtype subtype) {
        int count = 0;
        for (VideoItem item : allLoadedItems) {
            if (item.category != VideoItem.Category.SHOT) continue;
            if (matchesShotSubtype(item, subtype)) {
                count++;
            }
        }
        return count;
    }

    private int getCameraSubtypeCount(@NonNull VideoItem.CameraSubtype subtype) {
        int count = 0;
        for (VideoItem item : allLoadedItems) {
            if (item.category != VideoItem.Category.CAMERA && item.category != VideoItem.Category.DUAL) continue;
            if (matchesCameraSubtype(item, subtype)) {
                count++;
            }
        }
        return count;
    }

    private int getFaditorSubtypeCount(@NonNull VideoItem.FaditorSubtype subtype) {
        int count = 0;
        for (VideoItem item : allLoadedItems) {
            if (item.category != VideoItem.Category.FADITOR) continue;
            if (matchesFaditorSubtype(item, subtype)) {
                count++;
            }
        }
        return count;
    }

    private void reorderFilterChipsByCount(int camera, int screen, int faditor, int stream, int shot) {
        if (chipGroupRecordsFilter == null || chipFilterAll == null) return;
        List<ChipOrderItem> ordered = new ArrayList<>();
        ordered.add(new ChipOrderItem(chipFilterCamera, camera));
        ordered.add(new ChipOrderItem(chipFilterScreen, screen));
        ordered.add(new ChipOrderItem(chipFilterFaditor, faditor));
        ordered.add(new ChipOrderItem(chipFilterStream, stream));
        ordered.add(new ChipOrderItem(chipFilterShot, shot));
        Collections.sort(ordered, (a, b) -> Integer.compare(b.count, a.count));

        chipGroupRecordsFilter.removeAllViews();
        chipGroupRecordsFilter.addView(chipFilterAll);
        for (ChipOrderItem item : ordered) {
            if (item.chip != null) {
                chipGroupRecordsFilter.addView(item.chip);
            }
        }
        chipGroupRecordsFilter.check(getChipIdForActiveFilter(activeFilter));
    }

    private int getChipIdForActiveFilter(@NonNull VideoItem.Category filter) {
        switch (filter) {
            case CAMERA:
                return R.id.chip_filter_camera;
            case SCREEN:
                return R.id.chip_filter_screen;
            case FADITOR:
                return R.id.chip_filter_faditor;
            case STREAM:
                return R.id.chip_filter_stream;
            case SHOT:
                return R.id.chip_filter_shot;
            case UNKNOWN:
            case ALL:
            default:
                return R.id.chip_filter_all;
        }
    }

    private static final class ChipOrderItem {
        final Chip chip;
        final int count;

        ChipOrderItem(Chip chip, int count) {
            this.chip = chip;
            this.count = count;
        }
    }

    private List<VideoItem> normalizeVideoCategories(@NonNull List<VideoItem> input) {
        List<VideoItem> normalized = new ArrayList<>(input.size());
        for (VideoItem item : input) {
            if (item == null || item.uri == null) {
                continue;
            }
            VideoItem.Category category = item.category;
            if (category == null || category == VideoItem.Category.UNKNOWN) {
                category = inferCategoryForVideoItem(item);
            }
            VideoItem.ShotSubtype shotSubtype = item.shotSubtype;
            if (category == VideoItem.Category.SHOT) {
                shotSubtype = resolveShotSubtypeFromItem(item);
            } else {
                shotSubtype = VideoItem.ShotSubtype.UNKNOWN;
            }
            VideoItem.CameraSubtype cameraSubtype = item.cameraSubtype;
            if (category == VideoItem.Category.CAMERA || category == VideoItem.Category.DUAL) {
                cameraSubtype = resolveCameraSubtypeFromItem(item);
                category = VideoItem.Category.CAMERA;
            } else {
                cameraSubtype = VideoItem.CameraSubtype.UNKNOWN;
            }
            VideoItem.FaditorSubtype faditorSubtype = item.faditorSubtype;
            if (category == VideoItem.Category.FADITOR) {
                faditorSubtype = resolveFaditorSubtypeFromItem(item);
            } else {
                faditorSubtype = VideoItem.FaditorSubtype.UNKNOWN;
            }
            VideoItem copy = new VideoItem(
                    item.uri,
                    item.displayName,
                    item.size,
                    item.lastModified,
                    category,
                    resolveMediaTypeFromItem(item),
                    shotSubtype,
                    cameraSubtype,
                    faditorSubtype);
            copy.isNew = item.isNew;
            copy.isProcessingUri = item.isProcessingUri;
            copy.isSkeleton = item.isSkeleton;
            normalized.add(copy);
        }
        return normalized;
    }

    @NonNull
    private VideoItem.MediaType resolveMediaTypeFromItem(@NonNull VideoItem item) {
        VideoItem.MediaType inferred = inferMediaTypeFromName(item.displayName);
        if (inferred == null && item.uri != null) {
            inferred = inferMediaTypeFromName(item.uri.toString());
        }
        return inferred != null ? inferred : (item.mediaType != null ? item.mediaType : VideoItem.MediaType.VIDEO);
    }

    private VideoItem.Category inferCategoryForVideoItem(@NonNull VideoItem item) {
        String uri = item.uri.toString();
        if (uri.contains("/" + Constants.RECORDING_SUBDIR_CAMERA + "/")
                || uri.contains("%2F" + Constants.RECORDING_SUBDIR_CAMERA + "%2F")) {
            return VideoItem.Category.CAMERA;
        }
        if (uri.contains("/" + Constants.RECORDING_SUBDIR_DUAL + "/")
                || uri.contains("%2F" + Constants.RECORDING_SUBDIR_DUAL + "%2F")) {
            return VideoItem.Category.CAMERA;
        }
        if (uri.contains("/" + Constants.RECORDING_SUBDIR_SCREEN + "/")
                || uri.contains("%2F" + Constants.RECORDING_SUBDIR_SCREEN + "%2F")) {
            return VideoItem.Category.SCREEN;
        }
        if (uri.contains("/" + Constants.RECORDING_SUBDIR_FADITOR + "/")
                || uri.contains("%2F" + Constants.RECORDING_SUBDIR_FADITOR + "%2F")) {
            return VideoItem.Category.FADITOR;
        }
        if (uri.contains("/" + Constants.RECORDING_SUBDIR_STREAM + "/")
                || uri.contains("%2F" + Constants.RECORDING_SUBDIR_STREAM + "%2F")) {
            return VideoItem.Category.STREAM;
        }
        if (uri.contains("/" + Constants.RECORDING_SUBDIR_SHOT + "/")
                || uri.contains("%2F" + Constants.RECORDING_SUBDIR_SHOT + "%2F")) {
            return VideoItem.Category.SHOT;
        }
        return inferCategoryFromLegacyName(item.displayName);
    }

    private void updateFilterHelperText() {
        if (filterHelperText == null) return;
        if (activeFilter == VideoItem.Category.ALL) {
            filterHelperText.setVisibility(View.GONE);
            filterHelperText.setText("");
            return;
        }
        String helper;
        switch (activeFilter) {
            case CAMERA:
                switch (activeCameraSubtype) {
                    case BACK:
                        helper = getString(R.string.records_filter_helper_camera_back);
                        break;
                    case FRONT:
                        helper = getString(R.string.records_filter_helper_camera_front);
                        break;
                    case DUAL:
                        helper = getString(R.string.records_filter_helper_camera_dual);
                        break;
                    case UNKNOWN:
                    case ALL:
                    default:
                        helper = getString(R.string.records_filter_helper_camera_all);
                        break;
                }
                break;
            case SCREEN:
                helper = getString(R.string.records_filter_helper_screen);
                break;
            case FADITOR:
                switch (activeFaditorSubtype) {
                    case CONVERTED:
                        helper = getString(R.string.records_filter_helper_faditor_converted);
                        break;
                    case MERGE:
                        helper = getString(R.string.records_filter_helper_faditor_merge);
                        break;
                    case OTHER:
                    case UNKNOWN:
                    case ALL:
                    default:
                        helper = getString(R.string.records_filter_helper_faditor_all);
                        break;
                }
                break;
            case STREAM:
                helper = getString(R.string.records_filter_helper_stream);
                break;
            case SHOT:
                switch (activeShotSubtype) {
                    case BACK:
                        helper = getString(R.string.records_filter_helper_shot_back);
                        break;
                    case SELFIE:
                        helper = getString(R.string.records_filter_helper_shot_selfie);
                        break;
                    case FADREC:
                        helper = getString(R.string.records_filter_helper_shot_fadrec);
                        break;
                    case UNKNOWN:
                    case ALL:
                    default:
                        helper = getString(R.string.records_filter_helper_shot_all);
                        break;
                }
                break;
            case UNKNOWN:
            case ALL:
            default:
                filterHelperText.setVisibility(View.GONE);
                filterHelperText.setText("");
                return;
        }
        helper = helper + " • "
                + getString(R.string.records_location_format, getStorageLocationLabel(), getCategoryFolderLabel(activeFilter));
        filterHelperText.setText(helper);
        filterHelperText.setVisibility(View.VISIBLE);
    }

    private String getStorageLocationLabel() {
        if (getContext() == null || sharedPreferencesManager == null) {
            return "Internal/" + Constants.RECORDING_DIRECTORY;
        }
        String customUri = sharedPreferencesManager.getCustomStorageUri();
        if (customUri == null || customUri.trim().isEmpty()) {
            return "Internal/" + Constants.RECORDING_DIRECTORY;
        }
        try {
            Uri uri = Uri.parse(customUri);
            DocumentFile dir = DocumentFile.fromTreeUri(requireContext(), uri);
            if (dir != null && dir.getName() != null && !dir.getName().trim().isEmpty()) {
                return dir.getName();
            }
        } catch (Exception ignored) {
        }
        return "Custom";
    }

    private String getCategoryFolderLabel(@NonNull VideoItem.Category category) {
        switch (category) {
            case CAMERA:
                if (activeCameraSubtype == VideoItem.CameraSubtype.BACK) {
                    return Constants.RECORDING_SUBDIR_CAMERA + "/" + Constants.RECORDING_SUBDIR_CAMERA_BACK;
                }
                if (activeCameraSubtype == VideoItem.CameraSubtype.FRONT) {
                    return Constants.RECORDING_SUBDIR_CAMERA + "/" + Constants.RECORDING_SUBDIR_CAMERA_FRONT;
                }
                if (activeCameraSubtype == VideoItem.CameraSubtype.DUAL) {
                    return Constants.RECORDING_SUBDIR_CAMERA + "/" + Constants.RECORDING_SUBDIR_CAMERA_DUAL;
                }
                return Constants.RECORDING_SUBDIR_CAMERA;
            case SCREEN:
                return Constants.RECORDING_SUBDIR_SCREEN;
            case FADITOR:
                if (activeFaditorSubtype == VideoItem.FaditorSubtype.CONVERTED) {
                    return Constants.RECORDING_SUBDIR_FADITOR + "/" + Constants.RECORDING_SUBDIR_FADITOR_CONVERTED;
                }
                if (activeFaditorSubtype == VideoItem.FaditorSubtype.MERGE) {
                    return Constants.RECORDING_SUBDIR_FADITOR + "/" + Constants.RECORDING_SUBDIR_FADITOR_MERGE;
                }
                return Constants.RECORDING_SUBDIR_FADITOR;
            case STREAM:
                return Constants.RECORDING_SUBDIR_STREAM;
            case SHOT:
                if (activeShotSubtype == VideoItem.ShotSubtype.SELFIE) {
                    return Constants.RECORDING_SUBDIR_SHOT + "/" + Constants.RECORDING_SUBDIR_SHOT_SELFIE;
                }
                if (activeShotSubtype == VideoItem.ShotSubtype.FADREC) {
                    return Constants.RECORDING_SUBDIR_SHOT + "/" + Constants.RECORDING_SUBDIR_SHOT_FADREC;
                }
                if (activeShotSubtype == VideoItem.ShotSubtype.BACK) {
                    return Constants.RECORDING_SUBDIR_SHOT + "/" + Constants.RECORDING_SUBDIR_SHOT_BACK;
                }
                return Constants.RECORDING_SUBDIR_SHOT;
            case ALL:
            case UNKNOWN:
            default:
                return Constants.RECORDING_DIRECTORY;
        }
    }

    private void updateSelectionActionRow() {
        if (selectionActionsRow != null) selectionActionsRow.setVisibility(View.GONE);
    }

    private void toggleSelectAllVisibleItems() {
        if (!isInSelectionMode) return;
        boolean allSelected = !videoItems.isEmpty() && selectedUris.size() == videoItems.size();
        selectedUris.clear();
        if (!allSelected) {
            for (VideoItem item : videoItems) {
                if (item != null && item.uri != null) {
                    selectedUris.add(item.uri);
                }
            }
        }
        if (recordsAdapter != null) {
            recordsAdapter.setSelectionModeActive(true, selectedUris);
        }
        updateUiForSelectionMode();
    }

    private void showBatchActionsSheet() {
        if (!isAdded() || getContext() == null || getActivity() == null) return;
        if (!(getActivity() instanceof FragmentActivity)) return;

        int selectedCount = selectedUris.size();
        String suffix = selectedCount > 0 ? " (" + selectedCount + ")" : "";
        boolean hasSelection = selectedCount > 0;

        ArrayList<OptionItem> items = new ArrayList<>();
        boolean allSelected = !videoItems.isEmpty() && selectedUris.size() == videoItems.size();
        items.add(new OptionItem(
                "batch_select_all",
                getString(allSelected ? R.string.records_batch_deselect_all : R.string.records_batch_select_all) + suffix,
                getString(R.string.records_batch_select_all_desc),
                null, null, null, null, null, "select_all"));
        items.add(new OptionItem(
                "batch_save_gallery",
                getString(R.string.records_batch_save) + suffix,
                getString(R.string.records_batch_save_desc),
                null, null, R.drawable.ic_arrow_right, null, null, "download"));
        items.add(new OptionItem(
                "batch_faditor_actions",
                getString(R.string.records_batch_faditor) + suffix,
                getString(R.string.records_batch_faditor_desc),
                null, null, R.drawable.ic_arrow_right, null, null, "auto_awesome"));
        items.add(new OptionItem(
                "batch_delete",
                getString(R.string.records_batch_delete) + suffix,
                getString(R.string.records_batch_delete_desc),
                null, null, null, null, null, "delete"));

        FragmentActivity activity = (FragmentActivity) getActivity();
        String resultKey = "records_batch_actions";
        activity.getSupportFragmentManager().setFragmentResultListener(resultKey, activity, (requestKey, bundle) -> {
            if (bundle == null) return;
            String id = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (id == null) return;
            switch (id) {
                case "batch_select_all":
                    toggleSelectAllVisibleItems();
                    break;
                case "batch_save_gallery":
                    if (selectedUris.isEmpty()) {
                        Toast.makeText(requireContext(), getString(R.string.records_batch_select_items_first), Toast.LENGTH_SHORT).show();
                    } else {
                        showBatchSaveOptionsSheet();
                    }
                    break;
                case "batch_faditor_actions":
                    if (selectedUris.isEmpty()) {
                        Toast.makeText(requireContext(), getString(R.string.records_batch_select_items_first), Toast.LENGTH_SHORT).show();
                    } else {
                        showBatchFaditorOptionsSheet();
                    }
                    break;
                case "batch_delete":
                    if (selectedUris.isEmpty()) {
                        Toast.makeText(requireContext(), getString(R.string.records_batch_select_items_first), Toast.LENGTH_SHORT).show();
                    } else {
                        confirmDeleteSelected();
                    }
                    break;
                default:
                    break;
            }
        });

        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstanceGradient(
                getString(R.string.records_batch_actions_title),
                items,
                null,
                resultKey,
                null,
                true
        );
        Bundle args = sheet.getArguments();
        if (args != null) args.putBoolean(PickerBottomSheetFragment.ARG_HIDE_CHECK, true);
        sheet.show(activity.getSupportFragmentManager(), "records_batch_actions_sheet");
    }

    private void showBatchSaveOptionsSheet() {
        if (!isAdded() || getContext() == null || getActivity() == null) return;
        if (!(getActivity() instanceof FragmentActivity)) return;

        String destinationLabel = getGalleryDestinationLabel();
        ArrayList<OptionItem> items = new ArrayList<>();
        items.add(new OptionItem(
                "save_copy",
                getString(R.string.video_menu_save_copy),
                getString(R.string.records_batch_save_copy_helper, destinationLabel),
                null, null, null, null, null, "content_copy"));
        items.add(new OptionItem(
                "save_move",
                getString(R.string.video_menu_save_move),
                getString(R.string.records_batch_save_move_helper, destinationLabel),
                null, null, null, null, null, "drive_file_move"));
        items.add(new OptionItem(
                "save_export_custom_location",
                getString(R.string.records_batch_save_custom_location),
                getString(R.string.records_batch_save_custom_location_desc),
                null, null, null, null, null, "folder_open"));

        FragmentActivity activity = (FragmentActivity) getActivity();
        String resultKey = "records_batch_save_options";
        activity.getSupportFragmentManager().setFragmentResultListener(resultKey, activity, (requestKey, bundle) -> {
            if (bundle == null) return;
            String id = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (id == null) return;
            if ("save_copy".equals(id)) {
                queueBatchSaveToGallery(false);
            } else if ("save_move".equals(id)) {
                queueBatchSaveToGallery(true);
            } else if ("save_export_custom_location".equals(id)) {
                if (customExportTreePickerLauncher != null) {
                    pendingCustomExportUris = new ArrayList<>(selectedUris);
                    customExportTreePickerLauncher.launch(null);
                }
            }
        });

        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstanceGradient(
                getString(R.string.video_menu_save_copy_or_move_title),
                items,
                "save_copy",
                resultKey,
                null,
                true
        );
        Bundle args = sheet.getArguments();
        if (args != null) args.putBoolean(PickerBottomSheetFragment.ARG_HIDE_CHECK, true);
        sheet.show(activity.getSupportFragmentManager(), "records_batch_save_sheet");
    }

    private void showBatchFaditorOptionsSheet() {
        if (!isAdded() || getContext() == null || getActivity() == null) return;
        if (!(getActivity() instanceof FragmentActivity)) return;

        ArrayList<OptionItem> items = new ArrayList<>();
        items.add(new OptionItem(
                "faditor_export_standard_mp4",
                getString(R.string.records_batch_faditor_export_standard),
                getString(R.string.records_batch_faditor_export_standard_desc),
                null, null, null, null, null, "movie_edit"));
        items.add(new OptionItem(
                "faditor_merge_selected",
                getString(R.string.records_batch_faditor_merge),
                getString(R.string.records_batch_faditor_merge_desc),
                null, null, null, null, null, "merge_type"));

        FragmentActivity activity = (FragmentActivity) getActivity();
        String resultKey = "records_batch_faditor_options";
        activity.getSupportFragmentManager().setFragmentResultListener(resultKey, activity, (requestKey, bundle) -> {
            if (bundle == null) return;
            String id = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if ("faditor_export_standard_mp4".equals(id)) {
                startBatchMediaAction(Constants.INTENT_ACTION_BATCH_EXPORT_STANDARD_MP4);
            } else if ("faditor_merge_selected".equals(id)) {
                startBatchMediaAction(Constants.INTENT_ACTION_BATCH_MERGE_VIDEOS);
            }
        });

        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstanceGradient(
                getString(R.string.records_batch_faditor_sheet_title),
                items,
                null,
                resultKey,
                null,
                true
        );
        Bundle args = sheet.getArguments();
        if (args != null) args.putBoolean(PickerBottomSheetFragment.ARG_HIDE_CHECK, true);
        sheet.show(activity.getSupportFragmentManager(), "records_batch_faditor_sheet");
    }

    private void startBatchMediaAction(@NonNull String action) {
        if (selectedUris.isEmpty() || getContext() == null) {
            Toast.makeText(requireContext(), getString(R.string.records_batch_select_items_first), Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(requireContext(), com.fadcam.service.BatchMediaActionService.class);
        intent.setAction(action);
        ArrayList<String> uriStrings = new ArrayList<>();
        for (Uri uri : selectedUris) {
            uriStrings.add(uri.toString());
        }
        intent.putStringArrayListExtra(Constants.EXTRA_BATCH_INPUT_URIS, uriStrings);
        intent.putExtra(Constants.EXTRA_BATCH_OUTPUT_MODE, Constants.BATCH_OUTPUT_MODE_DEFAULT_FADITOR);
        ContextCompat.startForegroundService(requireContext(), intent);
        Toast.makeText(requireContext(), getString(R.string.records_batch_faditor_queued, uriStrings.size()), Toast.LENGTH_SHORT).show();
    }

    @NonNull
    private String getGalleryDestinationLabel() {
        return "Downloads/" + Constants.RECORDING_DIRECTORY;
    }

    private void queueBatchSaveToGallery(boolean moveFiles) {
        if (selectedUris.isEmpty() || getContext() == null) {
            Toast.makeText(requireContext(), getString(R.string.records_batch_select_items_first), Toast.LENGTH_SHORT).show();
            return;
        }
        int queued = 0;
        for (Uri uri : new ArrayList<>(selectedUris)) {
            VideoItem item = findVideoItemByUri(allLoadedItems, uri);
            String name = (item != null && item.displayName != null) ? item.displayName : "video.mp4";
            if (moveFiles) {
                com.fadcam.service.FileOperationService.startMoveToGallery(requireContext(), uri, name, name);
            } else {
                com.fadcam.service.FileOperationService.startCopyToGallery(requireContext(), uri, name, name);
            }
            queued++;
        }
        Toast.makeText(requireContext(),
                getString(R.string.records_batch_save_queued, queued),
                Toast.LENGTH_SHORT).show();

        if (moveFiles) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                // Delta scan will detect moved files automatically
                loadRecordsList();
            }, 1800);
        }
    }

    private void exportSelectedToCustomTree(@NonNull Uri treeUri) {
        if (pendingCustomExportUris == null || pendingCustomExportUris.isEmpty()) {
            return;
        }
        if (getContext() == null) return;
        final List<Uri> urisToExport = new ArrayList<>(pendingCustomExportUris);
        pendingCustomExportUris.clear();
        Toast.makeText(requireContext(), getString(R.string.records_batch_custom_export_started, urisToExport.size()),
                Toast.LENGTH_SHORT).show();
        executorService.submit(() -> {
            int success = 0;
            int failed = 0;
            DocumentFile targetDir = DocumentFile.fromTreeUri(requireContext(), treeUri);
            if (targetDir == null || !targetDir.isDirectory() || !targetDir.canWrite()) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> Toast.makeText(requireContext(),
                            getString(R.string.records_batch_custom_export_invalid_folder), Toast.LENGTH_LONG).show());
                }
                return;
            }
            for (Uri uri : urisToExport) {
                try {
                    VideoItem item = findVideoItemByUri(allLoadedItems, uri);
                    String name = item != null && item.displayName != null ? item.displayName : "video.mp4";
                    DocumentFile out = createUniqueSafFile(targetDir, name);
                    if (out == null) {
                        failed++;
                        continue;
                    }
                    try (java.io.InputStream in = requireContext().getContentResolver().openInputStream(uri);
                         java.io.OutputStream os = requireContext().getContentResolver().openOutputStream(out.getUri(), "w")) {
                        if (in == null || os == null) {
                            failed++;
                            continue;
                        }
                        byte[] buffer = new byte[16 * 1024];
                        int len;
                        while ((len = in.read(buffer)) > 0) {
                            os.write(buffer, 0, len);
                        }
                        os.flush();
                        success++;
                    }
                } catch (Exception e) {
                    failed++;
                }
            }
            final int successFinal = success;
            final int failedFinal = failed;
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> Toast.makeText(requireContext(),
                        getString(R.string.records_batch_custom_export_done, successFinal, failedFinal),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    @Nullable
    private DocumentFile createUniqueSafFile(@NonNull DocumentFile targetDir, @NonNull String originalName) {
        String base = originalName;
        String ext = "";
        int dot = originalName.lastIndexOf('.');
        if (dot > 0) {
            base = originalName.substring(0, dot);
            ext = originalName.substring(dot);
        }
        String candidate = originalName;
        int suffix = 1;
        while (targetDir.findFile(candidate) != null) {
            candidate = base + " (" + suffix + ")" + ext;
            suffix++;
        }
        return targetDir.createFile("video/mp4", candidate);
    }
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
            List<String> trashedUris = new ArrayList<>();

            for (Uri uri : itemsToDeleteUris) {
                VideoItem itemToTrash = findVideoItemByUri(allCurrentItems, uri);
                if (itemToTrash != null) {
                    if (moveToTrashVideoItem(itemToTrash)) {
                        successCount++;
                        trashedUris.add(uri.toString());
                    } else {
                        failCount++;
                    }
                } else {
                    Log.w(TAG, "Could not find VideoItem for URI: " + uri + " to move to trash.");
                    failCount++;
                }
            }

            // Remove trashed items from the persistent index in bulk
            if (!trashedUris.isEmpty()) {
                try {
                    com.fadcam.data.VideoIndexRepository.getInstance(requireContext())
                            .removeFromIndex(trashedUris);
                    Log.d(TAG, "Removed " + trashedUris.size() + " trashed items from index");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to remove trashed items from index: " + e.getMessage());
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
        // Check if videoItems has content, if not show toast
        if (videoItems.isEmpty()) {
            if (getContext() != null)
                Toast.makeText(requireContext(), "No videos to move to trash.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Create a copy of videoItems to avoid concurrent modification issues
        List<VideoItem> itemsToTrash = new ArrayList<>(videoItems);
        Log.i(TAG, "Moving all " + itemsToTrash.size() + " videos to trash...");

        // Show progress dialog for deleting all videos with count information
        onMoveToTrashStarted("all " + itemsToTrash.size() + " videos");

        if (executorService == null || executorService.isShutdown()) {
            executorService = Executors.newSingleThreadExecutor();
        }

        executorService.submit(() -> {
            int successCount = 0;
            int failCount = 0;
            List<String> trashedUris = new ArrayList<>();
            for (VideoItem item : itemsToTrash) {
                if (item != null && item.uri != null) {
                    if (moveToTrashVideoItem(item)) { // Pass the whole VideoItem
                        successCount++;
                        trashedUris.add(item.uri.toString());
                    } else {
                        failCount++;
                    }
                } else {
                    Log.w(TAG, "Encountered a null item or item with null URI in deleteAllVideos list.");
                    failCount++;
                }
            }

            // Remove all trashed items from the persistent index
            if (!trashedUris.isEmpty()) {
                try {
                    com.fadcam.data.VideoIndexRepository.getInstance(requireContext())
                            .removeFromIndex(trashedUris);
                    Log.d(TAG, "Removed " + trashedUris.size() + " trashed items from index (deleteAll)");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to remove trashed items from index (deleteAll): " + e.getMessage());
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
        // Use unified overlay: open a sidebar-style fragment to host row-based options.
        RecordsSidebarFragment sidebar = RecordsSidebarFragment.newInstance(mapSortToId(currentSortOption), currentGridSpan);
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
                    // Legacy: cycle to next span
                    applyGridSpan(currentGridSpan >= 5 ? 1 : currentGridSpan + 1);
                    break;
                case "set_view_mode":
                    int span = bundle.getInt("grid_span", currentGridSpan);
                    if (span != currentGridSpan) {
                        applyGridSpan(span);
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
    }

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

    // -----
    private BroadcastReceiver segmentCompleteReceiver;
    private boolean isSegmentReceiverRegistered = false;

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
    // -----

    private SearchView searchView;
    private boolean isSearchViewActive = false;

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

    /**
     * Checks if the fragment is currently in selection mode
     * 
     * @return true if in selection mode, false otherwise
     */
    private boolean isInSelectionMode() {
        return isInSelectionMode;
    }

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

    /**
     * Public method to refresh the records list
     * Can be called from other fragments when they need to update this fragment
     */
    public void refreshList() {
        if (isAdded()) {
            // Invalidate persistent index to force full re-scan
            com.fadcam.data.VideoIndexRepository.getInstance(requireContext()).invalidateIndex();
            if (recordsAdapter != null) {
                recordsAdapter.clearCaches();
            }
            Log.i(TAG, "refreshList: Invalidated index, reloading records list.");
            loadRecordsList();
        }
    }

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

        // Shutdown delta executor
        if (deltaExecutor != null && !deltaExecutor.isShutdown()) {
            deltaExecutor.shutdownNow();
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
            GridLayoutManager grid = new GridLayoutManager(getContext(), currentGridSpan);
            grid.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
                @Override
                public int getSpanSize(int position) {
                    if (recordsAdapter != null && recordsAdapter.getItemViewType(position) == 0) {
                        return currentGridSpan; // Month headers span full width
                    }
                    return 1;
                }
            });
            recyclerView.setLayoutManager(grid);
            Log.d(TAG, "LayoutManager set to GridLayout with " + currentGridSpan + " columns");
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
        updateHeaderStats();

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

        // While data is loading, don't flash empty state — keep recyclerView visible and
        // empty state hidden. The background thread will deliver data shortly.
        if (isEmpty && isLoading) {
            Log.d(TAG, "LOG_UI_VISIBILITY: Adapter empty but still loading — suppressing empty state flash");
            if (recyclerView != null) recyclerView.setVisibility(View.VISIBLE);
            if (emptyStateContainer != null) emptyStateContainer.setVisibility(View.GONE);
            return;
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

    private void updateHeaderStats() {
        if (getContext() == null) return;
        List<VideoItem> source = allLoadedItems == null || allLoadedItems.isEmpty() ? videoItems : allLoadedItems;
        int photos = 0;
        int videos = 0;
        long totalBytes = 0L;
        if (source != null) {
            for (VideoItem item : source) {
                if (item == null) continue;
                if (item.mediaType == VideoItem.MediaType.IMAGE) {
                    photos++;
                } else {
                    videos++;
                }
                totalBytes += Math.max(0L, item.size);
            }
        }
        if (statsPhotosText != null) statsPhotosText.setText(String.valueOf(photos));
        if (statsVideosText != null) statsVideosText.setText(String.valueOf(videos));
        if (statsSizeText != null) statsSizeText.setText(Formatter.formatShortFileSize(requireContext(), totalBytes));
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

    @Override
    public void onDeleteVideo(VideoItem videoItem) {
        // Delegate to the existing move-to-trash logic to avoid duplication
        onMoveToTrashRequested(videoItem);
    }

    @Override
    public void onCustomExportRequested(VideoItem videoItem) {
        if (!isAdded() || videoItem == null || videoItem.uri == null) return;
        if (customExportTreePickerLauncher == null) return;
        pendingCustomExportUris = new ArrayList<>();
        pendingCustomExportUris.add(videoItem.uri);
        customExportTreePickerLauncher.launch(null);
    }

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
        if (fastScroller != null) {
            fastScroller.detach();
        }
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
     * Loads the list of videos using the persistent Room DB index.
     * <p>
     * Flow:
     * <ol>
     *   <li>If DB has data → return instantly (< 50 ms) → delta-scan in background</li>
     *   <li>If DB empty (first launch / after wipe) → full scan → bulk insert → return</li>
     *   <li>Background enrichment resolves durations without blocking the UI</li>
     * </ol>
     */
    @SuppressLint("NotifyDataSetChanged")
    private void requestRealtimeRefresh(@NonNull String reason) {
        if (!isAdded()) return;
        loadRecordsList(true);
    }

    private void drainPendingRealtimeRefresh() {
        if (pendingForcedRealtimeReload && !isLoading) {
            pendingForcedRealtimeReload = false;
            loadRecordsList(true);
        }
    }

    private void loadRecordsList() {
        loadRecordsList(false);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void loadRecordsList(boolean forceReload) {
        Log.d(TAG, "loadRecordsList: start, storageMode=" + sharedPreferencesManager.getStorageMode()
                + ", activeFilter=" + activeFilter + ", forceReload=" + forceReload);

        // Guard against duplicate loads
        if (isLoading) {
            if (forceReload) {
                pendingForcedRealtimeReload = true;
            }
            Log.d(TAG, "loadRecordsList: already loading, skipping duplicate request");
            return;
        }

        // Skip reload if we already have data and this is not the initial load
        if (!forceReload && !videoItems.isEmpty() && !isInitialLoad) {
            Log.d(TAG, "loadRecordsList: already have " + videoItems.size() + " items, skipping");
            isLoading = false;
            updateUiVisibility();
            drainPendingRealtimeRefresh();
            return;
        }

        isLoading = true;

        final com.fadcam.data.VideoIndexRepository repository =
                com.fadcam.data.VideoIndexRepository.getInstance(requireContext());
        final int indexedCount = repository.getIndexedCount();

        // ═══════════════════════════════════════════════════════════════════════
        //  PRODUCTION FAST PATH: DB has indexed data (~26ms to read).
        //  NO skeleton — the DB read is so fast that showing skeleton for 26ms
        //  then replacing it costs MORE (800ms+ Davey) than the actual data load.
        //  Run DB read on background thread (Room requirement), deliver data
        //  directly to adapter. RecyclerView stays invisible for ~26ms = imperceptible.
        // ═══════════════════════════════════════════════════════════════════════
        if (indexedCount > 0) {
            Log.d(TAG, "loadRecordsList: DB fast path — " + indexedCount + " indexed, skipping skeleton");

            // Ensure RecyclerView is visible but adapter is empty (no skeleton flash)
            if (recyclerView != null) recyclerView.setVisibility(View.VISIBLE);
            if (emptyStateContainer != null) emptyStateContainer.setVisibility(View.GONE);
            if (loadingIndicator != null) loadingIndicator.setVisibility(View.GONE);

            if (executorService == null || executorService.isShutdown()) {
                executorService = Executors.newSingleThreadExecutor();
            }

            executorService.submit(() -> {
                try {
                    long loadStart = System.currentTimeMillis();

                    // DB read on background thread (Room requires this)
                    List<VideoItem> items = repository.getVideos(sharedPreferencesManager);
                    List<VideoItem> normalized = normalizeVideoCategories(items);
                    sortItems(normalized, currentSortOption);
                    totalItems = normalized.size();

                    long loadElapsed = System.currentTimeMillis() - loadStart;
                    Log.i(TAG, "loadRecordsList: DB fast path — " + normalized.size() + " items in " + loadElapsed + "ms (no skeleton)");

                    // Keep caches in sync
                    com.fadcam.utils.VideoSessionCache.updateSessionCache(normalized);

                    // Digital forensics indexing (non-blocking)
                    DigitalForensicsIndexCoordinator.getInstance(requireContext()).enqueueIndex(normalized);

                    // Deliver directly to UI — no skeleton transition
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (!isAdded()) return;

                        if (recordsAdapter != null && recordsAdapter.isSkeletonMode()) {
                            recordsAdapter.setSkeletonMode(false);
                        }
                        allLoadedItems.clear();
                        allLoadedItems.addAll(normalized);
                        applyActiveFilterToUi();
                        updateUiVisibility();
                        isLoading = false;
                        isInitialLoad = false;
                        drainPendingRealtimeRefresh();

                        // Hide any lingering progress UI
                        if (loadingProgress != null) loadingProgress.setVisibility(View.GONE);
                        if (swipeRefreshLayout != null && swipeRefreshLayout.isRefreshing()) {
                            swipeRefreshLayout.setRefreshing(false);
                        }
                    });

                    // ── Background: delta scan for freshness ──
                    if (deltaExecutor == null || deltaExecutor.isShutdown()) {
                        deltaExecutor = Executors.newSingleThreadExecutor();
                    }
                    final ExecutorService currentDeltaExec = deltaExecutor;
                    currentDeltaExec.submit(() -> {
                        try {
                            List<VideoItem> deltaItems = repository.deltaScan(sharedPreferencesManager);
                            if (deltaItems.size() != totalItems) {
                                List<VideoItem> deltaNormalized = normalizeVideoCategories(deltaItems);
                                sortItems(deltaNormalized, currentSortOption);
                                totalItems = deltaNormalized.size();
                                Log.i(TAG, "Delta scan detected changes: " + deltaNormalized.size() + " items");
                                com.fadcam.utils.VideoSessionCache.updateSessionCache(deltaNormalized);
                                new Handler(Looper.getMainLooper()).post(() -> {
                                    if (!isAdded()) return;
                                    allLoadedItems.clear();
                                    allLoadedItems.addAll(deltaNormalized);
                                    applyActiveFilterToUi();
                                });
                            } else {
                                Log.d(TAG, "Delta scan: no changes detected");
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Delta scan failed (non-fatal): " + e.getMessage());
                        }
                    });

                    // ── Background: enrich durations ──
                    repository.startBackgroundEnrichment((uriString, durationMs) ->
                        Log.d(TAG, "Duration enriched: " + uriString + " → " + durationMs + "ms"));

                } catch (Exception e) {
                    Log.e(TAG, "Error in loadRecordsList fast path", e);
                    new Handler(Looper.getMainLooper()).post(() -> handleLoadingError());
                }
            });
            return;
        }

        // ═══════════════════════════════════════════════════════════════════════
        //  COLD START PATH: DB is empty (first launch or after wipe).
        //  Show skeleton while full SAF scan runs in background.
        // ═══════════════════════════════════════════════════════════════════════
        Log.d(TAG, "loadRecordsList: Cold start — DB empty, showing skeleton");
        int estimatedCount = 12;
        if (recordsAdapter == null || !recordsAdapter.isSkeletonMode()) {
            showSkeletonLoading(estimatedCount);
        }

        // Show progress indicator after 200ms if still loading
        showProgressRunnable = () -> {
            if (isLoading && loadingProgress != null) {
                loadingProgress.setVisibility(View.VISIBLE);
            }
        };
        if (progressHandler != null) {
            progressHandler.postDelayed(showProgressRunnable, 200);
        }

        if (executorService == null || executorService.isShutdown()) {
            executorService = Executors.newSingleThreadExecutor();
        }

        executorService.submit(() -> {
            try {
                long loadStart = System.currentTimeMillis();

                // Full scan — DB is empty so getVideos() will do a full SAF scan
                List<VideoItem> items = repository.getVideos(sharedPreferencesManager);
                List<VideoItem> normalized = normalizeVideoCategories(items);
                sortItems(normalized, currentSortOption);
                totalItems = normalized.size();

                long loadElapsed = System.currentTimeMillis() - loadStart;
                Log.i(TAG, "loadRecordsList: Cold start — " + normalized.size() + " items ready in " + loadElapsed + "ms");

                // Digital forensics indexing (non-blocking)
                DigitalForensicsIndexCoordinator.getInstance(requireContext()).enqueueIndex(normalized);

                // Deliver results to UI — replaces skeleton
                new Handler(Looper.getMainLooper()).post(() -> {
                    replaceSkeletonsWithData(normalized);
                });

                // Keep VideoSessionCache in sync
                com.fadcam.utils.VideoSessionCache.updateSessionCache(normalized);

                // Background: enrich durations
                repository.startBackgroundEnrichment((uriString, durationMs) ->
                    Log.d(TAG, "Duration enriched: " + uriString + " → " + durationMs + "ms"));

            } catch (Exception e) {
                Log.e(TAG, "Error in loadRecordsList", e);
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
                drainPendingRealtimeRefresh();
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
                allLoadedItems.clear();
                allLoadedItems.addAll(normalizeVideoCategories(newVideos));
                applyActiveFilterToUi();
                if (recyclerView != null) recyclerView.setVisibility(View.VISIBLE);
                // Don't hide loading indicator yet for partial updates
            } else {
                // For complete updates, replace everything
                allLoadedItems.clear();
                allLoadedItems.addAll(normalizeVideoCategories(newVideos));
                applyActiveFilterToUi();

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
            drainPendingRealtimeRefresh();
        });
    }

    private boolean isSupportedVideoFile(@Nullable String fileName) {
        return inferMediaTypeFromName(fileName) == VideoItem.MediaType.VIDEO;
    }

    @Nullable
    private VideoItem.MediaType inferMediaTypeFromName(@Nullable String fileName) {
        if (fileName == null) return null;
        String lower = fileName.toLowerCase();
        String expectedExt = "." + Constants.RECORDING_FILE_EXTENSION.toLowerCase();
        if (lower.endsWith(expectedExt)) {
            return VideoItem.MediaType.VIDEO;
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")) {
            return VideoItem.MediaType.IMAGE;
        }
        return null;
    }

    @NonNull
    private VideoItem.ShotSubtype inferShotSubtypeFromFolder(@Nullable String folderName) {
        if (folderName == null) return VideoItem.ShotSubtype.UNKNOWN;
        if (Constants.RECORDING_SUBDIR_SHOT_SELFIE.equalsIgnoreCase(folderName)) {
            return VideoItem.ShotSubtype.SELFIE;
        }
        if (Constants.RECORDING_SUBDIR_SHOT_FADREC.equalsIgnoreCase(folderName)) {
            return VideoItem.ShotSubtype.FADREC;
        }
        if (Constants.RECORDING_SUBDIR_SHOT_BACK.equalsIgnoreCase(folderName)) {
            return VideoItem.ShotSubtype.BACK;
        }
        return VideoItem.ShotSubtype.UNKNOWN;
    }

    @NonNull
    private VideoItem.ShotSubtype inferShotSubtypeFromName(@Nullable String fileName) {
        if (fileName == null) return VideoItem.ShotSubtype.UNKNOWN;
        if (fileName.startsWith(Constants.RECORDING_FILE_PREFIX_FADSHOT + "Selfie_")) {
            return VideoItem.ShotSubtype.SELFIE;
        }
        if (fileName.startsWith(Constants.RECORDING_FILE_PREFIX_FADSHOT + "FadRec_")) {
            return VideoItem.ShotSubtype.FADREC;
        }
        if (fileName.startsWith(Constants.RECORDING_FILE_PREFIX_FADSHOT + "Back_")) {
            return VideoItem.ShotSubtype.BACK;
        }
        return VideoItem.ShotSubtype.UNKNOWN;
    }

    @NonNull
    private VideoItem.ShotSubtype resolveShotSubtype(
            @NonNull VideoItem.ShotSubtype explicitSubtype,
            @Nullable String fileName
    ) {
        if (explicitSubtype != VideoItem.ShotSubtype.UNKNOWN) {
            return explicitSubtype;
        }
        VideoItem.ShotSubtype byName = inferShotSubtypeFromName(fileName);
        if (byName != VideoItem.ShotSubtype.UNKNOWN) {
            return byName;
        }
        return VideoItem.ShotSubtype.BACK;
    }

    @NonNull
    private VideoItem.ShotSubtype resolveShotSubtypeFromItem(@NonNull VideoItem item) {
        if (item.shotSubtype != null && item.shotSubtype != VideoItem.ShotSubtype.UNKNOWN) {
            return item.shotSubtype;
        }
        VideoItem.ShotSubtype byUri = inferShotSubtypeFromUri(item.uri);
        if (byUri != VideoItem.ShotSubtype.UNKNOWN) {
            return byUri;
        }
        return resolveShotSubtype(VideoItem.ShotSubtype.UNKNOWN, item.displayName);
    }

    @NonNull
    private VideoItem.ShotSubtype inferShotSubtypeFromUri(@Nullable Uri uri) {
        if (uri == null) return VideoItem.ShotSubtype.UNKNOWN;
        String value = uri.toString();
        if (value.contains("/" + Constants.RECORDING_SUBDIR_SHOT + "/" + Constants.RECORDING_SUBDIR_SHOT_SELFIE + "/")
                || value.contains("%2F" + Constants.RECORDING_SUBDIR_SHOT + "%2F" + Constants.RECORDING_SUBDIR_SHOT_SELFIE + "%2F")) {
            return VideoItem.ShotSubtype.SELFIE;
        }
        if (value.contains("/" + Constants.RECORDING_SUBDIR_SHOT + "/" + Constants.RECORDING_SUBDIR_SHOT_FADREC + "/")
                || value.contains("%2F" + Constants.RECORDING_SUBDIR_SHOT + "%2F" + Constants.RECORDING_SUBDIR_SHOT_FADREC + "%2F")) {
            return VideoItem.ShotSubtype.FADREC;
        }
        if (value.contains("/" + Constants.RECORDING_SUBDIR_SHOT + "/" + Constants.RECORDING_SUBDIR_SHOT_BACK + "/")
                || value.contains("%2F" + Constants.RECORDING_SUBDIR_SHOT + "%2F" + Constants.RECORDING_SUBDIR_SHOT_BACK + "%2F")) {
            return VideoItem.ShotSubtype.BACK;
        }
        return VideoItem.ShotSubtype.UNKNOWN;
    }

    @NonNull
    private VideoItem.CameraSubtype inferCameraSubtypeFromFolder(@Nullable String folderName) {
        if (folderName == null) return VideoItem.CameraSubtype.UNKNOWN;
        if (Constants.RECORDING_SUBDIR_CAMERA_FRONT.equalsIgnoreCase(folderName)) {
            return VideoItem.CameraSubtype.FRONT;
        }
        if (Constants.RECORDING_SUBDIR_CAMERA_DUAL.equalsIgnoreCase(folderName)) {
            return VideoItem.CameraSubtype.DUAL;
        }
        if (Constants.RECORDING_SUBDIR_CAMERA_BACK.equalsIgnoreCase(folderName)) {
            return VideoItem.CameraSubtype.BACK;
        }
        return VideoItem.CameraSubtype.UNKNOWN;
    }

    @NonNull
    private VideoItem.CameraSubtype inferCameraSubtypeFromName(@Nullable String fileName) {
        if (fileName == null) return VideoItem.CameraSubtype.UNKNOWN;
        if (fileName.startsWith("DualCam_")) {
            return VideoItem.CameraSubtype.DUAL;
        }
        return VideoItem.CameraSubtype.UNKNOWN;
    }

    @NonNull
    private VideoItem.CameraSubtype resolveCameraSubtype(
            @NonNull VideoItem.CameraSubtype explicitSubtype,
            @Nullable String fileName
    ) {
        if (explicitSubtype != VideoItem.CameraSubtype.UNKNOWN) {
            return explicitSubtype;
        }
        VideoItem.CameraSubtype byName = inferCameraSubtypeFromName(fileName);
        if (byName != VideoItem.CameraSubtype.UNKNOWN) {
            return byName;
        }
        return VideoItem.CameraSubtype.BACK;
    }

    @NonNull
    private VideoItem.CameraSubtype resolveCameraSubtypeFromItem(@NonNull VideoItem item) {
        if (item.cameraSubtype != null && item.cameraSubtype != VideoItem.CameraSubtype.UNKNOWN) {
            return item.cameraSubtype;
        }
        VideoItem.CameraSubtype byUri = inferCameraSubtypeFromUri(item.uri);
        if (byUri != VideoItem.CameraSubtype.UNKNOWN) {
            return byUri;
        }
        return resolveCameraSubtype(VideoItem.CameraSubtype.UNKNOWN, item.displayName);
    }

    @NonNull
    private VideoItem.CameraSubtype inferCameraSubtypeFromUri(@Nullable Uri uri) {
        if (uri == null) return VideoItem.CameraSubtype.UNKNOWN;
        String value = uri.toString();
        if (value.contains("/" + Constants.RECORDING_SUBDIR_CAMERA + "/" + Constants.RECORDING_SUBDIR_CAMERA_FRONT + "/")
                || value.contains("%2F" + Constants.RECORDING_SUBDIR_CAMERA + "%2F" + Constants.RECORDING_SUBDIR_CAMERA_FRONT + "%2F")) {
            return VideoItem.CameraSubtype.FRONT;
        }
        if (value.contains("/" + Constants.RECORDING_SUBDIR_CAMERA + "/" + Constants.RECORDING_SUBDIR_CAMERA_DUAL + "/")
                || value.contains("%2F" + Constants.RECORDING_SUBDIR_CAMERA + "%2F" + Constants.RECORDING_SUBDIR_CAMERA_DUAL + "%2F")
                || value.contains("/" + Constants.RECORDING_SUBDIR_DUAL + "/")
                || value.contains("%2F" + Constants.RECORDING_SUBDIR_DUAL + "%2F")) {
            return VideoItem.CameraSubtype.DUAL;
        }
        if (value.contains("/" + Constants.RECORDING_SUBDIR_CAMERA + "/" + Constants.RECORDING_SUBDIR_CAMERA_BACK + "/")
                || value.contains("%2F" + Constants.RECORDING_SUBDIR_CAMERA + "%2F" + Constants.RECORDING_SUBDIR_CAMERA_BACK + "%2F")) {
            return VideoItem.CameraSubtype.BACK;
        }
        return VideoItem.CameraSubtype.UNKNOWN;
    }

    private VideoItem.Category inferCategoryFromLegacyName(@Nullable String fileName) {
        if (fileName == null) return VideoItem.Category.UNKNOWN;
        if (fileName.startsWith(Constants.RECORDING_DIRECTORY + "_")) return VideoItem.Category.CAMERA;
        if (fileName.startsWith("DualCam_")) return VideoItem.Category.CAMERA;
        if (fileName.startsWith(Constants.RECORDING_FILE_PREFIX_FADREC)) return VideoItem.Category.SCREEN;
        if (fileName.startsWith("Faditor_")) return VideoItem.Category.FADITOR;
        if (fileName.startsWith("Stream_")) return VideoItem.Category.STREAM;
        if (fileName.startsWith(Constants.RECORDING_FILE_PREFIX_FADSHOT)) return VideoItem.Category.SHOT;
        return VideoItem.Category.UNKNOWN;
    }

    @NonNull
    private VideoItem.FaditorSubtype inferFaditorSubtypeFromFolder(@Nullable String folderName) {
        if (folderName == null) return VideoItem.FaditorSubtype.UNKNOWN;
        if (Constants.RECORDING_SUBDIR_FADITOR_CONVERTED.equalsIgnoreCase(folderName)) {
            return VideoItem.FaditorSubtype.CONVERTED;
        }
        if (Constants.RECORDING_SUBDIR_FADITOR_MERGE.equalsIgnoreCase(folderName)) {
            return VideoItem.FaditorSubtype.MERGE;
        }
        return VideoItem.FaditorSubtype.UNKNOWN;
    }

    @NonNull
    private VideoItem.FaditorSubtype inferFaditorSubtypeFromName(@Nullable String fileName) {
        if (fileName == null) return VideoItem.FaditorSubtype.UNKNOWN;
        if (fileName.startsWith(Constants.RECORDING_FILE_PREFIX_FADITOR_STANDARD)) {
            return VideoItem.FaditorSubtype.CONVERTED;
        }
        if (fileName.startsWith(Constants.RECORDING_FILE_PREFIX_FADITOR_MERGE)) {
            return VideoItem.FaditorSubtype.MERGE;
        }
        return VideoItem.FaditorSubtype.UNKNOWN;
    }

    @NonNull
    private VideoItem.FaditorSubtype resolveFaditorSubtype(
            @NonNull VideoItem.FaditorSubtype explicitSubtype,
            @Nullable String fileName
    ) {
        if (explicitSubtype != VideoItem.FaditorSubtype.UNKNOWN) {
            return explicitSubtype;
        }
        VideoItem.FaditorSubtype byName = inferFaditorSubtypeFromName(fileName);
        if (byName != VideoItem.FaditorSubtype.UNKNOWN) {
            return byName;
        }
        return VideoItem.FaditorSubtype.OTHER;
    }

    @NonNull
    private VideoItem.FaditorSubtype resolveFaditorSubtypeFromItem(@NonNull VideoItem item) {
        if (item.faditorSubtype != null && item.faditorSubtype != VideoItem.FaditorSubtype.UNKNOWN) {
            return item.faditorSubtype;
        }
        VideoItem.FaditorSubtype byUri = inferFaditorSubtypeFromUri(item.uri);
        if (byUri != VideoItem.FaditorSubtype.UNKNOWN) {
            return byUri;
        }
        return resolveFaditorSubtype(VideoItem.FaditorSubtype.UNKNOWN, item.displayName);
    }

    @NonNull
    private VideoItem.FaditorSubtype inferFaditorSubtypeFromUri(@Nullable Uri uri) {
        if (uri == null) return VideoItem.FaditorSubtype.UNKNOWN;
        String value = uri.toString();
        if (value.contains("/" + Constants.RECORDING_SUBDIR_FADITOR + "/" + Constants.RECORDING_SUBDIR_FADITOR_CONVERTED + "/")
                || value.contains("%2F" + Constants.RECORDING_SUBDIR_FADITOR + "%2F" + Constants.RECORDING_SUBDIR_FADITOR_CONVERTED + "%2F")) {
            return VideoItem.FaditorSubtype.CONVERTED;
        }
        if (value.contains("/" + Constants.RECORDING_SUBDIR_FADITOR + "/" + Constants.RECORDING_SUBDIR_FADITOR_MERGE + "/")
                || value.contains("%2F" + Constants.RECORDING_SUBDIR_FADITOR + "%2F" + Constants.RECORDING_SUBDIR_FADITOR_MERGE + "%2F")) {
            return VideoItem.FaditorSubtype.MERGE;
        }
        return VideoItem.FaditorSubtype.UNKNOWN;
    }

    private void addSafCategoryFiles(
            @NonNull List<SafCandidate> out,
            @NonNull DocumentFile baseDir,
            @NonNull String childFolderName,
            @NonNull VideoItem.Category category
    ) {
        DocumentFile childDir = RecordingStoragePaths.findOrCreateChildDirectory(baseDir, childFolderName, false);
        if (childDir == null || !childDir.isDirectory() || !childDir.canRead()) {
            return;
        }
        if (category == VideoItem.Category.CAMERA) {
            addSafCameraFiles(out, childDir);
            return;
        }
        if (category == VideoItem.Category.SHOT) {
            addSafShotFiles(out, childDir);
            return;
        }
        if (category == VideoItem.Category.FADITOR) {
            addSafFaditorFiles(out, childDir);
            return;
        }
        DocumentFile[] files = childDir.listFiles();
        if (files == null) {
            return;
        }
        for (DocumentFile file : files) {
            if (file != null && file.isFile()) {
                out.add(new SafCandidate(file, category));
            }
        }
    }

    private void addSafCameraFiles(@NonNull List<SafCandidate> out, @NonNull DocumentFile cameraRoot) {
        DocumentFile[] entries = cameraRoot.listFiles();
        if (entries == null) return;
        for (DocumentFile entry : entries) {
            if (entry == null) continue;
            if (entry.isFile()) {
                out.add(new SafCandidate(
                        entry,
                        VideoItem.Category.CAMERA,
                        VideoItem.ShotSubtype.UNKNOWN,
                        inferCameraSubtypeFromName(entry.getName())));
                continue;
            }
            if (!entry.isDirectory()) continue;
            VideoItem.CameraSubtype subtype = inferCameraSubtypeFromFolder(entry.getName());
            DocumentFile[] nested = entry.listFiles();
            if (nested == null) continue;
            for (DocumentFile nestedFile : nested) {
                if (nestedFile != null && nestedFile.isFile()) {
                    out.add(new SafCandidate(
                            nestedFile,
                            VideoItem.Category.CAMERA,
                            VideoItem.ShotSubtype.UNKNOWN,
                            subtype));
                }
            }
        }
    }

    private void addSafShotFiles(@NonNull List<SafCandidate> out, @NonNull DocumentFile shotRoot) {
        DocumentFile[] entries = shotRoot.listFiles();
        if (entries == null) return;
        for (DocumentFile entry : entries) {
            if (entry == null) continue;
            if (entry.isFile()) {
                out.add(new SafCandidate(entry, VideoItem.Category.SHOT, inferShotSubtypeFromName(entry.getName())));
                continue;
            }
            if (!entry.isDirectory()) continue;
            VideoItem.ShotSubtype subtype = inferShotSubtypeFromFolder(entry.getName());
            DocumentFile[] nested = entry.listFiles();
            if (nested == null) continue;
            for (DocumentFile nestedFile : nested) {
                if (nestedFile != null && nestedFile.isFile()) {
                    out.add(new SafCandidate(nestedFile, VideoItem.Category.SHOT, subtype));
                }
            }
        }
    }

    private void addSafFaditorFiles(@NonNull List<SafCandidate> out, @NonNull DocumentFile faditorRoot) {
        DocumentFile[] entries = faditorRoot.listFiles();
        if (entries == null) return;
        for (DocumentFile entry : entries) {
            if (entry == null) continue;
            if (entry.isFile()) {
                out.add(new SafCandidate(
                        entry,
                        VideoItem.Category.FADITOR,
                        VideoItem.ShotSubtype.UNKNOWN,
                        VideoItem.CameraSubtype.UNKNOWN,
                        inferFaditorSubtypeFromName(entry.getName())));
                continue;
            }
            if (!entry.isDirectory()) continue;
            VideoItem.FaditorSubtype subtype = inferFaditorSubtypeFromFolder(entry.getName());
            DocumentFile[] nested = entry.listFiles();
            if (nested == null) continue;
            for (DocumentFile nestedFile : nested) {
                if (nestedFile != null && nestedFile.isFile()) {
                    out.add(new SafCandidate(
                            nestedFile,
                            VideoItem.Category.FADITOR,
                            VideoItem.ShotSubtype.UNKNOWN,
                            VideoItem.CameraSubtype.UNKNOWN,
                            subtype));
                }
            }
        }
    }

    private void addSafMediaItem(
            @NonNull List<VideoItem> out,
            @NonNull DocumentFile docFile,
            @NonNull VideoItem.Category explicitCategory,
            @NonNull VideoItem.ShotSubtype explicitShotSubtype,
            @NonNull VideoItem.CameraSubtype explicitCameraSubtype,
            @NonNull VideoItem.FaditorSubtype explicitFaditorSubtype
    ) {
        String fileName = docFile.getName();
        VideoItem.MediaType mediaType = inferMediaTypeFromName(fileName);
        if (mediaType == null) {
            return;
        }
        if (fileName != null && fileName.startsWith("temp_")) {
            // Skip temp_ files — OpenGL pipeline no longer uses them
            return;
        }

        VideoItem.Category category = explicitCategory != VideoItem.Category.UNKNOWN
                ? explicitCategory
                : inferCategoryFromLegacyName(fileName);
        long lastModified = docFile.lastModified();
        VideoItem.ShotSubtype shotSubtype = category == VideoItem.Category.SHOT
                ? resolveShotSubtype(explicitShotSubtype, fileName)
                : VideoItem.ShotSubtype.UNKNOWN;
        VideoItem.CameraSubtype cameraSubtype = category == VideoItem.Category.CAMERA
                ? resolveCameraSubtype(explicitCameraSubtype, fileName)
                : VideoItem.CameraSubtype.UNKNOWN;
        VideoItem.FaditorSubtype faditorSubtype = category == VideoItem.Category.FADITOR
                ? resolveFaditorSubtype(explicitFaditorSubtype, fileName)
                : VideoItem.FaditorSubtype.UNKNOWN;
        VideoItem item = new VideoItem(
                docFile.getUri(),
                fileName,
                docFile.length(),
                lastModified,
                category,
                mediaType,
                shotSubtype,
                cameraSubtype,
                faditorSubtype);
        item.isNew = Utils.isVideoConsideredNew(lastModified);
        out.add(item);
    }

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

}
