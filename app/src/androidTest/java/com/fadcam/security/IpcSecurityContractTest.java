package com.fadcam.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class IpcSecurityContractTest {
    private static final String INTERNAL_CONTROL = "com.fadcam.permission.INTERNAL_CONTROL";

    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void internalControlPermissionIsSignatureProtectedAndSelfGranted() throws Exception {
        PackageManager pm = context().getPackageManager();
        if (Build.VERSION.SDK_INT >= 28) {
            PermissionInfo permission = pm.getPermissionInfo(INTERNAL_CONTROL, 0);
            assertEquals(INTERNAL_CONTROL, permission.name);
            assertEquals(PermissionInfo.PROTECTION_SIGNATURE, permission.getProtection());
        }
        assertEquals(PackageManager.PERMISSION_GRANTED,
                pm.checkPermission(INTERNAL_CONTROL, context().getPackageName()));
    }

    @Test
    public void privilegedActivitiesRequireInternalControl() throws Exception {
        PackageManager pm = context().getPackageManager();
        String[] names = {
                "com.fadcam.RecordingStartActivity",
                "com.fadcam.RecordingStopActivity",
                "com.fadcam.RecordingToggleActivity",
                "com.fadcam.PhotoCaptureActivity",
                "com.fadcam.ScreenShotCaptureActivity"
        };
        for (String name : names) {
            ActivityInfo info = pm.getActivityInfo(new ComponentName(context(), name),
                    PackageManager.GET_META_DATA);
            assertTrue("must remain exported: " + name, info.exported);
            assertEquals("wrong protection on " + name, INTERNAL_CONTROL, info.permission);
        }
    }

    @Test
    public void privilegedServicesAndReceiverRequireInternalControl() throws Exception {
        PackageManager pm = context().getPackageManager();
        String[] services = {
                "com.fadcam.services.TorchService",
                "com.fadcam.services.AlarmService"
        };
        for (String name : services) {
            ServiceInfo info = pm.getServiceInfo(new ComponentName(context(), name),
                    PackageManager.GET_META_DATA);
            assertTrue("must remain exported: " + name, info.exported);
            assertEquals("wrong protection on " + name, INTERNAL_CONTROL, info.permission);
        }

        ActivityInfo receiver = pm.getReceiverInfo(
                new ComponentName(context(), "com.fadcam.receivers.TorchToggleReceiver"),
                PackageManager.GET_META_DATA);
        assertTrue(receiver.exported);
        assertEquals(INTERNAL_CONTROL, receiver.permission);
    }

    @Test
    public void sensitiveInternalComponentsAreNotExported() throws Exception {
        PackageManager pm = context().getPackageManager();
        String[] names = {
                "com.fadcam.MainActivity",
                "com.fadcam.ui.WatchMainActivity",
                "com.fadcam.ui.CloudAccountActivity",
                "com.fadcam.ui.FullscreenPreviewActivity",
                "com.fadcam.ui.miniapps.QRScannerActivity",
                "com.fadcam.ui.FadCamProActivity",
                "com.fadcam.fadrec.ui.ProjectNamingDialogActivity",
                "com.fadcam.fadrec.ui.ProjectSelectionDialogActivity"
        };
        for (String name : names) {
            ActivityInfo info = pm.getActivityInfo(new ComponentName(context(), name), 0);
            assertTrue("internal activity unexpectedly exported: " + name, !info.exported);
        }
    }
}
