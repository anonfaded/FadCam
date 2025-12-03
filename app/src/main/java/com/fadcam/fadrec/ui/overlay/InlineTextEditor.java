package com.fadcam.fadrec.ui.overlay;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
    private ScrollView scrollContainer;
    private EditText editText;
    private LinearLayout colorPickerLayout;
    private LinearLayout editingArea;
    private SeekBar fontSizeSlider;
    private ImageView btnDone;
    private ImageView btnDelete;

    // Color buttons
    private FrameLayout[] colorButtons;
    private static final int[] PRESET_COLORS = {
            Color.parseColor("#FFFFFF"), // White
            Color.parseColor("#000000"), // Black
            Color.parseColor("#26A69A"), // Teal
            Color.parseColor("#FF3B30"), // Red
            Color.parseColor("#FF9500"), // Orange
            Color.parseColor("#FFCC00"), // Yellow
            Color.parseColor("#34C759"), // Green
            Color.parseColor("#5AC8FA"), // Cyan
            Color.parseColor("#007AFF"), // Blue
            Color.parseColor("#AF52DE"), // Purple
            Color.parseColor("#FF2D55"), // Pink
    };

    // Track if color is dark (needs white border)
    private static final boolean[] IS_DARK_COLOR = {
            false, // White
            true, // Black
            true, // Teal
            true, // Red
            true, // Orange
            false, // Yellow
            false, // Green
            false, // Cyan
            true, // Blue
            true, // Purple
            true, // Pink
    };

    // Alignment buttons
    private ImageView btnAlignLeft;
    private ImageView btnAlignCenter;
    private ImageView btnAlignRight;

    // Style buttons
    private ImageView btnBold;
    private ImageView btnItalic;
    private ImageView btnBackground;

    // Current state
    private int selectedColor = Color.WHITE;
    private int selectedAlignment = Gravity.CENTER;
    private float selectedFontSize = 24f; // Default font size in sp
    private boolean isBold = false;
    private boolean isItalic = false;
    private boolean hasBackground = false;

    // Keyboard and layout state
    private boolean isKeyboardVisible = false;
    private int screenHeight = 0;
    private int keyboardHeight = 0;

    // Text object being edited (null for new text)
    private TextObject editingTextObject;
    private Typeface defaultTypeface;

    // Auto-save timer
    private Runnable autoSaveRunnable;
    private static final long AUTO_SAVE_DELAY_MS = 500; // 500ms debounce

    /**
     * Constructor
     */
    public InlineTextEditor(Context context, WindowManager windowManager) {
        super(context, windowManager);
        // Load default typeface (Ubuntu Regular)
        defaultTypeface = ResourcesCompat.getFont(context, R.font.ubuntu_regular);
    }

    @Override
    protected void initializeLayoutParams() {
        super.initializeLayoutParams();
        // Adjust window when keyboard appears to keep content visible
        layoutParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE;
    }

    @Override
    protected int getLayoutResourceId() {
        return R.layout.overlay_inline_text_editor;
    }

    @Override
    protected void onViewCreated(View view) {
        // Find views
        dimOverlay = view.findViewById(R.id.dimOverlay);
        scrollContainer = view.findViewById(R.id.scrollContainer);
        editText = view.findViewById(R.id.editText);
        colorPickerLayout = view.findViewById(R.id.colorPickerLayout);
        editingArea = view.findViewById(R.id.editingArea);
        fontSizeSlider = view.findViewById(R.id.fontSizeSlider);
        btnDone = view.findViewById(R.id.btnDone);
        btnDelete = view.findViewById(R.id.btnDelete);

        // Alignment buttons
        btnAlignLeft = view.findViewById(R.id.btnAlignLeft);
        btnAlignCenter = view.findViewById(R.id.btnAlignCenter);
        btnAlignRight = view.findViewById(R.id.btnAlignRight);

        // Style buttons
        btnBold = view.findViewById(R.id.btnBold);
        btnItalic = view.findViewById(R.id.btnItalic);
        btnBackground = view.findViewById(R.id.btnBackground);

        // Setup color picker
        setupColorPicker();

        // Setup font size slider
        setupFontSizeSlider();

        // Setup listeners
        setupListeners();

        // Setup keyboard detection
        setupKeyboardListener();

        // Apply default typeface
        editText.setTypeface(defaultTypeface);
    }

    /**
     * Setup color picker with preset colors
     */
    private void setupColorPicker() {
        colorButtons = new FrameLayout[PRESET_COLORS.length];

        for (int i = 0; i < PRESET_COLORS.length; i++) {
            FrameLayout colorButton = (FrameLayout) colorPickerLayout.getChildAt(i);
            if (colorButton != null) {
                colorButtons[i] = colorButton;
                final int color = PRESET_COLORS[i];
                final boolean isDark = IS_DARK_COLOR[i];
                final int colorIndex = i; // Make final for lambda

                // Get the inner View (color circle)
                View colorCircle = colorButton.getChildAt(0);
                if (colorCircle != null) {
                    // Create circular drawable with color
                    GradientDrawable drawable = new GradientDrawable();
                    drawable.setShape(GradientDrawable.OVAL);
                    drawable.setColor(color);

                    // Add white border for dark colors
                    if (isDark) {
                        drawable.setStroke(2, Color.WHITE);
                    }

                    colorCircle.setBackground(drawable);

                    // Add selection indicator for white (default)
                    if (color == selectedColor) {
                        colorButton.setSelected(true);
                        colorButton.setScaleX(1.15f);
                        colorButton.setScaleY(1.15f);
                    }
                }

                // Click listener
                colorButton.setOnClickListener(v -> selectColor(color, colorIndex));
            }
        }
    }

    /**
     * Setup font size slider (SeekBar styled with green)
     */
    private void setupFontSizeSlider() {
        fontSizeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && editText != null) {
                    // Map progress 0-32 to fontSize 16-48
                    selectedFontSize = 16 + progress;
                    editText.setTextSize(selectedFontSize);
                    triggerPreviewUpdate();
                    triggerAutoSave();
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
     * Setup keyboard detection to adjust layout dynamically based on screen size
     */
    private void setupKeyboardListener() {
        // Get screen height once
        screenHeight = overlayView.getRootView().getHeight();

        overlayView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            // Calculate current visible height
            int visibleHeight = overlayView.getHeight();
            int heightDiff = screenHeight - visibleHeight;

            // Keyboard is visible if height difference is significant (> 15% of screen)
            boolean keyboardNowVisible = heightDiff > (screenHeight * 0.15f);

            if (keyboardNowVisible != isKeyboardVisible) {
                isKeyboardVisible = keyboardNowVisible;
                keyboardHeight = isKeyboardVisible ? heightDiff : 0;
                adjustLayoutForKeyboard();
            }
        });
    }

    /**
     * Adjust layout based on keyboard visibility with dynamic calculations
     */
    private void adjustLayoutForKeyboard() {
        if (isKeyboardVisible) {
            // Keyboard is visible - move editing area to top with minimal spacing
            editingArea.setGravity(android.view.Gravity.TOP);

            // Calculate dynamic padding (2% of screen height)
            int topPadding = (int) (screenHeight * 0.02f);

            editingArea.setPadding(
                    editingArea.getPaddingLeft(),
                    topPadding,
                    editingArea.getPaddingRight(),
                    editingArea.getPaddingBottom());

        } else {
            // Keyboard is hidden - center editing area
            editingArea.setGravity(android.view.Gravity.CENTER);
            editingArea.setPadding(
                    editingArea.getPaddingLeft(),
                    0,
                    editingArea.getPaddingRight(),
                    editingArea.getPaddingBottom());
        }
    }

    /**
     * Setup all button listeners and text change handling
     */
    private void setupListeners() {
        // Done button
        btnDone.setOnClickListener(v -> confirmText());

        // Delete button (only show if editing existing text)
        btnDelete.setOnClickListener(v -> deleteText());

        // Alignment buttons
        btnAlignLeft.setOnClickListener(v -> setAlignment(Gravity.LEFT));
        btnAlignCenter.setOnClickListener(v -> setAlignment(Gravity.CENTER));
        btnAlignRight.setOnClickListener(v -> setAlignment(Gravity.RIGHT));

        // Style buttons
        btnBold.setOnClickListener(v -> toggleBold());
        btnItalic.setOnClickListener(v -> toggleItalic());
        btnBackground.setOnClickListener(v -> toggleBackground());

        // Dim overlay click to unfocus (not close)
        dimOverlay.setOnClickListener(v -> {
            // Just unfocus the EditText and hide keyboard
            if (editText.hasFocus()) {
                editText.clearFocus();
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) context
                        .getSystemService(
                                android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(editText.getWindowToken(), 0);
                }
            }
            // Don't close the editor - just unfocus
        });

        // Text change listener for auto-save and live preview
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Trigger live preview update
                triggerPreviewUpdate();

                // Trigger auto-save with debounce
                triggerAutoSave();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    /**
     * Trigger auto-save with debounce
     */
    private void triggerAutoSave() {
        // Remove previous auto-save callback
        if (autoSaveRunnable != null && editText != null) {
            editText.removeCallbacks(autoSaveRunnable);
        }

        // Schedule new auto-save
        autoSaveRunnable = () -> {
            String text = editText.getText().toString().trim();
            if (!text.isEmpty()) {
                autoSaveText();
            }
        };

        if (editText != null) {
            editText.postDelayed(autoSaveRunnable, AUTO_SAVE_DELAY_MS);
        }
    }

    /**
     * Trigger live preview update (no debounce for immediate feedback)
     */
    private void triggerPreviewUpdate() {
        String text = editText.getText().toString();

        if (text.isEmpty()) {
            return;
        }

        // Create preview data
        TextPreviewData previewData = new TextPreviewData(
                text,
                selectedColor,
                selectedAlignment,
                selectedFontSize,
                isBold,
                isItalic,
                hasBackground,
                hasBackground ? getContrastColor(selectedColor) : 0);

        // Notify callback for live preview
        if (editorCallback instanceof TextEditorCallback) {
            ((TextEditorCallback) editorCallback).onTextPreviewUpdate(previewData);
        }
    }

    /**
     * Auto-save text in background (creates/updates TextObject)
     */
    private void autoSaveText() {
        String text = editText.getText().toString().trim();

        if (text.isEmpty()) {
            return;
        }

        // Create text data
        int backgroundColor = hasBackground ? getContrastColor(selectedColor) : 0;
        TextData textData = new TextData(
                text,
                selectedColor,
                selectedAlignment,
                selectedFontSize,
                isBold,
                isItalic,
                hasBackground,
                backgroundColor,
                editingTextObject);

        // Notify callback to auto-save (doesn't close editor)
        if (editorCallback instanceof TextEditorCallback) {
            ((TextEditorCallback) editorCallback).onTextAutoSaved(textData);
        }
    }

    /**
     * Select a color and update UI
     */
    private void selectColor(int color, int colorIndex) {
        selectedColor = color;

        // Check if user has selected text
        int selectionStart = editText.getSelectionStart();
        int selectionEnd = editText.getSelectionEnd();

        if (selectionStart >= 0 && selectionEnd > selectionStart) {
            // User has text selected - apply color to selection only
            Editable editable = editText.getText();
            editable.setSpan(
                    new ForegroundColorSpan(color),
                    selectionStart,
                    selectionEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            // No selection - set default color for all text
            editText.setTextColor(color);
        }

        // Update selection indicators
        for (int i = 0; i < colorButtons.length; i++) {
            if (colorButtons[i] != null) {
                boolean isSelected = i == colorIndex;
                colorButtons[i].setSelected(isSelected);
                colorButtons[i].setScaleX(isSelected ? 1.15f : 1.0f);
                colorButtons[i].setScaleY(isSelected ? 1.15f : 1.0f);
            }
        }

        triggerPreviewUpdate();
        triggerAutoSave();
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

        triggerPreviewUpdate();
        triggerAutoSave();
    }

    /**
     * Toggle bold style
     */
    private void toggleBold() {
        isBold = !isBold;
        btnBold.setSelected(isBold);
        updateTypeface();
        triggerPreviewUpdate();
        triggerAutoSave();
    }

    /**
     * Toggle italic style
     */
    private void toggleItalic() {
        isItalic = !isItalic;
        btnItalic.setSelected(isItalic);
        updateTypeface();
        triggerPreviewUpdate();
        triggerAutoSave();
    }

    /**
     * Toggle background color
     */
    private void toggleBackground() {
        hasBackground = !hasBackground;
        btnBackground.setSelected(hasBackground);
        triggerPreviewUpdate();
        triggerAutoSave();
    }

    /**
     * Calculate contrast color for background
     */
    private int getContrastColor(int textColor) {
        // Calculate luminance
        int red = Color.red(textColor);
        int green = Color.green(textColor);
        int blue = Color.blue(textColor);

        // Use perceived luminance formula
        double luminance = (0.299 * red + 0.587 * green + 0.114 * blue) / 255;

        // If text is light, use dark background; if text is dark, use light background
        if (luminance > 0.5) {
            return Color.BLACK;
        } else {
            return Color.WHITE;
        }
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
     * Confirm text and close editor
     */
    private void confirmText() {
        String text = editText.getText().toString().trim();

        if (text.isEmpty()) {
            // If empty and editing existing text, soft delete it
            if (editingTextObject != null) {
                deleteText();
            } else {
                cancelText();
            }
            return;
        }

        // Cancel pending auto-save
        if (autoSaveRunnable != null && editText != null) {
            editText.removeCallbacks(autoSaveRunnable);
        }

        // Create text data
        int backgroundColor = hasBackground ? getContrastColor(selectedColor) : 0;
        TextData textData = new TextData(
                text,
                selectedColor,
                selectedAlignment,
                selectedFontSize,
                isBold,
                isItalic,
                hasBackground,
                backgroundColor,
                editingTextObject);

        if (editorCallback != null) {
            editorCallback.onContentConfirmed(textData);
        }

        hide();
    }

    /**
     * Cancel text editing
     */
    private void cancelText() {
        // Cancel pending auto-save
        if (autoSaveRunnable != null && editText != null) {
            editText.removeCallbacks(autoSaveRunnable);
        }

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
        selectedFontSize = 24f;
        isBold = false;
        isItalic = false;

        editText.setTextColor(selectedColor);
        editText.setGravity(selectedAlignment);
        editText.setTextSize(selectedFontSize);
        updateTypeface();

        // Update UI
        selectColor(selectedColor, 0); // White is index 0
        setAlignment(selectedAlignment);
        fontSizeSlider.setProgress((int) (selectedFontSize - 16)); // Map 16-48 to 0-32
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
        selectedFontSize = textObject.getFontSize();

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

        // Find color index
        int colorIndex = 0;
        for (int i = 0; i < PRESET_COLORS.length; i++) {
            if (PRESET_COLORS[i] == selectedColor) {
                colorIndex = i;
                break;
            }
        }

        // Update UI
        selectColor(selectedColor, colorIndex);
        setAlignment(selectedAlignment);
        fontSizeSlider.setProgress((int) (selectedFontSize - 16)); // Map 16-48 to 0-32
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
        public final float fontSize;
        public final boolean isBold;
        public final boolean isItalic;
        public final boolean hasBackground;
        public final int backgroundColor;
        public final TextObject editingTextObject; // null if new text

        public TextData(String text, int color, int alignment, float fontSize, boolean isBold,
                boolean isItalic, boolean hasBackground, int backgroundColor, TextObject editingTextObject) {
            this.text = text;
            this.color = color;
            this.alignment = alignment;
            this.fontSize = fontSize;
            this.isBold = isBold;
            this.isItalic = isItalic;
            this.hasBackground = hasBackground;
            this.backgroundColor = backgroundColor;
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
        public final float fontSize;
        public final boolean isBold;
        public final boolean isItalic;
        public final boolean hasBackground;
        public final int backgroundColor;

        public TextPreviewData(String text, int color, int alignment, float fontSize, boolean isBold,
                boolean isItalic, boolean hasBackground, int backgroundColor) {
            this.text = text;
            this.color = color;
            this.alignment = alignment;
            this.fontSize = fontSize;
            this.isBold = isBold;
            this.isItalic = isItalic;
            this.hasBackground = hasBackground;
            this.backgroundColor = backgroundColor;
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

        /**
         * Called when text is auto-saved (doesn't close editor)
         */
        void onTextAutoSaved(TextData textData);
    }
}
