package com.fadcam.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StorageDestinationManagerTest {

    @Test
    public void internalDestinationIsNotExternal() {
        assertFalse(StorageDestinationManager.isExternalDestination(
                StorageDestinationManager.DESTINATION_INTERNAL));
    }

    @Test
    public void sdCardDestinationIsExternal() {
        assertTrue(StorageDestinationManager.isExternalDestination(
                StorageDestinationManager.DESTINATION_SD));
    }

    @Test
    public void usbDestinationIsExternal() {
        assertTrue(StorageDestinationManager.isExternalDestination(
                StorageDestinationManager.DESTINATION_USB));
    }

    @Test
    public void unknownDestinationIsRejected() {
        assertFalse(StorageDestinationManager.isExternalDestination("cloud"));
        assertFalse(StorageDestinationManager.isExternalDestination(null));
    }
}
