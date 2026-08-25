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
    public void takeSwapsPreviewAndProgram() {
        ProductionControlState state = new ProductionControlState(
                ProductionScene.CAMERA,
                ProductionScene.VIDEO,
                ProductionControlState.Transition.DISSOLVE,
                750);

        ProductionControlState taken = state.take();

        assertEquals(ProductionScene.VIDEO, taken.getPreviewScene());
        assertEquals(ProductionScene.CAMERA, taken.getProgramScene());
        assertEquals(ProductionControlState.Transition.DISSOLVE, taken.getTransition());
        assertEquals(750, taken.getTransitionDurationMs());
    }

    @Test
    public void invalidConstructorValuesFallBackSafely() {
        ProductionControlState state = new ProductionControlState(
                null,
                null,
                null,
                99999);

        assertEquals(ProductionScene.DUET_PIP, state.getPreviewScene());
        assertEquals(ProductionScene.DUET_PIP, state.getProgramScene());
        assertEquals(ProductionControlState.Transition.CUT, state.getTransition());
        assertEquals(3000, state.getTransitionDurationMs());
    }
}
