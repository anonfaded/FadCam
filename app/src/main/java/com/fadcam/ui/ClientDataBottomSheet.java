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
                activeClientsLabel.setText("Cloud Viewers");
            }
            activeClientsText.setText(String.valueOf(cloudViewers));
            
            // Hide RecyclerView, show privacy message
            recyclerView.setVisibility(View.GONE);
            emptyStateText.setVisibility(View.VISIBLE);
            emptyStateText.setText("🔒 Per-viewer breakdown not available in cloud mode\n(Zero-log privacy policy)");
        } else {
            // LOCAL MODE: Show per-client breakdown
            if (activeClientsLabel != null) {
                activeClientsLabel.setText("Active Clients");
            }
            
            List<ClientMetrics> clientMetrics = manager.getAllClientMetrics();
            activeClientsText.setText(String.valueOf(clientMetrics.size()));
            
            if (clientMetrics.isEmpty()) {
                recyclerView.setVisibility(View.GONE);
                emptyStateText.setVisibility(View.VISIBLE);
                emptyStateText.setText("No clients connected");
            } else {
                recyclerView.setVisibility(View.VISIBLE);
                emptyStateText.setVisibility(View.GONE);
                adapter.setData(clientMetrics);
            }
        }
    }
}
