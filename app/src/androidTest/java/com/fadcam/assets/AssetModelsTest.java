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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the RF-DETR graphs are reached as absolute files the way
 * {@code CompiledModel.create} needs.
 *
 * <p>The graphs are no longer in the APK, so these tests download them first. That is
 * deliberate: it exercises the path a real user takes on a fresh install, rather than a
 * fixture that would keep passing after the packaging changed.
 */
@RunWith(AndroidJUnit4.class)
public class AssetModelsTest {

    private static final String TAG = "ASSETSPIKE";

    private static Context ctx() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    /**
     * Downloads and installs both graphs if they are not already present.
     *
     * <p>Installs the set in one call: the asset is made of two entries, and installing
     * them one at a time clears the directory between them.
     */
    static void ensureModelsInstalled() throws Exception {
        AssetRegistry reg = AssetRegistry.get(ctx());
        List<AssetManifest.Entry> entries = reg.entriesFor(Asset.MODELS);
        List<File> pending = new java.util.ArrayList<>();
        List<AssetManifest.Entry> needed = new java.util.ArrayList<>();
        for (AssetManifest.Entry e : entries) {
            File target = reg.store().fileFor(Asset.MODELS, e.file);
            if (reg.store().isPresent(target) && target.length() == e.size) {
                continue;
            }
            needed.add(e);
        }
        if (needed.isEmpty()) {
            return;
        }
        for (AssetManifest.Entry e : needed) {
            File partial = reg.store().partialFileFor(e);
            new AssetDownloader(new okhttp3.OkHttpClient()).download(
                    reg.manifest().baseUrl() + e.file, partial, (done, total) -> { });
            pending.add(partial);
        }
        AssetInstaller.installAll(Asset.MODELS, reg.store(), needed, pending);
    }

    @Test
    public void modelsAreNotBundledInTheApk() {
        // The whole point of the change: a fresh install must not carry 53.6 MB of graphs.
        AssetRegistry reg = AssetRegistry.get(ctx());
        AssetState state = reg.state(Asset.MODELS);
        assertFalse("models must not report BUNDLED; they were removed from the APK",
                state == AssetState.BUNDLED);
        System.out.println(TAG + " MODELS state on fresh install=" + state);
    }

    @Test
    public void graphFilesResolveToAbsolutePathsAfterDownload() throws Exception {
        ensureModelsInstalled();
        File a = AssetModels.graphFile(ctx(), AssetModels.GRAPH_A);
        File b = AssetModels.graphFile(ctx(), AssetModels.GRAPH_B);
        assertNotNull("graph_a must resolve once downloaded", a);
        assertNotNull("graph_b must resolve once downloaded", b);
        assertTrue("graph_a must be a real file at an absolute path", a.isAbsolute() && a.isFile());
        assertTrue("graph_b must be a real file at an absolute path", b.isAbsolute() && b.isFile());
        System.out.println(TAG + " MODELS graph_a=" + a.getAbsolutePath()
                + " " + a.length() + " bytes");
        System.out.println(TAG + " MODELS graph_b=" + b.getAbsolutePath()
                + " " + b.length() + " bytes");
    }

    @Test
    public void graphFilesLandInsideAppStorageNotExternal() throws Exception {
        ensureModelsInstalled();
        File a = AssetModels.graphFile(ctx(), AssetModels.GRAPH_A);
        assertNotNull(a);
        String path = a.getAbsolutePath();
        assertTrue("model must live in app-private storage, got " + path,
                path.startsWith(ctx().getFilesDir().getAbsolutePath())
                        || path.startsWith("/mnt/expand/"));
        System.out.println(TAG + " MODELS PASS app-private path " + path);
    }

    @Test
    public void returnsNullWhenModelsAreAbsent() {
        AssetRegistry reg = AssetRegistry.get(ctx());
        reg.delete(Asset.MODELS);
        // With nothing bundled and nothing downloaded, the resolver must say so instead of
        // handing back a path that does not exist.
        assertNull("resolver must return null when the graph is neither bundled nor installed",
                AssetModels.graphFile(ctx(), AssetModels.GRAPH_A));
        assertFalse(AssetModels.modelsAvailable(ctx()));
        System.out.println(TAG + " MODELS PASS absent models resolve to null, not a bad path");
    }

    @Test
    public void installedSizeReportsBytesActuallyOnDisk() throws Exception {
        ensureModelsInstalled();
        long size = AssetModels.installedSize(ctx());
        assertTrue("installed size must be positive after download, got " + size, size > 0);
        System.out.println(TAG + " MODELS installedSize=" + size + " bytes ("
                + (size / 1048576) + " MB)");
    }

    @Test
    public void repeatedCallsReuseTheSameFile() throws Exception {
        ensureModelsInstalled();
        File first = AssetModels.graphFile(ctx(), AssetModels.GRAPH_A);
        File second = AssetModels.graphFile(ctx(), AssetModels.GRAPH_A);
        assertNotNull(first);
        assertNotNull(second);
        assertEquals("graph path must be stable across calls",
                first.getAbsolutePath(), second.getAbsolutePath());
        System.out.println(TAG + " MODELS PASS stable across calls");
    }

    @Test
    public void deletingInstalledModelsMakesThemUnavailable() throws Exception {
        ensureModelsInstalled();
        assertTrue(AssetModels.modelsAvailable(ctx()));
        AssetRegistry reg = AssetRegistry.get(ctx());
        reg.delete(Asset.MODELS);
        assertFalse("after deletion the models must be reported missing",
                AssetModels.modelsAvailable(ctx()));
        for (AssetManifest.Entry e : reg.entriesFor(Asset.MODELS)) {
            assertFalse(reg.store().isPresent(reg.store().fileFor(Asset.MODELS, e.file)));
        }
        System.out.println(TAG + " MODELS PASS deletion clears availability");
    }
}
