package com.fadcam.production;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persistent state for the TV production switcher.
 *
 * Preview and Program are deliberately independent: selecting a source only
 * changes Preview; CUT/TAKE/AUTO are the only paths that promote Preview to
 * Program. Camera slots are persisted separately for remote/guest sources.
 */
public final class ProductionControlState {
    public enum Transition {
        CUT("Cut"),
        DISSOLVE("Dissolve"),
        FADE("Fade"),
        WIPE("Wipe");

        private final String title;
        Transition(String title) { this.title = title; }
        public String getTitle() { return title; }
    }

    private static final String PREFS = "fadcam_tv_production";
    private static final String KEY_PREVIEW = "switcher_preview";
    private static final String KEY_PROGRAM = "switcher_program";
    private static final String KEY_PREVIEW_CAMERA = "switcher_preview_camera_slot";
    private static final String KEY_PROGRAM_CAMERA = "switcher_program_camera_slot";
    private static final String KEY_TRANSITION = "switcher_transition";
    private static final String KEY_DURATION_MS = "switcher_transition_duration_ms";
    private static final int MIN_DURATION_MS = 100;
    private static final int MAX_DURATION_MS = 3000;
    private static final int MIN_CAMERA_SLOT = 1;
    private static final int MAX_CAMERA_SLOT = 6;

    private final ProductionScene previewScene;
    private final ProductionScene programScene;
    private final int previewCameraSlot;
    private final int programCameraSlot;
    private final Transition transition;
    private final int transitionDurationMs;

    public ProductionControlState(ProductionScene previewScene,
                                  ProductionScene programScene,
                                  Transition transition,
                                  int transitionDurationMs) {
        this(previewScene, programScene, 1, 1, transition, transitionDurationMs);
    }

    public ProductionControlState(ProductionScene previewScene,
                                  ProductionScene programScene,
                                  int previewCameraSlot,
                                  int programCameraSlot,
                                  Transition transition,
                                  int transitionDurationMs) {
        this.previewScene = previewScene == null ? ProductionScene.DUET_PIP : previewScene;
        this.programScene = programScene == null ? ProductionScene.DUET_PIP : programScene;
        this.previewCameraSlot = clampCameraSlot(previewCameraSlot);
        this.programCameraSlot = clampCameraSlot(programCameraSlot);
        this.transition = transition == null ? Transition.CUT : transition;
        this.transitionDurationMs = clampDuration(transitionDurationMs);
    }

    public static ProductionControlState load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new ProductionControlState(
                readScene(prefs, KEY_PREVIEW, ProductionScene.DUET_PIP),
                readScene(prefs, KEY_PROGRAM, ProductionScene.DUET_PIP),
                prefs.getInt(KEY_PREVIEW_CAMERA, 1),
                prefs.getInt(KEY_PROGRAM_CAMERA, 1),
                readTransition(prefs.getString(KEY_TRANSITION, Transition.CUT.name())),
                prefs.getInt(KEY_DURATION_MS, 500));
    }

    public void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PREVIEW, previewScene.name())
                .putString(KEY_PROGRAM, programScene.name())
                .putInt(KEY_PREVIEW_CAMERA, previewCameraSlot)
                .putInt(KEY_PROGRAM_CAMERA, programCameraSlot)
                .putString(KEY_TRANSITION, transition.name())
                .putInt(KEY_DURATION_MS, transitionDurationMs)
                .apply();
    }

    public ProductionScene getPreviewScene() { return previewScene; }
    public ProductionScene getProgramScene() { return programScene; }
    public int getPreviewCameraSlot() { return previewCameraSlot; }
    public int getProgramCameraSlot() { return programCameraSlot; }
    public Transition getTransition() { return transition; }
    public int getTransitionDurationMs() { return transitionDurationMs; }

    public ProductionControlState withPreview(ProductionScene scene) {
        return new ProductionControlState(scene, programScene, previewCameraSlot, programCameraSlot,
                transition, transitionDurationMs);
    }

    public ProductionControlState withProgram(ProductionScene scene) {
        return new ProductionControlState(previewScene, scene, previewCameraSlot, programCameraSlot,
                transition, transitionDurationMs);
    }

    public ProductionControlState withPreviewCameraSlot(int slot) {
        return new ProductionControlState(ProductionScene.CAMERA, programScene, slot, programCameraSlot,
                transition, transitionDurationMs);
    }

    public ProductionControlState withProgramCameraSlot(int slot) {
        return new ProductionControlState(previewScene, ProductionScene.CAMERA, previewCameraSlot, slot,
                transition, transitionDurationMs);
    }

    public ProductionControlState withTransition(Transition value) {
        return new ProductionControlState(previewScene, programScene, previewCameraSlot, programCameraSlot,
                value, transitionDurationMs);
    }

    public ProductionControlState withDuration(int durationMs) {
        return new ProductionControlState(previewScene, programScene, previewCameraSlot, programCameraSlot,
                transition, durationMs);
    }

    /**
     * Promote Preview to Program while keeping Preview selected for the next shot.
     * This mirrors a real production switcher: TAKE changes the Program bus but
     * does not unexpectedly replace the producer's next-shot selection.
     */
    public ProductionControlState take() {
        return new ProductionControlState(
                previewScene,
                previewScene,
                previewCameraSlot,
                previewCameraSlot,
                transition,
                transitionDurationMs);
    }

    public static int clampDuration(int durationMs) {
        return Math.max(MIN_DURATION_MS, Math.min(MAX_DURATION_MS, durationMs));
    }

    public static int clampCameraSlot(int slot) {
        return Math.max(MIN_CAMERA_SLOT, Math.min(MAX_CAMERA_SLOT, slot));
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
