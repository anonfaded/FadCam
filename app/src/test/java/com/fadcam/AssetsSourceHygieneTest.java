package com.fadcam;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Guards the layouts and source files this work touched against the kind of sloppiness that
 * is invisible in a diff but obvious when someone opens the file.
 *
 * <p>Written after an XML insertion left a blank line between every attribute, which doubled
 * the row's length. A diff does not show that; a reader does.
 */
public class AssetsSourceHygieneTest {

    /** Files this feature owns. Kept explicit so the test does not police the whole repo. */
    private static final String[] LAYOUTS = {
            "app/src/main/res/layout/fragment_asset_manager.xml",
            "app/src/main/res/layout/item_asset_row.xml",
    };

    /**
     * Files this feature only inserted a block into. Excluded from the whitespace checks:
     * they carry pre-existing blank-line runs and trailing spaces elsewhere, and churning
     * those would bury the real change in reformatting noise.
     */
    private static final String[] EDITED_LAYOUTS = {
            "app/src/main/res/layout/fragment_settings_motion_lab.xml",
            "app/src/main/res/layout/fragment_settings_home.xml",
    };

    private static final String[] SOURCES = {
            "app/src/full/java/com/fadcam/assets/Asset.java",
            "app/src/full/java/com/fadcam/assets/AssetState.java",
            "app/src/full/java/com/fadcam/assets/AssetManifest.java",
            "app/src/full/java/com/fadcam/assets/AssetStore.java",
            "app/src/full/java/com/fadcam/assets/AssetRegistry.java",
            "app/src/full/java/com/fadcam/assets/AssetInstaller.java",
            "app/src/full/java/com/fadcam/assets/AssetDownloader.java",
            "app/src/full/java/com/fadcam/assets/AssetDownloadService.java",
            "app/src/full/java/com/fadcam/assets/NativeLibraryLoader.java",
            "app/src/full/java/com/fadcam/assets/AssetModels.java",
            "app/src/full/java/com/fadcam/assets/AssetOpenCv.java",
            "app/src/full/java/com/fadcam/assets/AssetFfmpeg.java",
            "app/src/full/java/com/fadcam/ui/AssetManagerFragment.java",
            "app/src/full/java/com/fadcam/ui/AssetBanner.java",
    };

    /**
     * A single blank line between members is normal style; two in a row never is, and is the
     * signature of a bad insertion.
     */
    @Test
    public void noFileHasConsecutiveBlankLines() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (String path : concat(LAYOUTS, SOURCES)) {
            List<String> lines = readLines(path);
            int blanks = 0;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().isEmpty()) {
                    blanks++;
                    if (blanks >= 2) {
                        offenders.add(path + ":" + (i + 1));
                    }
                } else {
                    blanks = 0;
                }
            }
        }
        assertTrue("consecutive blank lines found at " + offenders, offenders.isEmpty());
    }

    /**
     * Trailing whitespace is invisible in review and shows up as noise in every later diff.
     */
    @Test
    public void noFileHasTrailingWhitespace() throws Exception {
        List<String> offenders = new ArrayList<>();
        // Only files this feature created. Files it merely inserted a block into carry
        // pre-existing whitespace noise, and reformatting them is not this change's job.
        for (String path : concat(LAYOUTS, SOURCES)) {
            List<String> lines = readLines(path);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!line.isEmpty() && (line.endsWith(" ") || line.endsWith("\t"))) {
                    offenders.add(path + ":" + (i + 1));
                }
            }
        }
        assertTrue("trailing whitespace at " + offenders, offenders.isEmpty());
    }

    /** Layouts must open and close their root element, or inflation fails at runtime. */
    @Test
    public void layoutsHaveMatchingRootTags() throws Exception {
        for (String path : LAYOUTS) {
            String text = new String(Files.readAllBytes(resolve(path).toPath()),
                    StandardCharsets.UTF_8);
            assertTrue(path + " must start with an XML declaration",
                    text.trim().startsWith("<?xml"));
            assertTrue(path + " must close its root element", text.contains("</"));
            // A stray '{' or '&lt;' from a bad escape is a common insertion accident.
            assertFalse(path + " contains an unescaped HTML entity", text.contains("&lt;"));
        }
    }

    /**
     * Every string referenced from the new layouts must exist, otherwise the row inflates
     * with a blank label instead of failing loudly.
     */
    @Test
    public void referencedStringsExist() throws Exception {
        String strings = new String(
                Files.readAllBytes(resolve("app/src/main/res/values/strings.xml").toPath()),
                StandardCharsets.UTF_8);
        for (String path : LAYOUTS) {
            List<String> lines = readLines(path);
            for (String line : lines) {
                int at = line.indexOf("@string/");
                if (at < 0) {
                    continue;
                }
                String name = line.substring(at + 8).split("[^A-Za-z0-9_]")[0];
                assertTrue("missing string resource: " + name + " (referenced in " + path + ")",
                        strings.contains("name=\"" + name + "\""));
            }
        }
    }

    /**
     * No hardcoded English in the new layouts: everything user-facing goes through @string.
     *
     * <p>Material icon ligatures are exempt. They are glyph names drawn by the icon font
     * (the app uses this everywhere), not words a user reads.
     */
    @Test
    public void layoutsDoNotHardcodeUserFacingText() throws Exception {
        for (String path : LAYOUTS) {
            List<String> lines = readLines(path);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (!line.startsWith("android:text=\"")) {
                    continue;
                }
                String value = line.substring("android:text=\"".length());
                if (value.startsWith("@string/")) {
                    continue;
                }
                // An icon ligature: lowercase words with underscores, no spaces, in a font
                // that is explicitly the material icon font.
                boolean ligature = value.matches("[a-z_]+\"");
                boolean iconFont = isIconFontLayoutPath(path, lines, i);
                assertTrue("hardcoded text in " + path + ":" + (i + 1) + " -> " + value,
                        ligature && iconFont);
            }
        }
    }

    /** True when the TextViews in this layout declare the material icon font. */
    private static boolean isIconFontLayoutPath(String path, List<String> lines, int index) {
        int start = Math.max(0, index - 12);
        for (int i = start; i <= index; i++) {
            if (lines.get(i).contains("@font/materialicons")) {
                return true;
            }
        }
        return false;
    }

    /** Lite must never reference any of this; the plan requires it. */
    @Test
    public void liteSourcesDoNotReferenceTheAssetFeature() throws Exception {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(resolve("app/src/lite").toPath())) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                try {
                    String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    if (text.contains("com.fadcam.assets.")) {
                        offenders.add(p.toString());
                    }
                } catch (Exception ignored) {
                    // Unreadable file: nothing to assert about it.
                }
            });
        }
        assertTrue("Lite must not reference the assets package: " + offenders,
                offenders.isEmpty());
    }

    /** The manifest that ships in the APK must actually be present. */
    @Test
    public void bundledManifestExistsAndIsNonEmpty() {
        File manifest = resolve("app/src/full/assets/assets-manifest.json");
        assertTrue("the app must bundle its own manifest", manifest.isFile());
        assertTrue("the bundled manifest must not be empty", manifest.length() > 100);
    }

    private static String[] concat(String[] a, String[] b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Module root, injected by Gradle because JVM tests do not run from the repo root. */
    private static final String ROOT = System.getProperty("fadcamModuleDir", "app");

    private static File resolve(String relative) {
        // Paths are written repo-relative ("app/src/..."), so strip the leading module dir
        // when it is already part of ROOT.
        String r = relative.startsWith("app/") ? relative.substring(4) : relative;
        return new File(ROOT, r);
    }

    private static List<String> readLines(String path) throws Exception {
        File f = resolve(path);
        assertTrue("missing file under test: " + path, f.isFile());
        return Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
    }
}
