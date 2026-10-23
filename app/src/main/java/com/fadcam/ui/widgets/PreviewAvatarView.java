package com.fadcam.ui.widgets;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.fadcam.R;

/**
 * The app's avatar, awake on a card. It only blinks: the sideways glance belongs to the
 * Pro screen, where the eyes track the call to action. The drawables are shared with the
 * home screen; the blink loop lives here so a host card needs no state machine.
 */
public class PreviewAvatarView extends FrameLayout {

    private static final long BLINK_MIN_DELAY_MS = 3200L;
    private static final long BLINK_MAX_DELAY_MS = 6200L;

    private final ImageView avatarView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable blinkTask = this::blink;

    private boolean playing;

    public PreviewAvatarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        avatarView = new ImageView(context);
        avatarView.setImageResource(R.drawable.toggle_on_idle_pro);
        avatarView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // The copy carries the message; the mascot is decoration next to it.
        avatarView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams avatarParams = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        avatarParams.gravity = Gravity.CENTER;
        addView(avatarView, avatarParams);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startBlinking();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopBlinking();
        super.onDetachedFromWindow();
    }

    private void startBlinking() {
        if (playing) return;
        playing = true;
        avatarView.setImageResource(R.drawable.toggle_on_idle_pro);
        if (!motionAllowed()) return;
        scheduleBlink();
    }

    private void stopBlinking() {
        playing = false;
        handler.removeCallbacks(blinkTask);
    }

    private void scheduleBlink() {
        handler.removeCallbacks(blinkTask);
        handler.postDelayed(blinkTask, randomDelay());
    }

    /** Plays the one-shot blink, then returns to the open-eyed pose. */
    private void blink() {
        if (!playing) return;
        avatarView.setImageResource(R.drawable.toggle_on_blink_pro);
        Drawable drawable = avatarView.getDrawable();
        Runnable finished = () -> {
            if (!playing) return;
            avatarView.setImageResource(R.drawable.toggle_on_idle_pro);
            scheduleBlink();
        };
        if (drawable instanceof Animatable2) {
            Animatable2 animatable = (Animatable2) drawable;
            animatable.clearAnimationCallbacks();
            animatable.registerAnimationCallback(new Animatable2.AnimationCallback() {
                @Override
                public void onAnimationEnd(Drawable finishedDrawable) {
                    finished.run();
                }
            });
            animatable.start();
        } else if (drawable instanceof Animatable) {
            ((Animatable) drawable).start();
            handler.postDelayed(finished, 260L);
        } else {
            finished.run();
        }
    }

    private long randomDelay() {
        return BLINK_MIN_DELAY_MS
                + (long) (Math.random() * (BLINK_MAX_DELAY_MS - BLINK_MIN_DELAY_MS));
    }

    /** Honours the system animation scale: a disabled animator means a still mascot. */
    private boolean motionAllowed() {
        return Settings.Global.getFloat(getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }
}
