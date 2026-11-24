package com.fadcam.util;

/**
 * Locates the "number one" marker inside the Pro and onboarding headlines so the accent can be
 * painted in every language.
 *
 * <p>Each language writes its rank its own way: {@code #1} in English, {@code n°1} in French,
 * {@code №1} in Russian, {@code n.º 1} in Spanish, {@code 排名第一} in Chinese, {@code أفضل} in
 * Arabic. The headline keeps the native form and this class still finds it, so the gold accent
 * never forces translators back to the English token.</p>
 */
public final class RankMarker {

    /**
     * Rank expressions, each including its native number sign, so the accent covers the whole
     * badge the way {@code #1} does in English. Longer, more specific forms come before the
     * shorter ones they contain.
     */
    private static final String[] MARKERS = {
            // Hash and numero signs
            "#1", "#\u0661",
            "\u21161", "\u2116 1",
            // Romance
            "n\u00b01", "n\u00b0 1",
            "n.\u00ba 1", "n.\u00ba1",
            "n. 1", "n.1",
            "numero 1",
            // Germanic and Nordic
            "Nr. 1", "Nr 1", "nr. 1", "nr 1",
            // Indonesian and the romanised Pashto copy
            "nomor 1",
            "no. 1", "no 1",
            // Greek
            "\u03bd\u03bf\u03cd\u03bc\u03b5\u03c1\u03bf 1",
            // Chinese writes the rank as characters, with no numeral or sign
            "\u6392\u540d\u7b2c\u4e00",
            // Turkish puts the number first
            "1 numaral\u0131",
            // Arabic: the number sign plus an Arabic-Indic digit, or the superlative
            "\u0631\u0642\u0645 \u0661", "\u0631\u0642\u0645 1",
            "\u0623\u0641\u0636\u0644",
    };

    private RankMarker() {
    }

    /**
     * @return {@code {start, end}} of the first marker in {@code text}, or {@code null}.
     */
    public static int[] locate(String text) {
        if (text == null) return null;
        int best = -1;
        int bestLength = 0;
        for (String marker : MARKERS) {
            int at = text.indexOf(marker);
            if (at >= 0 && (best < 0 || at < best)) {
                best = at;
                bestLength = marker.length();
            }
        }
        return best < 0 ? null : new int[]{best, best + bestLength};
    }
}
