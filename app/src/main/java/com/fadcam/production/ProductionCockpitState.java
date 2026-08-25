package com.fadcam.production;

/**
 * Immutable state model for the producer cockpit. All switching operations are
 * synchronous so a CUT/TAKE updates PROGRAM in the same UI event, avoiding a
 * timer-based scene change for the fast-switch path.
 */
public final class ProductionCockpitState {
    public enum Transition { CUT, MIX, FADE, WIPE, FLASH }

    private final ProductionScene preview;
    private final ProductionScene program;
    private final Transition transition;
    private final int transitionMs;
    private final int camera1;
    private final int camera2;
    private final int media;
    private final int master;

    public ProductionCockpitState(ProductionScene preview, ProductionScene program,
                                  Transition transition, int transitionMs,
                                  int camera1, int camera2, int media, int master) {
        this.preview = preview;
        this.program = program;
        this.transition = transition;
        this.transitionMs = clamp(transitionMs);
        this.camera1 = clampLevel(camera1);
        this.camera2 = clampLevel(camera2);
        this.media = clampLevel(media);
        this.master = clampLevel(master);
    }

    public static ProductionCockpitState defaults(ProductionScene program) {
        return new ProductionCockpitState(program, program, Transition.CUT, 0, 75, 75, 65, 85);
    }

    public ProductionScene getPreview() { return preview; }
    public ProductionScene getProgram() { return program; }
    public Transition getTransition() { return transition; }
    public int getTransitionMs() { return transitionMs; }
    public int getCamera1() { return camera1; }
    public int getCamera2() { return camera2; }
    public int getMedia() { return media; }
    public int getMaster() { return master; }

    public ProductionCockpitState preview(ProductionScene scene) {
        return new ProductionCockpitState(scene, program, transition, transitionMs, camera1, camera2, media, master);
    }

    public ProductionCockpitState take() {
        return new ProductionCockpitState(preview, preview, transition, transitionMs, camera1, camera2, media, master);
    }

    public ProductionCockpitState transition(Transition value, int durationMs) {
        return new ProductionCockpitState(preview, program, value, durationMs, camera1, camera2, media, master);
    }

    public ProductionCockpitState levels(int camera1, int camera2, int media, int master) {
        return new ProductionCockpitState(preview, program, transition, transitionMs, camera1, camera2, media, master);
    }

    public static int clamp(int value) { return Math.max(0, Math.min(1000, value)); }
    public static int clampLevel(int value) { return Math.max(0, Math.min(100, value)); }
}
