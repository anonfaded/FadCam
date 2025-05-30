package com.guardanis.applock.activities;

import android.app.Activity;
import android.content.Intent;

import com.guardanis.applock.AppLock;

import androidx.appcompat.app.AppCompatActivity;

public class LockableAppCompatActivity extends AppCompatActivity {

    @Override
    protected void onPostResume(){
        super.onPostResume();

        AppLock.onActivityResumed(this);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == AppLock.REQUEST_CODE_UNLOCK && resultCode == Activity.RESULT_CANCELED)
            handleUnlockActivityReturnFailure();
    }

    protected void handleUnlockActivityReturnFailure() {
        setResult(Activity.RESULT_CANCELED);
        finish();
    }
}

