package com.fadcam.model;

import java.util.Objects;

public class TrashItem {
    private String originalUriString;
    private String originalDisplayName;
    private String trashFileName; // The actual name of the file in the trash folder
    private long dateTrashed; // Timestamp in milliseconds
    private boolean isFromSaf; // Was the original item from SAF storage?

    // Default constructor for deserialization (e.g., by Gson)
    public TrashItem() {
    }

    public TrashItem(String originalUriString, String originalDisplayName, String trashFileName, long dateTrashed, boolean isFromSaf) {
        this.originalUriString = originalUriString;
        this.originalDisplayName = originalDisplayName;
        this.trashFileName = trashFileName;
        this.dateTrashed = dateTrashed;
        this.isFromSaf = isFromSaf;
    }

    // Getters
    public String getOriginalUriString() {
        return originalUriString;
    }

    public String getOriginalDisplayName() {
        return originalDisplayName;
    }

    public String getTrashFileName() {
        return trashFileName;
    }

    public long getDateTrashed() {
        return dateTrashed;
    }

    public boolean isFromSaf() {
        return isFromSaf;
    }

    // Setters (might be needed for Gson or other reflection-based libraries, though often not if constructor is used)
    public void setOriginalUriString(String originalUriString) {
        this.originalUriString = originalUriString;
    }

    public void setOriginalDisplayName(String originalDisplayName) {
        this.originalDisplayName = originalDisplayName;
    }

    public void setTrashFileName(String trashFileName) {
        this.trashFileName = trashFileName;
    }

    public void setDateTrashed(long dateTrashed) {
        this.dateTrashed = dateTrashed;
    }

    public void setFromSaf(boolean fromSaf) {
        isFromSaf = fromSaf;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TrashItem trashItem = (TrashItem) o;
        // Primarily, two trash items are the same if their file in the trash is the same.
        // originalUriString might be complex for SAF content URIs that can change or become invalid.
        return Objects.equals(trashFileName, trashItem.trashFileName) &&
               dateTrashed == trashItem.dateTrashed; // Date trashed can help differentiate if names somehow collide with different deletion events
    }

    @Override
    public int hashCode() {
        return Objects.hash(trashFileName, dateTrashed);
    }

    @Override
    public String toString() {
        return "TrashItem{" +
                "originalUriString='" + originalUriString + '\'' +
                ", originalDisplayName='" + originalDisplayName + '\'' +
                ", trashFileName='" + trashFileName + '\'' +
                ", dateTrashed=" + dateTrashed +
                ", isFromSaf=" + isFromSaf +
                '}';
    }
} 