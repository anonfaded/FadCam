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
 * Instrumented checks that the registry reads the real bundled manifest and reports
 * sane state on a real device.
 *
 * <p>Deliberately does not download anything: this proves the wiring, not the network.
 * The download path gets its own test once the service exists.
 */
@RunWith(AndroidJUnit4.class)
public class AssetRegistryTest {

    private static final String TAG = "ASSETSPIKE";

    private static Context ctx() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void bundledManifestParsesAndResolvesEveryAsset() {
        AssetRegistry reg = AssetRegistry.get(ctx());
        AssetManifest m = reg.manifest();
        assertNotNull("assets-manifest.json must ship inside the APK", m);
        assertEquals("assets-v1.0", m.assetSet());
        assertTrue("base URL must be the pinned release tag, never 'latest'",
                m.baseUrl().contains("/releases/download/assets-v1.0/"));

        for (Asset a : Asset.values()) {
            List<AssetManifest.Entry> e = reg.entriesFor(a);
            assertNotNull("no manifest entry resolves for " + a.id
                    + " on this device (abi=" + android.os.Build.SUPPORTED_ABIS[0] + ")", e);
            assertFalse("entry list is empty for " + a.id, e.isEmpty());
            System.out.println(TAG + " REGISTRY " + a.id + " -> " + e.get(0).file
                    + " size=" + e.get(0).size + " abi=" + e.get(0).abi);
        }
    }

    @Test
    public void resolvesTheAbiThisDeviceActuallyRuns() {
        AssetRegistry reg = AssetRegistry.get(ctx());
        String abi = android.os.Build.SUPPORTED_ABIS[0];
        AssetManifest.Entry opencv = reg.entriesFor(Asset.OPENCV).get(0);
        assertEquals("must pick the file for the primary ABI, not the first listed",
                abi, opencv.abi);
        AssetManifest.Entry models = reg.entriesFor(Asset.MODELS).get(0);
        assertEquals("models are ABI independent", "universal", models.abi);
    }

    @Test
    public void noAssetIsBundledAnyMore() {
        AssetRegistry reg = AssetRegistry.get(ctx());
        // All three heavy payloads left the APK. This is the assertion that would catch a
        // regression where one quietly gets packaged again.
        for (Asset a : Asset.values()) {
            assertFalse(a.id + " must not report BUNDLED any more", reg.isBundled(a));
        }
        System.out.println(TAG + " REGISTRY opencv=" + reg.state(Asset.OPENCV)
                + " ffmpeg=" + reg.state(Asset.FFMPEG)
                + " models=" + reg.state(Asset.MODELS));
    }

    @Test
    public void downloadSizeMatchesPublishedFile() {
        AssetRegistry reg = AssetRegistry.get(ctx());
        long size = reg.downloadSize(Asset.OPENCV);
        AssetManifest.Entry e = reg.entriesFor(Asset.OPENCV).get(0);
        assertEquals("download size must be the published file size", e.size, size);
        System.out.println(TAG + " REGISTRY opencv downloadSize=" + size
                + " ffmpeg=" + reg.downloadSize(Asset.FFMPEG)
                + " models=" + reg.downloadSize(Asset.MODELS));
    }

    @Test
    public void leftoverFilesReportCorruptForAssetsNotInTheApk() {
        AssetRegistry reg = AssetRegistry.get(ctx());
        AssetStore store = reg.store();
        // Drive the on-disk checks directly so this does not depend on what happens to be
        // installed on the test device.
        Asset asset = Asset.FFMPEG;

        // Drive AssetStore directly so this never depends on what is installed.
        File fake = store.fileFor(asset, "libavcodec.so");
        List<AssetManifest.Entry> entries = reg.entriesFor(asset);
        // Start from a clean directory: the asset may already be installed by another test,
        // in which case createNewFile() returns false and the assertion below would fail for
        // the wrong reason.
        if (fake.exists()) {
            //noinspection ResultOfMethodCallIgnored
            fake.setWritable(true);
            //noinspection ResultOfMethodCallIgnored
            fake.delete();
        }
        try {
            assertTrue("could not create the fake member", fake.createNewFile());
            System.out.println(TAG + " REGISTRY staged " + fake.getName()
                    + " len=" + fake.length() + " expected=" + entries.get(0).size);
            assertFalse("a wrong-sized leftover file must not count as installed",
                    store.hasAllFiles(asset, entries));
        } catch (java.io.IOException e) {
            throw new AssertionError("could not stage fake file", e);
        } finally {
            reg.delete(asset);
            assertFalse("delete must clear the asset directory",
                    store.hasAllFiles(asset, entries));
        }
    }

    @Test
    public void stateOrderPrefersDownloadingOverAnythingElse() {
        AssetRegistry reg = AssetRegistry.get(ctx());
        // A download in flight must be reported as DOWNLOADING regardless of what is on
        // disk, because the UI shows progress for the in-flight work.
        reg.onDownloadStarted(Asset.OPENCV);
        try {
            assertEquals(AssetState.DOWNLOADING, reg.state(Asset.OPENCV));
        } finally {
            reg.onDownloadFinished(Asset.OPENCV);
        }
        // Once finished, whatever is on disk decides: READY if installed, otherwise absent.
        AssetState after = reg.state(Asset.OPENCV);
        assertTrue("after the download flag clears, state must be resolved from disk, got "
                + after, after == AssetState.READY || after == AssetState.MISSING
                || after == AssetState.CORRUPT);
    }
}
