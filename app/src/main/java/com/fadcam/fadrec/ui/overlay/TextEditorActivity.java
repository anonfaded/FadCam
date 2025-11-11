package com.fadcam.fadrec.ui.overlay;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.Spannable;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import androidx.core.content.res.ResourcesCompat;
import com.fadcam.R;

/**
 * Text editor activity - extends base transparent editor.
 * Handles text editing with proper keyboard management.
 */
public class TextEditorActivity extends BaseTransparentEditorActivity {
    
    // Intent extras
    public static final String EXTRA_EDIT_MODE = "edit_mode";
    public static final String EXTRA_INITIAL_TEXT = "initial_text";
    public static final String EXTRA_TEXT_COLOR = "text_color";
    public static final String EXTRA_TEXT_SIZE = "text_size";
    public static final String EXTRA_TEXT_ALIGNMENT = "text_alignment";
    public static final String EXTRA_TEXT_BOLD = "text_bold";
    public static final String EXTRA_TEXT_ITALIC = "text_italic";
    
    // Result extras
    public static final String RESULT_TEXT = "text";
    public static final String RESULT_COLOR = "color";
    public static final String RESULT_SIZE = "size";
    public static final String RESULT_ALIGNMENT = "alignment";
    public static final String RESULT_BOLD = "bold";
    public static final String RESULT_ITALIC = "italic";
    public static final String RESULT_HAS_BACKGROUND = "has_background";
    public static final String RESULT_BACKGROUND_COLOR = "background_color";
    public static final String RESULT_MAX_WIDTH = "max_width";
    
    // UI Components
    private ScrollView scrollContainer;
    private EditText editText;
    private LinearLayout colorPickerLayout;
    private LinearLayout editingArea;
    private SeekBar fontSizeSlider;
    private ImageView btnDone, btnDelete;
    private ImageView btnAlignLeft, btnAlignCenter, btnAlignRight;
    private ImageView btnBold, btnItalic, btnBackground;
    private FrameLayout[] colorButtons;
    
    // State
    private int selectedColor = Color.WHITE;
    private int selectedAlignment = Gravity.CENTER;
    private float selectedFontSize = 24f;
    private boolean isBold = false;
    private boolean isItalic = false;
    private boolean hasBackground = false;
    private Typeface defaultTypeface;
    
    // Preset colors
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
    
    private static final boolean[] IS_DARK_COLOR = {
        false, true, true, true, true, false, false, false, true, true, true
    };
    
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        Log.d("TextEditorActivity", "dispatchTouchEvent: action=" + ev.getAction() + 
              " x=" + ev.getX() + " y=" + ev.getY());
        return super.dispatchTouchEvent(ev);
    }
    
    @Override
    protected int getLayoutResourceId() {
        Log.d("TextEditorActivity", "getLayoutResourceId() called");
        return R.layout.overlay_inline_text_editor;
    }
    
    @Override
    protected void onEditorViewCreated(View rootView) {
        Log.d("TextEditorActivity", "onEditorViewCreated() called");
        
        // Load default typeface
        defaultTypeface = ResourcesCompat.getFont(this, R.font.ubuntu_regular);
        
        Log.d("TextEditorActivity", "rootView clickable: " + rootView.isClickable());
        Log.d("TextEditorActivity", "rootView focusable: " + rootView.isFocusable());
        
        // Find views
        findViews(rootView);
        
        // Setup components
        setupColorPicker();
        setupFontSizeSlider();
        setupListeners();
        setupKeyboardAdjustment();
        
        // Load initial data if editing
        loadInitialData();
        
        // Show keyboard
        editText.requestFocus();
        editText.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT);
                Log.d("TextEditorActivity", "Keyboard show requested");
            }
        }, 200);
    }
    
    private void findViews(View rootView) {
        Log.d("TextEditorActivity", "findViews() called");
        scrollContainer = rootView.findViewById(R.id.scrollContainer);
        editText = rootView.findViewById(R.id.editText);
        colorPickerLayout = rootView.findViewById(R.id.colorPickerLayout);
        editingArea = rootView.findViewById(R.id.editingArea);
        fontSizeSlider = rootView.findViewById(R.id.fontSizeSlider);
        btnDone = rootView.findViewById(R.id.btnDone);
        btnDelete = rootView.findViewById(R.id.btnDelete);
        btnAlignLeft = rootView.findViewById(R.id.btnAlignLeft);
        btnAlignCenter = rootView.findViewById(R.id.btnAlignCenter);
        btnAlignRight = rootView.findViewById(R.id.btnAlignRight);
        btnBold = rootView.findViewById(R.id.btnBold);
        btnItalic = rootView.findViewById(R.id.btnItalic);
        btnBackground = rootView.findViewById(R.id.btnBackground);
        
        editText.setTypeface(defaultTypeface);
    }
    
    private void setupColorPicker() {
        colorButtons = new FrameLayout[PRESET_COLORS.length];
        for (int i = 0; i < PRESET_COLORS.length; i++) {
            FrameLayout colorButton = (FrameLayout) colorPickerLayout.getChildAt(i);
            if (colorButton != null) {
                colorButtons[i] = colorButton;
                final int color = PRESET_COLORS[i];
                final boolean isDark = IS_DARK_COLOR[i];
                final int colorIndex = i;
                
                View colorView = colorButton.getChildAt(0);
                if (colorView != null) {
                    GradientDrawable drawable = new GradientDrawable();
                    drawable.setShape(GradientDrawable.OVAL);
                    drawable.setColor(color);
                    if (isDark) {
                        drawable.setStroke(2, Color.WHITE);
                    }
                    colorView.setBackground(drawable);
                }
                
                colorButton.setOnClickListener(v -> selectColor(color, colorIndex));
            }
        }
    }
    
    private void setupFontSizeSlider() {
        fontSizeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    selectedFontSize = 16 + progress;
                    editText.setTextSize(selectedFontSize);
                }
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }
    
    private void setupListeners() {
        Log.d("TextEditorActivity", "setupListeners() called");
        
        btnDone.setOnClickListener(v -> {
            Log.d("TextEditorActivity", "Done button clicked");
            saveAndFinish();
        });
        
        btnDelete.setOnClickListener(v -> {
            Log.d("TextEditorActivity", "Delete button clicked");
            deleteAndFinish();
        });
        
        btnAlignLeft.setOnClickListener(v -> {
            Log.d("TextEditorActivity", "Align left clicked");
            setAlignment(Gravity.LEFT);
        });
        
        btnAlignCenter.setOnClickListener(v -> {
            Log.d("TextEditorActivity", "Align center clicked");
            setAlignment(Gravity.CENTER);
        });
        
        btnAlignRight.setOnClickListener(v -> {
            Log.d("TextEditorActivity", "Align right clicked");
            setAlignment(Gravity.RIGHT);
        });
        
        btnBold.setOnClickListener(v -> {
            Log.d("TextEditorActivity", "Bold button clicked");
            toggleBold();
        });
        
        btnItalic.setOnClickListener(v -> {
            Log.d("TextEditorActivity", "Italic button clicked");
            toggleItalic();
        });
        
        btnBackground.setOnClickListener(v -> {
            Log.d("TextEditorActivity", "Background button clicked");
            toggleBackground();
        });
        
        editText.setOnTouchListener((v, event) -> {
            Log.d("TextEditorActivity", "EditText touched: " + event.getAction());
            return false; // Don't consume, let it process normally
        });
    }
    
    private void setupKeyboardAdjustment() {
        final View rootView = findViewById(android.R.id.content);
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                int heightDiff = rootView.getRootView().getHeight() - rootView.getHeight();
                
                if (heightDiff > 200) {
                    // Keyboard visible - move to top
                    editingArea.setGravity(Gravity.TOP);
                    editingArea.setPadding(
                        editingArea.getPaddingLeft(),
                        16,
                        editingArea.getPaddingRight(),
                        editingArea.getPaddingBottom()
                    );
                } else {
                    // Keyboard hidden - center
                    editingArea.setGravity(Gravity.CENTER);
                    editingArea.setPadding(
                        editingArea.getPaddingLeft(),
                        0,
                        editingArea.getPaddingRight(),
                        editingArea.getPaddingBottom()
                    );
                }
            }
        });
    }
    
    private void loadInitialData() {
        String initialText = getIntent().getStringExtra(EXTRA_INITIAL_TEXT);
        if (initialText != null) {
            editText.setText(initialText);
        }
        
        selectedColor = getIntent().getIntExtra(EXTRA_TEXT_COLOR, Color.WHITE);
        selectedFontSize = getIntent().getFloatExtra(EXTRA_TEXT_SIZE, 24f);
        selectedAlignment = getIntent().getIntExtra(EXTRA_TEXT_ALIGNMENT, Gravity.CENTER);
        isBold = getIntent().getBooleanExtra(EXTRA_TEXT_BOLD, false);
        isItalic = getIntent().getBooleanExtra(EXTRA_TEXT_ITALIC, false);
        
        editText.setTextColor(selectedColor);
        editText.setTextSize(selectedFontSize);
        editText.setGravity(selectedAlignment | Gravity.CENTER_VERTICAL);
        updateTypeface();
        
        fontSizeSlider.setProgress((int)(selectedFontSize - 16));
        
        // Show delete button if editing
        boolean isEditing = getIntent().getBooleanExtra(EXTRA_EDIT_MODE, false);
        btnDelete.setVisibility(isEditing ? View.VISIBLE : View.GONE);
    }
    
    private void selectColor(int color, int colorIndex) {
        selectedColor = color;
        
        int selectionStart = editText.getSelectionStart();
        int selectionEnd = editText.getSelectionEnd();
        
        if (selectionStart >= 0 && selectionEnd > selectionStart) {
            Editable editable = editText.getText();
            editable.setSpan(
                new ForegroundColorSpan(color),
                selectionStart,
                selectionEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        } else {
            editText.setTextColor(color);
        }
        
        for (int i = 0; i < colorButtons.length; i++) {
            if (colorButtons[i] != null) {
                boolean isSelected = i == colorIndex;
                colorButtons[i].setSelected(isSelected);
                colorButtons[i].setScaleX(isSelected ? 1.15f : 1.0f);
                colorButtons[i].setScaleY(isSelected ? 1.15f : 1.0f);
            }
        }
    }
    
    private void setAlignment(int alignment) {
        selectedAlignment = alignment;
        editText.setGravity(alignment | Gravity.CENTER_VERTICAL);
        
        btnAlignLeft.setSelected(alignment == Gravity.LEFT);
        btnAlignCenter.setSelected(alignment == Gravity.CENTER);
        btnAlignRight.setSelected(alignment == Gravity.RIGHT);
    }
    
    private void toggleBold() {
        isBold = !isBold;
        btnBold.setSelected(isBold);
        updateTypeface();
    }
    
    private void toggleItalic() {
        isItalic = !isItalic;
        btnItalic.setSelected(isItalic);
        updateTypeface();
    }
    
    private void toggleBackground() {
        hasBackground = !hasBackground;
        btnBackground.setSelected(hasBackground);
    }
    
    private void updateTypeface() {
        int style = Typeface.NORMAL;
        if (isBold && isItalic) style = Typeface.BOLD_ITALIC;
        else if (isBold) style = Typeface.BOLD;
        else if (isItalic) style = Typeface.ITALIC;
        editText.setTypeface(defaultTypeface, style);
    }
    
    private int getContrastColor(int textColor) {
        int red = Color.red(textColor);
        int green = Color.green(textColor);
        int blue = Color.blue(textColor);
        double luminance = (0.299 * red + 0.587 * green + 0.114 * blue) / 255;
        return luminance > 0.5 ? Color.BLACK : Color.WHITE;
    }
    
    private void saveAndFinish() {
        String originalText = editText.getText().toString().trim();
        if (originalText.isEmpty()) {
            finishWithCancel();
            return;
        }
        
        // Measure EditText width to preserve line wrapping behavior
        // Let StaticLayout handle ALL text wrapping based on maxWidth and alignment
        int maxWidth = editText.getWidth() - editText.getPaddingLeft() - editText.getPaddingRight();
        Log.d("TextEditorActivity", "Saving text with maxWidth=" + maxWidth + 
              " (editText.width=" + editText.getWidth() + 
              " paddingLeft=" + editText.getPaddingLeft() + 
              " paddingRight=" + editText.getPaddingRight() + ")");
        
        Bundle result = new Bundle();
        result.putString(RESULT_TEXT, originalText);
        result.putInt(RESULT_COLOR, selectedColor);
        result.putFloat(RESULT_SIZE, selectedFontSize);
        result.putInt(RESULT_ALIGNMENT, selectedAlignment);
        result.putBoolean(RESULT_BOLD, isBold);
        result.putBoolean(RESULT_ITALIC, isItalic);
        result.putBoolean(RESULT_HAS_BACKGROUND, hasBackground);
        result.putInt(RESULT_BACKGROUND_COLOR, hasBackground ? getContrastColor(selectedColor) : 0);
        result.putInt(RESULT_MAX_WIDTH, maxWidth);
        
        finishWithSave(result);
    }
    
    /**
     * Insert line breaks (\n) where the EditText's layout wrapped the text.
     * This preserves the visual line breaking from the editor.
     */
    private String insertLineBreaksFromLayout(String text) {
        android.text.Layout layout = editText.getLayout();
        if (layout == null) {
            return text;
        }
        
        StringBuilder result = new StringBuilder();
        int lineCount = layout.getLineCount();
        int textLength = text.length();
        
        for (int i = 0; i < lineCount; i++) {
            int lineStart = layout.getLineStart(i);
            int lineEnd = layout.getLineEnd(i);
            
            // Bounds check to prevent StringIndexOutOfBoundsException
            if (lineStart >= textLength) break;
            if (lineEnd > textLength) lineEnd = textLength;
            
            // Get text for this line
            String lineText = text.substring(lineStart, lineEnd);
            
            // Trim trailing whitespace/newlines from this line
            lineText = lineText.replaceAll("[\\s\n]+$", "");
            
            result.append(lineText);
            
            // Add newline if not the last line and we have content
            if (i < lineCount - 1 && lineText.length() > 0) {
                result.append("\n");
            }
        }
        
        return result.toString();
    }
    
    private void deleteAndFinish() {
        Bundle result = new Bundle();
        finishWithDelete(result);
    }
}
