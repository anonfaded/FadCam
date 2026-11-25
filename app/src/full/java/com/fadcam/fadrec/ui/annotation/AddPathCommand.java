package com.fadcam.fadrec.ui.annotation;

import android.graphics.Paint;
import android.graphics.Path;

import com.fadcam.fadrec.ui.annotation.objects.AnnotationObject;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Command for adding a drawing path/object to a layer.
 * Fully serializable for command history persistence.
 */
public class AddPathCommand implements DrawingCommand {
    private static final String COMMAND_TYPE = "ADD_PATH";
    
    private AnnotationLayer layer;
    private String layerId;
    private String objectId;
    private transient AnnotationObject object;
    private DrawingPath path; // Keep for backward compatibility during session
    
    public AddPathCommand(AnnotationLayer layer, Path path, Paint paint) {
        this.layer = layer;
        this.layerId = layer.getId();
        this.path = new DrawingPath(path, paint);
        // Path command will create object when executed
    }
    
    // Constructor for deserialization
    private AddPathCommand(AnnotationLayer layer, String objectId) {
        this.layer = layer;
        this.layerId = layer.getId();
        this.objectId = objectId;
        resolveObject();
    }
    
    private void resolveObject() {
        if (object == null && objectId != null) {
            for (AnnotationObject obj : layer.getObjects()) {
                if (obj.getId().equals(objectId)) {
                    this.object = obj;
                    break;
                }
            }
        }
    }
    
    @Override
    public void execute() {
        if (path != null) {
            // First execution - add the path
            layer.addPath(path);
            // Find the object that was just added to get its ID
            if (!layer.getObjects().isEmpty()) {
                object = layer.getObjects().get(layer.getObjects().size() - 1);
                objectId = object.getId();
            }
        } else if (object != null) {
            // Redo - undelete the object
            object.setDeleted(false);
        } else {
            resolveObject();
            if (object != null) {
                object.setDeleted(false);
            }
        }
    }
    
    @Override
    public void undo() {
        if (object == null) resolveObject();
        if (object != null) {
            // Soft-delete instead of removing
            object.setDeleted(true);
        }
    }
    
    @Override
    public String getDescription() {
        return "Draw path";
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
        json.put("objectId", objectId);
        return json;
    }
    
    public static AddPathCommand fromJSON(AnnotationLayer layer, JSONObject json) throws JSONException {
        String objectId = json.getString("objectId");
        return new AddPathCommand(layer, objectId);
    }
}
