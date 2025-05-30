package com.guardanis.applock.pin;

import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;

import com.fadcam.R;

import java.lang.ref.WeakReference;

public class PINItemAnimator extends Thread {

    public enum ItemAnimationDirection {
        IN, OUT
    }

    private static final int ANIMATION_DURATION = 250;
    private static final int UPDATE_RATE = 25;

    private WeakReference<PINInputView> inputView;
    private PINItemView itemView;
    private ItemAnimationDirection animationDirection;
    private Interpolator itemInterpolator = new AccelerateDecelerateInterpolator();

    private float minSizePercent;

    private long startTime;
    private boolean canceled = false;

    public PINItemAnimator(PINInputView inputView, PINItemView itemView, ItemAnimationDirection animationDirection) {
        this.inputView = new WeakReference<PINInputView>(inputView);
        this.itemView = itemView;
        this.animationDirection = animationDirection;
        this.minSizePercent = Float.parseFloat(inputView.getResources().getString(R.string.applock__empty_item_min_size_percent));
    }

    @Override
    public void run() {
        this.startTime = System.currentTimeMillis();

        try{
            if(animationDirection == ItemAnimationDirection.IN)
                animateIn();
            else
                animateOut();
        }
        catch(Exception e){ e.printStackTrace(); }
    }

    private void animateIn() throws Exception {
        float percent = minSizePercent;

        while(percent < 1 && !canceled){
            percent = Math.min(minSizePercent + calculatePercentComplete(), 1);
            updateView(percent);

            Thread.sleep(UPDATE_RATE);
        }
    }

    private void animateOut() throws Exception {
        float percent = 1 - calculatePercentComplete();

        while(minSizePercent < percent && !canceled){
            percent = Math.max(1 - calculatePercentComplete(), minSizePercent);
            updateView(percent);

            Thread.sleep(UPDATE_RATE);
        }
    }

    protected float calculatePercentComplete() {
        float completed = ((float) (System.currentTimeMillis() - startTime)) / ANIMATION_DURATION;

        return itemInterpolator.getInterpolation(completed);
    }

    private void updateView(final float percent) {
        final PINInputView inputView = this.inputView.get();

        if (inputView == null)
            return;

        inputView.post(new Runnable() {
            public void run() {
                itemView.onAnimationUpdate(percent);

                inputView.invalidate();
            }
        });
    }

    public void cancel() {
        this.canceled = true;
    }

}
