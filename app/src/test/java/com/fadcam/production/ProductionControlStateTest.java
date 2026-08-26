package com.fadcam.production;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ProductionControlStateTest {
    @Test
    public void durationIsClampedToSafeRange() {
        assertEquals(100, ProductionControlState.clampDuration(-1));
        assertEquals(100, ProductionControlState.clampDuration(100));
        assertEquals(1500, ProductionControlState.clampDuration(1500));
        assertEquals(3000, ProductionControlState.clampDuration(5000));
    }

    @Test
    public void takePromotesPreviewAndKeepsPreviewSelected() {
        ProductionControlState state = new ProductionControlState(
                ProductionScene.CAMERA,
                ProductionScene.VIDEO,
                2,
                4,
                ProductionControlState.Transition.DISSOLVE,
                750);

        ProductionControlState taken = state.take();

        assertEquals(ProductionScene.CAMERA, taken.getPreviewScene());
        assertEquals(ProductionScene.CAMERA, taken.getProgramScene());
        assertEquals(2, taken.getPreviewCameraSlot());
        assertEquals(2, taken.getProgramCameraSlot());
        assertEquals(ProductionControlState.Transition.DISSOLVE, taken.getTransition());
        assertEquals(750, taken.getTransitionDurationMs());
    }

    @Test
    public void invalidConstructorValuesFallBackSafely() {
        ProductionControlState state = new ProductionControlState(
                null, null, 99, -10, null, 99999);

        assertEquals(ProductionScene.DUET_PIP, state.getPreviewScene());
        assertEquals(ProductionScene.DUET_PIP, state.getProgramScene());
        assertEquals(6, state.getPreviewCameraSlot());
        assertEquals(1, state.getProgramCameraSlot());
        assertEquals(ProductionControlState.Transition.CUT, state.getTransition());
        assertEquals(3000, state.getTransitionDurationMs());
    }

    @Test
    public void selectingPreviewCameraDoesNotChangeProgram() {
        ProductionControlState state = new ProductionControlState(
                ProductionScene.VIDEO,
                ProductionScene.DUET_PIP,
                1,
                3,
                ProductionControlState.Transition.CUT,
                500);

        ProductionControlState selected = state.withPreviewCameraSlot(5);

        assertEquals(ProductionScene.CAMERA, selected.getPreviewScene());
        assertEquals(5, selected.getPreviewCameraSlot());
        assertEquals(ProductionScene.DUET_PIP, selected.getProgramScene());
        assertEquals(3, selected.getProgramCameraSlot());
    }
}
