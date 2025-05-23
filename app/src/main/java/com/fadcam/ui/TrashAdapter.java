package com.fadcam.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
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

public class TrashAdapter extends RecyclerView.Adapter<TrashAdapter.TrashViewHolder> {

    private final Context context;
    private final List<TrashItem> trashItems;
    private final List<TrashItem> selectedItems = new ArrayList<>();
    private final OnTrashItemInteractionListener interactionListener;
    private final OnTrashItemLongClickListener longClickListener; // Optional

    public interface OnTrashItemInteractionListener {
        void onItemCheckChanged(TrashItem item, boolean isChecked);
        void onItemSelectedStateChanged(boolean anySelected);
        void onRestoreStarted(int itemCount);
        void onRestoreFinished(boolean success, String message);
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

    class TrashViewHolder extends RecyclerView.ViewHolder {
        TextView tvOriginalName;
        TextView tvDateTrashed;
        TextView tvOriginalLocation;
        CheckBox checkBoxSelected;

        TrashViewHolder(@NonNull View itemView) {
            super(itemView);
            tvOriginalName = itemView.findViewById(R.id.tv_trash_item_original_name);
            tvDateTrashed = itemView.findViewById(R.id.tv_trash_item_date_trashed);
            tvOriginalLocation = itemView.findViewById(R.id.tv_trash_item_original_location);
            checkBoxSelected = itemView.findViewById(R.id.checkbox_trash_item_selected);
        }

        void bind(final TrashItem item) {
            tvOriginalName.setText(item.getOriginalDisplayName());
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            tvDateTrashed.setText("Trashed: " + sdf.format(new Date(item.getDateTrashed())));
            tvOriginalLocation.setText("Original: " + (item.isFromSaf() ? "SAF Storage" : "Internal Storage"));

            checkBoxSelected.setChecked(selectedItems.contains(item));

            itemView.setOnClickListener(v -> {
                checkBoxSelected.toggle();
                // Listener will be called by CheckBox's listener
            });

            itemView.setOnLongClickListener(v -> {
                if (longClickListener != null) {
                    longClickListener.onItemLongClicked(item);
                    return true;
                }
                return false;
            });

            checkBoxSelected.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    if (!selectedItems.contains(item)) {
                        selectedItems.add(item);
                    }
                } else {
                    selectedItems.remove(item);
                }
                if (interactionListener != null) {
                    interactionListener.onItemCheckChanged(item, isChecked);
                }
            });
        }
    }
} 