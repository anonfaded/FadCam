package com.fadcam.ui.faditor.components;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.fadcam.Log;
import com.fadcam.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.elevation.SurfaceColors;

import java.util.HashMap;
import java.util.Map;

/**
 * Professional editing toolbar with Material 3 design, smooth animations, and contextual tool options.
 * Implements requirements 11.2, 11.4, 11.5, 11.6 for professional video editor interface.
 */
public class ToolbarComponent extends LinearLayout {
    
    private static final String TAG = "ToolbarComponent";
    private static final long ANIMATION_DURATION = 200L;
    private static final float SELECTED_ELEVATION = 8f;
    private static final float UNSELECTED_ELEVATION = 2f;
    
    public enum Tool { 
        SELECT("Select", R.drawable.ic_cursor_default, "Select and move elements"),
        TRIM("Trim", R.drawable.ic_content_cut, "Trim video segments"),
        SPLIT("Split", R.drawable.ic_call_split, "Split video at current position"),
        EFFECTS("Effects", R.drawable.ic_auto_fix_high, "Apply visual effects"),
        AUDIO("Audio", R.drawable.ic_volume_up, "Edit audio tracks");
        
        private final String displayName;
        private final int iconRes;
        private final String description;
        
        Tool(String displayName, int iconRes, String description) {
            this.displayName = displayName;
            this.iconRes = iconRes;
            this.description = description;
        }
        
        public String getDisplayName() { return displayName; }
        public int getIconRes() { return iconRes; }
        public String getDescription() { return description; }
    }
    
    public interface ToolbarListener {
        void onToolSelected(Tool tool);
        void onToolAction(Tool tool, Bundle parameters);
    }
    
    // Core components
    private ToolbarListener listener;
    private Tool selectedTool = Tool.SELECT;
    private boolean isAnimating = false;
    
    // UI components
    private LinearLayout toolButtonContainer;
    private MaterialCardView contextualOptionsPanel;
    private ViewGroup contextualOptionsContent;
    
    // Tool buttons mapping
    private final Map<Tool, MaterialButton> toolButtons = new HashMap<>();
    private final Map<Tool, View> contextualOptions = new HashMap<>();
    
    // Animation components
    private AnimatorSet currentAnimation;
    private final FastOutSlowInInterpolator interpolator = new FastOutSlowInInterpolator();
    
    // State management
    private Bundle toolSettings = new Bundle();
    private boolean isInitialized = false;
    
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
        setOrientation(VERTICAL);
        
        // Inflate the enhanced toolbar layout
        LayoutInflater.from(getContext()).inflate(R.layout.component_toolbar_enhanced, this, true);
        
        // Initialize UI components
        initializeViews();
        
        // Set up tool buttons
        setupToolButtons();
        
        // Set up contextual options
        setupContextualOptions();
        
        // Apply Material 3 theming
        applyMaterial3Theming();
        
        // Set initial selection
        updateToolSelection();
        
        isInitialized = true;
        Log.d(TAG, "Professional toolbar initialized with Material 3 design");
    }
    
    private void initializeViews() {
        toolButtonContainer = findViewById(R.id.tool_button_container);
        contextualOptionsPanel = findViewById(R.id.contextual_options_panel);
        contextualOptionsContent = findViewById(R.id.contextual_options_content);
        
        if (toolButtonContainer == null) {
            Log.e(TAG, "Tool button container not found in layout");
            return;
        }
        
        // Initially hide contextual options panel
        if (contextualOptionsPanel != null) {
            contextualOptionsPanel.setVisibility(View.GONE);
            contextualOptionsPanel.setAlpha(0f);
        }
    }
    
    private void setupToolButtons() {
        if (toolButtonContainer == null) {
            return;
        }
        
        // Create tool buttons dynamically for better control
        for (Tool tool : Tool.values()) {
            MaterialButton button = createToolButton(tool);
            toolButtons.put(tool, button);
            toolButtonContainer.addView(button);
        }
        
        Log.d(TAG, "Created " + toolButtons.size() + " tool buttons");
    }
    
    private MaterialButton createToolButton(Tool tool) {
        MaterialButton button = new MaterialButton(getContext(), null, 
            com.google.android.material.R.attr.materialIconButtonStyle);
        
        // Configure button properties
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        button.setLayoutParams(params);
        
        // Set icon and content description
        button.setIcon(ContextCompat.getDrawable(getContext(), tool.getIconRes()));
        button.setIconSize(getResources().getDimensionPixelSize(R.dimen.toolbar_icon_size));
        button.setContentDescription(tool.getDescription());
        
        // Set click listener
        button.setOnClickListener(v -> selectTool(tool));
        
        // Apply initial styling
        applyButtonStyling(button, false);
        
        return button;
    }
    
    private void setupContextualOptions() {
        // Create contextual option panels for each tool
        for (Tool tool : Tool.values()) {
            View optionsView = createContextualOptionsForTool(tool);
            if (optionsView != null) {
                contextualOptions.put(tool, optionsView);
            }
        }
    }
    
    private View createContextualOptionsForTool(Tool tool) {
        LayoutInflater inflater = LayoutInflater.from(getContext());
        
        switch (tool) {
            case TRIM:
                return inflater.inflate(R.layout.contextual_options_trim, null);
            case SPLIT:
                return inflater.inflate(R.layout.contextual_options_split, null);
            case EFFECTS:
                return inflater.inflate(R.layout.contextual_options_effects, null);
            case AUDIO:
                return inflater.inflate(R.layout.contextual_options_audio, null);
            case SELECT:
            default:
                return inflater.inflate(R.layout.contextual_options_select, null);
        }
    }
    
    private void applyMaterial3Theming() {
        // Apply Material 3 surface colors and elevation
        setBackgroundColor(SurfaceColors.SURFACE_2.getColor(getContext()));
        
        // Apply elevation for depth
        setElevation(getResources().getDimension(R.dimen.toolbar_elevation));
        
        // Apply Material 3 corner radius
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            setClipToOutline(true);
        }
    }
    
    private void applyButtonStyling(MaterialButton button, boolean isSelected) {
        Context context = getContext();
        
        if (isSelected) {
            // Selected state styling with app theme colors
            button.setIconTint(ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.colorPrimary)));
            button.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.colorButton)));
            button.setElevation(SELECTED_ELEVATION);
        } else {
            // Unselected state styling using theme attributes
            android.util.TypedValue typedValue = new android.util.TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.textColorSecondary, typedValue, true);
            int textColor = typedValue.data;
            
            button.setIconTint(ColorStateList.valueOf(textColor));
            button.setBackgroundTintList(null); // Use default background
            button.setElevation(UNSELECTED_ELEVATION);
        }
        
        // Apply state layer for touch feedback
        button.setRippleColor(ColorStateList.valueOf(
            ContextCompat.getColor(context, R.color.colorPrimary)));
    }
    
    // Public API methods
    
    public void setToolbarListener(ToolbarListener listener) {
        this.listener = listener;
    }
    
    public void setSelectedTool(Tool tool) {
        if (tool == null || tool == selectedTool) {
            return;
        }
        
        Tool previousTool = selectedTool;
        this.selectedTool = tool;
        
        // Animate tool selection change
        animateToolSelection(previousTool, tool);
        
        // Update contextual options
        showContextualOptions(tool);
        
        Log.d(TAG, "Tool selected: " + tool.getDisplayName());
    }
    
    public Tool getSelectedTool() {
        return selectedTool;
    }
    
    public void setToolEnabled(Tool tool, boolean enabled) {
        MaterialButton button = toolButtons.get(tool);
        if (button != null) {
            button.setEnabled(enabled);
            button.setAlpha(enabled ? 1.0f : 0.5f);
        }
    }
    
    public boolean isToolEnabled(Tool tool) {
        MaterialButton button = toolButtons.get(tool);
        return button != null && button.isEnabled();
    }
    
    public Bundle getToolSettings() {
        return new Bundle(toolSettings);
    }
    
    public void setToolSettings(Bundle settings) {
        if (settings != null) {
            toolSettings.putAll(settings);
        }
    }
    
    public void updateToolSetting(String key, Object value) {
        if (key != null && value != null) {
            if (value instanceof String) {
                toolSettings.putString(key, (String) value);
            } else if (value instanceof Integer) {
                toolSettings.putInt(key, (Integer) value);
            } else if (value instanceof Float) {
                toolSettings.putFloat(key, (Float) value);
            } else if (value instanceof Boolean) {
                toolSettings.putBoolean(key, (Boolean) value);
            }
            
            // Notify listener of tool action with updated settings
            if (listener != null) {
                Bundle params = new Bundle();
                params.putString("action", "setting_changed");
                params.putString("key", key);
                params.putAll(toolSettings);
                listener.onToolAction(selectedTool, params);
            }
        }
    }
    
    // Tool selection and animation methods
    
    private void selectTool(Tool tool) {
        if (tool == null || isAnimating || tool == selectedTool) {
            return;
        }
        
        Tool previousTool = selectedTool;
        this.selectedTool = tool;
        
        // Animate tool selection with smooth transitions
        animateToolSelection(previousTool, tool);
        
        // Show contextual options for selected tool
        showContextualOptions(tool);
        
        // Notify listener
        if (listener != null) {
            listener.onToolSelected(tool);
        }
        
        Log.d(TAG, "Tool selected with animation: " + tool.getDisplayName());
    }
    
    private void animateToolSelection(Tool previousTool, Tool newTool) {
        if (isAnimating) {
            return;
        }
        
        isAnimating = true;
        
        // Cancel any existing animation
        if (currentAnimation != null) {
            currentAnimation.cancel();
        }
        
        AnimatorSet animatorSet = new AnimatorSet();
        
        // Animate previous tool deselection
        MaterialButton previousButton = toolButtons.get(previousTool);
        if (previousButton != null) {
            ObjectAnimator deselectAnim = createDeselectAnimation(previousButton);
            animatorSet.play(deselectAnim);
        }
        
        // Animate new tool selection
        MaterialButton newButton = toolButtons.get(newTool);
        if (newButton != null) {
            ObjectAnimator selectAnim = createSelectAnimation(newButton);
            animatorSet.play(selectAnim);
        }
        
        // Set animation properties
        animatorSet.setDuration(ANIMATION_DURATION);
        animatorSet.setInterpolator(interpolator);
        
        // Add animation listener
        animatorSet.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                isAnimating = false;
                currentAnimation = null;
                
                // Ensure final states are correct
                updateToolSelection();
            }
        });
        
        currentAnimation = animatorSet;
        animatorSet.start();
    }
    
    private ObjectAnimator createSelectAnimation(MaterialButton button) {
        // Create scale and elevation animation for selection
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(button, "scaleX", 1.0f, 1.1f, 1.0f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(button, "scaleY", 1.0f, 1.1f, 1.0f);
        ObjectAnimator elevation = ObjectAnimator.ofFloat(button, "elevation", 
            UNSELECTED_ELEVATION, SELECTED_ELEVATION);
        
        AnimatorSet selectSet = new AnimatorSet();
        selectSet.playTogether(scaleX, scaleY, elevation);
        
        // Apply selected styling
        selectSet.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(android.animation.Animator animation) {
                applyButtonStyling(button, true);
            }
        });
        
        return ObjectAnimator.ofFloat(button, "alpha", 1.0f);
    }
    
    private ObjectAnimator createDeselectAnimation(MaterialButton button) {
        // Create elevation animation for deselection
        ObjectAnimator elevation = ObjectAnimator.ofFloat(button, "elevation", 
            SELECTED_ELEVATION, UNSELECTED_ELEVATION);
        
        // Apply unselected styling
        elevation.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(android.animation.Animator animation) {
                applyButtonStyling(button, false);
            }
        });
        
        return elevation;
    }
    
    private void updateToolSelection() {
        if (!isInitialized) {
            return;
        }
        
        // Update all button states without animation
        for (Map.Entry<Tool, MaterialButton> entry : toolButtons.entrySet()) {
            Tool tool = entry.getKey();
            MaterialButton button = entry.getValue();
            boolean isSelected = tool == selectedTool;
            
            applyButtonStyling(button, isSelected);
            button.setSelected(isSelected);
        }
    }
    
    // Contextual options methods
    
    private void showContextualOptions(Tool tool) {
        if (contextualOptionsPanel == null || contextualOptionsContent == null) {
            return;
        }
        
        View optionsView = contextualOptions.get(tool);
        if (optionsView == null) {
            // Hide panel if no options for this tool
            hideContextualOptions();
            return;
        }
        
        // Clear existing content
        contextualOptionsContent.removeAllViews();
        
        // Add new options view
        contextualOptionsContent.addView(optionsView);
        
        // Setup options for the specific tool
        setupToolSpecificOptions(tool, optionsView);
        
        // Animate panel appearance
        animateContextualOptionsIn();
    }
    
    private void hideContextualOptions() {
        if (contextualOptionsPanel == null) {
            return;
        }
        
        animateContextualOptionsOut();
    }
    
    private void animateContextualOptionsIn() {
        if (contextualOptionsPanel == null || contextualOptionsPanel.getVisibility() == View.VISIBLE) {
            return;
        }
        
        contextualOptionsPanel.setVisibility(View.VISIBLE);
        
        ObjectAnimator fadeIn = ObjectAnimator.ofFloat(contextualOptionsPanel, "alpha", 0f, 1f);
        ObjectAnimator slideIn = ObjectAnimator.ofFloat(contextualOptionsPanel, "translationY", 
            contextualOptionsPanel.getHeight(), 0f);
        
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(fadeIn, slideIn);
        animatorSet.setDuration(ANIMATION_DURATION);
        animatorSet.setInterpolator(interpolator);
        animatorSet.start();
    }
    
    private void animateContextualOptionsOut() {
        if (contextualOptionsPanel == null || contextualOptionsPanel.getVisibility() != View.VISIBLE) {
            return;
        }
        
        ObjectAnimator fadeOut = ObjectAnimator.ofFloat(contextualOptionsPanel, "alpha", 1f, 0f);
        ObjectAnimator slideOut = ObjectAnimator.ofFloat(contextualOptionsPanel, "translationY", 
            0f, contextualOptionsPanel.getHeight());
        
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(fadeOut, slideOut);
        animatorSet.setDuration(ANIMATION_DURATION);
        animatorSet.setInterpolator(interpolator);
        
        animatorSet.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                contextualOptionsPanel.setVisibility(View.GONE);
            }
        });
        
        animatorSet.start();
    }
    
    private void setupToolSpecificOptions(Tool tool, View optionsView) {
        // Setup tool-specific contextual options
        switch (tool) {
            case TRIM:
                setupTrimOptions(optionsView);
                break;
            case SPLIT:
                setupSplitOptions(optionsView);
                break;
            case EFFECTS:
                setupEffectsOptions(optionsView);
                break;
            case AUDIO:
                setupAudioOptions(optionsView);
                break;
            case SELECT:
            default:
                setupSelectOptions(optionsView);
                break;
        }
    }
    
    private void setupTrimOptions(View optionsView) {
        // Setup trim tool options (precision mode, snap to frame, etc.)
        // This will be expanded in future implementations
    }
    
    private void setupSplitOptions(View optionsView) {
        // Setup split tool options (split at current position, etc.)
        // This will be expanded in future implementations
    }
    
    private void setupEffectsOptions(View optionsView) {
        // Setup effects tool options (effect selection, intensity, etc.)
        // This will be expanded in future implementations
    }
    
    private void setupAudioOptions(View optionsView) {
        // Setup audio tool options (volume, mute, audio effects, etc.)
        // This will be expanded in future implementations
    }
    
    private void setupSelectOptions(View optionsView) {
        // Setup select tool options (selection mode, multi-select, etc.)
        // This will be expanded in future implementations
    }
    
    // Cleanup methods
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        
        // Cancel any running animations
        if (currentAnimation != null) {
            currentAnimation.cancel();
            currentAnimation = null;
        }
        
        // Clear references
        toolButtons.clear();
        contextualOptions.clear();
        listener = null;
        
        Log.d(TAG, "Toolbar component cleaned up");
    }
}