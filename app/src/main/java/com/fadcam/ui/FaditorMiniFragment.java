package com.fadcam.ui;

import android.app.AlertDialog;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.Log;
import com.fadcam.R;
import com.fadcam.ui.faditor.components.ProjectBrowserComponent;
import com.fadcam.ui.faditor.models.VideoMetadata;
import com.fadcam.ui.faditor.models.VideoProject;
import com.fadcam.ui.faditor.persistence.ProjectManager;
import com.fadcam.ui.faditor.persistence.ProjectMetadata;
import com.fadcam.ui.faditor.utils.NavigationUtils;
import com.fadcam.ui.faditor.utils.VideoFilePicker;
import com.fadcam.ui.faditor.utils.VideoFileUtils;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Project browser screen for Faditor Mini video editor.
 * Displays recent projects with Material 3 design and provides project management functionality.
 */
public class FaditorMiniFragment extends BaseFragment implements 
        ProjectBrowserComponent.ProjectBrowserListener, VideoFilePicker.VideoSelectionCallback {
    
    private static final String TAG = "FaditorMiniFragment";
    
    // UI Components
    private ProjectBrowserComponent projectBrowser;
    private MaterialButton viewModeButton;
    private MaterialButton newProjectButton;
    private FloatingActionButton fabNewProject;
    private MaterialButton emptyStateCreateButton;
    private MaterialButton sortButton;
    private TextInputEditText searchEditText;
    private TextView projectsCountText;
    private View emptyStateContainer;
    private View projectsContainer;
    
    // Project management
    private ProjectManager projectManager;
    private VideoFilePicker filePicker;
    private List<ProjectMetadata> allProjects = new ArrayList<>();
    private List<ProjectMetadata> filteredProjects = new ArrayList<>();
    private ProjectBrowserComponent.ViewMode currentViewMode = ProjectBrowserComponent.ViewMode.GRID;
    private SortMode currentSortMode = SortMode.DATE_DESC;
    
    private enum SortMode {
        DATE_DESC, DATE_ASC, NAME_ASC, NAME_DESC, SIZE_DESC, SIZE_ASC
    }
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_faditor_project_browser, container, false);
        
        initializeViews(view);
        setupProjectManager();
        setupFilePicker();
        loadProjects();
        
        return view;
    }
    
    private void initializeViews(View view) {
        // Find UI components
        projectBrowser = view.findViewById(R.id.projects_recycler_view);
        viewModeButton = view.findViewById(R.id.view_mode_button);
        newProjectButton = view.findViewById(R.id.new_project_button);
        fabNewProject = view.findViewById(R.id.fab_new_project);
        emptyStateCreateButton = view.findViewById(R.id.empty_state_create_button);
        sortButton = view.findViewById(R.id.sort_button);
        searchEditText = view.findViewById(R.id.search_edit_text);
        projectsCountText = view.findViewById(R.id.projects_count_text);
        emptyStateContainer = view.findViewById(R.id.empty_state_container);
        projectsContainer = view.findViewById(R.id.projects_container);
        
        // Set up project browser
        if (projectBrowser != null) {
            projectBrowser.setProjectBrowserListener(this);
            projectBrowser.setViewMode(currentViewMode);
        }
        
        // Set up click listeners
        if (viewModeButton != null) {
            viewModeButton.setOnClickListener(v -> toggleViewMode());
        }
        
        if (newProjectButton != null) {
            newProjectButton.setOnClickListener(v -> onNewProjectRequested());
        }
        
        if (fabNewProject != null) {
            fabNewProject.setOnClickListener(v -> onNewProjectRequested());
        }
        
        if (emptyStateCreateButton != null) {
            emptyStateCreateButton.setOnClickListener(v -> onNewProjectRequested());
        }
        
        if (sortButton != null) {
            sortButton.setOnClickListener(v -> showSortMenu());
        }
        
        // Set up search functionality
        if (searchEditText != null) {
            searchEditText.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    filterProjects(s.toString());
                }
                
                @Override
                public void afterTextChanged(Editable s) {}
            });
        }
    }
    
    private void setupProjectManager() {
        projectManager = new ProjectManager(requireContext());
    }
    
    private void setupFilePicker() {
        filePicker = new VideoFilePicker(this);
    }
    
    private void loadProjects() {
        if (projectManager != null) {
            projectManager.getRecentProjects().observe(this, projects -> {
                allProjects.clear();
                if (projects != null) {
                    allProjects.addAll(projects);
                }
                sortProjects();
                filterProjects(searchEditText != null ? searchEditText.getText().toString() : "");
                updateUI();
            });
        }
    }
    
    private void toggleViewMode() {
        currentViewMode = currentViewMode == ProjectBrowserComponent.ViewMode.GRID ? 
            ProjectBrowserComponent.ViewMode.LIST : ProjectBrowserComponent.ViewMode.GRID;
        
        if (projectBrowser != null) {
            projectBrowser.setViewMode(currentViewMode);
        }
        
        // Update button icon
        if (viewModeButton != null) {
            int iconRes = currentViewMode == ProjectBrowserComponent.ViewMode.GRID ? 
                R.drawable.ic_view_list : R.drawable.ic_view_grid;
            viewModeButton.setIconResource(iconRes);
        }
    }
    
    private void showSortMenu() {
        if (sortButton == null) return;
        
        PopupMenu popup = new PopupMenu(requireContext(), sortButton);
        popup.getMenuInflater().inflate(R.menu.sort_menu, popup.getMenu());
        
        popup.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.sort_date_desc) {
                currentSortMode = SortMode.DATE_DESC;
            } else if (itemId == R.id.sort_date_asc) {
                currentSortMode = SortMode.DATE_ASC;
            } else if (itemId == R.id.sort_name_asc) {
                currentSortMode = SortMode.NAME_ASC;
            } else if (itemId == R.id.sort_name_desc) {
                currentSortMode = SortMode.NAME_DESC;
            } else if (itemId == R.id.sort_size_desc) {
                currentSortMode = SortMode.SIZE_DESC;
            } else if (itemId == R.id.sort_size_asc) {
                currentSortMode = SortMode.SIZE_ASC;
            }
            
            sortProjects();
            filterProjects(searchEditText != null ? searchEditText.getText().toString() : "");
            updateUI();
            return true;
        });
        
        popup.show();
    }
    
    private void sortProjects() {
        Comparator<ProjectMetadata> comparator;
        
        switch (currentSortMode) {
            case DATE_ASC:
                comparator = Comparator.comparingLong(ProjectMetadata::getLastModified);
                break;
            case NAME_ASC:
                comparator = Comparator.comparing(ProjectMetadata::getProjectName, String.CASE_INSENSITIVE_ORDER);
                break;
            case NAME_DESC:
                comparator = Comparator.comparing(ProjectMetadata::getProjectName, String.CASE_INSENSITIVE_ORDER).reversed();
                break;
            case SIZE_DESC:
                comparator = Comparator.comparingLong(ProjectMetadata::getFileSize).reversed();
                break;
            case SIZE_ASC:
                comparator = Comparator.comparingLong(ProjectMetadata::getFileSize);
                break;
            case DATE_DESC:
            default:
                comparator = Comparator.comparingLong(ProjectMetadata::getLastModified).reversed();
                break;
        }
        
        Collections.sort(allProjects, comparator);
    }
    
    private void filterProjects(String query) {
        filteredProjects.clear();
        
        if (query == null || query.trim().isEmpty()) {
            filteredProjects.addAll(allProjects);
        } else {
            String lowerQuery = query.toLowerCase().trim();
            for (ProjectMetadata project : allProjects) {
                if (project.getProjectName().toLowerCase().contains(lowerQuery) ||
                    project.getOriginalVideoName().toLowerCase().contains(lowerQuery)) {
                    filteredProjects.add(project);
                }
            }
        }
        
        if (projectBrowser != null) {
            projectBrowser.loadProjects(filteredProjects);
        }
    }
    
    private void updateUI() {
        boolean hasProjects = !filteredProjects.isEmpty();
        
        if (emptyStateContainer != null) {
            emptyStateContainer.setVisibility(hasProjects ? View.GONE : View.VISIBLE);
        }
        
        if (projectsContainer != null) {
            projectsContainer.setVisibility(hasProjects ? View.VISIBLE : View.GONE);
        }
        
        // Update projects count
        if (projectsCountText != null) {
            int count = filteredProjects.size();
            String countText = count == 1 ? 
                getString(R.string.faditor_projects_count_single) : 
                getString(R.string.faditor_projects_count, count);
            projectsCountText.setText(countText);
        }
    }
    
    // ProjectBrowserListener implementation
    
    @Override
    public void onProjectSelected(ProjectMetadata project) {
        Log.d(TAG, "Project selected: " + project.getProjectName());
        
        // Navigate to the full-screen editor with this project (Requirement 10.1, 10.2)
        NavigationUtils.openEditor(this, project.getProjectId());
    }
    
    @Override
    public void onNewProjectRequested() {
        Log.d(TAG, "New project requested");
        
        // Show project creation dialog
        showNewProjectDialog();
    }
    
    @Override
    public void onProjectDeleted(String projectId) {
        Log.d(TAG, "Delete project requested: " + projectId);
        
        // Show confirmation dialog
        showDeleteConfirmationDialog(projectId);
    }
    
    @Override
    public void onProjectRenamed(String projectId, String newName) {
        Log.d(TAG, "Rename project requested: " + projectId + " to " + newName);
        
        // Show rename dialog
        showRenameDialog(projectId);
    }
    
    @Override
    public void onProjectImported() {
        Log.d(TAG, "Import project requested");
        
        // TODO: Implement project import functionality
        Toast.makeText(requireContext(), "Import functionality coming soon", Toast.LENGTH_SHORT).show();
    }
    
    @Override
    public void onProjectExported(String projectId) {
        Log.d(TAG, "Export project requested: " + projectId);
        
        // TODO: Implement project export functionality
        Toast.makeText(requireContext(), "Export functionality coming soon", Toast.LENGTH_SHORT).show();
    }
    
    // VideoFilePicker.VideoSelectionCallback implementation
    
    @Override
    public void onVideoSelected(Uri videoUri) {
        Log.d(TAG, "Video selected for new project: " + videoUri.toString());
        
        // Validate the selected video
        if (!VideoFileUtils.isValidVideoFile(requireContext(), videoUri)) {
            String supportedFormats = VideoFileUtils.getSupportedFormatsString();
            String errorMessage = getString(R.string.faditor_supported_formats, supportedFormats);
            Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show();
            return;
        }
        
        // Extract metadata
        VideoMetadata metadata = VideoFileUtils.extractMetadata(requireContext(), videoUri);
        
        if (metadata.getDuration() <= 0) {
            Toast.makeText(requireContext(), R.string.faditor_error_processing_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Create new project
        VideoProject newProject = new VideoProject();
        newProject.setOriginalVideoUri(videoUri);
        newProject.setMetadata(metadata);
        
        // Generate project name from video file
        String fileName = VideoFileUtils.getFileName(requireContext(), videoUri);
        String projectName = fileName != null ? fileName.replaceFirst("[.][^.]+$", "") : "New Project";
        newProject.setProjectName(projectName);
        
        // Save the project
        if (projectManager != null) {
            projectManager.saveProject(newProject, new ProjectManager.ProjectCallback() {
                @Override
                public void onProjectSaved(String projectId) {
                    Log.d(TAG, "New project saved: " + projectId);
                    Toast.makeText(requireContext(), "Project created: " + projectName, Toast.LENGTH_SHORT).show();
                    
                    // Navigate to editor with the new project (Requirement 10.1, 10.2)
                    NavigationUtils.openEditor(FaditorMiniFragment.this, projectId);
                }
                
                @Override
                public void onProjectLoaded(VideoProject project) {
                    // Not used in this context
                }
                
                @Override
                public void onError(String errorMessage) {
                    Log.e(TAG, "Failed to save new project: " + errorMessage);
                    Toast.makeText(requireContext(), "Failed to create project: " + errorMessage, Toast.LENGTH_LONG).show();
                }
            });
        }
    }
    
    @Override
    public void onSelectionCancelled() {
        Log.d(TAG, "Video selection cancelled");
        // No action needed - user cancelled
    }
    
    @Override
    public void onError(String errorMessage) {
        Log.e(TAG, "Video selection error: " + errorMessage);
        Toast.makeText(requireContext(), R.string.faditor_error_processing_failed, Toast.LENGTH_SHORT).show();
    }
    
    private void showNewProjectDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle(R.string.faditor_new_project_title);
        builder.setMessage(R.string.faditor_select_video_for_project);
        
        builder.setPositiveButton(R.string.faditor_select_video, (dialog, which) -> {
            if (filePicker != null) {
                filePicker.pickVideo(this);
            }
        });
        
        builder.setNegativeButton(R.string.faditor_cancel, (dialog, which) -> {
            dialog.dismiss();
        });
        
        builder.show();
    }
    
    private void showDeleteConfirmationDialog(String projectId) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle(R.string.faditor_project_delete_confirm);
        builder.setMessage(R.string.faditor_project_delete_message);
        
        builder.setPositiveButton(R.string.faditor_project_delete, (dialog, which) -> {
            deleteProject(projectId);
        });
        
        builder.setNegativeButton(R.string.faditor_cancel, (dialog, which) -> {
            dialog.dismiss();
        });
        
        builder.show();
    }
    
    private void showRenameDialog(String projectId) {
        // Find the project to get current name
        ProjectMetadata project = null;
        for (ProjectMetadata p : allProjects) {
            if (p.getProjectId().equals(projectId)) {
                project = p;
                break;
            }
        }
        
        if (project == null) return;
        
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle(R.string.faditor_project_rename_title);
        
        EditText editText = new EditText(requireContext());
        editText.setText(project.getProjectName());
        editText.setHint(R.string.faditor_project_name_hint);
        editText.selectAll();
        
        builder.setView(editText);
        
        builder.setPositiveButton(R.string.faditor_confirm, (dialog, which) -> {
            String newName = editText.getText().toString().trim();
            if (!newName.isEmpty()) {
                renameProject(projectId, newName);
            }
        });
        
        builder.setNegativeButton(R.string.faditor_cancel, (dialog, which) -> {
            dialog.dismiss();
        });
        
        AlertDialog dialog = builder.create();
        dialog.show();
        
        // Focus on the edit text and show keyboard
        editText.requestFocus();
    }
    
    private void deleteProject(String projectId) {
        if (projectManager != null) {
            projectManager.deleteProject(projectId, new ProjectManager.ProjectCallback() {
                @Override
                public void onProjectSaved(String projectId) {
                    // Not used in this context
                }
                
                @Override
                public void onProjectLoaded(VideoProject project) {
                    // Not used in this context
                }
                
                @Override
                public void onError(String errorMessage) {
                    Log.e(TAG, "Failed to delete project: " + errorMessage);
                    Toast.makeText(requireContext(), "Failed to delete project: " + errorMessage, Toast.LENGTH_LONG).show();
                }
            });
        }
    }
    
    private void renameProject(String projectId, String newName) {
        // TODO: Implement project renaming in ProjectManager
        Toast.makeText(requireContext(), "Rename functionality coming soon", Toast.LENGTH_SHORT).show();
    }
    
    @Override
    public void onResume() {
        super.onResume();
        
        // Refresh projects when returning to the fragment
        loadProjects();
    }
    
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        
        // Clean up resources
        if (projectBrowser != null) {
            projectBrowser.setProjectBrowserListener(null);
        }
        
        allProjects.clear();
        filteredProjects.clear();
    }
}
