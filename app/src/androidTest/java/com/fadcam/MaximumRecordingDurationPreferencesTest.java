package com.fadcam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MaximumRecordingDurationPreferencesTest {
    @Test
    public void customDurationPersistsAndInvalidValuesAreRejected() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferencesManager manager = SharedPreferencesManager.getInstance(context);
        SharedPreferences preferences = manager.sharedPreferences;
        boolean hadOption = preferences.contains(
                SharedPreferencesManager.PREF_MAX_RECORDING_DURATION_OPTION);
        boolean hadCustom = preferences.contains(
                SharedPreferencesManager.PREF_MAX_RECORDING_DURATION_CUSTOM_MINUTES);
        String previousOption = preferences.getString(
                SharedPreferencesManager.PREF_MAX_RECORDING_DURATION_OPTION, null);
        int previousCustom = preferences.getInt(
                SharedPreferencesManager.PREF_MAX_RECORDING_DURATION_CUSTOM_MINUTES,
                MaximumRecordingDuration.DEFAULT_CUSTOM_MINUTES);

        try {
            assertTrue(manager.setCustomMaximumRecordingDurationMinutes(77));
            assertTrue(manager.setMaximumRecordingDurationOption(
                    MaximumRecordingDuration.OPTION_CUSTOM));
            assertEquals(77, manager.getCustomMaximumRecordingDurationMinutes());
            assertEquals(77L * 60_000L, manager.getMaximumRecordingDurationMs());

            assertFalse(manager.setCustomMaximumRecordingDurationMinutes(0));
            assertFalse(manager.setCustomMaximumRecordingDurationMinutes(1441));
            assertFalse(manager.setMaximumRecordingDurationOption("invalid"));
            assertEquals(77, manager.getCustomMaximumRecordingDurationMinutes());
            assertEquals(MaximumRecordingDuration.OPTION_CUSTOM,
                    manager.getMaximumRecordingDurationOption());
        } finally {
            SharedPreferences.Editor editor = preferences.edit();
            if (hadOption) {
                editor.putString(
                        SharedPreferencesManager.PREF_MAX_RECORDING_DURATION_OPTION,
                        previousOption);
            } else {
                editor.remove(SharedPreferencesManager.PREF_MAX_RECORDING_DURATION_OPTION);
            }
            if (hadCustom) {
                editor.putInt(
                        SharedPreferencesManager.PREF_MAX_RECORDING_DURATION_CUSTOM_MINUTES,
                        previousCustom);
            } else {
                editor.remove(
                        SharedPreferencesManager.PREF_MAX_RECORDING_DURATION_CUSTOM_MINUTES);
            }
            editor.commit();
        }
    }
}
