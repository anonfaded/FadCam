package com.fadcam.utils;

import android.content.Context;
import android.content.res.Configuration;

import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Locale used for rendering numbers, dates and times to the user.
 *
 * <p>CLDR resolves plain {@code ar} to the Latin numbering system on Android, so
 * {@code String.format} and {@code SimpleDateFormat} would print {@code 13m 12s} while the
 * surrounding text is Arabic. Display formatting must therefore ask for the locale's native
 * digits explicitly instead of relying on {@link Locale#getDefault()}.
 *
 * <p>This is deliberately derived from the locale's own numbering data: if the platform
 * already resolves native digits (Persian, Pashto, ...), the locale is returned untouched.
 */
public final class DisplayLocale {

    private DisplayLocale() {}

    /** Locale to pass to formatters when producing on-screen text. */
    public static Locale get() {
        return of(Locale.getDefault());
    }

    /**
     * Locale actually in effect for on-screen text.
     *
     * <p>The app carries its own language choice, which AppCompat applies to the
     * configuration. That can differ from {@link Locale#getDefault()}, so anything that has a
     * context must read the resource locale instead of the process default.
     */
    public static Locale get(Context context) {
        if (context != null) {
            try {
                Configuration configuration = context.getResources().getConfiguration();
                if (configuration != null && configuration.getLocales() != null
                        && !configuration.getLocales().isEmpty()) {
                    return of(configuration.getLocales().get(0));
                }
            } catch (Exception ignored) {
                // Fall through to the process default.
            }
        }
        return get();
    }

    /** Same as {@link #get()} for an explicit base locale. */
    public static Locale of(Locale base) {
        if (base == null) {
            base = Locale.getDefault();
        }
        try {
            // Already resolving to native digits (۰-۹, ٠-٩, ...): nothing to do.
            if (DecimalFormatSymbols.getInstance(base).getZeroDigit() != '0') {
                return base;
            }
            // Arabic is the one supported language whose default numbering system is Latin.
            if ("ar".equalsIgnoreCase(base.getLanguage())) {
                return new Locale.Builder()
                        .setLocale(base)
                        .setUnicodeLocaleKeyword("nu", "arab")
                        .build();
            }
        } catch (Exception ignored) {
            // Fall through to the plain locale; formatting still works, just with Latin digits.
        }
        return base;
    }

    /**
     * Converts ASCII digits in already-formatted platform output (for example
     * {@code Formatter.formatFileSize}) to the locale's native digit shapes.
     */
    public static String digits(CharSequence text) {
        return digits(text, Locale.getDefault());
    }

    /** Context-aware variant: uses the language the app is actually showing. */
    public static String digits(CharSequence text, Context context) {
        return digits(text, get(context));
    }

    /**
     * Converts ASCII digits in {@code text} to the native digits of {@code base}.
     * Non-digit characters, including separators and unit names, are left alone.
     */
    public static String digits(CharSequence text, Locale base) {
        if (text == null) {
            return "";
        }
        char zero;
        char decimal;
        try {
            DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(of(base));
            zero = symbols.getZeroDigit();
            decimal = symbols.getDecimalSeparator();
        } catch (Exception ignored) {
            return text.toString();
        }
        if (zero == '0') {
            return text.toString();
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            if (c >= '0' && c <= '9') {
                out.append((char) (zero + (c - '0')));
            } else if (c == '.' && decimal != '.') {
                // Platform formatters hand back ASCII output; the decimal separator is part
                // of the localized value ("12.63" must read "١٢٫٦٣" in Arabic).
                out.append(decimal);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
