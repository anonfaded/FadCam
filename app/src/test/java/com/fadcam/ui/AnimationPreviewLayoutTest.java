package com.fadcam.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import com.fadcam.R;
import com.fadcam.ui.animations.AnimationView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/**
 * The animated previews must fill their card instead of being letterboxed.
 *
 * <p>{@link AnimationView} scales its scene to fit inside whatever box it is given, so
 * a box that does not match the scene's ratio leaves empty bands. Both Motion Lab and
 * the Digital Forensics screen therefore wrap the view in an
 * {@link com.fadcam.ui.widgets.AspectRatioFrameLayout} pinned to the scene ratio.
 *
 * <p>These tests measure the real layouts, so removing or changing the wrapper fails
 * here rather than silently shipping a smaller picture.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 31, qualifiers = "w360dp-h780dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class AnimationPreviewLayoutTest {

    /** Both MotionScene and ForensicScene declare a 168x92 design box. */
    private static final float SCENE_RATIO = 92f / 168f;

    private static void assertFillsCard(int layoutRes, int animationViewId) {
        Application app = RuntimeEnvironment.getApplication();
        // The layouts use theme attributes (colorHeading etc.), so inflate with the
        // real app theme rather than the bare Application theme.
        android.content.Context themed = new android.view.ContextThemeWrapper(
                app, R.style.Base_Theme_FadCam);
        View root = LayoutInflater.from(themed).inflate(layoutRes, null, false);
        assertNotNull("layout must inflate", root);

        AnimationView animation = root.findViewById(animationViewId);
        assertNotNull("animation view must exist", animation);

        ViewParent parent = animation.getParent();
        assertNotNull("animation must sit inside a wrapper", parent);
        assertTrue("wrapper must be an AspectRatioFrameLayout",
                parent instanceof com.fadcam.ui.widgets.AspectRatioFrameLayout);

        // Measure the card at a realistic phone width.
        int cardWidth = 336; // dp at mdpi == px
        root.measure(
                View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());

        ViewGroup wrapper = (ViewGroup) parent;
        float wrapperRatio = wrapper.getMeasuredHeight() / (float) wrapper.getMeasuredWidth();
        assertEquals("wrapper must use the scene ratio", SCENE_RATIO, wrapperRatio, 0.01f);
        assertEquals("animation must fill the wrapper width",
                wrapper.getMeasuredWidth(), animation.getMeasuredWidth());
        assertEquals("animation must fill the wrapper height",
                wrapper.getMeasuredHeight(), animation.getMeasuredHeight());
    }

    @Test
    public void motionLabPreviewFillsItsCard() {
        assertFillsCard(R.layout.fragment_settings_motion_lab, R.id.animation_motion_debug);
    }

    @Test
    public void forensicsSettingsPreviewFillsItsCard() {
        assertFillsCard(R.layout.fragment_settings_digital_forensics,
                R.id.animation_forensics_preview);
    }
}
