package com.fadcam.fadrec.ui.annotation;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.fadcam.R;

/**
 * Cute and user-friendly dialog for adding/editing text objects.
 * Features: font size, bold, italic, alignment, color picker.
 */
public class TextEditorDialog extends Dialog {
    
    public interface OnTextConfirmedListener {
        void onTextConfirmed(String text, float fontSize, int color, boolean bold, boolean italic, Paint.Align alignment);
    }
    
    private OnTextConfirmedListener listener;
    
    private EditText editTextContent;
    private SeekBar seekBarFontSize;
    private TextView txtFontSizeValue;
    private TextView btnBoldToggle;
    private TextView btnItalicToggle;
    private TextView btnAlignLeft;
    private View[] colorViews;
    
    private float fontSize = 24f;
    private int textColor = 0xFFFFFFFF; // White default
    private boolean isBold = false;
    private boolean isItalic = false;
    private Paint.Align alignment = Paint.Align.LEFT;
    private String initialText = ""; // Store text before onCreate()
    
    public TextEditorDialog(@NonNull Context context) {
        super(context);
    }
    
    public void setOnTextConfirmedListener(OnTextConfirmedListener listener) {
        this.listener = listener;
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_text_editor);
        
        // Configure as system alert window for Service context
        if (getWindow() != null) {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            
            // Set window type for showing from Service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            } else {
                getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            }
            
            // Add flags for proper behavior
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
            
            // Set dialog width to 90% of screen width for better visibility
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.width = (int) (getContext().getResources().getDisplayMetrics().widthPixels * 0.9);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            getWindow().setAttributes(params);
        }
        
        findViews();
        setupListeners();
    }
    
    private void findViews() {
        editTextContent = findViewById(R.id.editTextContent);
        seekBarFontSize = findViewById(R.id.seekBarFontSize);
        txtFontSizeValue = findViewById(R.id.txtFontSizeValue);
        btnBoldToggle = findViewById(R.id.btnBoldToggle);
        btnItalicToggle = findViewById(R.id.btnItalicToggle);
        btnAlignLeft = findViewById(R.id.btnAlignLeft);
        
        colorViews = new View[]{
            findViewById(R.id.colorWhite),
            findViewById(R.id.colorRed),
            findViewById(R.id.colorYellow),
            findViewById(R.id.colorGreen),
            findViewById(R.id.colorBlue),
            findViewById(R.id.colorBlack)
        };
        
        // Apply initial text if it was set before onCreate()
        if (!initialText.isEmpty()) {
            editTextContent.setText(initialText);
        }
        
        // Apply initial values to UI
        seekBarFontSize.setProgress((int)(fontSize - 12));
        txtFontSizeValue.setText(((int)fontSize) + "sp");
        updateStyleButton(btnBoldToggle, isBold);
        updateStyleButton(btnItalicToggle, isItalic);
        
        // Highlight selected color
        int[] colorValues = {0xFFFFFFFF, 0xFFF44336, 0xFFFFEB3B, 0xFF4CAF50, 0xFF2196F3, 0xFF000000};
        for (int i = 0; i < colorViews.length; i++) {
            if (colorValues[i] == textColor) {
                colorViews[i].setAlpha(1.0f);
            } else {
                colorViews[i].setAlpha(0.5f);
            }
        }
        
        updatePreview();
    }
    
    private void setupListeners() {
        // Font size slider
        seekBarFontSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                fontSize = 12 + progress; // Min 12sp, max 92sp
                txtFontSizeValue.setText(((int)fontSize) + "sp");
                updatePreview();
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        // Bold toggle
        btnBoldToggle.setOnClickListener(v -> {
            isBold = !isBold;
            updateStyleButton(btnBoldToggle, isBold);
            updatePreview();
        });
        
        // Italic toggle
        btnItalicToggle.setOnClickListener(v -> {
            isItalic = !isItalic;
            updateStyleButton(btnItalicToggle, isItalic);
            updatePreview();
        });
        
        // Alignment (simplified - just left for now)
        btnAlignLeft.setOnClickListener(v -> {
            alignment = Paint.Align.LEFT;
        });
        
        // Color pickers
        setupColorPicker(colorViews[0], 0xFFFFFFFF);
        setupColorPicker(colorViews[1], 0xFFF44336);
        setupColorPicker(colorViews[2], 0xFFFFEB3B);
        setupColorPicker(colorViews[3], 0xFF4CAF50);
        setupColorPicker(colorViews[4], 0xFF2196F3);
        setupColorPicker(colorViews[5], 0xFF000000);
        
        // Confirm button
        findViewById(R.id.btnConfirm).setOnClickListener(v -> {
            String text = editTextContent.getText().toString().trim();
            if (!text.isEmpty() && listener != null) {
                listener.onTextConfirmed(text, fontSize, textColor, isBold, isItalic, alignment);
            }
            dismiss();
        });
        
        // Cancel button
        findViewById(R.id.btnCancel).setOnClickListener(v -> dismiss());
        
        // Text preview
        editTextContent.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updatePreview();
            }
            
            @Override
            public void afterTextChanged(Editable s) {}
        });
    }
    
    private void setupColorPicker(View colorView, int color) {
        colorView.setOnClickListener(v -> {
            textColor = color;
            // Highlight selected color
            for (View view : colorViews) {
                view.setAlpha(view == colorView ? 1.0f : 0.5f);
            }
            updatePreview();
        });
    }
    
    private void updateStyleButton(TextView button, boolean active) {
        if (active) {
            button.setBackgroundResource(R.drawable.annotation_layer_selected);
            button.setTextColor(getContext().getColor(R.color.colorPrimary));
        } else {
            button.setBackgroundResource(R.drawable.settings_home_row_bg);
            button.setTextColor(getContext().getColor(android.R.color.white));
        }
    }
    
    private void updatePreview() {
        // Update EditText preview
        editTextContent.setTextSize(fontSize * 0.7f); // Scaled preview
        editTextContent.setTextColor(textColor);
        
        // Use Ubuntu font family (regular only, rely on textStyle for bold/italic)
        android.graphics.Typeface ubuntuFont = androidx.core.content.res.ResourcesCompat.getFont(
            getContext(), 
            com.fadcam.R.font.ubuntu_regular
        );
        
        // Apply font and let Android handle bold/italic through setTypeface style param
        int style = android.graphics.Typeface.NORMAL;
        if (isBold && isItalic) style = android.graphics.Typeface.BOLD_ITALIC;
        else if (isBold) style = android.graphics.Typeface.BOLD;
        else if (isItalic) style = android.graphics.Typeface.ITALIC;
        
        editTextContent.setTypeface(ubuntuFont, style);
    }
    
    // Setter methods for editing existing text
    public void setText(String text) {
        this.initialText = text; // Store for onCreate()
        if (editTextContent != null) {
            editTextContent.setText(text);
        }
    }
    
    public void setFontSize(float size) {
        this.fontSize = size;
        if (seekBarFontSize != null) {
            seekBarFontSize.setProgress((int)(size - 12));
        }
        if (txtFontSizeValue != null) {
            txtFontSizeValue.setText(((int)size) + "sp");
        }
        if (editTextContent != null) {
            updatePreview();
        }
    }
    
    public void setColor(int color) {
        this.textColor = color;
        // Highlight matching color if exists
        if (colorViews != null) {
            for (View view : colorViews) {
                view.setAlpha(0.5f);
            }
        }
        if (editTextContent != null) {
            updatePreview();
        }
    }
    
    public void setBold(boolean bold) {
        this.isBold = bold;
        if (btnBoldToggle != null) {
            updateStyleButton(btnBoldToggle, bold);
        }
        if (editTextContent != null) {
            updatePreview();
        }
    }
    
    public void setItalic(boolean italic) {
        this.isItalic = italic;
        if (btnItalicToggle != null) {
            updateStyleButton(btnItalicToggle, italic);
        }
        if (editTextContent != null) {
            updatePreview();
        }
    }
    
    public void setAlignment(Paint.Align align) {
        this.alignment = align;
    }
}