package com.fadcam.production;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent cockpit controls; scene/program authority remains ProductionControlState. */
public final class ProductionCockpitState {
    public enum Transition { CUT, MIX, FADE, WIPE, FLASH }
    private static final String PREFS = "fadcam_production_cockpit";
    private final ProductionScene preview, program;
    private final Transition transition;
    private final int transitionMs, camera1, camera2, media, master;

    public ProductionCockpitState(ProductionScene preview, ProductionScene program, Transition transition,
                                  int transitionMs, int camera1, int camera2, int media, int master) {
        this.preview = preview;
        this.program = program;
        this.transition = transition == null ? Transition.CUT : transition;
        this.transitionMs = clamp(transitionMs);
        this.camera1 = clampLevel(camera1); this.camera2 = clampLevel(camera2);
        this.media = clampLevel(media); this.master = clampLevel(master);
    }

    public static ProductionCockpitState defaults(ProductionScene program) {
        return new ProductionCockpitState(program, program, Transition.CUT, 0, 75, 75, 65, 85);
    }

    public static ProductionCockpitState load(Context context, ProductionScene program) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Transition t;
        try { t = Transition.valueOf(p.getString("transition", Transition.CUT.name())); }
        catch (Exception e) { t = Transition.CUT; }
        return new ProductionCockpitState(program, program, t, p.getInt("transition_ms", 0),
                p.getInt("camera1", 75), p.getInt("camera2", 75), p.getInt("media", 65), p.getInt("master", 85));
    }

    public void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("transition", transition.name()).putInt("transition_ms", transitionMs)
                .putInt("camera1", camera1).putInt("camera2", camera2)
                .putInt("media", media).putInt("master", master).apply();
    }

    public ProductionScene getPreview() { return preview; }
    public ProductionScene getProgram() { return program; }
    public Transition getTransition() { return transition; }
    public int getTransitionMs() { return transitionMs; }
    public int getCamera1() { return camera1; }
    public int getCamera2() { return camera2; }
    public int getMedia() { return media; }
    public int getMaster() { return master; }
    public ProductionCockpitState preview(ProductionScene scene) { return new ProductionCockpitState(scene, program, transition, transitionMs, camera1, camera2, media, master); }
    public ProductionCockpitState take() { return new ProductionCockpitState(preview, preview, transition, transitionMs, camera1, camera2, media, master); }
    public ProductionCockpitState transition(Transition value, int durationMs) { return new ProductionCockpitState(preview, program, value, durationMs, camera1, camera2, media, master); }
    public ProductionCockpitState levels(int a, int b, int m, int master) { return new ProductionCockpitState(preview, program, transition, transitionMs, a, b, m, master); }
    public static int clamp(int value) { return Math.max(0, Math.min(3000, value)); }
    public static int clampLevel(int value) { return Math.max(0, Math.min(100, value)); }
}
