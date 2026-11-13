package com.fadcam.ui;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fadcam.BuildConfig;
import com.fadcam.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

/** Verifies real view behavior and records device-rendered evidence without changing APK ownership. */
@RunWith(AndroidJUnit4.class)
public class ProScreenInstrumentedTest {
    @Test
    public void visitorAndOwnerKeepComparisonAndCorrectActions() throws Exception {
        try (ActivityScenario<FadCamProActivity> scenario = ActivityScenario.launch(FadCamProActivity.class)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Thread.sleep(500);
            scenario.onActivity(activity -> {
                assertEquals(View.VISIBLE, activity.findViewById(R.id.btnGetPro).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.priceRow).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.ownedStatus).getVisibility());
                assertEquals(View.OVER_SCROLL_NEVER, activity.findViewById(R.id.proScroll).getOverScrollMode());
                assertEquals(View.OVER_SCROLL_NEVER, activity.findViewById(R.id.comparisonScroll).getOverScrollMode());
                assertTrue(activity.findViewById(R.id.btnBack).getHeight()
                        >= activity.getResources().getDimensionPixelSize(R.dimen.pro_close_size));
            });
            capture("free-top");
            scenario.onActivity(activity -> ((ScrollView) activity.findViewById(R.id.proScroll)).fullScroll(View.FOCUS_DOWN));
            capture("free-bottom");
            scenario.onActivity(activity -> {
                configure(activity, "PRO");
                assertEquals(View.GONE, activity.findViewById(R.id.btnGetPro).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.priceRow).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.purchaseCaption).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.ownedStatus).getVisibility());
                assertFalse(activity.findViewById(R.id.ownedStatus).isClickable());
                assertEquals(BuildConfig.LITE_EDITION ? 18 : 26,
                        ((LinearLayout) activity.findViewById(R.id.featuresContainer)).getChildCount());
                ((ScrollView) activity.findViewById(R.id.proScroll)).fullScroll(View.FOCUS_UP);
            });
            capture("owner-top");
            scenario.onActivity(activity -> {
                View heading = activity.findViewById(R.id.tvEyebrowCompare);
                ((ScrollView) activity.findViewById(R.id.proScroll)).scrollTo(0, heading.getTop());
            });
            capture("owner-comparison");
            if (!BuildConfig.LITE_EDITION) {
                scenario.onActivity(activity -> {
                    configure(activity, "PRO_PLUS");
                    assertTrue(((TextView) activity.findViewById(R.id.tvProTitle)).getText().toString().contains("Pro+"));
                    assertEquals(View.GONE, activity.findViewById(R.id.btnGetPro).getVisibility());
                });
                capture("proplus-comparison");
            }
            scenario.onActivity(activity -> {
                configure(activity, "FREE");
                assertEquals(View.VISIBLE, activity.findViewById(R.id.btnGetPro).getVisibility());
                activity.findViewById(R.id.btnBack).performClick();
                assertTrue(activity.isFinishing());
            });
        }
    }

    private static void configure(FadCamProActivity activity, String tier) {
        try {
            Method bind = FadCamProActivity.class.getDeclaredMethod("configureScreen", String.class);
            bind.setAccessible(true);
            bind.invoke(activity, tier);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static int dp(FadCamProActivity activity, int value) {
        return Math.round(activity.getResources().getDisplayMetrics().density * value);
    }

    private static void capture(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        // Allow native window/draw frames and the brief entrance to finish before capturing pixels.
        Thread.sleep(500);
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getExternalFilesDir(null), "pro-review");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
    }
}
