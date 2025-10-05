package com.fadcam.fadrec.ui.annotation;

/**
 * Command for clearing all paths from a layer.
 */
public class ClearLayerCommand implements DrawingCommand {
    private AnnotationLayer layer;
    private java.util.List<DrawingPath> previousPaths;
    
    public ClearLayerCommand(AnnotationLayer layer) {
        this.layer = layer;
        this.previousPaths = new java.util.ArrayList<>(layer.getPaths());
    }
    
    @Override
    public void execute() {
        layer.clearPaths();
    }
    
    @Override
    public void undo() {
        layer.getPaths().clear();
        layer.getPaths().addAll(previousPaths);
    }
    
    @Override
    public String getDescription() {
        return "Clear layer";
    }
}
