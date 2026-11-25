package com.fadcam.fadrec.ui.annotation;

import com.fadcam.fadrec.ui.annotation.objects.AnnotationObject;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Command for soft-deleting all objects from a layer.
 * Uses soft-delete for version control - marks objects as deleted but preserves data.
 * Fully serializable for command history persistence.
 */
public class ClearLayerCommand implements DrawingCommand {
    private static final String COMMAND_TYPE = "CLEAR_LAYER";
    
    private AnnotationPage page;
    private String layerId;
    private transient AnnotationLayer layer;
    private java.util.List<String> objectIds; // IDs of objects that were cleared
    
    public ClearLayerCommand(AnnotationPage page, AnnotationLayer layer) {
        this.page = page;
        this.layer = layer;
        this.layerId = layer.getId();
        
        // Store IDs of all non-deleted objects
        this.objectIds = new java.util.ArrayList<>();
        for (AnnotationObject obj : layer.getObjects()) {
            if (!obj.isDeleted()) {
                objectIds.add(obj.getId());
            }
        }
    }
    
    // Constructor for deserialization
    private ClearLayerCommand(AnnotationPage page, String layerId, java.util.List<String> objectIds) {
        this.page = page;
        this.layerId = layerId;
        this.objectIds = objectIds;
        resolveLayer();
    }
    
    private void resolveLayer() {
        this.layer = page.getLayerById(layerId);
    }
    
    @Override
    public void execute() {
        if (layer == null) resolveLayer();
        if (layer != null) {
            // Soft-delete all objects by ID
            for (String objectId : objectIds) {
                for (AnnotationObject obj : layer.getObjects()) {
                    if (obj.getId().equals(objectId)) {
                        obj.setDeleted(true);
                        break;
                    }
                }
            }
        }
    }
    
    @Override
    public void undo() {
        if (layer == null) resolveLayer();
        if (layer != null) {
            // Restore all objects
            for (String objectId : objectIds) {
                for (AnnotationObject obj : layer.getObjects()) {
                    if (obj.getId().equals(objectId)) {
                        obj.setDeleted(false);
                        break;
                    }
                }
            }
        }
    }
    
    @Override
    public String getDescription() {
        return "Clear layer";
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
        
        JSONArray objectIdsArray = new JSONArray();
        for (String objectId : objectIds) {
            objectIdsArray.put(objectId);
        }
        json.put("objectIds", objectIdsArray);
        
        return json;
    }
    
    public static ClearLayerCommand fromJSON(AnnotationPage page, JSONObject json) throws JSONException {
        String layerId = json.getString("layerId");
        
        JSONArray objectIdsArray = json.getJSONArray("objectIds");
        java.util.List<String> objectIds = new java.util.ArrayList<>();
        for (int i = 0; i < objectIdsArray.length(); i++) {
            objectIds.add(objectIdsArray.getString(i));
        }
        
        return new ClearLayerCommand(page, layerId, objectIds);
    }
}
