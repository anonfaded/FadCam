package com.fadcam.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.R;
import com.fadcam.streaming.model.ClientMetrics;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView adapter for displaying client data usage metrics.
 * Follows MVVM pattern with clean separation of concerns.
 */
public class ClientDataAdapter extends RecyclerView.Adapter<ClientDataAdapter.ViewHolder> {
    
    private List<ClientMetrics> clientMetrics = new ArrayList<>();
    
    public void setData(List<ClientMetrics> metrics) {
        this.clientMetrics = metrics != null ? metrics : new ArrayList<>();
        notifyDataSetChanged();
    }
    
    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.client_data_item, parent, false);
        return new ViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ClientMetrics metrics = clientMetrics.get(position);
        holder.bind(metrics);
    }
    
    @Override
    public int getItemCount() {
        return clientMetrics.size();
    }
    
    static class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView ipText;
        private final TextView statusBadge;
        private final TextView dataServedText;
        private final TextView durationText;
        private final TextView fragmentsText;
        
        ViewHolder(View itemView) {
            super(itemView);
            ipText = itemView.findViewById(R.id.client_ip_text);
            statusBadge = itemView.findViewById(R.id.client_status_badge);
            dataServedText = itemView.findViewById(R.id.client_data_served_text);
            durationText = itemView.findViewById(R.id.client_duration_text);
            fragmentsText = itemView.findViewById(R.id.client_fragments_text);
        }
        
        void bind(ClientMetrics metrics) {
            ipText.setText(metrics.getIpAddress());
            
            // Status badge
            boolean isActive = metrics.isActive();
            statusBadge.setText(isActive ? "Active" : "Inactive");
            statusBadge.setBackgroundResource(
                isActive ? R.drawable.badge_bg_green : R.drawable.badge_bg_gray
            );
            
            // Data served
            long mb = metrics.getTotalMBServed();
            if (mb > 0) {
                dataServedText.setText(mb + " MB");
            } else {
                long kb = metrics.getTotalBytesServed() / 1024;
                dataServedText.setText(kb + " KB");
            }
            
            // Session duration
            long seconds = metrics.getSessionDurationSeconds();
            String duration;
            if (seconds < 60) {
                duration = seconds + "s";
            } else if (seconds < 3600) {
                duration = (seconds / 60) + "m";
            } else {
                duration = (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
            }
            durationText.setText(duration);
            
            // Fragments served
            fragmentsText.setText(String.valueOf(metrics.getFragmentsServed()));
        }
    }
}
