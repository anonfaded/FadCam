package com.guardanis.applock.views;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.view.View;

import com.guardanis.applock.AppLock;
import com.fadcam.R;
import com.guardanis.applock.password.PasswordInputController;
import com.guardanis.applock.pin.PINInputController;
import com.guardanis.applock.services.FingerprintLockService;
import com.guardanis.applock.services.PasswordLockService;
import com.guardanis.applock.services.PINLockService;

import java.lang.ref.WeakReference;

import androidx.core.content.ContextCompat;
import androidx.core.hardware.fingerprint.FingerprintManagerCompat;
import androidx.core.os.CancellationSignal;

public class LockCreationViewController extends AppLockViewController
        implements PINInputController.InputEventListener, PasswordInputController.InputEventListener, FingerprintLockService.AuthenticationDelegate {

    public interface Delegate {
        public void onLockCreated();
    }

    public enum DisplayVariant {
        NONE,
        CHOOSER,
        PIN_CREATION,
        PIN_CONFIRMATION,
        PASSWORD_CREATION,
        PASSWORD_CONFIRMATION,
        FINGERPRINT_AUTHENTICATION
    }

    protected DisplayVariant displayVariant = DisplayVariant.NONE;

    protected WeakReference<Delegate> delegate;
    protected WeakReference<View> chooserParent;

    protected String pinFirst;
    protected String passwordFirst;

    public LockCreationViewController(Activity activity, View parent) {
        super(activity, parent);

        this.chooserParent = new WeakReference(parent.findViewById(R.id.pin__create_chooser_items));
    }

    public LockCreationViewController setDelegate(Delegate delegate) {
        this.delegate = new WeakReference<Delegate>(delegate);

        return this;
    }

    @Override
    public void setupRootFlow() {
        Activity activity = this.activity.get();

        if (activity == null)
            return;

        FingerprintLockService fingerprintService = AppLock.getInstance(activity)
                .getLockService(FingerprintLockService.class);

        if (!fingerprintService.isEnrollmentEligible(activity)) {
            setupPINCreation();

            return;
        }

        setupCreationChooser();
    }

    protected void setupCreationChooser() {
        this.displayVariant = DisplayVariant.CHOOSER;

        hide(fingerprintAuthImageView);
        hide(pinInputView);
        hide(passwordInputView);
        hide(actionSettings);

        show(chooserParent);

        setDescription(R.string.applock__description_chooser);

        View parent = this.parent.get();

        if (parent == null)
            return;

        parent.findViewById(R.id.pin__create_option_pin)
                .setOnClickListener(new View.OnClickListener() {
                    public void onClick(View view) {
                        setupPINCreation();
                    }
                });

        parent.findViewById(R.id.pin__create_option_password)
                .setOnClickListener(new View.OnClickListener() {
                    public void onClick(View view) {
                        setupPasswordCreation();
                    }
                });

        parent.findViewById(R.id.pin__create_option_fingerprint)
                .setOnClickListener(new View.OnClickListener() {
                    public void onClick(View view) {
                        setupFingerprintAuthentication();
                    }
                });
    }

    protected void setupPINCreation() {
        this.displayVariant = DisplayVariant.PIN_CREATION;

        hide(fingerprintAuthImageView);
        hide(chooserParent);
        hide(passwordInputView);
        hide(actionSettings);

        show(pinInputView);

        setDescription(R.string.applock__description_create_pin);

        pinInputController.ensureKeyboardVisible();
        pinInputController.setInputEventListener(this);
    }

    protected void setupPasswordCreation() {
        this.displayVariant = DisplayVariant.PASSWORD_CREATION;

        hide(fingerprintAuthImageView);
        hide(chooserParent);
        hide(pinInputView);
        hide(actionSettings);

        show(passwordInputView);

        setDescription(R.string.applock__description_create_password);

        passwordInputController.ensureKeyboardVisible();
        passwordInputController.setInputEventListener(this);
    }

    protected void setupPINConfirmation() {
        this.displayVariant = DisplayVariant.PIN_CONFIRMATION;

        hide(fingerprintAuthImageView);
        hide(chooserParent);
        hide(passwordInputView);
        hide(actionSettings);

        show(pinInputView);

        setDescription(R.string.applock__description_confirm);

        pinInputController.ensureKeyboardVisible();
        pinInputController.setInputEventListener(this);
    }

    protected void setupPasswordConfirmation() {
        this.displayVariant = DisplayVariant.PASSWORD_CONFIRMATION;

        hide(fingerprintAuthImageView);
        hide(chooserParent);
        hide(pinInputView);
        hide(actionSettings);

        show(passwordInputView);

        setDescription(R.string.applock__description_confirm);
        
        // Clear the input field for confirmation
        if (passwordInputView.get() != null) {
            passwordInputView.get().reset();
        }

        // Ensure keyboard is visible and focused
        passwordInputController.ensureKeyboardVisible();
        passwordInputController.setInputEventListener(this);
    }

    @Override
    public void onInputEntered(String input) {
        switch (displayVariant) {
            case PIN_CREATION:
                if(!pinInputController.matchesRequiredPINLength(input)) {
                    setDescription(R.string.applock__unlock_error_insufficient_selection);
                    return;
                }

                this.pinFirst = input;
                setupPINConfirmation();
                break;
                
            case PASSWORD_CREATION:
                if(!passwordInputController.matchesMinimumPasswordLength(input)) {
                    int minLength = passwordInputView.get().getResources().getInteger(R.integer.applock__input_password_min_length);
                    String message = String.format(
                        passwordInputView.get().getResources().getString(R.string.applock__password_validation_min_length),
                        minLength);
                    setDescription(message);
                    return;
                }
                
                if(!passwordInputController.matchesRequiredPasswordComplexity(input)) {
                    setDescription(R.string.applock__password_complexity_requirement);
                    return;
                }

                this.passwordFirst = input;
                setupPasswordConfirmation();
                break;
                
            case PIN_CONFIRMATION:
                if(!pinInputController.matchesRequiredPINLength(input)) {
                    setDescription(R.string.applock__unlock_error_insufficient_selection);
                    return;
                }

                if(!input.equals(pinFirst)) {
                    this.pinFirst = null;
                    setupPINCreation();
                    setDescription(R.string.applock__description_create_pin_reattempt);
                    return;
                }

                createPINLock(input);
                break;
                
            case PASSWORD_CONFIRMATION:
                if(!passwordInputController.matchesMinimumPasswordLength(input)) {
                    int minLength = passwordInputView.get().getResources().getInteger(R.integer.applock__input_password_min_length);
                    String message = String.format(
                        passwordInputView.get().getResources().getString(R.string.applock__password_validation_min_length),
                        minLength);
                    setDescription(message);
                    return;
                }
                
                if(!passwordInputController.matchesRequiredPasswordComplexity(input)) {
                    setDescription(R.string.applock__password_complexity_requirement);
                    return;
                }

                if(!input.equals(passwordFirst)) {
                    this.passwordFirst = null;
                    setupPasswordCreation();
                    setDescription(R.string.applock__description_create_password_reattempt);
                    return;
                }

                createPasswordLock(input);
                break;
                
            default:
                break;
        }
    }

    protected void createPINLock(String input) {
        Activity activity = this.activity.get();

        if (activity == null)
            return;

        // Invalidate other authentication methods before enrolling PIN
        AppLock.getInstance(activity).getLockService(PasswordLockService.class).invalidateEnrollments(activity);
        AppLock.getInstance(activity).getLockService(FingerprintLockService.class).invalidateEnrollments(activity);

        AppLock.getInstance(activity)
                .getLockService(PINLockService.class)
                .enroll(activity, input);

        handleLockCreated();
    }

    protected void createPasswordLock(String input) {
        Activity activity = this.activity.get();

        if (activity == null)
            return;

        // Invalidate other authentication methods before enrolling password
        AppLock.getInstance(activity).getLockService(PINLockService.class).invalidateEnrollments(activity);
        AppLock.getInstance(activity).getLockService(FingerprintLockService.class).invalidateEnrollments(activity);

        AppLock.getInstance(activity)
                .getLockService(PasswordLockService.class)
                .enroll(activity, input);

        handleLockCreated();
    }

    protected void setupFingerprintAuthentication() {
        this.displayVariant = DisplayVariant.FINGERPRINT_AUTHENTICATION;

        hide(pinInputView);
        hide(passwordInputView);
        hide(chooserParent);
        hide(actionSettings);

        show(fingerprintAuthImageView);

        setDescription(R.string.applock__description_create_fingerprint);

        if (autoAuthorizationEnabled)
            attemptFingerprintAuthentication();
    }

    protected void attemptFingerprintAuthentication() {
        Activity activity = this.activity.get();

        if (activity == null)
            return;

        AppLock.getInstance(activity)
                .getLockService(FingerprintLockService.class)
                .enroll(activity, this);
    }
    @Override
    public void onResolutionRequired(int errorCode) {
        setDescription(getDescriptionResIdForError(errorCode));
        updateActionSettings(errorCode);
        handleInitialErrorPrompt(errorCode);
    }

    @Override
    public void onAuthenticationHelp(int code, CharSequence message) {
        Activity activity = this.activity.get();

        if (activity == null)
            return;

        String unformattedHelpMessage = activity.getString(R.string.applock__description_unlock_fingerprint_help);
        String formatted = String.format(unformattedHelpMessage, message);

        setDescription(formatted);
    }

    @Override
    public void onAuthenticating(CancellationSignal cancellationSignal) {
        // Handled internally
    }

    @Override
    public void onAuthenticationSuccess(FingerprintManagerCompat.AuthenticationResult result) {
        Activity activity = this.activity.get();
        
        if (activity != null) {
            // Invalidate other authentication methods before enrolling fingerprint
            AppLock.getInstance(activity).getLockService(PINLockService.class).invalidateEnrollments(activity);
            AppLock.getInstance(activity).getLockService(PasswordLockService.class).invalidateEnrollments(activity);
        }
        
        handleLockCreated();
    }

    @Override
    public void onAuthenticationFailed(String message) {
        setDescription(message);
    }

    protected void handleLockCreated() {
        this.displayVariant = DisplayVariant.NONE;

        Delegate delegate = this.delegate.get();

        if (delegate != null)
            delegate.onLockCreated();
    }

    @Override
    public void onActivityPaused() {
        Activity activity = this.activity.get();

        if (activity == null)
            return;

        AppLock.getInstance(activity)
                .getLockService(FingerprintLockService.class)
                .cancelPendingAuthentications(activity);

        if (displayVariant == DisplayVariant.FINGERPRINT_AUTHENTICATION)
            setDescription(R.string.applock__description_create_fingerprint_paused);
    }

    @Override
    public void onActivityResumed() {
        Activity activity = this.activity.get();

        if (activity == null || displayVariant != DisplayVariant.FINGERPRINT_AUTHENTICATION)
            return;

        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.USE_FINGERPRINT) != PackageManager.PERMISSION_GRANTED) {
            setDescription(R.string.applock__fingerprint_error_permission_multiple);
            updateActionSettings(AppLock.ERROR_CODE_FINGERPRINTS_PERMISSION_REQUIRED);
            return;
        }

        setDescription(R.string.applock__description_create_fingerprint);
        hide(actionSettings);

        attemptFingerprintAuthentication();
    }

    @Override
    protected void handleActionSettingsClicked(int errorCode) {
        Activity activity = this.activity.get();
        Intent intent = getSettingsIntent(errorCode);

        if (activity == null || intent == null)
            return;

        activity.startActivity(intent);
    }
}
