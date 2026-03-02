package com.fadcam.ui;

import android.app.Dialog;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.DialogFragment;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.ui.picker.OptionItem;
import com.fadcam.ui.picker.PickerBottomSheetFragment;
import com.google.android.material.sidesheet.SideSheetDialog;
import java.util.ArrayList;

/**
 * HomeSidebarFragment
 * Side overlay with settings-style grouped rows for Home options (tips, etc).
 * Based on RecordsSidebarFragment pattern.
 */
public class HomeSidebarFragment extends DialogFragment {

    private String resultKey = "home_sidebar_result";

    /** Continuous breathing alpha-pulse when preview is OFF (sleeping). */
    private ValueAnimator breathingAnimator;

    /** Continuous float translations for the zzz letters when preview is OFF. */
    private final List<ObjectAnimator> floatingZAnimators = new ArrayList<>();

    /** Handler + runnable for scheduling periodic eye blinks when awake. */
    private final Handler blinkHandler = new Handler(Looper.getMainLooper());
    private Runnable blinkRunnable;
    private final Random blinkRandom = new Random();

    /** Pulsing alpha animator for the live dot indicator when preview is ON. */
    private ValueAnimator liveDotPulseAnimator;

    public static HomeSidebarFragment newInstance() {
        return new HomeSidebarFragment();
    }

    public void setResultKey(String key) {
        this.resultKey = key;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        // Create a Material SideSheetDialog to host the sidebar content
        SideSheetDialog dialog = new SideSheetDialog(requireContext());

        // Make window background fully transparent so our gradient shape shows without gray corners
        if (dialog.getWindow() != null) {
            android.view.Window window = dialog.getWindow();
            window.setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT
                )
            );
            // Remove decor view padding/insets that can cause gray strips
            android.view.View decor = window.getDecorView();
            if (decor instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) decor).setPadding(0, 0, 0, 0);
                decor.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            }
        }
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        // Inflate the side sheet content (modal side sheet provided by Material components)
        return inflater.inflate(
            R.layout.fragment_home_sidebar,
            container,
            false
        );
    }

    @Override
    public void onViewCreated(
        @NonNull View view,
        @Nullable Bundle savedInstanceState
    ) {
        super.onViewCreated(view, savedInstanceState);

        // Handle close button
        ImageView closeButton = view.findViewById(R.id.home_sidebar_close_btn);
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> dismiss());
        }

        // What's New row
        View whatsNewRow = view.findViewById(R.id.row_whats_new);
        if (whatsNewRow != null) {
            // Show badge if feature not yet seen
            TextView whatsNewBadge = view.findViewById(R.id.badge_whats_new);
            if (whatsNewBadge != null) {
                boolean showBadge = com.fadcam.ui.utils.NewFeatureManager.shouldShowBadge(requireContext(), "whats_new");
                whatsNewBadge.setVisibility(showBadge ? View.VISIBLE : View.GONE);
            }
            
            whatsNewRow.setOnClickListener(v -> {
                openWhatsNew();
                // Mark badge as seen when clicked
                com.fadcam.ui.utils.NewFeatureManager.markFeatureAsSeen(requireContext(), "whats_new");
                dismiss();
            });
        }

        // Tips row
        View tipsRow = view.findViewById(R.id.row_tips);
        if (tipsRow != null) {
            tipsRow.setOnClickListener(v -> {
                openTipsPicker();
                dismiss();
            });
        }

        // Preview control row - bind to existing layout elements and use centralized strings/prefs.
        try {
            final SharedPreferencesManager sp =
                SharedPreferencesManager.getInstance(requireContext());

            View previewRow = view.findViewById(R.id.row_preview_toggle);
            if (previewRow != null) {
                TextView tvTitle = previewRow.findViewById(R.id.tv_preview_toggle_title);
                TextView tvSub = previewRow.findViewById(R.id.tv_preview_toggle_sub);
                ImageView ivToggle = previewRow.findViewById(R.id.iv_preview_toggle);
                View zzzGroup = previewRow.findViewById(R.id.zzz_badge_group);
                View zSmall   = previewRow.findViewById(R.id.tv_zzz_1);
                View zMid     = previewRow.findViewById(R.id.tv_zzz_2);
                View zLarge   = previewRow.findViewById(R.id.tv_zzz_3);
                View liveDot  = previewRow.findViewById(R.id.iv_live_dot);

                // Title must use centralized string: "Preview Area"
                if (tvTitle != null) {
                    tvTitle.setText(R.string.ui_preview_area);
                }

                boolean current = Boolean.TRUE.equals(sp.isPreviewEnabled());

                // Initialize subtitle, icon and zzz badge
                if (tvSub != null) {
                    tvSub.setText(current
                        ? getString(R.string.setting_enabled_msg)
                        : getString(R.string.setting_disabled_msg));
                }
                if (ivToggle != null) {
                    applyToggleState(ivToggle, zzzGroup, zSmall, zMid, zLarge, liveDot, current, /* animate= */ false);
                }

                // Toggle on icon tap
                if (ivToggle != null) {
                    ivToggle.setOnClickListener(v -> {
                        boolean newState = !Boolean.TRUE.equals(sp.isPreviewEnabled());
                        sp.setPreviewEnabled(newState);

                        // Scale-bounce the avatar then swap drawable
                        ivToggle.animate()
                            .scaleX(0.85f).scaleY(0.85f)
                            .setDuration(80)
                            .withEndAction(() -> {
                                applyToggleState(ivToggle, zzzGroup, zSmall, zMid, zLarge, liveDot, newState, /* animate= */ true);
                                ivToggle.animate()
                                    .scaleX(1f).scaleY(1f)
                                    .setDuration(260)
                                    .setInterpolator(new OvershootInterpolator(1.8f))
                                    .start();
                            }).start();

                        if (tvSub != null) {
                            tvSub.setText(newState
                                ? getString(R.string.setting_enabled_msg)
                                : getString(R.string.setting_disabled_msg));
                        }
                        try {
                            Bundle b = new Bundle();
                            b.putBoolean("preview_enabled", newState);
                            getParentFragmentManager().setFragmentResult(resultKey, b);
                        } catch (Exception ignored) {}
                    });
                }

                // Also make the whole row tappable
                previewRow.setOnClickListener(v -> {
                    if (ivToggle != null) ivToggle.performClick();
                });
            }

            View quickActionsRow = view.findViewById(R.id.row_preview_quick_actions_toggle);
            if (quickActionsRow != null) {
                TextView tvSub = quickActionsRow.findViewById(R.id.tv_preview_quick_actions_sub);
                SwitchCompat sw = quickActionsRow.findViewById(R.id.switch_preview_quick_actions_toggle);
                if (sw != null) {
                    boolean current = sp.isPreviewQuickActionsAlwaysVisible();
                    sw.setChecked(current);
                    if (tvSub != null) {
                        tvSub.setText(current
                            ? getString(R.string.preview_quick_actions_state_always)
                            : getString(R.string.preview_quick_actions_state_recording_only));
                    }
                    sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
                        sp.setPreviewQuickActionsAlwaysVisible(isChecked);
                        if (tvSub != null) {
                            tvSub.setText(isChecked
                                ? getString(R.string.preview_quick_actions_state_always)
                                : getString(R.string.preview_quick_actions_state_recording_only));
                        }
                        try {
                            Bundle b = new Bundle();
                            b.putBoolean("preview_quick_actions_always_visible", isChecked);
                            getParentFragmentManager().setFragmentResult(resultKey, b);
                        } catch (Exception ignored) {}
                    });
                    quickActionsRow.setOnClickListener(v -> sw.setChecked(!sw.isChecked()));
                }
            }
        } catch (Exception e) {
            android.util.Log.w(
                "HomeSidebar",
                "Failed to bind preview control",
                e
            );
        }

        // Discord branding row
        View discordRow = view.findViewById(R.id.row_discord_branding);
        if (discordRow != null) {
            discordRow.setOnClickListener(v -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://discord.gg/kvAZvdkuuN"));
                    startActivity(intent);
                } catch (Exception e) {
                    android.util.Log.w("HomeSidebar", "Failed to open Discord link", e);
                }
            });
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        // Set the sheet edge to START (left side) after the view is created
        Dialog dialog = getDialog();
        if (dialog instanceof SideSheetDialog) {
            try {
                ((SideSheetDialog) dialog).setSheetEdge(
                    android.view.Gravity.START
                );
            } catch (Exception ignored) {
                // Fallback if setSheetEdge fails
            }
        }

        // Clear any default container backgrounds from the SideSheet host views to avoid gray edges around rounded corners
        View root = getView();
        if (root != null) {
            View p = (View) root.getParent();
            int guard = 0;
            while (p != null && guard < 5) {
                // climb a few levels safely
                try {
                    if (p.getBackground() != null) {
                        p.setBackgroundColor(
                            android.graphics.Color.TRANSPARENT
                        );
                    }
                } catch (Exception ignored) {}
                if (!(p.getParent() instanceof View)) break;
                p = (View) p.getParent();
                guard++;
            }
        }
    }

    /**
     * Applies toggle state with full animations:
     * ON  → AVD eye light-up + eye rise → swap to idle → start blink loop.
     * OFF → AVD eye droop + fade → swap to static off → breathing + zzz.
     */
    private void applyToggleState(ImageView ivToggle,
                                   View zzzGroup, View z1, View z2, View z3,
                                   View liveDot, boolean enabled, boolean animate) {
        if (enabled) {
            // --- Turning ON ---
            stopBlinkLoop();
            stopBreathing(ivToggle);
            ivToggle.setAlpha(1.0f);
            stopFloatingZ();

            // Show live dot with smooth fade-in then start pulse
            if (liveDot != null) {
                liveDot.animate().cancel();
                liveDot.setAlpha(0f);
                liveDot.setVisibility(View.VISIBLE);
                liveDot.animate().alpha(1f).setDuration(280)
                    .withEndAction(() -> startLiveDotPulse(liveDot))
                    .start();
            }

            // Pop out zzz letters (if visible)
            if (zzzGroup != null && zzzGroup.getVisibility() == View.VISIBLE) {
                View[] letters = {z3, z2, z1};
                long outDelay = 0;
                for (View z : letters) {
                    if (z == null) continue;
                    z.animate().cancel();
                    z.animate()
                        .alpha(0f).scaleX(0.15f).scaleY(0.15f).translationY(-12f)
                        .setStartDelay(outDelay).setDuration(160)
                        .setInterpolator(new AccelerateDecelerateInterpolator()).start();
                    outDelay += 55;
                }
                zzzGroup.postDelayed(() -> {
                    zzzGroup.setVisibility(View.GONE);
                    resetZLetters(z1, z2, z3);
                }, 380);
            } else if (zzzGroup != null) {
                zzzGroup.setVisibility(View.GONE);
                resetZLetters(z1, z2, z3);
            }

            if (animate) {
                // Play wake-up AVD; after it finishes → idle + blink loop
                ivToggle.setImageResource(R.drawable.toggle_on_anim);
                Drawable d = ivToggle.getDrawable();
                if (d instanceof Animatable2) {
                    ((Animatable2) d).registerAnimationCallback(new Animatable2.AnimationCallback() {
                        @Override
                        public void onAnimationEnd(Drawable drawable) {
                            if (ivToggle.isAttachedToWindow()) {
                                ivToggle.setImageResource(R.drawable.toggle_on_idle);
                                startBlinkLoop(ivToggle);
                            }
                        }
                    });
                    ((Animatable2) d).start();
                } else if (d instanceof Animatable) {
                    ((Animatable) d).start();
                    // Fallback: swap after animation duration
                    ivToggle.postDelayed(() -> {
                        if (ivToggle.isAttachedToWindow()) {
                            ivToggle.setImageResource(R.drawable.toggle_on_idle);
                            startBlinkLoop(ivToggle);
                        }
                    }, 480);
                }
            } else {
                // Initial load: show idle immediately + start blinking
                ivToggle.setImageResource(R.drawable.toggle_on_idle);
                startBlinkLoop(ivToggle);
            }

        } else {
            // --- Turning OFF ---
            stopBlinkLoop();

            // Hide live dot with smooth fade-out
            if (liveDot != null) {
                stopLiveDotPulse();
                if (liveDot.getVisibility() == View.VISIBLE) {
                    liveDot.animate().cancel();
                    liveDot.animate().alpha(0f).setDuration(220)
                        .withEndAction(() -> {
                            liveDot.setVisibility(View.GONE);
                            liveDot.setAlpha(1f);
                        }).start();
                }
            }

            startBreathing(ivToggle, animate);

            if (animate) {
                // Play off-transition AVD; after it finishes → static off + zzz
                ivToggle.setImageResource(R.drawable.toggle_off_anim);
                Drawable d = ivToggle.getDrawable();
                Runnable afterOff = () -> {
                    if (!ivToggle.isAttachedToWindow()) return;
                    ivToggle.setImageResource(R.drawable.toggle_off);
                    showZzzLetters(zzzGroup, z1, z2, z3, true);
                };
                if (d instanceof Animatable2) {
                    ((Animatable2) d).registerAnimationCallback(new Animatable2.AnimationCallback() {
                        @Override
                        public void onAnimationEnd(Drawable drawable) {
                            afterOff.run();
                        }
                    });
                    ((Animatable2) d).start();
                } else if (d instanceof Animatable) {
                    ((Animatable) d).start();
                    ivToggle.postDelayed(afterOff, 320);
                } else {
                    afterOff.run();
                }
            } else {
                // Initial load: show static off + zzz without animation
                ivToggle.setImageResource(R.drawable.toggle_off);
                showZzzLetters(zzzGroup, z1, z2, z3, false);
            }
        }
    }

    /**
     * Shows and animates zzz letters over the sleeping avatar.
     * animate=true → staggered pop-in, then float.
     * animate=false → instant show + float.
     */
    private void showZzzLetters(View zzzGroup, View z1, View z2, View z3, boolean animate) {
        if (zzzGroup == null) return;
        stopFloatingZ();
        if (animate) {
            resetZLetters(z1, z2, z3);
            for (View z : new View[]{z1, z2, z3}) {
                if (z == null) continue;
                z.setAlpha(0f);
                z.setScaleX(0.1f);
                z.setScaleY(0.1f);
                z.setTranslationY(8f);
            }
            zzzGroup.setVisibility(View.VISIBLE);
            View[] letters = {z1, z2, z3};
            long inDelay = 130;
            for (View z : letters) {
                if (z == null) continue;
                z.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setStartDelay(inDelay).setDuration(290)
                    .setInterpolator(new OvershootInterpolator(2.5f)).start();
                inDelay += 115;
            }
            // Start gentle floating after pop-in completes
            zzzGroup.postDelayed(() -> startFloatingZ(z1, z2, z3), 700);
        } else {
            zzzGroup.setVisibility(View.VISIBLE);
            resetZLetters(z1, z2, z3);
            startFloatingZ(z1, z2, z3);
        }
    }

    // ── Blink loop ────────────────────────────────────────────────────────────

    /**
     * Schedules the first periodic blink (2.5–4.5s delay).
     * Each blink plays toggle_on_blink AVD then restores toggle_on_idle.
     */
    private void startBlinkLoop(ImageView ivToggle) {
        stopBlinkLoop();
        scheduleNextBlink(ivToggle, 2500 + blinkRandom.nextInt(2000));
    }

    private void scheduleNextBlink(ImageView ivToggle, long delayMs) {
        blinkRunnable = () -> {
            if (!ivToggle.isAttachedToWindow()) return;
            ivToggle.setImageResource(R.drawable.toggle_on_blink);
            Drawable d = ivToggle.getDrawable();
            if (d instanceof Animatable2) {
                ((Animatable2) d).registerAnimationCallback(new Animatable2.AnimationCallback() {
                    @Override
                    public void onAnimationEnd(Drawable drawable) {
                        if (ivToggle.isAttachedToWindow()) {
                            ivToggle.setImageResource(R.drawable.toggle_on_idle);
                            scheduleNextBlink(ivToggle, 3000 + blinkRandom.nextInt(2500));
                        }
                    }
                });
                ((Animatable2) d).start();
            } else if (d instanceof Animatable) {
                ((Animatable) d).start();
                ivToggle.postDelayed(() -> {
                    if (ivToggle.isAttachedToWindow()) {
                        ivToggle.setImageResource(R.drawable.toggle_on_idle);
                        scheduleNextBlink(ivToggle, 3000 + blinkRandom.nextInt(2500));
                    }
                }, 260);
            }
        };
        blinkHandler.postDelayed(blinkRunnable, delayMs);
    }

    /** Stops the blink loop (run on disable or destroy). */
    private void stopBlinkLoop() {
        if (blinkRunnable != null) {
            blinkHandler.removeCallbacks(blinkRunnable);
            blinkRunnable = null;
        }
    }

    /** Resets zzz letter transforms to default (no anim). */
    private void resetZLetters(View z1, View z2, View z3) {
        for (View z : new View[]{z1, z2, z3}) {
            if (z == null) continue;
            z.setAlpha(1f);
            z.setScaleX(1f);
            z.setScaleY(1f);
            z.setTranslationY(0f);
        }
    }

    /**
     * Starts a staggered infinite float on each Z letter:
     * each letter gently oscillates up/down at slightly different speeds.
     */
    private void startFloatingZ(View z1, View z2, View z3) {
        stopFloatingZ();
        View[] letters = {z1, z2, z3};
        long[] durations = {1600L, 1900L, 2200L};
        float[] amplitudes = {5f, 6f, 7f};
        for (int i = 0; i < letters.length; i++) {
            View z = letters[i];
            if (z == null) continue;
            ObjectAnimator floatAnim = ObjectAnimator.ofFloat(z, "translationY", 0f, -amplitudes[i]);
            floatAnim.setDuration(durations[i]);
            floatAnim.setRepeatCount(ObjectAnimator.INFINITE);
            floatAnim.setRepeatMode(ObjectAnimator.REVERSE);
            floatAnim.setInterpolator(new AccelerateDecelerateInterpolator());
            floatAnim.start();
            floatingZAnimators.add(floatAnim);
        }
    }

    /** Cancels all floating Z letter animations. */
    private void stopFloatingZ() {
        for (ObjectAnimator a : floatingZAnimators) a.cancel();
        floatingZAnimators.clear();
    }

    /**
     * Starts or restarts the breathing alpha pulse on the avatar (sleeping glow).
     * Pulses between 0.55 and 0.80 alpha with a 2.8 s period.
     */
    private void startBreathing(ImageView ivToggle, boolean animate) {
        stopBreathing(ivToggle);
        float startAlpha = animate ? 0.70f : 0.55f;
        ivToggle.setAlpha(startAlpha);
        breathingAnimator = ValueAnimator.ofFloat(0.55f, 0.80f);
        breathingAnimator.setDuration(2800);
        breathingAnimator.setRepeatCount(ValueAnimator.INFINITE);
        breathingAnimator.setRepeatMode(ValueAnimator.REVERSE);
        breathingAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        breathingAnimator.addUpdateListener(a -> {
            if (ivToggle.isAttachedToWindow()) {
                ivToggle.setAlpha((float) a.getAnimatedValue());
            }
        });
        breathingAnimator.start();
    }

    // ── Live dot pulse ─────────────────────────────────────────────────────────

    /**
     * Starts a gentle alpha+scale pulse on the live dot indicator.
     * Cycles 0.55 ↔ 1.0 alpha and 0.85 ↔ 1.0 scale every ~900 ms.
     */
    private void startLiveDotPulse(View liveDot) {
        stopLiveDotPulse();
        // Start from 1f so the first frame matches the fade-in ending alpha (no jump)
        liveDotPulseAnimator = ValueAnimator.ofFloat(1f, 0f);
        liveDotPulseAnimator.setDuration(900);
        liveDotPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        liveDotPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        liveDotPulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        liveDotPulseAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();          // 0 → 1
            float alpha = 0.55f + 0.45f * t;                 // 0.55 ↔ 1.0
            float scale = 0.85f + 0.15f * t;                 // 0.85 ↔ 1.0
            liveDot.setAlpha(alpha);
            liveDot.setScaleX(scale);
            liveDot.setScaleY(scale);
        });
        liveDotPulseAnimator.start();
    }

    /** Cancels the live dot pulse animator. */
    private void stopLiveDotPulse() {
        if (liveDotPulseAnimator != null) {
            liveDotPulseAnimator.cancel();
            liveDotPulseAnimator = null;
        }
    }

    /** Cancels the breathing animator. */
    private void stopBreathing(ImageView ivToggle) {
        if (breathingAnimator != null) {
            breathingAnimator.cancel();
            breathingAnimator = null;
        }
        ivToggle.setAlpha(1.0f);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Stop continuous animators to prevent leaks
        if (breathingAnimator != null) { breathingAnimator.cancel(); breathingAnimator = null; }
        stopFloatingZ();
        stopBlinkLoop();
        stopLiveDotPulse();
    }

    private void openWhatsNew() {
        // Open WhatsNewActivity
        android.content.Intent intent = new android.content.Intent(requireContext(), com.fadcam.ui.WhatsNewActivity.class);
        startActivity(intent);
    }

    private void openTipsPicker() {
        // Use the new TipsCarouselFragment for better tips display
        TipsCarouselFragment tipsCarousel = TipsCarouselFragment.newInstance();
        tipsCarousel.show(getParentFragmentManager(), "tips_carousel");
    }

}
