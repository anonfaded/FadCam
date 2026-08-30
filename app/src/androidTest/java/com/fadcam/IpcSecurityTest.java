package com.fadcam;

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

/** Security regression tests for FadCam's Android component boundary. */
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
                new ComponentName(PACKAGE, "androidx.core.content.FileProvider"), 0);
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
                    new ComponentName(PACKAGE, PACKAGE + name), 0);
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
                    new ComponentName(PACKAGE, PACKAGE + name), 0);
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
                    new ComponentName(PACKAGE, PACKAGE + name), 0);
            assertNotNull(name + " must exist", info);
            assertTrue(name + " is a deliberate launcher/shortcut boundary", info.exported);
        }
    }

    @Test
    public void untrustedShellCannotStartRecordingService() throws Exception {
        String output = runShell("am startservice -n " + PACKAGE + "/.services.RecordingService");
        assertDenied(output);
    }

    @Test
    public void untrustedShellCannotStartRemoteStreamService() throws Exception {
        String output = runShell("am startservice -n " + PACKAGE + "/.streaming.RemoteStreamService");
        assertDenied(output);
    }

    private void assertDenied(String output) {
        assertTrue(
                "An untrusted shell caller must be rejected. Output: " + output,
                output.contains("Permission Denial")
                        || output.contains("not exported")
                        || output.contains("SecurityException")
                        || output.contains("Error: Not found"));
    }

    private String runShell(String command) throws Exception {
        ParcelFileDescriptor pfd = instrumentation.getUiAutomation().executeShellCommand(command);
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(pfd)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        return output.toString();
    }
}
