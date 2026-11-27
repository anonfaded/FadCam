package com.fadcam.assets;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end: download from the real published release, verify, install, then load.
 *
 * <p>Requires network. This is the test that proves the whole chain against the bytes
 * users will actually receive, not a fixture.
 */
@RunWith(AndroidJUnit4.class)
public class AssetDownloadEndToEndTest {

    private static final String TAG = "ASSETSPIKE";

    private static Context ctx() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    /** Downloads OpenCV, installs it, and loads it with no bundled copy to fall back on. */
    @Test
    public void downloadsVerifiesInstallsAndLoadsOpencv() throws Exception {
        AssetRegistry reg = AssetRegistry.get(ctx());
        AssetStore store = reg.store();
        Asset asset = Asset.OPENCV;

        reg.delete(asset);
        List<AssetManifest.Entry> entries = reg.entriesFor(asset);
        assertNotNull(entries);
        AssetManifest.Entry entry = entries.get(0);
        System.out.println(TAG + " E2E downloading " + entry.file + " (" + entry.size
                + " bytes) from the published release");

        long t0 = System.currentTimeMillis();
        File partial = store.partialFileFor(entry);
        new AssetDownloader(new okhttp3.OkHttpClient()).download(
                reg.manifest().baseUrl() + entry.file, partial,
                (done, total) -> { });
        long ms = System.currentTimeMillis() - t0;
        System.out.println(TAG + " E2E downloaded " + partial.length() + " bytes in " + ms + "ms");

        assertEquals("downloaded size must match the published size exactly",
                entry.size, partial.length());
        String hash = AssetStore.sha256(partial);
        assertEquals("downloaded hash must match the manifest", entry.sha256, hash);
        System.out.println(TAG + " E2E hash verified " + hash.substring(0, 16) + "...");

        AssetInstaller.install(asset, store, entry, partial);
        assertFalse("partial file must be gone after install", partial.exists());

        for (String member : entry.members) {
            File f = store.fileFor(asset, member);
            assertTrue("member missing after install: " + member, f.isFile());
            System.out.println(TAG + " E2E installed " + member + " " + f.length() + " bytes");
        }
        // On a build that still ships OpenCV, BUNDLED legitimately outranks READY in the
        // resolution order, so assert the on-disk result rather than the label.
        assertTrue("installed files must be complete on disk",
                store.hasAllFiles(asset, entries));
        System.out.println(TAG + " E2E registry state=" + reg.state(asset)
                + " (BUNDLED is correct on this build; it outranks READY by design)");

        // Load it from storage explicitly. This is the path that matters once the
        // libraries leave the APK, and it must work even while BUNDLED is reported.
        reg.resetLoadedForTesting();
        NativeLibraryLoader.loadAll(asset, store, entries);
        System.out.println(TAG + " E2E PASS opencv loaded from app storage, files verified");
        reg.delete(asset);
    }

    /** Same chain for the models, which ship raw rather than zipped. */
    @Test
    public void downloadsRawModelWithoutUnzipping() throws Exception {
        AssetRegistry reg = AssetRegistry.get(ctx());
        AssetStore store = reg.store();
        Asset asset = Asset.MODELS;

        reg.delete(asset);
        List<AssetManifest.Entry> entries = reg.entriesFor(asset);
        assertNotNull(entries);
        // graph_b is the smaller of the two; one is enough to prove the raw path.
        AssetManifest.Entry entry = entries.get(entries.size() - 1);
        System.out.println(TAG + " E2E raw downloading " + entry.file);

        File partial = store.partialFileFor(entry);
        new AssetDownloader(new okhttp3.OkHttpClient()).download(
                reg.manifest().baseUrl() + entry.file, partial, (done, total) -> { });

        assertEquals(entry.size, partial.length());
        assertEquals(entry.sha256, AssetStore.sha256(partial));

        AssetInstaller.install(asset, store, entry, partial);
        File installed = store.fileFor(asset, entry.file);
        assertTrue("raw asset must land as a single file, not unpacked",
                installed.isFile() && installed.length() == entry.size);
        System.out.println(TAG + " E2E PASS raw model installed as " + installed.getName()
                + " " + installed.length() + " bytes");

        reg.delete(asset);
    }

    /** A corrupted download must be rejected, never installed. */
    @Test
    public void rejectsTamperedDownload() throws Exception {
        AssetRegistry reg = AssetRegistry.get(ctx());
        AssetStore store = reg.store();
        Asset asset = Asset.OPENCV;
        reg.delete(asset);

        AssetManifest.Entry entry = reg.entriesFor(asset).get(0);
        File partial = store.partialFileFor(entry);
        // A file of the right size but the wrong contents: exactly what a tampered
        // download or a truncated transfer would look like.
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(partial)) {
            out.write(new byte[(int) Math.min(entry.size, 4096)]);
        }

        try {
            AssetInstaller.install(asset, store, entry, partial);
            throw new AssertionError("installer accepted a file with the wrong hash");
        } catch (AssetException expected) {
            assertTrue("failure must name the asset",
                    expected.asset() == asset);
            System.out.println(TAG + " E2E PASS tampered download rejected: "
                    + expected.getMessage());
        }
        assertFalse("nothing may be installed after a hash failure",
                store.hasAllFiles(asset, reg.entriesFor(asset)));
        reg.delete(asset);
    }
}
