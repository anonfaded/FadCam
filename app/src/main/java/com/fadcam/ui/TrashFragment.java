package com.fadcam.ui;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.model.TrashItem;
import com.fadcam.utils.TrashManager;
import com.fadcam.SharedPreferencesManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.List;
import androidx.appcompat.app.AlertDialog;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.content.Intent;
import android.net.Uri;
import java.io.File;
import androidx.appcompat.app.AppCompatActivity;
import java.util.Locale;
import androidx.fragment.app.Fragment;
import androidx.activity.OnBackPressedCallback;
import android.widget.CheckBox;
import android.widget.ImageView;

public class TrashFragment extends BaseFragment implements TrashAdapter.OnTrashItemInteractionListener {

    private static final String TAG = "TrashFragment";
    private RecyclerView recyclerViewTrashItems;
    private TrashAdapter trashAdapter;
    private List<TrashItem> trashItems = new ArrayList<>();
    private Button buttonRestoreSelected;
    private Button buttonDeleteSelectedPermanently;
    private Button buttonEmptyAllTrash;
    private MaterialToolbar toolbar;
    private TextView textViewEmptyTrash;
    private View emptyTrashLayout;
    private AlertDialog restoreProgressDialog;
    private ExecutorService executorService;
    private TextView tvAutoDeleteInfo;
    private SharedPreferencesManager sharedPreferencesManager;
    private boolean isInSelectionMode = false;
    private CheckBox checkboxSelectAll;

    public TrashFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executorService = Executors.newSingleThreadExecutor();
        setHasOptionsMenu(true);
        sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());
        
        // ----- Fix Start: Remove custom back press handler -----
        // We no longer need our own back handler since MainActivity now detects
        // and handles TrashFragment visibility directly
        // ----- Fix End: Remove custom back press handler -----
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_trash, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        toolbar = view.findViewById(R.id.trash_toolbar);
        recyclerViewTrashItems = view.findViewById(R.id.recycler_view_trash_items);
        buttonRestoreSelected = view.findViewById(R.id.button_restore_selected);
        buttonDeleteSelectedPermanently = view.findViewById(R.id.button_delete_selected_permanently);
        buttonEmptyAllTrash = view.findViewById(R.id.button_empty_all_trash);
        textViewEmptyTrash = view.findViewById(R.id.empty_trash_text_view);
        emptyTrashLayout = view.findViewById(R.id.empty_trash_layout);
        tvAutoDeleteInfo = view.findViewById(R.id.tvAutoDeleteInfo);
        checkboxSelectAll = view.findViewById(R.id.checkbox_select_all);

        setupToolbar();
        setupRecyclerView();
        setupButtonListeners();
        setupSelectAllCheckbox();
        updateAutoDeleteInfoText();

        // Auto-delete old items first, then load
        if (getContext() != null) {
            int autoDeleteMinutes = sharedPreferencesManager.getTrashAutoDeleteMinutes();
            int autoDeletedCount = TrashManager.autoDeleteExpiredItems(getContext(), autoDeleteMinutes);
            if (autoDeletedCount > 0) {
                Toast.makeText(getContext(), getString(R.string.trash_auto_deleted_toast, autoDeletedCount), Toast.LENGTH_LONG).show();
            }
        }
        loadTrashItems();
    }

    private void setupToolbar() {
        if (toolbar != null && getActivity() instanceof AppCompatActivity) {
            AppCompatActivity activity = (AppCompatActivity) getActivity();
            activity.setSupportActionBar(toolbar);
            toolbar.setTitle(getString(R.string.trash_fragment_title_text));
            toolbar.setNavigationIcon(R.drawable.ic_close);
            toolbar.setNavigationOnClickListener(v -> {
                // ----- Fix Start for this method(setupToolbar) -----
                // Handle the fade-out animation manually instead of relying on dispatcher
                if (getActivity() instanceof MainActivity) {
                    MainActivity mainActivity = (MainActivity) getActivity();
                    View overlayContainer = mainActivity.findViewById(R.id.overlay_fragment_container);
                    
                    if (overlayContainer != null) {
                        // Animate fading out - identical to the animation in MainActivity
                        overlayContainer.animate()
                            .alpha(0f)
                            .setDuration(250)
                            .withEndAction(() -> {
                                // Set visibility to GONE after animation completes
                                overlayContainer.setVisibility(View.GONE);
                                overlayContainer.setAlpha(1f); // Reset alpha for next time
                                
                                // Get reference to viewPager and adapter
                                androidx.viewpager2.widget.ViewPager2 viewPager = 
                                    mainActivity.findViewById(R.id.view_pager);
                                com.google.android.material.bottomnavigation.BottomNavigationView bottomNav = 
                                    mainActivity.findViewById(R.id.bottom_navigation);
                                
                                if (viewPager != null && bottomNav != null) {
                                    // Force a complete reset of the ViewPager and its fragments
                                    final int currentPosition = viewPager.getCurrentItem();
                                    
                                    // Completely recreate the adapter
                                    ViewPagerAdapter newAdapter = new ViewPagerAdapter(mainActivity);
                                    viewPager.setAdapter(newAdapter);
                                    
                                    // Reset page transformer to ensure animations work
                                    viewPager.setPageTransformer(new FadePageTransformer());
                                    
                                    // Restore position without animation
                                    viewPager.setCurrentItem(currentPosition, false);
                                    
                                    // Make sure the correct tab is selected
                                    switch (currentPosition) {
                                        case 0:
                                            bottomNav.setSelectedItemId(R.id.navigation_home);
                                            break;
                                        case 1:
                                            bottomNav.setSelectedItemId(R.id.navigation_records);
                                            break;
                                        case 2:
                                            bottomNav.setSelectedItemId(R.id.navigation_remote);
                                            break;
                                        case 3:
                                            bottomNav.setSelectedItemId(R.id.navigation_settings);
                                            break;
                                        case 4:
                                            bottomNav.setSelectedItemId(R.id.navigation_about);
                                            break;
                                    }
                                }
                                
                                // Pop any fragments in the back stack
                                if (mainActivity.getSupportFragmentManager().getBackStackEntryCount() > 0) {
                                    mainActivity.getSupportFragmentManager().popBackStack();
                                }
                            });
                    }
                }
                // ----- Fix Ended for this method(setupToolbar) -----
            });
        } else {
            Log.e(TAG, "Toolbar is null or Activity is not AppCompatActivity, cannot set up toolbar as ActionBar.");
        }
    }

    private void setupRecyclerView() {
        if (getContext() == null) return;
        trashAdapter = new TrashAdapter(getContext(), trashItems, this, null);
        recyclerViewTrashItems.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerViewTrashItems.setAdapter(trashAdapter);
        
        // Add scroll state change listener to maintain selection during scrolling
        recyclerViewTrashItems.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    // Update select all checkbox state when scrolling stops
                    updateSelectAllCheckboxState();
                }
            }
        });
    }

    private void setupButtonListeners() {
        buttonRestoreSelected.setOnClickListener(v -> {
            List<TrashItem> selectedItems = trashAdapter.getSelectedItems();
            if (selectedItems.isEmpty()) {
                Toast.makeText(getContext(), getString(R.string.trash_no_items_selected_toast), Toast.LENGTH_SHORT).show();
                return;
            }
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.trash_dialog_restore_title))
                    .setMessage(getString(R.string.trash_dialog_restore_message, selectedItems.size()))
                    .setNegativeButton(getString(R.string.universal_cancel), (dialog, which) -> {
                        onRestoreFinished(false, getString(R.string.trash_restore_cancelled_toast));
                    })
                    .setPositiveButton(getString(R.string.universal_restore), (dialog, which) -> {
                        onRestoreStarted(selectedItems.size());
                        if (executorService == null || executorService.isShutdown()) {
                            executorService = Executors.newSingleThreadExecutor(); // Re-initialize if shutdown
                        }
                        executorService.submit(() -> {
                            boolean success = TrashManager.restoreItemsFromTrash(getContext(), selectedItems);
                            String message = success ? getString(R.string.trash_restore_success_toast, selectedItems.size())
                                                     : getString(R.string.trash_restore_fail_toast);
                            
                            // Post result back to main thread
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> {
                                    onRestoreFinished(success, message);
                                    if (success) {
                                        loadTrashItems(); // Refresh list only on success
                                    }
                                });
                            }
                        });
                    })
                    .show();
        });

        buttonDeleteSelectedPermanently.setOnClickListener(v -> {
            List<TrashItem> selectedItems = trashAdapter.getSelectedItems();
            if (selectedItems.isEmpty()) {
                Toast.makeText(getContext(), getString(R.string.trash_empty_toast_message), Toast.LENGTH_SHORT).show();
                return;
            }

            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.dialog_permanently_delete_title))
                    .setMessage(getString(R.string.dialog_permanently_delete_message, selectedItems.size()))
                    .setNegativeButton(getString(R.string.universal_cancel), null)
                    .setPositiveButton(getString(R.string.universal_delete), (dialog, which) -> {
                        if (TrashManager.permanentlyDeleteItems(getContext(), selectedItems)) {
                            Toast.makeText(getContext(), getString(R.string.trash_items_deleted_toast, selectedItems.size()), Toast.LENGTH_SHORT).show();
                            loadTrashItems();
                        } else {
                            Toast.makeText(getContext(), getString(R.string.trash_error_deleting_items_toast), Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
        });

        buttonEmptyAllTrash.setOnClickListener(v -> {
            if (trashItems.isEmpty()) {
                Toast.makeText(getContext(), getString(R.string.trash_empty_toast_message), Toast.LENGTH_SHORT).show();
                return;
            }
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.trash_dialog_empty_all_title))
                    .setMessage(getString(R.string.dialog_empty_all_trash_message))
                    .setNegativeButton(getString(R.string.universal_cancel), null)
                    .setPositiveButton(getString(R.string.trash_button_empty_all_action), (dialog, which) -> {
                        if (TrashManager.emptyAllTrash(getContext())) {
                            Toast.makeText(getContext(), getString(R.string.trash_emptied_toast), Toast.LENGTH_SHORT).show();
                            loadTrashItems();
                        } else {
                            Toast.makeText(getContext(), getString(R.string.trash_error_deleting_items_toast), Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
        });
        updateActionButtonsState(); // Initial state
    }

    private void loadTrashItems() {
        if (getContext() == null) return;
        List<TrashItem> loadedItems = TrashManager.loadTrashMetadata(getContext());
        trashItems.clear();
        trashItems.addAll(loadedItems);
        if (trashAdapter != null) {
            trashAdapter.notifyDataSetChanged();
        }
        updateActionButtonsState();
        checkEmptyState();
    }

    private void updateActionButtonsState() {
        // Example: enable buttons only if items are selected, or if trash is not empty for "Empty All"
        boolean anySelected = trashAdapter != null && trashAdapter.getSelectedItemsCount() > 0;
        buttonRestoreSelected.setEnabled(anySelected);
        buttonDeleteSelectedPermanently.setEnabled(anySelected);
        buttonEmptyAllTrash.setEnabled(!trashItems.isEmpty());
    }

    private void checkEmptyState() {
        if (trashItems.isEmpty()) {
            recyclerViewTrashItems.setVisibility(View.GONE);
            if (emptyTrashLayout != null) emptyTrashLayout.setVisibility(View.VISIBLE);
        } else {
            recyclerViewTrashItems.setVisibility(View.VISIBLE);
            if (emptyTrashLayout != null) emptyTrashLayout.setVisibility(View.GONE);
        }
    }

    @Override
    public void onItemSelectedStateChanged(boolean anySelected) {
        isInSelectionMode = anySelected;
        
        // Update button states
        buttonRestoreSelected.setEnabled(anySelected);
        buttonDeleteSelectedPermanently.setEnabled(anySelected);
        
        // Update toolbar title
        if (toolbar != null) {
            if (isInSelectionMode && trashAdapter != null) {
                int selectedCount = trashAdapter.getSelectedItemsCount();
                toolbar.setTitle(selectedCount + " selected");
            } else {
                toolbar.setTitle(getString(R.string.trash_fragment_title_text));
            }
        }
        
        // Show/hide select all checkbox
        if (checkboxSelectAll != null) {
            checkboxSelectAll.setVisibility((isInSelectionMode && !trashItems.isEmpty()) ? View.VISIBLE : View.GONE);
        }
        
        updateSelectAllCheckboxState();
    }

    @Override
    public void onPlayVideoRequested(TrashItem item) {
        if (getContext() == null || item == null || item.getTrashFileName() == null) {
            Toast.makeText(getContext(), "Cannot play video. Invalid item data.", Toast.LENGTH_SHORT).show();
            return;
        }

        File trashDirectory = TrashManager.getTrashDirectory(getContext());
        if (trashDirectory == null) {
            Toast.makeText(getContext(), "Cannot access trash directory.", Toast.LENGTH_SHORT).show();
            return;
        }

        File trashedVideoFile = new File(trashDirectory, item.getTrashFileName());

        if (!trashedVideoFile.exists()) {
            Log.e(TAG, "Trashed video file does not exist: " + trashedVideoFile.getAbsolutePath());
            Toast.makeText(getContext(), "Video file not found in trash.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Intent intent = new Intent(getContext(), VideoPlayerActivity.class);
            intent.setData(Uri.fromFile(trashedVideoFile)); 
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error starting VideoPlayerActivity for trash item: " + trashedVideoFile.getAbsolutePath(), e);
            Toast.makeText(getContext(), "Error playing video.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRestoreStarted(int itemCount) {
        if (getActivity() == null || getContext() == null) return;
        getActivity().runOnUiThread(() -> {
            if (restoreProgressDialog != null && restoreProgressDialog.isShowing()) {
                restoreProgressDialog.dismiss();
            }
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
            LayoutInflater inflater = LayoutInflater.from(getContext());
            View dialogView = inflater.inflate(R.layout.dialog_progress, null);

            TextView progressText = dialogView.findViewById(R.id.progress_text);
            if (progressText != null) {
                progressText.setText("Restoring " + itemCount + " item(s)...");
            }
            builder.setView(dialogView);
            builder.setCancelable(false);
            restoreProgressDialog = builder.create();
            if (!restoreProgressDialog.isShowing()) {
                restoreProgressDialog.show();
            }
        });
    }

    @Override
    public void onRestoreFinished(boolean success, String message) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (restoreProgressDialog != null && restoreProgressDialog.isShowing()) {
                restoreProgressDialog.dismiss();
                restoreProgressDialog = null;
            }
            if (getContext() != null && message != null && !message.isEmpty()) {
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onItemCheckChanged(TrashItem item, boolean isChecked) {
        // This method is called by the adapter when a checkbox state changes.
        if (getView() != null) { // Ensure fragment is still active
            // Update selection mode based on whether any items are selected
            boolean hasSelectedItems = trashAdapter != null && trashAdapter.getSelectedItemsCount() > 0;
            isInSelectionMode = hasSelectedItems;
            
            // Update UI based on selection state
            updateActionButtonsState();
            
            // Update toolbar title if in selection mode
            if (toolbar != null && isInSelectionMode) {
                int selectedCount = trashAdapter.getSelectedItemsCount();
                toolbar.setTitle(selectedCount + " selected");
            } else if (toolbar != null) {
                toolbar.setTitle(getString(R.string.trash_fragment_title_text));
            }
            
            // Update select all checkbox state
            updateSelectAllCheckboxState();
            
            // Show/hide select all checkbox
            if (checkboxSelectAll != null) {
                checkboxSelectAll.setVisibility(isInSelectionMode ? View.VISIBLE : View.GONE);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.trash_options_menu, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_trash_auto_delete_settings) {
            showAutoDeleteSettingsDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showAutoDeleteSettingsDialog() {
        if (getContext() == null || sharedPreferencesManager == null) {
            Log.e(TAG, "Cannot show auto-delete settings dialog, context or prefs manager is null.");
            return;
        }

        final String[] items = {
                getString(R.string.auto_delete_1_hour),
                getString(R.string.auto_delete_5_hours),
                getString(R.string.auto_delete_10_hours),
                getString(R.string.auto_delete_1_day),
                getString(R.string.auto_delete_7_days),
                getString(R.string.auto_delete_30_days),
                getString(R.string.auto_delete_60_days),
                getString(R.string.auto_delete_90_days),
                getString(R.string.auto_delete_never)
        };

        final int[] valuesInMinutes = {
                60,          // 1 Hour
                5 * 60,      // 5 Hours
                10 * 60,     // 10 Hours
                1 * 24 * 60, // 1 Day
                7 * 24 * 60, // 7 Days
                30 * 24 * 60,// 30 Days
                60 * 24 * 60,// 60 Days
                90 * 24 * 60,// 90 Days
                SharedPreferencesManager.TRASH_AUTO_DELETE_NEVER
        };

        int currentSettingMinutes = sharedPreferencesManager.getTrashAutoDeleteMinutes();
        int checkedItem = -1;

        for (int i = 0; i < valuesInMinutes.length; i++) {
            if (valuesInMinutes[i] == currentSettingMinutes) {
                checkedItem = i;
                break;
            }
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.auto_delete_dialog_title))
                .setSingleChoiceItems(items, checkedItem, (dialog, which) -> {
                    // Action on item selection (optional, could update a temporary variable)
                })
                .setPositiveButton(getString(R.string.auto_delete_save_setting), (dialog, which) -> {
                    AlertDialog alertDialog = (AlertDialog) dialog;
                    int selectedPosition = alertDialog.getListView().getCheckedItemPosition();
                    if (selectedPosition != -1 && selectedPosition < valuesInMinutes.length) {
                        int selectedMinutes = valuesInMinutes[selectedPosition];
                        sharedPreferencesManager.setTrashAutoDeleteMinutes(selectedMinutes);
                        updateAutoDeleteInfoText();
                        Log.d(TAG, "Auto-delete setting updated to: " + selectedMinutes + " minutes.");
                        
                        boolean itemsWereAutoDeleted = false;
                        if (getContext() != null) {
                            int autoDeletedCount = TrashManager.autoDeleteExpiredItems(getContext(), selectedMinutes);
                            if (autoDeletedCount > 0) {
                                Toast.makeText(getContext(), getString(R.string.trash_auto_deleted_toast, autoDeletedCount), Toast.LENGTH_LONG).show();
                                itemsWereAutoDeleted = true;
                            }
                        }
                        loadTrashItems(); 
                        if (trashAdapter != null && !itemsWereAutoDeleted) { 
                            trashAdapter.notifyDataSetChanged();
                        }
                    }
                })
                .setNegativeButton(getString(R.string.universal_cancel), null)
                .show();
    }

    private void updateAutoDeleteInfoText() {
        if (tvAutoDeleteInfo == null || sharedPreferencesManager == null || getContext() == null) return;

        int totalMinutes = sharedPreferencesManager.getTrashAutoDeleteMinutes();

        if (totalMinutes == SharedPreferencesManager.TRASH_AUTO_DELETE_NEVER) {
            tvAutoDeleteInfo.setText(getString(R.string.trash_auto_delete_info_manual));
        } else if (totalMinutes < 60) { // Less than an hour, show in minutes (though current options are >= 1 hour)
             // This case isn't strictly needed with current options but good for future flexibility
            tvAutoDeleteInfo.setText(String.format(Locale.getDefault(), "Items are automatically deleted after %d minutes.", totalMinutes));
        } else if (totalMinutes < (24 * 60)) { // Less than a day, show in hours
            int hours = totalMinutes / 60;
            tvAutoDeleteInfo.setText(getResources().getQuantityString(R.plurals.trash_auto_delete_info_hours, hours, hours));
        } else { // Show in days
            int days = totalMinutes / (24 * 60);
            tvAutoDeleteInfo.setText(getResources().getQuantityString(R.plurals.trash_auto_delete_info_days, days, days));
        }
    }

    @Override
    protected boolean onBackPressed() {
        // If in selection mode, exit selection mode
        if (isInSelectionMode()) {
            exitSelectionMode();
            return true;
        }
        
        // ----- Fix Start: Let MainActivity handle the back press -----
        // For normal cases, let MainActivity handle it - it will detect that
        // TrashFragment is visible and close it properly
        return false;
        // ----- Fix End: Let MainActivity handle the back press -----
    }

    /**
     * Checks if the fragment is currently in selection mode
     * @return true if in selection mode, false otherwise
     */
    private boolean isInSelectionMode() {
        return isInSelectionMode;
    }

    /**
     * Exits selection mode and updates the UI accordingly
     */
    private void exitSelectionMode() {
        if (isInSelectionMode && trashAdapter != null) {
            isInSelectionMode = false;
            trashAdapter.clearSelections();
            updateActionButtonsState();
            // Reset any UI elements that change in selection mode
            if (toolbar != null) {
                toolbar.setTitle(getString(R.string.trash_fragment_title_text));
            }
            
            // Hide select all checkbox
            if (checkboxSelectAll != null) {
                checkboxSelectAll.setVisibility(View.GONE);
            }
        }
    }

    private void setupSelectAllCheckbox() {
        if (checkboxSelectAll == null) return;
        
        checkboxSelectAll.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (!trashAdapter.isAllSelected() && !trashItems.isEmpty()) {
                    trashAdapter.selectAll();
                }
            } else {
                if (trashAdapter.getSelectedItemsCount() > 0) {
                    trashAdapter.clearSelections();
                }
            }
            updateActionButtonsState();
        });
    }
    
    private void updateSelectAllCheckboxState() {
        if (checkboxSelectAll == null || trashAdapter == null) return;
        
        // Update checkbox without triggering listener
        checkboxSelectAll.setOnCheckedChangeListener(null);
        boolean shouldBeChecked = trashAdapter.isAllSelected() && !trashItems.isEmpty();
        checkboxSelectAll.setChecked(shouldBeChecked);
        
        // Re-add the listener
        setupSelectAllCheckbox();
    }

    // TODO: Create TrashAdapter class
    // TODO: Implement logic for restore, permanent delete, empty all
    // TODO: Implement auto-deletion of files older than 30 days (perhaps in TrashManager and called periodically or on fragment load)
} 