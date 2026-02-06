package com.fadcam.ui.faditor.project;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.ExportSettings;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.Timeline;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Handles JSON persistence for Faditor projects.
 *
 * <p>Projects are stored in app-internal storage at:
 * {@code files/faditor/projects/{projectId}/project.json}</p>
 *
 * <p>Uses Gson for serialization with custom adapters for {@link Uri},
 * {@link Clip}, and {@link FaditorProject} types.</p>
 */
public class ProjectStorage {

    private static final String TAG = "ProjectStorage";
    private static final String PROJECTS_DIR = "faditor/projects";
    private static final String PROJECT_FILE = "project.json";

    @NonNull
    private final File projectsRoot;

    @NonNull
    private final Gson gson;

    /**
     * Create a ProjectStorage instance.
     *
     * @param context application or activity context
     */
    public ProjectStorage(@NonNull Context context) {
        this.projectsRoot = new File(context.getFilesDir(), PROJECTS_DIR);
        if (!projectsRoot.exists()) {
            projectsRoot.mkdirs();
        }
        this.gson = createGson();
    }

    // ── Public API ───────────────────────────────────────────────────

    /**
     * Save a project to disk. Creates or overwrites the project file.
     *
     * @param project the project to save
     * @return true if successful, false otherwise
     */
    public boolean save(@NonNull FaditorProject project) {
        File projectDir = getProjectDir(project.getId());
        if (!projectDir.exists() && !projectDir.mkdirs()) {
            Log.e(TAG, "Failed to create project directory: " + projectDir);
            return false;
        }

        File file = new File(projectDir, PROJECT_FILE);
        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(project, writer);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to save project: " + project.getId(), e);
            return false;
        }
    }

    /**
     * Load a project from disk by ID.
     *
     * @param projectId the project ID
     * @return the loaded project, or null if not found or corrupt
     */
    @Nullable
    public FaditorProject load(@NonNull String projectId) {
        File file = new File(getProjectDir(projectId), PROJECT_FILE);
        if (!file.exists()) {
            Log.d(TAG, "No saved project found: " + projectId);
            return null;
        }

        try (FileReader reader = new FileReader(file)) {
            return gson.fromJson(reader, FaditorProject.class);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load project: " + projectId, e);
            return null;
        }
    }

    /**
     * List all saved projects, sorted by last modified (newest first).
     *
     * @return list of project summaries (lightweight — only metadata, no full load)
     */
    @NonNull
    public List<ProjectSummary> listProjects() {
        List<ProjectSummary> result = new ArrayList<>();

        File[] dirs = projectsRoot.listFiles(File::isDirectory);
        if (dirs == null) return result;

        for (File dir : dirs) {
            File file = new File(dir, PROJECT_FILE);
            if (!file.exists()) continue;

            try (FileReader reader = new FileReader(file)) {
                JsonObject json = gson.fromJson(reader, JsonObject.class);
                if (json == null) continue;

                String id = json.has("id") ? json.get("id").getAsString() : dir.getName();
                String name = json.has("name") ? json.get("name").getAsString() : "Untitled";
                long lastModified = json.has("lastModified")
                        ? json.get("lastModified").getAsLong()
                        : file.lastModified();
                long createdAt = json.has("createdAt")
                        ? json.get("createdAt").getAsLong()
                        : lastModified;

                // Get video URI from first clip (for thumbnail in future)
                String videoUri = null;
                if (json.has("timeline")) {
                    JsonObject timeline = json.getAsJsonObject("timeline");
                    if (timeline.has("clips")) {
                        JsonArray clips = timeline.getAsJsonArray("clips");
                        if (clips.size() > 0) {
                            JsonObject firstClip = clips.get(0).getAsJsonObject();
                            if (firstClip.has("sourceUri")) {
                                videoUri = firstClip.get("sourceUri").getAsString();
                            }
                        }
                    }
                }

                result.add(new ProjectSummary(id, name, createdAt, lastModified, videoUri));
            } catch (Exception e) {
                Log.w(TAG, "Skipping corrupt project: " + dir.getName(), e);
            }
        }

        // Sort by lastModified descending
        Collections.sort(result, (a, b) -> Long.compare(b.lastModified, a.lastModified));
        return result;
    }

    /**
     * Delete a saved project.
     *
     * @param projectId the project ID to delete
     * @return true if deleted, false otherwise
     */
    public boolean delete(@NonNull String projectId) {
        File dir = getProjectDir(projectId);
        if (!dir.exists()) return false;

        // Delete all files in the directory
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        return dir.delete();
    }

    /**
     * Check if a project exists on disk.
     */
    public boolean exists(@NonNull String projectId) {
        return new File(getProjectDir(projectId), PROJECT_FILE).exists();
    }

    // ── Internal ─────────────────────────────────────────────────────

    @NonNull
    private File getProjectDir(@NonNull String projectId) {
        return new File(projectsRoot, projectId);
    }

    @NonNull
    private Gson createGson() {
        return new GsonBuilder()
                .registerTypeAdapter(Uri.class, new UriAdapter())
                .registerTypeAdapter(FaditorProject.class, new ProjectSerializer())
                .registerTypeAdapter(FaditorProject.class, new ProjectDeserializer())
                .setPrettyPrinting()
                .create();
    }

    // ── Gson adapters ────────────────────────────────────────────────

    /**
     * Serializes/deserializes Android Uri as a plain string.
     */
    private static class UriAdapter
            implements JsonSerializer<Uri>, JsonDeserializer<Uri> {

        @Override
        public JsonElement serialize(Uri src, Type typeOfSrc,
                                     JsonSerializationContext context) {
            return context.serialize(src.toString());
        }

        @Override
        public Uri deserialize(JsonElement json, Type typeOfT,
                               JsonDeserializationContext context) throws JsonParseException {
            return Uri.parse(json.getAsString());
        }
    }

    /**
     * Custom serializer for FaditorProject (flattens nested objects).
     */
    private static class ProjectSerializer implements JsonSerializer<FaditorProject> {
        @Override
        public JsonElement serialize(FaditorProject src, Type typeOfSrc,
                                     JsonSerializationContext context) {
            JsonObject json = new JsonObject();
            json.addProperty("id", src.getId());
            json.addProperty("name", src.getName());
            json.addProperty("createdAt", src.getCreatedAt());
            json.addProperty("lastModified", src.getLastModified());

            // Serialize timeline with clips
            JsonObject timelineJson = new JsonObject();
            JsonArray clipsArray = new JsonArray();
            for (Clip clip : src.getTimeline().getClips()) {
                JsonObject clipJson = new JsonObject();
                clipJson.addProperty("id", clip.getId());
                clipJson.addProperty("sourceUri", clip.getSourceUri().toString());
                clipJson.addProperty("inPointMs", clip.getInPointMs());
                clipJson.addProperty("outPointMs", clip.getOutPointMs());
                clipJson.addProperty("sourceDurationMs", clip.getSourceDurationMs());
                clipJson.addProperty("speedMultiplier", clip.getSpeedMultiplier());
                clipJson.addProperty("audioMuted", clip.isAudioMuted());
                clipJson.addProperty("rotationDegrees", clip.getRotationDegrees());
                clipJson.addProperty("flipHorizontal", clip.isFlipHorizontal());
                clipJson.addProperty("flipVertical", clip.isFlipVertical());
                clipJson.addProperty("cropPreset", clip.getCropPreset());
                clipsArray.add(clipJson);
            }
            timelineJson.add("clips", clipsArray);
            json.add("timeline", timelineJson);

            // Serialize export settings
            JsonObject exportJson = new JsonObject();
            exportJson.addProperty("resolution", src.getExportSettings().getResolution().name());
            exportJson.addProperty("quality", src.getExportSettings().getQuality().name());
            exportJson.addProperty("format", src.getExportSettings().getFormat().name());
            json.add("exportSettings", exportJson);

            return json;
        }
    }

    /**
     * Custom deserializer for FaditorProject (rebuilds from flat JSON).
     */
    private static class ProjectDeserializer implements JsonDeserializer<FaditorProject> {
        @Override
        public FaditorProject deserialize(JsonElement json, Type typeOfT,
                                          JsonDeserializationContext context)
                throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();

            String name = obj.has("name") ? obj.get("name").getAsString() : "Untitled";
            FaditorProject project = new FaditorProject(name);

            // Restore timeline clips
            if (obj.has("timeline")) {
                JsonObject timelineJson = obj.getAsJsonObject("timeline");
                if (timelineJson.has("clips")) {
                    JsonArray clips = timelineJson.getAsJsonArray("clips");
                    for (int i = 0; i < clips.size(); i++) {
                        JsonObject clipObj = clips.get(i).getAsJsonObject();
                        String clipId = clipObj.get("id").getAsString();
                        Uri sourceUri = Uri.parse(clipObj.get("sourceUri").getAsString());
                        long inPointMs = clipObj.get("inPointMs").getAsLong();
                        long outPointMs = clipObj.get("outPointMs").getAsLong();
                        long sourceDurationMs = clipObj.get("sourceDurationMs").getAsLong();
                        float speed = clipObj.has("speedMultiplier")
                                ? clipObj.get("speedMultiplier").getAsFloat() : 1.0f;
                        boolean audioMuted = clipObj.has("audioMuted")
                                && clipObj.get("audioMuted").getAsBoolean();
                        int rotationDeg = clipObj.has("rotationDegrees")
                                ? clipObj.get("rotationDegrees").getAsInt() : 0;
                        boolean flipH = clipObj.has("flipHorizontal")
                                && clipObj.get("flipHorizontal").getAsBoolean();
                        boolean flipV = clipObj.has("flipVertical")
                                && clipObj.get("flipVertical").getAsBoolean();
                        String crop = clipObj.has("cropPreset")
                                ? clipObj.get("cropPreset").getAsString() : "none";

                        Clip clip = new Clip(clipId, sourceUri,
                                inPointMs, outPointMs, sourceDurationMs, speed, audioMuted,
                                rotationDeg, flipH, flipV, crop);
                        project.getTimeline().addClip(clip);
                    }
                }
            }

            // Restore export settings
            if (obj.has("exportSettings")) {
                JsonObject expObj = obj.getAsJsonObject("exportSettings");
                ExportSettings settings = project.getExportSettings();
                if (expObj.has("resolution")) {
                    try {
                        settings.setResolution(
                                ExportSettings.Resolution.valueOf(
                                        expObj.get("resolution").getAsString()));
                    } catch (IllegalArgumentException ignored) { }
                }
                if (expObj.has("quality")) {
                    try {
                        settings.setQuality(
                                ExportSettings.Quality.valueOf(
                                        expObj.get("quality").getAsString()));
                    } catch (IllegalArgumentException ignored) { }
                }
                if (expObj.has("format")) {
                    try {
                        settings.setFormat(
                                ExportSettings.Format.valueOf(
                                        expObj.get("format").getAsString()));
                    } catch (IllegalArgumentException ignored) { }
                }
            }

            return project;
        }
    }

    // ── Project summary (lightweight) ────────────────────────────────

    /**
     * Lightweight summary of a saved project for list display.
     * Avoids loading full project data.
     */
    public static class ProjectSummary {

        @NonNull
        public final String id;

        @NonNull
        public final String name;

        public final long createdAt;

        public final long lastModified;

        /** First clip's video URI string (can be null). */
        @Nullable
        public final String videoUri;

        public ProjectSummary(@NonNull String id, @NonNull String name,
                              long createdAt, long lastModified,
                              @Nullable String videoUri) {
            this.id = id;
            this.name = name;
            this.createdAt = createdAt;
            this.lastModified = lastModified;
            this.videoUri = videoUri;
        }
    }
}
