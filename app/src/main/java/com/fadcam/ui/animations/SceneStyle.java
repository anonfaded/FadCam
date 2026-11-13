package com.fadcam.ui.animations;

/**
 * Palette and chrome switches shared by every animation component.
 *
 * <p>The defaults are the FadCam dossier look. A scene can be themed per host
 * (a dialog, a settings card, a full-screen player) without touching drawing
 * code:</p>
 *
 * <pre>{@code
 * SceneStyle style = SceneStyle.defaults().toBuilder()
 *         .accent(0xFF7BD1E8)
 *         .stageGrid(false)
 *         .build();
 * demoView.setStyle(style);
 * }</pre>
 *
 * <p>Instances are immutable, so one style can safely be shared by many views.</p>
 */
public final class SceneStyle {

    public final int background;
    public final int panel;
    public final int panelAlt;
    public final int panelDeep;
    public final int line;
    public final int accent;
    public final int accentDeep;
    public final int ink;
    public final int muted;
    public final int dim;
    public final int alert;
    public final int success;

    /** Draws the faint 12-unit layout grid behind a preview. */
    public final boolean stageGrid;
    /** Draws the four registration ticks at the stage corners. */
    public final boolean cornerTicks;

    private static final SceneStyle DEFAULT = new SceneStyle(new Builder());

    public static SceneStyle defaults() {
        return DEFAULT;
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    private SceneStyle(Builder b) {
        background = b.background;
        panel = b.panel;
        panelAlt = b.panelAlt;
        panelDeep = b.panelDeep;
        line = b.line;
        accent = b.accent;
        accentDeep = b.accentDeep;
        ink = b.ink;
        muted = b.muted;
        dim = b.dim;
        alert = b.alert;
        success = b.success;
        stageGrid = b.stageGrid;
        cornerTicks = b.cornerTicks;
    }

    /** Mutable builder; {@link #build()} returns an immutable style. */
    public static final class Builder {
        private int background = 0xFF070808;
        private int panel = 0xFF101213;
        private int panelAlt = 0xFF181B1D;
        private int panelDeep = 0xFF202326;
        private int line = 0xFF2C2F31;
        private int accent = 0xFFE8C77B;
        private int accentDeep = 0xFFC6A66A;
        private int ink = 0xFFF3F0E8;
        private int muted = 0xFF9C9991;
        private int dim = 0xFF5C605F;
        private int alert = 0xFFD9564A;
        private int success = 0xFF86D3A0;
        private boolean stageGrid = true;
        private boolean cornerTicks = true;

        Builder() {
        }

        Builder(SceneStyle source) {
            background = source.background;
            panel = source.panel;
            panelAlt = source.panelAlt;
            panelDeep = source.panelDeep;
            line = source.line;
            accent = source.accent;
            accentDeep = source.accentDeep;
            ink = source.ink;
            muted = source.muted;
            dim = source.dim;
            alert = source.alert;
            success = source.success;
            stageGrid = source.stageGrid;
            cornerTicks = source.cornerTicks;
        }

        public Builder background(int color) { background = color; return this; }
        public Builder panel(int color) { panel = color; return this; }
        public Builder panelAlt(int color) { panelAlt = color; return this; }
        public Builder panelDeep(int color) { panelDeep = color; return this; }
        public Builder line(int color) { line = color; return this; }
        public Builder accent(int color) { accent = color; return this; }
        public Builder accentDeep(int color) { accentDeep = color; return this; }
        public Builder ink(int color) { ink = color; return this; }
        public Builder muted(int color) { muted = color; return this; }
        public Builder dim(int color) { dim = color; return this; }
        public Builder alert(int color) { alert = color; return this; }
        public Builder success(int color) { success = color; return this; }
        public Builder stageGrid(boolean enabled) { stageGrid = enabled; return this; }
        public Builder cornerTicks(boolean enabled) { cornerTicks = enabled; return this; }

        public SceneStyle build() {
            return new SceneStyle(this);
        }
    }
}
