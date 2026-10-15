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

    private void measurePage() {
        View root = view(R.id.rootContainer);
        int width = dp(360);
        int height = dp(780);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }
}
