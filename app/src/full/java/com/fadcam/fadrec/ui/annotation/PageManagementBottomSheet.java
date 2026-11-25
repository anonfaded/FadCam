package com.fadcam.fadrec.ui.annotation;

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
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * Bottom sheet for managing annotation pages (tabs).
 * Allows switching, adding, deleting, and renaming pages.
 */
public class PageManagementBottomSheet extends BottomSheetDialogFragment {
    
    private AnnotationState state;
    private PageAdapter adapter;
    private OnPageActionListener listener;
    
    public interface OnPageActionListener {
        void onPageSelected(int index);
        void onPageAdded(String name);
        void onPageDeleted(int index);
        void onPageRenamed(int index, String newName);
    }
    
    public static PageManagementBottomSheet newInstance(AnnotationState state) {
        PageManagementBottomSheet fragment = new PageManagementBottomSheet();
        fragment.state = state;
        return fragment;
    }
    
    public void setOnPageActionListener(OnPageActionListener listener) {
        this.listener = listener;
    }
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_page_management, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        RecyclerView recyclerView = view.findViewById(R.id.recyclerPages);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        
        adapter = new PageAdapter();
        recyclerView.setAdapter(adapter);
        
        // Add new page button
        view.findViewById(R.id.btnAddPage).setOnClickListener(v -> {
            if (listener != null) {
                listener.onPageAdded("Page " + (state.getPages().size() + 1));
                adapter.notifyDataSetChanged();
            }
        });
    }
    
    private class PageAdapter extends RecyclerView.Adapter<PageViewHolder> {
        
        @NonNull
        @Override
        public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_annotation_page, parent, false);
            return new PageViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
            AnnotationPage page = state.getPages().get(position);
            boolean isActive = position == state.getActivePageIndex();
            
            holder.txtPageName.setText(page.getName());
            holder.txtPageInfo.setText(page.getLayers().size() + " layers");
            
            // Highlight active page
            holder.itemView.setBackgroundResource(isActive ? 
                    R.drawable.annotation_page_selected : R.drawable.settings_home_row_bg);
            
            // Click to switch page
            holder.itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onPageSelected(position);
                    notifyDataSetChanged();
                }
            });
            
            // Delete button
            holder.btnDelete.setVisibility(state.getPages().size() > 1 ? View.VISIBLE : View.GONE);
            holder.btnDelete.setOnClickListener(v -> {
                if (listener != null && state.getPages().size() > 1) {
                    listener.onPageDeleted(position);
                    notifyDataSetChanged();
                }
            });
        }
        
        @Override
        public int getItemCount() {
            return state != null ? state.getPages().size() : 0;
        }
    }
    
    private static class PageViewHolder extends RecyclerView.ViewHolder {
        TextView txtPageName;
        TextView txtPageInfo;
        TextView btnDelete;
        
        PageViewHolder(@NonNull View itemView) {
            super(itemView);
            txtPageName = itemView.findViewById(R.id.txtPageName);
            txtPageInfo = itemView.findViewById(R.id.txtPageInfo);
            btnDelete = itemView.findViewById(R.id.btnDeletePage);
        }
    }
}
