package com.fadcam.fadrec.ui.annotation;

import com.fadcam.fadrec.ui.annotation.objects.AnnotationObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command for clearing all objects from all layers in a page.
 * This is a composite command that clears all layers atomically,
 * allowing proper undo/redo of "Delete All" operation.
 */
public class ClearAllLayersCommand implements DrawingCommand {
    private AnnotationPage page;
    private Map<AnnotationLayer, List<AnnotationObject>> previousObjectsByLayer;
    
    public ClearAllLayersCommand(AnnotationPage page) {
        this.page = page;
        this.previousObjectsByLayer = new HashMap<>();
        
        // Save all objects from all layers
        for (AnnotationLayer layer : page.getLayers()) {
            List<AnnotationObject> layerObjects = new ArrayList<>(layer.getObjects());
            previousObjectsByLayer.put(layer, layerObjects);
        }
    }
    
    @Override
    public void execute() {
        // Clear all layers
        for (AnnotationLayer layer : page.getLayers()) {
            layer.clearObjects();
        }
    }
    
    @Override
    public void undo() {
        // Restore all objects to all layers
        for (AnnotationLayer layer : page.getLayers()) {
            List<AnnotationObject> savedObjects = previousObjectsByLayer.get(layer);
            if (savedObjects != null) {
                layer.getObjects().clear();
                layer.getObjects().addAll(savedObjects);
            }
        }
    }
    
    @Override
    public String getDescription() {
        return "Clear all layers";
    }
}
