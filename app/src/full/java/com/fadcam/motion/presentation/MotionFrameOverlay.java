package com.fadcam.motion.presentation;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * Draws exactly what the motion detector is seeing on top of its own analysis
 * frame, so the Motion Lab monitor is a real visual debug view rather than a
 * copy of the camera preview.
 *
 * <p>Colour code (matches professional CCTV debug views):
 * <ul>
 *   <li>green box — significant motion blob that passed the size filter</li>
 *   <li>amber box — blob below the size filter (noise / too small)</li>
 *   <li>grey box — AI detection that did not pass the confidence filter</li>
 *   <li>cyan cross — weighted motion centre</li>
 * </ul>
 */
public final class MotionFrameOverlay {

    private MotionFrameOverlay() {
    }

    /** Snapshot of one detector frame for the visual overlay. */
    public static final class Frame {
        public final int width;
        public final int height;
        public final List<Rect> blobs;
        public final float minBlobAreaRatio;
        public final float centerX;
        public final float centerY;
        public final float rawScore;
        public final float smoothedScore;
        public final float startThreshold;
        public final float stopThreshold;
        public final float changedArea;
        public final float strongArea;
        public final float meanDelta;
        public final float bgDelta;
        public final float maxDelta;
        public final String state;
        public final String backend;
        public final float analysisFps;
        public final boolean globalSuppressed;

        public Frame(int width, int height, List<Rect> blobs, float minBlobAreaRatio,
                     float centerX, float centerY, float rawScore, float smoothedScore,
                     float startThreshold, float stopThreshold, float changedArea,
                     float strongArea, float meanDelta, float bgDelta, float maxDelta,
                     String state, String backend, float analysisFps,
                     boolean globalSuppressed) {
            this.width = width;
            this.height = height;
            this.blobs = blobs;
            this.minBlobAreaRatio = minBlobAreaRatio;
            this.centerX = centerX;
            this.centerY = centerY;
            this.rawScore = rawScore;
            this.smoothedScore = smoothedScore;
            this.startThreshold = startThreshold;
            this.stopThreshold = stopThreshold;
            this.changedArea = changedArea;
            this.strongArea = strongArea;
            this.meanDelta = meanDelta;
            this.bgDelta = bgDelta;
            this.maxDelta = maxDelta;
            this.state = state;
            this.backend = backend;
            this.analysisFps = analysisFps;
            this.globalSuppressed = globalSuppressed;
        }
    }

    /**
     * Returns a copy of {@code base} with the detector overlay drawn on top.
     * The bitmap is never mutated in place, so the image view can keep showing
     * the previous frame until this one is ready.
     */
    @NonNull
    public static Bitmap render(@NonNull Bitmap base, @Nullable Frame frame) {
        Bitmap out = base.copy(Bitmap.Config.ARGB_8888, true);
        if (out == null || frame == null || frame.width <= 0 || frame.height <= 0) {
            return out != null ? out : base;
        }
        Canvas canvas = new Canvas(out);
        float sx = out.getWidth() / (float) frame.width;
        float sy = out.getHeight() / (float) frame.height;

        Paint pass = new Paint(Paint.ANTI_ALIAS_FLAG);
        pass.setStyle(Paint.Style.STROKE);
        pass.setStrokeWidth(Math.max(2f, out.getWidth() * 0.004f));
        pass.setColor(Color.parseColor("#33D17A"));

        Paint below = new Paint(Paint.ANTI_ALIAS_FLAG);
        below.setStyle(Paint.Style.STROKE);
        below.setStrokeWidth(Math.max(1f, out.getWidth() * 0.0025f));
        below.setColor(Color.parseColor("#F5A623"));

        Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(Math.max(16f, out.getWidth() * 0.028f));
        labelPaint.setFakeBoldText(true);

        if (frame.blobs != null) {
            float frameArea = frame.width * (float) frame.height;
            for (Rect b : frame.blobs) {
                if (b == null) {
                    continue;
                }
                RectF r = new RectF(b.left * sx, b.top * sy, b.right * sx, b.bottom * sy);
                float ratio = (b.width() * (float) b.height()) / frameArea;
                canvas.drawRect(r, ratio >= frame.minBlobAreaRatio ? pass : below);
            }
        }

        // Motion centre
        float cx = frame.centerX * out.getWidth();
        float cy = frame.centerY * out.getHeight();
        Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);
        cross.setColor(Color.parseColor("#67E8F9"));
        cross.setStrokeWidth(Math.max(2f, out.getWidth() * 0.004f));
        float arm = Math.max(10f, out.getWidth() * 0.03f);
        canvas.drawLine(cx - arm, cy, cx + arm, cy, cross);
        canvas.drawLine(cx, cy - arm, cx, cy + arm, cross);
        canvas.drawCircle(cx, cy, arm * 0.45f, cross);

        // Readable HUD: backend, fps, state, score vs threshold, area
        drawHud(canvas, frame, out.getWidth(), out.getHeight(), labelPaint);
        return out;
    }

    private static void drawHud(Canvas canvas, Frame f, int w, int h, Paint textPaint) {
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.argb(150, 0, 0, 0));

        String line1 = (f.backend == null ? "-" : f.backend)
                + " | " + Math.round(f.analysisFps) + " fps"
                + " | " + (f.state == null ? "-" : f.state);
        String line2 = String.format(java.util.Locale.US,
                "score %.2f / start %.2f | area %.3f",
                f.smoothedScore, f.startThreshold, f.changedArea);
        String line3 = String.format(java.util.Locale.US,
                "blobs %d | min %.3f%% | global %s",
                f.blobs == null ? 0 : f.blobs.size(),
                f.minBlobAreaRatio * 100f,
                f.globalSuppressed ? "SUPPRESSED" : "ok");

        float padding = w * 0.012f;
        float lineHeight = textPaint.getTextSize() * 1.25f;
        float boxHeight = lineHeight * 3f + padding * 2f;
        canvas.drawRect(0, 0, w, boxHeight, bg);
        float y = padding - textPaint.ascent();
        canvas.drawText(line1, padding, y, textPaint);
        y += lineHeight;
        canvas.drawText(line2, padding, y, textPaint);
        y += lineHeight;
        canvas.drawText(line3, padding, y, textPaint);

        // Score bar: smoothed score against the start threshold.
        float barH = Math.max(4f, h * 0.010f);
        float barY = h - barH - padding;
        Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        bar.setColor(Color.argb(120, 0, 0, 0));
        canvas.drawRect(0, barY, w, h, bar);
        bar.setColor(Color.parseColor("#33D17A"));
        canvas.drawRect(0, barY, w * clamp01(f.smoothedScore), h, bar);
        bar.setColor(Color.parseColor("#F5A623"));
        float tx = w * clamp01(f.startThreshold);
        canvas.drawRect(tx - 1f, barY, tx + 1f, h, bar);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
