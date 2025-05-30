package com.guardanis.applock.pin;

import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

import java.lang.ref.WeakReference;

public class PINInputController implements TextView.OnEditorActionListener {

    public interface InputEventListener {
        public void onInputEntered(String input);
    }

    private WeakReference<PINInputView> inputView;
    private WeakReference<InputEventListener> eventListener = new WeakReference<InputEventListener>(null);

    /**
     * If you want to actually receive events after this, make sure you call setInputEventListener
     */
    public PINInputController(PINInputView inputView) {
        this.inputView = new WeakReference<PINInputView>(inputView);

        inputView.setOnEditorActionListener(this);
    }

    public PINInputController ensureKeyboardVisible() {
        final PINInputView inputView = this.inputView.get();

        if (inputView == null)
            return this;

        inputView.postDelayed(new Runnable() {
            public void run() {
                inputView.ensureKeyboardVisible();
            }
        }, 300);

        return this;
    }

    public PINInputController setPasswordCharactersEnabled(boolean passwordCharacterEnabled) {
        this.inputView.get().setPasswordCharactersEnabled(passwordCharacterEnabled);
        return this;
    }

    public PINInputController setInputNumbersCount(int inputNumbersCount) {
        this.inputView.get().setInputViewsCount(inputNumbersCount);
        return this;
    }

    public PINInputController setInputEventListener(InputEventListener eventListener) {
        this.eventListener = new WeakReference<InputEventListener>(eventListener);
        return this;
    }

    @Override
    public boolean onEditorAction(TextView textView, int i, KeyEvent keyEvent) {
        PINInputView inputView = this.inputView.get();

        if (inputView == null)
            return false;

        if(isSoftKeyboardFinishedAction(textView, i, keyEvent)) {
            InputEventListener eventListener = this.eventListener.get();

            if(eventListener != null)
                eventListener.onInputEntered(inputView.getText().toString());

            inputView.reset();

            return true;
        }

        return false;
    }

    private boolean isSoftKeyboardFinishedAction(TextView view, int action, KeyEvent event) {
        // Some devices return null event on editor actions for Enter Button (thanks, Samsung)
        if (!(event == null || event.getAction() == KeyEvent.ACTION_DOWN))
            return false;

        // Enter clicked on Bluetooth Keyboard (action is zero on hardware keyboards)
        if (action == EditorInfo.IME_ACTION_UNSPECIFIED && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)
            return true;

        return action == EditorInfo.IME_ACTION_DONE
                || action == EditorInfo.IME_ACTION_GO
                || action == EditorInfo.IME_ACTION_SEND;
    }

    public boolean matchesRequiredPINLength(String input) {
        final PINInputView inputView = this.inputView.get();

        if (inputView == null)
            return false;

        return inputView.matchesRequiredPINLength(input);
    }
}
