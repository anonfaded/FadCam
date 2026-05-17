package com.guardanis.applock.activities;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import com.fadcam.R;
import com.guardanis.applock.views.UnlockViewController;

public class UnlockActivity extends AppCompatActivity implements UnlockViewController.Delegate {

    public static final String INTENT_ALLOW_UNLOCKED_EXIT = "pin__allow_activity_exit"; // false by default

    protected UnlockViewController viewController;

    @Override
    public void onCreate(Bundle savedInstance) {
        super.onCreate(savedInstance);

        setContentView(R.layout.applock__activity_unlock);

        this.viewController = new UnlockViewController(this, findViewById(R.id.pin__container));
        this.viewController.setAutoAuthorizationEnabled(false); // Disable auto authorization so fingerprint doesn't crash onResume
        this.viewController.setDelegate(this);
        this.viewController.setupRootFlow();
        this.viewController.setAutoAuthorizationEnabled(true);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackPressed();
            }
        });
    }

    @Override
    public void onUnlockSuccessful() {
        setResult(Activity.RESULT_OK);
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if(keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getRepeatCount() == 0)
                handleBackPressed();

            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        handleBackPressed();
    }

    protected void handleBackPressed() {
        if(!getIntent().getBooleanExtra(INTENT_ALLOW_UNLOCKED_EXIT, false)){
            Toast.makeText(this, getString(R.string.applock__toast_unlock_required), Toast.LENGTH_LONG)
                    .show();

            return;
        }

        setResult(Activity.RESULT_CANCELED);
        finish();
    }
}
