package com.fadcam.ui;

import android.os.Bundle;
import androidx.annotation.Nullable;
import android.view.View;
import androidx.annotation.NonNull;
import android.os.Handler;
import android.widget.TextView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;

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
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * OnboardingActivity shows the app intro slides using AppIntro.
 */
public class OnboardingActivity extends AppIntro {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addSlide(AppIntroCustomLayoutFragment.newInstance(R.layout.onboarding_intro_slide));
        addSlide(AppIntroCustomLayoutFragment.newInstance(R.layout.onboarding_language_slide));
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
                // Slide 2 logic (language selection)
                MaterialButton languageChooseButton = v.findViewById(R.id.language_choose_button);
                if (languageChooseButton != null) {
                    setupOnboardingLanguageDialog(languageChooseButton);
                }
            }
        }, false);
    }

    /**
     * Sets up the language choose button for onboarding, using a Material dialog for selection.
     * @param chooseButton The MaterialButton to setup.
     */
    private void setupOnboardingLanguageDialog(MaterialButton chooseButton) {
        SharedPreferencesManager sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
        String[] languages = getResources().getStringArray(R.array.languages_array);
        String savedLanguageCode = sharedPreferencesManager.getLanguage();
        int selectedIndex = getLanguageIndex(savedLanguageCode);
        chooseButton.setText(languages[selectedIndex]);
        chooseButton.setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_language_title)
                .setSingleChoiceItems(languages, selectedIndex, (dialog, which) -> {
                    String newLangCode = getLanguageCode(which);
                    if (!newLangCode.equals(sharedPreferencesManager.getLanguage())) {
                        sharedPreferencesManager.sharedPreferences.edit().putString(com.fadcam.Constants.LANGUAGE_KEY, newLangCode).apply();
                        applyLanguage(newLangCode);
                    }
                    chooseButton.setText(languages[which]);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.universal_cancel, null)
                .show();
        });
    }

    /**
     * Helper to map language code to spinner index (must match SettingsFragment).
     */
    private int getLanguageIndex(String languageCode) {
        switch (languageCode) {
            case "en": return 0;
            case "zh": return 1;
            case "ar": return 2;
            case "fr": return 3;
            case "tr": return 4;
            case "ps": return 5;
            case "in": return 6;
            case "it": return 7;
            default: return 0;
        }
    }
    /**
     * Helper to map spinner index to language code (must match SettingsFragment).
     */
    private String getLanguageCode(int position) {
        switch (position) {
            case 0: return "en";
            case 1: return "zh";
            case 2: return "ar";
            case 3: return "fr";
            case 4: return "tr";
            case 5: return "ps";
            case 6: return "in";
            case 7: return "it";
            default: return "en";
        }
    }
    /**
     * Applies the selected language immediately (same as MainActivity).
     */
    private void applyLanguage(String languageCode) {
        String currentLanguage = getResources().getConfiguration().locale.getLanguage();
        if (!languageCode.equals(currentLanguage)) {
            java.util.Locale locale = new java.util.Locale(languageCode);
            java.util.Locale.setDefault(locale);
            android.content.res.Configuration config = new android.content.res.Configuration();
            config.setLocale(locale);
            getApplicationContext().createConfigurationContext(config);
            getResources().updateConfiguration(config, getResources().getDisplayMetrics());
            recreate();
        }
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