package com.fadcam.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.core.content.ContextCompat;

import com.fadcam.R;
import com.fadcam.ui.widgets.AvatarDrawables;

/**
 * The complete launch animation, played in one full-screen overlay:
 * the dormant avatar wakes and grows → eyes close → it floats and glides into
 * the exact Home avatar slot.
 *
 * <p><b>One continuous motion.</b> There is no hold anywhere in the timeline:
 * the brightness ramp, the growth, the float while the eyes close and the
 * glide all overlap, so the avatar is never a still picture waiting for the
 * next phase. Every phase is a pure function of elapsed time, so a dropped
 * frame skips ahead exactly like a platform animator instead of stretching.</p>
 *
 * <p><b>Seamless hand-off.</b> The starting window (API &lt; 31) and the system
 * splash icon (API 31+) draw {@code splash_avatar_icon}, which is the same
 * artwork at {@code splash_avatar_start_size} and the same dimmed alpha this
 * overlay starts from. The first frame is therefore pixel-identical and the
 * motion simply continues out of it.</p>
 *
 * <p><b>Cheap to draw.</b> The avatar is a gradient vector; re-drawing it every
 * frame is what makes this stutter on low-end phones. It is rasterised once to
 * a bitmap so each frame is a single texture blit, the zzz badge is moved with
 * translations instead of layout params, and the target slot is measured once
 * instead of every frame.</p>
 */
public final class SplashAvatarOverlay {

    // ── Timeline (milliseconds, every phase overlaps the next) ───────────────
    /**
     * The avatar grows to hero size. It stops growing only after the travel has
     * already started, so there is never a frame where nothing moves.
     */
    private static final long ENTRANCE_MS = 380L;
    private static final long EXPAND_END_MS = 820L;
    /**
     * The travel starts while the growth is still finishing. One curve carries
     * the avatar all the way into the Home slot — the slow start here is what
     * makes the eye-close read as part of the same motion.
     */
    private static final long GLIDE_START_MS = 620L;
    private static final long GLIDE_END_MS = 2000L;
    /** Eyes start closing during the slow part of the travel. */
    private static final long SLEEP_START_MS = 760L;
    /** Home is revealed only once the avatar is well on its way. */
    private static final long BACKGROUND_FADE_START_MS = 1180L;
    private static final long BACKGROUND_FADE_MS = 520L;
    private static final long ALPHA_SETTLE_MS = 280L;
    /** When the scale reaches Home's breathing size. */
    private static final long SCALE_END_MS = GLIDE_END_MS;

    /**
     * TEST OVERRIDE — splash avatar only. Red eyes here while Home keeps whatever
     * the user picked. Set both to 0 to follow the user's Home eye colour again.
     */
    private static final int TEST_IDLE_DRAWABLE = R.drawable.toggle_on_idle_splashred;
    private static final int TEST_SLEEP_DRAWABLE = R.drawable.toggle_off_anim_splashred;

    /** Brightness of the dormant splash avatar; matches splash_avatar_scrim. */
    private static final float START_ALPHA = 0.85f;
    /** Alpha the real Home avatar is left at (first frame of its breathing). */
    private static final float LANDING_ALPHA = 0.62f;
    /** Home's avatar rests at scale 0.96 (first frame of its breathing). */
    private static final float HOME_REST_SCALE = 0.96f;

    private final Activity activity;
    private final HomeFragment homeFragment;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Slow start, decelerate into hero size.
    private final PathInterpolator expandCurve = new PathInterpolator(0.3f, 0f, 0.1f, 1f);
    // One travel curve: eases out of the growth, then settles into the slot.
    private final PathInterpolator glideCurve = new PathInterpolator(0.3f, 0f, 0.14f, 1f);
    private final PathInterpolator settleCurve = new PathInterpolator(0.32f, 0f, 0.16f, 1f);

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!showing || !motionRunning) return;
            if (startNanos == 0L) {
                startNanos = frameTimeNanos;
                com.fadcam.FLog.d("SplashAvatar", "firstFrame +"
                        + (android.os.SystemClock.uptimeMillis() - attachAtMs) + "ms");
            }
            applyFrame((frameTimeNanos - startNanos) / 1_000_000L);
            if (finished) {
                motionRunning = false;
                applyFinalPose();
                land();
            } else {
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    };

    private FrameLayout layer;
    private View homeContainer;
    private View scrim;
    private View avatarRoot;
    private ImageView avatarImage;
    private View zzzGroup;

    private View target;
    private View homeZzzBadge;
    private boolean zzzAnchorsMeasured;
    /** Pixels from the avatar box's END edge inward to Home's badge END edge. */
    private float zzzInsetEnd;
    /** Pixels from the avatar box's top down to Home's badge top. */
    private float zzzInsetTop;
    private float centerX;
    private float centerY;
    private float layerOriginX;
    private float layerOriginY;
    private float baseTx;
    private float baseTy;
    private float heroPx;
    private float startScale;
    private float finalScale;

    private long attachAtMs;
    private long startNanos;
    private Runnable onLanded;
    private boolean motionRunning;
    private boolean motionArmed;
    private boolean splashIconAdopted;
    private boolean sleepStarted;
    private boolean sleepFinished;
    private boolean zzzShown;
    private float zzzPop = 1f;
    private boolean showing;
    private boolean landed;
    private boolean overlayRemoved;
    private boolean finished;

    /** Attached before the system splash is dismissed; it owns the only avatar. */
    public SplashAvatarOverlay(Activity activity, HomeFragment homeFragment) {
        this.activity = activity;
        this.homeFragment = homeFragment;
    }

    /** Adds the overlay and starts the motion immediately. */
    public void start() {
        if (showing || activity.isFinishing() || activity.isDestroyed()) {
            removeSplashWindow();
            return;
        }
        attachAtMs = android.os.SystemClock.uptimeMillis();
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) {
            removeSplashWindow();
            return;
        }
        heroPx = activity.getResources().getDimension(R.dimen.splash_avatar_hero_size);
        float startPx = activity.getResources().getDimension(R.dimen.splash_avatar_start_size);
        if (heroPx <= 0f || startPx <= 0f) {
            removeSplashWindow();
            return;
        }
        // Derived from resources so the first frame is always exactly the size the
        // starting window / splash icon drew.
        startScale = startPx / heroPx;

        layer = new FrameLayout(activity);
        layer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Flat black scrim as its own view: changing a view's alpha is free,
        // whereas animating a background drawable's alpha invalidates the whole
        // layer every frame.
        scrim = new View(activity);
        // Never animate this with View.setAlpha(): a full-screen view with alpha
        // below 1 makes the framework allocate an offscreen saveLayer for the
        // whole window (measured 180ms per frame on a low-end GPU). The colour
        // drawable can fade itself with no layer at all.
        scrim.setBackgroundColor(Color.BLACK);
        layer.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        content.addView(layer);

        // The overlay is fully opaque at this point, so Home underneath is never
        // seen. Hiding it skips its entire draw (and its infinite decorations)
        // for the whole expand/sleep phase — on a low-end phone that is the
        // difference between 60fps and a stutter, and it costs nothing visually.
        homeContainer = activity.findViewById(R.id.fragment_container);

        avatarRoot = LayoutInflater.from(activity)
                .inflate(R.layout.view_splash_avatar, layer, false);
        // Gravity.CENTER is direction-independent, so the avatar is truly centred
        // in LTR and RTL. Its translation is used for the motion.
        layer.addView(avatarRoot, new FrameLayout.LayoutParams(
                Math.round(heroPx), Math.round(heroPx), Gravity.CENTER));

        avatarImage = avatarRoot.findViewById(R.id.splash_avatar_image);
        if (avatarImage != null) {
            int idleRes = TEST_IDLE_DRAWABLE != 0
                    ? TEST_IDLE_DRAWABLE
                    : AvatarDrawables.resolve(activity, AvatarDrawables.RES_IDLE);
            avatarImage.setImageDrawable(rasterise(idleRes));
        }

        avatarRoot.setScaleX(startScale);
        avatarRoot.setScaleY(startScale);
        setAvatarAlpha(START_ALPHA);

        showing = true;
        layer.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        android.view.ViewTreeObserver observer = layer.getViewTreeObserver();
                        if (observer.isAlive()) {
                            observer.removeOnPreDrawListener(this);
                        }
                        resolveGeometry();
                        com.fadcam.FLog.d("SplashAvatar", "firstDraw +"
                                + (android.os.SystemClock.uptimeMillis() - attachAtMs)
                                + "ms " + layer.getWidth() + "x" + layer.getHeight());
                        // Do not start the clock yet: the splash is still on screen
                        // and it is about to report the exact icon size we must
                        // start from. Starting now would animate a picture nobody
                        // can see and then cut to a different size.
                        motionArmed = true;
                        if (splashIconAdopted) {
                            beginMotion();
                        } else {
                            handler.postDelayed(SplashAvatarOverlay.this::beginMotion, 260L);
                        }
                        return true;
                    }
                });
    }

    /**
     * Adopts the size the system splash actually rendered.
     *
     * <p>The platform scales the splash icon into its own canvas, so a drawable
     * declared at 74dp can appear at a completely different size. Guessing that
     * factor is what produced a visible size jump on the hand-off. Instead we
     * render the very same drawable into the very same canvas and measure what
     * the user is actually looking at, then start the animation from exactly
     * that size — on every API level and every OEM.</p>
     */
    public void adoptSplashIcon(Drawable icon, int canvasPx) {
        if (icon == null || canvasPx <= 0 || heroPx <= 0) return;
        // Only meaningful before the first animated frame is produced.
        if (startNanos != 0L || sleepStarted) return;
        splashIconAdopted = true;
        try {
            Drawable copy = icon.getConstantState() != null
                    ? icon.getConstantState().newDrawable(activity.getResources()).mutate()
                    : icon;
            Bitmap bitmap = Bitmap.createBitmap(canvasPx, canvasPx, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            copy.setBounds(0, 0, canvasPx, canvasPx);
            copy.draw(canvas);

            int[] pixels = new int[canvasPx * canvasPx];
            bitmap.getPixels(pixels, 0, canvasPx, 0, 0, canvasPx, canvasPx);
            int minX = canvasPx, maxX = -1, minY = canvasPx, maxY = -1;
            for (int y = 0; y < canvasPx; y += 2) {
                int row = y * canvasPx;
                for (int x = 0; x < canvasPx; x += 2) {
                    if ((pixels[row + x] >>> 24) > 6) {
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
            }
            bitmap.recycle();
            if (maxX <= minX || maxY <= minY) return;
            float rendered = Math.max(maxX - minX + 1, maxY - minY + 1);
            startScale = rendered / heroPx;
            avatarRoot.setScaleX(startScale);
            avatarRoot.setScaleY(startScale);
            com.fadcam.FLog.d("SplashAvatar", "adopted splash icon "
                    + Math.round(rendered) + "px -> startScale " + startScale);
        } catch (Exception e) {
            com.fadcam.FLog.d("SplashAvatar", "adoptSplashIcon failed: " + e);
        }
        beginMotion();
    }

    public boolean isShowing() {
        return showing && !landed;
    }

    /** Invoked once the avatar has landed on Home and the overlay is gone. */
    public void setOnLanded(Runnable action) {
        onLanded = action;
    }

    /** Tap / back: snap to the settled Home pose and finish. */
    public void skip() {
        if (!showing || landed) return;
        cancelMotion();
        acquireTargetIfNeeded();
        applyFinalPose();
        land();
    }

    /** Cancel and restore Home if the activity is leaving mid-splash. */
    public void cancel() {
        if (!showing) return;
        cancelMotion();
        showing = false;
        landed = true;
        if (homeFragment != null && homeFragment.isAdded()) {
            homeFragment.cancelSplashTransition();
        }
        removeSplashWindow();
    }

    /**
     * The starting window centres the avatar on the whole window, while this
     * overlay lives in the content view. Align the overlay to the window centre
     * so the first frame lands on exactly the same pixels the splash left.
     */
    private void resolveGeometry() {
        int[] decorLocation = new int[2];
        View decor = activity.getWindow().getDecorView();
        decor.getLocationOnScreen(decorLocation);
        float windowCx = decorLocation[0] + decor.getWidth() / 2f;
        float windowCy = decorLocation[1] + decor.getHeight() / 2f;

        int[] layerLocation = new int[2];
        layer.getLocationOnScreen(layerLocation);
        float layoutCx = layerLocation[0] + layer.getWidth() / 2f;
        float layoutCy = layerLocation[1] + layer.getHeight() / 2f;

        baseTx = windowCx - layoutCx;
        baseTy = windowCy - layoutCy;
        layerOriginX = layerLocation[0];
        layerOriginY = layerLocation[1];
        centerX = layoutCx + baseTx;
        centerY = layoutCy + baseTy;

        avatarRoot.setTranslationX(baseTx);
        avatarRoot.setTranslationY(baseTy);
    }

    // ── Timeline ────────────────────────────────────────────────────────────

    /** Starts the clock once the splash has handed over its exact size. */
    private void beginMotion() {
        if (motionRunning || !showing || avatarRoot == null) return;
        motionRunning = true;
        startNanos = 0L;
        handler.removeCallbacksAndMessages(null);
        com.fadcam.FLog.d("SplashAvatar", "motion +"
                + (android.os.SystemClock.uptimeMillis() - attachAtMs)
                + "ms startScale=" + startScale);
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void applyFrame(long elapsedMs) {
        if (avatarRoot == null) return;
        finished = false;
        startSleepIfDue(elapsedMs);

        // Scale: grow to hero, then shrink into the slot. The shrink is keyed
        // from EXPAND_END so the hand-over has no size jump.
        float scale;
        if (elapsedMs < EXPAND_END_MS) {
            float growth = expandCurve.getInterpolation(clamp01(elapsedMs / (float) EXPAND_END_MS));
            scale = startScale + (1f - startScale) * growth;
        } else {
            float scaleP = clamp01((elapsedMs - EXPAND_END_MS)
                    / (float) (SCALE_END_MS - EXPAND_END_MS));
            scale = 1f + (finalScale - 1f) * settleCurve.getInterpolation(scaleP);
        }
        avatarRoot.setScaleX(scale);
        avatarRoot.setScaleY(scale);

        // Brightness: the dormant splash avatar wakes up over the first moments.
        setAvatarAlpha(START_ALPHA + (1f - START_ALPHA)
                * expandCurve.getInterpolation(clamp01(elapsedMs / (float) ENTRANCE_MS)));

        // Position: a single curve from the centre to the Home slot. It is the
        // same curve for every frame, so the travel can never stall mid-way.
        float travelP = clamp01((elapsedMs - GLIDE_START_MS)
                / (float) (GLIDE_END_MS - GLIDE_START_MS));
        float travelled = glideCurve.getInterpolation(travelP);

        if (target == null) {
            acquireTargetIfNeeded();
        }
        if (target == null) {
            // Home is not ready yet — keep the avatar centred and growing rather
            // than freezing it in a half-travelled pose.
            avatarRoot.setTranslationX(baseTx);
            avatarRoot.setTranslationY(baseTy);
            setBackgroundAlpha(1f);
            return;
        }

        avatarRoot.setTranslationX(baseTx + (targetCx - centerX) * travelled);
        avatarRoot.setTranslationY(baseTy + (targetCy - centerY) * travelled);

        setBackgroundAlpha(1f - clamp01((elapsedMs - BACKGROUND_FADE_START_MS)
                / (float) BACKGROUND_FADE_MS));

        // Settle to Home's breathing start alpha (0.62).
        setAvatarAlpha(1f - (1f - LANDING_ALPHA) * clamp01(
                (elapsedMs - (GLIDE_END_MS - ALPHA_SETTLE_MS)) / (float) ALPHA_SETTLE_MS));

        if (zzzShown) {
            positionZzz();
        }
        if (elapsedMs >= GLIDE_END_MS) {
            finished = true;
        }
    }

    private void startSleepIfDue(long elapsedMs) {
        if (sleepStarted || elapsedMs < SLEEP_START_MS) return;
        sleepStarted = true;
        playSleepAnimation();
    }

    private float targetCx;
    private float targetCy;

    /**
     * Hides the real Home avatar and captures its bounds. Called only when the
     * glide is about to start, so Home has had the whole entrance to lay out.
     */
    private void acquireTargetIfNeeded() {
        if (target != null || homeFragment == null || !homeFragment.isAdded()) return;
        View candidate = homeFragment.beginSplashTransition();
        if (candidate == null) return;
        target = candidate;
        finalScale = (candidate.getWidth() * HOME_REST_SCALE) / heroPx;
        measureTarget();
        homeZzzBadge = homeFragment.getSplashHandoffZzzBadge();
    }

    /** Measured on demand, never every frame — keeps the glide allocation-free. */
    private void measureTarget() {
        if (target == null) return;
        int[] location = new int[2];
        target.getLocationOnScreen(location);
        targetCx = location[0] + target.getWidth() / 2f;
        targetCy = location[1] + target.getHeight() / 2f;
    }

    /**
     * Fades the avatar through its drawable. Fading the view instead would wrap
     * the avatar in an offscreen layer every frame.
     */
    private void setAvatarAlpha(float alpha) {
        if (avatarRoot == null) return;
        if (avatarRoot.getAlpha() != 1f) avatarRoot.setAlpha(1f);
        if (avatarImage != null) {
            Drawable d = avatarImage.getDrawable();
            if (d != null) d.setAlpha(Math.round(255f * clamp01(alpha)));
        }
    }

    /** Skips Home's draw while the scrim hides it. */
    private void setHomeDrawn(boolean drawn) {
        if (homeContainer == null) return;
        int wanted = drawn ? View.VISIBLE : View.INVISIBLE;
        if (homeContainer.getVisibility() != wanted) {
            homeContainer.setVisibility(wanted);
        }
    }

    private void setBackgroundAlpha(float alpha) {
        if (scrim == null) return;
        Drawable bg = scrim.getBackground();
        // 254 instead of 255 while "opaque": visually identical on black, but it
        // keeps the scrim on the alpha-blend path from the very first frame. That
        // makes the GPU compile its blend shaders while the system splash still
        // covers the overlay, instead of hitching on the first crossfade frame.
        int a = alpha >= 1f ? 254 : Math.round(254f * clamp01(alpha));
        if (bg instanceof android.graphics.drawable.ColorDrawable) {
            ((android.graphics.drawable.ColorDrawable) bg).setAlpha(a);
        } else if (bg != null) {
            bg.setAlpha(a);
        }
    }

    /** Plays the same sleep AnimatedVectorDrawable Home uses. */
    private void playSleepAnimation() {
        if (avatarImage == null) return;
        avatarImage.setImageResource(TEST_SLEEP_DRAWABLE != 0
                ? TEST_SLEEP_DRAWABLE
                : AvatarDrawables.resolve(activity, AvatarDrawables.RES_SLEEP));
        Drawable drawable = avatarImage.getDrawable();
        Runnable finishedRunnable = this::onSleepFinished;
        if (drawable instanceof Animatable2) {
            Animatable2 animatable = (Animatable2) drawable;
            animatable.clearAnimationCallbacks();
            animatable.registerAnimationCallback(new Animatable2.AnimationCallback() {
                @Override
                public void onAnimationEnd(Drawable finishedDrawable) {
                    handler.post(finishedRunnable);
                }
            });
            animatable.start();
        } else if (drawable instanceof Animatable) {
            ((Animatable) drawable).start();
            handler.postDelayed(finishedRunnable, 460L);
        } else {
            handler.post(finishedRunnable);
        }
        // Fail-safe for OEM drawables that never report the end callback.
        handler.postDelayed(finishedRunnable, 700L);
    }

    private void onSleepFinished() {
        if (sleepFinished) return;
        sleepFinished = true;
        if (avatarImage != null) {
            // Swap the animated vector for a one-off raster so the glide never
            // re-draws vector paths on the GPU frame by frame.
            avatarImage.setImageDrawable(rasterise(AvatarDrawables.SLEEPING_STATIC));
        }
        showZzz();
    }

    private void showZzz() {
        if (!showing || zzzShown || layer == null) return;
        zzzShown = true;

        zzzGroup = LayoutInflater.from(activity)
                .inflate(R.layout.view_splash_zzz, layer, false);
        zzzGroup.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        // Absolute TOP|LEFT anchoring: Gravity.START would resolve to the wrong
        // edge in RTL, so the direction is handled explicitly in positionZzz().
        layer.addView(zzzGroup, new FrameLayout.LayoutParams(
                zzzGroup.getMeasuredWidth(), zzzGroup.getMeasuredHeight(),
                Gravity.TOP | Gravity.LEFT));
        zzzGroup.setVisibility(View.VISIBLE);
        zzzGroup.setAlpha(0f);
        positionZzz();
        zzzGroup.animate().alpha(1f).setDuration(200L).start();
        // Pop as a multiplier on badgeScale, so the per-frame re-anchoring of
        // the badge to the avatar cannot fight the entrance animation.
        android.animation.ValueAnimator pop =
                android.animation.ValueAnimator.ofFloat(0.55f, 1f);
        pop.setDuration(260L);
        pop.setInterpolator(new OvershootInterpolator(2f));
        pop.addUpdateListener(a -> {
            zzzPop = (Float) a.getAnimatedValue();
            positionZzz();
        });
        pop.start();
    }

    /**
     * Pins the zzz badge to the avatar's top END corner (right in LTR, left in
     * RTL, matching Home's {@code layout_gravity="top|end"}) and keeps it there
     * every frame. The badge scales with the avatar so it stays proportionally
     * attached while the avatar is hero-sized, then lands at exactly Home's
     * natural size and position.
     *
     * <p>Uses translations, not layout params: {@code setLayoutParams} requests
     * a layout pass and was measurably stuttering the glide on low-end phones.</p>
     */
    private void positionZzz() {
        if (zzzGroup == null || avatarRoot == null || layer == null) return;
        boolean rtl = layer.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        measureZzzAnchorIfNeeded(rtl);

        float avatarScale = avatarRoot.getScaleX();
        // Home lays its zzz out against the avatar's *layout* box, not against the
        // 0.96 breathing scale it is drawn at. Anchoring to the drawn box put our
        // badge a few px inside Home's, which read as a jump at the hand-off.
        float boxScale = avatarScale / HOME_REST_SCALE;
        float avatarWidth = avatarRoot.getWidth() * boxScale;
        float avatarLeft = centerX + avatarRoot.getTranslationX() - avatarWidth / 2f;
        float avatarTop = centerY + avatarRoot.getTranslationY()
                - avatarRoot.getHeight() * boxScale / 2f;

        // Natural badge size represents Home's resting size, so the badge is
        // exactly right at the landing frame.
        float badgeScale = finalScale > 0f ? avatarScale / finalScale : 1f;
        badgeScale = Math.max(1f, Math.min(3f, badgeScale)) * zzzPop;
        zzzGroup.setScaleX(badgeScale);
        zzzGroup.setScaleY(badgeScale);

        int badgeWidth = zzzGroup.getWidth() > 0
                ? zzzGroup.getWidth() : zzzGroup.getMeasuredWidth();
        float left;
        if (rtl) {
            // End = left. Pivot on the left edge so growth extends rightwards,
            // matching Home's mirrored layout.
            zzzGroup.setPivotX(0f);
            zzzGroup.setPivotY(0f);
            left = avatarLeft + endInset();
        } else {
            // End = right. Pivot on the right edge so growth extends leftwards.
            zzzGroup.setPivotX(badgeWidth);
            zzzGroup.setPivotY(0f);
            left = avatarLeft + avatarWidth - badgeWidth - endInset();
        }
        // The badge is laid out at (0,0) of the overlay, so its visual position
        // is its translation from the layer origin (cached — never measured
        // again inside the frame loop).
        zzzGroup.setX(left - layerOriginX);
        zzzGroup.setY(avatarTop + topInset() - layerOriginY);
    }

    private float endInset() {
        return zzzAnchorsMeasured ? zzzInsetEnd : dp(4);
    }

    private float topInset() {
        return zzzAnchorsMeasured ? zzzInsetTop : 0f;
    }

    /**
     * Reads where Home actually puts its zzz badge. Home anchors it with
     * {@code layout_gravity="top|end"} plus a margin inside a wrap_content
     * container, so a computed guess is always a few dp off and the badge
     * visibly jumps at the hand-off. Measuring removes the guess.
     */
    private void measureZzzAnchorIfNeeded(boolean rtl) {
        if (zzzAnchorsMeasured || homeZzzBadge == null || target == null) return;
        if (homeZzzBadge.getWidth() <= 0 || target.getWidth() <= 0) return;
        try {
            int[] avatar = new int[2];
            int[] badge = new int[2];
            target.getLocationOnScreen(avatar);
            homeZzzBadge.getLocationOnScreen(badge);
            float avatarLeft = avatar[0];
            float avatarRight = avatar[0] + target.getWidth();
            if (rtl) {
                zzzInsetEnd = badge[0] - avatarLeft;
            } else {
                zzzInsetEnd = avatarRight - (badge[0] + homeZzzBadge.getWidth());
            }
            zzzInsetTop = badge[1] - avatar[1];
            zzzAnchorsMeasured = true;
            com.fadcam.FLog.d("SplashAvatar", "zzz anchor end=" + zzzInsetEnd
                    + " top=" + zzzInsetTop + " homeW=" + homeZzzBadge.getWidth()
                    + " homeH=" + homeZzzBadge.getHeight()
                    + " overlayW=" + zzzGroup.getWidth());
        } catch (Exception e) {
            com.fadcam.FLog.d("SplashAvatar", "zzz anchor failed: " + e);
        }
    }

    private void applyFinalPose() {
        if (avatarRoot == null) return;
        acquireTargetIfNeeded();
        if (!sleepFinished) {
            sleepFinished = true;
            if (avatarImage != null) {
                avatarImage.setImageDrawable(rasterise(AvatarDrawables.SLEEPING_STATIC));
            }
        }
        // Re-measure right before the swap: Home settles while the splash runs,
        // so a value captured earlier would visibly drift.
        measureTarget();
        avatarRoot.setScaleX(finalScale);
        avatarRoot.setScaleY(finalScale);
        avatarRoot.setTranslationX(baseTx + (targetCx - centerX));
        avatarRoot.setTranslationY(baseTy + (targetCy - centerY));
        setAvatarAlpha(LANDING_ALPHA);
        setBackgroundAlpha(0f);
        if (zzzGroup != null) {
            zzzGroup.setAlpha(1f);
            zzzGroup.setScaleX(1f);
            zzzGroup.setScaleY(1f);
            positionZzz();
        }
    }

    private void land() {
        if (landed) return;
        landed = true;
        handler.removeCallbacksAndMessages(null);
        cancelMotion();
        if (homeFragment != null && homeFragment.isAdded()) {
            homeFragment.finishSplashTransition();
        }
        showing = false;
        setHomeDrawn(true);
        removeSplashWindow();
        Runnable action = onLanded;
        onLanded = null;
        if (action != null) action.run();
    }

    private void removeSplashWindow() {
        if (overlayRemoved) return;
        overlayRemoved = true;
        if (layer != null) {
            android.view.ViewParent parent = layer.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(layer);
            }
            layer = null;
        }
    }

    private void cancelMotion() {
        motionRunning = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        handler.removeCallbacksAndMessages(null);
    }

    /**
     * Draws a vector avatar once into a bitmap. Re-drawing the gradient vector
     * on every frame is what made the animation stutter on low-end hardware;
     * a bitmap turns each frame into a single texture blit.
     */
    private Drawable rasterise(int resId) {
        Drawable source = ContextCompat.getDrawable(activity, resId);
        if (source == null) return null;
        int size = Math.max(1, Math.round(heroPx));
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        source.setBounds(0, 0, size, size);
        source.draw(canvas);
        BitmapDrawable out = new BitmapDrawable(activity.getResources(), bitmap);
        out.setFilterBitmap(true);
        out.setAntiAlias(true);
        return out;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private float dp(float value) {
        return value * activity.getResources().getDisplayMetrics().density;
    }
}
