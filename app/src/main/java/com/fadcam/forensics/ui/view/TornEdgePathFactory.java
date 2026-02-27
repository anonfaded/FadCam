package com.fadcam.forensics.ui.view;

import android.graphics.Path;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.NonNull;

import com.fadcam.BuildConfig;

import java.util.Locale;
import java.util.Random;

/**
 * Deterministic torn-edge path factory with bounded cache.
 */
public final class TornEdgePathFactory {
    private static final String TAG = "TornEdgePathFactory";

    private static final LruCache<String, Path> PATH_CACHE = new LruCache<>(220);
    private static int cacheHitCount;
    private static int cacheMissCount;

    private TornEdgePathFactory() {
    }

    @NonNull
    public static Path getOrCreate(
            int width,
            int height,
            int seed,
            float amplitudePx,
            float frequency
    ) {
        if (width <= 0 || height <= 0) {
            Path fallback = new Path();
            fallback.addRect(0f, 0f, Math.max(0, width), Math.max(0, height), Path.Direction.CW);
            return fallback;
        }

        float safeAmplitude = clamp(amplitudePx, 1f, Math.max(2f, height * 0.12f));
        float safeFrequency = clamp(frequency, 0.6f, 8f);
        String key = String.format(
                Locale.US,
                "%d|%d|%d|%.2f|%.2f",
                width,
                height,
                seed,
                safeAmplitude,
                safeFrequency
        );

        Path cached = PATH_CACHE.get(key);
        if (cached != null) {
            cacheHitCount++;
            maybeLogStats();
            return new Path(cached);
        }

        Path path = buildPath(width, height, seed, safeAmplitude, safeFrequency);
        PATH_CACHE.put(key, new Path(path));
        cacheMissCount++;
        maybeLogStats();
        return path;
    }

    private static Path buildPath(
            int width,
            int height,
            int seed,
            float amplitudePx,
            float frequency
    ) {
        Path p = new Path();
        final float w = width;
        final float h = height;

        Random random = new Random(seed * 1103515245L + 12345L);
        int segments = Math.max(12, Math.round((w / 26f) * frequency));
        float step = w / (float) segments;
        float[] topY = new float[segments + 1];
        float[] bottomY = new float[segments + 1];

        for (int i = 0; i <= segments; i++) {
            float n = (random.nextFloat() * 2f) - 1f;
            float y = amplitudePx * (0.45f + 0.55f * random.nextFloat()) * Math.max(-1f, n);
            topY[i] = clamp(y, -amplitudePx, amplitudePx);
        }

        random.setSeed((seed * 73856093L) ^ 0x9e3779b9L);
        for (int i = 0; i <= segments; i++) {
            float n = (random.nextFloat() * 2f) - 1f;
            float y = h + amplitudePx * (0.45f + 0.55f * random.nextFloat()) * Math.max(-1f, n);
            bottomY[i] = clamp(y, h - amplitudePx, h + amplitudePx);
        }

        p.moveTo(0f, topY[0]);

        for (int i = 1; i <= segments; i++) {
            float prevX = (i - 1) * step;
            float prevY = topY[i - 1];
            float x = Math.min(w, i * step);
            float y = topY[i];
            float midX = (prevX + x) * 0.5f;
            float midY = (prevY + y) * 0.5f;
            p.quadTo(prevX, prevY, midX, midY);
            if (i == segments) {
                p.quadTo(midX, midY, x, y);
            }
        }

        float rightTop = topY[segments];
        float rightBottom = bottomY[segments];
        float rightCtrlX = w + (amplitudePx * 0.12f);
        float rightMid = rightTop + ((rightBottom - rightTop) * 0.5f);
        p.quadTo(rightCtrlX, rightTop, w, rightMid);
        p.quadTo(rightCtrlX, rightBottom, w, rightBottom);

        for (int i = segments; i >= 1; i--) {
            float prevX = i * step;
            float prevY = bottomY[i];
            float x = (i - 1) * step;
            float y = bottomY[i - 1];
            float midX = (prevX + x) * 0.5f;
            float midY = (prevY + y) * 0.5f;
            p.quadTo(prevX, prevY, midX, midY);
            if (i == 1) {
                p.quadTo(midX, midY, x, y);
            }
        }

        float leftBottom = bottomY[0];
        float leftTop = topY[0];
        float leftCtrlX = -amplitudePx * 0.12f;
        float leftMid = leftBottom - ((leftBottom - leftTop) * 0.5f);
        p.quadTo(leftCtrlX, leftBottom, 0f, leftMid);
        p.quadTo(leftCtrlX, leftTop, 0f, leftTop);

        p.close();
        return p;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void maybeLogStats() {
        if (!BuildConfig.DEBUG) {
            return;
        }
        int total = cacheHitCount + cacheMissCount;
        if (total == 1 || total % 60 == 0) {
            Log.d(TAG, "pathCache hits=" + cacheHitCount + ", misses=" + cacheMissCount + ", size=" + PATH_CACHE.size());
        }
    }
}
