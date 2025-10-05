package com.fadcam.fadrec.ui.annotation;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Root state object containing all annotation pages.
 * Manages page switching and persistence.
 */
public class AnnotationState implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private List<AnnotationPage> pages;
    private int activePageIndex;
    private long createdAt;
    private long modifiedAt;
    
    // Drawing tool state (shared across all pages)
    private int currentColor;
    private float currentStrokeWidth;
    private boolean isEraserMode;
    
    public AnnotationState() {
        this.pages = new ArrayList<>();
        this.activePageIndex = 0;
        this.createdAt = System.currentTimeMillis();
        this.modifiedAt = createdAt;
        
        // Default tool state
        this.currentColor = 0xFFF44336; // Red
        this.currentStrokeWidth = 8f; // Medium
        this.isEraserMode = false;
        
        // Create default page
        pages.add(new AnnotationPage("Page 1"));
    }
    
    // Page management
    public List<AnnotationPage> getPages() { return pages; }
    
    public AnnotationPage getActivePage() {
        if (activePageIndex >= 0 && activePageIndex < pages.size()) {
            return pages.get(activePageIndex);
        }
        return null;
    }
    
    public int getActivePageIndex() { return activePageIndex; }
    
    public void setActivePageIndex(int index) {
        if (index >= 0 && index < pages.size()) {
            this.activePageIndex = index;
            this.modifiedAt = System.currentTimeMillis();
        }
    }
    
    public void addPage(String name) {
        pages.add(new AnnotationPage(name));
        this.modifiedAt = System.currentTimeMillis();
    }
    
    public void removePage(int index) {
        if (pages.size() > 1 && index >= 0 && index < pages.size()) {
            pages.remove(index);
            if (activePageIndex >= pages.size()) {
                activePageIndex = pages.size() - 1;
            }
            this.modifiedAt = System.currentTimeMillis();
        }
    }
    
    public void movePage(int fromIndex, int toIndex) {
        if (fromIndex >= 0 && fromIndex < pages.size() && 
            toIndex >= 0 && toIndex < pages.size()) {
            AnnotationPage page = pages.remove(fromIndex);
            pages.add(toIndex, page);
            this.modifiedAt = System.currentTimeMillis();
        }
    }
    
    // Tool state
    public int getCurrentColor() { return currentColor; }
    public void setCurrentColor(int color) { 
        this.currentColor = color;
        this.isEraserMode = false;
    }
    
    public float getCurrentStrokeWidth() { return currentStrokeWidth; }
    public void setCurrentStrokeWidth(float width) { this.currentStrokeWidth = width; }
    
    public boolean isEraserMode() { return isEraserMode; }
    public void setEraserMode(boolean enabled) { this.isEraserMode = enabled; }
    
    public long getCreatedAt() { return createdAt; }
    public long getModifiedAt() { return modifiedAt; }
    
    /**
     * Reconstruct transient fields after deserialization
     */
    public void reconstruct() {
        for (AnnotationPage page : pages) {
            page.reconstruct();
        }
    }
}
