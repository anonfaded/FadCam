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
    public final Integer colorInt; // nullable for theme color circle
    public final Integer iconResId; // nullable for app icon grid

    public OptionItem(String id, String title) { this(id, title, null, null, null); }
    public OptionItem(String id, String title, String subtitle) { this(id, title, subtitle, null, null); }
    public OptionItem(String id, String title, String subtitle, Integer colorInt) { this(id, title, subtitle, colorInt, null); }
    public OptionItem(String id, String title, String subtitle, Integer colorInt, Integer iconResId) {
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.colorInt = colorInt;
        this.iconResId = iconResId;
    }

    public OptionItem(String id, String title, Integer iconResId) {
        this.id = id; this.title = title; this.subtitle = null; this.colorInt = null; this.iconResId = iconResId;
    }

    protected OptionItem(Parcel in) {
        id = in.readString();
        title = in.readString();
    subtitle = in.readString();
    if(in.readInt()==1){ colorInt = in.readInt(); } else { colorInt = null; }
    if(in.readInt()==1){ iconResId = in.readInt(); } else { iconResId = null; }
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
    if(colorInt!=null){ dest.writeInt(1); dest.writeInt(colorInt); } else { dest.writeInt(0); }
    if(iconResId!=null){ dest.writeInt(1); dest.writeInt(iconResId); } else { dest.writeInt(0); }
    }
}
