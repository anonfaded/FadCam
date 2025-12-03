package com.fadcam.fadrec.ui.annotation;

import com.fadcam.fadrec.ui.annotation.objects.AnnotationObject;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command for soft-deleting all objects from all layers in a page.
 * This is a composite command that clears all layers atomically,
 * allowing proper undo/redo of "Delete All" operation.
 * Uses soft-delete for complete version control.
 * Fully serializable for command history persistence.
 */
public class ClearAllLayersCommand implements DrawingCommand {
    private static final String COMMAND_TYPE = "CLEAR_ALL_LAYERS";
    
    private AnnotationPage page;
    private Map<String, List<String>> objectIdsByLayerId; // layerId -> list of object IDs
    
    public ClearAllLayersCommand(AnnotationPage page) {
        this.page = page;
        this.objectIdsByLayerId = new HashMap<>();
        
        // Save all object IDs from all layers
        for (AnnotationLayer layer : page.getLayers()) {
            List<String> layerObjectIds = new ArrayList<>();
            for (AnnotationObject obj : layer.getObjects()) {
                if (!obj.isDeleted()) {
                    layerObjectIds.add(obj.getId());
                }
            }
            if (!layerObjectIds.isEmpty()) {
                objectIdsByLayerId.put(layer.getId(), layerObjectIds);
            }
        }
    }
    
    // Constructor for deserialization
    private ClearAllLayersCommand(AnnotationPage page, Map<String, List<String>> objectIdsByLayerId) {
        this.page = page;
        this.objectIdsByLayerId = objectIdsByLayerId;
    }
    
    @Override
    public void execute() {
        // Soft-delete all objects in all layers
        for (AnnotationLayer layer : page.getLayers()) {
            List<String> objectIds = objectIdsByLayerId.get(layer.getId());
            if (objectIds != null) {
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
    }
    
    @Override
    public void undo() {
        // Restore all objects in all layers
        for (AnnotationLayer layer : page.getLayers()) {
            List<String> objectIds = objectIdsByLayerId.get(layer.getId());
            if (objectIds != null) {
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
    }
    
    @Override
    public String getDescription() {
        return "Clear all layers";
    }
    
    @Override
    public String getCommandType() {
        return COMMAND_TYPE;
    }
    
    @Override
    public JSONObject toJSON() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("type", COMMAND_TYPE);
        
        JSONObject layersObj = new JSONObject();
        for (Map.Entry<String, List<String>> entry : objectIdsByLayerId.entrySet()) {
            String layerId = entry.getKey();
            List<String> objectIds = entry.getValue();
            
            JSONArray objectIdsArray = new JSONArray();
            for (String objectId : objectIds) {
                objectIdsArray.put(objectId);
            }
            layersObj.put(layerId, objectIdsArray);
        }
        json.put("objectIdsByLayerId", layersObj);
        
        return json;
    }
    
    public static ClearAllLayersCommand fromJSON(AnnotationPage page, JSONObject json) throws JSONException {
        JSONObject layersObj = json.getJSONObject("objectIdsByLayerId");
        Map<String, List<String>> objectIdsByLayerId = new HashMap<>();
        
        java.util.Iterator<String> keys = layersObj.keys();
        while (keys.hasNext()) {
            String layerId = keys.next();
            JSONArray objectIdsArray = layersObj.getJSONArray(layerId);
            
            List<String> objectIds = new ArrayList<>();
            for (int i = 0; i < objectIdsArray.length(); i++) {
                objectIds.add(objectIdsArray.getString(i));
            }
            objectIdsByLayerId.put(layerId, objectIds);
        }
        
        return new ClearAllLayersCommand(page, objectIdsByLayerId);
    }
}
