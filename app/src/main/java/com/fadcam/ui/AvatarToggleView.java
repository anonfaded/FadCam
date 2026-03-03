package com.fadcam.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.Drawable;
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

import com.fadcam.Constants;
import com.fadcam.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private static final long SLEEP_AVD_DURATION_MS = 480L;

    // ─── Color-specific drawable lookup ───────────────────────────────────────────

    private static final int RES_IDLE = 0, RES_BLINK = 1, RES_WAKE = 2, RES_SLEEP = 3;
    private static final int[] DEFAULT_DRAWABLES = {
        R.drawable.toggle_on_idle, R.drawable.toggle_on_blink,
        R.drawable.toggle_on_anim, R.drawable.toggle_off_anim
    };
    /** Maps eye-color ARGB ints → [idle, blink, wake, sleep] drawable resource IDs. */
    private static final Map<Integer, int[]> EYE_COLOR_DRAWABLES;
    static {
        Map<Integer, int[]> m = new HashMap<>();
        m.put(0xFFFF1744, new int[]{ R.drawable.toggle_on_idle_ruby,    R.drawable.toggle_on_blink_ruby,    R.drawable.toggle_on_anim_ruby,    R.drawable.toggle_off_anim_ruby });
        m.put(0xFF00E5FF, new int[]{ R.drawable.toggle_on_idle_cyan,    R.drawable.toggle_on_blink_cyan,    R.drawable.toggle_on_anim_cyan,    R.drawable.toggle_off_anim_cyan });
        m.put(0xFFD500F9, new int[]{ R.drawable.toggle_on_idle_violet,  R.drawable.toggle_on_blink_violet,  R.drawable.toggle_on_anim_violet,  R.drawable.toggle_off_anim_violet });
        m.put(0xFF2979FF, new int[]{ R.drawable.toggle_on_idle_cobalt,  R.drawable.toggle_on_blink_cobalt,  R.drawable.toggle_on_anim_cobalt,  R.drawable.toggle_off_anim_cobalt });
        m.put(0xFFFFD740, new int[]{ R.drawable.toggle_on_idle_amber,   R.drawable.toggle_on_blink_amber,   R.drawable.toggle_on_anim_amber,   R.drawable.toggle_off_anim_amber });
        m.put(0xFF00E676, new int[]{ R.drawable.toggle_on_idle_lime,    R.drawable.toggle_on_blink_lime,    R.drawable.toggle_on_anim_lime,    R.drawable.toggle_off_anim_lime });
        m.put(0xFFF50057, new int[]{ R.drawable.toggle_on_idle_magenta, R.drawable.toggle_on_blink_magenta, R.drawable.toggle_on_anim_magenta, R.drawable.toggle_off_anim_magenta });
        EYE_COLOR_DRAWABLES = Collections.unmodifiableMap(m);
    }

    // ─── Views ───────────────────────────────────────────────────────────────

    private ImageView ivAvatar;
    private LinearLayout zzzGroup;
    /** Small "ON"/"OFF" label — digital-clock style, bottom-end overlay. */
    private TextView statusLabel;

    // ─── Eye color tint ──────────────────────────────────────────────────────

    /** 0 = white/no tint (default). Any other ARGB int is applied via SRC_IN on the eye overlay. */
    private int eyeColor = 0;

    // ─── State ───────────────────────────────────────────────────────────────

    private boolean checked = false;

    @Nullable
    private CompoundButton.OnCheckedChangeListener listener;

    // ─── Animation ───────────────────────────────────────────────────────────

    private final Handler handler          = new Handler(Looper.getMainLooper());
    private final Random  blinkRandom      = new Random();
    @Nullable private Runnable       blinkRunnable;
    @Nullable private ValueAnimator  breathingAnimator;
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

        // ── ON/OFF status label — digital-clock style, bottom-end corner. ──────
        statusLabel = new TextView(context);
        statusLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        statusLabel.setTextSize(7f);
        statusLabel.setLetterSpacing(0.1f);
        statusLabel.setIncludeFontPadding(false);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        statusParams.gravity = Gravity.BOTTOM | Gravity.END;
        statusParams.bottomMargin = dpPx(context, -1);
        statusParams.setMarginEnd(dpPx(context, -2));
        statusLabel.setLayoutParams(statusParams);
        statusLabel.setVisibility(View.GONE);
        addView(statusLabel);

        // ── Eye color tint — read from SharedPreferences. ──────────────────────
        try {
            SharedPreferences prefs = context.getSharedPreferences(
                    Constants.PREFS_NAME, Context.MODE_PRIVATE);
            int saved = prefs.getInt(Constants.PREF_AVATAR_EYE_COLOR,
                    Constants.DEFAULT_AVATAR_EYE_COLOR);
            if (saved != 0) eyeColor = saved;
        } catch (Exception ignored) { /* defensive */ }

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

            // Animated zzZ exit or instant hide.
            if (animate && zzzGroup.getVisibility() == View.VISIBLE) {
                hideZzz();
            } else {
                stopFloatingZ();
                zzzGroup.setVisibility(View.GONE);
            }

            // Status label → "ON" (green).
            showStatusLabel("ON", 0xFF4CAF50, animate);

            if (animate) {
                startAvd(resolveDrawable(RES_WAKE), WAKE_AVD_DURATION_MS, () -> {
                    ivAvatar.setImageResource(resolveDrawable(RES_IDLE));
                    startBlink();
                });
            } else {
                ivAvatar.setImageResource(resolveDrawable(RES_IDLE));
                startBlink();
            }
        } else {
            // ── Sleep path ───────────────────────────────────────────────────
            // Status label → "OFF" (red).
            showStatusLabel("OFF", 0xFFE57373, animate);

            if (animate) {
                startAvd(resolveDrawable(RES_SLEEP), SLEEP_AVD_DURATION_MS, () -> {
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
     * Callers are responsible for managing the eye-color overlay before/after the AVD.
     */
    private void startAvd(int drawableRes, long fallbackDurationMs, Runnable onEnd) {
        // DEBUG: Log which drawable we're  trying to start
        String resName = getContext().getResources().getResourceEntryName(drawableRes);
        com.fadcam.Log.d("AvatarToggleView", "=== START AVD: " + resName + " ===");
        
        ivAvatar.setImageResource(drawableRes);
        Drawable d = ivAvatar.getDrawable();
        
        com.fadcam.Log.d("AvatarToggleView", "Drawable class: " + (d != null ? d.getClass().getName() : "NULL"));
        com.fadcam.Log.d("AvatarToggleView", "Is Animatable2: " + (d instanceof Animatable2));
        com.fadcam.Log.d("AvatarToggleView", "Is Animatable: " + (d instanceof Animatable));
        
        // Force fresh resolution in case drawable is cached
        if (d == null || !(d instanceof Animatable)) {
            com.fadcam.Log.d("AvatarToggleView", "WARNING: Not animatable! Trying ContextCompat...");
            d = androidx.core.content.ContextCompat.getDrawable(getContext(), drawableRes);
            if (d != null) {
                com.fadcam.Log.d("AvatarToggleView", "ContextCompat resolved: " + d.getClass().getName());
                ivAvatar.setImageDrawable(d);
            }
        }
        
        if (d instanceof Animatable2) {
            com.fadcam.Log.d("AvatarToggleView", "✓ Using Animatable2 path");
            Animatable2 avd = (Animatable2) d;
            avd.clearAnimationCallbacks();
            avd.registerAnimationCallback(new Animatable2.AnimationCallback() {
                @Override
                public void onAnimationEnd(Drawable drawable) {
                    com.fadcam.Log.d("AvatarToggleView", "→ Animation END callback received");
                    if (ivAvatar != null && ivAvatar.isAttachedToWindow()) {
                        onEnd.run();
                    }
                }
            });
            avd.start();
            com.fadcam.Log.d("AvatarToggleView", "→ Animation started");
        } else if (d instanceof Animatable) {
            com.fadcam.Log.d("AvatarToggleView", "❌ Using FALLBACK Animatable path (may not show)");
            ((Animatable) d).start();
            ivAvatar.postDelayed(() -> {
                com.fadcam.Log.d("AvatarToggleView", "→ Fallback delay expired");
                if (ivAvatar != null && ivAvatar.isAttachedToWindow()) {
                    onEnd.run();
                }
            }, fallbackDurationMs);
        } else {
            com.fadcam.Log.d("AvatarToggleView", "❌❌ Not animatable AT ALL! Using pure delay");
            if (fallbackDurationMs > 0) {
                ivAvatar.postDelayed(() -> {
                    com.fadcam.Log.d("AvatarToggleView", "→ Delay expired, calling onEnd");
                    if (ivAvatar != null && ivAvatar.isAttachedToWindow()) {
                        onEnd.run();
                    }
                }, fallbackDurationMs);
            } else {
                onEnd.run();
            }
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
            ivAvatar.setImageResource(resolveDrawable(RES_BLINK));
            Drawable d = ivAvatar.getDrawable();
            if (d instanceof Animatable2) {
                Animatable2 avd = (Animatable2) d;
                avd.clearAnimationCallbacks(); // prevent stale callback accumulation
                avd.registerAnimationCallback(new Animatable2.AnimationCallback() {
                    @Override public void onAnimationEnd(Drawable drawable) {
                        if (isAttachedToWindow() && checked && ivAvatar != null) {
                            ivAvatar.setImageResource(resolveDrawable(RES_IDLE));
                            scheduleNextBlink(3000 + blinkRandom.nextInt(2500));
                        }
                    }
                });
                avd.start();
            } else if (d instanceof Animatable) {
                ((Animatable) d).start();
                handler.postDelayed(() -> {
                    if (isAttachedToWindow() && checked && ivAvatar != null) {
                        ivAvatar.setImageResource(resolveDrawable(RES_IDLE));
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
        // Stop any currently-playing blink AVD and clear its callbacks so that
        // its onAnimationEnd cannot fire after we transition to the sleep state.
        if (ivAvatar != null) {
            Drawable d = ivAvatar.getDrawable();
            if (d instanceof Animatable2) {
                ((Animatable2) d).clearAnimationCallbacks();
                ((Animatable2) d).stop();
            } else if (d instanceof Animatable) {
                ((Animatable) d).stop();
            }
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

    // ─── Status label (ON / OFF) ─────────────────────────────────────────────

    /**
     * Updates the status label text and color.
     * If animate=true, plays a quick scale+fade entrance.
     * Once shown, the label stays static (no blinking/pulsing).
     */
    private void showStatusLabel(String text, int color, boolean animate) {
        statusLabel.animate().cancel();
        statusLabel.setText(text);
        statusLabel.setTextColor(color);
        if (animate) {
            statusLabel.setAlpha(0f);
            statusLabel.setScaleX(0.4f);
            statusLabel.setScaleY(0.4f);
            statusLabel.setTranslationY(dpPx(getContext(), 4));
            statusLabel.setVisibility(View.VISIBLE);
            statusLabel.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(220)
                    .setInterpolator(new OvershootInterpolator(1.2f))
                    .start();
        } else {
            statusLabel.setAlpha(1f);
            statusLabel.setScaleX(1f);
            statusLabel.setScaleY(1f);
            statusLabel.setTranslationY(0f);
            statusLabel.setVisibility(View.VISIBLE);
        }
    }

    // ─── zzZ animated exit ────────────────────────────────────────────────────

    /**
     * Reverse-staggered pop-out of the zzZ letters (largest first).
     * After animation completes, hides the group and resets children.
     */
    private void hideZzz() {
        if (zzzGroup.getVisibility() != View.VISIBLE) return;
        stopFloatingZ();
        int count = zzzGroup.getChildCount();
        for (int i = 0; i < count; i++) {
            // Reverse order: largest Z (last child) exits first.
            final View v = zzzGroup.getChildAt(count - 1 - i);
            v.animate()
                    .alpha(0f).scaleX(0.2f).scaleY(0.2f).translationY(-10f)
                    .setStartDelay(i * 70L).setDuration(150)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .start();
        }
        long totalDuration = (count - 1) * 70L + 150L;
        handler.postDelayed(() -> {
            zzzGroup.setVisibility(View.GONE);
            resetZzzChildren();
        }, totalDuration + 20);
    }

    /** Resets zzZ children to default transform for the next showZzz() call. */
    private void resetZzzChildren() {
        for (int i = 0; i < zzzGroup.getChildCount(); i++) {
            View v = zzzGroup.getChildAt(i);
            v.animate().cancel();
            v.setAlpha(1f);
            v.setScaleX(1f);
            v.setScaleY(1f);
            v.setTranslationY(0f);
        }
    }

    // ─── Eye color tint ──────────────────────────────────────────────────────

    /**
     * Sets a color tint for the avatar eyes. 0 = white/no tint (default).
     * Re-applies the current state so dedicated colored drawables take effect.
     */
    public void setEyeColor(int color) {
        this.eyeColor = color;
        applyState(checked, false);
    }

    /** Returns the current eye color tint (0 = default/white). */
    public int getEyeColor() {
        return eyeColor;
    }

    /**
     * Re-reads the eye color from SharedPreferences so the cached {@code eyeColor}
     * field is always up-to-date (e.g. after the user changes the color in Settings).
     */
    private void refreshEyeColorFromPrefs() {
        try {
            SharedPreferences prefs = getContext().getSharedPreferences(
                    Constants.PREFS_NAME, Context.MODE_PRIVATE);
            eyeColor = prefs.getInt(Constants.PREF_AVATAR_EYE_COLOR,
                    Constants.DEFAULT_AVATAR_EYE_COLOR);
        } catch (Exception ignored) { /* defensive */ }
    }

    /**
     * Returns the drawable resource ID for the given animation state,
     * choosing the color-specific variant when a custom eye color is set.
     */
    private int resolveDrawable(int resIndex) {
        refreshEyeColorFromPrefs();
        int[] res = EYE_COLOR_DRAWABLES.get(eyeColor);
        return res != null ? res[resIndex] : DEFAULT_DRAWABLES[resIndex];
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopBlink();
        stopBreathing();
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
        if (statusLabel != null) statusLabel.animate().cancel();
        if (zzzGroup != null) {
            for (int i = 0; i < zzzGroup.getChildCount(); i++) {
                zzzGroup.getChildAt(i).animate().cancel();
            }
        }
    }
}
