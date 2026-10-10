package com.fadcam.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.fadcam.BuildConfig;
import com.fadcam.FLog;
import com.fadcam.R;
import com.fadcam.ui.widgets.ProBundleShowcaseView;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Product comparison and purchase information. Ownership always comes from this APK's tier. */
public class FadCamProActivity extends AppCompatActivity {
    private static final String PATREON_SHOP_URL = "https://www.patreon.com/cw/Fadedx/shop";
    private static final String DISCORD_URL = "https://discord.gg/kvAZvdkuuN";
    private static final String INCLUDED = "check";
    private static final String ABSENT = "remove";
    private static final String SOON = "soon";
    /** Feature column keeps most of the width; tier columns share the rest equally. */

    private static final class Tier {
        final String label;
        final boolean owned;
        final boolean highlighted;
        /** Tier not purchasable yet: its whole column reads dimmed until it is available. */
        final boolean comingSoon;
        Tier(String label, boolean owned, boolean highlighted, boolean comingSoon) {
            this.label = label;
            this.owned = owned;
            this.highlighted = highlighted;
            this.comingSoon = comingSoon;
        }
    }

    private static final class Feature {
        final int iconRes;
        final int glyph;
        final int name;
        final int description;
        final String[] statuses;
        boolean accent;
        Feature(int iconRes, int glyph, int name, int description, String... statuses) {
            this.iconRes = iconRes;
            this.glyph = glyph;
            this.name = name;
            this.description = description;
            this.statuses = statuses;
        }
    }

    private Tier[] tiers;
    private Feature[] features;
    private boolean isOwner;
    private String ownerTierLabel;
    private LinearLayout tableHeaderRow;
    private LinearLayout stickyTableHeaderRow;
    private android.widget.HorizontalScrollView stickyHeaderScroll;
    private View stickyHeaderWrap;
    private boolean syncingHeaderScroll;
    private LinearLayout featuresContainer;
    private LinearLayout priceRow;
    private ShimmerButton btnGetPro;
    private Typeface iconTypeface;
    private View.OnLayoutChangeListener comparisonLayoutListener;
    private View scrollHint;
    private View scrollHintArrow;
    private View scrollTopButton;
    private android.animation.ValueAnimator scrollAnimator;
    private android.animation.ValueAnimator hintBounce;
    private boolean firstFrameDrawn;
    private final Handler animationHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private boolean resumed;
    private final Runnable blink = this::blinkOnce;
    private Animatable2.AnimationCallback blinkCallback;
    private static final int AVATAR_HEADER = 0;
    private static final int AVATAR_MOVING_TO_CTA = 1;
    private static final int AVATAR_CTA = 2;
    private static final int AVATAR_MOVING_TO_HEADER = 3;
    private View avatarOverlayHost;
    private View avatarPoseFrame;
    private View avatarUnit;
    private ImageView avatarOverlay;
    private int avatarState = AVATAR_HEADER;
    private android.animation.ValueAnimator avatarTravelAnimator;
    private final Runnable glance = this::glanceOnce;
    private Animatable2.AnimationCallback glanceCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        // Some OEM window managers (observed on Android 11 devices) keep the window frame
        // fitted below the status bar even after the bar is hidden, leaving a black band at the
        // top. NO_LIMITS forces the surface to span the whole display; insets are applied
        // manually to the root view below.
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        // Some OEM window managers (observed on Android 11 devices) still fit app windows below
        // the status bar. A non-default cutout mode is the usual OEM lever that lets the surface
        // reach the top of the display.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            android.view.WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
        }
        // A one-alpha black surface is visually transparent, while preventing Android's
        // transient-bar contrast scrim from tinting the page when the user swipes down.
        getWindow().setStatusBarColor(android.graphics.Color.argb(1, 0, 0, 0));
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            getWindow().setStatusBarContrastEnforced(false);
        }
        getWindow().setNavigationBarColor(ContextCompat.getColor(this, R.color.pro_background));
        getWindow().setBackgroundDrawableResource(R.color.pro_background);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(false);
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        setContentView(R.layout.activity_fadcam_pro);
        View root = findViewById(R.id.rootContainer);
        View closeButton = findViewById(R.id.btnBack);
        // The layered glass background has no intrinsic outline; keep the drop shadow round.
        closeButton.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            // Keep interactive content below the status-bar/cutout safe area even while the bar
            // is hidden. The hero art flows under the bar: the hero frame grows by the safe
            // inset so the crown rain starts at the screen top while its content stays below.
            Insets safe = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, 0, bars.right, bars.bottom);
            if (closeButton != null) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) closeButton.getLayoutParams();
                int top = getResources().getDimensionPixelSize(R.dimen.pro_close_margin) + safe.top;
                if (lp.topMargin != top) {
                    lp.topMargin = top;
                    closeButton.setLayoutParams(lp);
                }
            }
            View heroFrame = findViewById(R.id.heroFrame);
            if (heroFrame != null) {
                ViewGroup.LayoutParams flp = heroFrame.getLayoutParams();
                int height = getResources().getDimensionPixelSize(R.dimen.pro_hero_frame_h) + safe.top;
                if (flp.height != height) {
                    flp.height = height;
                    heroFrame.setLayoutParams(flp);
                }
            }
            View avatar = findViewById(R.id.ivAvatar);
            if (avatar != null) {
                android.widget.FrameLayout.LayoutParams alp =
                        (android.widget.FrameLayout.LayoutParams) avatar.getLayoutParams();
                if (alp.topMargin != safe.top) {
                    alp.topMargin = safe.top;
                    avatar.setLayoutParams(alp);
                }
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        // Reconcile after any layout shift so the avatar can never sit on stale coordinates.
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> syncAvatarState());
        // Keep the normal first frame stable, then hide the transparent status bar on the next frame.
        root.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                firstFrameDrawn = true;
                root.postOnAnimation(() -> {
                    if (!isFinishing()) {
                        hideStatusBar(root);
                    }
                });
                return true;
            }
        });
        tableHeaderRow = findViewById(R.id.tableHeaderRow);
        stickyTableHeaderRow = findViewById(R.id.stickyTableHeaderRow);
        stickyHeaderScroll = findViewById(R.id.stickyHeaderScroll);
        stickyHeaderWrap = findViewById(R.id.stickyHeaderWrap);
        featuresContainer = findViewById(R.id.featuresContainer);
        priceRow = findViewById(R.id.priceRow);
        btnGetPro = findViewById(R.id.btnGetPro);
        scrollHint = findViewById(R.id.scrollHint);
        scrollHintArrow = findViewById(R.id.scrollHintArrow);
        android.widget.ScrollView proScroll = findViewById(R.id.proScroll);
        scrollTopButton = findViewById(R.id.btnScrollTop);
        if (scrollTopButton != null) {
            scrollTopButton.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            scrollTopButton.setOnClickListener(v -> animateScroll(proScroll, 0));
        }
        // The animated cue sits above the fixed footer; reserved content padding keeps the final
        // section clear of it when the scroll reaches the end.
        scrollHint.setOnClickListener(v -> animateScrollToTable(proScroll));
        proScroll.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && scrollAnimator != null) {
                scrollAnimator.cancel();
            }
            return false;
        });
        proScroll.setOnScrollChangeListener((View v, int x, int y, int oldX, int oldY) -> {
            updateScrollHint(y, proScroll.getHeight(), proScroll.getChildAt(0).getHeight());
            updateStickyHeaderVisibility();
        });
        proScroll.post(() -> {
            updateScrollHint(proScroll.getScrollY(), proScroll.getHeight(), proScroll.getChildAt(0).getHeight());
            updateStickyHeaderVisibility();
        });
        android.widget.HorizontalScrollView comparisonScroll = findViewById(R.id.comparisonScroll);
        comparisonScroll.setOnScrollChangeListener((View v, int x, int y, int oldX, int oldY) -> {
            if (!syncingHeaderScroll) {
                syncingHeaderScroll = true;
                stickyHeaderScroll.scrollTo(x, 0);
                syncingHeaderScroll = false;
            }
        });
        stickyHeaderScroll.setOnScrollChangeListener((View v, int x, int y, int oldX, int oldY) -> {
            if (!syncingHeaderScroll) {
                syncingHeaderScroll = true;
                comparisonScroll.scrollTo(x, 0);
                syncingHeaderScroll = false;
            }
        });
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnDiscord).setOnClickListener(v -> openLink(DISCORD_URL));
        // In short windows or with very large text, a pinned footer would consume the viewport.
        // Keep every action reachable by letting it travel with the rest of the page instead.
        if (getResources().getConfiguration().screenHeightDp < 480
                || getResources().getConfiguration().fontScale >= 1.5f) {
            LinearLayout footer = findViewById(R.id.footerBar);
            ((ViewGroup) footer.getParent()).removeView(footer);
            android.widget.ScrollView scroll = findViewById(R.id.proScroll);
            ((LinearLayout) scroll.getChildAt(0)).addView(footer,
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
            footer.setPadding(0, dp(20), 0, 0);
            ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) scroll.getLayoutParams();
            params.bottomToTop = ConstraintLayout.LayoutParams.UNSET;
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
            scroll.setLayoutParams(params);
            ConstraintLayout.LayoutParams hintParams = (ConstraintLayout.LayoutParams) scrollHint.getLayoutParams();
            hintParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
            hintParams.bottomToTop = ConstraintLayout.LayoutParams.UNSET;
            hintParams.bottomMargin = getResources().getDimensionPixelSize(R.dimen.pro_scroll_hint_margin);
            scrollHint.setLayoutParams(hintParams);
            LinearLayout content = (LinearLayout) scroll.getChildAt(0);
            content.setPadding(content.getPaddingLeft(), content.getPaddingTop(), content.getPaddingRight(), dp(24));
            scroll.setPadding(scroll.getPaddingLeft(), scroll.getPaddingTop(), scroll.getPaddingRight(), dp(54));
        }
        configureScreen(BuildConfig.TIER);
        if (motionAllowed()) {
            View hero = findViewById(R.id.heroSection);
            hero.setAlpha(0f);
            hero.setTranslationY(dp(8));
            hero.animate().alpha(1f).translationY(0f).setDuration(240)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    /** Private binding boundary also allows tests to exercise every APK tier without a UI override. */
    private void configureScreen(String tier) {
        isOwner = "PRO".equals(tier) || "PRO_PLUS".equals(tier);
        ownerTierLabel = getString("PRO_PLUS".equals(tier)
                ? R.string.pro_screen_tier_plus : R.string.pro_screen_tier);
        tiers = buildTiers(tier);
        features = buildFeatures();
        ((TextView) findViewById(R.id.tvProTitle)).setText(isOwner
                ? getString(R.string.pro_screen_owned_title, ownerTierLabel)
                : getString(R.string.pro_screen_title));
        TextView subtitle = findViewById(R.id.tvProSubtitle);
        if (isOwner) {
            subtitle.setText(R.string.pro_screen_owned_subtitle);
        } else {
            subtitle.setText(styledClaimTitle());
        }
        findViewById(R.id.tvOwnedBadge).setVisibility(isOwner ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.tvEyebrowCompare)).setText(isOwner
                ? R.string.pro_screen_compare_owned : R.string.pro_screen_compare);
        ProBundleShowcaseView showcase = findViewById(R.id.bundleShowcase);
        if (showcase != null) {
            showcase.configure(getString(R.string.pro_showcase_label),
                    getString(R.string.pro_screen_bundle),
                    getString(R.string.pro_showcase_supporting),
                    getString(R.string.pro_showcase_meta));
            showcase.setCinematicHost(findViewById(R.id.cinematicOverlay));
        }
        TextView note = findViewById(R.id.tvNote);
        note.setText(linkFadSecLab(getString(isOwner
                ? R.string.pro_screen_owned_note : R.string.pro_screen_support_note)));
        note.setMovementMethod(LinkMovementMethod.getInstance());
        note.setHighlightColor(android.graphics.Color.TRANSPARENT);
        priceRow.setVisibility(isOwner ? View.GONE : View.VISIBLE);
        btnGetPro.setVisibility(isOwner ? View.GONE : View.VISIBLE);
        if (isOwner) btnGetPro.stopShimmer(); else btnGetPro.startShimmer();
        btnGetPro.setOnClickListener(isOwner ? null : v -> openLink(PATREON_SHOP_URL));
        avatarOverlayHost = findViewById(R.id.avatarOverlayHost);
        avatarPoseFrame = findViewById(R.id.avatarPoseFrame);
        avatarUnit = findViewById(R.id.avatarUnit);
        avatarOverlay = findViewById(R.id.ivAvatarOverlay);
        if (avatarOverlay != null) {
            avatarOverlay.setClickable(false);
            avatarOverlay.setFocusable(false);
            // Tiny reaction when the CTA is pressed; never consumes the touch.
            btnGetPro.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
                        && avatarState == AVATAR_CTA && avatarOverlayHost != null
                        && avatarOverlayHost.getVisibility() == View.VISIBLE
                        && motionAllowed()) {
                    avatarUnit.animate().cancel();
                    avatarUnit.animate().translationY(dp(2)).setDuration(90)
                            .withEndAction(() -> {
                                if (avatarUnit != null) {
                                    avatarUnit.animate().translationY(0f).setDuration(170)
                                            .setInterpolator(new DecelerateInterpolator()).start();
                                }
                            }).start();
                }
                return false;
            });
        }
        findViewById(R.id.purchaseCaption).setVisibility(isOwner ? View.GONE : View.VISIBLE);
        TextView ownedStatus = findViewById(R.id.ownedStatus);
        ownedStatus.setText(getString(R.string.pro_screen_owned_status, ownerTierLabel));
        ownedStatus.setVisibility(isOwner ? View.VISIBLE : View.GONE);

        // The table always fits the screen: fixed-width tier columns keep every edition readable
        // without horizontal scrolling.
        View tableContent = findViewById(R.id.tableContent);
        View comparison = findViewById(R.id.comparisonScroll);
        tableContent.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
        findViewById(R.id.comparisonHint).setVisibility(View.GONE);
        if (comparisonLayoutListener != null) comparison.removeOnLayoutChangeListener(comparisonLayoutListener);
        comparisonLayoutListener = (v, l, t, r, b, ol, ot, or, ob) -> {
            int viewportWidth = r - l;
            if (viewportWidth > 0) {
                if (tableContent.getLayoutParams().width != viewportWidth) {
                    tableContent.getLayoutParams().width = viewportWidth;
                    tableContent.requestLayout();
                }
                setStickyHeaderWidth(viewportWidth);
            }
        };
        comparison.addOnLayoutChangeListener(comparisonLayoutListener);
        if (comparison.getWidth() > 0) {
            tableContent.getLayoutParams().width = comparison.getWidth();
        } else {
            comparison.post(() -> {
                int width = comparison.getWidth();
                if (width > 0) {
                    tableContent.getLayoutParams().width = width;
                    tableContent.requestLayout();
                    setStickyHeaderWidth(width);
                }
            });
        }
        findViewById(R.id.soonLegend).setVisibility(View.VISIBLE);
        tableHeaderRow.removeAllViews();
        featuresContainer.removeAllViews();
        buildTable();
        // The pinned header must measure exactly like the inline header row, whatever the
        // container's measurement quirks are; sync its height to the real row once laid out.
        tableHeaderRow.post(() -> {
            int height = tableHeaderRow.getHeight();
            if (height > 0 && stickyHeaderScroll != null) {
                ViewGroup.LayoutParams lp = stickyHeaderScroll.getLayoutParams();
                if (lp.height != height) {
                    lp.height = height;
                    stickyHeaderScroll.setLayoutParams(lp);
                }
            }
        });
    }

    /** Animated scroll with a longer decelerating motion than the platform's quick jump. */
    private void animateScroll(android.widget.ScrollView proScroll, int target) {
        int from = proScroll.getScrollY();
        if (target == from) return;
        if (scrollAnimator != null) scrollAnimator.cancel();
        scrollAnimator = android.animation.ValueAnimator.ofInt(from, target);
        scrollAnimator.setDuration(650L);
        scrollAnimator.setInterpolator(new DecelerateInterpolator(1.6f));
        scrollAnimator.addUpdateListener(a -> proScroll.scrollTo(0, (int) a.getAnimatedValue()));
        scrollAnimator.start();
    }

    /** Scrolls to the table, stopping exactly where the pinned header lands at its pin position,
     *  so the first row is still untouched at the top. */
    private void animateScrollToTable(android.widget.ScrollView proScroll) {
        View sectionTitle = findViewById(R.id.tvEyebrowCompare);
        View closeButton = findViewById(R.id.btnBack);
        int[] titleLocation = new int[2];
        int[] closeLocation = new int[2];
        sectionTitle.getLocationOnScreen(titleLocation);
        closeButton.getLocationOnScreen(closeLocation);
        int pinTop = closeLocation[1] + closeButton.getHeight()
                + getResources().getDimensionPixelSize(R.dimen.pro_sticky_header_gap);
        int target = proScroll.getScrollY() + titleLocation[1] - pinTop;
        if (target <= proScroll.getScrollY()) return;
        animateScroll(proScroll, target);
    }

    /** Bottom-right shortcut that only appears once the table header pins, so it cannot be hit
     *  by accident while browsing the top of the screen. */
    private void updateScrollTopButton(boolean shouldShow) {
        if (scrollTopButton == null) return;
        if (shouldShow && scrollTopButton.getVisibility() != View.VISIBLE) {
            scrollTopButton.setVisibility(View.VISIBLE);
            scrollTopButton.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start();
        } else if (!shouldShow && scrollTopButton.getVisibility() == View.VISIBLE) {
            scrollTopButton.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(150)
                    .withEndAction(() -> {
                        if (scrollTopButton != null) scrollTopButton.setVisibility(View.GONE);
                    }).start();
        }
    }

    private void updateScrollHint(int scrollY, int viewportHeight, int contentHeight) {
        if (scrollHint == null) return;
        boolean canScroll = contentHeight > viewportHeight + dp(8);
        boolean atTop = scrollY <= dp(4);
        boolean show = canScroll && atTop;
        if (!motionAllowed()) {
            scrollHint.animate().cancel();
            scrollHint.setAlpha(show ? 1f : 0f);
            scrollHint.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
            if (show) startHintBounce(); else stopHintBounce();
            return;
        }
        if (show) {
            if (scrollHint.getVisibility() != View.VISIBLE) scrollHint.setVisibility(View.VISIBLE);
            startHintBounce();
            if (scrollHint.getAlpha() != 1f) {
                scrollHint.animate().cancel();
                scrollHint.animate().alpha(1f).setDuration(180)
                        .setInterpolator(new DecelerateInterpolator()).start();
            }
        } else if (scrollHint.getVisibility() == View.VISIBLE) {
            scrollHint.animate().alpha(0f).setDuration(180)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> {
                        if (scrollHint.getAlpha() == 0f) {
                            scrollHint.setVisibility(View.INVISIBLE);
                            stopHintBounce();
                        }
                    }).start();
        }
    }

    private CharSequence styledClaimTitle() {
        String text = getString(R.string.pro_screen_claim_title);
        SpannableString styled = new SpannableString(text);
        int at = text.indexOf("#1");
        if (at >= 0) {
            styled.setSpan(new ForegroundColorSpan(color(R.color.pro_check_gold)), at, at + 2,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(new TypefaceSpan("sans-serif-black"), at, at + 2,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(new RelativeSizeSpan(1.08f), at, at + 2,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return styled;
    }

    private CharSequence linkFadSecLab(String text) {
        SpannableString styled = new SpannableString(text);
        int start = text.indexOf("FadSec Lab");
        if (start >= 0) {
            styled.setSpan(new ClickableSpan() {
                @Override public void onClick(View widget) {
                    openLink("https://fadseclab.com");
                }

                @Override public void updateDrawState(TextPaint paint) {
                    paint.setColor(color(R.color.pro_brand_red));
                    paint.setUnderlineText(false);
                }
            }, start, start + "FadSec Lab".length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return styled;
    }

    private void startHintBounce() {
        if (!motionAllowed() || hintBounce != null || scrollHintArrow == null) return;
        hintBounce = android.animation.ValueAnimator.ofFloat(0f, dp(4));
        hintBounce.setDuration(700);
        hintBounce.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        hintBounce.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        hintBounce.setInterpolator(new DecelerateInterpolator());
        hintBounce.addUpdateListener(a -> scrollHintArrow.setTranslationY((float) a.getAnimatedValue()));
        hintBounce.start();
    }

    private void stopHintBounce() {
        if (hintBounce == null) return;
        hintBounce.cancel();
        hintBounce = null;
        if (scrollHintArrow != null) scrollHintArrow.setTranslationY(0f);
    }

    private void updateStickyHeaderVisibility() {
        if (stickyHeaderWrap == null) return;
        // The whole title row is toggled (not just the text) so its prefix icon cannot float.
        View sectionTitle = findViewById(R.id.compareTitleRow);
        View tableCard = findViewById(R.id.comparisonScroll);
        View closeButton = findViewById(R.id.btnBack);
        int[] titleLocation = new int[2];
        int[] closeLocation = new int[2];
        int[] cardLocation = new int[2];
        int[] lastRowLocation = new int[2];
        sectionTitle.getLocationOnScreen(titleLocation);
        closeButton.getLocationOnScreen(closeLocation);
        tableCard.getLocationOnScreen(cardLocation);
        View lastTableRow = featuresContainer != null && featuresContainer.getChildCount() > 0
                ? featuresContainer.getChildAt(featuresContainer.getChildCount() - 1) : null;
        if (lastTableRow != null) lastTableRow.getLocationOnScreen(lastRowLocation);
        int stickyTop = closeLocation[1] + closeButton.getHeight()
                + getResources().getDimensionPixelSize(R.dimen.pro_sticky_header_gap);
        int blockHeight = stickyHeaderWrap.getHeight();
        int tableBottom = cardLocation[1] + tableCard.getHeight();
        View pageScroll = findViewById(R.id.proScroll);
        View hero = findViewById(R.id.heroSection);
        View bundle = findViewById(R.id.bundleShowcase);
        // Pin the moment the real section title reaches the spot below the close button; the
        // pinned copy renders at exactly that position, so nothing visibly jumps or duplicates.
        boolean show = titleLocation[1] <= stickyTop && tableBottom > stickyTop;
        if (stickyHeaderWrap.getVisibility() != (show ? View.VISIBLE : View.INVISIBLE)) {
            stickyHeaderWrap.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        }
        if (sectionTitle.getVisibility() != (show ? View.INVISIBLE : View.VISIBLE)) {
            sectionTitle.setVisibility(show ? View.INVISIBLE : View.VISIBLE);
        }
        // Clip the card at the pinned header's bottom edge: rows genuinely disappear under the
        // header instead of floating above it, without drawing any overlay on top of the table.
        if (show) {
            // While the title is pinned, the content above it (hero + bundle) must not scroll
            // away either: it is counter-translated so it visually freezes in the strip above
            // the header, and only the table rows keep moving.
            if (pageScroll != null && hero != null && bundle != null) {
                int scrollY = pageScroll.getScrollY();
                int pinScrollY = scrollY + (titleLocation[1] - stickyTop);
                int delta = scrollY - pinScrollY;
                int shift = 0;
                if (delta > 0) {
                    int maxShift = Math.max(0, (lastRowLocation[1] + delta) - (stickyTop + blockHeight));
                    shift = Math.min(delta, maxShift);
                }
                hero.setTranslationY(shift);
                bundle.setTranslationY(shift);
            }
            // The block stays pinned until the table's LAST row reaches it. From then on the last
            // row "attaches" to the block and the two move up together with the page, so no row
            // is ever swallowed and the header never rises over empty space.
            int push = Math.min(0, lastRowLocation[1] - (stickyTop + blockHeight));
            stickyHeaderWrap.setTranslationY(push);
            if (stickyHeaderWrap.getClipBounds() != null) {
                stickyHeaderWrap.setClipBounds(null);
            }
            int clipTop = stickyTop + push + blockHeight - cardLocation[1];
            if (clipTop > 0 && clipTop < tableCard.getHeight()) {
                android.graphics.Rect clip = new android.graphics.Rect(
                        0, clipTop, tableCard.getWidth(), tableCard.getHeight());
                if (!clip.equals(tableCard.getClipBounds())) tableCard.setClipBounds(clip);
            }
        } else {
            if (hero != null) hero.setTranslationY(0);
            if (bundle != null) bundle.setTranslationY(0);
            stickyHeaderWrap.setTranslationY(0);
            if (tableCard.getClipBounds() != null) tableCard.setClipBounds(null);
        }
        // The shortcut appears exactly when the table's title pins — same moment for animated
        // jumps and manual scrolling, so it is never too early or missed.
        if (pageScroll != null) {
            int pageScrollY = pageScroll.getScrollY();
            int pinScrollY = pageScrollY + (titleLocation[1] - stickyTop);
            updateScrollTopButton(pageScrollY >= pinScrollY - dp(4));
            syncAvatarState();
        }
    }

    private void setStickyHeaderWidth(int width) {
        if (stickyTableHeaderRow == null) return;
        ViewGroup.LayoutParams params = stickyTableHeaderRow.getLayoutParams();
        if (params.width != width) {
            params.width = width;
            stickyTableHeaderRow.setLayoutParams(params);
        }
    }

    private void hideStatusBar(View root) {
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        View decor = getWindow().getDecorView();
        int systemUiVisibility = decor.getSystemUiVisibility();
        decor.setSystemUiVisibility(systemUiVisibility
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .hide(WindowInsetsCompat.Type.statusBars());
        // Force a window relayout so the OEM window manager recomputes the frame with the bar
        // hidden; some OEM window managers keep the first layout below the status bar otherwise.
        getWindow().setAttributes(getWindow().getAttributes());
        ViewCompat.requestApplyInsets(root);
    }

    private Tier[] buildTiers(String tier) {
        // Both editions share one tier ladder: the Pro+ subscription covers FadCam and FadCam Lite.
        return new Tier[] {
            new Tier(getString(R.string.pro_screen_free), false, false, false),
            new Tier(getString(R.string.pro_screen_tier), "PRO".equals(tier), !isOwner || "PRO".equals(tier), false),
            new Tier(getString(R.string.pro_screen_tier_plus), "PRO_PLUS".equals(tier), "PRO_PLUS".equals(tier), true),
            new Tier(getString(R.string.pro_screen_tier_lab), false, false, true)
        };
    }

    private Feature[] buildFeatures() {
        List<Feature> rows = new ArrayList<>();
        rows.add(feature(R.mipmap.ic_launcher_notes, R.string.pro_glyph_notes, R.string.pro_screen_notes_feature, R.string.pro_screen_notes_desc, ABSENT, INCLUDED, INCLUDED, INCLUDED));
        rows.add(feature(R.mipmap.ic_launcher_calculator, R.string.pro_glyph_calc, R.string.pro_screen_calc_feature, R.string.pro_screen_calc_desc, ABSENT, INCLUDED, INCLUDED, INCLUDED));
        rows.add(feature(R.mipmap.ic_launcher_weather, R.string.pro_glyph_weather, R.string.pro_screen_weather_feature, R.string.pro_screen_weather_desc, ABSENT, INCLUDED, INCLUDED, INCLUDED));
        rows.add(feature(0, R.string.pro_glyph_updates, R.string.pro_screen_updates, R.string.pro_screen_updates_desc, ABSENT, INCLUDED, INCLUDED, INCLUDED));
        // Discord is the community support channel across editions.
        rows.add(feature(0, R.string.pro_glyph_support, R.string.pro_screen_direct_support, R.string.pro_screen_direct_support_desc, INCLUDED, INCLUDED, INCLUDED, INCLUDED));
        rows.add(feature(0, R.string.pro_glyph_everything, R.string.pro_screen_everything, R.string.pro_screen_everything_desc, INCLUDED, INCLUDED, INCLUDED, INCLUDED));
        rows.add(feature(0, R.string.pro_glyph_notification, R.string.pro_screen_notification, R.string.pro_screen_notification_desc, INCLUDED, INCLUDED, INCLUDED, ABSENT));
        rows.add(feature(0, R.string.pro_glyph_no_ads, R.string.pro_screen_no_ads, R.string.pro_screen_no_ads_desc, INCLUDED, INCLUDED, INCLUDED, INCLUDED));
        rows.add(feature(0, R.string.pro_glyph_no_nags, R.string.pro_screen_no_nags, R.string.pro_screen_no_nags_desc, ABSENT, INCLUDED, INCLUDED, INCLUDED));
        // Closing highlight of the included set, then the upcoming subscription features below it
        // so all checked rows read first and the planned ones group at the bottom.
        Feature fan = feature(0, R.string.pro_glyph_fan, R.string.pro_screen_fan_feature, R.string.pro_screen_fan_desc, ABSENT, INCLUDED, INCLUDED, INCLUDED);
        fan.accent = true;
        rows.add(fan);
        rows.add(feature(0, R.string.pro_glyph_custom_icon, R.string.pro_screen_custom_icon, R.string.pro_screen_custom_icon_desc, ABSENT, ABSENT, SOON, SOON));
        rows.add(feature(0, R.string.pro_glyph_custom_name, R.string.pro_screen_custom_name, R.string.pro_screen_custom_name_desc, ABSENT, ABSENT, SOON, SOON));
        rows.add(feature(0, R.string.pro_glyph_cloud, R.string.pro_screen_cloud, R.string.pro_screen_cloud_desc, ABSENT, ABSENT, SOON, SOON));
        return rows.toArray(new Feature[0]);
    }

    private Feature feature(int iconRes, int glyph, int name, int description, String free, String pro, String plus, String lab) {
        return new Feature(iconRes, glyph, name, description, new String[] {free, pro, plus, lab});
    }

    private void buildTable() {
        addTableHeader(tableHeaderRow);
        addTableHeader(stickyTableHeaderRow);
        LayoutInflater inflater = LayoutInflater.from(this);
        boolean skipDivider = false;
        for (Feature feature : features) {
            // The highlighted closing row floats without dividers above or below it.
            if (!feature.accent && !skipDivider) {
                View divider = new View(this);
                divider.setBackgroundColor(color(R.color.pro_border));
                LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                dividerParams.setMargins(dp(12), 0, dp(12), 0);
                featuresContainer.addView(divider, dividerParams);
            }
            skipDivider = feature.accent;
            View row = inflater.inflate(R.layout.item_pro_table_row, featuresContainer, false);
            TextView glyphView = row.findViewById(R.id.tvRowIcon);
            ImageView imageView = row.findViewById(R.id.ivRowImage);
            if (feature.iconRes != 0) {
                glyphView.setVisibility(View.GONE);
                imageView.setImageResource(feature.iconRes);
                imageView.setVisibility(View.VISIBLE);
            } else {
                glyphView.setText(feature.glyph);
            }
            ((TextView) row.findViewById(R.id.tvFeatureName)).setText(feature.name);
            ((TextView) row.findViewById(R.id.tvFeatureDesc)).setText(feature.description);
            if (feature.accent) {
                row.setBackgroundResource(R.drawable.bg_pro_row_accent);
                // The inset background would otherwise become the row's padding (8dp sides /
                // 4dp vertical), pulling the icon, title and checks off every other row's
                // columns. Restore the standard row padding after setting the background.
                row.setPaddingRelative(dp(12), dp(14), dp(12), dp(14));
                ((TextView) row.findViewById(R.id.tvFeatureName)).setTextColor(color(R.color.pro_check_gold));
            }
            LinearLayout statuses = row.findViewById(R.id.statusContainer);
            StringBuilder announcement = new StringBuilder(getString(feature.name)).append(". ")
                    .append(getString(feature.description));
            for (int t = 0; t < tiers.length; t++) {
                String status = feature.statuses[t];
                statuses.addView(buildStatusCell(status, tiers[t]));
                announcement.append(". ").append(getString(R.string.pro_screen_status_description,
                        tiers[t].label, getString(statusLabel(status))));
            }
            // One coherent TalkBack stop per row, never raw icon-font ligature names.
            ViewCompat.setScreenReaderFocusable(row, true);
            row.setContentDescription(announcement);
            for (int i = 0; i < ((ViewGroup) row).getChildCount(); i++) {
                ((ViewGroup) row).getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }
            featuresContainer.addView(row);
        }
        int lastIndex = featuresContainer.getChildCount() - 1;
        if (lastIndex >= 0) featuresContainer.getChildAt(lastIndex).setMinimumHeight(0);
    }

    private void addTableHeader(LinearLayout headerRow) {
        TextView heading = new TextView(this);
        heading.setText(R.string.pro_screen_features);
        heading.setTextSize(12);
        heading.setSingleLine(true);
        heading.setTextColor(color(R.color.pro_text_secondary));
        heading.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        headerRow.addView(heading);
        for (Tier tier : tiers) {
            TextView label = new TextView(this);
            label.setText(tier.owned ? tier.label + "\n" + getString(R.string.pro_screen_active) : tier.label);
            label.setTextSize(12);
            if (!tier.owned) label.setSingleLine(true);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            label.setGravity(Gravity.CENTER);
            label.setTextColor(color(tier.highlighted ? R.color.pro_gold : R.color.pro_text_secondary));
            if (tier.comingSoon && !tier.owned) label.setAlpha(0.45f);
            label.setLayoutParams(new LinearLayout.LayoutParams(
                    getResources().getDimensionPixelSize(R.dimen.pro_table_col_w),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            label.setPadding(0, dp(4), 0, dp(4));
            headerRow.addView(label);
        }
    }

    private View buildStatusCell(String status, Tier tier) {
        FrameLayout cell = new FrameLayout(this);
        cell.setLayoutParams(new LinearLayout.LayoutParams(
                getResources().getDimensionPixelSize(R.dimen.pro_table_col_w),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        boolean included = INCLUDED.equals(status);
        TextView glyph = new TextView(this);
        glyph.setGravity(Gravity.CENTER);
        glyph.setTypeface(iconTypeface());
        glyph.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.pro_table_glyph_size));
        glyph.setText(included ? "check" : SOON.equals(status) ? "schedule" : "remove");
        glyph.setTextColor(color(included && tier.highlighted
                ? R.color.pro_check_gold : R.color.pro_text_secondary));
        if (tier.comingSoon && !tier.owned) glyph.setAlpha(0.45f);
        if (included) {
            // Small square badge instead of a wide pill.
            int size = getResources().getDimensionPixelSize(R.dimen.pro_table_cell_square);
            glyph.setLayoutParams(new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
            glyph.getPaint().setFakeBoldText(true);
            if (tier.highlighted) glyph.setBackgroundResource(R.drawable.bg_pro_cell_owned);
        } else {
            glyph.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        }
        cell.addView(glyph);
        cell.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return cell;
    }

    private int statusLabel(String status) {
        return INCLUDED.equals(status) ? R.string.pro_screen_included
                : SOON.equals(status) ? R.string.pro_screen_coming_soon_status : R.string.pro_screen_not_included;
    }

    private void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.pro_screen_link_error, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        findViewById(R.id.crownRain).setVisibility(motionAllowed() ? View.VISIBLE : View.INVISIBLE);
        rescheduleAvatarLife();
        android.widget.ScrollView scroll = findViewById(R.id.proScroll);
        if (scroll != null) scroll.post(() -> {
            updateScrollHint(scroll.getScrollY(), scroll.getHeight(), scroll.getChildAt(0).getHeight());
            updateStickyHeaderVisibility();
        });
    }

    @Override
    protected void onPause() {
        resumed = false;
        animationHandler.removeCallbacksAndMessages(null);
        stopHintBounce();
        if (avatarTravelAnimator != null) avatarTravelAnimator.cancel();
        if (glanceCallback != null && avatarOverlay != null) {
            Drawable glanceDrawable = avatarOverlay.getDrawable();
            if (glanceDrawable instanceof Animatable2) {
                ((Animatable2) glanceDrawable).unregisterAnimationCallback(glanceCallback);
            }
        }
        ImageView avatar = activeAvatarView();
        if (avatar != null) {
            Drawable drawable = avatar.getDrawable();
            if (drawable instanceof Animatable2 && blinkCallback != null) {
                ((Animatable2) drawable).unregisterAnimationCallback(blinkCallback);
            }
            if (drawable instanceof Animatable) ((Animatable) drawable).stop();
            avatar.setImageResource(R.drawable.toggle_on_idle_pro);
        }
        findViewById(R.id.crownRain).setVisibility(View.INVISIBLE);
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            View root = findViewById(R.id.rootContainer);
            if (root != null) ViewCompat.requestApplyInsets(root);
            if (root != null && firstFrameDrawn) root.postOnAnimation(() -> {
                if (hasWindowFocus() && !isFinishing()) {
                    hideStatusBar(root);
                }
            });
        }
    }

    private void blinkOnce() {
        if (!resumed || !motionAllowed()) return;
        if (avatarState == AVATAR_MOVING_TO_CTA || avatarState == AVATAR_MOVING_TO_HEADER) return;
        final ImageView avatar = activeAvatarView();
        if (avatar == null || avatar.getVisibility() != View.VISIBLE) return;
        Drawable drawable = AppCompatResources.getDrawable(this, R.drawable.toggle_on_blink_pro);
        if (!(drawable instanceof Animatable2)) return;
        blinkCallback = new Animatable2.AnimationCallback() {
            @Override
            public void onAnimationEnd(Drawable finished) {
                if (activeAvatarView() == avatar) {
                    avatar.setImageResource(R.drawable.toggle_on_idle_pro);
                }
                if (resumed && motionAllowed() && activeAvatarView() == avatar
                        && (avatarState == AVATAR_HEADER || avatarState == AVATAR_CTA)) {
                    animationHandler.postDelayed(blink, 3000L + random.nextInt(2000));
                }
            }
        };
        ((Animatable2) drawable).registerAnimationCallback(blinkCallback);
        avatar.setImageDrawable(drawable);
        ((Animatable2) drawable).start();
    }

    /** Which avatar is alive right now (blinking): the header one or the CTA copy. */
    private ImageView activeAvatarView() {
        if (avatarState == AVATAR_CTA && avatarOverlay != null && avatarOverlayHost != null
                && avatarOverlayHost.getVisibility() == View.VISIBLE) {
            return avatarOverlay;
        }
        return findViewById(R.id.ivAvatar);
    }

    // ── Shared-element avatar: one element travelling between two exact anchors ──────────────

    /** Exact rendered bounds of the original avatar at the top of the page. */
    private android.graphics.RectF headerAvatarAnchor() {
        View header = findViewById(R.id.ivAvatar);
        if (header == null) return new android.graphics.RectF();
        int[] location = new int[2];
        header.getLocationOnScreen(location);
        return new android.graphics.RectF(location[0], location[1],
                location[0] + header.getWidth(), location[1] + header.getHeight());
    }

    /** Exact rendered bounds the avatar occupies while resting inside the CTA. Only the upper
     *  part of the head stays above the button's bottom edge; the rest is clipped by its outline. */
    private android.graphics.RectF ctaAvatarAnchor() {
        int[] location = new int[2];
        btnGetPro.getLocationOnScreen(location);
        float size = getResources().getDimension(R.dimen.pro_cta_avatar);
        float right = location[0] + btnGetPro.getWidth() - dp(4);
        float bottom = location[1] + btnGetPro.getHeight();
        float top = bottom - size * 0.6f;
        return new android.graphics.RectF(right - size, top, right, top + size);
    }

    /**
     * Single source of truth: the real visibility of the header anchor decides the target state,
     * independent of any previous animation. Small hysteresis stops flicker at the boundary.
     */
    private void syncAvatarState() {
        if (avatarOverlay == null || btnGetPro == null) return;
        View header = findViewById(R.id.ivAvatar);
        if (header == null || header.getWidth() == 0) return;
        if (isOwner || btnGetPro.getVisibility() != View.VISIBLE) {
            if (avatarState != AVATAR_HEADER) forceAvatarHeader(header);
            return;
        }
        int[] location = new int[2];
        header.getLocationOnScreen(location);
        float bottom = location[1] + header.getHeight();
        boolean fullyOut = bottom <= 0f;
        boolean entering = bottom > dp(6);
        switch (avatarState) {
            case AVATAR_HEADER:
                if (fullyOut) { FLog.d("ProAvatar", "sync HEADER->CTA headerBottom=" + bottom); moveAvatarTo(AVATAR_MOVING_TO_CTA); }
                break;
            case AVATAR_CTA:
                if (entering) { FLog.d("ProAvatar", "sync CTA->HEADER headerBottom=" + bottom); moveAvatarTo(AVATAR_MOVING_TO_HEADER); }
                else reapplyCtaPoseIfNeeded();
                break;
            case AVATAR_MOVING_TO_CTA:
                if (entering) { FLog.d("ProAvatar", "sync MOVING_CTA->HEADER headerBottom=" + bottom); moveAvatarTo(AVATAR_MOVING_TO_HEADER); }
                break;
            default: // AVATAR_MOVING_TO_HEADER
                if (fullyOut) { FLog.d("ProAvatar", "sync MOVING_HEADER->CTA headerBottom=" + bottom); moveAvatarTo(AVATAR_MOVING_TO_CTA); }
                break;
        }
    }

    /** No CTA in this configuration (owner): the avatar always rests in the header. */
    private void forceAvatarHeader(View header) {
        animationHandler.removeCallbacks(blink);
        animationHandler.removeCallbacks(glance);
        android.animation.ValueAnimator previous = avatarTravelAnimator;
        avatarTravelAnimator = null;
        if (previous != null) previous.cancel();
        avatarOverlay.animate().cancel();
        if (avatarUnit != null) avatarUnit.setTranslationY(0f);
        avatarOverlayHost.setClipToOutline(false);
        avatarPoseFrame.setRotation(0f);
        avatarOverlay.setVisibility(View.VISIBLE);
        avatarOverlayHost.setVisibility(View.INVISIBLE);
        avatarOverlay.setImageResource(R.drawable.toggle_on_idle_pro);
        header.setVisibility(View.VISIBLE);
        header.setAlpha(1f);
        avatarState = AVATAR_HEADER;
        rescheduleAvatarLife();
    }

    /**
     * Starts (or retargets) the travel. When a transition is interrupted the avatar continues
     * from its exact current visual pose — never from stale coordinates and never by snapping.
     */
    private void moveAvatarTo(int destinationState) {
        View root = findViewById(R.id.rootContainer);
        View header = findViewById(R.id.ivAvatar);
        if (root == null || header == null || avatarOverlay == null || btnGetPro == null) return;
        if (btnGetPro.getHeight() == 0) return;
        if (avatarOverlayHost == null || avatarOverlayHost.getWidth() == 0) {
            // Not laid out yet: retry on the next frame instead of using invalid bounds.
            if (avatarOverlayHost != null) avatarOverlayHost.post(() -> moveAvatarTo(destinationState));
            return;
        }
        android.graphics.RectF target = destinationState == AVATAR_MOVING_TO_CTA
                ? ctaAvatarAnchor() : headerAvatarAnchor();
        if (target.width() <= 0f || target.height() <= 0f) return;
        int[] rootLocation = new int[2];
        root.getLocationOnScreen(rootLocation);

        final boolean toCta = destinationState == AVATAR_MOVING_TO_CTA;
        float startCx, startCy, startSize, startRotation;
        if (avatarState == AVATAR_HEADER && toCta) {
            // Fresh handoff: overlay snaps exactly onto the header anchor before it is hidden.
            android.graphics.RectF anchor = headerAvatarAnchor();
            startCx = anchor.centerX();
            startCy = anchor.centerY();
            startSize = anchor.width();
            startRotation = 0f;
            avatarOverlayHost.setClipToOutline(false);
            if (avatarUnit != null) avatarUnit.setTranslationY(0f);
            avatarOverlay.setImageResource(R.drawable.toggle_on_idle_pro);
            avatarOverlay.setAlpha(1f);
            avatarPoseFrame.setRotation(0f);
            avatarOverlay.setVisibility(View.VISIBLE);
            avatarOverlayHost.setAlpha(1f);
            avatarOverlayHost.setVisibility(View.VISIBLE);
            applyPose(rootLocation, startCx, startCy, startSize, 0f);
            header.setVisibility(View.INVISIBLE);
        } else {
            // Interrupted transition: continue from the avatar's exact current visual pose.
            float width = avatarPoseFrame.getWidth();
            if (width <= 0) return;
            int[] hostOrigin = overlayHostOrigin();
            startSize = width * avatarPoseFrame.getScaleX();
            startCx = hostOrigin[0] + avatarPoseFrame.getX() + width / 2f;
            startCy = hostOrigin[1] + avatarPoseFrame.getY() + width / 2f;
            startRotation = avatarPoseFrame.getRotation();
        }
        avatarState = destinationState;
        animationHandler.removeCallbacks(blink);
        animationHandler.removeCallbacks(glance);
        avatarOverlay.animate().cancel();
        android.animation.ValueAnimator previous = avatarTravelAnimator;
        avatarTravelAnimator = null;
        if (previous != null) previous.cancel();

        final float fromCx = startCx, fromCy = startCy;
        final float fromSize = startSize, fromRotation = startRotation;
        float distance = (float) Math.hypot(target.centerX() - fromCx, target.centerY() - fromCy);
        long duration = (long) (640f * Math.max(0.55f, Math.min(1f, distance / dp(780))));

        final android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(duration);
        animator.setInterpolator(new android.view.animation.PathInterpolator(0.24f, 0f, 0.14f, 1f));
        animator.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            float mt = 1f - t;
            // Track the destination's live bounds every frame: if the layout moves underneath
            // (e.g. fast scrolling while returning), the avatar converges on the real anchor
            // instead of finishing on stale coordinates.
            android.graphics.RectF live = toCta ? ctaAvatarAnchor() : headerAvatarAnchor();
            float liveCx = live.centerX(), liveCy = live.centerY(), liveSize = live.width();
            float liveControlX = (fromCx + liveCx) / 2f + (toCta ? dp(56) : dp(40));
            float liveControlY = Math.min(fromCy, liveCy) - dp(30);
            float cx = mt * mt * fromCx + 2f * mt * t * liveControlX + t * t * liveCx;
            float cy = mt * mt * fromCy + 2f * mt * t * liveControlY + t * t * liveCy;
            float size = fromSize + (liveSize - fromSize) * t;
            float rotation = fromRotation * mt + (float) Math.sin(t * Math.PI) * (toCta ? -7f : -5f);
            applyPose(rootLocation, cx, cy, size, rotation);
            // Applies in both directions: keeps the cut exact while descending AND while the
            // head is lifting back out, so the lower part is never exposed mid-transition.
            updateOverlayClip(cy - size / 2f, cy + size / 2f);
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (animation != avatarTravelAnimator) return; // superseded by a retarget
                finishAvatarTravel(toCta);
            }
        });
        avatarTravelAnimator = animator;
        animator.start();
    }

    private int[] overlayHostOrigin() {
        int[] location = new int[2];
        if (avatarOverlayHost != null) avatarOverlayHost.getLocationOnScreen(location);
        return location;
    }

    /** Exact visual pose of the avatar, expressed in the untransformed host's space. The pose
     *  frame carries position/size/tilt so idle micro-animations (unit bob, highlight drift)
     *  can never disturb it. */
    private void applyPose(int[] rootLocation, float centerX, float centerY, float size, float rotation) {
        if (avatarOverlayHost == null || avatarPoseFrame == null) return;
        float width = avatarPoseFrame.getWidth();
        if (width <= 0) return;
        int[] hostOrigin = overlayHostOrigin();
        float scale = size / width;
        avatarPoseFrame.setScaleX(scale);
        avatarPoseFrame.setScaleY(scale);
        avatarPoseFrame.setX(centerX - hostOrigin[0] - width / 2f);
        avatarPoseFrame.setY(centerY - hostOrigin[1] - width / 2f);
        avatarPoseFrame.setRotation(rotation);
    }

    /** The final frame is snapped to the destination's real bounds, so there is zero jump. */
    private void finishAvatarTravel(boolean toCta) {
        View root = findViewById(R.id.rootContainer);
        View header = findViewById(R.id.ivAvatar);
        if (root == null || header == null || avatarOverlay == null) return;
        int[] rootLocation = new int[2];
        root.getLocationOnScreen(rootLocation);
        if (toCta) {
            avatarState = AVATAR_CTA;
            android.graphics.RectF anchor = ctaAvatarAnchor();
            FLog.d("ProAvatar", "finish CTA pose center=" + anchor.centerX() + "," + anchor.centerY()
                    + " size=" + anchor.width() + " overlayX=" + avatarOverlay.getX()
                    + " overlayY=" + avatarOverlay.getY() + " scale=" + avatarOverlay.getScaleX());
            applyPose(rootLocation, anchor.centerX(), anchor.centerY(), anchor.width(), 0f);
            updateOverlayClip(anchor.top, anchor.bottom);
            // Landing is already exact: no rotate/scale settle that could show a visible tilt.
            rescheduleAvatarLife();
        } else {
            avatarState = AVATAR_HEADER;
            android.graphics.RectF anchor = headerAvatarAnchor();
            applyPose(rootLocation, anchor.centerX(), anchor.centerY(), anchor.width(), 0f);
            avatarOverlayHost.setClipToOutline(false);
            // Hand control back in the same frame: the overlay hides exactly over the header copy.
            header.setVisibility(View.VISIBLE);
            header.setAlpha(1f);
            avatarOverlayHost.setClipToOutline(false);
            avatarOverlayHost.setVisibility(View.INVISIBLE);
            if (avatarUnit != null) avatarUnit.setTranslationY(0f);
            avatarPoseFrame.setRotation(0f);
            rescheduleAvatarLife();
        }
    }

    /**
     * Screen-fixed clip, expressed as an outline on the untransformed, full-screen host:
     *  - While the head is still above the button's band: everything above the button's bottom
     *    edge is kept (the flying/approaching head is untouched); anything below is cut.
     *  - Once the head is fully inside the band: clip to the button's exact rounded shape, so
     *    everything outside the button is hidden and everything inside stays visible at any
     *    tilt angle — the clip never moves with the child.
     */
    private void updateOverlayClip(float avatarTopScreen, float avatarBottomScreen) {
        if (avatarOverlayHost == null || btnGetPro == null) return;
        int[] buttonLocation = new int[2];
        btnGetPro.getLocationOnScreen(buttonLocation);
        int[] hostOrigin = overlayHostOrigin();
        final float localLeft = buttonLocation[0] - hostOrigin[0];
        final float localTop = buttonLocation[1] - hostOrigin[1];
        final float localRight = localLeft + btnGetPro.getWidth();
        final float localBottom = localTop + btnGetPro.getHeight();
        final float radius = getResources().getDimension(R.dimen.pro_cta_corner);
        boolean insideBand = avatarTopScreen >= buttonLocation[1];
        if (insideBand) {
            avatarOverlayHost.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(Math.round(localLeft), Math.round(localTop),
                            Math.round(localRight), Math.round(localBottom), radius);
                }
            });
        } else {
            avatarOverlayHost.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    // Plain rect outline: everything above the button's bottom edge is kept, the
                    // rest is cut. Rect outlines clip reliably on every GPU, unlike convex paths
                    // (which some OEM drivers silently ignore).
                    outline.setRect(0, 0, view.getWidth(), Math.max(0, Math.round(localBottom)));
                }
            });
        }
        avatarOverlayHost.setClipToOutline(true);
    }

    /** Keeps the resting CTA pose exact when the layout shifts; leaves the glance tilt alone. */
    private void reapplyCtaPoseIfNeeded() {
        if (btnGetPro == null || avatarOverlayHost == null) return;
        if (avatarPoseFrame == null) return;
        float width = avatarPoseFrame.getWidth();
        if (width <= 0) return;
        int[] hostOrigin = overlayHostOrigin();
        android.graphics.RectF anchor = ctaAvatarAnchor();
        float expectedScale = anchor.width() / width;
        float expectedX = anchor.centerX() - hostOrigin[0] - width / 2f;
        float expectedY = anchor.centerY() - hostOrigin[1] - width / 2f;
        if (Math.abs(avatarPoseFrame.getScaleX() - expectedScale) > 0.001f
                || Math.abs(avatarPoseFrame.getX() - expectedX) > 0.5f
                || Math.abs(avatarPoseFrame.getY() - expectedY) > 0.5f) {
            avatarPoseFrame.setScaleX(expectedScale);
            avatarPoseFrame.setScaleY(expectedScale);
            avatarPoseFrame.setX(expectedX);
            avatarPoseFrame.setY(expectedY);
            updateOverlayClip(anchor.top, anchor.bottom);
        }
    }

    /** (Re)schedules the idle life: blinking everywhere, glances only inside the CTA. */
    private void rescheduleAvatarLife() {
        animationHandler.removeCallbacks(blink);
        animationHandler.removeCallbacks(glance);
        if (!resumed || !motionAllowed()) return;
        if (avatarState == AVATAR_HEADER || avatarState == AVATAR_CTA) {
            animationHandler.postDelayed(blink, 1400L + random.nextInt(1200));
        }
        if (avatarState == AVATAR_CTA) {
            animationHandler.postDelayed(glance, 2600L + random.nextInt(2200));
        }
    }

    /** Eye-direction glance toward the CTA label: the eyes move, with only a hint of head tilt. */
    private void glanceOnce() {
        if (!resumed || !motionAllowed() || avatarState != AVATAR_CTA || avatarOverlay == null
                || avatarOverlayHost == null || avatarOverlayHost.getVisibility() != View.VISIBLE) return;
        final Drawable glanceDrawable = AppCompatResources.getDrawable(this, R.drawable.toggle_on_glance_pro);
        if (!(glanceDrawable instanceof Animatable2)) return;
        animationHandler.removeCallbacks(blink);
        animationHandler.removeCallbacks(glance);
        glanceCallback = new Animatable2.AnimationCallback() {
            @Override
            public void onAnimationEnd(Drawable finished) {
                if (avatarState != AVATAR_CTA || avatarOverlay == null) return;
                avatarOverlay.setImageResource(R.drawable.toggle_on_idle_pro);
                rescheduleAvatarLife();
            }
        };
        ((Animatable2) glanceDrawable).registerAnimationCallback(glanceCallback);
        avatarOverlay.setImageDrawable(glanceDrawable);
        ((Animatable2) glanceDrawable).start();
        // Eyes carry the expression; a gentle head tilt accompanies them. The clip lives on the
        // untransformed host, so the cut stays exactly on the button's edge (no exposed head).
        avatarPoseFrame.animate().cancel();
        avatarPoseFrame.animate().rotation(-4f).setDuration(320)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (avatarState == AVATAR_CTA && avatarPoseFrame != null) {
                        avatarPoseFrame.animate().rotation(0f).setDuration(360)
                                .setInterpolator(new DecelerateInterpolator()).start();
                    }
                }).start();
    }

    private boolean motionAllowed() {
        return Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }

    private Typeface iconTypeface() {
        if (iconTypeface == null) iconTypeface = ResourcesCompat.getFont(this, R.font.materialicons);
        return iconTypeface;
    }

    private int color(int resource) { return ContextCompat.getColor(this, resource); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
