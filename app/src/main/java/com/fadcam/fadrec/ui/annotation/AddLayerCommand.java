package com.fadcam.fadrec.ui.annotation;

/**
 * Command for adding a new layer to a page.
 * Enables undo/redo for layer creation.
 */
public class AddLayerCommand implements DrawingCommand {
    private AnnotationPage page;
    private AnnotationLayer layer;
    private int insertPosition;
    
    public AddLayerCommand(AnnotationPage page, String layerName) {
        this.page = page;
        this.layer = new AnnotationLayer(layerName);
        this.insertPosition = page.getLayers().size(); // Add at end
    }
    
    @Override
    public void execute() {
        // Add layer at the saved position
        page.getLayers().add(insertPosition, layer);
    }
    
    @Override
    public void undo() {
        // Remove the layer
        page.getLayers().remove(layer);
        // Adjust active layer index if needed
        if (page.getActiveLayerIndex() >= page.getLayers().size()) {
            page.setActiveLayerIndex(page.getLayers().size() - 1);
        }
    }
    
    @Override
    public String getDescription() {
        return "Add layer: " + layer.getName();
    }
}
