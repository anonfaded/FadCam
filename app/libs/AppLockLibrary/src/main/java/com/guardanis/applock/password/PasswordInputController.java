package com.guardanis.applock.password;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;
import androidx.annotation.Nullable;

public class PasswordInputController {

    public interface InputEventListener {
        public void onInputEntered(String input);
    }

    private PasswordInputView passwordInputView;
    private InputEventListener listener;

    public PasswordInputController(Context context, View parent) {
        this(parent.findViewById(com.fadcam.R.id.pin__password_input_view));
    }

    public PasswordInputController(PasswordInputView passwordInputView) {
        this.passwordInputView = passwordInputView;

        if (this.passwordInputView != null) {
            this.passwordInputView.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override
                public boolean onEditorAction(TextView textView, int actionId, KeyEvent keyEvent) {
                    if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                        if (listener != null)
                            listener.onInputEntered(passwordInputView.getText());
                        return true;
                    }
                    return false;
                }
            });
        }
    }

    public void ensureKeyboardVisible() {
        if (passwordInputView != null) {
            passwordInputView.ensureKeyboardVisible();
        }
    }

    public PasswordInputController setInputEventListener(InputEventListener listener) {
        this.listener = listener;
        ensureKeyboardVisible();
        return this;
    }

    public boolean matchesMinimumPasswordLength(String input) {
        return passwordInputView != null && passwordInputView.matchesMinimumPasswordLength(input);
    }

    public boolean matchesRequiredPasswordComplexity(String input) {
        return passwordInputView != null && passwordInputView.matchesRequiredPasswordComplexity(input);
    }

    public void togglePasswordVisibility() {
        if (passwordInputView != null) {
            passwordInputView.togglePasswordVisibility();
        }
    }

    public boolean isInputValid() {
        String password = passwordInputView.getText();
        return password != null && !password.isEmpty();
    }

    @Nullable
    public String getEncodedInputValue() {
        if (!isInputValid())
            return null;

        return passwordInputView.getText();
    }
} 