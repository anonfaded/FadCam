package com.fadcam.ui;

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

import java.util.ArrayList;
import java.util.List;

/**
 * OnboardingActivity shows the app intro slides using AppIntro.
 */
public class OnboardingActivity extends AppIntro {
    private boolean isLastSlide = false;
    private boolean isFirstSlide = true; // Track if we're on the first slide
    // Use View instead of Button to avoid class cast exceptions
    private View backButton;
    private View nextButton;
    
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addSlide(AppIntroCustomLayoutFragment.newInstance(R.layout.onboarding_intro_slide));
        addSlide(AppIntroCustomLayoutFragment.newInstance(R.layout.onboarding_language_slide));
        addSlide(new OnboardingPermissionsFragment());
        addSlide(new OnboardingHumanFragment());

        // Hide only the Done button while keeping navigation buttons
        setSkipButtonEnabled(false); // No skip button needed
        setNextPageSwipeLock(false);
        setIndicatorEnabled(true);
        
        // Use wizard mode to replace Skip with Back button
        setWizardMode(true);
        
        // Fix for indicator dots - use custom colors
        setIndicatorColor(
            ContextCompat.getColor(this, R.color.white), // Selected dot
            ContextCompat.getColor(this, R.color.gray500) // Unselected dot
        );
        
        // Disable color transitions as our slides don't implement SlideBackgroundColorHolder
        setColorTransitionsEnabled(false);
        
        // Set fade transition effect between slides
        ViewPager viewPager = findViewById(com.github.appintro.R.id.view_pager);
        if (viewPager != null) {
            viewPager.setPageTransformer(true, new FadePageTransformer());
            
            // Listen for page changes to update navigation buttons
            viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
                @Override
                public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {}
    
                @Override
                public void onPageSelected(int position) {
                    updateNavigationButtons(position);
                }
    
                @Override
                public void onPageScrollStateChanged(int state) {}
            });
        }
        
        // Initialize navigation button references
        backButton = findViewById(com.github.appintro.R.id.back);
        nextButton = findViewById(com.github.appintro.R.id.next);
        
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
        
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(new androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
            @Override
            public void onFragmentViewCreated(@NonNull androidx.fragment.app.FragmentManager fm, @NonNull Fragment f, @Nullable View v, @Nullable Bundle savedInstanceState) {
                super.onFragmentViewCreated(fm, f, v, savedInstanceState);
                if (v == null) return;
                View imageView = v.findViewById(R.id.onboardingWelcomeImage);
                final TextView descView = v.findViewById(R.id.tvOnboardingDescription);
                final LottieAnimationView lottieArrow = v.findViewById(R.id.lottieArrow);
                final TextView swipeInstruction = v.findViewById(R.id.tvSwipeInstruction);
                if (imageView != null && descView != null) {
                    imageView.setAlpha(0f);
                    imageView.setVisibility(View.VISIBLE);
                    // ----- Fix Start for onboarding intro step-by-step animation flow -----
                    imageView.animate().alpha(1f).setDuration(1200).withEndAction(() -> {
                        // Only start text animation after image fade-in completes
                        descView.setTypeface(android.graphics.Typeface.MONOSPACE);
                        descView.setGravity(android.view.Gravity.START);
                        descView.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
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
                                shownText.append(lines[0]);
                                updateTextWithCursor();
                                rowIdx = 1;
                                handler.postDelayed(this::animateNextRow, rowPauseDelay);
                            }
                            void animateNextRow() {
                                if (rowIdx >= lines.length) {
                                    // Start blinking cursor after all lines
                                    startBlinkingCursor.run();
                                    return;
                                }
                                shownText.append("\n").append(lines[rowIdx]);
                                descView.setAlpha(0f);
                                updateTextWithCursor();
                                descView.animate().alpha(1f).setDuration(rowFadeDuration).withEndAction(() -> {
                                    // As the last line animates in, fade in swipe instruction and arrow
                                    if (rowIdx == lines.length - 1 && swipeInstruction != null && lottieArrow != null) {
                                        // Show arrow first
                                        lottieArrow.setVisibility(View.VISIBLE);
                                        lottieArrow.setSpeed(0.5f);
                                        lottieArrow.setRepeatCount(0);
                                        lottieArrow.playAnimation();
                                        
                                        // Create a center-outward fade effect for the text
                                        handler.postDelayed(() -> {
                                            // Check if the view is still attached before proceeding
                                            if (swipeInstruction == null || !swipeInstruction.isAttachedToWindow()) {
                                                return; // Skip animation if view is detached
                                            }
                                            
                                            // Simple fade-in without reveal animation (which was causing the crash)
                                            swipeInstruction.setVisibility(View.VISIBLE);
                                            swipeInstruction.setAlpha(0f);
                                            
                                            // First fade in the entire text (base layer)
                                            swipeInstruction.animate()
                                                .alpha(1f)
                                                .setDuration(1200)
                                                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                                                .withEndAction(() -> {
                                                    // Start the shimmer effect after fade-in completes
                                                    if (swipeInstruction.isAttachedToWindow()) {
                                                        startShimmerEffect(swipeInstruction);
                                                    }
                                                })
                                                .start();
                                        }, 700); // Delay before animation
                                    }
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
                        handler.postDelayed(rowAnimator::start, 200); // Short pause after image fade-in
                    });
                    // ----- Fix End for onboarding intro step-by-step animation flow -----
                }
                // Slide 2 logic (language selection)
                MaterialButton languageChooseButton = v.findViewById(R.id.language_choose_button);
                if (languageChooseButton != null) {
                    setupOnboardingLanguageDialog(languageChooseButton);
                }
            }
        }, true);
    }

    // Method to update navigation buttons based on current position
    private void updateNavigationButtons(int position) {
        ViewPager viewPager = findViewById(com.github.appintro.R.id.view_pager);
        if (viewPager == null || viewPager.getAdapter() == null) return;
        
        int slideCount = viewPager.getAdapter().getCount();
        isFirstSlide = position == 0;
        isLastSlide = position == slideCount - 1;
        
        if (backButton != null) {
            backButton.setVisibility(isFirstSlide ? View.INVISIBLE : View.VISIBLE);
        }
        
        if (nextButton != null) {
            nextButton.setVisibility(isLastSlide ? View.INVISIBLE : View.VISIBLE);
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

    /**
     * This method is still overridden to handle any case where the done action might be triggered,
     * even though we've hidden the done button and are using our custom Start button.
     */
    @Override
    protected void onDonePressed(Fragment currentFragment) {
        super.onDonePressed(currentFragment);
        finishOnboarding();
    }
    
    public void finishOnboarding() {
        // Mark onboarding as completed
        SharedPreferencesManager sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
        sharedPreferencesManager.sharedPreferences.edit().putBoolean(com.fadcam.Constants.COMPLETED_ONBOARDING_KEY, true).apply();
        
        // Return to MainActivity or just finish
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }

    /**
     * Creates a continuous white shimmer effect on a TextView.
     * @param textView The TextView to apply the shimmer effect to.
     */
    private void startShimmerEffect(final TextView textView) {
        if (textView == null || !textView.isAttachedToWindow()) return;
        
        // Make the text significantly darker to increase contrast with the shimmer
        final int originalTextColor = textView.getCurrentTextColor();
        // Darken the text color more for better shimmer contrast (0.5f makes it 50% darker)
        int darkerTextColor = darkenColor(originalTextColor, 0.5f);
        textView.setTextColor(darkerTextColor);
        
        // Create a new ValueAnimator for the shimmer effect
        final ValueAnimator shimmerAnimator = ValueAnimator.ofFloat(0f, 2 * (float)Math.PI); // Full sine wave cycle
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
            if (text.isEmpty()) return; // Skip if no text
            
            android.text.SpannableString spannableString = new android.text.SpannableString(text);
            
            // Create a very bright white for shimmer highlight - pure white
            int brightWhite = android.graphics.Color.argb(255, 255, 255, 255);
            
            // Use sine function for smooth, continuous shimmer position
            // This creates a wave-like effect that smoothly moves across the text
            // and naturally loops without visible jumps
            float shimmerCenter = (float)(Math.sin(animatedValue) + 1) / 2; // Convert sine (-1 to 1) to 0-1 range
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
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    );
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
     * Factor should be between 0 and 1, where 0 makes the color black and 1 keeps it unchanged.
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
        int r = Math.round(android.graphics.Color.red(color1) * inverseRatio + android.graphics.Color.red(color2) * ratio);
        int g = Math.round(android.graphics.Color.green(color1) * inverseRatio + android.graphics.Color.green(color2) * ratio);
        int b = Math.round(android.graphics.Color.blue(color1) * inverseRatio + android.graphics.Color.blue(color2) * ratio);
        return android.graphics.Color.rgb(r, g, b);
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