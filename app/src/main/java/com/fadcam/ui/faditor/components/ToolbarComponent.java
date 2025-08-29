package com.fadcam.ui.faditor.components;

import android.content.Context;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.fadcam.R;
import com.google.android.material.button.MaterialButton;

/**
 * Professional editing toolbar with Material 3 design and tool selection.
 * This is a placeholder implementation - full implementation will be in future tasks.
 */
public class ToolbarComponent extends LinearLayout {
    
    public enum Tool { 
        SELECT, TRIM, SPLIT, EFFECTS, AUDIO 
    }
    
    public interface ToolbarListener {
        void onToolSelected(Tool tool);
        void onToolAction(Tool tool, Bundle parameters);
    }
    
    private ToolbarListener listener;
    private Tool selectedTool = Tool.SELECT;
    
    // Tool buttons
    private MaterialButton selectButton;
    private MaterialButton trimButton;
    private MaterialButton splitButton;
    private MaterialButton effectsButton;
    private MaterialButton audioButton;
    
    public ToolbarComponent(Context context) {
        super(context);
        init();
    }
    
    public ToolbarComponent(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public ToolbarComponent(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        setOrientation(HORIZONTAL);
        
        // Inflate the toolbar layout
        LayoutInflater.from(getContext()).inflate(R.layout.component_toolbar, this, true);
        
        // Find buttons
        selectButton = findViewById(R.id.tool_select);
        trimButton = findViewById(R.id.tool_trim);
        splitButton = findViewById(R.id.tool_split);
        effectsButton = findViewById(R.id.tool_effects);
        audioButton = findViewById(R.id.tool_audio);
        
        // Set up click listeners
        setupClickListeners();
        
        // Set initial selection
        updateToolSelection();
    }
    
    private void setupClickListeners() {
        if (selectButton != null) {
            selectButton.setOnClickListener(v -> selectTool(Tool.SELECT));
        }
        if (trimButton != null) {
            trimButton.setOnClickListener(v -> selectTool(Tool.TRIM));
        }
        if (splitButton != null) {
            splitButton.setOnClickListener(v -> selectTool(Tool.SPLIT));
        }
        if (effectsButton != null) {
            effectsButton.setOnClickListener(v -> selectTool(Tool.EFFECTS));
        }
        if (audioButton != null) {
            audioButton.setOnClickListener(v -> selectTool(Tool.AUDIO));
        }
    }
    
    public void setToolbarListener(ToolbarListener listener) {
        this.listener = listener;
    }
    
    public void setSelectedTool(Tool tool) {
        this.selectedTool = tool;
        updateToolSelection();
    }
    
    public void setToolEnabled(Tool tool, boolean enabled) {
        MaterialButton button = getButtonForTool(tool);
        if (button != null) {
            button.setEnabled(enabled);
        }
    }
    
    private void selectTool(Tool tool) {
        this.selectedTool = tool;
        updateToolSelection();
        
        if (listener != null) {
            listener.onToolSelected(tool);
        }
    }
    
    private void updateToolSelection() {
        // Reset all buttons
        resetButtonStates();
        
        // Highlight selected tool
        MaterialButton selectedButton = getButtonForTool(selectedTool);
        if (selectedButton != null) {
            selectedButton.setSelected(true);
        }
    }
    
    private void resetButtonStates() {
        if (selectButton != null) selectButton.setSelected(false);
        if (trimButton != null) trimButton.setSelected(false);
        if (splitButton != null) splitButton.setSelected(false);
        if (effectsButton != null) effectsButton.setSelected(false);
        if (audioButton != null) audioButton.setSelected(false);
    }
    
    private MaterialButton getButtonForTool(Tool tool) {
        switch (tool) {
            case SELECT: return selectButton;
            case TRIM: return trimButton;
            case SPLIT: return splitButton;
            case EFFECTS: return effectsButton;
            case AUDIO: return audioButton;
            default: return null;
        }
    }
}