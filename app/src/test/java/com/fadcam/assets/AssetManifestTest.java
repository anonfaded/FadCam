package com.fadcam.assets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.List;

/**
 * JVM tests for manifest parsing and ABI selection. No device needed.
 *
 * <p>ABI selection is the part most likely to break silently: a phone reports several
 * ABIs and picking the wrong one downloads code it cannot execute.
 */
public class AssetManifestTest {

    /** Mirrors Build.SUPPORTED_ABIS order: most preferred first. */
    private static final AssetManifest.AbiRank ARM64_FIRST = new AssetManifest.AbiRank() {
        @Override
        public int rankOf(String abi) {
            switch (abi) {
                case "arm64-v8a": return 0;
                case "armeabi-v7a": return 1;
                case "universal": return Integer.MAX_VALUE - 1;
                default: return -1;
            }
        }
    };

    /** A 32-bit-only device: arm64 is not in its supported list at all. */
    private static final AssetManifest.AbiRank V7A_ONLY = new AssetManifest.AbiRank() {
        @Override
        public int rankOf(String abi) {
            switch (abi) {
                case "armeabi-v7a": return 0;
                case "universal": return Integer.MAX_VALUE - 1;
                default: return -1;
            }
        }
    };

    private static final String MANIFEST = "{"
            + "\"schema\":1,"
            + "\"assetSet\":\"assets-v1.0\","
            + "\"baseUrl\":\"https://example.test/assets-v1.0/\","
            + "\"assets\":["
            + "{\"id\":\"opencv\",\"abi\":\"arm64-v8a\",\"file\":\"opencv-arm64.zip\","
            + "\"sha256\":\"AA11\",\"size\":100,\"license\":\"Apache-2.0\","
            + "\"name\":\"OpenCV\",\"version\":\"5.1.0\","
            + "\"members\":[\"libc++_shared.so\",\"libopencv_java5.so\"]},"
            + "{\"id\":\"opencv\",\"abi\":\"armeabi-v7a\",\"file\":\"opencv-v7a.zip\","
            + "\"sha256\":\"BB22\",\"size\":90,\"license\":\"Apache-2.0\","
            + "\"name\":\"OpenCV\",\"version\":\"5.1.0\","
            + "\"members\":[\"libc++_shared.so\",\"libopencv_java5.so\"]},"
            + "{\"id\":\"models\",\"abi\":\"universal\",\"file\":\"graph-a.tflite\","
            + "\"sha256\":\"CC33\",\"size\":1000,\"license\":\"Apache-2.0\","
            + "\"name\":\"RF-DETR Nano graph_a\",\"version\":\"1.0\"}"
            + "]"
            + "}";

    private static AssetManifest parse() throws Exception {
        return AssetManifest.parse(MANIFEST);
    }

    @Test
    public void parsesAssetSetAndBaseUrl() throws Exception {
        AssetManifest m = parse();
        assertEquals("assets-v1.0", m.assetSet());
        assertEquals("https://example.test/assets-v1.0/", m.baseUrl());
    }

    @Test
    public void baseUrlGetsTrailingSlashWhenMissing() throws Exception {
        String json = MANIFEST.replace("assets-v1.0/\",", "assets-v1.0\",");
        AssetManifest m = AssetManifest.parse(json);
        assertTrue("a base URL without a slash must be normalised",
                m.baseUrl().endsWith("/"));
    }

    @Test
    public void sha256IsNormalisedToLowercase() throws Exception {
        List<AssetManifest.Entry> entries = parse().entries(Asset.OPENCV);
        assertEquals(2, entries.size());
        for (AssetManifest.Entry e : entries) {
            assertEquals("hash must be lowercase so comparison cannot fail on case",
                    e.sha256.toLowerCase(java.util.Locale.US), e.sha256);
        }
    }

    @Test
    public void resolvesArm64OnDeviceThatPrefersIt() throws Exception {
        List<AssetManifest.Entry> e = parse().resolve(Asset.OPENCV, ARM64_FIRST);
        assertNotNull(e);
        assertEquals(1, e.size());
        assertEquals("opencv-arm64.zip", e.get(0).file);
    }

    @Test
    public void resolvesV7aOnThirtyTwoBitDevice() throws Exception {
        List<AssetManifest.Entry> e = parse().resolve(Asset.OPENCV, V7A_ONLY);
        assertNotNull(e);
        assertEquals("opencv-v7a.zip", e.get(0).file);
    }

    @Test
    public void resolvesUniversalWhenNoAbiMatches() throws Exception {
        // A device that matches neither ABI still gets the ABI-independent model.
        AssetManifest.AbiRank stranger = new AssetManifest.AbiRank() {
            @Override
            public int rankOf(String abi) {
                return "universal".equals(abi) ? 0 : -1;
            }
        };
        List<AssetManifest.Entry> e = parse().resolve(Asset.MODELS, stranger);
        assertNotNull("universal assets must resolve without an ABI match", e);
        assertEquals("graph-a.tflite", e.get(0).file);
    }

    @Test
    public void returnsNullForAssetAbsentFromManifest() throws Exception {
        assertNull(parse().resolve(Asset.FFMPEG, ARM64_FIRST));
        assertTrue(parse().entries(Asset.FFMPEG).isEmpty());
    }

    @Test
    public void totalSizeSumsEveryFileOfTheChosenAbi() throws Exception {
        AssetManifest m = parse();
        assertEquals(100L, m.totalSize(m.resolve(Asset.OPENCV, ARM64_FIRST)));
        assertEquals(90L, m.totalSize(m.resolve(Asset.OPENCV, V7A_ONLY)));
        assertEquals(0L, m.totalSize(null));
    }

    @Test
    public void archiveEntriesExposeMembersAndRawOnesDoNot() throws Exception {
        AssetManifest m = parse();
        AssetManifest.Entry zip = m.resolve(Asset.OPENCV, ARM64_FIRST).get(0);
        assertTrue("a zipped asset must be marked so the installer unzips it", zip.isArchive());
        assertEquals(2, zip.members.size());

        AssetManifest.Entry raw = m.resolve(Asset.MODELS, ARM64_FIRST).get(0);
        assertFalse("a raw asset is the artifact; unzipping it would be wrong", raw.isArchive());
    }

    @Test
    public void rejectsMalformedManifest() {
        try {
            AssetManifest.parse("{\"assets\": []}");
            fail("a manifest without assetSet must not parse silently");
        } catch (Exception expected) {
            // Missing required fields must throw rather than produce a half-manifest.
        }
    }

    @Test
    public void assetIdsAreStable() {
        // The ids are persisted in the manifest, so changing one silently orphans
        // installed files. This test is a tripwire, not a style check.
        assertEquals("opencv", Asset.OPENCV.id);
        assertEquals("ffmpeg", Asset.FFMPEG.id);
        assertEquals("models", Asset.MODELS.id);
        assertEquals(Asset.OPENCV, Asset.fromId("opencv"));
        assertNull(Asset.fromId("nope"));
        assertNull(Asset.fromId(null));
    }

    @Test
    public void zippedFlagMatchesHowEachAssetIsPublished() {
        assertTrue("shared libraries compress ~50-63%, so they ship zipped",
                Asset.OPENCV.zipped);
        assertTrue(Asset.FFMPEG.zipped);
        assertFalse("models compress ~9.5%; zipping costs disk for no real gain",
                Asset.MODELS.zipped);
    }
}
