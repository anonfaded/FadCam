package com.fadcam.motion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import com.fadcam.Constants;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.motion.domain.model.MotionRuntimeSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Proves that every Motion Lab setting reaches the engine the moment it changes —
 * via a real {@link SharedPreferences.OnSharedPreferenceChangeListener}, not by
 * polling and not only on the next recording session.
 */
@RunWith(RobolectricTestRunner.class)
public class MotionRuntimeSettingsTest {

    private SharedPreferencesManager manager;
    private SharedPreferences prefs;
    private MotionRuntimeSettings runtime;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        manager = SharedPreferencesManager.getInstance(context);
        prefs = manager.sharedPreferences;
        prefs.edit().clear().commit();
        runtime = new MotionRuntimeSettings(manager);
    }

    @After
    public void tearDown() {
        runtime.release();
    }

    @Test
    public void defaultsAreSane() {
        assertEquals(80, runtime.getSensitivity());
        assertEquals(10, runtime.getAnalysisFps());
        assertEquals(720, runtime.getAnalysisResolution());
        assertEquals(220, runtime.getDebounceMs());
        assertEquals(10_000, runtime.getPostRollMs());
        assertEquals(2, runtime.getPreRollSeconds());
        assertFalse(runtime.isAutoTorchEnabled());
        assertFalse(runtime.isEnabled());
    }

    @Test
    public void sensitivityAppliesImmediately() {
        prefs.edit().putInt(Constants.PREF_MOTION_SENSITIVITY, 25).commit();
        assertEquals("a sensitivity edit must be visible on the next frame",
                25, runtime.getSensitivity());
    }

    @Test
    public void analysisFpsAppliesImmediately() {
        prefs.edit().putInt(Constants.PREF_MOTION_ANALYSIS_FPS, 7).commit();
        assertEquals(7, runtime.getAnalysisFps());
    }

    @Test
    public void analysisResolutionAppliesImmediately() {
        prefs.edit().putInt(Constants.PREF_MOTION_ANALYSIS_RESOLUTION, 1080).commit();
        assertEquals(1080, runtime.getAnalysisResolution());
        prefs.edit().putInt(Constants.PREF_MOTION_ANALYSIS_RESOLUTION, 480).commit();
        assertEquals(480, runtime.getAnalysisResolution());
    }

    @Test
    public void debounceAppliesImmediately() {
        prefs.edit().putInt(Constants.PREF_MOTION_DEBOUNCE_MS, 900).commit();
        assertEquals(900, runtime.getDebounceMs());
    }

    @Test
    public void postRollAppliesImmediately() {
        prefs.edit().putInt(Constants.PREF_MOTION_POST_ROLL_MS, 30_000).commit();
        assertEquals(30_000, runtime.getPostRollMs());
    }

    @Test
    public void preRollAppliesImmediately() {
        prefs.edit().putInt(Constants.PREF_MOTION_PRE_ROLL_SECONDS, 6).commit();
        assertEquals(6, runtime.getPreRollSeconds());
    }

    @Test
    public void autoTorchAppliesImmediately() {
        prefs.edit().putBoolean(Constants.PREF_MOTION_AUTO_TORCH_ENABLED, true).commit();
        assertTrue(runtime.isAutoTorchEnabled());
    }

    @Test
    public void triggerModeAppliesImmediately() {
        prefs.edit().putString(Constants.PREF_MOTION_TRIGGER_MODE,
                "person_confirmed").commit();
        assertEquals("person_confirmed", runtime.getTriggerMode());
    }

    @Test
    public void valuesAreSanitisedSoTheEngineCannotSeeNonsense() {
        prefs.edit()
                .putInt(Constants.PREF_MOTION_SENSITIVITY, 5_000)
                .putInt(Constants.PREF_MOTION_ANALYSIS_FPS, 0)
                .putInt(Constants.PREF_MOTION_ANALYSIS_RESOLUTION, 4_000)
                .putInt(Constants.PREF_MOTION_POST_ROLL_MS, -1)
                .putInt(Constants.PREF_MOTION_PRE_ROLL_SECONDS, 999)
                .commit();
        assertEquals("sensitivity must clamp to 0..100", 100, runtime.getSensitivity());
        assertEquals("a zero FPS must fall back to the default", 10, runtime.getAnalysisFps());
        assertEquals("an unsupported resolution must fall back to 720",
                720, runtime.getAnalysisResolution());
        assertEquals("a negative post-roll must not be honoured", 0, runtime.getPostRollMs());
        assertEquals("pre-roll is bounded", 10, runtime.getPreRollSeconds());
    }

    @Test
    public void releaseStopsListening() {
        runtime.release();
        prefs.edit().putInt(Constants.PREF_MOTION_SENSITIVITY, 11).commit();
        assertEquals("after release the cached value must not change",
                80, runtime.getSensitivity());
    }
}
