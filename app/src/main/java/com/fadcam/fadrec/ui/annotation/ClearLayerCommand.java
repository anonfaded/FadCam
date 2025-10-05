package com.fadcam.fadrec.ui.annotation;

import com.fadcam.fadrec.ui.annotation.objects.AnnotationObject;

/**
 * Command for clearing all objects from a layer.
 */
public class ClearLayerCommand implements DrawingCommand {
    private AnnotationLayer layer;
    private java.util.List<AnnotationObject> previousObjects;
    
    public ClearLayerCommand(AnnotationLayer layer) {
        this.layer = layer;
        this.previousObjects = new java.util.ArrayList<>(layer.getObjects());
    }
    
    @Override
    public void execute() {
        layer.clearObjects();
    }
    
    @Override
    public void undo() {
        layer.getObjects().clear();
        layer.getObjects().addAll(previousObjects);
    }
    
    @Override
    public String getDescription() {
        return "Clear layer";
    }
}
