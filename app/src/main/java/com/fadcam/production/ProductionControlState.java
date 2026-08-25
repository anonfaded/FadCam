package com.fadcam.production;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent, validation-friendly state for the V1 TV production switcher. */
public final class ProductionControlState {
    public enum Transition {
        CUT("Cut"),
        DISSOLVE("Dissolve"),
        FADE("Fade"),
        WIPE("Wipe");

        private final String title;

        Transition(String title) {
            this.title = title;
        }

        public String getTitle() {
            return title;
        }
    }

    private static final String PREFS = "fadcam_tv_production";
    private static final String KEY_PREVIEW = "switcher_preview";
    private static final String KEY_PROGRAM = "switcher_program";
    private static final String KEY_TRANSITION = "switcher_transition";
    private static final String KEY_DURATION_MS = "switcher_transition_duration_ms";
    private static final int MIN_DURATION_MS = 100;
    private static final int MAX_DURATION_MS = 3000;

    private final ProductionScene previewScene;
    private final ProductionScene programScene;
    private final Transition transition;
    private final int transitionDurationMs;

    public ProductionControlState(ProductionScene previewScene,
                                  ProductionScene programScene,
                                  Transition transition,
                                  int transitionDurationMs) {
        this.previewScene = previewScene == null ? ProductionScene.DUET_PIP : previewScene;
        this.programScene = programScene == null ? ProductionScene.DUET_PIP : programScene;
        this.transition = transition == null ? Transition.CUT : transition;
        this.transitionDurationMs = clampDuration(transitionDurationMs);
    }

    public static ProductionControlState load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new ProductionControlState(
                readScene(prefs, KEY_PREVIEW, ProductionScene.DUET_PIP),
                readScene(prefs, KEY_PROGRAM, ProductionScene.DUET_PIP),
                readTransition(prefs.getString(KEY_TRANSITION, Transition.CUT.name())),
                prefs.getInt(KEY_DURATION_MS, 500));
    }

    public void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PREVIEW, previewScene.name())
                .putString(KEY_PROGRAM, programScene.name())
                .putString(KEY_TRANSITION, transition.name())
                .putInt(KEY_DURATION_MS, transitionDurationMs)
                .apply();
    }

    public ProductionScene getPreviewScene() { return previewScene; }
    public ProductionScene getProgramScene() { return programScene; }
    public Transition getTransition() { return transition; }
    public int getTransitionDurationMs() { return transitionDurationMs; }

    public ProductionControlState withPreview(ProductionScene scene) {
        return new ProductionControlState(scene, programScene, transition, transitionDurationMs);
    }

    public ProductionControlState withProgram(ProductionScene scene) {
        return new ProductionControlState(previewScene, scene, transition, transitionDurationMs);
    }

    public ProductionControlState withTransition(Transition value) {
        return new ProductionControlState(previewScene, programScene, value, transitionDurationMs);
    }

    public ProductionControlState withDuration(int durationMs) {
        return new ProductionControlState(previewScene, programScene, transition, durationMs);
    }

    public ProductionControlState take() {
        return new ProductionControlState(programScene, previewScene, transition, transitionDurationMs);
    }

    public static int clampDuration(int durationMs) {
        return Math.max(MIN_DURATION_MS, Math.min(MAX_DURATION_MS, durationMs));
    }

    private static ProductionScene readScene(SharedPreferences prefs, String key, ProductionScene fallback) {
        String value = prefs.getString(key, fallback.name());
        if (value == null) return fallback;
        try {
            return ProductionScene.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static Transition readTransition(String value) {
        if (value == null) return Transition.CUT;
        try {
            return Transition.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return Transition.CUT;
        }
    }
}
