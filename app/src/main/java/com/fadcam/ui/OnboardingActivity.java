package com.fadcam.ui;

import android.os.Bundle;
import androidx.annotation.Nullable;
import android.view.View;
import androidx.annotation.NonNull;
import android.os.Handler;
import android.widget.TextView;

import com.fadcam.SharedPreferencesManager;
import com.github.appintro.AppIntro;
import com.github.appintro.AppIntroFragment;
import com.fadcam.R;
import androidx.core.content.ContextCompat;
import android.content.Intent;
import android.content.SharedPreferences;
import com.github.appintro.AppIntroCustomLayoutFragment;
import android.animation.ValueAnimator;
import com.airbnb.lottie.LottieAnimationView;

/**
 * OnboardingActivity shows the app intro slides using AppIntro.
 */
public class OnboardingActivity extends AppIntro {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addSlide(AppIntroCustomLayoutFragment.newInstance(R.layout.onboarding_intro_slide));
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(new androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
            @Override
            public void onFragmentViewCreated(@NonNull androidx.fragment.app.FragmentManager fm, @NonNull androidx.fragment.app.Fragment f, @NonNull View v, @Nullable Bundle savedInstanceState) {
                View imageView = v.findViewById(R.id.onboardingWelcomeImage);
                if (imageView != null) {
                    imageView.setAlpha(0f);
                    imageView.setVisibility(View.VISIBLE);
                    imageView.animate().alpha(1f).setDuration(1200).start();
                }
                final TextView descView = v.findViewById(R.id.tvOnboardingDescription);
                final LottieAnimationView lottieArrow = v.findViewById(R.id.lottieArrow);
                final TextView swipeInstruction = v.findViewById(R.id.tvSwipeInstruction);
                if (descView != null) {
                    descView.setTypeface(android.graphics.Typeface.MONOSPACE);
                    descView.setGravity(android.view.Gravity.START);
                    descView.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                    // ----- Fix Start: Robust row-by-row fade-in for onboarding description -----
                    final String[] lines = {
                        "Open source,",
                        "ad-free,",
                        "and built for you—not for advertisers."
                    };
                    final String cursorChar = "▌";
                    final int rowFadeDuration = 220;
                    final int rowPauseDelay = 1200;
                    final int blinkFrameDelay = 32;
                    final int blinkDuration = 900;
                    final int startDelay = 900;
                    final Handler handler = new Handler();
                    final Runnable[] blinkRunnable = new Runnable[1];
                    final float[] cursorAlpha = {1f};
                    final boolean[] fadingOut = {true};
                    final int cursorColor = 0xFFE43C3C;

                    descView.setText("");

                    // Define startBlinkingCursor before RowFadeAnimator so it is in scope
                    final Runnable startBlinkingCursor = new Runnable() {
                        @Override
                        public void run() {
                            blinkRunnable[0] = new Runnable() {
                                @Override
                                public void run() {
                                    if (fadingOut[0]) {
                                        cursorAlpha[0] -= (float) blinkFrameDelay / (blinkDuration / 2f);
                                        if (cursorAlpha[0] <= 0f) {
                                            cursorAlpha[0] = 0f;
                                            fadingOut[0] = false;
                                        }
                                    } else {
                                        cursorAlpha[0] += (float) blinkFrameDelay / (blinkDuration / 2f);
                                        if (cursorAlpha[0] >= 1f) {
                                            cursorAlpha[0] = 1f;
                                            fadingOut[0] = true;
                                        }
                                    }
                                    // Cursor at end of all text (no extra newline)
                                    String finalText = String.join("\n", lines);
                                    android.text.SpannableString span = new android.text.SpannableString(finalText + cursorChar);
                                    span.setSpan(new AlphaSpan(1f), 0, finalText.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                                    span.setSpan(new android.text.style.ForegroundColorSpan(cursorColor), finalText.length(), finalText.length() + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                                    span.setSpan(new AlphaSpan(cursorAlpha[0]), finalText.length(), finalText.length() + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                                    descView.setText(span);
                                    handler.postDelayed(this, blinkFrameDelay);
                                }
                            };
                            handler.post(blinkRunnable[0]);
                            // Show the swipe instruction and Lottie arrow almost immediately after blinking cursor starts
                            if (lottieArrow != null && swipeInstruction != null) {
                                handler.postDelayed(() -> {
                                    // Fade in swipe instruction
                                    swipeInstruction.setAlpha(0f);
                                    swipeInstruction.setVisibility(View.VISIBLE);
                                    swipeInstruction.animate().alpha(1f).setDuration(600).start();
                                    // Show arrow after swipe text is visible
                                    handler.postDelayed(() -> {
                                        lottieArrow.setVisibility(View.VISIBLE);
                                        lottieArrow.setSpeed(0.5f); // Slightly faster
                                        lottieArrow.setRepeatCount(0); // Animate only once
                                        lottieArrow.playAnimation();
                                    }, 300); // Arrow appears sooner after swipe text fade-in
                                }, 0); // No delay after text animation completes
                            }
                        }
                    };

                    class RowFadeAnimator {
                        private final Runnable startBlinkingCursor;
                        int rowIdx = 0;
                        StringBuilder shownText = new StringBuilder();
                        RowFadeAnimator(Runnable startBlinkingCursor) {
                            this.startBlinkingCursor = startBlinkingCursor;
                        }
                        void start() {
                            // Show the first row immediately
                            shownText.append(lines[0]);
                            updateTextWithCursor();
                            rowIdx = 1;
                            handler.postDelayed(this::animateNextRow, rowPauseDelay);
                        }
                        void animateNextRow() {
                            if (rowIdx >= lines.length) {
                                startBlinkingCursor.run();
                                return;
                            }
                            shownText.append("\n").append(lines[rowIdx]);
                            // Fade in the whole TextView for this row
                            descView.setAlpha(0f);
                            updateTextWithCursor();
                            descView.animate().alpha(1f).setDuration(rowFadeDuration).withEndAction(() -> {
                                rowIdx++;
                                handler.postDelayed(this::animateNextRow, rowPauseDelay);
                            }).start();
                        }
                        void updateTextWithCursor() {
                            String text = shownText.toString() + cursorChar;
                            android.text.SpannableString span = new android.text.SpannableString(text);
                            span.setSpan(new android.text.style.ForegroundColorSpan(cursorColor), text.length() - 1, text.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            descView.setText(span);
                        }
                    }
                    final RowFadeAnimator rowAnimator = new RowFadeAnimator(startBlinkingCursor);
                    handler.postDelayed(rowAnimator::start, startDelay);
                    // ----- Fix End: Robust row-by-row fade-in for onboarding description -----
                }
            }
        }, false);
    }

    @Override
    public void onDonePressed(@Nullable androidx.fragment.app.Fragment currentFragment) {
        // Mark onboarding as completed
        SharedPreferencesManager.getInstance(this)
            .sharedPreferences.edit().putBoolean("PREF_SHOW_ONBOARDING", false).apply();

        // Launch MainActivity
        Intent intent = new Intent(this, com.fadcam.MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);

        super.onDonePressed(currentFragment);
        finish();
    }
}

// ----- Fix Start: AlphaSpan for cursor fade animation -----
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
// ----- Fix End: AlphaSpan for cursor fade animation ----- 