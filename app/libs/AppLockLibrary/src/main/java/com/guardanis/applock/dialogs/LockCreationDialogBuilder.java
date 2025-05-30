package com.guardanis.applock.dialogs;

import android.app.Activity;
import android.view.View;

import com.fadcam.R;
import com.guardanis.applock.views.LockCreationViewController;

public class LockCreationDialogBuilder extends AppLockDialogBuilder<LockCreationViewController> implements LockCreationViewController.Delegate {

    protected Runnable lockCreatedCallback;
    protected Runnable canceledCallback;

    public LockCreationDialogBuilder(Activity activity) {
        super(activity, R.layout.applock__lock_creation);
    }

    /**
     * Set a Runnable to be triggered when a user has successfully enrolled in locking.
     */
    public LockCreationDialogBuilder onLockCreated(Runnable lockCreatedCallback) {
        this.lockCreatedCallback = lockCreatedCallback;

        return this;
    }

    /**
     * Set a Runnable to be triggered when a user has canceled enrollment.
     */
    public LockCreationDialogBuilder onCanceled(Runnable canceledCallback) {
        this.canceledCallback = canceledCallback;

        return this;
    }

    @Override
    protected LockCreationViewController buildViewControllerInstance(View parent) {
        LockCreationViewController controller = new LockCreationViewController(activity.get(), parent);
        controller.setDelegate(this);

        return controller;
    }

    @Override
    public void onLockCreated() {
        dismissDialog();

        if(lockCreatedCallback != null)
            lockCreatedCallback.run();
    }

    @Override
    protected void handleCanceled() {
        super.handleCanceled();

        if(canceledCallback != null)
            canceledCallback.run();
    }
}
