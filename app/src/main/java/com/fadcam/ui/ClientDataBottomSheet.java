package com.fadcam.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.R;
import com.fadcam.streaming.CloudStreamUploader;
import com.fadcam.streaming.RemoteStreamManager;
import com.fadcam.streaming.model.ClientMetrics;
import com.fadcam.ui.adapter.ClientDataAdapter;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.List;

/**
 * Bottom sheet showing detailed per-client data usage.
 * In cloud mode, shows aggregate stats only (privacy-preserving).
 * Follows MVVM architecture with reactive data updates.
 */
public class ClientDataBottomSheet extends BottomSheetDialogFragment {
    
    private TextView totalDataText;
    private TextView activeClientsText;
    private TextView activeClientsLabel;
    private RecyclerView recyclerView;
    private TextView emptyStateText;
    private ClientDataAdapter adapter;
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_client_data_usage, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        totalDataText = view.findViewById(R.id.total_data_served_text);
        activeClientsText = view.findViewById(R.id.active_clients_count_text);
        activeClientsLabel = view.findViewById(R.id.active_clients_label);
        recyclerView = view.findViewById(R.id.clients_data_recycler);
        emptyStateText = view.findViewById(R.id.empty_state_text);
        
        // Setup RecyclerView
        adapter = new ClientDataAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);
        
        // Load data
        loadClientData();
    }
    
    private void loadClientData() {
        RemoteStreamManager manager = RemoteStreamManager.getInstance();
        
        // Check if we're in cloud mode
        boolean isCloudMode = requireContext() != null && 
            CloudStreamUploader.getInstance(requireContext()) != null &&
            CloudStreamUploader.getInstance(requireContext()).isEnabled();
        
        // Get total data
        long totalMB = manager.getTotalDataTransferred() / (1024 * 1024);
        totalDataText.setText(totalMB + " MB");
        
        if (isCloudMode) {
            // CLOUD MODE: Show aggregate stats only
            int cloudViewers = manager.getCloudViewerCount();
            
            // Update label for cloud mode
            if (activeClientsLabel != null) {
                activeClientsLabel.setText(getString(R.string.client_cloud_viewers));
            }
            activeClientsText.setText(String.valueOf(cloudViewers));
            
            // Hide RecyclerView, show privacy message
            recyclerView.setVisibility(View.GONE);
            emptyStateText.setVisibility(View.VISIBLE);
            emptyStateText.setText("🔒 Per-viewer breakdown not available in cloud mode\n(Zero-log privacy policy)");
        } else {
            // LOCAL MODE: Show per-client breakdown
            if (activeClientsLabel != null) {
                activeClientsLabel.setText(getString(R.string.client_active_clients));
            }
            
            List<ClientMetrics> clientMetrics = manager.getAllClientMetrics();
            activeClientsText.setText(String.valueOf(clientMetrics.size()));
            
            if (clientMetrics.isEmpty()) {
                recyclerView.setVisibility(View.GONE);
                emptyStateText.setVisibility(View.VISIBLE);
                emptyStateText.setText(getString(R.string.client_no_clients));
            } else {
                recyclerView.setVisibility(View.VISIBLE);
                emptyStateText.setVisibility(View.GONE);
                adapter.setData(clientMetrics);
            }
        }
    }

    @Override
    public int getTheme() {
        return R.style.CustomBottomSheetDialogTheme;
    }

    @Override
    public android.app.Dialog onCreateDialog(Bundle savedInstanceState) {
        android.app.Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((com.google.android.material.bottomsheet.BottomSheetDialog) dialog)
                .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackgroundResource(R.drawable.picker_bottom_sheet_dark_gradient_bg);
            }
        });
        if (dialog.getWindow() != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            dialog.getWindow().setNavigationBarColor(android.graphics.Color.BLACK);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                dialog.getWindow().setNavigationBarContrastEnforced(false);
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                int flags = dialog.getWindow().getDecorView().getSystemUiVisibility();
                dialog.getWindow().getDecorView().setSystemUiVisibility(
                    flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                );
            }
        }
        return dialog;
    }
}
