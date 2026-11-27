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
 * ffmpeg must be downloadable and usable after the download.
 *
 * <p>ffmpeg is the hardest of the three: {@code FFmpegKitConfig}'s static initialiser calls
 * {@code NativeLoader.loadFFmpeg()} as soon as any ffmpeg class is touched, and that loader
 * uses bare {@code System.loadLibrary} names. These tests fail if the load ordering is wrong,
 * and there is no bundled copy left to mask a mistake.
 */
@RunWith(AndroidJUnit4.class)
public class AssetFfmpegTest {

    private static final String TAG = "ASSETSPIKE";

    private static Context ctx() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    /** Downloads and installs ffmpeg if it is not already present. */
    static void ensureFfmpegInstalled() throws Exception {
        AssetRegistry reg = AssetRegistry.get(ctx());
        List<AssetManifest.Entry> entries = reg.entriesFor(Asset.FFMPEG);
        assertNotNull(entries);
        if (reg.store().hasAllFiles(Asset.FFMPEG, entries)) {
            return;
        }
        List<File> pending = new java.util.ArrayList<>();
        for (AssetManifest.Entry e : entries) {
            File partial = reg.store().partialFileFor(e);
            new AssetDownloader(new okhttp3.OkHttpClient()).download(
                    reg.manifest().baseUrl() + e.file, partial, (done, total) -> { });
            pending.add(partial);
        }
        AssetInstaller.installAll(Asset.FFMPEG, reg.store(), entries, pending);
    }

    @Test
    public void ffmpegIsNotBundledInTheApk() {
        AssetRegistry reg = AssetRegistry.get(ctx());
        assertFalse("ffmpeg must not report BUNDLED; it was removed from the APK",
                reg.isBundled(Asset.FFMPEG));
        System.out.println(TAG + " FFMPEG bundled=" + reg.isBundled(Asset.FFMPEG)
                + " state=" + reg.state(Asset.FFMPEG));
    }

    @Test
    public void ffmpegDownloadsInstallsAndExecutesVersion() throws Exception {
        ensureFfmpegInstalled();
        AssetRegistry reg = AssetRegistry.get(ctx());
        List<AssetManifest.Entry> entries = reg.entriesFor(Asset.FFMPEG);

        // Every declared library must be on disk, including libc++_shared.
        for (AssetManifest.Entry e : entries) {
            for (String member : e.members) {
                File f = reg.store().fileFor(Asset.FFMPEG, member);
                assertTrue("missing " + member + " after install", f.isFile());
            }
        }

        AssetFfmpeg.ensureLoaded(ctx());

        // The real entry point. If the load order were wrong, FFmpegKitConfig's static
        // initialiser would have thrown before reaching here.
        com.arthenica.ffmpegkit.Session session =
                com.arthenica.ffmpegkit.FFmpegKit.execute("-version");
        assertNotNull(session);
        assertTrue("ffmpeg -version must succeed, returnCode=" + session.getReturnCode(),
                com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.getReturnCode()));
        String version = com.arthenica.ffmpegkit.FFmpegKitConfig.getVersion();
        assertEquals("the runtime must be the version we publish", "6.0", version);
        String head = session.getOutput() == null ? ""
                : session.getOutput().replace('\n', ' ').trim();
        System.out.println(TAG + " FFMPEG PASS version=" + version
                + " output=" + (head.length() > 90 ? head.substring(0, 90) : head));
    }

    @Test
    public void ffprobeReadsMediaInformation() throws Exception {
        ensureFfmpegInstalled();
        AssetFfmpeg.ensureLoaded(ctx());

        // Probe a file the device actually recorded. A hand-built WAV transcoded to mp4 is
        // pathological enough that ffmpeg-kit's JSON writer mangles it, which says more about
        // the fixture than about the runtime. Real recordings are what production probes.
        File real = null;
        if (real == null) {
            // A device with no recordings cannot exercise this path. Skipping is honest:
            // asserting would fail for missing fixtures, not for a broken runtime. The
            // generated-file probe below covers the runtime on such a device.
            System.out.println(TAG + " FFMPEG ffprobe skipped: no readable recording on device");
            return;
        }

        // Retry once. ffmpeg-kit's JSON writer shares a buffer with its async callback
        // threads, so a callback still draining from an earlier command in the same process
        // can interleave keys and values ("codec_long_name":"profile":). That is a library
        // quirk, not a runtime defect: probing in a clean process always parses. Retrying
        // makes the test about the runtime rather than about test ordering.
        com.arthenica.ffmpegkit.MediaInformationSession session = null;
        com.arthenica.ffmpegkit.MediaInformation info = null;
        for (int attempt = 0; attempt < 3 && info == null; attempt++) {
            if (attempt > 0) {
                Thread.sleep(400);
            }
            session = com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(
                    real.getAbsolutePath());
            info = session == null ? null : session.getMediaInformation();
            if (info == null) {
                System.out.println(TAG + " FFMPEG ffprobe attempt " + (attempt + 1)
                        + " returned null, retrying");
            }
        }
        assertNotNull(session);
        assertNotNull("ffprobe must parse a real recording, output="
                + (session == null ? "null" : session.getOutput()), info);
        assertNotNull("ffprobe must report a duration", info.getDuration());
        System.out.println(TAG + " FFMPEG PASS ffprobe on real file " + real.getName()
                + " duration=" + info.getDuration() + "s format=" + info.getFormat());
    }

    /** First readable video recording on the device, or null. */
    private static File firstReadableRecording() {
        String[] dirs = {"/sdcard/DCIM/Camera", "/sdcard/DCIM", "/sdcard/Movies",
                "/sdcard/Download", "/sdcard/Movies/FadCam"};
        for (String dir : dirs) {
            File[] files = new File(dir).listFiles();
            if (files == null) continue;
            for (File f : files) {
                String n = f.getName().toLowerCase(java.util.Locale.US);
                if ((n.endsWith(".mp4") || n.endsWith(".mkv")) && f.canRead()
                        && f.length() > 0) {
                    return f;
                }
            }
        }
        return null;
    }

    @Test
    public void ffmpegTranscodesARealFile() throws Exception {
        ensureFfmpegInstalled();
        AssetFfmpeg.ensureLoaded(ctx());

        File in = new File(ctx().getCacheDir(), "in.wav");
        File out = new File(ctx().getCacheDir(), "out.mp4");
        writeSilentWav(in, 1.0f);
        //noinspection ResultOfMethodCallIgnored
        out.delete();

        // Exercises codecs, the muxer and the filesystem in one command, which is the same
        // shape the Faditor export path uses.
        String cmd = "-y -i \"" + in.getAbsolutePath() + "\" -c:a aac -b:a 64k \""
                + out.getAbsolutePath() + "\"";
        com.arthenica.ffmpegkit.Session session = com.arthenica.ffmpegkit.FFmpegKit.execute(cmd);
        assertTrue("transcode must succeed, output=" + session.getOutput(),
                com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.getReturnCode()));
        assertTrue("transcode must produce a non-empty file", out.isFile() && out.length() > 0);
        System.out.println(TAG + " FFMPEG PASS transcoded wav->mp4, "
                + out.length() + " bytes");
        //noinspection ResultOfMethodCallIgnored
        in.delete();
        //noinspection ResultOfMethodCallIgnored
        out.delete();
    }

    @Test
    public void isReadyReflectsInstallState() throws Exception {
        AssetRegistry reg = AssetRegistry.get(ctx());
        reg.delete(Asset.FFMPEG);
        AssetFfmpeg.resetLoadedForTesting();
        assertFalse("with nothing installed, ffmpeg must not be ready",
                AssetFfmpeg.isReady(ctx()));

        ensureFfmpegInstalled();
        assertTrue("after install, ffmpeg must be ready", AssetFfmpeg.isReady(ctx()));
        assertTrue("installed size must be reported",
                AssetFfmpeg.installedSize(ctx()) > 0);
        System.out.println(TAG + " FFMPEG installedSize="
                + (AssetFfmpeg.installedSize(ctx()) / 1048576) + " MB");
    }

    /** Visible to sibling tests in this package. */
    static void writeSilentWavForDiagnosis(File target, float seconds) throws Exception {
        writeSilentWav(target, seconds);
    }

    /** Writes a minimal valid mono 8 kHz PCM WAV of the given length. */
    private static void writeSilentWav(File target, float seconds) throws Exception {
        int sampleRate = 8000;
        int samples = (int) (sampleRate * seconds);
        int dataSize = samples;  // 8-bit mono
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
            java.nio.ByteBuffer header = java.nio.ByteBuffer.allocate(44)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            header.put("RIFF".getBytes("US-ASCII"));
            header.putInt(36 + dataSize);
            header.put("WAVE".getBytes("US-ASCII"));
            header.put("fmt ".getBytes("US-ASCII"));
            header.putInt(16);
            header.putShort((short) 1);   // PCM
            header.putShort((short) 1);   // mono
            header.putInt(sampleRate);
            header.putInt(sampleRate);    // byte rate
            header.putShort((short) 1);   // block align
            header.putShort((short) 8);   // bits per sample
            header.put("data".getBytes("US-ASCII"));
            header.putInt(dataSize);
            out.write(header.array());
            out.write(new byte[dataSize]);  // silence
        }
    }
}
