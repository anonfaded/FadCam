package com.guardanis.applock.views;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import com.guardanis.applock.AppLock;
import com.fadcam.R;
import com.guardanis.applock.pin.PINInputController;
import com.guardanis.applock.pin.PINInputView;
import com.guardanis.applock.utils.LifeCycleUtils;

import java.lang.ref.WeakReference;

import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.app.ActivityCompat;

public abstract class AppLockViewController implements LifeCycleUtils.AppLockActivityLifeCycleCallbacks.Delegate {

    protected PINInputController pinInputController;

    protected WeakReference<Activity> activity;
    protected WeakReference<View> parent;

    protected WeakReference<PINInputView> pinInputView;
    protected WeakReference<AppCompatImageView> fingerprintAuthImageView;

    protected WeakReference<TextView> descriptionView;
    protected WeakReference<View> actionSettings;

    protected boolean autoAuthorizationEnabled = true;

    protected Application.ActivityLifecycleCallbacks activityLifecycleCallbacks;

    public AppLockViewController(Activity activity, View parent) {
        this.activity = new WeakReference<Activity>(activity);
        this.parent = new WeakReference<View>(parent);
        this.descriptionView = new WeakReference((TextView) parent.findViewById(R.id.pin__description));
        this.actionSettings = new WeakReference<View>(parent.findViewById(R.id.pin__action_settings));

        this.pinInputView = new WeakReference((PINInputView) parent.findViewById(R.id.pin__input_view));
        this.fingerprintAuthImageView = new WeakReference(parent.findViewById(R.id.pin__fingerprint_image));

        int inputViewsCount = parent.getResources()
                .getInteger(R.integer.applock__input_pin_item_count);

        boolean passwordCharsEnabled = parent.getResources()
                .getBoolean(R.bool.applock__item_password_chars_enabled);

        this.pinInputController = new PINInputController(pinInputView.get())
                .setInputNumbersCount(inputViewsCount)
                .setPasswordCharactersEnabled(passwordCharsEnabled);

        this.activityLifecycleCallbacks = LifeCycleUtils.attach(activity, this);
    }

    public abstract void setupRootFlow();

    public void setDescription(int descriptionResId) {
        final TextView descriptionView = this.descriptionView.get();

        if (descriptionView == null)
            return;

        descriptionView.setText(descriptionResId);
    }

    public void setDescription(String description) {
        final TextView descriptionView = this.descriptionView.get();

        if (descriptionView == null)
            return;

        descriptionView.setText(description);
    }

    protected <T extends View> void hide(WeakReference<T> weakView) {
        final T view = weakView.get();

        if (view == null)
            return;

        view.setVisibility(View.GONE);
    }

    protected <T extends View> void show(WeakReference<T> weakView) {
        final T view = weakView.get();

        if (view == null)
            return;

        view.setVisibility(View.VISIBLE);
    }

    public PINInputController getPINInputController() {
        return pinInputController;
    }

    public View getParent() {
        return parent.get();
    }

    public void setAutoAuthorizationEnabled(boolean autoAuthorizationEnabled) {
        this.autoAuthorizationEnabled = autoAuthorizationEnabled;
    }

    public void updateActionSettings(final int errorCode) {
        View actionSettings = this.actionSettings.get();

        if (actionSettings == null)
            return;

        if (getSettingsIntent(errorCode) == null) {
            actionSettings.setVisibility(View.GONE);
            return;
        }

        actionSettings.setVisibility(View.VISIBLE);
        actionSettings.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                handleActionSettingsClicked(errorCode);
            }
        });
    }

    protected abstract void handleActionSettingsClicked(int errorCode);

    protected void handleInitialErrorPrompt(int errorCode) {
        Activity activity = this.activity.get();

        if (activity == null)
            return;

        switch (errorCode) {
            case AppLock.ERROR_CODE_FINGERPRINTS_PERMISSION_REQUIRED:
                ActivityCompat.requestPermissions(
                        activity,
                        new String[] { Manifest.permission.USE_FINGERPRINT },
                        AppLock.REQUEST_CODE_FINGERPRINT_PERMISSION);
                break;
            default:
                break;
        }
    }

    protected int getDescriptionResIdForError(int errorCode) {
        switch (errorCode) {
            case AppLock.ERROR_CODE_FINGERPRINTS_PERMISSION_REQUIRED:
                return R.string.applock__fingerprint_error_permission;
            case AppLock.ERROR_CODE_FINGERPRINTS_EMPTY:
                return R.string.applock__fingerprint_error_none;
            case AppLock.ERROR_CODE_FINGERPRINTS_MISSING_HARDWARE:
                return R.string.applock__fingerprint_error_hardware;
            case AppLock.ERROR_CODE_FINGERPRINTS_NOT_LOCALLY_ENROLLED:
                return R.string.applock__fingerprint_error_not_enrolled;
            default:
                return R.string.applock__fingerprint_error_unknown;
        }
    }

    /**
     * @return an Intent directed towards the correct system setting for the error, or null if there is none.
     */
    public Intent getSettingsIntent(int errorCode) {
        switch (errorCode) {
            case AppLock.ERROR_CODE_FINGERPRINTS_PERMISSION_REQUIRED:
                // TODO: App settings?
            case AppLock.ERROR_CODE_FINGERPRINTS_EMPTY:
            case AppLock.ERROR_CODE_FINGERPRINTS_MISSING_HARDWARE:
                return new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS);
            default:
                return null;
        }
    }

    public void unregisterReceivers() {
        Activity activity = this.activity.get();

        if (activity == null)
            return;

        activity.getApplication()
                .unregisterActivityLifecycleCallbacks(activityLifecycleCallbacks);
    }

    @Override
    public void onActivityResumed() { }

    @Override
    public void onActivityPaused() { }
}
