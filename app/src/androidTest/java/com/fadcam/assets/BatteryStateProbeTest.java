package com.fadcam.assets;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class BatteryStateProbeTest {
    @Test
    public void reportBatteryExemptionState() {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.os.PowerManager pm = (android.os.PowerManager)
                ctx.getSystemService(Context.POWER_SERVICE);
        boolean ignoring = pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        System.out.println("BATTCHECK package=" + ctx.getPackageName()
                + " sdk=" + android.os.Build.VERSION.SDK_INT
                + " isIgnoringBatteryOptimizations=" + ignoring);
        System.out.println("BATTCHECK whitelisted-by-dumpsys should be compared by hand");
    }
}
