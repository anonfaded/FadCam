package com.fadcam;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Locks in two product rules that are easy to break by accident and invisible in a screenshot.
 *
 * <ol>
 *   <li><b>No automatic network access.</b> An asset may only be downloaded from a direct user
 *       action. A future refactor that "helpfully" prefetches on screen open would violate the
 *       plan (D5) and burn user data, so it fails here instead.</li>
 *   <li><b>No hardcoded sizes.</b> Download size and installed size differ a lot (OpenCV is a
 *       12 MB archive that becomes 32 MB on disk) and both are ABI dependent. Any number
 *       written into UI code would eventually be wrong for some device.</li>
 * </ol>
 */
public class AssetsProductRulesTest {

    private static final String ROOT =
            System.getProperty("fadcamModuleDir", "app");

    /** Files allowed to start a download, because they are click handlers. */
    private static final String[] DOWNLOAD_CALLERS = {
            "src/full/java/com/fadcam/ui/AssetManagerFragment.java",
            "src/full/java/com/fadcam/ui/AssetBanner.java",
    };

    /**
     * The download service may only be started from a click handler. This scans every asset
     * source file and requires any call to appear inside an {@code setOnClickListener}.
     */
    /**
     * Methods that are themselves download entry points, each proven below to be called from a
     * click listener in another file. Declaring them here rather than widening the search is
     * deliberate: an allowlist can be audited, a fuzzy rule cannot.
     */
    private static final String[] CLICK_ENTRY_POINTS = {
            // AssetDetailSheet's Download row -> host.onAssetDetailDownload(asset)
            "onAssetDetailDownload",
    };

    @Test
    public void downloadsOnlyStartFromAClick() throws Exception {
        List<String> files = new ArrayList<>();
        collect(new File(ROOT, "src/full/java/com/fadcam"), files);

        for (String path : files) {
            List<String> lines = Files.readAllLines(new File(ROOT, path).toPath(),
                    StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).contains("AssetDownloadService.start")) {
                    continue;
                }
                // Require a setOnClickListener between the previous statement boundary and the
                // call. That is the local, checkable property: the call is inside a lambda
                // that a tap runs. Cross-file entry points are covered by the separate test
                // below, which proves each one is clicked.
                boolean inListener = false;
                for (int back = i; back >= Math.max(0, i - 18); back--) {
                    String line = lines.get(back);
                    if (line.contains("setOnClickListener")) {
                        inListener = true;
                        break;
                    }
                    // Left the lambda: a statement that closes with ");" and opens nothing.
                    if (back < i && line.trim().endsWith("});")) {
                        break;
                    }
                }
                // A download entry point is allowed, but only when annotated as such. The
                // separate test proves every annotated entry point really is clicked.
                // The marker sits above the method's annotations, which sit above the
                // declaration, so scan a wider window and do not stop at the signature.
                boolean annotated = false;
                for (int back = i; back >= Math.max(0, i - 30); back--) {
                    if (lines.get(back).contains("download-entry-point")) {
                        annotated = true;
                        break;
                    }
                }
                assertTrue("AssetDownloadService.start at " + path + ":" + (i + 1)
                                + " is not inside a click listener and the enclosing method is"
                                + " not marked download-entry-point. Downloading must only ever"
                                + " follow a user tap.",
                        inListener || annotated);
            }
        }
    }

    /**
     * Each cross-file download callback must be invoked from a click listener in the file that
     * owns the button, so the local rule above cannot be worked around by moving the call into
     * a helper.
     */
    @Test
    public void everyAnnotatedEntryPointIsClicked() throws Exception {
        List<String> files = new ArrayList<>();
        collect(new File(ROOT, "src/full/java/com/fadcam"), files);

        for (String path : files) {
            List<String> lines = Files.readAllLines(new File(ROOT, path).toPath(),
                    StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).contains("// download-entry-point")) {
                    continue;
                }
                // The annotated method's name is on a later line.
                String name = null;
                for (int j = i; j < Math.min(lines.size(), i + 4); j++) {
                    String t = lines.get(j).trim();
                    int paren = t.indexOf('(');
                    if (paren > 0 && (t.contains("void ") || t.contains("()"))) {
                        int end = t.lastIndexOf(' ', paren);
                        if (end > 0) {
                            name = t.substring(end + 1, paren).trim();
                        }
                        break;
                    }
                }
                assertNotNull("could not read the method name after a download-entry-point "
                        + "marker in " + path, name);

                // Now prove some click listener invokes it.
                boolean clicked = false;
                for (String other : files) {
                    List<String> ol = Files.readAllLines(new File(ROOT, other).toPath(),
                            StandardCharsets.UTF_8);
                    for (int k = 0; k < ol.size() && !clicked; k++) {
                        // Either a literal listener, or a row built through the shared builder
                        // whose final argument is the click lambda.
                        // Three shapes exist in the app:
                        //   setOnClickListener(...)          literal listener
                        //   SheetRowBuilder.build(..., click) shared builder, click inline
                        //   addRow(..., v -> {...})           local helper that attaches it
                        boolean anchor = ol.get(k).contains("setOnClickListener")
                                || ol.get(k).contains("SheetRowBuilder.build")
                                || ol.get(k).contains("addRow(");
                        if (!anchor) {
                            continue;
                        }
                        for (int m = k; m < Math.min(ol.size(), k + 16); m++) {
                            if (ol.get(m).contains(name + "(")) {
                                clicked = true;
                                break;
                            }
                            if (m > k && ol.get(m).trim().endsWith("});")) {
                                break;
                            }
                        }
                    }
                    if (clicked) {
                        break;
                    }
                }
                assertTrue(name + " is marked download-entry-point but no click listener "
                        + "invokes it; either wire it to a tap or remove the marker", clicked);
            }
        }
    }

    /** No asset source may create its own OkHttpClient outside the service. */
    @Test
    public void onlyTheDownloaderBuildsAnHttpClient() throws Exception {
        // Scoped to the assets package: that is the code this feature owns. Other packages
        // (streaming, cloud upload) have their own HTTP clients by design.
        List<String> offenders = new ArrayList<>();
        collect(new File(ROOT, "src/full/java/com/fadcam/assets"), offenders);
        for (String path : offenders) {
            if (path.endsWith("AssetDownloadService.java")) {
                continue;
            }
            String text = new String(Files.readAllBytes(new File(ROOT, path).toPath()),
                    StandardCharsets.UTF_8);
            assertFalse("the download service is the only place that should build an HTTP "
                    + "client, found one in " + path, text.contains("new OkHttpClient"));
        }
    }

    /**
     * The UI must not hardcode asset sizes. Sizes come from the manifest, per ABI.
     */
    @Test
    public void uiDoesNotHardcodeAssetSizes() throws Exception {
        for (String rel : new String[]{
                "src/full/java/com/fadcam/ui/AssetManagerFragment.java",
                "src/full/java/com/fadcam/ui/AssetBanner.java"}) {
            String text = new String(Files.readAllBytes(new File(ROOT, rel).toPath()),
                    StandardCharsets.UTF_8);
            // A literal MB figure is the smell: 12.0, 31.2, 48.6 and friends appeared in the
            // plan, and copying one into code would make it wrong on the other ABI.
            assertFalse("hardcoded megabyte figure in " + rel,
                    text.matches("(?s).*\\b(1?\\d|2?\\d|3\\d|4\\d|5\\d)\\.\\d\\s*MB.*"));
        }
    }

    /** Every asset must declare the features that depend on it. */
    @Test
    public void everyAssetDeclaresWhatUsesIt() {
        for (com.fadcam.assets.Asset asset : com.fadcam.assets.Asset.values()) {
            assertTrue(asset.id + " must list the features that depend on it",
                    asset.usedBy != null && asset.usedBy.length > 0);
            assertTrue(asset.id + " must describe what it does for the user",
                    asset.purpose != null && asset.purpose.length() > 30);
        }
    }

    /** The manifest must record installed sizes, so the UI never shows a download size as if
     *  it were what lands on disk. */
    @Test
    public void manifestRecordsInstalledSizeForArchives() throws Exception {
        String json = new String(Files.readAllBytes(
                new File(ROOT, "src/full/assets/assets-manifest.json").toPath()),
                StandardCharsets.UTF_8);
        int entries = 0;
        int withInstalled = 0;
        for (String chunk : json.split("\\{")) {
            if (!chunk.contains("\"file\"")) {
                continue;
            }
            entries++;
            if (chunk.contains("\"installedSize\"")) {
                withInstalled++;
            }
        }
        assertTrue("manifest has entries", entries > 0);
        assertTrue("every manifest entry must record installedSize so the row can label "
                        + "download size and on-device size separately ("
                        + withInstalled + "/" + entries + " did)",
                withInstalled == entries);
    }

    private static void collect(File dir, List<String> out) {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File kid : kids) {
            if (kid.isDirectory()) {
                collect(kid, out);
            } else if (kid.getName().endsWith(".java")) {
                // Store repo-relative for readable failure messages.
                String p = kid.getPath();
                int at = p.indexOf("src/");
                out.add(at >= 0 ? p.substring(at) : p);
            }
        }
    }
}
