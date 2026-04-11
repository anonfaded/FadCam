package com.fadcam.service;

/**
 * Constants for batch media operations (merge, export, etc.)
 */
public class BatchOperationConstants {

    public static final String ACTION_BATCH_OPERATION_UPDATED =
            "com.fadcam.action.BATCH_OPERATION_UPDATED";

    public static final String ACTION_BATCH_OPERATION_FINISHED =
            "com.fadcam.action.BATCH_OPERATION_FINISHED";

    public static final String EXTRA_SESSION_SNAPSHOT_JSON =
            "com.fadcam.extra.SESSION_SNAPSHOT_JSON";

    public static final String EXTRA_SESSION_ID =
            "com.fadcam.extra.SESSION_ID";

    public static final String EXTRA_OPERATION_TYPE =
            "com.fadcam.extra.OPERATION_TYPE";

    public static final String EXTRA_SESSION_STATE =
            "com.fadcam.extra.SESSION_STATE";

    private BatchOperationConstants() {
        // Utility class
    }
}
