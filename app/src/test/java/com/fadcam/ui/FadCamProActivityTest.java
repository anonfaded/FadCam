package com.fadcam.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fadcam.BuildConfig;
import com.fadcam.R;
import com.google.android.material.button.MaterialButton;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Method;

/** Run against both LiteDebug and DefaultDebug; production ownership remains BuildConfig-only. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 31, qualifiers = "w360dp-h780dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class FadCamProActivityTest {
    private ActivityController<FadCamProActivity> controller;
    private FadCamProActivity activity;

    @Before
    public void disableMotion() {
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 0f);
    }

    @After
    public void closeActivity() {
        if (controller != null) controller.pause().stop().destroy();
    }

    @Test
    public void freeBuildShowsPurchaseAndOpensExistingShop() {
        launch();
        assertEquals("FREE", BuildConfig.TIER);
        assertEquals(View.VISIBLE, view(R.id.priceRow).getVisibility());
        assertEquals(View.VISIBLE, view(R.id.purchaseCaption).getVisibility());
        assertEquals(View.GONE, view(R.id.ownedStatus).getVisibility());
        assertEquals(View.GONE, view(R.id.tvOwnedBadge).getVisibility());
        MaterialButton purchase = activity.findViewById(R.id.btnGetPro);
        assertEquals(View.VISIBLE, purchase.getVisibility());
        assertTrue(purchase.performClick());
        Intent checkout = shadowOf(activity).getNextStartedActivity();
        assertNotNull(checkout);
        assertEquals(Intent.ACTION_VIEW, checkout.getAction());
        assertEquals("https://www.patreon.com/cw/Fadedx/shop", checkout.getDataString());
    }

    @Test
    public void proOwnerSeesStatusInsteadOfPurchase() throws Exception {
        launch();
        render("PRO");
        assertOwner(R.string.pro_screen_tier, 2);
    }

    @Test
    public void proPlusOwnerMarksOnlyOwnedTier() throws Exception {
        launch();
        render("PRO_PLUS");
        assertOwner(R.string.pro_screen_tier_plus, 3);
    }

    @Test
    public void changingPresentationClearsRowsAndRestoresFreePurchase() throws Exception {
        launch();
        ViewGroup rows = activity.findViewById(R.id.featuresContainer);
        ViewGroup headers = activity.findViewById(R.id.tableHeaderRow);
        int rowCount = rows.getChildCount();
        int headerCount = headers.getChildCount();
        assertTrue(rowCount > 0);
        render("PRO");
        render("PRO");
        render("FREE");
        assertEquals(rowCount, rows.getChildCount());
        assertEquals(headerCount, headers.getChildCount());
        assertEquals(View.VISIBLE, view(R.id.priceRow).getVisibility());
        assertEquals(View.VISIBLE, view(R.id.btnGetPro).getVisibility());
        assertEquals(View.VISIBLE, view(R.id.purchaseCaption).getVisibility());
        assertEquals(View.GONE, view(R.id.ownedStatus).getVisibility());
        assertEquals(View.GONE, view(R.id.tvOwnedBadge).getVisibility());
        assertEquals(activity.getString(R.string.pro_screen_title),
                ((TextView) view(R.id.tvProTitle)).getText().toString());
        assertTrue(view(R.id.btnGetPro).performClick());
        assertNotNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test
    public void reducedMotionLeavesContentVisibleAndAmbientMotionHidden() {
        launch();
        assertEquals(1f, view(R.id.heroSection).getAlpha(), 0f);
        assertEquals(0f, view(R.id.heroSection).getTranslationY(), 0f);
        assertEquals(1f, view(R.id.footerBar).getAlpha(), 0f);
        assertFalse(view(R.id.crownRain).getVisibility() == View.VISIBLE);
        ViewGroup rows = activity.findViewById(R.id.featuresContainer);
        for (int i = 0; i < rows.getChildCount(); i++) {
            assertEquals(1f, rows.getChildAt(i).getAlpha(), 0f);
        }
    }

    @Test
    public void interactionColorsAndScrollEdgesStayWithinProTheme() {
        launch();
        assertEquals(View.OVER_SCROLL_NEVER, view(R.id.proScroll).getOverScrollMode());
        assertEquals(View.OVER_SCROLL_NEVER, view(R.id.comparisonScroll).getOverScrollMode());
        TypedValue accent = new TypedValue();
        assertTrue(activity.getTheme().resolveAttribute(android.R.attr.colorAccent, accent, true));
        assertEquals(activity.getColor(R.color.pro_gold), accent.data);
        assertEquals(0f, view(R.id.btnGetPro).getElevation(), 0f);
        assertNull(view(R.id.btnGetPro).getStateListAnimator());
    }

    @Test
    @Config(qualifiers = "es-rES-w360dp-h780dp-mdpi")
    public void localizedSystemDoesNotInheritOldFullSalesCopy() {
        launch();
        assertEquals(BuildConfig.LITE_EDITION ? "FadCam Lite Pro" : "FadCam Pro",
                ((TextView) view(R.id.tvProTitle)).getText().toString());
        assertEquals("Unlock the Pro Bundle",
                ((TextView) view(R.id.btnGetPro)).getText().toString());
    }

    @Test
    public void comparisonFitsTheScreenWithoutHorizontalScrolling() {
        launch();
        measurePage();
        measurePage();
        assertEquals(view(R.id.comparisonScroll).getMeasuredWidth(), view(R.id.tableContent).getMeasuredWidth());
        assertEquals(View.GONE, view(R.id.comparisonHint).getVisibility());
        assertEquals(5, ((ViewGroup) view(R.id.tableHeaderRow)).getChildCount());
    }

    @Test
    public void largeTextMovesFooterIntoContentAndKeepsTableFitting() {
        RuntimeEnvironment.setFontScale(1.8f);
        launch();
        android.widget.ScrollView scroll = activity.findViewById(R.id.proScroll);
        assertSame(scroll.getChildAt(0), view(R.id.footerBar).getParent());
        measurePage();
        measurePage();
        assertEquals(view(R.id.comparisonScroll).getMeasuredWidth(),
                view(R.id.tableContent).getMeasuredWidth());
        assertEquals(View.VISIBLE, view(R.id.btnGetPro).getVisibility());
    }

    @Test
    public void comparisonRowsShowDistinctIcons() {
        launch();
        ViewGroup rows = activity.findViewById(R.id.featuresContainer);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            TextView glyph = row.findViewById(R.id.tvRowIcon);
            if (glyph == null) continue; // divider views carry no icon
            boolean glyphShown = glyph.getVisibility() == View.VISIBLE
                    && !glyph.getText().toString().isEmpty();
            View image = row.findViewById(R.id.ivRowImage);
            boolean imageShown = image != null && image.getVisibility() == View.VISIBLE
                    && ((android.widget.ImageView) image).getDrawable() != null;
            assertTrue("row " + i + " needs an icon", glyphShown || imageShown);
        }
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-mdpi")
    public void shortWindowKeepsPurchaseReachableInsideScroll() {
        launch();
        android.widget.ScrollView scroll = activity.findViewById(R.id.proScroll);
        assertSame(scroll.getChildAt(0), view(R.id.footerBar).getParent());
    }

    private void launch() {
        controller = Robolectric.buildActivity(FadCamProActivity.class).setup();
        activity = controller.get();
    }

    private void render(String tier) throws Exception {
        Method configure = FadCamProActivity.class.getDeclaredMethod("configureScreen", String.class);
        configure.setAccessible(true);
        configure.invoke(activity, tier);
    }

    private void assertOwner(int labelResource, int ownedHeaderIndex) {
        assertEquals(View.GONE, view(R.id.priceRow).getVisibility());
        assertEquals(View.GONE, view(R.id.btnGetPro).getVisibility());
        assertEquals(View.GONE, view(R.id.purchaseCaption).getVisibility());
        assertFalse(view(R.id.btnGetPro).hasOnClickListeners());
        TextView status = activity.findViewById(R.id.ownedStatus);
        assertEquals(View.VISIBLE, status.getVisibility());
        assertFalse(status.isClickable());
        assertEquals(activity.getString(R.string.pro_screen_owned_status,
                activity.getString(labelResource)), status.getText().toString());
        assertEquals(View.VISIBLE, view(R.id.tvOwnedBadge).getVisibility());
        ViewGroup headers = activity.findViewById(R.id.tableHeaderRow);
        for (int i = 1; i < headers.getChildCount(); i++) {
            String text = ((TextView) headers.getChildAt(i)).getText().toString();
            assertEquals(i == ownedHeaderIndex,
                    text.contains(activity.getString(R.string.pro_screen_active)));
        }
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    private View view(int id) {
        return activity.findViewById(id);
    }

    private int dp(int size) {
        return Math.round(size * activity.getResources().getDisplayMetrics().density);
    }

    /** Longer than any bucket's text column, so the overflow is real under Robolectric. */
    private static final String PROBE_LONG_LABEL = "Discreet entry screens that keep the disguise "
            + "visible in the launcher, the notification shade and the recent apps list of every "
            + "edition of the bundle without a single visible recording indicator";

    /**
     * The crown backdrop is a `match_parent` child of a wrapping bar, and a plain View under an
     * AT_MOST pass claims the whole window: that turned the sticky bar into a full-screen panel
     * that covered everything. The backdrop must never drive the bar's height, and must end up
     * exactly as tall as the bar.
     */
    @Test
    public void stickyFooterKeepsItsHeightWithTheCrownBackdrop() {
        launch();
        measurePage();
        measurePage();
        View footer = view(R.id.footerBar);
        View rain = view(R.id.footerCrownRain);
        assertTrue("sticky footer grew to the window: " + footer.getHeight(),
                footer.getHeight() < dp(400));
        assertEquals("crown backdrop must span the whole bar, divider included",
                footer.getHeight(), rain.getHeight());
    }

    private void measurePage() {
        measurePage(360, 780);
    }

    private void measurePage(int widthDp, int heightDp) {
        View root = view(R.id.rootContainer);
        int width = dp(widthDp);
        int height = dp(heightDp);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    /**
     * The SOON pill used to be pushed out of the card: its label was a wrap-to-wrap constraint
     * chain, so the label resolved at its one-line width on a 360dp screen and the pill landed
     * past the text column's edge — clipped there and covered by the phone mockup. The pill must
     * always keep its width, stay inside the text column and stay clear of the phone's slot.
     */
    private void assertSoonPillIsVisibleAndClearOfThePhone() {
        View soon = view(R.id.entrySoon);
        View label = view(R.id.entryLabel);
        View column = view(R.id.showcaseTextColumn);
        View phone = view(R.id.showcasePhoneWrapper);
        assertEquals(View.VISIBLE, soon.getVisibility());
        assertTrue("SOON pill was squeezed to nothing", soon.getWidth() > 0);
        assertTrue("SOON pill overflows the text column", soon.getRight() <= column.getRight());
        assertTrue("label runs under the SOON pill", label.getRight() <= soon.getLeft());
        assertTrue("phone covers the SOON pill", phone.getLeft() >= soon.getRight());
    }

    @Test
    public void soonPillStaysVisibleAndClearOfThePhoneOnNarrowScreens() {
        launch();
        measurePage();
        assertSoonPillIsVisibleAndClearOfThePhone();
    }

    /**
     * Localized copy is the long-label case: a wrap-to-wrap constraint chain resolved the label
     * at its full one-line width and pushed the pill out of the row, so the row must be a
     * weight split — the label wraps (max three lines) and the pill keeps its width.
     */
    @Test
    public void longEntryLabelWrapsInsteadOfPushingTheSoonPillOut() {
        launch();
        TextView label = activity.findViewById(R.id.entryLabel);
        ViewGroup.LayoutParams labelParams = label.getLayoutParams();
        // Robolectric measures text at one pixel per character, so the probe copy has to be
        // longer than the column itself to make the overflow real; the row must absorb it by
        // wrapping the label (max three lines) instead of pushing the pill out of the card.
        label.setText(PROBE_LONG_LABEL);
        measurePage();
        assertSoonPillIsVisibleAndClearOfThePhone();
        assertEquals("the label must take the leftover width, never push its sibling out",
                0, labelParams.width);
        assertTrue("the label must be weighted against the pill",
                labelParams instanceof LinearLayout.LayoutParams
                        && ((LinearLayout.LayoutParams) labelParams).weight > 0f);
    }

    @Test
    @Config(qualifiers = "sw400dp-w412dp-h900dp-mdpi")
    public void soonPillStaysVisibleAndClearOfThePhoneOnWideScreens() {
        launch();
        measurePage(412, 900);
        assertSoonPillIsVisibleAndClearOfThePhone();
    }

    @Test
    public void soonPillSurvivesLargeTextInsteadOfCollapsing() {
        RuntimeEnvironment.setFontScale(1.5f);
        launch();
        measurePage();
        assertSoonPillIsVisibleAndClearOfThePhone();
    }

    /**
     * The card is a proportional composition: the phone slot takes a share of the row and its
     * height follows the device's own aspect ratio. With fixed dp sizes the phone ate a constant
     * 150dp of every screen, which emptied the text column on narrow ones.
     */
    @Test
    public void phoneSlotScalesWithTheCardAndKeepsTheDeviceAspect() {
        launch();
        int previousSlot = 0;
        for (int widthDp : new int[] {320, 360, 412, 480}) {
            measurePage(widthDp, 780);
            View phone = view(R.id.showcasePhoneWrapper);
            View column = view(R.id.showcaseTextColumn);
            assertEquals("device aspect at " + widthDp + "dp", 2.05f,
                    phone.getHeight() / (float) phone.getWidth(), 0.02f);
            assertTrue("text column collapsed at " + widthDp + "dp: " + column.getWidth(),
                    column.getWidth() >= dp(120));
            assertTrue("phone slot did not follow the card at " + widthDp + "dp",
                    phone.getWidth() > previousSlot);
            previousSlot = phone.getWidth();
        }
    }
}
