package com.guardanis.applock.password;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatEditText;

import com.fadcam.R;

public class PasswordInputView extends LinearLayout implements TextWatcher {

    private EditText editText;
    private boolean passwordVisibility = false;
    private String lastText = "";

    public PasswordInputView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setupEditText();
    }

    protected void setupEditText() {
        removeAllViews();
        
        // Create horizontal container for edit text and button side by side
        LinearLayout container = new LinearLayout(getContext());
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        container.setGravity(Gravity.CENTER_VERTICAL);
        
        // Use weight to make EditText take most space
        this.editText = inflatePasswordEditText();
        LinearLayout.LayoutParams editTextParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f);  // Weight of 1 makes it expand
        this.editText.setLayoutParams(editTextParams);
        this.editText.addTextChangedListener(this);
        
        // Configure for password input
        this.editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        this.editText.setTransformationMethod(PasswordTransformationMethod.getInstance());
        this.editText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        
        container.addView(editText);
        
        // Add a smaller, more stylish toggle button
        Button toggleButton = new Button(getContext());
        toggleButton.setText(R.string.applock__password_show);
        toggleButton.setTextSize(10);
        toggleButton.setTextColor(getResources().getColor(android.R.color.darker_gray));
        toggleButton.setBackgroundColor(getResources().getColor(android.R.color.transparent));
        
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 
                LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonParams.leftMargin = -12; // Negative margin to overlay slightly
        
        toggleButton.setLayoutParams(buttonParams);
        toggleButton.setMinimumWidth(0);
        toggleButton.setMinWidth(0);
        toggleButton.setPadding(8, 0, 8, 0);
        toggleButton.setOnClickListener(v -> {
            togglePasswordVisibility();
            // Update button text
            if (passwordVisibility) {
                toggleButton.setText(R.string.applock__password_hide);
            } else {
                toggleButton.setText(R.string.applock__password_show);
            }
        });
        
        container.addView(toggleButton);
        
        addView(container);
        
        // Request focus and show keyboard automatically
        editText.requestFocus();
        post(() -> {
            editText.requestFocus();
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    protected EditText inflatePasswordEditText() {
        return (EditText) LayoutInflater.from(getContext())
                .inflate(R.layout.applock__password_edit_text, this, false);
    }

    public PasswordInputView reset() {
        this.lastText = "";
        this.editText.setText("");
        return this;
    }

    public void ensureKeyboardVisible() {
        editText.requestFocus();

        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(editText, InputMethodManager.SHOW_FORCED);
        
        // Add a delayed attempt as well for more reliability
        postDelayed(() -> {
            if (editText != null) {
                editText.requestFocus();
                InputMethodManager imm2 = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                imm2.showSoftInput(editText, InputMethodManager.SHOW_FORCED);
            }
        }, 200);
    }

    @Override
    public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
        // Not needed
    }

    @Override
    public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
        // Not needed
    }

    @Override
    public void afterTextChanged(Editable editable) {
        if(editable == null || lastText == null)
            return;

        String text = editable.toString();
        this.lastText = text;
    }

    public String getText() {
        return editText.getText().toString();
    }

    public void setOnEditorActionListener(TextView.OnEditorActionListener actionListener) {
        editText.setOnEditorActionListener(actionListener);
    }

    public void togglePasswordVisibility() {
        passwordVisibility = !passwordVisibility;
        if (passwordVisibility) {
            editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        } else {
            editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        // Maintain cursor position
        editText.setSelection(editText.getText().length());
    }

    public boolean matchesMinimumPasswordLength(String input) {
        // No minimum password length requirement
        return true;
    }

    public boolean matchesRequiredPasswordComplexity(String input) {
        // No complexity requirements
        return true;
    }
} 