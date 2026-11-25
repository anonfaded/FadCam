package com.fadcam.fadrec.ui.annotation;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Command for adding a new layer to a page.
 * Enables undo/redo for layer creation.
 * Uses soft-delete for undo - marks layer as deleted instead of removing.
 * Fully serializable for command history persistence.
 */
public class AddLayerCommand implements DrawingCommand {
    private static final String COMMAND_TYPE = "ADD_LAYER";
    
    private AnnotationPage page;
    private String layerId;
    private String layerName;
    private transient AnnotationLayer layer;
    
    public AddLayerCommand(AnnotationPage page, String layerName) {
        this.page = page;
        this.layerName = layerName;
        this.layer = new AnnotationLayer(layerName);
        this.layerId = layer.getId();
    }
    
    // Constructor for deserialization
    private AddLayerCommand(AnnotationPage page, String layerId, String layerName) {
        this.page = page;
        this.layerId = layerId;
        this.layerName = layerName;
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
        if (layer == null) {
            resolveLayer();
        }
        
        // If layer doesn't exist yet, it was created in constructor - add it to page
        if (!page.getLayers().contains(layer)) {
            page.getLayers().add(layer);
        }
        
        // Ensure layer is not marked as deleted (for redo case)
        layer.setDeleted(false);
    }
    
    @Override
    public void undo() {
        if (layer == null) resolveLayer();
        if (layer != null) {
            // Soft-delete instead of removing
            layer.setDeleted(true);
        }
    }
    
    @Override
    public String getDescription() {
        return "Add layer: " + layerName;
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
        json.put("layerName", layerName);
        return json;
    }
    
    public static AddLayerCommand fromJSON(AnnotationPage page, JSONObject json) throws JSONException {
        String layerId = json.getString("layerId");
        String layerName = json.getString("layerName");
        return new AddLayerCommand(page, layerId, layerName);
    }
}
