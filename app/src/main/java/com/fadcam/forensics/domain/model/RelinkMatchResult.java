package com.fadcam.forensics.domain.model;

public class RelinkMatchResult {

    public enum Status {
        EXACT,
        PROBABLE,
        NEW
    }

    public final Status status;
    public final String mediaUid;
    public final float score;

    private RelinkMatchResult(Status status, String mediaUid, float score) {
        this.status = status;
        this.mediaUid = mediaUid;
        this.score = score;
    }

    public static RelinkMatchResult exact(String mediaUid) {
        return new RelinkMatchResult(Status.EXACT, mediaUid, 1f);
    }

    public static RelinkMatchResult probable(String mediaUid, float score) {
        return new RelinkMatchResult(Status.PROBABLE, mediaUid, score);
    }

    public static RelinkMatchResult fresh() {
        return new RelinkMatchResult(Status.NEW, null, 0f);
    }
}
