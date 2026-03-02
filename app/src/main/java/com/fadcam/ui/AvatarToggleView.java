package com.fadcam.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.fadcam.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A custom toggle view that displays the FadCam avatar eye in place of a standard switch.
 *
 * <p>States:
 * <ul>
 *   <li><b>Checked (on/awake)</b>: toggle_on_idle drawable + periodic blink animation.</li>
 *   <li><b>Unchecked (off/sleeping)</b>: toggle_off drawable + floating zzZ badge.</li>
 * </ul>
 *
 * <p>API is a drop-in replacement for {@code SwitchCompat}:
 * <pre>
 *   setChecked(boolean)              — programmatic state change (no listener fired, instant)
 *   isChecked()                      — current state
 *   setOnCheckedChangeListener(...)  — called only on user-initiated taps
 *   performClick()                   — user tap programmatically
 * </pre>
 *
 * <p>Size: set {@code android:layout_width} and {@code android:layout_height} in XML.
 * The avatar ImageView fills the view bounds; the zzZ badge overlays the top-end corner.
 *
 * <p>Resource efficient: single {@link Handler} per instance, cancelled in
 * {@link #onDetachedFromWindow()}. AVD callbacks are cleared before re-use to prevent leaks.
 */
public class AvatarToggleView extends FrameLayout {

    // ─── Constants ───────────────────────────────────────────────────────────

    /** Duration for the wake-up AVD; fallback when Animatable2 callback is unavailable. */
    private static final long WAKE_AVD_DURATION_MS  = 480L;
    /** Duration for the sleep AVD; fallback when Animatable2 callback is unavailable. */
    private static final long SLEEP_AVD_DURATION_MS = 320L;

    // ─── Views ───────────────────────────────────────────────────────────────

    private ImageView ivAvatar;
    private LinearLayout zzzGroup;
    private View liveDot;

    // ─── State ───────────────────────────────────────────────────────────────

    private boolean checked = false;

    @Nullable
    private CompoundButton.OnCheckedChangeListener listener;

    // ─── Animation ───────────────────────────────────────────────────────────

    private final Handler handler          = new Handler(Looper.getMainLooper());
    private final Random  blinkRandom      = new Random();
    @Nullable private Runnable       blinkRunnable;
    @Nullable private ValueAnimator  breathingAnimator;
    @Nullable private ValueAnimator  liveDotPulseAnimator;
    private final List<ObjectAnimator> floatingZAnimators = new ArrayList<>();

    // ─── Constructors ────────────────────────────────────────────────────────

    public AvatarToggleView(Context context) {
        super(context);
        init(context);
    }

    public AvatarToggleView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public AvatarToggleView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    // ─── Initialization ──────────────────────────────────────────────────────

    private void init(Context context) {
        setClickable(true);
        setFocusable(true);
        setClipChildren(false);
        setClipToPadding(false);

        // Avatar image — fills the view, respects whatever size parent sets.
        ivAvatar = new ImageView(context);
        FrameLayout.LayoutParams imgParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        imgParams.gravity = Gravity.CENTER;
        ivAvatar.setLayoutParams(imgParams);
        ivAvatar.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ivAvatar.setClickable(false);
        ivAvatar.setFocusable(false);
        addView(ivAvatar);

        // Live green dot — top-end corner, pulsing when awake.
        liveDot = new View(context);
        int dotSize = dpPx(context, 8);
        FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(dotSize, dotSize);
        dotParams.gravity = Gravity.TOP | Gravity.END;
        dotParams.topMargin = dpPx(context, 2);
        dotParams.rightMargin = dpPx(context, 2);
        liveDot.setLayoutParams(dotParams);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(0xFF4CAF50);
        liveDot.setBackground(gd);
        liveDot.setVisibility(View.GONE);
        addView(liveDot);

        // zzZ badge — three letters in a row, positioned top|end.
        zzzGroup = buildZzzBadge(context);
        FrameLayout.LayoutParams zzzParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        zzzParams.gravity = Gravity.TOP | Gravity.END;
        zzzGroup.setLayoutParams(zzzParams);
        zzzGroup.setVisibility(View.GONE);
        addView(zzzGroup);

        // Tap toggles state + fires listener.
        // NOTE: performToggle() is invoked via performClick() override —
        // do NOT also register a separate OnClickListener here or it would
        // call performToggle() twice (once from the override, once from the dispatch).

        // Apply initial (unchecked) state instantly.
        applyState(false, false);
    }

    private static int dpPx(Context ctx, int value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * Builds the three-letter zzZ badge {@link LinearLayout}.
     */
    private LinearLayout buildZzzBadge(Context context) {
        LinearLayout group = new LinearLayout(context);
        group.setOrientation(LinearLayout.HORIZONTAL);
        group.setGravity(Gravity.BOTTOM);

        Typeface bold = Typeface.create("sans-serif-black", Typeface.BOLD);
        group.addView(makeZLetter(context, "z",  7f,  0xFFa8d8f0, bold));
        group.addView(makeZLetter(context, "Z", 10f,  0xFFceecf8, bold));
        group.addView(makeZLetter(context, "Z", 13f,  0xFFffffff, bold));
        return group;
    }

    private TextView makeZLetter(Context ctx, String text, float spSize, int color, Typeface tf) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(spSize);
        tv.setTextColor(color);
        tv.setTypeface(tf);
        tv.setPadding(0, 0, 1, 0);
        return tv;
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Returns the current checked state.
     */
    public boolean isChecked() {
        return checked;
    }

    /**
     * Sets the checked state programmatically (instant, no animation, listener NOT fired).
     * No-op if the state is already equal — prevents cancelling an in-progress animation
     * when a ViewModel re-render observes the same state that was just set by the user.
     * Safe to call before setting a listener; safe inside a RecyclerView adapter.
     */
    public void setChecked(boolean newChecked) {
        if (this.checked == newChecked) return;
        this.checked = newChecked;
        applyState(newChecked, false);
    }

    /**
     * Sets the checked state with optional animation (listener NOT fired).
     * No-op if the state is already equal and animate=false.
     */
    public void setChecked(boolean newChecked, boolean animate) {
        if (!animate && this.checked == newChecked) return;
        this.checked = newChecked;
        applyState(newChecked, animate);
    }

    /**
     * Registers a listener to be called when the user taps the toggle.
     * Pass {@code null} to remove the current listener.
     *
     * @param l the listener, or {@code null}
     */
    public void setOnCheckedChangeListener(@Nullable CompoundButton.OnCheckedChangeListener l) {
        this.listener = l;
    }

    /**
     * Simulates a user tap: toggles state with animation and fires the listener.
     */
    @Override
    public boolean performClick() {
        performToggle();
        return super.performClick();
    }

    // ─── Private internals ───────────────────────────────────────────────────

    /** User-initiated toggle: animates + fires listener. */
    private void performToggle() {
        checked = !checked;
        applyState(checked, true);
        if (listener != null) listener.onCheckedChanged(null, checked);
    }

    /**
     * Drives the avatar into either the awake (on) or sleeping (off) state.
     *
     * @param on      target state
     * @param animate whether to play the transition AVD drawable
     */
    private void applyState(boolean on, boolean animate) {
        stopBlink();
        if (on) {
            // ── Wake path ────────────────────────────────────────────────────
            stopBreathing();
            ivAvatar.setAlpha(1f);
            zzzGroup.setVisibility(View.GONE);
            stopFloatingZ();

            // Show live dot with fade-in then pulse.
            liveDot.animate().cancel();
            liveDot.setAlpha(0f);
            liveDot.setVisibility(View.VISIBLE);
            liveDot.animate().alpha(1f).setDuration(280)
                    .withEndAction(() -> {
                        if (isAttachedToWindow() && checked) startLiveDotPulse();
                    }).start();

            if (animate) {
                startAvd(R.drawable.toggle_on_anim, WAKE_AVD_DURATION_MS, () -> {
                    ivAvatar.setImageResource(R.drawable.toggle_on_idle);
                    startBlink();
                });
            } else {
                ivAvatar.setImageResource(R.drawable.toggle_on_idle);
                startBlink();
            }
        } else {
            // ── Sleep path ───────────────────────────────────────────────────
            stopLiveDotPulse();
            if (liveDot.getVisibility() == View.VISIBLE) {
                liveDot.animate().cancel();
                liveDot.animate().alpha(0f).setDuration(220)
                        .withEndAction(() -> {
                            liveDot.setVisibility(View.GONE);
                            liveDot.setAlpha(1f);
                        }).start();
            }

            if (animate) {
                startAvd(R.drawable.toggle_off_anim, SLEEP_AVD_DURATION_MS, () -> {
                    ivAvatar.setImageResource(R.drawable.toggle_off);
                    startBreathing();
                    showZzz(true);
                });
            } else {
                ivAvatar.setImageResource(R.drawable.toggle_off);
                startBreathing();
                showZzz(false);
            }
        }
    }

    /**
     * Starts an AVD drawable, then runs {@code onEnd} when it completes.
     * Handles both {@link Animatable2} (API 23+) and {@link Animatable} fallback.
     */
    private void startAvd(int drawableRes, long fallbackDurationMs, Runnable onEnd) {
        ivAvatar.setImageResource(drawableRes);
        Drawable d = ivAvatar.getDrawable();
        if (d instanceof Animatable2) {
            Animatable2 avd = (Animatable2) d;
            // Clear previous callbacks to avoid accumulation on repeated calls.
            avd.clearAnimationCallbacks();
            avd.registerAnimationCallback(new Animatable2.AnimationCallback() {
                @Override
                public void onAnimationEnd(Drawable drawable) {
                    if (ivAvatar != null && ivAvatar.isAttachedToWindow()) {
                        onEnd.run();
                    }
                }
            });
            avd.start();
        } else if (d instanceof Animatable) {
            ((Animatable) d).start();
            ivAvatar.postDelayed(() -> {
                if (ivAvatar != null && ivAvatar.isAttachedToWindow()) {
                    onEnd.run();
                }
            }, fallbackDurationMs);
        } else {
            onEnd.run();
        }
    }

    // ─── zzZ badge ───────────────────────────────────────────────────────────

    /**
     * Shows the zzZ badge.
     * animate=true  → staggered pop-in with OvershootInterpolator, then infinite float.
     * animate=false → instant appearance at full alpha/scale, then infinite float directly.
     *                  Used for initial state, re-attachment, and programmatic setChecked.
     */
    private void showZzz(boolean animate) {
        if (!isAttachedToWindow()) return;
        stopFloatingZ();
        zzzGroup.setVisibility(View.VISIBLE);
        if (animate) {
            // Pre-pop-in state: tiny and transparent.
            for (int i = 0; i < zzzGroup.getChildCount(); i++) {
                View v = zzzGroup.getChildAt(i);
                v.animate().cancel();
                v.setAlpha(0f);
                v.setScaleX(0.3f);
                v.setScaleY(0.3f);
                v.setTranslationY(0f);
            }
            // Staggered pop-in, then start infinite float.
            int count = zzzGroup.getChildCount();
            long maxPopDelay = (count - 1) * 130L + 220L;
            for (int i = 0; i < count; i++) {
                final View v = zzzGroup.getChildAt(i);
                v.animate()
                        .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                        .setStartDelay(i * 130L).setDuration(220)
                        .setInterpolator(new OvershootInterpolator(1.4f))
                        .start();
            }
            handler.postDelayed(() -> {
                if (!isAttachedToWindow() || checked) return;
                startFloatingZ();
            }, maxPopDelay);
        } else {
            // Instant show: full alpha/scale, float immediately.
            for (int i = 0; i < zzzGroup.getChildCount(); i++) {
                View v = zzzGroup.getChildAt(i);
                v.animate().cancel();
                v.setAlpha(1f);
                v.setScaleX(1f);
                v.setScaleY(1f);
                v.setTranslationY(0f);
            }
            startFloatingZ();
        }
    }

    private void startFloatingZ() {
        stopFloatingZ();
        int count = zzzGroup.getChildCount();
        long[]  durations  = {1600L, 1900L, 2200L};
        float[] amplitudes = {4f, 5f, 6f};
        for (int i = 0; i < count && i < durations.length; i++) {
            View v = zzzGroup.getChildAt(i);
            if (v == null) continue;
            ObjectAnimator a = ObjectAnimator.ofFloat(v, "translationY", 0f, -amplitudes[i]);
            a.setDuration(durations[i]);
            a.setRepeatCount(ObjectAnimator.INFINITE);
            a.setRepeatMode(ObjectAnimator.REVERSE);
            a.setInterpolator(new AccelerateDecelerateInterpolator());
            a.start();
            floatingZAnimators.add(a);
        }
    }

    private void stopFloatingZ() {
        for (ObjectAnimator a : floatingZAnimators) a.cancel();
        floatingZAnimators.clear();
    }

    // ─── Blink loop ──────────────────────────────────────────────────────────

    private void startBlink() {
        stopBlink();
        scheduleNextBlink(2500 + blinkRandom.nextInt(2000));
    }

    private void scheduleNextBlink(long delayMs) {
        blinkRunnable = () -> {
            if (!isAttachedToWindow() || !checked || ivAvatar == null) return;
            ivAvatar.setImageResource(R.drawable.toggle_on_blink);
            Drawable d = ivAvatar.getDrawable();
            if (d instanceof Animatable2) {
                Animatable2 avd = (Animatable2) d;
                avd.clearAnimationCallbacks();
                avd.registerAnimationCallback(new Animatable2.AnimationCallback() {
                    @Override public void onAnimationEnd(Drawable drawable) {
                        if (isAttachedToWindow() && checked && ivAvatar != null) {
                            ivAvatar.setImageResource(R.drawable.toggle_on_idle);
                            scheduleNextBlink(3000 + blinkRandom.nextInt(2500));
                        }
                    }
                });
                avd.start();
            } else if (d instanceof Animatable) {
                ((Animatable) d).start();
                ivAvatar.postDelayed(() -> {
                    if (isAttachedToWindow() && checked && ivAvatar != null) {
                        ivAvatar.setImageResource(R.drawable.toggle_on_idle);
                        scheduleNextBlink(3000 + blinkRandom.nextInt(2500));
                    }
                }, 260);
            }
        };
        handler.postDelayed(blinkRunnable, delayMs);
    }

    private void stopBlink() {
        if (blinkRunnable != null) {
            handler.removeCallbacks(blinkRunnable);
            blinkRunnable = null;
        }
        // Restore idle if frozen mid-blink.
        if (ivAvatar != null && checked) {
            Drawable d = ivAvatar.getDrawable();
            if (d instanceof Animatable) ((Animatable) d).stop();
        }
    }

    // ─── Breathing animation (sleeping state) ────────────────────────────────

    private void startBreathing() {
        stopBreathing();
        ivAvatar.setAlpha(0.70f);
        breathingAnimator = ValueAnimator.ofFloat(0.55f, 0.80f);
        breathingAnimator.setDuration(2800);
        breathingAnimator.setRepeatCount(ValueAnimator.INFINITE);
        breathingAnimator.setRepeatMode(ValueAnimator.REVERSE);
        breathingAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        breathingAnimator.addUpdateListener(a -> {
            if (ivAvatar != null && isAttachedToWindow()) {
                ivAvatar.setAlpha((float) a.getAnimatedValue());
            }
        });
        breathingAnimator.start();
    }

    private void stopBreathing() {
        if (breathingAnimator != null) {
            breathingAnimator.cancel();
            breathingAnimator = null;
        }
        if (ivAvatar != null) ivAvatar.setAlpha(1f);
    }

    // ─── Live dot pulse ───────────────────────────────────────────────────────

    private void startLiveDotPulse() {
        stopLiveDotPulse();
        liveDotPulseAnimator = ValueAnimator.ofFloat(1f, 0f);
        liveDotPulseAnimator.setDuration(900);
        liveDotPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        liveDotPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        liveDotPulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        liveDotPulseAnimator.addUpdateListener(a -> {
            if (liveDot != null && isAttachedToWindow()) {
                float t = (float) a.getAnimatedValue();
                liveDot.setAlpha(0.55f + 0.45f * t);
                liveDot.setScaleX(0.85f + 0.15f * t);
                liveDot.setScaleY(0.85f + 0.15f * t);
            }
        });
        liveDotPulseAnimator.start();
    }

    private void stopLiveDotPulse() {
        if (liveDotPulseAnimator != null) {
            liveDotPulseAnimator.cancel();
            liveDotPulseAnimator = null;
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopBlink();
        stopBreathing();
        stopLiveDotPulse();
        stopFloatingZ();
        handler.removeCallbacksAndMessages(null);
        cancelAllChildAnimations();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Disable clipping in parent so zzZ badge can overflow bounds.
        if (getParent() instanceof ViewGroup) {
            ((ViewGroup) getParent()).setClipChildren(false);
            ((ViewGroup) getParent()).setClipToPadding(false);
        }
        // Re-apply without animation when view is reattached (e.g. RecyclerView recycling).
        applyState(checked, false);
    }

    private void cancelAllChildAnimations() {
        if (ivAvatar != null) {
            ivAvatar.animate().cancel();
            Drawable d = ivAvatar.getDrawable();
            if (d instanceof Animatable) ((Animatable) d).stop();
        }
        if (liveDot != null) liveDot.animate().cancel();
        if (zzzGroup != null) {
            for (int i = 0; i < zzzGroup.getChildCount(); i++) {
                zzzGroup.getChildAt(i).animate().cancel();
            }
        }
    }
}
