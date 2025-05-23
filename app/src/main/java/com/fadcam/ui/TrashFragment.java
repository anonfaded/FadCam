package com.fadcam.ui;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
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
import com.fadcam.R;
import com.fadcam.model.TrashItem;
import com.fadcam.utils.TrashManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.List;
import androidx.appcompat.app.AlertDialog;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TrashFragment extends Fragment implements TrashAdapter.OnTrashItemInteractionListener {

    private static final String TAG = "TrashFragment";
    private RecyclerView recyclerViewTrashItems;
    private TrashAdapter trashAdapter;
    private List<TrashItem> trashItems = new ArrayList<>();
    private Button buttonRestoreSelected;
    private Button buttonDeleteSelectedPermanently;
    private Button buttonEmptyAllTrash;
    private MaterialToolbar toolbar;
    private TextView textViewEmptyTrash;
    private AlertDialog restoreProgressDialog;
    private ExecutorService executorService;

    public TrashFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executorService = Executors.newSingleThreadExecutor();
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

        setupToolbar();
        setupRecyclerView();
        setupButtonListeners();

        // Auto-delete old items first, then load
        if (getContext() != null) {
            int autoDeletedCount = TrashManager.autoDeleteOldTrashItems(getContext());
            if (autoDeletedCount > 0) {
                Toast.makeText(getContext(), autoDeletedCount + " old item(s) auto-deleted from trash.", Toast.LENGTH_LONG).show();
                // Metadata is already updated by autoDeleteOldTrashItems, loadTrashItems will get the fresh list.
            }
        }
        loadTrashItems();
    }

    private void setupToolbar() {
        if (toolbar != null) {
            toolbar.setTitle("Trash");
            toolbar.setNavigationIcon(R.drawable.ic_close); // Ensure you have this drawable
            toolbar.setNavigationOnClickListener(v -> {
                try {
                    // Pop the back stack
                    if (getParentFragmentManager().getBackStackEntryCount() > 0) {
                        getParentFragmentManager().popBackStack();
                    } else {
                        // If no back stack, directly try to remove/hide (though addToBackStack should prevent this)
                        if (getActivity() != null) getActivity().onBackPressed(); 
                    }

                    // Hide the overlay container when TrashFragment is closed
                    View overlayContainer = requireActivity().findViewById(R.id.overlay_fragment_container);
                    if (overlayContainer != null) {
                        overlayContainer.setVisibility(View.GONE);
                    } else {
                        Log.w(TAG, "Could not find R.id.overlay_fragment_container to hide it.");
                    }
                } catch (Exception e) {
                    android.util.Log.e(TAG, "Toolbar navigation up failed (manual popBackStack)", e);
                }
            });
        }
    }

    private void setupRecyclerView() {
        if (getContext() == null) return;
        trashAdapter = new TrashAdapter(getContext(), trashItems, this, null);
        recyclerViewTrashItems.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerViewTrashItems.setAdapter(trashAdapter);
    }

    private void setupButtonListeners() {
        buttonRestoreSelected.setOnClickListener(v -> {
            List<TrashItem> selectedItems = trashAdapter.getSelectedItems();
            if (selectedItems.isEmpty()) {
                Toast.makeText(getContext(), "No items selected.", Toast.LENGTH_SHORT).show();
                return;
            }
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Restore Selected?")
                    .setMessage("Restore " + selectedItems.size() + " item(s) to the public Downloads/FadCam/ folder?")
                    .setNegativeButton("Cancel", (dialog, which) -> {
                        onRestoreFinished(false, "Restore cancelled.");
                    })
                    .setPositiveButton("Restore", (dialog, which) -> {
                        onRestoreStarted(selectedItems.size());
                        if (executorService == null || executorService.isShutdown()) {
                            executorService = Executors.newSingleThreadExecutor(); // Re-initialize if shutdown
                        }
                        executorService.submit(() -> {
                            boolean success = TrashManager.restoreItemsFromTrash(getContext(), selectedItems);
                            String message = success ? selectedItems.size() + " item(s) restored."
                                                     : "Error restoring items. Some files might not have been restored or gallery might not be updated. Check app permissions for storage.";
                            
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
                Toast.makeText(getContext(), "No items selected.", Toast.LENGTH_SHORT).show();
                return;
            }

            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Delete Permanently?")
                    .setMessage("Are you sure you want to permanently delete " + selectedItems.size() + " selected item(s)? This action cannot be undone.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete", (dialog, which) -> {
                        if (TrashManager.permanentlyDeleteItems(getContext(), selectedItems)) {
                            Toast.makeText(getContext(), selectedItems.size() + " item(s) deleted permanently.", Toast.LENGTH_SHORT).show();
                            loadTrashItems(); // Refresh the list
                        } else {
                            Toast.makeText(getContext(), "Error deleting items.", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
        });

        buttonEmptyAllTrash.setOnClickListener(v -> {
            if (trashItems.isEmpty()) {
                Toast.makeText(getContext(), "Trash is already empty.", Toast.LENGTH_SHORT).show();
                return;
            }
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Empty All Trash?")
                    .setMessage("Are you sure you want to permanently delete all items in the trash? This action cannot be undone.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Empty Trash", (dialog, which) -> {
                        if (TrashManager.emptyAllTrash(getContext())) {
                            Toast.makeText(getContext(), "Trash emptied.", Toast.LENGTH_SHORT).show();
                            loadTrashItems(); // Refresh the list
                        } else {
                            Toast.makeText(getContext(), "Error emptying trash.", Toast.LENGTH_SHORT).show();
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
            textViewEmptyTrash.setVisibility(View.VISIBLE);
        } else {
            recyclerViewTrashItems.setVisibility(View.VISIBLE);
            textViewEmptyTrash.setVisibility(View.GONE);
        }
    }

    @Override
    public void onItemSelectedStateChanged(boolean anySelected) {
        buttonRestoreSelected.setEnabled(anySelected);
        buttonDeleteSelectedPermanently.setEnabled(anySelected);
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
        // The main purpose is to update the enabled state of action buttons.
        if (getView() != null) { // Ensure fragment is still active
            updateActionButtonsState();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }

    // TODO: Create TrashAdapter class
    // TODO: Implement logic for restore, permanent delete, empty all
    // TODO: Implement auto-deletion of files older than 30 days (perhaps in TrashManager and called periodically or on fragment load)
} 