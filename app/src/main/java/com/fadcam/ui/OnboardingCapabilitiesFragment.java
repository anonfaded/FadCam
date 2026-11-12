package com.fadcam.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.GridLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.fadcam.BuildConfig;
import com.fadcam.R;
import com.github.appintro.SlidePolicy;

import java.util.ArrayList;
import java.util.List;

/**
 * Slide 02: the multimedia suite. Owns the one-shot card reveal and the
 * miniature product previews, so animations only start when the page is
 * actually visible and never accumulate across page visits.
 */
public class OnboardingCapabilitiesFragment extends Fragment implements SlidePolicy {

    /** Host hooks for the slide-02 scroll gate. */
    public interface Host {
        void onCapabilitiesScrollState(boolean atBottom);

        /** A forward swipe was refused because the showcase has not been read yet. */
        void onCapabilitiesScrollBlocked();
    }

    private boolean started;
    private boolean lite;
    private Host host;
    private ScrollView scrollView;
    private boolean lastAtBottom;
    private ValueAnimator ambientAnimator;
    private List<OnboardingDemoView> ambientDemos;
    private final Runnable startAmbientRunnable = this::startAmbient;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof Host) host = (Host) context;
    }

    @Override
    public void onDetach() {
        host = null;
        scrollView = null;
        super.onDetach();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.onboarding_capabilities_slide, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        lite = BuildConfig.LITE_EDITION;
        started = false;
        applyEdition(view);
        setPayoff(view, lite ? 3 : 8);
        TextView liteNote = view.findViewById(R.id.onboarding_lite_note);
        if (liteNote != null && lite) liteNote.setVisibility(View.VISIBLE);
        scrollView = view.findViewById(R.id.onboarding_scroll);
        if (scrollView != null) {
            scrollView.setOnScrollChangeListener((v, x, y, oldX, oldY) -> reportScrollState());
        }
        view.post(this::reportScrollState);
    }

    /** Called by the host when this page becomes the current slide. */
    public void onPageBecameVisible() {
        View view = getView();
        if (view == null) return;
        view.post(() -> {
            reportScrollState();
            playShowcase();
        });
    }

    /** Stops previews before the page leaves the screen and rewinds them for the next visit. */
    public void onPageHidden() {
        stopShowcase();
        started = false;
    }

    @Override
    public boolean isPolicyRespected() {
        // Same gate as the dock arrow: the payoff must be read before moving on.
        return isAtBottom();
    }

    @Override
    public void onUserIllegallyRequestedNextPage() {
        if (host != null) host.onCapabilitiesScrollBlocked();
    }

    /** True when the payoff is close enough to be considered seen. */
    public boolean isAtBottom() {
        if (scrollView == null) return false;
        View child = scrollView.getChildAt(0);
        if (child == null) return false;
        if (scrollView.getHeight() == 0 || child.getMeasuredHeight() == 0) return false;
        // The payoff must clear the floating dock before the next arrow appears.
        int threshold = dp(16);
        return scrollView.getScrollY() + scrollView.getHeight() + threshold
                >= child.getMeasuredHeight();
    }

    /** Tapping the dock's MORE cue advances the showcase by most of a screen. */
    public void scrollDown() {
        if (scrollView == null) return;
        scrollView.smoothScrollBy(0, scrollView.getHeight() * 3 / 4);
    }

    private void reportScrollState() {
        if (scrollView == null) return;
        boolean atBottom = isAtBottom();
        if (atBottom == lastAtBottom) return;
        lastAtBottom = atBottom;
        if (host != null) host.onCapabilitiesScrollState(atBottom);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void applyEdition(View root) {
        if (!lite) return;

        int[] fullOnly = {R.id.onboarding_feature_screen, R.id.onboarding_feature_editor,
                R.id.onboarding_feature_motion, R.id.onboarding_feature_remote};
        for (int id : fullOnly) {
            View card = root.findViewById(id);
            if (card != null) card.setVisibility(View.GONE);
        }

        View mini = root.findViewById(R.id.onboarding_mini_section);
        if (mini != null) mini.setVisibility(View.GONE);

        // Lite counts its jobs separately: background, bodycam, dashcam.
        View combined = root.findViewById(R.id.onboarding_feature_bodycam);
        if (combined != null) combined.setVisibility(View.GONE);
        View bodycam = root.findViewById(R.id.onboarding_feature_bodycam_solo);
        if (bodycam != null) bodycam.setVisibility(View.VISIBLE);
        View dashcam = root.findViewById(R.id.onboarding_feature_dashcam);
        if (dashcam != null) dashcam.setVisibility(View.VISIBLE);

        GridLayout grid = root.findViewById(R.id.onboarding_feature_grid);
        if (grid != null) grid.setColumnCount(1);

    }

    private void setPayoff(View root, int count) {
        TextView title = root.findViewById(R.id.onboarding_payoff_title);
        if (title == null) return;
        String text = getString(R.string.onboarding_payoff_title, count);
        SpannableString styled = new SpannableString(text);
        int gold = ContextCompat.getColor(requireContext(), R.color.pro_gold);
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) {
                start = i;
                break;
            }
        }
        if (start >= 0) {
            int end = start;
            while (end < text.length() && Character.isDigit(text.charAt(end))) end++;
            styled.setSpan(new ForegroundColorSpan(gold), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        int brand = text.indexOf("FADCAM");
        if (brand >= 0) {
            styled.setSpan(new ForegroundColorSpan(gold), brand, brand + 6,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        title.setText(styled);
    }

    private void stopShowcase() {
        View root = getView();
        if (root == null) return;
        root.removeCallbacks(startAmbientRunnable);
        if (ambientAnimator != null) {
            ambientAnimator.cancel();
            ambientAnimator = null;
        }
        ambientDemos = null;
        List<OnboardingDemoView> demos = new ArrayList<>();
        collectDemos(root, demos);
        for (OnboardingDemoView demo : demos) {
            demo.resetDemo();
        }
        List<View> cards = new ArrayList<>();
        collectCards(root, cards);
        for (View card : cards) {
            card.animate().cancel();
            card.setAlpha(1f);
            card.setTranslationY(0f);
        }
    }

    private void playShowcase() {
        View root = getView();
        if (root == null || started || !isAdded()) return;
        started = true;

        List<View> cards = new ArrayList<>();
        collectCards(root, cards);
        animateCards(cards);

        List<OnboardingDemoView> demos = new ArrayList<>();
        collectDemos(root, demos);
        long base = animationsEnabled() ? 520L : 0L;
        for (int i = 0; i < demos.size(); i++) {
            demos.get(i).playOnce(base + i * 120L);
        }
        if (animationsEnabled()) {
            // Start the ambient clock with the reveal. Each view ignores the phase until
            // its own one-shot settles, so the loop takes over seamlessly with no dead gap.
            root.removeCallbacks(startAmbientRunnable);
            root.postDelayed(startAmbientRunnable, base + 60L);
        }
    }

    private void startAmbient() {
        if (!isAdded() || !animationsEnabled()) return;
        View root = getView();
        if (root == null || ambientAnimator != null) return;
        List<OnboardingDemoView> demos = new ArrayList<>();
        collectDemos(root, demos);
        if (demos.isEmpty()) return;
        ambientDemos = demos;
        for (int i = 0; i < demos.size(); i++) {
            demos.get(i).setAmbientOffset(i * 0.11f);
        }
        ambientAnimator = ValueAnimator.ofFloat(0f, 1f);
        ambientAnimator.setDuration(6000L);
        ambientAnimator.setRepeatCount(ValueAnimator.INFINITE);
        ambientAnimator.setRepeatMode(ValueAnimator.RESTART);
        ambientAnimator.setInterpolator(new LinearInterpolator());
        ambientAnimator.addUpdateListener(a -> {
            float phase = (float) a.getAnimatedValue();
            if (ambientDemos == null) return;
            for (OnboardingDemoView demo : ambientDemos) {
                demo.setAmbientPhase(phase);
            }
        });
        ambientAnimator.start();
    }

    @Override
    public void onDestroyView() {
        View root = getView();
        if (root != null) root.removeCallbacks(startAmbientRunnable);
        if (ambientAnimator != null) {
            ambientAnimator.cancel();
            ambientAnimator = null;
        }
        ambientDemos = null;
        super.onDestroyView();
    }

    private void collectCards(View view, List<View> out) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view.getId() == R.id.onboarding_feature_background
                || view.getId() == R.id.onboarding_feature_screen
                || view.getId() == R.id.onboarding_feature_editor
                || view.getId() == R.id.onboarding_feature_motion
                || view.getId() == R.id.onboarding_feature_remote
                || view.getId() == R.id.onboarding_feature_bodycam
                || view.getId() == R.id.onboarding_feature_bodycam_solo
                || view.getId() == R.id.onboarding_feature_dashcam
                || view.getId() == R.id.onboarding_mini_flashlight
                || view.getId() == R.id.onboarding_mini_qr
                || view.getId() == R.id.onboarding_payoff) {
            out.add(view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectCards(group.getChildAt(i), out);
            }
        }
    }

    private void collectDemos(View view, List<OnboardingDemoView> out) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof OnboardingDemoView) {
            out.add((OnboardingDemoView) view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectDemos(group.getChildAt(i), out);
            }
        }
    }

    private void animateCards(List<View> cards) {
        if (cards.isEmpty()) return;
        if (!animationsEnabled()) return;
        float dp = getResources().getDisplayMetrics().density;
        for (int i = 0; i < cards.size(); i++) {
            View card = cards.get(i);
            card.setAlpha(0f);
            card.setTranslationY(10f * dp);
            card.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(i * 80L)
                    .setDuration(480L)
                    .setInterpolator(new PathInterpolator(0.16f, 1f, 0.3f, 1f))
                    .start();
        }
    }

    private boolean animationsEnabled() {
        try {
            float scale = Settings.Global.getFloat(requireContext().getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            return scale != 0f;
        } catch (Exception ignored) {
            return true;
        }
    }
}
