package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;

import java.util.UUID;

/**
 * Top-level project wrapper for the Faditor editor.
 * Contains a {@link Timeline} and metadata.
 *
 * <p>For Phase 1 (MVP) a project is created on-the-fly from a video pick.
 * Phase 4 adds persistence via JSON serialization.</p>
 */
public class FaditorProject {

    @NonNull
    private final String id;

    @NonNull
    private String name;

    private final long createdAt;

    private long lastModified;

    @NonNull
    private final Timeline timeline;

    @NonNull
    private final ExportSettings exportSettings;

    /** Canvas aspect ratio preset (project-level). "original" = no change. */
    @NonNull
    private String canvasPreset = "original";

    /**
     * Create a new empty project.
     *
     * @param name display name for the project
     */
    public FaditorProject(@NonNull String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.createdAt = System.currentTimeMillis();
        this.lastModified = this.createdAt;
        this.timeline = new Timeline();
        this.exportSettings = new ExportSettings();
    }

    /**
     * Restore a project from persisted data.
     * Used by {@code ProjectStorage} deserialization.
     *
     * @param id           the original project ID
     * @param name         display name
     * @param createdAt    original creation timestamp
     * @param lastModified last modification timestamp
     */
    public FaditorProject(@NonNull String id, @NonNull String name,
                          long createdAt, long lastModified) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
        this.lastModified = lastModified;
        this.timeline = new Timeline();
        this.exportSettings = new ExportSettings();
    }

    // ── Getters ──────────────────────────────────────────────────────

    @NonNull
    public String getId() {
        return id;
    }

    @NonNull
    public String getName() {
        return name;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getLastModified() {
        return lastModified;
    }

    @NonNull
    public Timeline getTimeline() {
        return timeline;
    }

    @NonNull
    public ExportSettings getExportSettings() {
        return exportSettings;
    }

    @NonNull
    public String getCanvasPreset() {
        return canvasPreset;
    }

    // ── Setters ──────────────────────────────────────────────────────

    public void setCanvasPreset(@NonNull String canvasPreset) {
        this.canvasPreset = canvasPreset;
        touch();
    }

    public void setName(@NonNull String name) {
        this.name = name;
        touch();
    }

    /**
     * Update the lastModified timestamp. Call after any edit operation.
     */
    public void touch() {
        this.lastModified = System.currentTimeMillis();
    }

    @NonNull
    @Override
    public String toString() {
        return "FaditorProject{id=" + id
                + ", name=" + name
                + ", clips=" + timeline.getClipCount()
                + "}";
    }
}
