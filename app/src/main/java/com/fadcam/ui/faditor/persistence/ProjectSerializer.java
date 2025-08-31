package com.fadcam.ui.faditor.persistence;

import com.fadcam.ui.faditor.models.VideoProject;
import com.fadcam.ui.faditor.models.EditorState;
import com.fadcam.ui.faditor.models.EditOperation;
import com.fadcam.ui.faditor.models.VideoMetadata;
import com.fadcam.ui.faditor.models.TimelineState;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.net.Uri;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles JSON serialization and deserialization of project data
 */
public class ProjectSerializer {

    private static final String VERSION = "1.0";

    /**
     * Serialize a VideoProject to JSON string
     */
    public static String serializeProject(VideoProject project) throws JSONException {
        JSONObject json = new JSONObject();

        // Project metadata
        json.put("version", VERSION);
        json.put("projectId", project.getProjectId());
        json.put("projectName", project.getProjectName());
        json.put("createdAt", project.getCreatedAt());
        json.put("lastModified", project.getLastModified());
        json.put("duration", project.getDuration());

        // Media asset information
        json.put("primaryMediaAssetId", project.getPrimaryMediaAssetId());

        // Media asset IDs array
        JSONArray mediaAssetIds = new JSONArray();
        if (project.getMediaAssetIds() != null) {
            for (String assetId : project.getMediaAssetIds()) {
                mediaAssetIds.put(assetId);
            }
        }
        json.put("mediaAssetIds", mediaAssetIds);

        // Legacy media information for backward compatibility
        JSONObject media = new JSONObject();
        JSONObject originalVideo = new JSONObject();
        originalVideo.put("uri",
                project.getOriginalVideoUri() != null ? project.getOriginalVideoUri().toString() : null);
        originalVideo.put("path", project.getOriginalVideoPath());

        if (project.getMetadata() != null) {
            JSONObject metadata = new JSONObject();
            metadata.put("codec", project.getMetadata().getCodec());
            metadata.put("width", project.getMetadata().getWidth());
            metadata.put("height", project.getMetadata().getHeight());
            metadata.put("duration", project.getMetadata().getDuration());
            metadata.put("bitrate", project.getMetadata().getBitrate());
            metadata.put("frameRate", project.getMetadata().getFrameRate());
            metadata.put("colorFormat", project.getMetadata().getColorFormat());
            originalVideo.put("metadata", metadata);
        }

        media.put("originalVideo", originalVideo);
        json.put("media", media);

        // Timeline data
        JSONObject timeline = new JSONObject();
        timeline.put("duration", project.getDuration());

        if (project.getCurrentTrim() != null) {
            timeline.put("trimStart", project.getCurrentTrim().getStartTime());
            timeline.put("trimEnd", project.getCurrentTrim().getEndTime());
        }

        // Serialize operations
        JSONArray operations = new JSONArray();
        if (project.getOperations() != null) {
            for (EditOperation operation : project.getOperations()) {
                JSONObject opJson = new JSONObject();
                opJson.put("type", operation.getType().name());
                opJson.put("startTime", operation.getStartTime());
                opJson.put("endTime", operation.getEndTime());
                opJson.put("requiresReencoding", operation.isRequiresReencoding());

                // Serialize parameters
                JSONObject params = new JSONObject();
                if (operation.getParameters() != null) {
                    for (Map.Entry<String, Object> entry : operation.getParameters().entrySet()) {
                        params.put(entry.getKey(), entry.getValue());
                    }
                }
                opJson.put("parameters", params);
                operations.put(opJson);
            }
        }
        timeline.put("operations", operations);

        json.put("timeline", timeline);

        // Settings
        JSONObject settings = new JSONObject();
        settings.put("frameSnapping", true);
        settings.put("autoSave", true);
        json.put("settings", settings);

        // Custom data
        if (project.getCustomData() != null && !project.getCustomData().isEmpty()) {
            JSONObject customData = new JSONObject();
            for (Map.Entry<String, Object> entry : project.getCustomData().entrySet()) {
                customData.put(entry.getKey(), entry.getValue());
            }
            json.put("customData", customData);
        }

        return json.toString(2); // Pretty print with 2-space indentation
    }

    /**
     * Deserialize a VideoProject from JSON string
     */
    public static VideoProject deserializeProject(String jsonString) throws JSONException {
        JSONObject json = new JSONObject(jsonString);

        VideoProject project = new VideoProject();

        // Project metadata
        project.setProjectId(json.getString("projectId"));
        project.setProjectName(json.getString("projectName"));
        project.setCreatedAt(json.getLong("createdAt"));
        project.setLastModified(json.getLong("lastModified"));
        project.setDuration(json.getLong("duration"));

        // Media asset information
        if (json.has("primaryMediaAssetId") && !json.isNull("primaryMediaAssetId")) {
            project.setPrimaryMediaAssetId(json.getString("primaryMediaAssetId"));
        }

        if (json.has("mediaAssetIds")) {
            JSONArray mediaAssetIds = json.getJSONArray("mediaAssetIds");
            List<String> assetIds = new ArrayList<>();
            for (int i = 0; i < mediaAssetIds.length(); i++) {
                assetIds.add(mediaAssetIds.getString(i));
            }
            project.setMediaAssetIds(assetIds);
        }

        // Legacy media information
        if (json.has("media")) {
            JSONObject media = json.getJSONObject("media");
            if (media.has("originalVideo")) {
                JSONObject originalVideo = media.getJSONObject("originalVideo");

                if (originalVideo.has("path") && !originalVideo.isNull("path")) {
                    project.setOriginalVideoPath(originalVideo.getString("path"));
                }

                if (originalVideo.has("uri") && !originalVideo.isNull("uri")) {
                    String uriString = originalVideo.getString("uri");
                    project.setOriginalVideoUri(Uri.parse(uriString));
                }

                if (originalVideo.has("metadata")) {
                    JSONObject metadataJson = originalVideo.getJSONObject("metadata");
                    VideoMetadata metadata = new VideoMetadata();
                    metadata.setCodec(metadataJson.optString("codec"));
                    metadata.setWidth(metadataJson.optInt("width"));
                    metadata.setHeight(metadataJson.optInt("height"));
                    metadata.setDuration(metadataJson.optLong("duration"));
                    metadata.setBitrate(metadataJson.optInt("bitrate"));
                    metadata.setFrameRate((float) metadataJson.optDouble("frameRate"));
                    metadata.setColorFormat(metadataJson.optString("colorFormat"));
                    project.setMetadata(metadata);
                }
            }
        }

        // Timeline data
        if (json.has("timeline")) {
            JSONObject timeline = json.getJSONObject("timeline");

            // Deserialize operations
            if (timeline.has("operations")) {
                JSONArray operations = timeline.getJSONArray("operations");
                List<EditOperation> operationsList = new ArrayList<>();

                for (int i = 0; i < operations.length(); i++) {
                    JSONObject opJson = operations.getJSONObject(i);
                    EditOperation operation = new EditOperation();

                    operation.setType(EditOperation.Type.valueOf(opJson.getString("type")));
                    operation.setStartTime(opJson.getLong("startTime"));
                    operation.setEndTime(opJson.getLong("endTime"));
                    operation.setRequiresReencoding(opJson.optBoolean("requiresReencoding", false));

                    // Deserialize parameters
                    if (opJson.has("parameters")) {
                        JSONObject params = opJson.getJSONObject("parameters");
                        Map<String, Object> parameters = new HashMap<>();
                        for (java.util.Iterator<String> keys = params.keys(); keys.hasNext();) {
                            String key = keys.next();
                            parameters.put(key, params.get(key));
                        }
                        operation.setParameters(parameters);
                    }

                    operationsList.add(operation);
                }

                project.setOperations(operationsList);
            }
        }

        // Custom data
        if (json.has("customData")) {
            JSONObject customDataJson = json.getJSONObject("customData");
            Map<String, Object> customData = new HashMap<>();
            for (java.util.Iterator<String> keys = customDataJson.keys(); keys.hasNext();) {
                String key = keys.next();
                customData.put(key, customDataJson.get(key));
            }
            project.setCustomData(customData);
        }

        return project;
    }

    /**
     * Serialize EditorState to JSON string
     */
    public static String serializeEditorState(EditorState editorState) throws JSONException {
        return editorState.toJson().toString(2);
    }

    /**
     * Deserialize EditorState from JSON string
     */
    public static EditorState deserializeEditorState(String jsonString) throws JSONException {
        JSONObject json = new JSONObject(jsonString);
        return EditorState.fromJson(json);
    }
}