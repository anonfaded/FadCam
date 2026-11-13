package com.fadcam.ui.animations.scenes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.core.content.ContextCompat;

import com.fadcam.R;
import com.fadcam.ui.animations.AnimationMath;
import com.fadcam.ui.animations.CanvasKit;
import com.fadcam.ui.animations.Scene;
import com.fadcam.ui.animations.SceneFrame;

/**
 * Mini flashlight: a side-view torch with a soft warm beam and a stuttering
 * tube ignition, then a quiet mains hum.
 */
public final class FlashlightScene implements Scene {

    private static final Shader BEAM = new LinearGradient(55f, 0f, 120f, 0f,
            new int[]{0xFFFFF4DA, 0x00FFF4DA}, null, Shader.TileMode.CLAMP);

    private final Drawable torch;

    public FlashlightScene(Context context) {
        torch = ContextCompat.getDrawable(context, R.drawable.ic_detection_torch);
    }

    @Override
    public float designWidth() {
        return 120f;
    }

    @Override
    public float designHeight() {
        return 64f;
    }

    @Override
    public void draw(Canvas canvas, SceneFrame frame, CanvasKit kit) {
        Paint paint = kit.paint;
        float glow = torchGlow(frame);

        // Soft warm beam that fades out at the far end instead of a hard edge.
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(BEAM);
        paint.setAlpha((int) (22f * glow));
        kit.path.reset();
        kit.path.moveTo(55f, 20f);
        kit.path.lineTo(120f, 0f);
        kit.path.lineTo(120f, 64f);
        kit.path.lineTo(55f, 44f);
        kit.path.close();
        canvas.drawPath(kit.path, paint);
        paint.setAlpha((int) (30f * glow));
        kit.path.reset();
        kit.path.moveTo(55f, 24f);
        kit.path.lineTo(116f, 6f);
        kit.path.lineTo(116f, 58f);
        kit.path.lineTo(55f, 40f);
        kit.path.close();
        canvas.drawPath(kit.path, paint);
        paint.setAlpha((int) (42f * glow));
        kit.path.reset();
        kit.path.moveTo(56f, 28f);
        kit.path.lineTo(110f, 14f);
        kit.path.lineTo(110f, 50f);
        kit.path.lineTo(56f, 36f);
        kit.path.close();
        canvas.drawPath(kit.path, paint);
        paint.setAlpha(255);
        paint.setShader(null);

        if (torch != null) {
            torch.setBounds(8, 4, 64, 60);
            // The tube dims too, not just the beam.
            torch.setAlpha(165 + (int) (90f * glow));
            torch.draw(canvas);
            torch.setAlpha(255);
        }
        if (glow > 0.55f) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(AnimationMath.withAlpha(0xFFFFF4DA, (int) (80f * glow)));
            canvas.drawCircle(59f, 32f, 4.5f, paint);
        }
    }

    private float torchGlow(SceneFrame frame) {
        if (frame.ambient) {
            float t = frame.phase;
            if (t > 0.14f && t < 0.26f) {
                int step = (int) (t * 24f);
                return ((step * 7 + 3) % 3) == 0 ? 1f : 0.14f;
            }
            if (t > 0.68f && t < 0.74f) {
                int step = (int) (t * 24f);
                return ((step * 5) % 2) == 0 ? 0.95f : 0.22f;
            }
            return 0.94f + 0.06f * frame.wave();
        }
        float p = frame.progress;
        if (p >= 0.55f) return 1f;
        if (p < 0.06f) return 0.08f;
        int step = (int) (p * 70f);
        int hash = (step * 1103515245 + 12345) & 0x7fffffff;
        return (hash % 100) < 42 ? 1f : 0.10f;
    }
}
