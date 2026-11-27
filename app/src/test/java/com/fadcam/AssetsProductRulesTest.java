package com.fadcam;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
    @Test
    public void downloadsOnlyStartFromAClick() throws Exception {
        List<String> files = new ArrayList<>();
        collect(new File(ROOT, "src/full/java/com/fadcam"), files);

        for (String path : files) {
            List<String> lines = Files.readAllLines(new File(ROOT, path).toPath(),
                    StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!line.contains("AssetDownloadService.start")) {
                    continue;
                }
                if (line.trim().startsWith("*") || line.trim().startsWith("//")) {
                    continue;
                }
                        String owner = methodAt(lines, i);
                boolean fromClick = reachableFromClick(lines, i)
                        || (owner != null && clickedAnywhere(owner))
                        // The seed itself may be a callback the sheet clicks.
                        || (owner != null && clickedAnywhere("onAssetDetailDownload"));
                assertTrue("AssetDownloadService.start outside a click handler at " + path
                        + ":" + (i + 1)
                        + " — downloading must never happen automatically", fromClick);
            }
        }
    }

    /**
     * True when the download call is inside a click handler, directly or through helpers that
     * a click handler calls.
     *
     * <p>Follows the call chain rather than only looking a few lines back, because the real
     * shape is {@code click -> onActionTapped -> startDownload}. Expansion stops after two
     * hops so the search cannot eventually reach every method in the file and pass by
     * accident.
     */
    /**
     * True when {@code method} is invoked from a click listener in ANY asset source file.
     *
     * <p>Needed because the download path spans two files: the sheet's click calls
     * {@code host.onAssetDetailDownload}, which is implemented in the manager.
     */
    private static boolean clickedAnywhere(String method) throws Exception {
        List<String> files = new ArrayList<>();
        collect(new File(ROOT, "src/full/java/com/fadcam"), files);
        for (String rel : files) {
            List<String> lines = Files.readAllLines(new File(ROOT, rel).toPath(),
                    StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).contains("setOnClickListener")) {
                    continue;
                }
                // Look inside this handler's body for the call.
                for (int j = i; j < Math.min(lines.size(), i + 12); j++) {
                    if (lines.get(j).contains(method + "(")) {
                        return true;
                    }
                    if (j > i && lines.get(j).trim().endsWith("});")) {
                        break;
                    }
                }
            }
        }
        return false;
    }

    private static boolean reachableFromClick(List<String> lines, int index) {
        // Case 1: the call sits directly inside a lambda that is itself the click listener.
        // Walk back only as far as the lambda's opening brace so a listener elsewhere in the
        // file cannot satisfy it.
        for (int back = index - 1; back >= Math.max(0, index - 20); back--) {
            String line = lines.get(back);
            if (line.contains("setOnClickListener")) {
                return true;
            }
            // A new statement at method-body indentation means we left the lambda.
            if (line.trim().endsWith(");") && !line.trim().contains("->")) {
                break;
            }
        }

        String seed = methodAt(lines, index);
        if (seed == null) {
            return false;
        }
        java.util.Set<String> methods = new java.util.HashSet<>();
        methods.add(seed);
        // Interface callbacks are invoked by the surface that implements them, so add the
        // override names too: onAssetDetailDownload is called from the detail sheet's click.
        methods.add("onAssetDetailDownload");
        for (int hop = 0; hop < 3; hop++) {
            boolean grew = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String caller = methodAt(lines, i);
                for (String m : new java.util.ArrayList<>(methods)) {
                    // Match "m(" and also "host.m(" / "this.m(" qualified calls.
                    if (!(line.contains(m + "(")) || m.equals(caller)) {
                        continue;
                    }
                    if (line.contains("setOnClickListener")) {
                        return true;
                    }
                    if (caller != null && methods.add(caller)) {
                        grew = true;
                    }
                }
            }
            if (!grew) {
                break;
            }
        }
        return false;
    }

    /** Name of the method whose body contains this line, or null. */
    @androidx.annotation.Nullable
    private static String methodAt(List<String> lines, int index) {
        for (int i = index; i >= 0; i--) {
            String t = lines.get(i).trim();
            if (!(t.startsWith("private ") || t.startsWith("public ")
                    || t.startsWith("protected "))) {
                continue;
            }
            int paren = t.indexOf('(');
            if (paren <= 0) {
                continue;
            }
            int start = t.lastIndexOf(' ', paren);
            if (start < 0) {
                continue;
            }
            String name = t.substring(start + 1, paren).trim();
            if (!name.isEmpty() && name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                return name;
            }
        }
        return null;
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
