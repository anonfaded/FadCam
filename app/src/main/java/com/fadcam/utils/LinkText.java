package com.fadcam.utils;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns {@code [label](url)} inside plain text into real, tappable links.
 *
 * <p>Why not HTML: {@code Html.fromHtml} gives no control over the link's colour or press
 * state, and it quietly mangles the surrounding text (entities, stray angle brackets). This
 * parser understands exactly one construct — a markdown-style link — and leaves every other
 * character as-is, so prose such as "a plain GET request" survives untouched.
 */
public final class LinkText {

    /** {@code [label](url)} — label without brackets, url without spaces or parentheses. */
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\(([^)\\s]+)\\)");

    private LinkText() {
    }

    /** True when {@code text} contains at least one link, so callers can skip the work. */
    public static boolean hasLink(@Nullable String text) {
        return text != null && LINK.matcher(text).find();
    }

    /**
     * Builds the styled text: the {@code [label](url)} syntax collapses to just the label, and
     * each label becomes a span that opens {@code url} when tapped.
     *
     * @param linkColor colour for the label (underlined, so it reads as a link without relying
     *                  on a colour that may clash with the surrounding theme)
     */
    @NonNull
    public static CharSequence parse(@NonNull String text, int linkColor) {
        // Collect (start, end, url) as the collapsed string is built, then apply the spans.
        // Recording the url directly avoids a second regex pass to recover it.
        java.util.List<Object[]> spans = new java.util.ArrayList<>();
        Matcher m = LINK.matcher(text);
        StringBuilder visible = new StringBuilder(text.length());
        int cursorInSource = 0;
        int cursorInVisible = 0;
        while (m.find()) {
            String before = text.substring(cursorInSource, m.start());
            visible.append(before);
            cursorInVisible += before.length();
            int labelStart = cursorInVisible;
            visible.append(m.group(1));
            cursorInVisible += m.group(1).length();
            cursorInSource = m.end();
            spans.add(new Object[]{labelStart, cursorInVisible, m.group(2)});
        }
        visible.append(text, cursorInSource, text.length());

        SpannableString out = new SpannableString(visible.toString());
        for (Object[] span : spans) {
            out.setSpan(new ThemedLink((String) span[2], linkColor),
                    (Integer) span[0], (Integer) span[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    /** Applies {@link #parse} to a TextView and makes the links tappable. */
    public static void apply(@NonNull TextView view, @Nullable String text, int linkColor) {
        if (text == null) {
            view.setText("");
            return;
        }
        if (!hasLink(text)) {
            view.setText(text);
            return;
        }
        view.setText(parse(text, linkColor));
        view.setMovementMethod(LinkMovementMethod.getInstance());
        view.setLinksClickable(true);
        // No row-wide highlight: a link shows its own pressed colour.
        view.setHighlightColor(android.graphics.Color.TRANSPARENT);
    }

    /**
     * A link drawn in the caller's colour with an underline, rather than Android's default
     * blue-and-underline, so it sits correctly in a themed sheet.
     */
    private static final class ThemedLink extends ClickableSpan {
        private final String url;
        private final int color;

        ThemedLink(String url, int color) {
            this.url = url;
            this.color = color;
        }

        @Override
        public void onClick(@NonNull View widget) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                widget.getContext().startActivity(intent);
            } catch (ActivityNotFoundException ignored) {
                // No browser installed — doing nothing beats crashing the sheet.
            }
        }

        @Override
        public void updateDrawState(@NonNull TextPaint ds) {
            ds.setColor(color);
            ds.setUnderlineText(true);
        }
    }
}
