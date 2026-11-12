package com.fadcam.ui.animations;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;

import com.fadcam.R;

/**
 * Thin host for a {@link Scene}.
 *
 * <p>The view owns no drawing logic: it scales the scene's design space to its
 * bounds, advances the shared {@link AnimationClock}, and starts/stops cleanly
 * with window visibility. Scenes can be swapped or themed at runtime, so the
 * same component used in onboarding can be dropped into any other screen.</p>
 */
public class AnimationView extends View implements AnimationClock.FrameListener {

    public static final int TYPE_BACKGROUND = Scenes.BACKGROUND;
    public static final int TYPE_SCREEN = Scenes.SCREEN;
    public static final int TYPE_EDITOR = Scenes.EDITOR;
    public static final int TYPE_MOTION = Scenes.MOTION;
    public static final int TYPE_REMOTE = Scenes.REMOTE;
    public static final int TYPE_FLASHLIGHT = Scenes.FLASHLIGHT;
    public static final int TYPE_QR = Scenes.QR;
    public static final int TYPE_BODYCAM_SOLO = Scenes.BODYCAM;
    public static final int TYPE_DASHCAM = Scenes.DASHCAM;
    public static final int TYPE_FORENSIC = Scenes.FORENSIC;

    private static final long DEFAULT_REVEAL_MS = 4200L;
    private static final long DEFAULT_AMBIENT_PERIOD_MS = 6000L;

    private final SceneFrame frame = new SceneFrame();
    private final CanvasKit kit;
    private final boolean animationsEnabled;
    private final Rect visibleRect = new Rect();
    private final ViewTreeObserver.OnScrollChangedListener scrollListener =
            this::updateClockAttachment;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener =
            this::updateClockAttachment;

    private Scene scene;

    private boolean played;
    private boolean active = true;
    private boolean ambientEnabled;
    private boolean revealPending;
    private boolean clockAttached;
    private long revealDelayMs;
    private long revealStartMs = -1L;
    private long revealDurationMs = DEFAULT_REVEAL_MS;
    private long ambientPeriodMs = DEFAULT_AMBIENT_PERIOD_MS;
    private float ambientOffset;
    private ViewTreeObserver observer;

    public AnimationView(Context context) {
        this(context, null);
    }

    public AnimationView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AnimationView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        int type = Scenes.BACKGROUND;
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs,
                    R.styleable.AnimationView, defStyleAttr, 0);
            type = a.getInt(R.styleable.AnimationView_sceneType, Scenes.BACKGROUND);
            a.recycle();
        }

        kit = new CanvasKit(context);
        AnimationClock.get().install(context);
        scene = Scenes.create(context, type);
        animationsEnabled = animationsEnabled();
        frame.progress = animationsEnabled ? 0f : 1f;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Swaps the scene at runtime; the new scene starts in its reset state. */
    public void setScene(Scene scene) {
        this.scene = scene;
        scene.reset();
        invalidate();
    }

    /** Applies a palette/theme without rebuilding the scene. */
    public void setStyle(SceneStyle style) {
        frame.style = style != null ? style : SceneStyle.defaults();
        invalidate();
    }

    /** Overrides how long the one-shot reveal runs. */
    public void setRevealDuration(long durationMs) {
        revealDurationMs = Math.max(1L, durationMs);
    }

    /** Overrides the ambient loop length; every preview should normally share it. */
    public void setAmbientPeriod(long periodMs) {
        ambientPeriodMs = Math.max(1L, periodMs);
    }

    /** Starts the one-shot reveal. Safe to call more than once. */
    public void playOnce(long startDelayMs) {
        if (played) return;
        played = true;

        if (!animationsEnabled) {
            frame.progress = 1f;
            revealStartMs = -1L;
            revealPending = false;
            invalidate();
            return;
        }

        frame.progress = 0f;
        frame.ambient = false;
        revealDelayMs = Math.max(0L, startDelayMs);
        revealPending = true;
        updateClockAttachment();
        // The view may not be laid out yet; retry once layout has settled.
        post(this::updateClockAttachment);
        invalidate();
    }

    /** Starts the shared ambient loop once the reveal has settled. */
    public void setAmbientEnabled(boolean enabled) {
        ambientEnabled = enabled && animationsEnabled;
        frame.ambient = ambientEnabled && frame.progress >= 0.999f;
        updateClockAttachment();
        invalidate();
    }

    public void setAmbientOffset(float offset) {
        ambientOffset = offset;
    }

    /**
     * Page visibility gate. Invisible pages detach from the clock immediately,
     * so a pager holding many previews costs nothing.
     */
    public void setActive(boolean active) {
        if (this.active == active) return;
        this.active = active;
        updateClockAttachment();
    }

    /** Rewinds the demo so the next visit replays the one-shot from the start. */
    public void resetDemo() {
        played = false;
        ambientEnabled = false;
        revealPending = false;
        revealStartMs = -1L;
        frame.progress = 0f;
        frame.ambient = false;
        scene.reset();
        updateClockAttachment();
        invalidate();
    }

    /** Stops a running preview and leaves the meaningful final state. */
    public void stopDemo() {
        played = true;
        ambientEnabled = false;
        revealPending = false;
        revealStartMs = -1L;
        frame.progress = 1f;
        frame.ambient = false;
        updateClockAttachment();
        invalidate();
    }

    @Override
    public void onAnimFrame(long timeMs) {
        if (!played) return;

        if (revealPending) {
            revealStartMs = timeMs + revealDelayMs;
            revealPending = false;
        }

        float progress = revealStartMs < 0L
                ? 1f
                : (timeMs - revealStartMs) / (float) revealDurationMs;
        frame.progress = AnimationMath.clamp01(progress);
        frame.ambient = ambientEnabled && frame.progress >= 0.999f;
        float phase = (timeMs % ambientPeriodMs) / (float) ambientPeriodMs + ambientOffset;
        phase -= (float) Math.floor(phase);
        frame.phase = phase;
        frame.timeMs = timeMs;
        invalidate();

        if (frame.progress >= 1f && !ambientEnabled) {
            detachClock();
        }
    }

    private void updateClockAttachment() {
        if (shouldAnimate()) {
            if (!clockAttached) {
                AnimationClock.get().add(this);
                clockAttached = true;
            }
        } else {
            detachClock();
        }
    }

    private void detachClock() {
        if (!clockAttached) return;
        AnimationClock.get().remove(this);
        clockAttached = false;
    }

    private boolean shouldAnimate() {
        if (!played || !active || !animationsEnabled) return false;
        if (frame.progress >= 1f && !ambientEnabled) return false;
        return isAttachedToWindow()
                && getWindowVisibility() == VISIBLE
                && isShown()
                && inViewport();
    }

    /**
     * Cards scrolled out of the ScrollView viewport keep their frame but stop
     * drawing, which is the difference between 10 live previews and the 2-4
     * actually on screen.
     */
    private boolean inViewport() {
        return getLocalVisibleRect(visibleRect)
                && visibleRect.width() > 0
                && visibleRect.height() > 0;
    }

    private boolean animationsEnabled() {
        try {
            float scale = Settings.Global.getFloat(
                    getContext().getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            return scale != 0f;
        } catch (Exception ignored) {
            return true;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() == 0 || getHeight() == 0 || scene == null) return;

        float designW = scene.designWidth();
        float designH = scene.designHeight();
        float scale = Math.min(getWidth() / designW, getHeight() / designH);
        float ox = (getWidth() - designW * scale) * 0.5f;
        float oy = (getHeight() - designH * scale) * 0.5f;

        canvas.save();
        canvas.translate(ox, oy);
        canvas.scale(scale, scale);
        kit.stage(canvas, designW, designH, frame.style);
        scene.draw(canvas, frame, kit);
        canvas.restore();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        observer = getViewTreeObserver();
        observer.addOnScrollChangedListener(scrollListener);
        observer.addOnGlobalLayoutListener(layoutListener);
        updateClockAttachment();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (observer != null && observer.isAlive()) {
            observer.removeOnScrollChangedListener(scrollListener);
            observer.removeOnGlobalLayoutListener(layoutListener);
        }
        observer = null;
        detachClock();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateClockAttachment();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateClockAttachment();
    }
}
