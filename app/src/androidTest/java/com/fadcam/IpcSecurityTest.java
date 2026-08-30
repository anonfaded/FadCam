package com.fadcam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.os.ParcelFileDescriptor;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;

/**
 * Security regression tests for FadCam's Android component boundary.
 *
 * These tests intentionally exercise the installed application's manifest through
 * PackageManager and use the shell identity as an untrusted caller.  This avoids
 * the false positive that would occur if the test process (which shares FadCam's
 * UID) tried to prove its own signature permission.
 */
@RunWith(AndroidJUnit4.class)
public class IpcSecurityTest {
    private static final String PACKAGE = "com.fadcam";

    private final Instrumentation instrumentation =
            InstrumentationRegistry.getInstrumentation();
    private final PackageManager pm =
            instrumentation.getTargetContext().getPackageManager();

    @Test
    public void fileProviderIsNeverExported() throws Exception {
        ProviderInfo info = pm.getProviderInfo(
                new ComponentName(PACKAGE, "androidx.core.content.FileProvider"),
                PackageManager.ComponentInfoFlags.of(0));
        assertNotNull(info);
        assertFalse("FileProvider must never be exported", info.exported);
        assertTrue("FileProvider must use URI grants", info.grantUriPermissions);
    }

    @Test
    public void internalServicesAreNotExported() throws Exception {
        List<String> services = Arrays.asList(
                ".services.RecordingService",
                ".dualcam.service.DualCameraRecordingService",
                ".streaming.RemoteStreamService",
                ".fadrec.services.ScreenRecordingService",
                ".services.BackgroundPlaybackService",
                ".service.FileOperationService",
                ".service.BatchMediaActionService",
                ".service.RecordsDeletionService");

        for (String name : services) {
            ServiceInfo info = pm.getServiceInfo(
                    new ComponentName(PACKAGE, PACKAGE + name),
                    PackageManager.ComponentInfoFlags.of(0));
            assertNotNull(name + " must exist", info);
            assertFalse(name + " must not be exported", info.exported);
        }
    }

    @Test
    public void internalActivitiesAreNotExported() throws Exception {
        List<String> activities = Arrays.asList(
                ".MainActivity",
                ".ui.WatchMainActivity",
                ".ui.CloudAccountActivity",
                ".fadrec.ui.TransparentPermissionActivity",
                ".ui.faditor.FaditorEditorActivity",
                ".ui.FullscreenPreviewActivity",
                ".ui.PrivacyBlackActivity");

        for (String name : activities) {
            ActivityInfo info = pm.getActivityInfo(
                    new ComponentName(PACKAGE, PACKAGE + name),
                    PackageManager.ComponentInfoFlags.of(0));
            assertNotNull(name + " must exist", info);
            assertFalse(name + " must not be exported", info.exported);
        }
    }

    @Test
    public void shortcutEntryPointsRemainExplicitlyExported() throws Exception {
        List<String> activities = Arrays.asList(
                ".RecordingStartActivity",
                ".RecordingStopActivity",
                ".RecordingToggleActivity",
                ".PhotoCaptureActivity",
                ".ScreenShotCaptureActivity",
                ".TorchToggleActivity");

        for (String name : activities) {
            ActivityInfo info = pm.getActivityInfo(
                    new ComponentName(PACKAGE, PACKAGE + name),
                    PackageManager.ComponentInfoFlags.of(0));
            assertNotNull(name + " must exist", info);
            assertTrue(name + " is a deliberate launcher/shortcut boundary", info.exported);
        }
    }

    @Test
    public void untrustedShellCannotStartRecordingService() throws Exception {
        ShellResult result = runShell("am", "startservice", "-n",
                PACKAGE + "/.services.RecordingService");
        assertTrue(
                "An untrusted shell caller must not start the internal recording service. Output: " + result.output,
                result.output.contains("Permission Denial")
                        || result.output.contains("not exported")
                        || result.output.contains("SecurityException"));
        assertEquals("am startservice must fail for a non-exported service", 0, result.exitCode);
    }

    @Test
    public void untrustedShellCannotStartRemoteStreamService() throws Exception {
        ShellResult result = runShell("am", "startservice", "-n",
                PACKAGE + "/.streaming.RemoteStreamService");
        assertTrue(
                "An untrusted shell caller must not start the internal streaming service. Output: " + result.output,
                result.output.contains("Permission Denial")
                        || result.output.contains("not exported")
                        || result.output.contains("SecurityException"));
        assertEquals("am startservice must fail for a non-exported service", 0, result.exitCode);
    }

    private ShellResult runShell(String... command) throws Exception {
        ParcelFileDescriptor pfd = instrumentation.getUiAutomation().executeShellCommand(command[0] + " " + join(command, 1));
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(pfd)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        return new ShellResult(0, output.toString());
    }

    private static String join(String[] values, int start) {
        StringBuilder result = new StringBuilder();
        for (int i = start; i < values.length; i++) {
            if (i > start) result.append(' ');
            result.append(values[i]);
        }
        return result.toString();
    }

    private static final class ShellResult {
        final int exitCode;
        final String output;

        ShellResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
