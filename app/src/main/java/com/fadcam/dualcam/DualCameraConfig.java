package com.fadcam.dualcam;

import androidx.annotation.NonNull;

import java.io.Serializable;

/**
 * Immutable configuration for dual camera (PiP) recording.
 * Use {@link Builder} to construct or modify instances.
 */
public class DualCameraConfig implements Serializable {

    // ── Enums ──────────────────────────────────────────────────────────────

    /** Corner position of the Picture-in-Picture (secondary) camera. */
    public enum PipPosition {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    /** Relative size of the PiP camera overlay as a fraction of output width. */
    public enum PipSize {
        SMALL(0.20f),    // 20 % of output width
        MEDIUM(0.30f),   // 30 %
        LARGE(0.40f);    // 40 %

        /** Ratio of PiP width to full output width. */
        public final float ratio;

        PipSize(float ratio) {
            this.ratio = ratio;
        }
    }

    /** Which physical camera is rendered as the full-screen (primary) camera. */
    public enum PrimaryCamera {
        /** Back camera fills the frame; front camera is PiP. */
        BACK,
        /** Front camera fills the frame; back camera is PiP. */
        FRONT
    }

    // ── Fields ─────────────────────────────────────────────────────────────

    private final PipPosition pipPosition;
    private final PipSize pipSize;
    private final PrimaryCamera primaryCamera;
    private final boolean showPipBorder;
    private final boolean roundPipCorners;
    /** Margin (dp) between PiP overlay and output edge. */
    private final int pipMarginDp;

    // ── Constructor ────────────────────────────────────────────────────────

    private DualCameraConfig(
            PipPosition pipPosition,
            PipSize pipSize,
            PrimaryCamera primaryCamera,
            boolean showPipBorder,
            boolean roundPipCorners,
            int pipMarginDp) {
        this.pipPosition = pipPosition;
        this.pipSize = pipSize;
        this.primaryCamera = primaryCamera;
        this.showPipBorder = showPipBorder;
        this.roundPipCorners = roundPipCorners;
        this.pipMarginDp = pipMarginDp;
    }

    // ── Factory ────────────────────────────────────────────────────────────

    /**
     * Returns a sensible default configuration:
     * back camera primary, medium PiP bottom-right, border + rounded corners.
     */
    @NonNull
    public static DualCameraConfig defaultConfig() {
        return new Builder().build();
    }

    // ── Getters ────────────────────────────────────────────────────────────

    @NonNull
    public PipPosition getPipPosition() {
        return pipPosition;
    }

    @NonNull
    public PipSize getPipSize() {
        return pipSize;
    }

    @NonNull
    public PrimaryCamera getPrimaryCamera() {
        return primaryCamera;
    }

    public boolean isShowPipBorder() {
        return showPipBorder;
    }

    public boolean isRoundPipCorners() {
        return roundPipCorners;
    }

    public int getPipMarginDp() {
        return pipMarginDp;
    }

    // ── Builder ────────────────────────────────────────────────────────────

    /**
     * Builder for constructing {@link DualCameraConfig} instances.
     */
    public static class Builder {
        private PipPosition pipPosition = PipPosition.BOTTOM_RIGHT;
        private PipSize pipSize = PipSize.MEDIUM;
        private PrimaryCamera primaryCamera = PrimaryCamera.BACK;
        private boolean showPipBorder = true;
        private boolean roundPipCorners = true;
        private int pipMarginDp = 12;

        public Builder() { }

        /** Copy-constructor: start from an existing config. */
        public Builder(@NonNull DualCameraConfig source) {
            this.pipPosition = source.pipPosition;
            this.pipSize = source.pipSize;
            this.primaryCamera = source.primaryCamera;
            this.showPipBorder = source.showPipBorder;
            this.roundPipCorners = source.roundPipCorners;
            this.pipMarginDp = source.pipMarginDp;
        }

        @NonNull
        public Builder pipPosition(@NonNull PipPosition pos) {
            this.pipPosition = pos;
            return this;
        }

        @NonNull
        public Builder pipSize(@NonNull PipSize size) {
            this.pipSize = size;
            return this;
        }

        @NonNull
        public Builder primaryCamera(@NonNull PrimaryCamera cam) {
            this.primaryCamera = cam;
            return this;
        }

        @NonNull
        public Builder showPipBorder(boolean show) {
            this.showPipBorder = show;
            return this;
        }

        @NonNull
        public Builder roundPipCorners(boolean round) {
            this.roundPipCorners = round;
            return this;
        }

        @NonNull
        public Builder pipMarginDp(int dp) {
            this.pipMarginDp = Math.max(0, dp);
            return this;
        }

        @NonNull
        public DualCameraConfig build() {
            return new DualCameraConfig(
                    pipPosition, pipSize, primaryCamera,
                    showPipBorder, roundPipCorners, pipMarginDp);
        }
    }

    // ── toString ───────────────────────────────────────────────────────────

    @NonNull
    @Override
    public String toString() {
        return "DualCameraConfig{" +
                "primary=" + primaryCamera +
                ", pip=" + pipPosition +
                ", size=" + pipSize +
                ", border=" + showPipBorder +
                ", rounded=" + roundPipCorners +
                ", margin=" + pipMarginDp + "dp}";
    }
}
