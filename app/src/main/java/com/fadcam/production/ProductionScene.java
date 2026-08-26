package com.fadcam.production;

/** Presets for the mobile TV-production canvas. */
public enum ProductionScene {
    CAMERA("Camera", "Full camera program"),
    VIDEO("Video", "Full loaded video"),
    DUET_PIP("Duet PiP", "Video full with commentator window"),
    DUET_SPLIT("Duet Split", "Video and commentator side-by-side"),
    COMMENTARY("Commentary", "Commentator full with video inset"),
    INTERVIEW("Interview", "Camera program with production lower-third");

    private final String title;
    private final String description;

    ProductionScene(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public String getTitle() { return title; }
    public String getDescription() { return description; }
}
