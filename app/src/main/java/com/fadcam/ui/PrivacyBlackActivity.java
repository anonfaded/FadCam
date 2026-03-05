package com.fadcam.ui;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;

public class PrivacyBlackActivity extends Activity {

    private GestureDetector gestureDetector;
    private int tapCount = 0;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable resetTapCountRunnable = () -> tapCount = 0;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        final com.fadcam.SharedPreferencesManager prefs = com.fadcam.SharedPreferencesManager.getInstance(this);

        // Remove title and make completely black
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        
        // Keep screen on while activity is active
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                             WindowManager.LayoutParams.FLAG_FULLSCREEN);
                             
        // Prepare black view
        View blackView = new View(this);
        blackView.setBackgroundColor(0xFF000000); // Solid black
        
        // Enable immersive mode
        blackView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        setContentView(blackView);

        // Toast letting user know how to exit
        Toast.makeText(this, getString(com.fadcam.R.string.privacy_black_mode_enabled_toast), Toast.LENGTH_SHORT).show();

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                if (prefs.isPrivacyBlackLongPressEnabled()) {
                    exitPrivacyMode();
                }
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                // Swipe Up detection
                if (prefs.isPrivacyBlackSwipeUpEnabled() && e1 != null && e2 != null && e1.getY() - e2.getY() > 50 && Math.abs(velocityY) > 100) {
                    exitPrivacyMode();
                    return true;
                }
                return false;
            }
        });

        blackView.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            if (prefs.isPrivacyBlackTripleTapEnabled() && event.getAction() == MotionEvent.ACTION_DOWN) {
                tapCount++;
                if (tapCount == 3) {
                    exitPrivacyMode();
                } else {
                    handler.removeCallbacks(resetTapCountRunnable);
                    handler.postDelayed(resetTapCountRunnable, 500); // 500ms to triple tap
                }
            }
            return true;
        });
    }



    private void exitPrivacyMode() {
        Toast.makeText(this, getString(com.fadcam.R.string.privacy_black_mode_disabled_toast), Toast.LENGTH_SHORT).show();
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
