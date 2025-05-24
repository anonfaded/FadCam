package com.fadcam;

import java.io.Serializable;

public enum RecordingState implements Serializable {
    STARTING,
    IN_PROGRESS,
    PAUSED,
    NONE
}
