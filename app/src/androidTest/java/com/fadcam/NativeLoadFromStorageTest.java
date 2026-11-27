package com.fadcam;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Proves whether a native library can be loaded from app-private storage.
 *
 * <p>This is the precondition for shipping ffmpeg and OpenCV as downloaded
 * artifacts instead of bundling them in the APK. The interesting case is
 * Android 10+, where SELinux dropped execve() for app data; the docs claim
 * dlopen() still works. This test settles it on the running device.
 *
 * <p>Method: take a .so that ships inside this APK, copy it out to files/, and
 * load it by absolute path. That is exactly what a download-then-load flow would
 * do, minus the network.
 */
@RunWith(AndroidJUnit4.class)
public class NativeLoadFromStorageTest {

    /** Any library that opens cleanly and has no side effects on load. */
    private static final String SOURCE_LIB = "libLiteRt.so";

    @Test
    public void canLoadNativeLibraryFromAppPrivateStorage() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        // Copy the library out of the APK into app-private storage, mimicking a
        // download landing in filesDir.
        File libDir = new File(context.getFilesDir(), "downloaded-libs");
        assertTrue("could not create lib dir", libDir.exists() || libDir.mkdirs());
        File target = new File(libDir, SOURCE_LIB);
        // A previous run leaves this file read-only, so it must be removed before it can
        // be written again. Without this the test only ever passes once per install.
        if (target.exists() && !target.delete()) {
            assertTrue("stale read-only " + SOURCE_LIB + " could not be replaced",
                    target.setWritable(true) && target.delete());
        }

        String abi = android.os.Build.SUPPORTED_ABIS[0];
        assertTrue("no usable ABI", abi != null && !abi.isEmpty());
        String entryName = "lib/" + abi + "/" + SOURCE_LIB;

        try (ZipFile apk = new ZipFile(context.getApplicationInfo().sourceDir)) {
            ZipEntry entry = apk.getEntry(entryName);
            if (entry == null) {
                fail("APK does not contain " + entryName + " for ABI " + abi);
            }
            try (InputStream in = apk.getInputStream(entry);
                 FileOutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                }
            }
        }

        assertTrue("copied library is empty", target.length() > 0);

        // Android 14+ rejects loading writable files; a real download path must do
        // this too, so the test exercises the same requirement.
        boolean readOnly = target.setReadOnly();
        assertTrue("could not make the library read-only", readOnly);

        System.out.println("NATIVE_LOAD_TEST path=" + target.getAbsolutePath()
                + " bytes=" + target.length()
                + " sdk=" + android.os.Build.VERSION.SDK_INT);

        try {
            System.load(target.getAbsolutePath());
            System.out.println("NATIVE_LOAD_TEST result=SUCCESS");
        } catch (UnsatisfiedLinkError e) {
            // A load failure here is the answer we are looking for, not a test bug.
            System.out.println("NATIVE_LOAD_TEST result=FAILED " + e.getMessage());
            throw new AssertionError("System.load from filesDir failed: " + e.getMessage(), e);
        }
    }
}
