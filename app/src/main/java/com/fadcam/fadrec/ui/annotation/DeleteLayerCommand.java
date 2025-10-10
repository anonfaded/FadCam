package com.fadcam.fadrec.ui.annotation;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Command for soft-deleting a layer from a page.
 * Uses soft-delete system - marks layer as deleted but preserves it for complete version control.
 * Enables undo/redo for layer deletion without losing data.
 * Fully serializable for command history persistence.
 */
public class DeleteLayerCommand implements DrawingCommand {
    private static final String COMMAND_TYPE = "DELETE_LAYER";
    
    private AnnotationPage page;
    private String layerId; // Use ID instead of index for reliability after reload
    private transient AnnotationLayer layer; // Resolved from ID
    
    public DeleteLayerCommand(AnnotationPage page, int layerIndex) {
        this.page = page;
        this.layer = page.getLayers().get(layerIndex);
        this.layerId = layer.getId();
    }
    
    // Constructor for deserialization
    private DeleteLayerCommand(AnnotationPage page, String layerId) {
        this.page = page;
        this.layerId = layerId;
        resolveLayer();
    }
    
    private void resolveLayer() {
        for (AnnotationLayer l : page.getLayers()) {
            if (l.getId().equals(layerId)) {
                this.layer = l;
                break;
            }
        }
    }
    
    @Override
    public void execute() {
        if (layer == null) resolveLayer();
        if (layer != null) {
            layer.setDeleted(true);
            android.util.Log.d("DeleteLayerCommand", "Layer soft-deleted: " + layer.getName() + " (preserved in memory)");
        }
    }
    
    @Override
    public void undo() {
        if (layer == null) resolveLayer();
        if (layer != null) {
            layer.setDeleted(false);
            android.util.Log.d("DeleteLayerCommand", "Layer restored: " + layer.getName());
        }
    }
    
    @Override
    public String getDescription() {
        return "Delete layer: " + (layer != null ? layer.getName() : layerId);
    }
    
    @Override
    public String getCommandType() {
        return COMMAND_TYPE;
    }
    
    @Override
    public JSONObject toJSON() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("type", COMMAND_TYPE);
        json.put("layerId", layerId);
        return json;
    }
    
    public static DeleteLayerCommand fromJSON(AnnotationPage page, JSONObject json) throws JSONException {
        String layerId = json.getString("layerId");
        return new DeleteLayerCommand(page, layerId);
    }
}
