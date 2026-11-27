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

import static org.junit.Assert.assertTrue;

/**
 * Spike: is {@code setReadOnly()} actually required before {@code System.load()}, or is
 * the plan being over-cautious?
 *
 * <p>This matters because the download path must remember to do it, and a forgotten call
 * would only fail on Android 17+. Documented since API 17 behaviour changes: if a native
 * file passed to {@code System.load()} is writable by the app, the load fails. Since we
 * support API 24 through 37, the requirement is real for exactly one end of the range.
 *
 * <p>The assertion is deliberately environment-aware: it asserts that the writable file
 * loads on pre-17 (so the test proves nothing about older systems that isn't true) and
 * asserts that it FAILS on 17+, which is the actual claim being tested. If Google ever
 * relaxes this, this test fails loudly and the note in the plan can be updated.
 */
@RunWith(AndroidJUnit4.class)
public class NativeReadOnlyRequirementTest {

    private static final String TAG = "ASSETSPIKE";

    private static Context ctx() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void writableNativeFile_isRejectedOnlyWhenTargetingAndroid17() {
        File dir = new File(ctx().getFilesDir(), "ro-requirement");
        assertTrue("could not create dir", dir.exists() || dir.mkdirs());

        // Copy a library that is definitely present in the spike APK.
        File lib = new File(dir, "libLiteRt_ro_test.so");
        if (lib.exists()) {
            //noinspection ResultOfMethodCallIgnored
            lib.setWritable(true);
            //noinspection ResultOfMethodCallIgnored
            lib.delete();
        }
        copyFromApk("libLiteRt.so", lib);

        // Explicitly writable — this is the mistake the download path must not make.
        assertTrue("could not make the file writable", lib.setWritable(true));
        int targetSdk = ctx().getApplicationInfo().targetSdkVersion;
        System.out.println(TAG + " READONLY sdk=" + Build.VERSION.SDK_INT
                + " targetSdk=" + targetSdk
                + " writable=true length=" + lib.length());

        Throwable thrown = null;
        try {
            System.load(lib.getAbsolutePath());
            System.out.println(TAG + " READONLY result=LOADED (writable file accepted)");
        } catch (Throwable t) {
            thrown = t;
            System.out.println(TAG + " READONLY result=REJECTED "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        // Per the Android 17 behaviour changes, Safer Native DCL applies only when the
        // app targets API 37 or higher AND the device runs Android 17+. Assert the
        // condition that actually governs our build, so this test stays a true regression
        // guard when targetSdk is eventually bumped from 36 to 37.
        boolean ruleApplies = Build.VERSION.SDK_INT >= 37 && targetSdk >= 37;
        if (ruleApplies) {
            assertTrue("targetSdk " + targetSdk + " on API " + Build.VERSION.SDK_INT
                    + " should reject loading a writable native file, but it loaded."
                    + " The read-only requirement may have changed; re-check the docs"
                    + " before removing setReadOnly() from the download path.",
                    thrown != null);
            System.out.println(TAG + " READONLY PASS rejected as documented"
                    + " (SDK " + Build.VERSION.SDK_INT + ", targetSdk " + targetSdk + ")");
        } else {
            assertTrue("Expected a writable file to load when targetSdk=" + targetSdk
                    + " on API " + Build.VERSION.SDK_INT + ", but it failed: "
                    + (thrown == null ? "?" : thrown.getMessage()),
                    thrown == null);
            System.out.println(TAG + " READONLY PASS writable file accepted, rule not"
                    + " engaged (targetSdk " + targetSdk + " < 37)."
                    + " setReadOnly() stays in the download path so a future"
                    + " targetSdk 37 bump cannot break loading.");
        }
    }

    private static void copyFromApk(String name, File out) {
        String entryName = "lib/" + Build.SUPPORTED_ABIS[0] + "/" + name;
        try (java.util.zip.ZipFile apk =
                     new java.util.zip.ZipFile(ctx().getApplicationInfo().sourceDir)) {
            java.util.zip.ZipEntry entry = apk.getEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException("APK lacks " + entryName);
            }
            try (InputStream in = apk.getInputStream(entry);
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[1 << 16];
                int read;
                while ((read = in.read(buf)) != -1) {
                    fos.write(buf, 0, read);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not stage " + name, e);
        }
    }
}
