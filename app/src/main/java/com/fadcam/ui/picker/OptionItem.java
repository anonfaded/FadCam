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
    public final Integer iconResId; // nullable for app icon grid or leading icon
    public final Integer trailingIconResId; // optional trailing icon (e.g., external-link)
    public final Boolean hasSwitch; // nullable - if true, shows a switch instead of subtitle
    public final Boolean switchState; // nullable - current state of the switch (only used if hasSwitch is true)

    public OptionItem(String id, String title) { this(id, title, null, null, null, null, null, null); }
    public OptionItem(String id, String title, String subtitle) { this(id, title, subtitle, null, null, null, null, null); }
    public OptionItem(String id, String title, String subtitle, Integer colorInt) { this(id, title, subtitle, colorInt, null, null, null, null); }
    public OptionItem(String id, String title, String subtitle, Integer colorInt, Integer iconResId) {
        this(id, title, subtitle, colorInt, iconResId, null, null, null);
    }

    public OptionItem(String id, String title, String subtitle, Integer colorInt, Integer iconResId, Integer trailingIconResId) {
        this(id, title, subtitle, colorInt, iconResId, trailingIconResId, null, null);
    }

    // Constructor for switch items
    public OptionItem(String id, String title, Integer iconResId, boolean switchState) {
        this(id, title, null, null, iconResId, null, true, switchState);
    }

    // Main constructor
    public OptionItem(String id, String title, String subtitle, Integer colorInt, Integer iconResId, Integer trailingIconResId, Boolean hasSwitch, Boolean switchState) {
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.colorInt = colorInt;
        this.iconResId = iconResId;
        this.trailingIconResId = trailingIconResId;
        this.hasSwitch = hasSwitch;
        this.switchState = switchState;
    }

    public OptionItem(String id, String title, Integer iconResId) {
        this.id = id; this.title = title; this.subtitle = null; this.colorInt = null; this.iconResId = iconResId; this.trailingIconResId = null; this.hasSwitch = null; this.switchState = null;
    }

    protected OptionItem(Parcel in) {
        id = in.readString();
        title = in.readString();
    subtitle = in.readString();
    if(in.readInt()==1){ colorInt = in.readInt(); } else { colorInt = null; }
    if(in.readInt()==1){ iconResId = in.readInt(); } else { iconResId = null; }
    if(in.readInt()==1){ trailingIconResId = in.readInt(); } else { trailingIconResId = null; }
    if(in.readInt()==1){ hasSwitch = in.readInt()==1; } else { hasSwitch = null; }
    if(in.readInt()==1){ switchState = in.readInt()==1; } else { switchState = null; }
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
    if(trailingIconResId!=null){ dest.writeInt(1); dest.writeInt(trailingIconResId); } else { dest.writeInt(0); }
    if(hasSwitch!=null){ dest.writeInt(1); dest.writeInt(hasSwitch ? 1 : 0); } else { dest.writeInt(0); }
    if(switchState!=null){ dest.writeInt(1); dest.writeInt(switchState ? 1 : 0); } else { dest.writeInt(0); }
    }
}
