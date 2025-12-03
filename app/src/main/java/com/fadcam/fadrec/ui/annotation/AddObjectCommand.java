package com.fadcam.fadrec.ui.annotation;

import com.fadcam.fadrec.ui.annotation.objects.AnnotationObject;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Command for adding a generic AnnotationObject (TextObject, ShapeObject, etc.) to a layer.
 * Uses the command pattern to ensure all object additions are tracked in version control.
 * Different from AddPathCommand which is specifically for path drawing.
 */
public class AddObjectCommand implements DrawingCommand {
    private static final String COMMAND_TYPE = "ADD_OBJECT";
    
    private final AnnotationLayer layer;
    private final AnnotationObject object;
    private boolean wasAdded = false;
    
    /**
     * Create command to add object to layer.
     */
    public AddObjectCommand(AnnotationLayer layer, AnnotationObject object) {
        this.layer = layer;
        this.object = object;
    }
    
    @Override
    public void execute() {
        if (!wasAdded && !layer.getObjects().contains(object)) {
            layer.addObject(object);
            wasAdded = true;
        }
    }
    
    @Override
    public void undo() {
        if (wasAdded && layer.getObjects().contains(object)) {
            layer.getObjects().remove(object);
            wasAdded = false;
        }
    }
    
    @Override
    public String getDescription() {
        return "Add " + object.getClass().getSimpleName();
    }
    
    @Override
    public String getCommandType() {
        return COMMAND_TYPE;
    }
    
    @Override
    public JSONObject toJSON() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("type", COMMAND_TYPE);
        json.put("layerId", layer.getId());
        json.put("object", object.toJSON());
        return json;
    }
    
    /**
     * Deserialize command from JSON.
     */
    public static AddObjectCommand fromJSON(AnnotationLayer layer, JSONObject json) 
            throws JSONException {
        JSONObject objectJson = json.getJSONObject("object");
        
        // Deserialize the object using the same pattern as AnnotationLayer
        String typeStr = objectJson.getString("type");
        com.fadcam.fadrec.ui.annotation.objects.AnnotationObject.ObjectType type = 
            com.fadcam.fadrec.ui.annotation.objects.AnnotationObject.ObjectType.valueOf(typeStr);
        
        AnnotationObject object;
        switch (type) {
            case PATH:
                object = new com.fadcam.fadrec.ui.annotation.objects.PathObject();
                break;
            case TEXT:
                object = new com.fadcam.fadrec.ui.annotation.objects.TextObject();
                break;
            case SHAPE:
                object = new com.fadcam.fadrec.ui.annotation.objects.ShapeObject();
                break;
            default:
                throw new JSONException("Unknown object type: " + type);
        }
        
        object.fromJSON(objectJson);
        return new AddObjectCommand(layer, object);
    }
}
