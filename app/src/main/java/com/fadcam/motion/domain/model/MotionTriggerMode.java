package com.fadcam.motion.domain.model;

public enum MotionTriggerMode {
    ANY_MOTION("any_motion"),
    PERSON_CONFIRMED("person_confirmed");

    private final String value;

    MotionTriggerMode(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static MotionTriggerMode fromValue(String raw) {
        if (ANY_MOTION.value.equals(raw)) {
            return ANY_MOTION;
        }
        return PERSON_CONFIRMED;
    }
}
