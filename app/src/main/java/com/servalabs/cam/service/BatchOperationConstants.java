package com.servalabs.cam.service;

/**
 * Constants for batch media operations (merge, export, etc.)
 */
public class BatchOperationConstants {

    public static final String ACTION_BATCH_OPERATION_UPDATED =
            "com.servalabs.cam.action.BATCH_OPERATION_UPDATED";

    public static final String ACTION_BATCH_OPERATION_FINISHED =
            "com.servalabs.cam.action.BATCH_OPERATION_FINISHED";

    public static final String EXTRA_SESSION_SNAPSHOT_JSON =
            "com.servalabs.cam.extra.SESSION_SNAPSHOT_JSON";

    public static final String EXTRA_SESSION_ID =
            "com.servalabs.cam.extra.SESSION_ID";

    public static final String EXTRA_OPERATION_TYPE =
            "com.servalabs.cam.extra.OPERATION_TYPE";

    public static final String EXTRA_SESSION_STATE =
            "com.servalabs.cam.extra.SESSION_STATE";

    private BatchOperationConstants() {
        // Utility class
    }
}
