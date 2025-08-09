package com.fadcam.ui.picker;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * OptionItem
 * Simple parcelable data holder for bottom sheet picker options.
 * Kept deliberately minimal (id + title + optional subtitle) to preserve legacy semantics.
 */
public class OptionItem implements Parcelable {
    public final String id;
    public final String title;
    public final String subtitle; // nullable

    public OptionItem(String id, String title) { this(id, title, null); }
    public OptionItem(String id, String title, String subtitle) {
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
    }

    protected OptionItem(Parcel in) {
        id = in.readString();
        title = in.readString();
        subtitle = in.readString();
    }

    public static final Creator<OptionItem> CREATOR = new Creator<OptionItem>() {
        @Override public OptionItem createFromParcel(Parcel in) { return new OptionItem(in); }
        @Override public OptionItem[] newArray(int size) { return new OptionItem[size]; }
    };

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(id);
        dest.writeString(title);
        dest.writeString(subtitle);
    }
}
