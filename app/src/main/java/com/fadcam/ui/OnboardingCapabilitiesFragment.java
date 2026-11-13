package com.fadcam.ui;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.GridLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.fadcam.BuildConfig;
import com.fadcam.ui.animations.AnimationView;
import com.fadcam.ui.animations.SceneStyle;
import com.fadcam.ui.animations.PayoffFlowView;
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
    private MiniAppsMarqueeView miniAppsMarquee;
    private PayoffFlowView payoffFlow;

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
        setPayoff(view, lite ? 3 : 10);
        TextView liteNote = view.findViewById(R.id.onboarding_lite_note);
        if (liteNote != null && lite) liteNote.setVisibility(View.VISIBLE);

        miniAppsMarquee = view.findViewById(R.id.onboarding_mini_marquee);
        if (miniAppsMarquee != null) {
            List<String> comingSoon = MiniAppsCatalog.comingSoonTitles(requireContext());
            if (comingSoon.isEmpty()) {
                miniAppsMarquee.setVisibility(View.GONE);
                View comingLabel = view.findViewById(R.id.onboarding_mini_coming_label);
                if (comingLabel != null) comingLabel.setVisibility(View.GONE);
            } else {
                miniAppsMarquee.setItems(comingSoon);
            }
        }
        // The cards already frame the preview; the stage corner ticks repeated the
        // screen chrome four times and ate space the illustration needs.
        SceneStyle quietStage = SceneStyle.defaults().toBuilder()
                .cornerTicks(false)
                // A touch lighter than the card surface, so the preview reads as a
                // window inside the card instead of a black hole in it.
                .background(0xFF0E0F0F)
                .line(0xFF3A3D3F)
                .dim(0xFF6E7273)
                .build();
        List<AnimationView> previews = new ArrayList<>();
        collectDemos(view, previews);
        for (AnimationView preview : previews) preview.setStyle(quietStage);

        payoffFlow = view.findViewById(R.id.onboarding_payoff_flow);
        if (payoffFlow != null) {
            payoffFlow.setIconMetrics(
                    getResources().getDimension(R.dimen.onboarding_payoff_icon_size),
                    getResources().getDimension(R.dimen.onboarding_payoff_icon_margin));
            payoffFlow.setIcon(BitmapFactory.decodeResource(
                    getResources(), R.drawable.payoff_icon));
        }

        scrollView = view.findViewById(R.id.onboarding_scroll);
        if (scrollView != null) {
            scrollView.setOnScrollChangeListener((v, x, y, oldX, oldY) ->
                    reportScrollState());
        }
        view.post(this::reportScrollState);
    }

    /** Called by the host when this page becomes the current slide. */
    public void onPageBecameVisible() {
        View view = getView();
        if (view == null) return;
        view.post(() -> {
            // A re-entry starts at the top, exactly like a fresh visit to this slide.
            if (!started && scrollView != null) scrollView.scrollTo(0, 0);
            reportScrollState();
            playShowcase();
            if (payoffFlow != null) payoffFlow.setActive(true);
            if (miniAppsMarquee != null && miniAppsMarquee.isShown()) {
                miniAppsMarquee.startMarquee();
            }
        });
    }

    /** Stops previews before the page leaves the screen and rewinds them for the next visit. */
    public void onPageHidden() {
        if (payoffFlow != null) payoffFlow.setActive(false);
        stopShowcase();
        if (miniAppsMarquee != null) miniAppsMarquee.stopMarquee();
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
                R.id.onboarding_feature_motion, R.id.onboarding_feature_forensic,
                R.id.onboarding_feature_remote};
        for (int id : fullOnly) {
            View card = root.findViewById(id);
            if (card != null) card.setVisibility(View.GONE);
        }

        View mini = root.findViewById(R.id.onboarding_mini_section);
        if (mini != null) mini.setVisibility(View.GONE);

        // Lite counts its jobs separately: background, bodycam, dashcam.

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
        if (payoffFlow != null) payoffFlow.setActive(false);
        List<AnimationView> demos = new ArrayList<>();
        collectDemos(root, demos);
        for (AnimationView demo : demos) {
            demo.setActive(false);
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

        List<AnimationView> demos = new ArrayList<>();
        collectDemos(root, demos);
        long base = animationsEnabled() ? 520L : 0L;
        for (int i = 0; i < demos.size(); i++) {
            AnimationView demo = demos.get(i);
            demo.setActive(true);
            demo.setAmbientOffset(i * 0.11f);
            // Each preview ignores the ambient phase until its own reveal settles,
            // so the loop takes over with no dead gap.
            demo.setAmbientEnabled(animationsEnabled());
            demo.playOnce(base + i * 120L);
        }
    }

    @Override
    public void onDestroyView() {
        if (miniAppsMarquee != null) miniAppsMarquee.stopMarquee();
        miniAppsMarquee = null;
        payoffFlow = null;
        super.onDestroyView();
    }

    private void collectCards(View view, List<View> out) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view.getId() == R.id.onboarding_feature_background
                || view.getId() == R.id.onboarding_feature_screen
                || view.getId() == R.id.onboarding_feature_editor
                || view.getId() == R.id.onboarding_feature_motion
                || view.getId() == R.id.onboarding_feature_forensic
                || view.getId() == R.id.onboarding_feature_remote
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

    private void collectDemos(View view, List<AnimationView> out) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof AnimationView) {
            out.add((AnimationView) view);
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
