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

// Add AppLock imports
import com.guardanis.applock.AppLock;
import com.guardanis.applock.dialogs.UnlockDialogBuilder;

import android.widget.ArrayAdapter;
import androidx.core.content.ContextCompat;
import android.graphics.Color;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Build;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.graphics.PorterDuff;
import android.widget.RadioButton;
import java.lang.reflect.Field;

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
    
    private static final String PREF_APPLOCK_ENABLED = "applock_enabled";
    private boolean isUnlocked = false;

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

        // Hide content initially until AppLock status is checked
        view.findViewById(R.id.constraint_layout_root).setVisibility(View.INVISIBLE);

        initializeViews(view);

        // Check if app lock is enabled and show unlock dialog if needed
        checkAppLock();

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

    private void initializeViews(@NonNull View view) {
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
        // Set the restore button to blue color for Faded Night theme
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME, com.fadcam.Constants.DEFAULT_APP_THEME);
        boolean isFadedNightTheme = "Faded Night".equals(currentTheme);
        
        if (isFadedNightTheme && buttonRestoreSelected != null) {
            // Use a blue color for restore button in Faded Night theme
            int blueColor = Color.parseColor("#4285F4"); // Google blue
            buttonRestoreSelected.setTextColor(blueColor);
        }
        
        // Improve the delete button appearance when disabled - make it visually obvious
        if (buttonDeleteSelectedPermanently != null) {
            updateDeleteButtonAppearance(false); // Initially disabled (no selection)
        }
        
        buttonRestoreSelected.setOnClickListener(v -> {
            List<TrashItem> selectedItems = trashAdapter.getSelectedItems();
            if (selectedItems.isEmpty()) {
                Toast.makeText(getContext(), getString(R.string.trash_no_items_selected_toast), Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Use the themed dialog builder helper method
            TextView messageView = new TextView(requireContext());
            messageView.setText(getString(R.string.trash_dialog_restore_message, selectedItems.size()));
            
            // Set text color based on theme
            boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
            messageView.setTextColor(ContextCompat.getColor(requireContext(), 
                isSnowVeilTheme ? android.R.color.black : android.R.color.white));
            messageView.setPadding(48, 32, 48, 0);
            messageView.setTextSize(16);
            
            AlertDialog dialog = themedDialogBuilder(requireContext())
                    .setTitle(getString(R.string.trash_dialog_restore_title))
                    .setView(messageView)
                    .setNegativeButton(getString(R.string.universal_cancel), (dialogInterface, which) -> {
                        onRestoreFinished(false, getString(R.string.trash_restore_cancelled_toast));
                    })
                    .setPositiveButton(getString(R.string.universal_restore), (dialogInterface, which) -> {
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
                    .create();
                    
            dialog.show();
            
            // Apply theme-specific button colors
            setDialogButtonColors(dialog);
            
            // Color the restore button specially
            if (isFadedNightTheme && dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.parseColor("#4285F4"));
            }
        });

        buttonDeleteSelectedPermanently.setOnClickListener(v -> {
            List<TrashItem> selectedItems = trashAdapter.getSelectedItems();
            if (selectedItems.isEmpty()) {
                Toast.makeText(getContext(), getString(R.string.trash_empty_toast_message), Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Use the themed dialog builder helper method
            TextView messageView = new TextView(requireContext());
            messageView.setText(getString(R.string.dialog_permanently_delete_message, selectedItems.size()));
            
            // Set text color based on theme
            boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
            
            messageView.setTextColor(ContextCompat.getColor(requireContext(), 
                isSnowVeilTheme ? android.R.color.black : android.R.color.white));
            messageView.setPadding(48, 32, 48, 0);
            messageView.setTextSize(16);
            
            AlertDialog dialog = themedDialogBuilder(requireContext())
                    .setTitle(getString(R.string.dialog_permanently_delete_title))
                    .setView(messageView)
                    .setNegativeButton(getString(R.string.universal_cancel), null)
                    .setPositiveButton(getString(R.string.universal_delete), (dialogInterface, which) -> {
                        if (TrashManager.permanentlyDeleteItems(getContext(), selectedItems)) {
                            Toast.makeText(getContext(), getString(R.string.trash_items_deleted_toast, selectedItems.size()), Toast.LENGTH_SHORT).show();
                            loadTrashItems();
                        } else {
                            Toast.makeText(getContext(), getString(R.string.trash_error_deleting_items_toast), Toast.LENGTH_SHORT).show();
                        }
                    })
                    .create();
                    
            dialog.show();
            
            // Apply theme-specific button colors
            setDialogButtonColors(dialog);
            
            // Set delete button to red color for emphasis
            if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
                int errorColor = ContextCompat.getColor(requireContext(), R.color.colorError);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(errorColor);
            }
        });

        buttonEmptyAllTrash.setOnClickListener(v -> {
            if (trashItems.isEmpty()) {
                Toast.makeText(getContext(), getString(R.string.trash_empty_toast_message), Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Use the themed dialog builder helper method
            TextView messageView = new TextView(requireContext());
            messageView.setText(getString(R.string.dialog_empty_all_trash_message));
            
            // Set text color based on theme
            boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
            
            messageView.setTextColor(ContextCompat.getColor(requireContext(), 
                isSnowVeilTheme ? android.R.color.black : android.R.color.white));
            messageView.setPadding(48, 32, 48, 0);
            messageView.setTextSize(16);
            
            AlertDialog dialog = themedDialogBuilder(requireContext())
                    .setTitle(getString(R.string.trash_dialog_empty_all_title))
                    .setView(messageView)
                    .setNegativeButton(getString(R.string.universal_cancel), null)
                    .setPositiveButton(getString(R.string.trash_button_empty_all_action), (dialogInterface, which) -> {
                        if (TrashManager.emptyAllTrash(getContext())) {
                            Toast.makeText(getContext(), getString(R.string.trash_emptied_toast), Toast.LENGTH_SHORT).show();
                            loadTrashItems();
                        } else {
                            Toast.makeText(getContext(), getString(R.string.trash_error_deleting_items_toast), Toast.LENGTH_SHORT).show();
                        }
                    })
                    .create();
                    
            dialog.show();
            
            // Apply theme-specific button colors
            setDialogButtonColors(dialog);
            
            // Set empty all button to red color for emphasis
            if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
                int errorColor = ContextCompat.getColor(requireContext(), R.color.colorError);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(errorColor);
            }
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
        
        // Update the delete and restore button appearances
        updateDeleteButtonAppearance(anySelected);
        updateRestoreButtonAppearance(anySelected);
    }

    /**
     * Updates the appearance of the delete button based on whether items are selected
     * @param anySelected true if any items are selected, false otherwise
     */
    private void updateDeleteButtonAppearance(boolean anySelected) {
        if (buttonDeleteSelectedPermanently == null) return;
        
        if (anySelected) {
            // Items selected - button should be bright red and enabled
            buttonDeleteSelectedPermanently.setEnabled(true);
            buttonDeleteSelectedPermanently.setAlpha(1.0f);
            
            // Set a bright, vibrant red color matching the "Empty All" button
            int errorColor = ContextCompat.getColor(requireContext(), R.color.colorError);
            buttonDeleteSelectedPermanently.setTextColor(errorColor);
        } else {
            // No items selected - button should be visually disabled
            buttonDeleteSelectedPermanently.setEnabled(false);
            buttonDeleteSelectedPermanently.setAlpha(0.5f); // Semi-transparent
            
            // Check current theme to apply appropriate styling
            // Get the current theme from shared preferences
            String currentThemeForButton = sharedPreferencesManager.sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME, com.fadcam.Constants.DEFAULT_APP_THEME);
            
            // Always use a muted red color for the disabled delete button
            int disabledRedColor = ContextCompat.getColor(requireContext(), R.color.colorErrorDisabled);
            buttonDeleteSelectedPermanently.setTextColor(disabledRedColor);
        }
    }

    /**
     * Updates the appearance of the restore button based on whether items are selected
     * @param anySelected true if any items are selected, false otherwise
     */
    private void updateRestoreButtonAppearance(boolean anySelected) {
        if (buttonRestoreSelected == null) return;
        
        if (anySelected) {
            // Items selected - button should be fully visible and enabled
            buttonRestoreSelected.setEnabled(true);
            buttonRestoreSelected.setAlpha(1.0f);
            
            // Set proper color based on theme
            String currentTheme = sharedPreferencesManager.sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME, com.fadcam.Constants.DEFAULT_APP_THEME);
            boolean isFadedNightTheme = "Faded Night".equals(currentTheme);
            
            if (isFadedNightTheme) {
                // For Faded Night theme, use blue color
                buttonRestoreSelected.setTextColor(Color.parseColor("#4285F4")); // Google blue
            } else {
                // For other themes, use theme's primary/accent color
                buttonRestoreSelected.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorPrimary));
            }
        } else {
            // No items selected - button should be visually disabled
            buttonRestoreSelected.setEnabled(false);
            buttonRestoreSelected.setAlpha(0.5f); // Semi-transparent
            
            // Check current theme to apply appropriate styling
            String currentTheme = sharedPreferencesManager.sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME, com.fadcam.Constants.DEFAULT_APP_THEME);
            boolean isFadedNightTheme = "Faded Night".equals(currentTheme);
            
            if (isFadedNightTheme) {
                // For Faded Night theme, use darker blue for disabled state
                buttonRestoreSelected.setTextColor(Color.parseColor("#2A4374")); // Darker blue
            }
        }
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
            
            // Use the themed dialog builder
            MaterialAlertDialogBuilder builder = themedDialogBuilder(requireContext());
            LayoutInflater inflater = LayoutInflater.from(getContext());
            View dialogView = inflater.inflate(R.layout.dialog_progress, null);

            TextView progressText = dialogView.findViewById(R.id.progress_text);
            if (progressText != null) {
                progressText.setText("Restoring " + itemCount + " item(s)...");
                
                // Set text color based on theme
                String currentTheme = sharedPreferencesManager.sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME, com.fadcam.Constants.DEFAULT_APP_THEME);
                boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
                progressText.setTextColor(ContextCompat.getColor(requireContext(), 
                    isSnowVeilTheme ? android.R.color.black : android.R.color.white));
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
            
            // Explicitly update button appearances based on selection
            updateDeleteButtonAppearance(hasSelectedItems);
            updateRestoreButtonAppearance(hasSelectedItems);
            
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
        
        // Determine text color based on theme
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME, com.fadcam.Constants.DEFAULT_APP_THEME);
        boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
        boolean isFadedNightTheme = "Faded Night".equals(currentTheme);
        
        int textColor = ContextCompat.getColor(requireContext(), 
            isSnowVeilTheme ? android.R.color.black : android.R.color.white);
            
        // Create adapter with proper text colors
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(requireContext(), 
            android.R.layout.simple_list_item_single_choice, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView text1 = view.findViewById(android.R.id.text1);
                if (text1 != null) text1.setTextColor(textColor);
                return view;
            }
        };
        
        AlertDialog dialog = themedDialogBuilder(requireContext())
                .setTitle(getString(R.string.auto_delete_dialog_title))
                .setSingleChoiceItems(adapter, checkedItem, (dialogInterface, which) -> {
                    // Action on item selection (optional, could update a temporary variable)
                })
                .setPositiveButton(getString(R.string.auto_delete_save_setting), (dialogInterface, which) -> {
                    AlertDialog alertDialog = (AlertDialog) dialogInterface;
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
                .create();
                
        dialog.show();
        
        // After dialog is shown, tint the radio buttons for Faded Night theme
        if (isFadedNightTheme && dialog.getListView() != null) {
            try {
                // Force a small delay to ensure the ListView has been populated
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        // For each visible list item, find and tint the RadioButton
                        for (int i = 0; i < dialog.getListView().getChildCount(); i++) {
                            View listItem = dialog.getListView().getChildAt(i);
                            if (listItem != null) {
                                // Try several common IDs for RadioButton
                                int[] possibleIds = {
                                    android.R.id.checkbox
                                };
                                
                                RadioButton radioButton = null;
                                for (int id : possibleIds) {
                                    View potential = listItem.findViewById(id);
                                    if (potential instanceof RadioButton) {
                                        radioButton = (RadioButton) potential;
                                        break;
                                    }
                                }
                                
                                // If not found by ID, try to find by class
                                if (radioButton == null && listItem instanceof ViewGroup) {
                                    ViewGroup vg = (ViewGroup) listItem;
                                    for (int j = 0; j < vg.getChildCount(); j++) {
                                        View child = vg.getChildAt(j);
                                        if (child instanceof RadioButton) {
                                            radioButton = (RadioButton) child;
                                            break;
                                        }
                                    }
                                }
                                
                                // Apply white tint if found
                                if (radioButton != null) {
                                    ColorStateList whiteStateList = ColorStateList.valueOf(Color.WHITE);
                                    
                                    // Use appropriate method based on API level
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        radioButton.setButtonTintList(whiteStateList);
                                    } else {
                                        // For older versions, we can try reflection or a different approach
                                        try {
                                            Field buttonDrawable = RadioButton.class.getDeclaredField("mButtonDrawable");
                                            buttonDrawable.setAccessible(true);
                                            Drawable drawable = (Drawable) buttonDrawable.get(radioButton);
                                            if (drawable != null) {
                                                drawable.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
                                            }
                                        } catch (Exception e) {
                                            Log.e(TAG, "Failed to tint radio button via reflection: " + e.getMessage());
                                        }
                                    }
                                    
                                    // Also set the radio button's text color
                                    radioButton.setTextColor(Color.WHITE);
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to tint radio buttons after delay: " + e.getMessage());
                    }
                }, 100); // Short delay to ensure views are laid out
            } catch (Exception e) {
                Log.e(TAG, "Failed to schedule radio button tinting: " + e.getMessage());
            }
        }
        
        // Apply theme-specific button colors
        setDialogButtonColors(dialog);
        
        // Highlight the Save button with blue for better visibility in Faded Night theme
        if (isFadedNightTheme && dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.parseColor("#4285F4"));
        }
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
            
            // Explicitly update button appearances
            updateDeleteButtonAppearance(false);
            updateRestoreButtonAppearance(false);
            
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

    /**
     * Checks if app lock is enabled and shows the unlock dialog if needed
     */
    private void checkAppLock() {
        if (getContext() == null) return;
        
        sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());
        boolean isAppLockEnabled = sharedPreferencesManager.isAppLockEnabled();
        
        if (isAppLockEnabled && !isUnlocked && AppLock.isEnrolled(requireContext())) {
            // We'll show unlock dialog and handle visibility in callbacks
            new UnlockDialogBuilder(requireActivity())
                .onUnlocked(() -> {
                    // Show content when unlocked
                    isUnlocked = true;
                    // Make content visible after successful unlock
                    if (getView() != null) {
                         getView().findViewById(R.id.constraint_layout_root).setVisibility(View.VISIBLE);
                    }
                })
                .onCanceled(() -> {
                    // Close the trash fragment if unlock is canceled
                    if (getActivity() instanceof MainActivity) {
                        MainActivity mainActivity = (MainActivity) getActivity();
                        View overlayContainer = mainActivity.findViewById(R.id.overlay_fragment_container);
                        
                        if (overlayContainer != null) {
                            // Animate fading out
                            overlayContainer.animate()
                                .alpha(0f)
                                .setDuration(250)
                                .withEndAction(() -> {
                                    overlayContainer.setVisibility(View.GONE);
                                    overlayContainer.setAlpha(1f);
                                })
                                .start();
                        }
                    }
                })
                .show();
        } else {
            // If no lock needed, make content visible immediately
            if (getView() != null) {
                 getView().findViewById(R.id.constraint_layout_root).setVisibility(View.VISIBLE);
            }
        }
    }
    
    @Override
    public void onPause() {
        super.onPause();
        // Reset unlock state when leaving the fragment
        isUnlocked = false;
    }

    // TODO: Create TrashAdapter class
    // TODO: Implement logic for restore, permanent delete, empty all
    // TODO: Implement auto-deletion of files older than 30 days (perhaps in TrashManager and called periodically or on fragment load)

    // Add a helper method for themedDialogBuilder similar to other fragments
    private MaterialAlertDialogBuilder themedDialogBuilder(Context context) {
        int dialogTheme = R.style.ThemeOverlay_FadCam_Dialog;
        
        // Check the current theme
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME, com.fadcam.Constants.DEFAULT_APP_THEME);
        if ("Snow Veil".equals(currentTheme)) {
            dialogTheme = R.style.ThemeOverlay_FadCam_SnowVeil_Dialog;
        } else if ("Crimson Bloom".equals(currentTheme)) {
            dialogTheme = R.style.ThemeOverlay_FadCam_Red_Dialog;
        } else if ("Faded Night".equals(currentTheme)) {
            dialogTheme = R.style.ThemeOverlay_FadCam_Amoled_MaterialAlertDialog;
        }
        
        return new MaterialAlertDialogBuilder(context, dialogTheme);
    }
    
    /**
     * Sets dialog button colors based on theme
     * @param dialog The dialog whose buttons need color adjustment
     */
    private void setDialogButtonColors(AlertDialog dialog) {
        if (dialog == null) return;
        
        // Check current theme
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME, com.fadcam.Constants.DEFAULT_APP_THEME);
        boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
        boolean isFadedNightTheme = "Faded Night".equals(currentTheme);
        
        if (isSnowVeilTheme) {
            // Set black text color for buttons in Snow Veil theme
            if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.BLACK);
            }
            if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null) {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.BLACK);
            }
            if (dialog.getButton(AlertDialog.BUTTON_NEUTRAL) != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.BLACK);
            }
        } else if (isFadedNightTheme) {
            // Set white text color for Faded Night theme buttons
            if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
            }
            if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null) {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
            }
            if (dialog.getButton(AlertDialog.BUTTON_NEUTRAL) != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.WHITE);
            }
        }
    }
} 