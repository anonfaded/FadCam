package com.fadcam.fadrec.ui.annotation;

/**
 * Command for deleting a layer from a page.
 * Enables undo/redo for layer deletion.
 */
public class DeleteLayerCommand implements DrawingCommand {
    private AnnotationPage page;
    private AnnotationLayer layer;
    private int originalPosition;
    private int originalActiveIndex;
    
    public DeleteLayerCommand(AnnotationPage page, int layerIndex) {
        this.page = page;
        this.originalPosition = layerIndex;
        this.layer = page.getLayers().get(layerIndex);
        this.originalActiveIndex = page.getActiveLayerIndex();
    }
    
    @Override
    public void execute() {
        // Remove the layer
        page.getLayers().remove(originalPosition);
        // Adjust active layer index if needed
        if (page.getActiveLayerIndex() >= page.getLayers().size()) {
            page.setActiveLayerIndex(page.getLayers().size() - 1);
        }
    }
    
    @Override
    public void undo() {
        // Restore the layer at its original position
        page.getLayers().add(originalPosition, layer);
        // Restore original active index if valid
        if (originalActiveIndex < page.getLayers().size()) {
            page.setActiveLayerIndex(originalActiveIndex);
        }
    }
    
    @Override
    public String getDescription() {
        return "Delete layer: " + layer.getName();
    }
}
