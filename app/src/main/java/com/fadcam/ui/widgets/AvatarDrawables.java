package com.fadcam.ui.widgets;

import android.content.Context;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves the home avatar drawables for the splash screen.
 *
 * <p>Keep the mapping in sync with {@code HomeFragment} — the splash avatar must
 * use exactly the same idle/sleep artwork (including the user's eye-colour
 * variant) so the hand-off into Home is invisible.</p>
 */
public final class AvatarDrawables {

    public static final int RES_IDLE = 0;
    public static final int RES_BLINK = 1;
    public static final int RES_WAKE = 2;
    public static final int RES_SLEEP = 3;

    public static final int SLEEPING_STATIC = R.drawable.toggle_off;

    private static final int[] DEFAULT_DRAWABLES = {
            R.drawable.toggle_on_idle,
            R.drawable.toggle_on_blink,
            R.drawable.toggle_on_anim,
            R.drawable.toggle_off_anim
    };

    private static final Map<Integer, int[]> EYE_COLOR_DRAWABLES;

    static {
        Map<Integer, int[]> map = new HashMap<>();
        map.put(0xFFE60012, new int[]{
                R.drawable.toggle_on_idle_ruby,
                R.drawable.toggle_on_blink_ruby,
                R.drawable.toggle_on_anim_ruby,
                R.drawable.toggle_off_anim_ruby
        });
        map.put(0xFF00B8D4, new int[]{
                R.drawable.toggle_on_idle_cyan,
                R.drawable.toggle_on_blink_cyan,
                R.drawable.toggle_on_anim_cyan,
                R.drawable.toggle_off_anim_cyan
        });
        map.put(0xFFAA00FF, new int[]{
                R.drawable.toggle_on_idle_violet,
                R.drawable.toggle_on_blink_violet,
                R.drawable.toggle_on_anim_violet,
                R.drawable.toggle_off_anim_violet
        });
        map.put(0xFF2962FF, new int[]{
                R.drawable.toggle_on_idle_cobalt,
                R.drawable.toggle_on_blink_cobalt,
                R.drawable.toggle_on_anim_cobalt,
                R.drawable.toggle_off_anim_cobalt
        });
        map.put(0xFFFFAB00, new int[]{
                R.drawable.toggle_on_idle_amber,
                R.drawable.toggle_on_blink_amber,
                R.drawable.toggle_on_anim_amber,
                R.drawable.toggle_off_anim_amber
        });
        map.put(0xFF00C853, new int[]{
                R.drawable.toggle_on_idle_lime,
                R.drawable.toggle_on_blink_lime,
                R.drawable.toggle_on_anim_lime,
                R.drawable.toggle_off_anim_lime
        });
        map.put(0xFFC51162, new int[]{
                R.drawable.toggle_on_idle_magenta,
                R.drawable.toggle_on_blink_magenta,
                R.drawable.toggle_on_anim_magenta,
                R.drawable.toggle_off_anim_magenta
        });
        EYE_COLOR_DRAWABLES = Collections.unmodifiableMap(map);
    }

    private AvatarDrawables() {
    }

    /**
     * Returns the drawable for the requested state, using the eye-colour variant
     * selected in Appearance settings.
     */
    public static int resolve(Context context, int state) {
        if (context == null) {
            return DEFAULT_DRAWABLES[state];
        }
        int eyeColor = SharedPreferencesManager.getInstance(context)
                .sharedPreferences
                .getInt(Constants.PREF_AVATAR_EYE_COLOR, Constants.DEFAULT_AVATAR_EYE_COLOR);
        int[] variants = EYE_COLOR_DRAWABLES.get(eyeColor);
        return variants != null ? variants[state] : DEFAULT_DRAWABLES[state];
    }
}
