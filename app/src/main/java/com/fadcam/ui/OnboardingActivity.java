package com.fadcam.ui;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.os.Bundle;
import androidx.annotation.Nullable;
import android.view.View;
import androidx.annotation.NonNull;
import android.os.Handler;
import android.widget.TextView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;

import com.fadcam.MainActivity;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.utils.RuntimeCompat;
import com.github.appintro.AppIntro;
import com.github.appintro.AppIntroFragment;
import com.fadcam.R;
import androidx.core.content.ContextCompat;
import android.content.Intent;
import android.content.SharedPreferences;
import com.github.appintro.AppIntroCustomLayoutFragment;
import android.animation.ValueAnimator;
import com.airbnb.lottie.LottieAnimationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager.widget.ViewPager;
import android.view.ViewGroup;
import android.text.TextUtils;
import android.graphics.Typeface;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.core.content.res.ResourcesCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * OnboardingActivity shows the app intro slides using AppIntro.
 */
public class OnboardingActivity extends AppIntro
        implements OnboardingCapabilitiesFragment.Host {
    private boolean isLastSlide = false;
    private boolean isFirstSlide = true; // Track if we're on the first slide
    // Use View instead of Button to avoid class cast exceptions
    private View backButton;
    private View nextButton;
    private ImageView dockBack;
    private ImageView dockNext;
    private ImageView dockDone;
    private View dockScrollHint;
    private SquircleIndicatorController dockIndicator;
    private boolean capabilitiesNextRevealed;
    private boolean scrollHintVisible;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // Force dark mode for onboarding regardless of system theme
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getDelegate().setLocalNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES);
        }
        super.onCreate(savedInstanceState);

        // Edge to edge, but keep every control inside the status bar, camera cutout and
        // navigation bar. AppIntro puts its arrows at the window edges, so the whole
        // content root gets the inset padding once.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        // Some OEMs ignore a transparent nav bar and paint a white scrim. Black blends with
        // the briefing background instead.
        getWindow().setNavigationBarColor(android.graphics.Color.BLACK);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            getWindow().setNavigationBarDividerColor(android.graphics.Color.TRANSPARENT);
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            // Without this the system paints a contrast scrim over the transparent nav bar.
            getWindow().setNavigationBarContrastEnforced(false);
            getWindow().setStatusBarContrastEnforced(false);
        }
        androidx.core.view.WindowInsetsControllerCompat barsController =
                androidx.core.view.WindowCompat.getInsetsController(
                        getWindow(), getWindow().getDecorView());
        barsController.setAppearanceLightNavigationBars(false);
        barsController.setAppearanceLightStatusBars(false);
        // Full-screen briefing: bars stay hidden and only peek back as transient overlays
        // when the user swipes from an edge. This also removes the OEM's white nav scrim.
        hideSystemBars();
        View windowContent = findViewById(android.R.id.content);
        windowContent.setBackgroundColor(android.graphics.Color.BLACK);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(windowContent, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                            | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            // The dossier grid stays full-bleed behind the status bar and cutout. Only the
            // bottom controls need window padding; each slide pads its own header.
            v.setPadding(0, 0, 0, bars.bottom);
            return insets;
        });

        // Make sure we don't have duplicate slides when changing language
        // Clear any existing slides by removing them all first.
        try {
            // AppIntro 6.x stores slides in a private `fragments` list on its
            // AppIntroBase parent class. getDeclaredField only searches the
            // declared class, so walk the superclass chain until we find it.
            java.lang.reflect.Field slidesField = null;
            Class<?> c = com.github.appintro.AppIntro.class;
            while (c != null && slidesField == null) {
                try {
                    slidesField = c.getDeclaredField("fragments");
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
            if (slidesField != null) {
                slidesField.setAccessible(true);
                java.util.List<?> slides = (java.util.List<?>) slidesField.get(this);
                if (slides != null) {
                    slides.clear();
                }
            }
        } catch (Exception e) {
            // If reflection fails, log and continue
            FLog.e("OnboardingActivity", "Could not clear slides: " + e.getMessage());
        }

        // Add slides in the correct sequence
        addSlide(AppIntroCustomLayoutFragment.newInstance(R.layout.onboarding_intro_slide));
        addSlide(new OnboardingCapabilitiesFragment());
        addSlide(new OnboardingPermissionsFragment());
        addSlide(new OnboardingHumanFragment());

        // Hide only the Done button while keeping navigation buttons
        setSkipButtonEnabled(false); // No skip button needed
        setSwipeLock(false); // (setNextPageSwipeLock is deprecated in AppIntro 6.x)
        setIndicatorEnabled(false);

        // Use wizard mode to replace Skip with Back button
        setWizardMode(true);

        ViewPager navPager = findViewById(com.github.appintro.R.id.view_pager);
        if (navPager != null) {
            navPager.post(() -> updateNavigationButtons(navPager.getCurrentItem()));
        }

        // Disable color transitions as our slides don't implement
        // SlideBackgroundColorHolder
        setColorTransitionsEnabled(false);

        // Set fade transition effect between slides
        ViewPager viewPager = findViewById(com.github.appintro.R.id.view_pager);
        if (viewPager != null) {
            // ViewPager2 would use viewPager.setUserInputEnabled(true);
            viewPager.setEnabled(true); // Ensure the ViewPager is enabled

            // Set page transformer
            viewPager.setPageTransformer(true, new FadePageTransformer());

            // Listen for page changes to update navigation buttons
            viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
                @Override
                public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                }

                @Override
                public void onPageSelected(int position) {
                    updateNavigationButtons(position);
                    viewPager.post(() -> notifyCapabilitiesPage(
                            viewPager.getCurrentItem() == 1));
                    viewPager.postDelayed(() -> notifyCapabilitiesPage(
                            viewPager.getCurrentItem() == 1), 220L);
                    viewPager.post(() -> {
                        if (viewPager.getCurrentItem() == 2) notifyPermissionsPage();
                    });
                }

                @Override
                public void onPageScrollStateChanged(int state) {
                }
            });

        }

        // AppIntro's bottom bar is replaced by a floating dossier dock.
        backButton = findViewById(com.github.appintro.R.id.back);
        nextButton = findViewById(com.github.appintro.R.id.next);
        hideNativeBottomChrome();
        setupFloatingDock();

        // Completely remove the Done button
        View doneButton = findViewById(com.github.appintro.R.id.done);
        if (doneButton != null) {
            // First make it invisible
            doneButton.setVisibility(View.GONE);

            // Zero out its dimensions
            ViewGroup.LayoutParams params = doneButton.getLayoutParams();
            if (params != null) {
                params.width = 0;
                params.height = 0;
                doneButton.setLayoutParams(params);
            }

            // Also try to remove it from its parent
            ViewGroup parent = (ViewGroup) doneButton.getParent();
            if (parent != null) {
                parent.removeView(doneButton);
            }
        }

        // Update navigation buttons initially
        updateNavigationButtons(0);

        getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentResumed(
                            @NonNull androidx.fragment.app.FragmentManager fm,
                            @NonNull Fragment f) {
                        super.onFragmentResumed(fm, f);
                        if (!(f instanceof OnboardingCapabilitiesFragment)) return;
                        ViewPager pager = findViewById(com.github.appintro.R.id.view_pager);
                        if (pager != null && pager.getCurrentItem() == 1) {
                            pager.post(((OnboardingCapabilitiesFragment) f)::onPageBecameVisible);
                        }
                    }

                    @Override
                    public void onFragmentViewCreated(@NonNull androidx.fragment.app.FragmentManager fm,
                            @NonNull Fragment f, @Nullable View v, @Nullable Bundle savedInstanceState) {
                        super.onFragmentViewCreated(fm, f, v, savedInstanceState);
                        if (v == null) return;
                        final View slideContent = v.findViewById(R.id.onboarding_content);
                        if (slideContent != null) {
                            final int baseTop = slideContent.getPaddingTop();
                            final int baseLeft = slideContent.getPaddingLeft();
                            final int baseRight = slideContent.getPaddingRight();
                            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
                                    slideContent, (cv, insets) -> {
                                        androidx.core.graphics.Insets bars = insets.getInsets(
                                                androidx.core.view.WindowInsetsCompat.Type.systemBars()
                                                        | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
                                        cv.setPadding(baseLeft + bars.left, baseTop + bars.top,
                                                baseRight + bars.right, cv.getPaddingBottom());
                                        return insets;
                                    });
                            androidx.core.view.ViewCompat.requestApplyInsets(slideContent);
                        }
                        final TextView heroTitle = v.findViewById(R.id.onboardingHeroTitle);
                        if (heroTitle != null) {
                            String heroText = heroTitle.getText().toString();
                            int marker = heroText.indexOf("#1");
                            if (marker >= 0) {
                                android.text.SpannableString styled =
                                        new android.text.SpannableString(heroText);
                                styled.setSpan(new android.text.style.ForegroundColorSpan(
                                                androidx.core.content.ContextCompat.getColor(
                                                        OnboardingActivity.this, R.color.pro_gold)),
                                        marker, marker + 2,
                                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                                heroTitle.setText(styled);
                            }
                        }
                        final TextView swipeHint = v.findViewById(R.id.tvSwipeInstruction);
                        if (swipeHint != null) {
                            swipeHint.post(() -> startShimmerEffect(swipeHint));
                        }
                        final android.widget.ImageView ivOnboardingAvatar = v.findViewById(R.id.ivOnboardingAvatar);
                        final TextView tvHiGreeting = v.findViewById(R.id.tvHiGreeting);
                        if (ivOnboardingAvatar != null) {
                            // ===== SLEEP STATE =====
                            ivOnboardingAvatar.setImageResource(R.drawable.toggle_off);
                            final Handler handler = new Handler(android.os.Looper.getMainLooper());
                            final boolean[] wakeDone = {false};
                            final boolean[] blinkLoopActive = {false};
                            final android.widget.TextView tvZ1 = v.findViewById(R.id.tvOnboardZ1);
                            final android.widget.TextView tvZ2 = v.findViewById(R.id.tvOnboardZ2);
                            final android.widget.TextView tvZ3 = v.findViewById(R.id.tvOnboardZ3);
                            // ZzZ float-up-and-fade loop, staggered per letter
                            if (tvZ1 != null && tvZ2 != null && tvZ3 != null) {
                                final Runnable[] z1r = {null}, z2r = {null}, z3r = {null};
                                z1r[0] = () -> { if (wakeDone[0]) return; tvZ1.setTranslationY(0f); tvZ1.setAlpha(0.85f); tvZ1.animate().translationY(-20f).alpha(0f).setDuration(1100).withEndAction(() -> { if (!wakeDone[0]) handler.postDelayed(z1r[0], 120); }).start(); };
                                z2r[0] = () -> { if (wakeDone[0]) return; tvZ2.setTranslationY(0f); tvZ2.setAlpha(0.85f); tvZ2.animate().translationY(-24f).alpha(0f).setDuration(1300).withEndAction(() -> { if (!wakeDone[0]) handler.postDelayed(z2r[0], 120); }).start(); };
                                z3r[0] = () -> { if (wakeDone[0]) return; tvZ3.setTranslationY(0f); tvZ3.setAlpha(0.85f); tvZ3.animate().translationY(-28f).alpha(0f).setDuration(1500).withEndAction(() -> { if (!wakeDone[0]) handler.postDelayed(z3r[0], 120); }).start(); };
                                tvZ1.setVisibility(View.VISIBLE); tvZ2.setVisibility(View.VISIBLE); tvZ3.setVisibility(View.VISIBLE);
                                tvZ1.setAlpha(0f); tvZ2.setAlpha(0f); tvZ3.setAlpha(0f);
                                tvZ1.animate().alpha(1f).setDuration(220).withEndAction(() -> handler.post(z1r[0])).start();
                                handler.postDelayed(() -> tvZ2.animate().alpha(1f).setDuration(220).withEndAction(() -> handler.post(z2r[0])).start(), 220);
                                handler.postDelayed(() -> tvZ3.animate().alpha(1f).setDuration(220).withEndAction(() -> handler.post(z3r[0])).start(), 440);
                            }
                            // Dim-breathing while sleeping
                            ValueAnimator sleepBreath = ValueAnimator.ofFloat(0f, 1f);
                            sleepBreath.setDuration(2400);
                            sleepBreath.setRepeatCount(ValueAnimator.INFINITE);
                            sleepBreath.setRepeatMode(ValueAnimator.REVERSE);
                            sleepBreath.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                            sleepBreath.addUpdateListener(a -> {
                                if (!ivOnboardingAvatar.isAttachedToWindow()) return;
                                float t = (float) a.getAnimatedValue();
                                ivOnboardingAvatar.setAlpha(0.52f + 0.30f * t);
                                float sc = 0.96f + 0.04f * t;
                                ivOnboardingAvatar.setScaleX(sc); ivOnboardingAvatar.setScaleY(sc);
                            });
                            sleepBreath.start();
                            // ===== WAKE SEQUENCE =====
                            handler.postDelayed(() -> {
                                wakeDone[0] = true;
                                sleepBreath.cancel();
                                ivOnboardingAvatar.setAlpha(1f); ivOnboardingAvatar.setScaleX(1f); ivOnboardingAvatar.setScaleY(1f);
                                if (tvZ1 != null) tvZ1.animate().alpha(0f).setDuration(200).withEndAction(() -> tvZ1.setVisibility(View.GONE)).start();
                                if (tvZ2 != null) tvZ2.animate().alpha(0f).setDuration(200).withEndAction(() -> tvZ2.setVisibility(View.GONE)).start();
                                if (tvZ3 != null) tvZ3.animate().alpha(0f).setDuration(200).withEndAction(() -> tvZ3.setVisibility(View.GONE)).start();
                                // Play wake AVD immediately
                                ivOnboardingAvatar.setImageResource(R.drawable.toggle_on_anim);
                                ivOnboardingAvatar.post(() -> {
                                        android.graphics.drawable.Drawable wakeDrawable = ivOnboardingAvatar.getDrawable();
                                        // afterWake handles Hi! greeting — no pre-pop needed here
                                        Runnable afterWake = () -> {
                                            ivOnboardingAvatar.setImageResource(R.drawable.toggle_on_idle);
                                            // Idle breathing (alpha + scale pulse)
                                            ValueAnimator idleBreath = ValueAnimator.ofFloat(0f, 1f);
                                            idleBreath.setDuration(2600);
                                            idleBreath.setRepeatCount(ValueAnimator.INFINITE);
                                            idleBreath.setRepeatMode(ValueAnimator.REVERSE);
                                            idleBreath.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                                            idleBreath.addUpdateListener(a2 -> {
                                                if (!ivOnboardingAvatar.isAttachedToWindow()) return;
                                                float t2 = (float) a2.getAnimatedValue();
                                                ivOnboardingAvatar.setAlpha(0.85f + 0.15f * t2);
                                                float sc2 = 0.97f + 0.03f * t2;
                                                ivOnboardingAvatar.setScaleX(sc2); ivOnboardingAvatar.setScaleY(sc2);
                                            });
                                            idleBreath.start();
                                            // Floating bob
                                            android.animation.ObjectAnimator floatAnim = android.animation.ObjectAnimator
                                                    .ofFloat(ivOnboardingAvatar, "translationY", 0f, -8f);
                                            floatAnim.setDuration(3200);
                                            floatAnim.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
                                            floatAnim.setRepeatMode(android.animation.ObjectAnimator.REVERSE);
                                            floatAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                                            floatAnim.start();
                                            // Blink loop
                                            blinkLoopActive[0] = true;
                                            final Runnable[] blinkRef = {null};
                                            blinkRef[0] = () -> {
                                                if (!blinkLoopActive[0]) return;
                                                ivOnboardingAvatar.setImageResource(R.drawable.toggle_on_blink);
                                                android.graphics.drawable.Drawable bd = ivOnboardingAvatar.getDrawable();
                                                if (bd instanceof android.graphics.drawable.Animatable2) {
                                                    android.graphics.drawable.Animatable2 blinkAvd = (android.graphics.drawable.Animatable2) bd;
                                                    blinkAvd.clearAnimationCallbacks();
                                                    blinkAvd.registerAnimationCallback(new android.graphics.drawable.Animatable2.AnimationCallback() {
                                                        @Override
                                                        public void onAnimationEnd(android.graphics.drawable.Drawable d2) {
                                                            if (!blinkLoopActive[0]) return;
                                                            ivOnboardingAvatar.post(() -> {
                                                                ivOnboardingAvatar.setImageResource(R.drawable.toggle_on_idle);
                                                                int nb = 2600 + new java.util.Random().nextInt(2200);
                                                                handler.postDelayed(blinkRef[0], nb);
                                                            });
                                                        }
                                                    });
                                                    blinkAvd.start();
                                                } else if (bd instanceof android.graphics.drawable.Animatable) {
                                                    ((android.graphics.drawable.Animatable) bd).start();
                                                    handler.postDelayed(() -> {
                                                        if (!blinkLoopActive[0]) return;
                                                        ivOnboardingAvatar.setImageResource(R.drawable.toggle_on_idle);
                                                        handler.postDelayed(blinkRef[0], 3000);
                                                    }, 280);
                                                }
                                            };
                                            handler.postDelayed(blinkRef[0], 2400);
                                            // Greeting cycle: Hi! <-> Arabic (parallel, non-blocking)
                                            final int[] gCycleIdx = {0};
                                            final Runnable[] greetingCycle = {null};
                                            greetingCycle[0] = () -> {
                                                if (tvHiGreeting == null || !tvHiGreeting.isAttachedToWindow()) return;
                                                boolean isHi = (gCycleIdx[0] % 2 == 0);
                                                tvHiGreeting.setText(isHi ? "Hi!"
                                                        : "\u0627\u0644\u0633\u0644\u0627\u0645 \u0639\u0644\u064a\u0643\u0645\n\u0648\u0631\u062d\u0645\u0629 \u0627\u0644\u0644\u0647 \u0648\u0628\u0631\u0643\u0627\u062a\u0647");
                                                tvHiGreeting.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, isHi ? 32f : 11f);
                                                tvHiGreeting.setRotation(isHi ? -10f : 4f);
                                                tvHiGreeting.setTextDirection(isHi ? View.TEXT_DIRECTION_LTR : View.TEXT_DIRECTION_RTL);
                                                tvHiGreeting.setAlpha(0f);
                                                tvHiGreeting.setScaleX(isHi ? 0.4f : 0.7f);
                                                tvHiGreeting.setScaleY(isHi ? 0.4f : 0.7f);
                                                tvHiGreeting.animate().alpha(1f).scaleX(1f).scaleY(1f)
                                                        .setDuration(280)
                                                        .setInterpolator(new android.view.animation.OvershootInterpolator(isHi ? 2.2f : 1.5f))
                                                        .withEndAction(() -> handler.postDelayed(() -> {
                                                            tvHiGreeting.animate().alpha(0f).setDuration(220)
                                                                    .withEndAction(() -> {
                                                                        gCycleIdx[0]++;
                                                                        handler.postDelayed(greetingCycle[0], 150);
                                                                    }).start();
                                                        }, 1200)).start();
                                            };
                                            tvHiGreeting.setVisibility(View.VISIBLE);
                                            handler.post(greetingCycle[0]);
                                            
                                        }; // closes afterWake
                                        // Play wake AVD; call afterWake on completion
                                        if (wakeDrawable instanceof android.graphics.drawable.Animatable2) {
                                            android.graphics.drawable.Animatable2 avd2 = (android.graphics.drawable.Animatable2) wakeDrawable;
                                            avd2.clearAnimationCallbacks();
                                            avd2.registerAnimationCallback(new android.graphics.drawable.Animatable2.AnimationCallback() {
                                                @Override
                                                public void onAnimationEnd(android.graphics.drawable.Drawable drawable) {
                                                    ivOnboardingAvatar.post(afterWake);
                                                }
                                            });
                                            avd2.start();
                                        } else if (wakeDrawable instanceof android.graphics.drawable.Animatable) {
                                            ((android.graphics.drawable.Animatable) wakeDrawable).start();
                                            handler.postDelayed(afterWake, 480);
                                        } else {
                                            afterWake.run();
                                        }
                                    });
                            }, 600); // sleep display duration
                        }
                    }
                }, true);
    }

    private void hideSystemBars() {
        getWindow().setNavigationBarColor(android.graphics.Color.BLACK);
        androidx.core.view.WindowInsetsControllerCompat controller =
                androidx.core.view.WindowCompat.getInsetsController(
                        getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightNavigationBars(false);
        controller.setAppearanceLightStatusBars(false);
        controller.setSystemBarsBehavior(
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
            hideNativeBottomChrome();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // Method to update navigation buttons based on current position
    private void updateNavigationButtons(int position) {
        ViewPager viewPager = findViewById(com.github.appintro.R.id.view_pager);
        if (viewPager == null) return;

        // The pager adapter can arrive after the first resume; the flow always has 4 slides.
        int slideCount = viewPager.getAdapter() == null ? 4 : viewPager.getAdapter().getCount();
        isFirstSlide = position == 0;
        isLastSlide = position == slideCount - 1;

        // The library's own arrows and indicator stay out of the way; the dock owns them.
        if (backButton != null) backButton.setVisibility(View.GONE);
        if (nextButton != null) nextButton.setVisibility(View.GONE);

        if (position == 1 && !capabilitiesNextRevealed) {
            OnboardingCapabilitiesFragment capabilities = findCapabilitiesFragment();
            if (capabilities != null && capabilities.isAtBottom()) {
                capabilitiesNextRevealed = true;
            }
        }

        if (dockBack != null) {
            dockBack.setVisibility(View.VISIBLE);
            setDockArrowEnabled(dockBack, !isFirstSlide);
        }
        if (dockIndicator != null) {
            dockIndicator.selectPosition(position);
        }

        boolean scrollGate = position == 1 && !capabilitiesNextRevealed;
        boolean permissionsBlocked = false;
        if (position == 2) {
            OnboardingPermissionsFragment permissions = findPermissionsFragment();
            permissionsBlocked = permissions == null || !permissions.isPolicyRespected();
        }
        setDockNextVisible(!isLastSlide,
                !isLastSlide && !scrollGate && !permissionsBlocked);
        setDockDoneVisible(isLastSlide);
        setScrollHintVisible(scrollGate);
    }

    /** Disabled arrows stay in the dock as quiet anchors instead of leaving a hole. */
    private void setDockArrowEnabled(View arrow, boolean enabled) {
        if (!(arrow instanceof ImageView)) return;
        ImageView image = (ImageView) arrow;
        image.setEnabled(enabled);
        image.setClickable(enabled);
        image.setAlpha(enabled ? 1f : 0.55f);
        image.setColorFilter(ContextCompat.getColor(this,
                enabled ? R.color.pro_gold : R.color.dossier_muted));
    }

    private void setDockNextVisible(boolean visible, boolean enabled) {
        if (dockNext == null) return;
        dockNext.animate().cancel();
        if (!visible) {
            dockNext.setVisibility(View.INVISIBLE);
            return;
        }
        float targetAlpha = enabled ? 1f : 0.55f;
        dockNext.setVisibility(View.VISIBLE);
        dockNext.setEnabled(enabled);
        dockNext.setClickable(enabled);
        dockNext.setColorFilter(ContextCompat.getColor(this,
                enabled ? R.color.pro_gold : R.color.dossier_muted));
        if (animationsEnabled()) {
            dockNext.setAlpha(0f);
            dockNext.setScaleX(0.9f);
            dockNext.setScaleY(0.9f);
            dockNext.animate().alpha(targetAlpha).scaleX(1f).scaleY(1f)
                    .setDuration(240L).setInterpolator(expoOut()).start();
        } else {
            dockNext.setAlpha(targetAlpha);
            dockNext.setScaleX(1f);
            dockNext.setScaleY(1f);
        }
    }

    private void setDockDoneVisible(boolean visible) {
        if (dockDone == null) return;
        dockDone.animate().cancel();
        if (!visible) {
            dockDone.setVisibility(View.INVISIBLE);
            return;
        }
        dockDone.setVisibility(View.VISIBLE);
        if (animationsEnabled()) {
            dockDone.setAlpha(0f);
            dockDone.setScaleX(0.8f);
            dockDone.setScaleY(0.8f);
            dockDone.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(260L).setInterpolator(expoOut()).start();
        } else {
            dockDone.setAlpha(1f);
            dockDone.setScaleX(1f);
            dockDone.setScaleY(1f);
        }
    }

    private void setScrollHintVisible(boolean show) {
        if (dockScrollHint == null) return;
        if (show && !scrollHintVisible) {
            dockScrollHint.animate().cancel();
            dockScrollHint.setVisibility(View.VISIBLE);
            scrollHintVisible = true;
            if (animationsEnabled()) {
                dockScrollHint.setAlpha(0f);
                dockScrollHint.setTranslationY(dp(6));
                dockScrollHint.animate().alpha(1f).translationY(0f)
                        .setDuration(300L).setInterpolator(expoOut()).start();
            } else {
                dockScrollHint.setAlpha(1f);
                dockScrollHint.setTranslationY(0f);
            }
        } else if (!show && scrollHintVisible) {
            scrollHintVisible = false;
            if (animationsEnabled()) {
                dockScrollHint.animate().alpha(0f).translationY(-dp(4))
                        .setDuration(180L).setInterpolator(expoOut())
                        .withEndAction(() -> dockScrollHint.setVisibility(View.GONE)).start();
            } else {
                dockScrollHint.setVisibility(View.GONE);
            }
        } else if (!show) {
            dockScrollHint.setVisibility(View.GONE);
        }
    }

    @Override
    public void onCapabilitiesScrollState(boolean atBottom) {
        // The gate is live: scrolling back up brings the cue and the quiet arrow back.
        capabilitiesNextRevealed = atBottom;
        ViewPager pager = findViewById(com.github.appintro.R.id.view_pager);
        if (pager != null && pager.getCurrentItem() == 1) {
            updateNavigationButtons(1);
        }
    }

    /**
     * Floating translucent dock: back, page squircles and next. It replaces AppIntro's
     * opaque bottom bar so the dossier paper stays visible behind the controls.
     */
    private void setupFloatingDock() {
        if (dockBack != null) return;
        FrameLayout content = findViewById(android.R.id.content);
        if (content == null) return;
        final ViewPager pager = findViewById(com.github.appintro.R.id.view_pager);
        final boolean rtl = getResources().getConfiguration().getLayoutDirection()
                == View.LAYOUT_DIRECTION_RTL;

        final int dockH = getResources().getDimensionPixelSize(R.dimen.onboarding_dock_height);
        final int arrow = getResources().getDimensionPixelSize(R.dimen.onboarding_dock_arrow);
        final int pad = getResources().getDimensionPixelSize(R.dimen.onboarding_dock_padding);
        final int dockBottom = getResources().getDimensionPixelSize(
                R.dimen.onboarding_dock_bottom_margin);
        final int dotSize = getResources().getDimensionPixelSize(R.dimen.onboarding_dock_dot);
        final int dotMargin = getResources().getDimensionPixelSize(
                R.dimen.onboarding_dock_dot_margin);
        final int hintGap = getResources().getDimensionPixelSize(
                R.dimen.onboarding_scroll_hint_bottom_gap);
        final float hintTextSize = getResources().getDimension(
                R.dimen.onboarding_scroll_hint_text_size);
        final int hintBottom = dockBottom + dockH + hintGap;

        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setBackgroundResource(R.drawable.bg_onboarding_dock);
        dock.setElevation(dp(10));
        dock.setPadding(pad, 0, pad, 0);

        ImageView back = new ImageView(this);
        back.setLayoutParams(new LinearLayout.LayoutParams(arrow, arrow));
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setImageResource(rtl
                ? R.drawable.ic_onboarding_chevron
                : R.drawable.ic_onboarding_chevron_left);
        back.setContentDescription(getString(R.string.onboarding_back));
        int ripple = selectableBorderless();
        if (ripple != 0) back.setBackgroundResource(ripple);
        back.setOnClickListener(v -> {
            if (pager == null) return;
            int current = pager.getCurrentItem();
            if (current > 0) pager.setCurrentItem(current - 1, true);
        });

        dockIndicator = new SquircleIndicatorController();
        dockIndicator.setSelectedIndicatorColor(ContextCompat.getColor(this, R.color.white));
        dockIndicator.setUnselectedIndicatorColor(ContextCompat.getColor(this, R.color.gray500));
        dockIndicator.setDotSize(dotSize);
        dockIndicator.setDotMargin(dotMargin);
        View dots = dockIndicator.newInstance(this);
        dockIndicator.initialize(4);
        dots.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dotsLp.setMargins(dp(5), 0, dp(5), 0);
        dots.setLayoutParams(dotsLp);

        ImageView next = new ImageView(this);
        next.setScaleType(ImageView.ScaleType.CENTER);
        next.setImageResource(rtl
                ? R.drawable.ic_onboarding_chevron_left
                : R.drawable.ic_onboarding_chevron);
        next.setContentDescription(getString(R.string.onboarding_next));
        if (ripple != 0) next.setBackgroundResource(ripple);
        next.setOnClickListener(v -> {
            if (pager == null) return;
            int current = pager.getCurrentItem();
            if (pager.getAdapter() != null && current < pager.getAdapter().getCount() - 1) {
                pager.setCurrentItem(current + 1, true);
            }
        });

        ImageView done = new ImageView(this);
        done.setScaleType(ImageView.ScaleType.CENTER);
        done.setImageResource(R.drawable.ic_check);
        done.setColorFilter(ContextCompat.getColor(this, R.color.pro_gold));
        done.setContentDescription(getString(R.string.onboarding_done));
        done.setVisibility(View.INVISIBLE);

        FrameLayout slot = new FrameLayout(this);
        slot.setLayoutParams(new LinearLayout.LayoutParams(arrow, arrow));
        next.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        done.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        slot.addView(next);
        slot.addView(done);

        dock.addView(back);
        dock.addView(dots);
        dock.addView(slot);

        FrameLayout.LayoutParams dockLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dockH,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dockLp.bottomMargin = dockBottom;
        content.addView(dock, dockLp);

        LinearLayout hint = new LinearLayout(this);
        hint.setOrientation(LinearLayout.HORIZONTAL);
        hint.setGravity(Gravity.CENTER_VERTICAL);
        hint.setBackgroundResource(R.drawable.bg_onboarding_scroll_hint);
        hint.setElevation(dp(6));
        hint.setPadding(dp(10), dp(5), dp(10), dp(5));
        hint.setVisibility(View.GONE);
        hint.setContentDescription(getString(R.string.onboarding_scroll_hint));
        hint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        hint.setClickable(true);
        hint.setFocusable(true);
        hint.setOnClickListener(v -> {
            OnboardingCapabilitiesFragment capabilities = findCapabilitiesFragment();
            if (capabilities != null) capabilities.scrollDown();
        });

        TextView chevron = new TextView(this);
        chevron.setText("keyboard_arrow_down");
        chevron.setTypeface(ResourcesCompat.getFont(this, R.font.materialicons));
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        chevron.setTextColor(ContextCompat.getColor(this, R.color.pro_gold));
        chevron.setIncludeFontPadding(false);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        TextView scrollLabel = new TextView(this);
        scrollLabel.setText(R.string.onboarding_scroll_short);
        scrollLabel.setTypeface(Typeface.MONOSPACE);
        scrollLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, hintTextSize);
        scrollLabel.setTextColor(ContextCompat.getColor(this, R.color.pro_gold));
        scrollLabel.setAllCaps(true);
        scrollLabel.setLetterSpacing(0.12f);
        scrollLabel.setIncludeFontPadding(false);
        scrollLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams scrollLabelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollLabelLp.setMarginStart(dp(4));

        hint.addView(chevron);
        hint.addView(scrollLabel, scrollLabelLp);

        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hintLp.bottomMargin = hintBottom;
        content.addView(hint, hintLp);

        dockBack = back;
        dockNext = next;
        dockDone = done;
        dockScrollHint = hint;

        ViewCompat.setOnApplyWindowInsetsListener(dock, (v, insets) -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            lp.bottomMargin = dockBottom + insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()).bottom;
            v.setLayoutParams(lp);
            return insets;
        });
        ViewCompat.setOnApplyWindowInsetsListener(hint, (v, insets) -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            lp.bottomMargin = hintBottom + insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()).bottom;
            v.setLayoutParams(lp);
            return insets;
        });
        ViewCompat.requestApplyInsets(dock);
        ViewCompat.requestApplyInsets(hint);

        if (animationsEnabled()) {
            dock.setAlpha(0f);
            dock.setTranslationY(dp(10));
            dock.post(() -> dock.animate().alpha(1f).translationY(0f)
                    .setStartDelay(140L).setDuration(420L)
                    .setInterpolator(expoOut()).start());
        }
    }

    private int selectableBorderless() {
        TypedValue value = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true);
        return value.resourceId;
    }

    private void hideNativeBottomChrome() {
        // AppIntro may re-show its own arrows on SlidePolicy pages, so merely setting
        // GONE is not enough: detach the arrows and native indicator from the hierarchy.
        // The bottom/separator views stay attached (hidden) because the pager anchors to them.
        int[] removable = {com.github.appintro.R.id.back, com.github.appintro.R.id.next,
                com.github.appintro.R.id.indicator_container};
        for (int id : removable) {
            View view = findViewById(id);
            if (view == null) continue;
            view.setVisibility(View.GONE);
            if (view.getParent() instanceof ViewGroup) {
                ((ViewGroup) view.getParent()).removeView(view);
            }
        }
        int[] hidden = {com.github.appintro.R.id.bottom,
                com.github.appintro.R.id.bottom_separator};
        for (int id : hidden) {
            View view = findViewById(id);
            if (view != null) view.setVisibility(View.GONE);
        }
    }

    private android.view.animation.Interpolator expoOut() {
        return new PathInterpolator(0.16f, 1f, 0.3f, 1f);
    }

    private boolean animationsEnabled() {
        try {
            float scale = Settings.Global.getFloat(getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            return scale != 0f;
        } catch (Exception ignored) {
            return true;
        }
    }

    // Implement a custom page transformer for fade transitions
    private static class FadePageTransformer implements ViewPager.PageTransformer {
        private static final float MIN_ALPHA = 0.0f;
        private static final float MAX_ALPHA = 1.0f;

        @Override
        public void transformPage(@NonNull View page, float position) {
            if (position < -1) { // Page is way off-screen to the left
                page.setAlpha(MIN_ALPHA);
            } else if (position <= 1) { // Page is visible or entering
                // Calculate alpha based on position
                float alphaFactor = Math.max(MIN_ALPHA, 1 - Math.abs(position));
                page.setAlpha(alphaFactor);

                // Apply a slight scale effect
                float scaleFactor = Math.max(0.85f, 1 - Math.abs(position * 0.15f));
                page.setScaleX(scaleFactor);
                page.setScaleY(scaleFactor);
            } else { // Page is way off-screen to the right
                page.setAlpha(MIN_ALPHA);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideNativeBottomChrome();
        ViewPager pager = findViewById(com.github.appintro.R.id.view_pager);
        if (pager != null) {
            pager.post(() -> updateNavigationButtons(pager.getCurrentItem()));
            pager.postDelayed(() -> updateNavigationButtons(pager.getCurrentItem()), 240L);
            pager.post(() -> notifyCapabilitiesPage(pager.getCurrentItem() == 1));
            pager.postDelayed(() -> notifyCapabilitiesPage(pager.getCurrentItem() == 1), 240L);
            pager.post(() -> {
                if (pager.getCurrentItem() == 2) notifyPermissionsPage();
            });
        }
    }

    private void notifyPermissionsPage() {
        OnboardingPermissionsFragment permissions = findPermissionsFragment();
        if (permissions != null) permissions.onSlideShown();
    }

    private OnboardingPermissionsFragment findPermissionsFragment() {
        Fragment target = getSupportFragmentManager().findFragmentByTag(
                "android:switcher:" + com.github.appintro.R.id.view_pager + ":2");
        if (target instanceof OnboardingPermissionsFragment) {
            return (OnboardingPermissionsFragment) target;
        }
        for (Fragment candidate : getSupportFragmentManager().getFragments()) {
            if (candidate instanceof OnboardingPermissionsFragment) {
                return (OnboardingPermissionsFragment) candidate;
            }
        }
        return null;
    }

    /** Called by the permission slide whenever the grant state changes. */
    public void onPermissionsStateChanged() {
        ViewPager pager = findViewById(com.github.appintro.R.id.view_pager);
        if (pager != null && pager.getCurrentItem() == 2) {
            updateNavigationButtons(2);
        }
    }

    private OnboardingCapabilitiesFragment findCapabilitiesFragment() {
        Fragment target = getSupportFragmentManager().findFragmentByTag(
                "android:switcher:" + com.github.appintro.R.id.view_pager + ":1");
        if (target instanceof OnboardingCapabilitiesFragment) {
            return (OnboardingCapabilitiesFragment) target;
        }
        for (Fragment candidate : getSupportFragmentManager().getFragments()) {
            if (candidate instanceof OnboardingCapabilitiesFragment) {
                return (OnboardingCapabilitiesFragment) candidate;
            }
        }
        return null;
    }

    /** Slide 02 owns its reveal; the pager is the source of truth for visibility. */
    private void notifyCapabilitiesPage(boolean visible) {
        OnboardingCapabilitiesFragment target = findCapabilitiesFragment();
        if (target == null) return;
        if (visible) {
            target.onPageBecameVisible();
        } else {
            target.onPageHidden();
        }
    }

    /**
     * This method is still overridden to handle any case where the done action
     * might be triggered,
     * even though we've hidden the done button and are using our custom Start
     * button.
     */
    @Override
    protected void onDonePressed(Fragment currentFragment) {
        super.onDonePressed(currentFragment);
        finishOnboarding();
    }

    public void finishOnboarding() {
        // Mark onboarding as completed
        SharedPreferencesManager sharedPreferencesManager = SharedPreferencesManager.getInstance(this);

        // Use commit() for immediate effect and make sure we're writing to the correct
        // key
        sharedPreferencesManager.sharedPreferences.edit()
                .putBoolean(com.fadcam.Constants.COMPLETED_ONBOARDING_KEY, true)
                .commit(); // Use commit() instead of apply() to ensure it's written immediately

        // Also directly call the setter method to be doubly sure
        sharedPreferencesManager.setShowOnboarding(false);

        // Log the change to verify it happened
        FLog.d("OnboardingActivity", "Onboarding marked as completed");

        // START: Navigate to What's New screen after onboarding completes
        // Show What's New screen if onboarding display is enabled (which it should be on first install)
        // This ensures the What's New screen appears right after onboarding completes
        // Skip WhatsNewActivity on Wear OS: WebView is not supported
        if (!com.fadcam.utils.RuntimeCompat.isWatchDevice(this)) {
            Intent intent = new Intent(this, WhatsNewActivity.class);
            startActivity(intent);
        }
        finish();
        // END: Navigate to What's New screen after onboarding completes
    }

    /**
     * Creates a continuous white shimmer effect on a TextView.
     * 
     * @param textView The TextView to apply the shimmer effect to.
     */
    private void startShimmerEffect(final TextView textView) {
        if (textView == null || !textView.isAttachedToWindow())
            return;

        // Make the text significantly darker to increase contrast with the shimmer
        final int originalTextColor = textView.getCurrentTextColor();
        // Darken the text color more for better shimmer contrast (0.5f makes it 50%
        // darker)
        int darkerTextColor = darkenColor(originalTextColor, 0.5f);
        textView.setTextColor(darkerTextColor);

        // Create a new ValueAnimator for the shimmer effect
        final ValueAnimator shimmerAnimator = ValueAnimator.ofFloat(0f, 2 * (float) Math.PI); // Full sine wave cycle
        shimmerAnimator.setDuration(4000); // Slower shimmer - 4 seconds per cycle
        shimmerAnimator.setRepeatCount(ValueAnimator.INFINITE);
        shimmerAnimator.setRepeatMode(ValueAnimator.RESTART);

        // Use a smoother interpolator for a more continuous shimmer
        shimmerAnimator.setInterpolator(new android.view.animation.LinearInterpolator());

        shimmerAnimator.addUpdateListener(animation -> {
            // Safety check - make sure the textView is still attached
            if (textView == null || !textView.isAttachedToWindow()) {
                animation.cancel();
                return;
            }

            float animatedValue = (float) animation.getAnimatedValue();

            // Create a shimmer effect using SpannableString
            String text = textView.getText().toString();
            if (text.isEmpty())
                return; // Skip if no text

            android.text.SpannableString spannableString = new android.text.SpannableString(text);

            // Create a very bright white for shimmer highlight - pure white
            int brightWhite = android.graphics.Color.argb(255, 255, 255, 255);

            // Use sine function for smooth, continuous shimmer position
            // This creates a wave-like effect that smoothly moves across the text
            // and naturally loops without visible jumps
            float shimmerCenter = (float) (Math.sin(animatedValue) + 1) / 2; // Convert sine (-1 to 1) to 0-1 range
            float shimmerWidth = 0.3f; // Wider shimmer for more visibility

            // Apply the shimmer to all characters
            for (int i = 0; i < text.length(); i++) {
                // Calculate character position as percentage of total width
                float charPosition = (float) i / text.length();

                // Calculate distance from the "shimmer center" - with wraparound handling
                // This creates a circular distance calculation for smoother loop transitions
                float distanceFromShimmer;
                if (Math.abs(charPosition - shimmerCenter) <= 0.5f) {
                    distanceFromShimmer = Math.abs(charPosition - shimmerCenter);
                } else {
                    distanceFromShimmer = 1.0f - Math.abs(charPosition - shimmerCenter);
                }

                // If the character is within the shimmer width, apply the effect
                if (distanceFromShimmer < shimmerWidth) {
                    // Calculate a brightness factor - closer to center = brighter
                    float brightnessFactor = 1.0f - (distanceFromShimmer / shimmerWidth);

                    // Apply a smooth curve to the brightness using sine for a more natural shimmer
                    brightnessFactor = (float) Math.sin(brightnessFactor * Math.PI / 2);

                    // Mix the darkened text color with white based on brightness factor
                    int shimmerColor = blendColors(darkerTextColor, brightWhite, brightnessFactor);

                    spannableString.setSpan(
                            new android.text.style.ForegroundColorSpan(shimmerColor),
                            i, i + 1,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                // Characters outside the shimmer width remain the darker text color
            }

            textView.setText(spannableString);
        });

        // Start the shimmer animation
        shimmerAnimator.start();

        // Store the animator in a tag to cancel it if needed
        textView.setTag(R.id.shimmer_animator_tag, shimmerAnimator);
    }

    /**
     * Darkens a color by the given factor.
     * Factor should be between 0 and 1, where 0 makes the color black and 1 keeps
     * it unchanged.
     */
    private int darkenColor(int color, float factor) {
        int a = android.graphics.Color.alpha(color);
        int r = Math.round(android.graphics.Color.red(color) * factor);
        int g = Math.round(android.graphics.Color.green(color) * factor);
        int b = Math.round(android.graphics.Color.blue(color) * factor);
        return android.graphics.Color.argb(a, r, g, b);
    }

    /**
     * Blends two colors based on the given ratio.
     * Ratio should be between 0 and 1, where 0 returns color1 and 1 returns color2.
     */
    private int blendColors(int color1, int color2, float ratio) {
        float inverseRatio = 1f - ratio;
        int r = Math
                .round(android.graphics.Color.red(color1) * inverseRatio + android.graphics.Color.red(color2) * ratio);
        int g = Math.round(
                android.graphics.Color.green(color1) * inverseRatio + android.graphics.Color.green(color2) * ratio);
        int b = Math.round(
                android.graphics.Color.blue(color1) * inverseRatio + android.graphics.Color.blue(color2) * ratio);
        return android.graphics.Color.rgb(r, g, b);
    }
}

class AlphaSpan extends android.text.style.CharacterStyle {
    private final float alpha;

    public AlphaSpan(float alpha) {
        this.alpha = Math.max(0f, Math.min(1f, alpha));
    }

    @Override
    public void updateDrawState(android.text.TextPaint tp) {
        int oldAlpha = tp.getAlpha();
        tp.setAlpha((int) (oldAlpha * alpha));
    }
}
