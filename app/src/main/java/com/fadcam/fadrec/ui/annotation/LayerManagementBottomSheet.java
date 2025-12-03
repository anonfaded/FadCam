package com.fadcam.fadrec.ui.annotation;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Bottom sheet for managing layers in current annotation page.
 * Allows visibility toggle, lock/unlock, opacity adjustment, and layer reordering.
 */
public class LayerManagementBottomSheet extends BottomSheetDialogFragment {
    
    private AnnotationPage page;
    private LayerAdapter adapter;
    private OnLayerActionListener listener;
    
    public interface OnLayerActionListener {
        void onLayerVisibilityChanged(int layerIndex, boolean visible);
        void onLayerLockChanged(int layerIndex, boolean locked);
        void onLayerOpacityChanged(int layerIndex, float opacity);
        void onLayerPinnedChanged(int layerIndex, boolean pinned);
        void onLayerAdded(String name);
        void onLayerDeleted(int layerIndex);
        void onLayerReordered(int fromIndex, int toIndex);
    }
    
    public static LayerManagementBottomSheet newInstance(AnnotationPage page) {
        LayerManagementBottomSheet fragment = new LayerManagementBottomSheet();
        fragment.page = page;
        return fragment;
    }
    
    public void setOnLayerActionListener(OnLayerActionListener listener) {
        this.listener = listener;
    }
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_layer_management, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        RecyclerView recyclerView = view.findViewById(R.id.recyclerLayers);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        
        adapter = new LayerAdapter();
        recyclerView.setAdapter(adapter);
        
        // Add new layer button
        view.findViewById(R.id.btnAddLayer).setOnClickListener(v -> {
            if (listener != null) {
                listener.onLayerAdded("Layer " + (page.getLayers().size() + 1));
                adapter.notifyDataSetChanged();
            }
        });
    }
    
    private class LayerAdapter extends RecyclerView.Adapter<LayerViewHolder> {
        
        @NonNull
        @Override
        public LayerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_annotation_layer, parent, false);
            return new LayerViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull LayerViewHolder holder, int position) {
            AnnotationLayer layer = page.getLayers().get(position);
            boolean isActive = page.getActiveLayerIndex() == position;
            
            holder.txtLayerName.setText(layer.getName());
            holder.txtLayerInfo.setText(layer.getObjects().size() + " objects");
            
            // Highlight active layer
            holder.itemView.setBackgroundResource(isActive ? 
                    R.drawable.annotation_layer_selected : R.drawable.settings_home_row_bg);
            
            // Visibility toggle
            holder.switchVisible.setChecked(layer.isVisible());
            holder.switchVisible.setOnCheckedChangeListener((btn, checked) -> {
                if (listener != null) {
                    listener.onLayerVisibilityChanged(position, checked);
                }
            });
            
            // Lock toggle
            holder.switchLocked.setChecked(layer.isLocked());
            holder.switchLocked.setOnCheckedChangeListener((btn, checked) -> {
                if (listener != null) {
                    listener.onLayerLockChanged(position, checked);
                }
            });
            
            // Pin toggle
            holder.switchPinned.setChecked(layer.isPinned());
            holder.switchPinned.setOnCheckedChangeListener((btn, checked) -> {
                if (listener != null) {
                    listener.onLayerPinnedChanged(position, checked);
                }
            });
            
            // Opacity slider
            holder.seekOpacity.setProgress((int)(layer.getOpacity() * 100));
            holder.txtOpacity.setText((int)(layer.getOpacity() * 100) + "%");
            holder.seekOpacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    holder.txtOpacity.setText(progress + "%");
                    if (fromUser && listener != null) {
                        listener.onLayerOpacityChanged(position, progress / 100f);
                    }
                }
                
                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}
                
                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            
            // Delete button (can't delete last layer)
            holder.btnDelete.setVisibility(page.getLayers().size() > 1 ? View.VISIBLE : View.GONE);
            holder.btnDelete.setOnClickListener(v -> {
                if (listener != null && page.getLayers().size() > 1) {
                    listener.onLayerDeleted(position);
                    adapter.notifyDataSetChanged();
                }
            });
        }
        
        @Override
        public int getItemCount() {
            return page != null ? page.getLayers().size() : 0;
        }
    }
    
    private static class LayerViewHolder extends RecyclerView.ViewHolder {
        TextView txtLayerName;
        TextView txtLayerInfo;
        SwitchCompat switchVisible;
        SwitchCompat switchLocked;
        SwitchCompat switchPinned;
        SeekBar seekOpacity;
        TextView txtOpacity;
        TextView btnDelete;
        
        LayerViewHolder(@NonNull View itemView) {
            super(itemView);
            txtLayerName = itemView.findViewById(R.id.txtLayerName);
            txtLayerInfo = itemView.findViewById(R.id.txtLayerInfo);
            switchVisible = itemView.findViewById(R.id.switchVisible);
            switchLocked = itemView.findViewById(R.id.switchLocked);
            switchPinned = itemView.findViewById(R.id.switchPinned);
            seekOpacity = itemView.findViewById(R.id.seekOpacity);
            txtOpacity = itemView.findViewById(R.id.txtOpacity);
            btnDelete = itemView.findViewById(R.id.btnDeleteLayer);
        }
    }
}
