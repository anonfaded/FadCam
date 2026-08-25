package com.fadcam.production;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ProductionCockpitStateTest {
    @Test public void instantTakeMakesPreviewProgramImmediately() {
        ProductionCockpitState s = ProductionCockpitState.defaults(ProductionScene.CAMERA);
        s = s.preview(ProductionScene.VIDEO).take();
        assertEquals(ProductionScene.VIDEO, s.getProgram());
        assertEquals(ProductionScene.VIDEO, s.getPreview());
    }

    @Test public void levelsAreClampedToSafeMixerRange() {
        ProductionCockpitState s = ProductionCockpitState.defaults(ProductionScene.CAMERA).levels(-5, 140, 50, 101);
        assertEquals(0, s.getCamera1());
        assertEquals(100, s.getCamera2());
        assertEquals(50, s.getMedia());
        assertEquals(100, s.getMaster());
    }

    @Test public void transitionDurationIsBounded() {
        ProductionCockpitState s = ProductionCockpitState.defaults(ProductionScene.CAMERA)
                .transition(ProductionCockpitState.Transition.WIPE, 5000);
        assertEquals(1000, s.getTransitionMs());
    }
}
