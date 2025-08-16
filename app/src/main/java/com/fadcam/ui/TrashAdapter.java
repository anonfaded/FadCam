package com.fadcam.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.drawable.Drawable;
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.fadcam.R;
import com.fadcam.model.TrashItem;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import com.bumptech.glide.Glide;
import android.net.Uri;
import java.io.File;
import com.fadcam.utils.TrashManager;
import android.util.Log;
import android.widget.Toast;
import com.fadcam.SharedPreferencesManager;
import java.util.concurrent.TimeUnit;
import com.fadcam.Constants;

public class TrashAdapter extends RecyclerView.Adapter<TrashAdapter.TrashViewHolder> {

    private final Context context;
    private final List<TrashItem> trashItems;
    private final List<TrashItem> selectedItems = new ArrayList<>();
    private final OnTrashItemInteractionListener interactionListener;
    private final OnTrashItemLongClickListener longClickListener; // Optional
    private final SharedPreferencesManager sharedPreferencesManager;
    private boolean isSnowVeilTheme = false;

    public interface OnTrashItemInteractionListener {
        void onItemCheckChanged(TrashItem item, boolean isChecked);
        void onItemSelectedStateChanged(boolean anySelected);
        void onRestoreStarted(int itemCount);
        void onRestoreFinished(boolean success, String message);
        void onPlayVideoRequested(TrashItem item);
    }

    public interface OnTrashItemLongClickListener {
        void onItemLongClicked(TrashItem item);
    }

    public TrashAdapter(Context context, List<TrashItem> trashItems,
                          OnTrashItemInteractionListener interactionListener,
                          OnTrashItemLongClickListener longClickListener) {
        this.context = context;
        this.trashItems = trashItems;
        this.interactionListener = interactionListener;
        this.longClickListener = longClickListener;
        
        // Get current theme for theming
        sharedPreferencesManager = SharedPreferencesManager.getInstance(context);
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        this.isSnowVeilTheme = "Snow Veil".equals(currentTheme);
    }

    @NonNull
    @Override
    public TrashViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_trash, parent, false);
        return new TrashViewHolder(view);
    }
    @Override
    public void onBindViewHolder(@NonNull TrashViewHolder holder, int position) {
        TrashItem item = trashItems.get(position);
        holder.bind(item);
        // Apply Snow Veil theme if needed (apply after normal binding)
        if (isSnowVeilTheme && holder.itemView instanceof androidx.cardview.widget.CardView) {
            ((androidx.cardview.widget.CardView) holder.itemView).setCardBackgroundColor(android.graphics.Color.WHITE);
            if (holder.tvOriginalName != null) holder.tvOriginalName.setTextColor(android.graphics.Color.BLACK);
            if (holder.tvDateTrashed != null) holder.tvDateTrashed.setTextColor(android.graphics.Color.BLACK);
            if (holder.tvOriginalLocation != null) holder.tvOriginalLocation.setTextColor(android.graphics.Color.BLACK);
        }
    }

    // Payload-aware binding so we can play an animation only when selection toggles occur.
    @Override
    public void onBindViewHolder(@NonNull TrashViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads == null || payloads.isEmpty()) {
            // Full bind
            onBindViewHolder(holder, position);
            return;
        }

        // Handle selection toggle payload specifically
        TrashItem item = trashItems.get(position);
        boolean selected = selectedItems.contains(item);

        if (payloads.contains("SELECTION_TOGGLE")) {
            if (holder.iconCheckContainer != null && holder.iconCheck != null) {
                if (selected) {
                    holder.iconCheckContainer.setVisibility(View.VISIBLE);
                    holder.iconCheck.setAlpha(1f);
                    // Create a fresh AnimatedVectorDrawableCompat and start it
                    AnimatedVectorDrawableCompat avd = AnimatedVectorDrawableCompat.create(context, R.drawable.avd_check_draw);
                    if (avd != null) {
                        holder.iconCheck.setImageDrawable(avd);
                        avd.start();
                    } else {
                        // Fallback: simple scale/pop animation
                        holder.iconCheck.setScaleX(0.7f);
                        holder.iconCheck.setScaleY(0.7f);
                        holder.iconCheck.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200).start();
                    }
                } else {
                    // Fade out the tick but keep the container visible (empty state)
                    holder.iconCheck.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                        // Clear drawable to avoid leftover AVD artifacts and keep background visible
                        holder.iconCheck.setImageDrawable(null);
                        holder.iconCheck.setAlpha(0f);
                    }).start();
                }
            }
        } else {
            // Other payloads fall back to full bind
            onBindViewHolder(holder, position);
        }
    }

    @Override
    public int getItemCount() {
        return trashItems.size();
    }

    public List<TrashItem> getSelectedItems() {
        return new ArrayList<>(selectedItems); // Return a copy
    }

    public int getSelectedItemsCount() {
        return selectedItems.size();
    }

    /**
     * Selects all items in the list
     */
    public void selectAll() {
        selectedItems.clear();
        selectedItems.addAll(trashItems);
    // Notify all items to ensure offscreen holders bind with a tick when they appear
    notifyDataSetChanged();
        if (interactionListener != null) {
            interactionListener.onItemSelectedStateChanged(!selectedItems.isEmpty());
        }
    }

    /**
     * Clears all selected items and updates the UI accordingly
     */
    public void clearSelections() {
        if (!selectedItems.isEmpty()) {
            int oldCount = selectedItems.size();
            selectedItems.clear();
            notifyDataSetChanged();
            if (interactionListener != null) {
                interactionListener.onItemSelectedStateChanged(false);
            }
        }
    }

    /**
     * Checks if all items are currently selected
     * @return true if all items are selected, false otherwise
     */
    public boolean isAllSelected() {
        return !trashItems.isEmpty() && selectedItems.size() == trashItems.size();
    }

    class TrashViewHolder extends RecyclerView.ViewHolder {
        TextView tvOriginalName;
        TextView tvDateTrashed;
        TextView tvOriginalLocation;
    View iconCheckContainer;
    ImageView iconCheck;
        ImageView imageViewThumbnail;
        TextView tvRemainingTime;

        TrashViewHolder(@NonNull View itemView) {
            super(itemView);
            imageViewThumbnail = itemView.findViewById(R.id.image_view_trash_thumbnail);
            tvOriginalName = itemView.findViewById(R.id.tv_trash_item_original_name);
            tvDateTrashed = itemView.findViewById(R.id.tv_trash_item_date_trashed);
            tvOriginalLocation = itemView.findViewById(R.id.tv_trash_item_original_location);
            iconCheckContainer = itemView.findViewById(R.id.icon_check_container);
            iconCheck = itemView.findViewById(R.id.icon_check);
            tvRemainingTime = itemView.findViewById(R.id.tv_trash_item_remaining_time);
        }

        void bind(final TrashItem item) {
            tvOriginalName.setText(item.getOriginalDisplayName());
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            tvDateTrashed.setText("Trashed: " + sdf.format(new Date(item.getDateTrashed())));
            tvOriginalLocation.setText("Original: " + (item.isFromSaf() ? "SAF Storage" : "Internal Storage"));

            if (tvRemainingTime != null && sharedPreferencesManager != null && context != null) {
                int autoDeleteMinutes = sharedPreferencesManager.getTrashAutoDeleteMinutes();
                if (autoDeleteMinutes == SharedPreferencesManager.TRASH_AUTO_DELETE_NEVER) {
                    tvRemainingTime.setText(context.getString(R.string.trash_auto_delete_info_manual));
                    tvRemainingTime.setTextColor(context.getResources().getColor(R.color.gray_text_very_light));
                } else {
                    long timeSinceTrashedMillis = System.currentTimeMillis() - item.getDateTrashed();
                    long autoDeleteTotalMillis = TimeUnit.MINUTES.toMillis(autoDeleteMinutes);
                    long remainingMillis = autoDeleteTotalMillis - timeSinceTrashedMillis;

                    if (remainingMillis <= 0) {
                        tvRemainingTime.setText(context.getString(R.string.trash_item_remaining_soon));
                        tvRemainingTime.setTextColor(context.getResources().getColor(R.color.colorError));
                    } else {
                        long remainingDays = TimeUnit.MILLISECONDS.toDays(remainingMillis);
                        if (remainingDays > 0) {
                            tvRemainingTime.setText(context.getResources().getQuantityString(R.plurals.trash_item_remaining_days, (int)remainingDays, (int)remainingDays));
                        } else {
                            long remainingHours = TimeUnit.MILLISECONDS.toHours(remainingMillis);
                            if (remainingHours > 0) {
                                tvRemainingTime.setText(context.getResources().getQuantityString(R.plurals.trash_item_remaining_hours, (int)remainingHours, (int)remainingHours));
                            } else {
                                tvRemainingTime.setText(context.getString(R.string.trash_item_remaining_soon));
                            }
                        }
                        
                        if (remainingDays == 0 && TimeUnit.MILLISECONDS.toHours(remainingMillis) < 1) {
                            tvRemainingTime.setTextColor(context.getResources().getColor(R.color.colorError));
                        } else if (remainingDays == 0 && TimeUnit.MILLISECONDS.toHours(remainingMillis) < 12) {
                            tvRemainingTime.setTextColor(context.getResources().getColor(R.color.colorWarning));
                        } else if (remainingDays < 3) {
                            tvRemainingTime.setTextColor(context.getResources().getColor(R.color.colorWarning));
                        } else {
                            tvRemainingTime.setTextColor(context.getResources().getColor(R.color.gray_text_light));
                        }
                    }
                }
            } else {
                if(tvRemainingTime != null) tvRemainingTime.setVisibility(View.GONE);
            }

            if (imageViewThumbnail != null && context != null) {
                File trashDirectory = TrashManager.getTrashDirectory(context);
                if (trashDirectory != null && item.getTrashFileName() != null) {
                    File trashedVideoFile = new File(trashDirectory, item.getTrashFileName());
                    if (trashedVideoFile.exists()) {
                        Uri videoUri = Uri.fromFile(trashedVideoFile);
                        Glide.with(context)
                            .load(videoUri)
                            .placeholder(R.drawable.ic_video_placeholder)
                            .error(R.drawable.ic_error)
                            .centerCrop()
                            .into(imageViewThumbnail);
                    } else {
                        Log.w("TrashAdapter", "Trashed video file does not exist: " + trashedVideoFile.getAbsolutePath());
                        Glide.with(context).load(R.drawable.ic_error).into(imageViewThumbnail); // Show error icon
                    }
                } else {
                    Log.e("TrashAdapter", "Trash directory or trash file name is null.");
                    Glide.with(context).load(R.drawable.ic_error).into(imageViewThumbnail); // Show error icon
                }
            }

            // Show check container by default (background visible). The inner tick image is shown when selected.
            if (iconCheckContainer != null) {
                iconCheckContainer.setVisibility(View.VISIBLE);
                if (iconCheck != null) {
                    if (selectedItems.contains(item)) {
                        // Ensure a drawable is set (recycled views might have been cleared)
                        AnimatedVectorDrawableCompat avd = AnimatedVectorDrawableCompat.create(context, R.drawable.avd_check_draw);
                        if (avd != null) {
                            iconCheck.setImageDrawable(avd);
                            // It's okay to animate when coming into view; ensures a consistent drawn tick
                            avd.start();
                        }
                        iconCheck.setAlpha(1f);
                    } else {
                        // Unselected: ensure inner tick is cleared and transparent, keep background visible
                        iconCheck.setImageDrawable(null);
                        iconCheck.setAlpha(0f);
                    }
                }
            }
            
            // Set up click listeners
            itemView.setOnClickListener(v -> {
                if (selectedItems.isEmpty()) {
                    if (interactionListener != null) {
                        File trashDirectory = TrashManager.getTrashDirectory(context);
                        if (trashDirectory != null && item.getTrashFileName() != null) {
                            File trashedVideoFile = new File(trashDirectory, item.getTrashFileName());
                            if (trashedVideoFile.exists()) {
                                interactionListener.onPlayVideoRequested(item); // item itself is fine, TrashFragment will reconstruct path
                            } else {
                                Toast.makeText(context, "Video file not found in trash.", Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            Toast.makeText(context, "Cannot locate video file.", Toast.LENGTH_SHORT).show();
                        }
                    }
                } else {
                    toggleSelection(item);
                }
            });

            itemView.setOnLongClickListener(v -> {
                if (longClickListener != null) {
                    longClickListener.onItemLongClicked(item);
                    return true;
                }
                toggleSelection(item);
                return true;
            });

            // Clicking directly on the check container should toggle selection and
            // should not open playback. Consume the click event to prevent
            // propagation to the itemView click listener.
            if (iconCheckContainer != null) {
                iconCheckContainer.setOnTouchListener((v, event) -> {
                    // Consume touch and handle click via OnClick to avoid propagation
                    return false; // allow click to proceed
                });
                iconCheckContainer.setOnClickListener(v -> {
                    toggleSelection(item);
                    v.setPressed(false);
                });
            }

            // We keep selection driven by clicks/long clicks. When toggling programmatically,
            // simply show/hide the container and notify listener.
        }
    }
    
    /**
     * Toggle selection state of an item
     */
    private void toggleSelection(TrashItem item) {
        boolean newState = !selectedItems.contains(item);
        if (newState) {
            selectedItems.add(item);
        } else {
            selectedItems.remove(item);
        }
        
        // Find the position of the item and notify the adapter (use payload for targeted bind)
        int position = trashItems.indexOf(item);
        if (position != -1) {
            notifyItemChanged(position, "SELECTION_TOGGLE");
        }
        
        if (interactionListener != null) {
            interactionListener.onItemCheckChanged(item, newState);
            interactionListener.onItemSelectedStateChanged(!selectedItems.isEmpty());
        }
    }

    /**
     * Returns true if currently in selection mode (i.e., any items selected)
     */
    public boolean isInSelectionMode() {
        return !selectedItems.isEmpty();
    }
} 