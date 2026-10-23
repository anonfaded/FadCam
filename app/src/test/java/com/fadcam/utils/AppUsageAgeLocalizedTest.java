package com.fadcam.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Locale;

/**
 * The weekly reminder injects the app age into translated copy. These tests pin the two paths
 * that are easy to regress: ICU's localized units and the hand-written Roman Pashto units.
 */
@RunWith(RobolectricTestRunner.class)
public class AppUsageAgeLocalizedTest {

    private static long days(long d) {
        return d * 24L * 60L * 60L * 1000L;
    }

    @Test
    public void englishUsesLocalizedUnits() {
        Context context = RuntimeEnvironment.getApplication();
        String value = AppUsageAge.twoUnits(context, days(400));
        assertTrue(value, value.contains("1 year"));
        assertTrue(value, value.contains("1 month"));
    }

    @Test
    public void pashtoStaysRomanAndPluralizes() {
        Context context = RuntimeEnvironment.getApplication();
        Configuration configuration = new Configuration(
                context.getResources().getConfiguration());
        configuration.setLocale(Locale.forLanguageTag("ps"));
        Context pashto = context.createConfigurationContext(configuration);

        String value = AppUsageAge.twoUnits(pashto, days(400));
        assertEquals("1 kal 1 myasht", value);
        assertFalse("Pashto must not fall back to Arabic script: " + value,
                value.matches(".*[\\u0600-\\u06FF].*"));
    }
}
