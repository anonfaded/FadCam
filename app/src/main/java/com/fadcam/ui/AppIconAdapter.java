package com.fadcam.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.Constants;
import com.fadcam.R;

public class AppIconAdapter extends RecyclerView.Adapter<AppIconAdapter.IconViewHolder> {

    private final Context context;
    private final String[] iconNames;
    private final int[] iconResources;
    private final String[] iconKeys;
    private final String currentIcon;
    private final OnIconSelectedListener listener;
    private final boolean isSnowVeilTheme;
    private int lastPosition = -1;

    public interface OnIconSelectedListener {
        void onIconSelected(String iconKey, int position);
    }

    public AppIconAdapter(Context context, String[] iconNames, int[] iconResources, 
                          String[] iconKeys, String currentIcon, 
                          boolean isSnowVeilTheme, OnIconSelectedListener listener) {
        this.context = context;
        this.iconNames = iconNames;
        this.iconResources = iconResources;
        this.iconKeys = iconKeys;
        this.currentIcon = currentIcon;
        this.isSnowVeilTheme = isSnowVeilTheme;
        this.listener = listener;
    }

    @NonNull
    @Override
    public IconViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context)
                .inflate(R.layout.item_app_icon_grid, parent, false);
        return new IconViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull IconViewHolder holder, int position) {
        holder.iconName.setText(iconNames[position]);
        holder.iconPreview.setImageResource(iconResources[position]);
        
        // Set text color based on theme
        if (isSnowVeilTheme) {
            holder.iconName.setTextColor(Color.BLACK);
        } else {
            holder.iconName.setTextColor(ContextCompat.getColor(context, android.R.color.white));
        }
        
        // Set radio button checked state
        boolean isSelected = iconKeys[position].equals(currentIcon);
        holder.radioButton.setChecked(isSelected);
        
        // Set click listener
        holder.itemView.setOnClickListener(v -> {
            listener.onIconSelected(iconKeys[position], position);
        });
        
        // Apply animation to items
        setAnimation(holder.itemView, position);
    }

    private void setAnimation(View viewToAnimate, int position) {
        // Only animate new items that become visible for the first time
        if (position > lastPosition) {
            Animation animation = AnimationUtils.loadAnimation(context, R.anim.item_animation_from_bottom);
            viewToAnimate.startAnimation(animation);
            lastPosition = position;
        }
    }

    @Override
    public int getItemCount() {
        return iconNames.length;
    }

    static class IconViewHolder extends RecyclerView.ViewHolder {
        ImageView iconPreview;
        TextView iconName;
        RadioButton radioButton;

        public IconViewHolder(@NonNull View itemView) {
            super(itemView);
            iconPreview = itemView.findViewById(R.id.app_icon_preview);
            iconName = itemView.findViewById(R.id.app_icon_name);
            radioButton = itemView.findViewById(R.id.app_icon_radio_button);
        }
    }
} 