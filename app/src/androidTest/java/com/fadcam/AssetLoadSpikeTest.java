package com.fadcam;

import android.content.Context;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Spike: can OpenCV, ffmpeg-kit and the RF-DETR models be loaded from app-private
 * storage exactly as they would be after a download?
 *
 * <p>This is deliberately NOT the same question as {@link NativeLoadFromStorageTest}.
 * That test copies {@code libLiteRt.so} out of the APK and loads it, which proves very
 * little: LiteRT has no {@code libc++_shared} dependency and no internal
 * {@code System.loadLibrary} call. OpenCV and ffmpeg-kit both do, so both are tested
 * here with their real dependency graphs and their real entry points.
 *
 * <p>Two levels per library:
 * <ul>
 *   <li><b>Level 1 (gate):</b> copy every {@code .so} into {@code filesDir/spike/},
 *       mark each read-only (required by Android 14+/17 for {@link System#load}), load
 *       them in dependency order by absolute path, then drive the real API.</li>
 *   <li><b>Level 2 (diagnostic):</b> after the absolute-path load, confirm the
 *       library's own internal {@code System.loadLibrary("name")} still succeeds. If
 *       the dynamic linker resolves it from the already-loaded soname this needs no
 *       patch; if it throws, the bundled loader has to be patched. Reported, not
 *       asserted, so Level 1 results stay readable.</li>
 * </ul>
 *
 * <p>Level 2 is only truly conclusive on a build where the library is absent from
 * {@code lib/} in the APK. Run with {@code -PspikeNoBundledNative=true} for that.
 */
@RunWith(AndroidJUnit4.class)
public class AssetLoadSpikeTest {

    private static final String TAG = "ASSETSPIKE";

    /** ffmpeg libraries in dependency order, derived from their DT_NEEDED entries. */
    private static final String[] FFMPEG_ORDER = {
            "libc++_shared.so",
            "libavutil.so",
            "libswresample.so",
            "libswscale.so",
            "libavcodec.so",
            "libavformat.so",
            "libavfilter.so",
            "libavdevice.so",
            "libffmpegkit_abidetect.so",
            "libffmpegkit.so",
    };

    /** OpenCV libraries in dependency order. */
    private static final String[] OPENCV_ORDER = {
            "libc++_shared.so",
            "libopencv_java5.so",
    };

    // ---------------------------------------------------------------- helpers

    private static Context ctx() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static String abi() {
        return Build.SUPPORTED_ABIS[0];
    }

    private static void log(String msg) {
        System.out.println(TAG + " " + msg);
    }

    /**
     * Copies the named library out of the installed APK into {@code destDir}, mimicking
     * a completed download. Returns the written file, already marked read-only.
     *
     * <p>When the APK was built with {@code -PspikeNoBundledNative=true} the libraries
     * are not in {@code lib/} at all — that is the point of that build — so they are read
     * from {@code getExternalFilesDir("spike-src")} instead, where they are pushed with
     * {@code adb push}. Reading from there is also closer to a real download than reading
     * out of the APK.
     */
    private static File stageFromApk(String name, File destDir) throws Exception {
        File out = new File(destDir, name);
        if (out.exists() && out.length() > 0) {
            // Already staged by a previous test in this run.
            assertTrue("could not mark staged " + name + " read-only", out.setReadOnly());
            return out;
        }
        File pushed = new File(ctx().getExternalFilesDir("spike-src"), name);
        if (!pushed.isFile() || pushed.length() == 0) {
            // Fallback: internal filesDir, used when adb push into Android/data is
            // blocked (Android 11+), so the files are copied in via run-as instead.
            pushed = new File(ctx().getFilesDir(), "spike-src/" + name);
        }
        if (pushed.isFile() && pushed.length() > 0) {
            copy(pushed, out);
            log("staged " + name + " from " + pushed.getParentFile().getName()
                    + " (" + (out.length() / 1024) + " KB)");
            assertTrue("could not mark " + name + " read-only", out.setReadOnly());
            return out;
        }
        String entryName = "lib/" + abi() + "/" + name;
        try (ZipFile apk = new ZipFile(ctx().getApplicationInfo().sourceDir)) {
            ZipEntry entry = apk.getEntry(entryName);
            if (entry == null) {
                fail("neither " + pushed.getAbsolutePath() + " nor APK entry "
                        + entryName + " exists. Push the .so files, or build without"
                        + " -PspikeNoBundledNative=true.");
            }
            try (InputStream in = apk.getInputStream(entry)) {
                copy(in, out);
            }
        }
        assertTrue("staged " + name + " is empty", out.length() > 0);
        log("staged " + name + " from APK (" + (out.length() / 1024) + " KB)");
        // Android 17 (targetSdk 37+) refuses to load a native file the app can still
        // write to, so the real download path must do this too.
        assertTrue("could not mark " + name + " read-only", out.setReadOnly());
        return out;
    }

    private static void copy(File from, File to) throws Exception {
        try (InputStream in = new java.io.FileInputStream(from)) {
            copy(in, to);
        }
    }

    private static void copy(InputStream in, File to) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(to)) {
            byte[] buf = new byte[1 << 16];
            int read;
            while ((read = in.read(buf)) != -1) {
                fos.write(buf, 0, read);
            }
        }
    }

    private static File freshDir(String lib) {
        File dir = new File(ctx().getFilesDir(), "spike/" + lib);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** Loads the given libraries by absolute path, in the order supplied. */
    private static List<String> loadInOrder(String[] names, File dir) {
        List<String> loaded = new ArrayList<>();
        for (String name : names) {
            File f = new File(dir, name);
            long t0 = System.nanoTime();
            System.load(f.getAbsolutePath());
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            loaded.add(name);
            log("loaded " + name + " (" + (f.length() / 1024) + " KB, " + ms + " ms)");
        }
        return loaded;
    }

    // ------------------------------------------------------------------ tests

    /**
     * Level 1 for OpenCV: load from storage and actually run MOG2, which is the exact
     * production consumer ({@code OpenCvMog2MotionDetector}).
     */
    @Test
    public void opencv_1_loadsFromStorageAndRunsMog2() throws Exception {
        File dir = freshDir("opencv");
        for (String name : OPENCV_ORDER) {
            stageFromApk(name, dir);
        }
        log("OPENCV sdk=" + Build.VERSION.SDK_INT + " abi=" + abi() + " dir=" + dir);
        loadInOrder(OPENCV_ORDER, dir);

        // Touch a real native entry point, not just the load.
        long t0 = System.nanoTime();
        org.opencv.video.BackgroundSubtractorMOG2 mog2 =
                org.opencv.video.Video.createBackgroundSubtractorMOG2(500, 16.0, false);
        assertTrue("MOG2 handle is null", mog2 != null);
        mog2.setDetectShadows(false);
        long ms = (System.nanoTime() - t0) / 1_000_000L;

        // One real frame through the subtractor proves the JNI bridge works.
        org.opencv.core.Mat frame = new org.opencv.core.Mat(64, 64, org.opencv.core.CvType.CV_8UC1);
        frame.setTo(new org.opencv.core.Scalar(10));
        org.opencv.core.Mat fg = new org.opencv.core.Mat();
        long t1 = System.nanoTime();
        mog2.apply(frame, fg);
        long applyMs = (System.nanoTime() - t1) / 1_000_000L;
        assertTrue("MOG2 returned an empty mask", fg.rows() == 64 && fg.cols() == 64);

        mog2.clear();
        frame.release();
        fg.release();
        log("OPENCV level1 PASS createBackgroundSubtractorMOG2=" + ms
                + "ms apply=" + applyMs + "ms version=" + org.opencv.core.Core.VERSION);
    }

    /**
     * Level 2 for OpenCV: does {@code OpenCVLoader.initLocal()} — which internally calls
     * the bare {@code System.loadLibrary("opencv_java5")} — still work once the soname is
     * already resident? Diagnostic only.
     */
    @Test
    public void opencv_2_internalLoadLibraryResolvesToLoadedSoname() throws Exception {
        // Depends on the directories having been staged; stage again if this runs first.
        File dir = freshDir("opencv");
        for (String name : OPENCV_ORDER) {
            stageFromApk(name, dir);
        }
        try {
            loadInOrder(OPENCV_ORDER, dir);
        } catch (UnsatisfiedLinkError alreadyLoaded) {
            log("OPENCV level2 note: already loaded (" + alreadyLoaded.getMessage() + ")");
        }
        try {
            boolean ok = org.opencv.android.OpenCVLoader.initLocal();
            log("OPENCV level2 initLocal()=" + ok);
            if (!ok) {
                log("OPENCV level2 RESULT=NEEDS_REVIEW initLocal returned false");
            }
        } catch (Throwable t) {
            log("OPENCV level2 RESULT=NEEDS_PATCH " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
        }
    }

    /**
     * Level 1 for ffmpeg: load the whole dependency chain from storage, then run a real
     * FFmpegKit command and require a zero exit code.
     */
    @Test
    public void ffmpeg_1_loadsFromStorageAndExecutes() throws Exception {
        File dir = freshDir("ffmpeg");
        for (String name : FFMPEG_ORDER) {
            stageFromApk(name, dir);
        }
        log("FFMPEG sdk=" + Build.VERSION.SDK_INT + " abi=" + abi() + " dir=" + dir);
        loadInOrder(FFMPEG_ORDER, dir);

        // FFmpegKitConfig's static initialiser re-enters System.loadLibrary for
        // "ffmpegkit" and "ffmpegkit_abidetect". If the already-loaded soname satisfies
        // it, this call succeeds; otherwise it throws Error("failed to start").
        long t0 = System.nanoTime();
        com.arthenica.ffmpegkit.Session session =
                com.arthenica.ffmpegkit.FFmpegKit.execute("-version");
        long ms = (System.nanoTime() - t0) / 1_000_000L;

        assertTrue("execute() returned null session", session != null);
        com.arthenica.ffmpegkit.ReturnCode rc = session.getReturnCode();
        String output = session.getOutput();
        log("FFMPEG level1 returnCode=" + rc + " in " + ms + "ms");
        log("FFMPEG level1 outputHead=" + head(output, 200));

        assertTrue("ffmpeg returned no return code", rc != null);
        assertTrue("ffmpeg -version failed, returnCode=" + rc,
                com.arthenica.ffmpegkit.ReturnCode.isSuccess(rc));

        String version = com.arthenica.ffmpegkit.FFmpegKitConfig.getVersion();
        log("FFMPEG level1 PASS version=" + version);
    }

    /**
     * Level 1 for the RF-DETR models: the documented {@code CompiledModel.create(String
     * filePath, Options)} overload accepts an absolute path, so no hacks are needed.
     * This only proves the file is readable and the graph compiles from that path.
     */
    @Test
    public void models_1_compiledModelLoadsFromAbsolutePath() throws Exception {
        File dir = freshDir("models");
        File graphA = new File(dir, "rfdetr_nano_graph_a.tflite");
        stageAsset("models/rfdetr_nano_graph_a.tflite", graphA);
        log("MODELS sdk=" + Build.VERSION.SDK_INT + " graphA=" + graphA.length() + " bytes");

        // Mirror RfDetrNanoDetector.gpuOptions() exactly so the spike exercises the same
        // accelerator path production uses, not a CPU fallback that would hide problems.
        com.google.ai.edge.litert.CompiledModel.Options options =
                new com.google.ai.edge.litert.CompiledModel.Options(
                        com.google.ai.edge.litert.Accelerator.GPU);
        File cacheDir = ctx().getCodeCacheDir();
        if (cacheDir != null && (cacheDir.exists() || cacheDir.mkdirs())) {
            options.setGpuOptions(new com.google.ai.edge.litert.CompiledModel.GpuOptions(
                    null, null, null,
                    com.google.ai.edge.litert.CompiledModel.GpuOptions.Precision.FP16,
                    null, null,
                    cacheDir.getAbsolutePath(),
                    "spike_graph_a",
                    Boolean.TRUE, Boolean.TRUE,
                    null, null, null, null, null));
        }
        com.google.ai.edge.litert.CompiledModel model =
                com.google.ai.edge.litert.CompiledModel.create(graphA.getAbsolutePath(), options);
        assertTrue("compiled model is null", model != null);
        model.close();
        log("MODELS level1 PASS CompiledModel.create(absolutePath) succeeded");
    }

    // ------------------------------------------------------------ more helpers

    private static void stageAsset(String assetPath, File out) throws Exception {
        if (out.exists() && out.length() > 0) {
            return;
        }
        try (InputStream in = ctx().getAssets().open(assetPath);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            int read;
            while ((read = in.read(buf)) != -1) {
                fos.write(buf, 0, read);
            }
        }
        assertTrue("staged asset is empty: " + assetPath, out.length() > 0);
    }

    private static String head(String s, int max) {
        if (s == null) {
            return "null";
        }
        String oneLine = s.replace('\n', ' ').trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "...";
    }
}
