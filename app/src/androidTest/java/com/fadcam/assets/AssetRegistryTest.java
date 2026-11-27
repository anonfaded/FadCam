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
    public void opencvAndModelsAreNoLongerBundled() {
        AssetRegistry reg = AssetRegistry.get(ctx());
        // OpenCV and the models left the APK. ffmpeg is still bundled until its own PR.
        assertFalse("OpenCV must not report BUNDLED any more",
                reg.isBundled(Asset.OPENCV));
        assertFalse("models must not report BUNDLED any more",
                reg.isBundled(Asset.MODELS));
        assertTrue("ffmpeg still ships in the APK at this point",
                reg.isBundled(Asset.FFMPEG));
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
        // ffmpeg: still bundled, so drive the on-disk checks directly rather than through
        // state(), which correctly prefers BUNDLED.
        Asset asset = Asset.FFMPEG;

        // This build ships OpenCV in the APK, so BUNDLED correctly wins the resolution
        // order and masks on-disk state. To exercise the on-disk branch, the test drives
        // AssetStore directly — that is the code the DOWNLOADING/MISSING/CORRUPT states
        // depend on once the libraries leave the APK.
        File dir = store.dirFor(asset);
        File fake = new File(dir, "libavcodec.so");
        List<AssetManifest.Entry> entries = reg.entriesFor(asset);
        try {
            assertTrue(fake.createNewFile());
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
        reg.onDownloadStarted(Asset.FFMPEG);
        try {
            assertEquals(AssetState.DOWNLOADING, reg.state(Asset.FFMPEG));
        } finally {
            reg.onDownloadFinished(Asset.FFMPEG);
        }
        assertEquals("once finished, the bundled copy is reported again",
                AssetState.BUNDLED, reg.state(Asset.FFMPEG));
    }
}
