package com.fadcam.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The app-age line is shown in two places, so its wording is pinned here. */
public class AppUsageAgeTest {

    private static long days(long d) {
        return d * 24L * 60L * 60L * 1000L;
    }

    @Test
    public void usesDaysInsteadOfWeeks() {
        assertEquals("5 days", AppUsageAge.duration(days(5)));
        assertEquals("1 month, 9 days", AppUsageAge.duration(days(39)));
    }

    @Test
    public void showsEveryUnit() {
        assertEquals("1 year, 1 month, 5 days", AppUsageAge.duration(days(400)));
        assertEquals("1 month, 9 days, 16 hours, 25 minutes",
                AppUsageAge.duration(days(39) + 16L * 60L * 60L * 1000L + 25L * 60L * 1000L));
    }

    @Test
    public void freshInstallReadsNaturally() {
        assertEquals("under a minute", AppUsageAge.duration(0L));
        assertEquals("1 minute", AppUsageAge.duration(60000L));
    }

    @Test
    public void twoUnitsKeepsExactlyTheTwoLargestParts() {
        assertEquals("1 year 1 month", AppUsageAge.twoUnits(days(400)));
        assertEquals("1 month 9 days", AppUsageAge.twoUnits(days(39)));
        assertEquals("5 days 4 hours",
                AppUsageAge.twoUnits(days(5) + 4L * 60L * 60L * 1000L));
    }

    @Test
    public void twoUnitsOmitsZeroUnits() {
        assertEquals("1 year", AppUsageAge.twoUnits(days(365)));
        assertEquals("1 month", AppUsageAge.twoUnits(days(30)));
        assertEquals("2 days", AppUsageAge.twoUnits(days(2)));
        assertEquals("23 hours", AppUsageAge.twoUnits(23L * 60L * 60L * 1000L));
    }

    @Test
    public void twoUnitsHandlesVeryFreshInstalls() {
        assertEquals("under an hour", AppUsageAge.twoUnits(0L));
        assertEquals("under an hour", AppUsageAge.twoUnits(20L * 60L * 1000L));
    }
}
