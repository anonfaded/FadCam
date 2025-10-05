package com.fadcam.fadrec.ui.annotation;

import android.graphics.Paint;
import android.graphics.Path;

/**
 * Command for adding a drawing path to a layer.
 */
public class AddPathCommand implements DrawingCommand {
    private AnnotationLayer layer;
    private DrawingPath path;
    
    public AddPathCommand(AnnotationLayer layer, Path path, Paint paint) {
        this.layer = layer;
        this.path = new DrawingPath(path, paint);
    }
    
    @Override
    public void execute() {
        layer.addPath(path);
    }
    
    @Override
    public void undo() {
        layer.removePath(path);
    }
    
    @Override
    public String getDescription() {
        return "Draw path";
    }
}
