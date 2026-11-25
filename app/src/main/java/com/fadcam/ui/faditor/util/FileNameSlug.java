package com.fadcam.ui.faditor.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Turns a user-typed project name into the filename it will be exported as.
 *
 * <p>The rules are the ones every desktop editor applies, so a name typed here is
 * predictable everywhere:</p>
 * <ul>
 *   <li>trim leading and trailing whitespace;</li>
 *   <li>collapse any run of whitespace to a single hyphen;</li>
 *   <li>replace characters that are illegal on common filesystems
 *       ({@code / \ : * ? " < > |}, control characters) with a hyphen;</li>
 *   <li>collapse repeated hyphens and trim hyphens from both ends;</li>
 *   <li>refuse a name that is only separators or dots;</li>
 *   <li>cap the length so the extension and any deduplication suffix still fit.</li>
 * </ul>
 *
 * <p>The sheet shows the result live so the exported file never surprises anyone.</p>
 */
public final class FileNameSlug {

    /** Leaves room for a deduplication suffix and the ".mp4" extension. */
    private static final int MAX_LENGTH = 64;

    private FileNameSlug() {
    }

    /**
     * @param raw whatever the user typed
     * @return a filesystem-safe name without an extension; never null, possibly empty
     *         when nothing usable is left
     */
    @NonNull
    public static String slugify(@Nullable String raw) {
        if (raw == null) return "";
        String cleaned = raw.trim();
        if (cleaned.isEmpty()) return "";

        // Whitespace runs become a single hyphen.
        cleaned = cleaned.replaceAll("\\s+", "-");
        // Illegal-on-some-filesystem characters, plus controls.
        cleaned = cleaned.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "-");
        // Collapse repeats and trim the ends.
        cleaned = cleaned.replaceAll("-{2,}", "-");
        while (cleaned.startsWith("-") || cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        while (cleaned.endsWith("-") || cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.isEmpty()) return "";

        if (cleaned.length() > MAX_LENGTH) {
            cleaned = cleaned.substring(0, MAX_LENGTH);
            while (cleaned.endsWith("-") || cleaned.endsWith(".")) {
                cleaned = cleaned.substring(0, cleaned.length() - 1);
            }
        }
        return cleaned;
    }

}
