package com.fadcam;

import com.fadcam.service.BatchFfmpegOps;

/**
 * Single lookup point for build-scoped feature implementations.
 * <p>
 * Full builds carry the real implementations in src/full/ (FullFeaturesImpl,
 * BatchFfmpegOpsImpl); Lite builds compile the no-op defaults instead, so shared
 * code always gets a non-null, typed implementation. This is manual dependency
 * injection with a tiny composition root — see FADCAM_DI_PLAN.md for the future
 * framework migration.
 */
public final class FeatureRegistry {

    private static volatile FullFeatures fullFeatures;
    private static volatile BatchFfmpegOps batchFfmpegOps;

    private FeatureRegistry() {
    }

    public static FullFeatures features() {
        FullFeatures f = fullFeatures;
        if (f == null) {
            synchronized (FeatureRegistry.class) {
                f = fullFeatures;
                if (f == null) {
                    f = loadOr("com.fadcam.full.FullFeaturesImpl", FullFeatures.class,
                            new FullFeaturesDefault());
                    fullFeatures = f;
                }
            }
        }
        return f;
    }

    public static BatchFfmpegOps batchFfmpeg() {
        BatchFfmpegOps ops = batchFfmpegOps;
        if (ops == null) {
            synchronized (FeatureRegistry.class) {
                ops = batchFfmpegOps;
                if (ops == null) {
                    ops = loadOr("com.fadcam.service.BatchFfmpegOpsImpl", BatchFfmpegOps.class,
                            new com.fadcam.service.BatchFfmpegOpsDefault());
                    batchFfmpegOps = ops;
                }
            }
        }
        return ops;
    }

    private static <T> T loadOr(String implClassName, Class<T> type, T fallback) {
        try {
            T impl = type.cast(Class.forName(implClassName).getDeclaredConstructor().newInstance());
            return impl != null ? impl : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }
}
