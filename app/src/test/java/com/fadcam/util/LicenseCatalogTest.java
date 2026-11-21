package com.fadcam.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;
import java.util.Map;

/**
 * Guards the attribution screen against the two ways it can silently mislead:
 * a licence name the normaliser fails to collapse, and data that is referenced
 * but not actually packaged.
 */
@RunWith(RobolectricTestRunner.class)
public class LicenseCatalogTest {

    @Test
    public void normalizesTheSpellingsPomsActuallyProduce() {
        // These are the exact strings that arrived from the real dependency graph;
        // leaving them distinct produced five Apache sections on screen.
        String[] apache = {
                "The Apache Software License, Version 2.0",
                "Apache License, Version 2.0",
                "Apache-2.0",
                "The Apache License, Version 2.0",
                "Apache 2.0",
        };
        for (String raw : apache) {
            assertEquals(raw, "Apache License 2.0", LicenseCatalog.normalize(raw));
        }

        assertEquals("BSD 3-Clause", LicenseCatalog.normalize("The 3-Clause BSD License"));
        assertEquals("BSD 3-Clause", LicenseCatalog.normalize("BSD-3-Clause"));
        assertEquals("BSD 3-Clause", LicenseCatalog.normalize("The BSD 3-Clause License"));
        // Glide and others publish the 3-clause text under the bare "BSD License"
        // name; mapping it to BSD 3-Clause is what gives it bundled text.
        assertEquals("BSD 3-Clause", LicenseCatalog.normalize("Simplified BSD License"));
        assertEquals("MIT", LicenseCatalog.normalize("The MIT License"));
        assertEquals("GPL-3.0", LicenseCatalog.normalize("GNU General Public License v3.0"));
        assertEquals("LGPL-3.0", LicenseCatalog.normalize("GNU Lesser General Public License"));
        assertEquals("Android SDK License", LicenseCatalog.normalize("Android Software Development Kit License"));
        assertEquals("Play Core SDK Terms", LicenseCatalog.normalize("Play Core Software Development Kit Terms of Service"));
    }

    @Test
    public void blankOrNullBecomesUnknownRatherThanAnEmptyHeading() {
        assertEquals("Unknown", LicenseCatalog.normalize(null));
        assertEquals("Unknown", LicenseCatalog.normalize(""));
        assertEquals("Unknown", LicenseCatalog.normalize("   "));
    }

    @Test
    public void everyRedistributableLicenseHasBundledText() {
        // Apache-2.0 section 4(a) requires a copy of the License to accompany the
        // binary, so these must resolve to an asset rather than to a URL.
        assertNotNull(LicenseCatalog.assetFor("Apache License 2.0"));
        assertNotNull(LicenseCatalog.assetFor("BSD 3-Clause"));
        assertNotNull(LicenseCatalog.assetFor("BSD 2-Clause"));
        assertNotNull(LicenseCatalog.assetFor("GPL-3.0"));

        // Terms we may link to but not redistribute.
        assertNull(LicenseCatalog.assetFor("Play Core SDK Terms"));
        assertNull(LicenseCatalog.assetFor("Android SDK License"));
    }

    @Test
    public void loadsTheGeneratedAssetAndGroupsIt() {
        Map<String, List<String[]>> grouped =
                LicenseCatalog.load(RuntimeEnvironment.getApplication());

        assertTrue("generated license asset missing from build output", !grouped.isEmpty());

        int total = 0;
        for (Map.Entry<String, List<String[]>> e : grouped.entrySet()) {
            assertTrue("empty group for " + e.getKey(), !e.getValue().isEmpty());
            total += e.getValue().size();
        }
        // The dependency graph is far larger than this; a low count means the
        // report was generated for the wrong configuration.
        assertTrue("only " + total + " libraries parsed", total > 50);

        // Apache dominates this app, so it must sort first by design.
        assertEquals("Apache License 2.0", grouped.keySet().iterator().next());

        List<String[]> apache = grouped.get("Apache License 2.0");
        assertNotNull(apache);
        boolean hasDependency = false;
        for (String[] row : apache) {
            if (row[1] != null && !row[1].isEmpty()) {
                hasDependency = true;
                break;
            }
        }
        assertTrue("rows should carry a maven coordinate", hasDependency);
    }

    @Test
    public void bundledLicenseTextsArePresentAndNonTrivial() {
        for (String name : new String[]{"Apache License 2.0", "BSD 3-Clause", "BSD 2-Clause", "GPL-3.0"}) {
            String asset = LicenseCatalog.assetFor(name);
            assertNotNull(asset);
            String text = LicenseCatalog.readAsset(RuntimeEnvironment.getApplication(), asset);
            assertTrue(name + " text is too short to be a real license", text.length() > 1000);
            // A fetched HTML page would have slipped through as "text" otherwise.
            assertTrue(name + " is HTML, not plain text",
                    !text.toLowerCase().contains("<!doctype html"));
        }
    }
}
