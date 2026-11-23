package com.fadcam.ui.faditor.util;

/**
 * Single source of truth for the Faditor chrome palette.
 *
 * <p>The editor runs on its own fixed dark theme, so its accents are intentionally
 * hard-coded to the same champagne-gold family used by the Pro screen
 * ({@code res/values/pro_screen.xml}). Keep these values in sync with
 * {@code @color/pro_gold}, {@code @color/pro_check_gold} and
 * {@code @color/pro_card_champagne}.</p>
 */
public final class FaditorPalette {

    private FaditorPalette() {
        // no instances
    }

    /** Primary accent: Pro screen champagne gold. */
    public static final int ACCENT = 0xFFE8C77B;
    /** Brighter highlight for progress/selected states. */
    public static final int ACCENT_BRIGHT = 0xFFFFD15C;
    /** Deeper champagne for secondary detailing. */
    public static final int ACCENT_DEEP = 0xFFC6A66A;

    /** 20% alpha accent for tinted surfaces. */
    public static final int ACCENT_SOFT = 0x33E8C77B;
    /** 25% alpha accent for recover/trim overlays. */
    public static final int ACCENT_FAINT = 0x40E8C77B;
    /** 40% alpha accent for drag ghosts. */
    public static final int ACCENT_GHOST = 0x66E8C77B;

    /** Dark warm fill behind a selected timeline segment. */
    public static final int SELECTED_FILL = 0xFF2A2317;
    /** Dark warm tint layered over a selected segment. */
    public static final int SELECTED_TINT = 0x402A2317;
    /** Dark warm fill for an active timeline region. */
    public static final int ACTIVE_REGION_FILL = 0xFF2B2418;

    /** Trim-handle notch (translucent deep gold). */
    public static final int HANDLE_NOTCH = 0xBB8A7440;
    /** Trim-handle notch (stronger, used by the compact timeline). */
    public static final int HANDLE_NOTCH_SOLID = 0xCC8A7440;

    /** Neutral secondary text used across the editor sheets. */
    public static final int TEXT_SECONDARY = 0xFFCCCCCC;
    /** Neutral muted text/icon used for inactive controls. */
    public static final int TEXT_MUTED = 0xFF888888;
}
