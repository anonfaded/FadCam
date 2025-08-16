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
    public final String iconLigature; // nullable, Material Symbols text
    public final Integer trailingIconResId; // optional trailing icon (e.g., external-link)
    public final Boolean hasSwitch; // nullable - if true, shows a switch instead of subtitle
    public final Boolean switchState; // nullable - current state of the switch (only used if hasSwitch is true)
    public final String badgeText; // nullable - optional small pill text under title
    public final Integer badgeBgResId; // nullable - drawable background for badge (rounded)
    public final Boolean disabled; // nullable - if true, row appears grayed-out and non-selecting

    public OptionItem(String id, String title) { this(id, title, null, null, null, null, null, null, null); }
    public OptionItem(String id, String title, String subtitle) { this(id, title, subtitle, null, null, null, null, null, null); }
    public OptionItem(String id, String title, String subtitle, Integer colorInt) { this(id, title, subtitle, colorInt, null, null, null, null, null); }
    public OptionItem(String id, String title, String subtitle, Integer colorInt, Integer iconResId) {
        this(id, title, subtitle, colorInt, iconResId, null, null, null, null);
    }

    public OptionItem(String id, String title, String subtitle, Integer colorInt, Integer iconResId, Integer trailingIconResId) {
        this(id, title, subtitle, colorInt, iconResId, trailingIconResId, null, null, null);
    }

    // Constructor for switch items
    public OptionItem(String id, String title, Integer iconResId, boolean switchState) {
        this(id, title, null, null, iconResId, null, true, switchState, null);
    }

    // Main constructor (legacy, no badge/disabled)
    public OptionItem(String id, String title, String subtitle, Integer colorInt, Integer iconResId, Integer trailingIconResId, Boolean hasSwitch, Boolean switchState, String iconLigature) {
        this(id, title, subtitle, colorInt, iconResId, trailingIconResId, hasSwitch, switchState, iconLigature, null, null, null);
    }

    // New extended constructor
    public OptionItem(String id, String title, String subtitle, Integer colorInt, Integer iconResId, Integer trailingIconResId, Boolean hasSwitch, Boolean switchState, String iconLigature, String badgeText, Integer badgeBgResId, Boolean disabled) {
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.colorInt = colorInt;
        this.iconResId = iconResId;
        this.iconLigature = iconLigature;
        this.trailingIconResId = trailingIconResId;
        this.hasSwitch = hasSwitch;
        this.switchState = switchState;
        this.badgeText = badgeText;
        this.badgeBgResId = badgeBgResId;
        this.disabled = disabled;
    }

    public OptionItem(String id, String title, Integer iconResId) {
        this.id = id; this.title = title; this.subtitle = null; this.colorInt = null; this.iconResId = iconResId; this.iconLigature = null; this.trailingIconResId = null; this.hasSwitch = null; this.switchState = null; this.badgeText = null; this.badgeBgResId = null; this.disabled = null;
    }

    // Factory for ligature-based icon
    public static OptionItem withLigature(String id, String title, String iconLigature) {
        return new OptionItem(id, title, null, null, null, null, null, null, iconLigature);
    }

    // Factory for ligature + badge + disabled state (helper subtitle optional)
    public static OptionItem withLigatureBadge(String id, String title, String iconLigature, String badgeText, Integer badgeBgResId, boolean disabled, String subtitle) {
        return new OptionItem(id, title, subtitle, null, null, null, null, null, iconLigature, badgeText, badgeBgResId, disabled);
    }

    protected OptionItem(Parcel in) {
        id = in.readString();
        title = in.readString();
    subtitle = in.readString();
    if(in.readInt()==1){ colorInt = in.readInt(); } else { colorInt = null; }
    if(in.readInt()==1){ iconResId = in.readInt(); } else { iconResId = null; }
    iconLigature = in.readString();
    if(in.readInt()==1){ trailingIconResId = in.readInt(); } else { trailingIconResId = null; }
    if(in.readInt()==1){ hasSwitch = in.readInt()==1; } else { hasSwitch = null; }
    if(in.readInt()==1){ switchState = in.readInt()==1; } else { switchState = null; }
    badgeText = in.readString();
    if(in.readInt()==1){ badgeBgResId = in.readInt(); } else { badgeBgResId = null; }
    if(in.readInt()==1){ disabled = in.readInt()==1; } else { disabled = null; }
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
    dest.writeString(iconLigature);
    if(trailingIconResId!=null){ dest.writeInt(1); dest.writeInt(trailingIconResId); } else { dest.writeInt(0); }
    if(hasSwitch!=null){ dest.writeInt(1); dest.writeInt(hasSwitch ? 1 : 0); } else { dest.writeInt(0); }
    if(switchState!=null){ dest.writeInt(1); dest.writeInt(switchState ? 1 : 0); } else { dest.writeInt(0); }
    dest.writeString(badgeText);
    if(badgeBgResId!=null){ dest.writeInt(1); dest.writeInt(badgeBgResId); } else { dest.writeInt(0); }
    if(disabled!=null){ dest.writeInt(1); dest.writeInt(disabled ? 1 : 0); } else { dest.writeInt(0); }
    }
}
