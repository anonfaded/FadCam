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
import com.fadcam.service.FileOperationService;
import com.fadcam.ui.InputActionBottomSheetFragment;
import com.fadcam.R;
import com.fadcam.model.TrashItem;
import com.fadcam.utils.TrashManager;
import com.fadcam.SharedPreferencesManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.JSONObject;
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
// Picker bottom sheet imports
import com.fadcam.ui.picker.OptionItem;
import com.fadcam.ui.picker.PickerBottomSheetFragment;

public class TrashFragment extends BaseFragment implements TrashAdapter.OnTrashItemInteractionListener {

    private static final String TAG = "TrashFragment";
    private RecyclerView recyclerViewTrashItems;
    private TrashAdapter trashAdapter;
    private List<TrashItem> trashItems = new ArrayList<>();
    private Button buttonRestoreSelected;
    private Button buttonDeleteSelectedPermanently;
    private Button buttonEmptyAllTrash;

    private TextView textViewEmptyTrash;
    private TextView titleText;
    private View emptyTrashLayout;
    private AlertDialog restoreProgressDialog;
    private ExecutorService executorService;
    private TextView tvAutoDeleteInfo;
    private SharedPreferencesManager sharedPreferencesManager;
    private boolean isInSelectionMode = false;
    private View selectAllContainer;
    private ImageView selectAllCheck;
    private ImageView selectAllBg;
    private ImageView settingsIcon;

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
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
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
                Toast.makeText(getContext(), getString(R.string.trash_auto_deleted_toast, autoDeletedCount),
                        Toast.LENGTH_LONG).show();
            }
        }
        loadTrashItems();
    }

    private void initializeViews(@NonNull View view) {
        // -------------- Fix Start for this method(initializeViews)-----------
        ImageView backButton = view.findViewById(R.id.back_button);
        if (backButton != null) {
            backButton.setOnClickListener(v -> {
                // -------------- Fix Start for this method(backButtonOnClick)-----------
                // If we're in selection mode, exit it instead of leaving the screen
                if (isInSelectionMode()) {
                    exitSelectionMode();
                    return;
                }
                // Otherwise dismiss the overlay directly to avoid cascading back logic
                try { com.fadcam.ui.OverlayNavUtil.dismiss(requireActivity()); } catch (Throwable ignored) {}
                // -------------- Fix Ended for this method(backButtonOnClick)-----------
            });
        }
        
        // Setup menu button click listener
        ImageView menuButton = view.findViewById(R.id.action_trash_auto_delete_settings);
        if (menuButton != null) {
            menuButton.setOnClickListener(v -> showAutoDeleteBottomSheet());
        }
        // -------------- Fix Ended for this method(initializeViews)-----------
        titleText = view.findViewById(R.id.title_text);
        recyclerViewTrashItems = view.findViewById(R.id.recycler_view_trash_items);
        buttonRestoreSelected = view.findViewById(R.id.button_restore_selected);
        buttonDeleteSelectedPermanently = view.findViewById(R.id.button_delete_selected_permanently);
        buttonEmptyAllTrash = view.findViewById(R.id.button_empty_all_trash);
        textViewEmptyTrash = view.findViewById(R.id.empty_trash_text_view);
        emptyTrashLayout = view.findViewById(R.id.empty_trash_layout);
        tvAutoDeleteInfo = view.findViewById(R.id.tvAutoDeleteInfo);
    selectAllContainer = view.findViewById(R.id.action_select_all_container);
    selectAllCheck = view.findViewById(R.id.action_select_all_check);
    selectAllBg = view.findViewById(R.id.action_select_all_bg);
    settingsIcon = view.findViewById(R.id.action_trash_auto_delete_settings);

        // Wire header select-all container click
    if (selectAllContainer != null) {
            selectAllContainer.setOnClickListener(v -> {
                if (trashAdapter == null) return;
                if (!trashAdapter.isAllSelected() && !trashItems.isEmpty()) {
                    trashAdapter.selectAll();
            if (selectAllCheck != null) {
                        selectAllCheck.setVisibility(View.VISIBLE);
                        selectAllCheck.setAlpha(1f);
                        try {
                            androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat avd =
                                    androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat.create(requireContext(), R.drawable.avd_check_draw);
                            if (avd != null) {
                                selectAllCheck.setImageDrawable(avd);
                                avd.start();
                            }
                        } catch (Exception e) {
                            selectAllCheck.setScaleX(0.85f);
                            selectAllCheck.setScaleY(0.85f);
                            selectAllCheck.setAlpha(0f);
                            selectAllCheck.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).start();
                        }
                    }
                } else {
                    if (trashAdapter.getSelectedItemsCount() > 0) {
                        trashAdapter.clearSelections();
                    }
                    if (selectAllCheck != null) {
                        selectAllCheck.animate().alpha(0f).setDuration(160).withEndAction(() -> {
                            selectAllCheck.setVisibility(View.INVISIBLE);
                            // Clear drawable to reset AVD state for next time
                            selectAllCheck.setImageDrawable(null);
                        }).start();
                    }
                }
                updateActionButtonsState();
            });
        }

    // Ensure initial visibility: settings icon shown, select-all hidden
    if (settingsIcon != null) settingsIcon.setVisibility(View.VISIBLE);
    if (selectAllContainer != null) selectAllContainer.setVisibility(View.GONE);
    if (selectAllBg != null) selectAllBg.setVisibility(View.INVISIBLE);
    if (selectAllCheck != null) selectAllCheck.setVisibility(View.INVISIBLE);

    setupRecyclerView();
    setupButtonListeners();
        updateAutoDeleteInfoText();
    }

    private void setupRecyclerView() {
        if (getContext() == null)
            return;
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
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME,
                com.fadcam.Constants.DEFAULT_APP_THEME);
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
                Toast.makeText(getContext(), getString(R.string.trash_no_items_selected_toast), Toast.LENGTH_SHORT)
                        .show();
                return;
            }
            showRestoreDestinationPicker(selectedItems);
        });

        buttonDeleteSelectedPermanently.setOnClickListener(v -> {
            List<TrashItem> selectedItems = trashAdapter.getSelectedItems();
            if (selectedItems.isEmpty()) {
                Toast.makeText(getContext(), getString(R.string.trash_empty_toast_message), Toast.LENGTH_SHORT).show();
                return;
            }

            showDeleteConfirmationSheet(selectedItems);
        });

        buttonEmptyAllTrash.setOnClickListener(v -> {
            if (trashItems.isEmpty()) {
                Toast.makeText(getContext(), getString(R.string.trash_empty_toast_message), Toast.LENGTH_SHORT).show();
                return;
            }

            showEmptyAllConfirmationSheet();
        });
        updateActionButtonsState(); // Initial state
    }

    private void loadTrashItems() {
        if (getContext() == null)
            return;
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
        // Example: enable buttons only if items are selected, or if trash is not empty
        // for "Empty All"
        boolean anySelected = trashAdapter != null && trashAdapter.getSelectedItemsCount() > 0;
        buttonRestoreSelected.setEnabled(anySelected);
        buttonDeleteSelectedPermanently.setEnabled(anySelected);
        buttonEmptyAllTrash.setEnabled(!trashItems.isEmpty());

        // Update the delete and restore button appearances
        updateDeleteButtonAppearance(anySelected);
        updateRestoreButtonAppearance(anySelected);
    }

    /**
     * Updates the appearance of the delete button based on whether items are
     * selected
     * 
     * @param anySelected true if any items are selected, false otherwise
     */
    private void updateDeleteButtonAppearance(boolean anySelected) {
        if (buttonDeleteSelectedPermanently == null)
            return;

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
            String currentThemeForButton = sharedPreferencesManager.sharedPreferences
                    .getString(com.fadcam.Constants.PREF_APP_THEME, com.fadcam.Constants.DEFAULT_APP_THEME);

            // Always use a muted red color for the disabled delete button
            int disabledRedColor = ContextCompat.getColor(requireContext(), R.color.colorErrorDisabled);
            buttonDeleteSelectedPermanently.setTextColor(disabledRedColor);
        }
    }

    /**
     * Updates the appearance of the restore button based on whether items are
     * selected
     * 
     * @param anySelected true if any items are selected, false otherwise
     */
    private void updateRestoreButtonAppearance(boolean anySelected) {
        if (buttonRestoreSelected == null)
            return;

        if (anySelected) {
            // Items selected - button should be fully visible and enabled
            buttonRestoreSelected.setEnabled(true);
            buttonRestoreSelected.setAlpha(1.0f);

            // Set proper color based on theme
            String currentTheme = sharedPreferencesManager.sharedPreferences
                    .getString(com.fadcam.Constants.PREF_APP_THEME, com.fadcam.Constants.DEFAULT_APP_THEME);
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
            String currentTheme = sharedPreferencesManager.sharedPreferences
                    .getString(com.fadcam.Constants.PREF_APP_THEME, com.fadcam.Constants.DEFAULT_APP_THEME);
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
            if (emptyTrashLayout != null)
                emptyTrashLayout.setVisibility(View.VISIBLE);
        } else {
            recyclerViewTrashItems.setVisibility(View.VISIBLE);
            if (emptyTrashLayout != null)
                emptyTrashLayout.setVisibility(View.GONE);
        }
    }

    @Override
    public void onItemSelectedStateChanged(boolean anySelected) {
        isInSelectionMode = anySelected;

        // Update button states
        buttonRestoreSelected.setEnabled(anySelected);
        buttonDeleteSelectedPermanently.setEnabled(anySelected);

        // Update toolbar title
        // -------------- Fix Start for this method(updateToolbarTitle)-----------
        if (titleText != null) {
            if (isInSelectionMode && trashAdapter != null) {
                int selectedCount = trashAdapter.getSelectedItemsCount();
                titleText.setText(selectedCount + " selected");
            } else {
                titleText.setText(getString(R.string.trash_fragment_title_text));
            }
        }
        // -------------- Fix Ended for this method(updateToolbarTitle)-----------

        // Show select-all slot; keep container present to preserve toolbar height.
        boolean inSel = isInSelectionMode && !trashItems.isEmpty();
        if (selectAllContainer != null) selectAllContainer.setVisibility(inSel ? View.VISIBLE : View.GONE);
        if (selectAllBg != null) selectAllBg.setVisibility(inSel ? View.VISIBLE : View.INVISIBLE);
        if (selectAllCheck != null) {
            boolean allSel = inSel && trashAdapter != null && trashAdapter.isAllSelected();
            if (allSel) {
                // Ensure AVD starts fresh each time all becomes selected
                selectAllCheck.setAlpha(1f);
                selectAllCheck.setVisibility(View.VISIBLE);
                androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat avd =
                        androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat.create(requireContext(), R.drawable.avd_check_draw);
                if (avd != null) {
                    selectAllCheck.setImageDrawable(avd);
                    avd.start();
                }
            } else {
                // Hide tick but keep empty box background
                selectAllCheck.setAlpha(0f);
                selectAllCheck.setVisibility(inSel ? View.INVISIBLE : View.GONE);
                selectAllCheck.setImageDrawable(null);
            }
        }
        if (settingsIcon != null) settingsIcon.setVisibility(inSel ? View.GONE : View.VISIBLE);

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
        if (getActivity() == null || getContext() == null)
            return;
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
                String currentTheme = sharedPreferencesManager.sharedPreferences
                        .getString(com.fadcam.Constants.PREF_APP_THEME, com.fadcam.Constants.DEFAULT_APP_THEME);
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
        if (getActivity() == null)
            return;
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
            // -------------- Fix Start for this method(updateSelectionUI)-----------
            if (titleText != null && isInSelectionMode) {
                int selectedCount = trashAdapter.getSelectedItemsCount();
                titleText.setText(selectedCount + " selected");
            } else if (titleText != null) {
                titleText.setText(getString(R.string.trash_fragment_title_text));
            }
            // -------------- Fix Ended for this method(updateSelectionUI)-----------

            // Update select all checkbox state
            updateSelectAllCheckboxState();

            // Show/hide header select all container
            if (selectAllContainer != null) selectAllContainer.setVisibility(isInSelectionMode ? View.VISIBLE : View.GONE);
            if (selectAllBg != null) selectAllBg.setVisibility(isInSelectionMode ? View.VISIBLE : View.INVISIBLE);
            if (selectAllCheck != null) {
                boolean allSel = isInSelectionMode && trashAdapter != null && trashAdapter.isAllSelected();
                if (allSel) {
                    selectAllCheck.setAlpha(1f);
                    selectAllCheck.setVisibility(View.VISIBLE);
                    androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat avd =
                            androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat.create(requireContext(), R.drawable.avd_check_draw);
                    if (avd != null) {
                        selectAllCheck.setImageDrawable(avd);
                        avd.start();
                    }
                } else {
                    selectAllCheck.setAlpha(0f);
                    selectAllCheck.setVisibility(View.INVISIBLE);
                    selectAllCheck.setImageDrawable(null);
                }
            }
            if (settingsIcon != null) settingsIcon.setVisibility(isInSelectionMode ? View.GONE : View.VISIBLE);
            if (settingsIcon != null) {
                settingsIcon.setVisibility(isInSelectionMode ? View.GONE : View.VISIBLE);
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
            // -------------- Fix Start for this method(onOptionsItemSelected:routeToBottomSheet)-----------
            showAutoDeleteBottomSheet();
            // -------------- Fix Ended for this method(onOptionsItemSelected:routeToBottomSheet)-----------
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Shows the unified bottom sheet picker for Trash auto-delete policy, replacing the legacy dialog.
     */
    private void showAutoDeleteBottomSheet() {
        if (getContext() == null || sharedPreferencesManager == null) return;

        // -------------- Fix Start for this method(showAutoDeleteBottomSheet)-----------
        // Define options
        final String[] ids = new String[]{
                "immediate", "1h", "5h", "10h", "1d", "7d", "30d", "60d", "90d", "never"
        };
        final int[] minutes = new int[]{
                0,
                60,
                5 * 60,
                10 * 60,
                1 * 24 * 60,
                7 * 24 * 60,
                30 * 24 * 60,
                60 * 24 * 60,
                90 * 24 * 60,
                SharedPreferencesManager.TRASH_AUTO_DELETE_NEVER
        };

        ArrayList<OptionItem> items = new ArrayList<>();
        items.add(new OptionItem("immediate", getString(R.string.auto_delete_immediate)));
        items.add(new OptionItem("1h", getString(R.string.auto_delete_1_hour)));
        items.add(new OptionItem("5h", getString(R.string.auto_delete_5_hours)));
        items.add(new OptionItem("10h", getString(R.string.auto_delete_10_hours)));
        items.add(new OptionItem("1d", getString(R.string.auto_delete_1_day)));
        items.add(new OptionItem("7d", getString(R.string.auto_delete_7_days)));
        items.add(new OptionItem("30d", getString(R.string.auto_delete_30_days)));
        items.add(new OptionItem("60d", getString(R.string.auto_delete_60_days)));
        items.add(new OptionItem("90d", getString(R.string.auto_delete_90_days)));
        items.add(new OptionItem("never", getString(R.string.auto_delete_never)));

        // Determine selectedId from current setting
        int current = sharedPreferencesManager.getTrashAutoDeleteMinutes();
        String selectedId = null;
        for (int i = 0; i < minutes.length; i++) {
            if (minutes[i] == current) { selectedId = ids[i]; break; }
        }
        if (selectedId == null) selectedId = "never"; // default

        // Register result listener
        final String resultKey = "trash_auto_delete_picker";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (requestKey, result) -> {
            if (!resultKey.equals(requestKey)) return;
            String sel = result.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (sel == null) return;
            Integer newMinutes = null;
            for (int i = 0; i < ids.length; i++) {
                if (ids[i].equals(sel)) { newMinutes = minutes[i]; break; }
            }
            if (newMinutes == null) return;

            sharedPreferencesManager.setTrashAutoDeleteMinutes(newMinutes);
            updateAutoDeleteInfoText();

            boolean itemsWereAutoDeleted = false;
            if (getContext() != null) {
                int autoDeletedCount = TrashManager.autoDeleteExpiredItems(getContext(), newMinutes);
                if (autoDeletedCount > 0) {
                    Toast.makeText(getContext(), getString(R.string.trash_auto_deleted_toast, autoDeletedCount), Toast.LENGTH_LONG).show();
                    itemsWereAutoDeleted = true;
                }
            }
            loadTrashItems();
            if (trashAdapter != null && !itemsWereAutoDeleted) {
                trashAdapter.notifyDataSetChanged();
            }
        });

        // Helper text if available
        String helper;
        try {
            helper = getString(R.string.trash_auto_delete_picker_helper);
        } catch (Exception e) {
            helper = null;
        }

        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstance(
                getString(R.string.auto_delete_dialog_title), items, selectedId, resultKey, helper
        );
        sheet.show(getParentFragmentManager(), "TrashAutoDeletePicker");
        // -------------- Fix Ended for this method(showAutoDeleteBottomSheet)-----------
    }

    private void showAutoDeleteSettingsDialog() {
        if (getContext() == null || sharedPreferencesManager == null) {
            Log.e(TAG, "Cannot show auto-delete settings dialog, context or prefs manager is null.");
            return;
        }

        // ----- Fix Start for this method(showAutoDeleteSettingsDialog)-----
        final String[] items = {
                getString(R.string.auto_delete_immediate), // new immediate option
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
                0, // Immediate
                60, // 1 Hour
                5 * 60, // 5 Hours
                10 * 60, // 10 Hours
                1 * 24 * 60, // 1 Day
                7 * 24 * 60, // 7 Days
                30 * 24 * 60, // 30 Days
                60 * 24 * 60, // 60 Days
                90 * 24 * 60, // 90 Days
                SharedPreferencesManager.TRASH_AUTO_DELETE_NEVER
        };
        // ----- Fix Ended for this method(showAutoDeleteSettingsDialog)-----

        int currentSettingMinutes = sharedPreferencesManager.getTrashAutoDeleteMinutes();
        int checkedItem = -1;

        for (int i = 0; i < valuesInMinutes.length; i++) {
            if (valuesInMinutes[i] == currentSettingMinutes) {
                checkedItem = i;
                break;
            }
        }

        // Determine text color based on theme
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME,
                com.fadcam.Constants.DEFAULT_APP_THEME);
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
                if (text1 != null)
                    text1.setTextColor(textColor);
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
                                Toast.makeText(getContext(),
                                        getString(R.string.trash_auto_deleted_toast, autoDeletedCount),
                                        Toast.LENGTH_LONG).show();
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
                                            Field buttonDrawable = RadioButton.class
                                                    .getDeclaredField("mButtonDrawable");
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

        // Highlight the Save button with blue for better visibility in Faded Night
        // theme
        if (isFadedNightTheme && dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.parseColor("#4285F4"));
        }
    }

    private void updateAutoDeleteInfoText() {
        if (tvAutoDeleteInfo == null || sharedPreferencesManager == null || getContext() == null)
            return;

        int totalMinutes = sharedPreferencesManager.getTrashAutoDeleteMinutes();

        // ----- Fix Start for this method(updateAutoDeleteInfoText)-----
        if (totalMinutes == 0) {
            tvAutoDeleteInfo.setText(getString(R.string.trash_auto_delete_info_immediate));
        } else if (totalMinutes == SharedPreferencesManager.TRASH_AUTO_DELETE_NEVER) {
            tvAutoDeleteInfo.setText(getString(R.string.trash_auto_delete_info_manual));
        } else if (totalMinutes < 60) { // Less than an hour, show in minutes (though current options are >= 1 hour)
            // This case isn't strictly needed with current options but good for future
            // flexibility
            tvAutoDeleteInfo.setText(String.format(Locale.getDefault(),
                    "Items are automatically deleted after %d minutes.", totalMinutes));
        } else if (totalMinutes < (24 * 60)) { // Less than a day, show in hours
            int hours = totalMinutes / 60;
            tvAutoDeleteInfo
                    .setText(getResources().getQuantityString(R.plurals.trash_auto_delete_info_hours, hours, hours));
        } else { // Show in days
            int days = totalMinutes / (24 * 60);
            tvAutoDeleteInfo
                    .setText(getResources().getQuantityString(R.plurals.trash_auto_delete_info_days, days, days));
        }
        // ----- Fix Ended for this method(updateAutoDeleteInfoText)-----
    }

    @Override
    protected boolean onBackPressed() {
        // If in selection mode, exit selection mode
        if (isInSelectionMode()) {
            exitSelectionMode();
            return true; // consumed
        }
    // Dismiss the overlay explicitly; don't propagate to activity
    try { com.fadcam.ui.OverlayNavUtil.dismiss(requireActivity()); } catch (Throwable ignored) {}
        return true; // consumed
    }

    /**
     * Checks if the fragment is currently in selection mode
     * 
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
            // -------------- Fix Start for this method(exitSelectionMode)-----------
            // Reset any UI elements that change in selection mode
            if (titleText != null) {
                titleText.setText(getString(R.string.trash_fragment_title_text));
            }
            // -------------- Fix Ended for this method(exitSelectionMode)-----------

            // Explicitly update button appearances
            updateDeleteButtonAppearance(false);
            updateRestoreButtonAppearance(false);

            // Hide header select all
            if (selectAllContainer != null) {
                selectAllContainer.setVisibility(View.GONE);
            }
            if (selectAllBg != null) selectAllBg.setVisibility(View.INVISIBLE);
            if (selectAllCheck != null) {
                selectAllCheck.setAlpha(0f);
                selectAllCheck.setVisibility(View.INVISIBLE);
                selectAllCheck.setImageDrawable(null);
            }
        }
    }

    private void updateSelectAllCheckboxState() {
    if (selectAllContainer == null || trashAdapter == null) return;
    boolean inSel2 = isInSelectionMode && !trashItems.isEmpty();
    selectAllContainer.setVisibility(inSel2 ? View.VISIBLE : View.GONE);
    if (selectAllBg != null) selectAllBg.setVisibility(inSel2 ? View.VISIBLE : View.INVISIBLE);
    if (selectAllCheck != null) {
        boolean all = inSel2 && trashAdapter.isAllSelected();
        if (all) {
            selectAllCheck.setAlpha(1f);
            selectAllCheck.setVisibility(View.VISIBLE);
        } else {
            selectAllCheck.setAlpha(0f);
            selectAllCheck.setVisibility(inSel2 ? View.INVISIBLE : View.GONE);
            selectAllCheck.setImageDrawable(null);
        }
    }
    }

    /**
     * Checks if app lock is enabled and shows the unlock dialog if needed
     */
    private void checkAppLock() {
        if (getContext() == null)
            return;

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
    // TODO: Implement auto-deletion of files older than 30 days (perhaps in
    // TrashManager and called periodically or on fragment load)

    // Add a helper method for themedDialogBuilder similar to other fragments
    private MaterialAlertDialogBuilder themedDialogBuilder(Context context) {
        int dialogTheme = R.style.ThemeOverlay_FadCam_Dialog;

        // Check the current theme
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME,
                com.fadcam.Constants.DEFAULT_APP_THEME);
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
     * 
     * @param dialog The dialog whose buttons need color adjustment
     */
    private void setDialogButtonColors(AlertDialog dialog) {
        if (dialog == null)
            return;

        // Check current theme
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME,
                com.fadcam.Constants.DEFAULT_APP_THEME);
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

    /**
     * Shows a picker dialog to select restore destination
     * 
     * @param selectedItems The list of selected trash items to restore
     */
    private void showRestoreDestinationPicker(List<TrashItem> selectedItems) {
        if (getContext() == null) return;

        // Get available restore destinations
        List<TrashManager.RestoreDestination> destinations = TrashManager.getAvailableRestoreDestinations(selectedItems);
        
        // Create option items for the picker
        ArrayList<OptionItem> options = new ArrayList<>();
        
        for (TrashManager.RestoreDestination destination : destinations) {
            String title;
            String subtitle;
            
            switch (destination) {
                case DOWNLOADS_FOLDER:
                    title = getString(R.string.restore_destination_downloads);
                    subtitle = getString(R.string.restore_destination_downloads_desc);
                    break;
                case INTERNAL_STORAGE:
                    title = getString(R.string.restore_destination_internal);
                    subtitle = getString(R.string.restore_destination_internal_desc);
                    break;
                case SAF_STORAGE:
                    title = getString(R.string.restore_destination_original);
                    subtitle = getString(R.string.restore_destination_original_desc);
                    break;
                default:
                    continue;
            }
            
            options.add(OptionItem.withLigature(destination.name(), title, "folder"));
        }

        // Show picker bottom sheet
        PickerBottomSheetFragment picker = PickerBottomSheetFragment.newInstance(
                getString(R.string.restore_destination_title),
                options,
                null, // No pre-selected item
                "restore_destination_result"
        );

        // Set up result listener
        getParentFragmentManager().setFragmentResultListener("restore_destination_result", this, (requestKey, result) -> {
            String selectedId = result.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (selectedId != null) {
                TrashManager.RestoreDestination destination = TrashManager.RestoreDestination.valueOf(selectedId);
                performRestore(selectedItems, destination);
            }
        });

        picker.show(getParentFragmentManager(), "restore_destination_picker");
    }

    /**
     * Performs the actual restore operation with the selected destination
     * 
     * @param selectedItems The list of items to restore
     * @param destination   The destination to restore to
     */
    private void performRestore(List<TrashItem> selectedItems, TrashManager.RestoreDestination destination) {
        if (getContext() == null) return;

        String destinationName;
        switch (destination) {
            case DOWNLOADS_FOLDER:
                destinationName = getString(R.string.restore_destination_downloads);
                break;
            case INTERNAL_STORAGE:
                destinationName = getString(R.string.restore_destination_internal);
                break;
            case SAF_STORAGE:
                destinationName = getString(R.string.restore_destination_original);
                break;
            default:
                destinationName = getString(R.string.restore_destination_downloads);
                break;
        }

        // Show confirmation dialog
        String message = getString(R.string.restore_confirm_message, selectedItems.size(), destinationName);
        
        AlertDialog dialog = themedDialogBuilder(requireContext())
                .setTitle(getString(R.string.restore_confirm_title))
                .setMessage(message)
                .setNegativeButton(getString(android.R.string.cancel), (dialogInterface, which) -> {
                    onRestoreFinished(false, getString(R.string.restore_cancelled));
                })
                .setPositiveButton(getString(R.string.restore_confirm_button), (dialogInterface, which) -> {
                    // Show progress notification and start background operation
                    String itemText = selectedItems.size() == 1 ? 
                        selectedItems.get(0).getOriginalDisplayName() : 
                        selectedItems.size() + " items";
                    Toast.makeText(getContext(), 
                            getString(R.string.background_restore_progress, itemText),
                            Toast.LENGTH_SHORT).show();
                    performRestoreInBackground(selectedItems, destination);
                })
                .create();

        dialog.show();
        setDialogButtonColors(dialog);

        // Color the restore button specially for Faded Night theme
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME, com.fadcam.Constants.DEFAULT_APP_THEME);
        boolean isFadedNightTheme = "Faded Night".equals(currentTheme);
        if (isFadedNightTheme && dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.parseColor("#4285F4"));
        }
    }

    /**
     * Refreshes the Records fragment after files are restored from trash
     */
    private void refreshRecordsFragment() {
        boolean refreshSuccess = false;
        
        try {
            // Method 1: Try to find RecordsFragment directly
            if (getActivity() instanceof com.fadcam.MainActivity) {
                com.fadcam.MainActivity mainActivity = (com.fadcam.MainActivity) getActivity();
                
                // Try different possible tags for ViewPager2 fragments
                String[] possibleTags = {"f0", "f1", "f2"}; // Records could be at different positions
                
                for (String tag : possibleTags) {
                    androidx.fragment.app.Fragment fragment = mainActivity.getSupportFragmentManager().findFragmentByTag(tag);
                    Log.d("TrashFragment", "Checking tag " + tag + ": " + (fragment != null ? fragment.getClass().getSimpleName() : "null"));
                    if (fragment instanceof com.fadcam.ui.RecordsFragment) {
                        ((com.fadcam.ui.RecordsFragment) fragment).refreshList();
                        Log.i("TrashFragment", "Successfully refreshed RecordsFragment with tag: " + tag);
                        refreshSuccess = true;
                        break;
                    }
                }
                
                // Method 2: Try to find by iterating through all fragments
                if (!refreshSuccess) {
                    java.util.List<androidx.fragment.app.Fragment> allFragments = mainActivity.getSupportFragmentManager().getFragments();
                    Log.d("TrashFragment", "Total fragments found: " + allFragments.size());
                    for (androidx.fragment.app.Fragment fragment : allFragments) {
                        Log.d("TrashFragment", "Fragment: " + (fragment != null ? fragment.getClass().getSimpleName() : "null"));
                        if (fragment instanceof com.fadcam.ui.RecordsFragment) {
                            ((com.fadcam.ui.RecordsFragment) fragment).refreshList();
                            Log.i("TrashFragment", "Successfully refreshed RecordsFragment by iteration.");
                            refreshSuccess = true;
                            break;
                        }
                    }
                }
                
                // Also refresh HomeFragment stats
                refreshHomeFragmentStats(mainActivity);
            }
        } catch (Exception e) {
            Log.e("TrashFragment", "Failed to refresh RecordsFragment directly", e);
        }
        
        // Method 3: Fallback to broadcast if direct method failed
        if (!refreshSuccess) {
            try {
                Intent intent = new Intent(com.fadcam.Constants.ACTION_FILES_RESTORED);
                requireContext().sendBroadcast(intent);
                Log.i("TrashFragment", "Sent ACTION_FILES_RESTORED broadcast as fallback.");
            } catch (Exception e) {
                Log.e("TrashFragment", "Failed to send fallback broadcast", e);
            }
        }
    }

    /**
     * Refreshes the HomeFragment stats widget after files are restored
     */
    private void refreshHomeFragmentStats(com.fadcam.MainActivity mainActivity) {
        try {
            // Try to find HomeFragment
            String[] possibleTags = {"f0", "f1", "f2"}; // Home could be at different positions
            boolean refreshSuccess = false;
            
            for (String tag : possibleTags) {
                androidx.fragment.app.Fragment fragment = mainActivity.getSupportFragmentManager().findFragmentByTag(tag);
                if (fragment instanceof com.fadcam.ui.HomeFragment) {
                    ((com.fadcam.ui.HomeFragment) fragment).refreshStats();
                    Log.i("TrashFragment", "Successfully refreshed HomeFragment stats with tag: " + tag);
                    refreshSuccess = true;
                    break;
                }
            }
            
            // Try iteration if tag method failed
            if (!refreshSuccess) {
                for (androidx.fragment.app.Fragment fragment : mainActivity.getSupportFragmentManager().getFragments()) {
                    if (fragment instanceof com.fadcam.ui.HomeFragment) {
                        ((com.fadcam.ui.HomeFragment) fragment).refreshStats();
                        Log.i("TrashFragment", "Successfully refreshed HomeFragment stats by iteration.");
                        refreshSuccess = true;
                        break;
                    }
                }
            }
            
            if (!refreshSuccess) {
                Log.w("TrashFragment", "Could not find HomeFragment to refresh stats after restore.");
            }
        } catch (Exception e) {
            Log.e("TrashFragment", "Failed to refresh HomeFragment stats", e);
        }
    }

    /**
     * Performs delete operation in background with progress notification
     */
    private void performDeleteInBackground(List<TrashItem> selectedItems) {
        if (getContext() == null) return;
        
        // Perform actual operation in background
        if (executorService == null || executorService.isShutdown()) {
            executorService = java.util.concurrent.Executors.newSingleThreadExecutor();
        }
        
        executorService.submit(() -> {
            boolean success = TrashManager.permanentlyDeleteItems(getContext(), selectedItems);
            
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (success) {
                        Toast.makeText(getContext(),
                                getString(R.string.trash_items_deleted_toast, selectedItems.size()),
                                Toast.LENGTH_SHORT).show();
                        loadTrashItems();
                    } else {
                        Toast.makeText(getContext(), getString(R.string.trash_error_deleting_items_toast),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    /**
     * Performs restore operation in background with progress notification
     */
    private void performRestoreInBackground(List<TrashItem> selectedItems, TrashManager.RestoreDestination destination) {
        if (getContext() == null) return;
        
        // Perform actual restore operation
        if (executorService == null || executorService.isShutdown()) {
            executorService = java.util.concurrent.Executors.newSingleThreadExecutor();
        }
        
        executorService.submit(() -> {
            boolean success = TrashManager.restoreItemsFromTrash(getContext(), selectedItems, destination);
            
            String destinationName;
            switch (destination) {
                case DOWNLOADS_FOLDER:
                    destinationName = getString(R.string.restore_destination_downloads);
                    break;
                case INTERNAL_STORAGE:
                    destinationName = getString(R.string.restore_destination_internal);
                    break;
                case SAF_STORAGE:
                    destinationName = getString(R.string.restore_destination_original);
                    break;
                default:
                    destinationName = getString(R.string.restore_destination_downloads);
                    break;
            }
            
            String resultMessage = success
                    ? getString(R.string.restore_success_message, selectedItems.size(), destinationName)
                    : getString(R.string.restore_error_message);
            
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), resultMessage, Toast.LENGTH_LONG).show();
                    
                    if (success) {
                        loadTrashItems(); // Refresh trash list
                        
                        // Add a small delay to ensure UI is ready, then refresh Records tab
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            refreshRecordsFragment();
                        }, 100);
                    }
                });
            }
        });
    }

    /**
     * Shows delete confirmation bottom sheet requiring typing "DELETE" to confirm
     */
    private void showDeleteConfirmationSheet(List<TrashItem> selectedItems) {
        if (getContext() == null) return;

        String title = getString(R.string.dialog_permanently_delete_title);
        String actionTitle = getString(R.string.universal_delete);
        String actionSubtitle = getString(R.string.dialog_permanently_delete_message, selectedItems.size());

        // Create the delete confirmation sheet that requires typing "DELETE"
        InputActionBottomSheetFragment sheet = InputActionBottomSheetFragment.newReset(
                title,
                "DELETE", // Required phrase to type
                actionTitle,
                actionSubtitle,
                R.drawable.ic_delete // Delete icon
        );

        // Set up callback for when DELETE is typed correctly
        sheet.setCallbacks(new InputActionBottomSheetFragment.Callbacks() {
            @Override
            public void onImportConfirmed(JSONObject json) {
                // Not used
            }

            @Override
            public void onResetConfirmed() {
                // Dismiss the sheet first
                sheet.dismiss();
                
                // When DELETE is typed correctly, proceed with deletion
                String itemText = selectedItems.size() == 1 ? 
                    selectedItems.get(0).getOriginalDisplayName() : 
                    selectedItems.size() + " items";
                Toast.makeText(getContext(),
                        getString(R.string.background_delete_progress, itemText),
                        Toast.LENGTH_SHORT).show();
                
                // Start delete operation in background with service notifications
                performDeleteInBackground(selectedItems);
            }
        });

        sheet.show(getParentFragmentManager(), "delete_confirmation");
    }

    /**
     * Shows empty all confirmation bottom sheet requiring typing "DELETE" to confirm
     */
    private void showEmptyAllConfirmationSheet() {
        if (getContext() == null) return;

        String title = getString(R.string.trash_dialog_empty_all_title);
        String actionTitle = getString(R.string.trash_button_empty_all_action);
        String actionSubtitle = getString(R.string.dialog_empty_all_trash_message);

        // Create the empty all confirmation sheet that requires typing "DELETE"
        InputActionBottomSheetFragment sheet = InputActionBottomSheetFragment.newReset(
                title,
                "DELETE", // Required phrase to type
                actionTitle,
                actionSubtitle,
                R.drawable.ic_delete_all // Delete all icon
        );

        // Set up callback for when DELETE is typed correctly
        sheet.setCallbacks(new InputActionBottomSheetFragment.Callbacks() {
            @Override
            public void onImportConfirmed(JSONObject json) {
                // Not used
            }

            @Override
            public void onResetConfirmed() {
                // Dismiss the sheet first
                sheet.dismiss();
                
                // When DELETE is typed correctly, proceed with emptying all trash
                if (TrashManager.emptyAllTrash(getContext())) {
                    Toast.makeText(getContext(), getString(R.string.trash_emptied_toast), Toast.LENGTH_SHORT)
                            .show();
                    loadTrashItems();
                } else {
                    Toast.makeText(getContext(), getString(R.string.trash_error_deleting_items_toast),
                            Toast.LENGTH_SHORT).show();
                }
            }
        });

        sheet.show(getParentFragmentManager(), "empty_all_confirmation");
    }
}