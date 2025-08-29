package com.fadcam.ui.faditor.components;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.R;
import com.fadcam.ui.faditor.models.VideoProject;
import com.fadcam.ui.faditor.persistence.ProjectMetadata;
import com.fadcam.ui.faditor.utils.TimelineUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Professional project management interface with Material 3 design
 * Displays grid/list of recent projects with thumbnails and metadata
 */
public class ProjectBrowserComponent extends RecyclerView {

    public enum ViewMode {
        GRID, LIST
    }

    public interface ProjectBrowserListener {
        void onProjectSelected(ProjectMetadata project);

        void onNewProjectRequested();

        void onProjectDeleted(String projectId);

        void onProjectRenamed(String projectId, String newName);

        void onProjectImported();

        void onProjectExported(String projectId);
    }

    private ProjectAdapter adapter;
    private ProjectBrowserListener listener;
    private ViewMode currentViewMode = ViewMode.GRID;

    public ProjectBrowserComponent(@NonNull Context context) {
        super(context);
        init();
    }

    public ProjectBrowserComponent(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ProjectBrowserComponent(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        adapter = new ProjectAdapter();
        setAdapter(adapter);
        setViewMode(ViewMode.GRID);
    }

    public void setViewMode(ViewMode mode) {
        this.currentViewMode = mode;

        if (mode == ViewMode.GRID) {
            setLayoutManager(new GridLayoutManager(getContext(), 2));
        } else {
            setLayoutManager(new LinearLayoutManager(getContext()));
        }

        if (adapter != null) {
            adapter.setViewMode(mode);
        }
    }

    public void loadProjects(List<ProjectMetadata> projects) {
        if (adapter != null) {
            adapter.updateProjects(projects);
        }
    }

    public void refreshProjects() {
        // This will be called by the parent fragment to refresh the project list
        if (listener != null) {
            // Trigger refresh through listener - parent will call loadProjects() with
            // updated data
        }
    }

    public void setProjectBrowserListener(ProjectBrowserListener listener) {
        this.listener = listener;
    }

    private class ProjectAdapter extends RecyclerView.Adapter<ProjectAdapter.ProjectViewHolder> {

        private List<ProjectMetadata> projects = new ArrayList<>();
        private ViewMode viewMode = ViewMode.GRID;

        public void updateProjects(List<ProjectMetadata> newProjects) {
            this.projects.clear();
            this.projects.addAll(newProjects);
            notifyDataSetChanged();
        }

        public void setViewMode(ViewMode mode) {
            this.viewMode = mode;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ProjectViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layoutId = viewMode == ViewMode.GRID ? R.layout.item_project_grid : R.layout.item_project_list;
            View view = LayoutInflater.from(parent.getContext()).inflate(layoutId, parent, false);
            return new ProjectViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ProjectViewHolder holder, int position) {
            ProjectMetadata project = projects.get(position);
            holder.bind(project);
        }

        @Override
        public int getItemCount() {
            return projects.size();
        }

        class ProjectViewHolder extends RecyclerView.ViewHolder {

            private ImageView thumbnailImage;
            private TextView projectNameText;
            private TextView durationText;
            private TextView lastModifiedText;
            private TextView fileSizeText;
            private View unsavedIndicator;

            public ProjectViewHolder(@NonNull View itemView) {
                super(itemView);

                thumbnailImage = itemView.findViewById(R.id.project_thumbnail);
                projectNameText = itemView.findViewById(R.id.project_name);
                durationText = itemView.findViewById(R.id.project_duration);
                lastModifiedText = itemView.findViewById(R.id.project_last_modified);
                fileSizeText = itemView.findViewById(R.id.project_file_size);
                unsavedIndicator = itemView.findViewById(R.id.unsaved_indicator);

                itemView.setOnClickListener(v -> {
                    int position = getAdapterPosition();
                    if (position != RecyclerView.NO_POSITION && listener != null) {
                        listener.onProjectSelected(projects.get(position));
                    }
                });

                itemView.setOnLongClickListener(v -> {
                    int position = getAdapterPosition();
                    if (position != RecyclerView.NO_POSITION) {
                        showProjectContextMenu(projects.get(position));
                    }
                    return true;
                });
            }

            public void bind(ProjectMetadata project) {
                projectNameText.setText(project.getProjectName());

                // Format duration
                if (durationText != null) {
                    durationText.setText(TimelineUtils.formatDuration(project.getDuration()));
                }

                // Format last modified date
                if (lastModifiedText != null) {
                    SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
                    String dateStr = dateFormat.format(new Date(project.getLastModified()));
                    lastModifiedText.setText(dateStr);
                }

                // Format file size
                if (fileSizeText != null && project.getFileSize() > 0) {
                    fileSizeText.setText(formatFileSize(project.getFileSize()));
                }

                // Show unsaved indicator if needed
                if (unsavedIndicator != null) {
                    unsavedIndicator.setVisibility(project.isHasUnsavedChanges() ? View.VISIBLE : View.GONE);
                }

                // Load thumbnail (placeholder for now)
                if (thumbnailImage != null) {
                    // TODO: Load actual thumbnail from project.getThumbnailPath()
                    thumbnailImage.setImageResource(R.drawable.unknown_icon3);
                }
            }

            private String formatFileSize(long bytes) {
                if (bytes < 1024)
                    return bytes + " B";
                int exp = (int) (Math.log(bytes) / Math.log(1024));
                String pre = "KMGTPE".charAt(exp - 1) + "";
                return String.format(Locale.getDefault(), "%.1f %sB", bytes / Math.pow(1024, exp), pre);
            }
        }
    }

    private void showProjectContextMenu(ProjectMetadata project) {
        // TODO: Implement context menu for rename, delete, export
        // This will be implemented with a popup menu or bottom sheet
    }
}