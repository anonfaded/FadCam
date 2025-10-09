package com.fadcam.fadrec.ui.overlay;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.util.Log;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.R;
import com.fadcam.fadrec.ui.annotation.objects.TextObject;

/**
 * Inline text editor overlay for creating and editing text annotations.
 * Provides Instagram/Telegram-style text editing with:
 * - Center-aligned text input with dashed border
 * - Real-time text preview
 * - Color picker
 * - Font size slider
 * - Text alignment options
 * - Font style options (bold, italic)
 * - Dimmed background for focus
 * - Soft delete support
 */
public class InlineTextEditor extends BaseEditorOverlay {
    
    private static final String TAG = "InlineTextEditor";
    
    // UI Components
    private View dimOverlay;
    private EditText editText;
    private LinearLayout colorPickerLayout;
    private SeekBar fontSizeSlider;
    private ImageView btnDone;
    private ImageView btnClear;
    private ImageView btnDelete;
    
    // Color buttons
    private View[] colorButtons;
    private static final int[] PRESET_COLORS = {
        Color.parseColor("#FFFFFF"), // White
        Color.parseColor("#000000"), // Black
        Color.parseColor("#FF3B30"), // Red
        Color.parseColor("#FF9500"), // Orange
        Color.parseColor("#FFCC00"), // Yellow
        Color.parseColor("#34C759"), // Green
        Color.parseColor("#5AC8FA"), // Cyan
        Color.parseColor("#007AFF"), // Blue
        Color.parseColor("#AF52DE"), // Purple
        Color.parseColor("#FF2D55"), // Pink
    };
    
    // Alignment buttons
    private ImageView btnAlignLeft;
    private ImageView btnAlignCenter;
    private ImageView btnAlignRight;
    
    // Style buttons
    private ImageView btnBold;
    private ImageView btnItalic;
    
    // Current state
    private int selectedColor = Color.WHITE;
    private int selectedAlignment = Gravity.CENTER;
    private int selectedFontSize = 28; // Default font size in sp
    private boolean isBold = false;
    private boolean isItalic = false;
    
    // Text object being edited (null for new text)
    private TextObject editingTextObject;
    private Typeface defaultTypeface;
    
    /**
     * Constructor
     */
    public InlineTextEditor(Context context, WindowManager windowManager) {
        super(context, windowManager);
        // Load default typeface (Ubuntu Regular)
        defaultTypeface = ResourcesCompat.getFont(context, R.font.ubuntu_regular);
    }
    
    @Override
    protected int getLayoutResourceId() {
        return R.layout.overlay_inline_text_editor;
    }
    
    @Override
    protected void onViewCreated(View view) {
        // Find views
        dimOverlay = view.findViewById(R.id.dimOverlay);
        editText = view.findViewById(R.id.editText);
        colorPickerLayout = view.findViewById(R.id.colorPickerLayout);
        fontSizeSlider = view.findViewById(R.id.fontSizeSlider);
        btnDone = view.findViewById(R.id.btnDone);
        btnClear = view.findViewById(R.id.btnClear);
        btnDelete = view.findViewById(R.id.btnDelete);
        
        // Alignment buttons
        btnAlignLeft = view.findViewById(R.id.btnAlignLeft);
        btnAlignCenter = view.findViewById(R.id.btnAlignCenter);
        btnAlignRight = view.findViewById(R.id.btnAlignRight);
        
        // Style buttons
        btnBold = view.findViewById(R.id.btnBold);
        btnItalic = view.findViewById(R.id.btnItalic);
        
        // Setup color picker
        setupColorPicker();
        
        // Setup font size slider
        setupFontSizeSlider();
        
        // Setup listeners
        setupListeners();
        
        // Apply default typeface
        editText.setTypeface(defaultTypeface);
    }
    
    /**
     * Setup color picker with preset colors
     */
    private void setupColorPicker() {
        colorButtons = new View[PRESET_COLORS.length];
        
        for (int i = 0; i < PRESET_COLORS.length; i++) {
            View colorButton = colorPickerLayout.getChildAt(i);
            if (colorButton != null) {
                colorButtons[i] = colorButton;
                final int color = PRESET_COLORS[i];
                
                // Set background color
                colorButton.setBackgroundColor(color);
                
                // Add selection indicator for white (default)
                if (color == selectedColor) {
                    colorButton.setSelected(true);
                    colorButton.setScaleX(1.2f);
                    colorButton.setScaleY(1.2f);
                }
                
                // Click listener
                colorButton.setOnClickListener(v -> selectColor(color));
            }
        }
    }
    
    /**
     * Setup font size slider
     */
    private void setupFontSizeSlider() {
        fontSizeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && editText != null) {
                    selectedFontSize = progress;
                    editText.setTextSize(progress);
                }
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // No action needed
            }
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // No action needed
            }
        });
    }
    
    /**
     * Setup all button listeners
     */
    private void setupListeners() {
        // Done button
        btnDone.setOnClickListener(v -> confirmText());
        
        // Clear button
        btnClear.setOnClickListener(v -> editText.setText(""));
        
        // Delete button (only show if editing existing text)
        btnDelete.setOnClickListener(v -> deleteText());
        
        // Alignment buttons
        btnAlignLeft.setOnClickListener(v -> setAlignment(Gravity.LEFT));
        btnAlignCenter.setOnClickListener(v -> setAlignment(Gravity.CENTER));
        btnAlignRight.setOnClickListener(v -> setAlignment(Gravity.RIGHT));
        
        // Style buttons
        btnBold.setOnClickListener(v -> toggleBold());
        btnItalic.setOnClickListener(v -> toggleItalic());
        
        // Dim overlay click to close
        dimOverlay.setOnClickListener(v -> cancelText());
        
        // Text change listener for real-time updates
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Update preview in real-time if editing existing text
                updateTextPreview();
            }
            
            @Override
            public void afterTextChanged(Editable s) {}
        });
    }
    
    /**
     * Select a color and update UI
     */
    private void selectColor(int color) {
        selectedColor = color;
        editText.setTextColor(color);
        
        // Update selection indicators
        for (int i = 0; i < colorButtons.length; i++) {
            if (colorButtons[i] != null) {
                boolean isSelected = PRESET_COLORS[i] == color;
                colorButtons[i].setSelected(isSelected);
                colorButtons[i].setScaleX(isSelected ? 1.2f : 1.0f);
                colorButtons[i].setScaleY(isSelected ? 1.2f : 1.0f);
            }
        }
        
        updateTextPreview();
    }
    
    /**
     * Set text alignment
     */
    private void setAlignment(int alignment) {
        selectedAlignment = alignment;
        editText.setGravity(alignment | Gravity.CENTER_VERTICAL);
        
        // Update button states
        btnAlignLeft.setSelected(alignment == Gravity.LEFT);
        btnAlignCenter.setSelected(alignment == Gravity.CENTER);
        btnAlignRight.setSelected(alignment == Gravity.RIGHT);
        
        updateTextPreview();
    }
    
    /**
     * Toggle bold style
     */
    private void toggleBold() {
        isBold = !isBold;
        btnBold.setSelected(isBold);
        updateTypeface();
        updateTextPreview();
    }
    
    /**
     * Toggle italic style
     */
    private void toggleItalic() {
        isItalic = !isItalic;
        btnItalic.setSelected(isItalic);
        updateTypeface();
        updateTextPreview();
    }
    
    /**
     * Update EditText typeface based on style selections
     */
    private void updateTypeface() {
        int style = Typeface.NORMAL;
        if (isBold && isItalic) {
            style = Typeface.BOLD_ITALIC;
        } else if (isBold) {
            style = Typeface.BOLD;
        } else if (isItalic) {
            style = Typeface.ITALIC;
        }
        
        editText.setTypeface(defaultTypeface, style);
    }
    
    /**
     * Update text preview in real-time (if editing existing text)
     */
    private void updateTextPreview() {
        if (editingTextObject != null && editorCallback != null) {
            // Create preview data
            TextPreviewData previewData = new TextPreviewData(
                editText.getText().toString(),
                selectedColor,
                selectedAlignment,
                selectedFontSize,
                isBold,
                isItalic
            );
            
            // Notify callback for live preview update
            if (editorCallback instanceof TextEditorCallback) {
                ((TextEditorCallback) editorCallback).onTextPreviewUpdate(previewData);
            }
        }
    }
    
    /**
     * Confirm text and close editor
     */
    private void confirmText() {
        String text = editText.getText().toString().trim();
        
        if (text.isEmpty()) {
            cancelText();
            return;
        }
        
        // Create text data
        TextData textData = new TextData(
            text,
            selectedColor,
            selectedAlignment,
            selectedFontSize,
            isBold,
            isItalic,
            editingTextObject
        );
        
        if (editorCallback != null) {
            editorCallback.onContentConfirmed(textData);
        }
        
        hide();
    }
    
    /**
     * Cancel text editing
     */
    private void cancelText() {
        if (editorCallback != null) {
            editorCallback.onContentCancelled();
        }
        hide();
    }
    
    /**
     * Delete text (soft delete)
     */
    private void deleteText() {
        if (editingTextObject != null && editorCallback != null) {
            // Pass the text object to be deleted through the callback data
            if (editorCallback instanceof TextEditorCallback) {
                ((TextEditorCallback) editorCallback).onTextDeleteRequested(editingTextObject);
            } else {
                editorCallback.onDeleteRequested();
            }
        }
        hide();
    }
    
    /**
     * Show editor for creating new text
     */
    public void showForNewText() {
        editingTextObject = null;
        show(); // Show first to inflate views
        resetEditor(); // Then reset after views are created
    }
    
    /**
     * Show editor for editing existing text
     */
    public void showForEditingText(TextObject textObject) {
        editingTextObject = textObject;
        show(); // Show first to inflate views
        loadTextObject(textObject); // Then load data after views are created
    }
    
    /**
     * Reset editor to default state
     */
    private void resetEditor() {
        // Safety check - views must be initialized
        if (editText == null) {
            Log.e(TAG, "resetEditor called but views not initialized");
            return;
        }
        
        editText.setText("");
        selectedColor = Color.WHITE;
        selectedAlignment = Gravity.CENTER;
        selectedFontSize = 28;
        isBold = false;
        isItalic = false;
        
        editText.setTextColor(selectedColor);
        editText.setGravity(selectedAlignment);
        editText.setTextSize(selectedFontSize);
        updateTypeface();
        
        // Update UI
        selectColor(selectedColor);
        setAlignment(selectedAlignment);
        fontSizeSlider.setProgress(selectedFontSize);
        btnBold.setSelected(false);
        btnItalic.setSelected(false);
        
        // Hide delete button for new text
        btnDelete.setVisibility(View.GONE);
    }
    
    /**
     * Load text object data into editor
     */
    private void loadTextObject(TextObject textObject) {
        // Safety check - views must be initialized
        if (editText == null) {
            Log.e(TAG, "loadTextObject called but views not initialized");
            return;
        }
        
        editText.setText(textObject.getText());
        selectedColor = textObject.getTextColor();
        selectedFontSize = (int) textObject.getFontSize();
        
        // Convert Paint.Align to Gravity constant
        android.graphics.Paint.Align paintAlign = textObject.getAlignment();
        if (paintAlign == android.graphics.Paint.Align.CENTER) {
            selectedAlignment = Gravity.CENTER;
        } else if (paintAlign == android.graphics.Paint.Align.RIGHT) {
            selectedAlignment = Gravity.RIGHT;
        } else {
            selectedAlignment = Gravity.LEFT;
        }
        
        // Get style from TextObject
        isBold = textObject.isBold();
        isItalic = textObject.isItalic();
        
        editText.setTextColor(selectedColor);
        editText.setGravity(selectedAlignment);
        editText.setTextSize(selectedFontSize);
        updateTypeface();
        
        // Update UI
        selectColor(selectedColor);
        setAlignment(selectedAlignment);
        fontSizeSlider.setProgress(selectedFontSize);
        btnBold.setSelected(isBold);
        btnItalic.setSelected(isItalic);
        
        // Show delete button for existing text
        btnDelete.setVisibility(View.VISIBLE);
    }
    
    @Override
    protected void onShow() {
        // Focus EditText and show keyboard
        editText.requestFocus();
        editText.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 100);
    }
    
    @Override
    protected void onHide() {
        // Hide keyboard
        InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(editText.getWindowToken(), 0);
        }
        
        // Clear focus
        editText.clearFocus();
    }
    
    /**
     * Data class for text content
     */
    public static class TextData {
        public final String text;
        public final int color;
        public final int alignment;
        public final int fontSize;
        public final boolean isBold;
        public final boolean isItalic;
        public final TextObject editingTextObject; // null if new text
        
        public TextData(String text, int color, int alignment, int fontSize, boolean isBold, 
                       boolean isItalic, TextObject editingTextObject) {
            this.text = text;
            this.color = color;
            this.alignment = alignment;
            this.fontSize = fontSize;
            this.isBold = isBold;
            this.isItalic = isItalic;
            this.editingTextObject = editingTextObject;
        }
    }
    
    /**
     * Data class for real-time preview updates
     */
    public static class TextPreviewData {
        public final String text;
        public final int color;
        public final int alignment;
        public final int fontSize;
        public final boolean isBold;
        public final boolean isItalic;
        
        public TextPreviewData(String text, int color, int alignment, int fontSize, boolean isBold, boolean isItalic) {
            this.text = text;
            this.color = color;
            this.alignment = alignment;
            this.fontSize = fontSize;
            this.isBold = isBold;
            this.isItalic = isItalic;
        }
    }
    
    /**
     * Extended callback interface with text-specific events
     */
    public interface TextEditorCallback extends EditorCallback {
        /**
         * Called when text is being edited (real-time preview)
         */
        void onTextPreviewUpdate(TextPreviewData previewData);
        
        /**
         * Called when text delete is requested with the specific object
         */
        void onTextDeleteRequested(TextObject textObject);
    }
}
