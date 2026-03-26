package com.fadcam.ui.utils;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.animation.DecelerateInterpolator;
import androidx.appcompat.widget.AppCompatTextView;

/**
 * A TextView that animates text changes with an intelligent slot-machine effect.
 *
 * <p><b>Differential animation:</b> Only the portion of the text that actually changed
 * animates.  The unchanged prefix and suffix are drawn statically in place, so e.g.
 * "00:38" → "00:39" only animates the last digit "8" → "9", not the whole label.
 *
 * <p><b>Direction:</b>
 * <ul>
 *   <li>{@link #animateSlot} — new value slides UP from below (increasing values).
 *   <li>{@link #animateSlotDown} — new value slides DOWN from above (decreasing values).
 * </ul>
 *
 * <p>Works with plain {@link String} and HTML {@link android.text.Spanned} text.
 */
public class AnimatedTextView extends AppCompatTextView {

    private static final String TAG = "AnimatedTextView";
    private static final long DEBOUNCE_MS = 100;

    // Slot machine animation state
    private boolean isSlotAnimating = false;
    private float slotProgress = 1f;
    private boolean slotUpward = true; // true = new slides UP from below; false = new slides DOWN from above

    // Layouts for the CHANGED column only (prefix/suffix drawn statically)
    private StaticLayout slotOldLayout;
    private StaticLayout slotNewLayout;
    private CharSequence slotPendingFinalText;
    private ValueAnimator slotAnimator;
    private long lastUpdateTime = 0;

    // Differential animation: column bounds (full-string layouts used for all regions)
    private float slotPrefixWidth;     // px offset from view-left to column start
    private float slotColumnWidth;     // px width of animated column = max(oldChangedW, newChangedW)
    private float slotNewChangedWidth; // px width of new changed text (marks where suffix starts)


    public AnimatedTextView(Context context) {
        super(context);
    }

    public AnimatedTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AnimatedTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    // ========== PRIMARY ANIMATION API ==========

    /**
     * Animates a text change with a slot-machine effect: only the changed portion slides
     * UP (new value coming from below). The unchanged prefix / suffix stay still.
     * E.g. "00:38" → "00:39" — only "8" → "9" animates; "00:3" and "" stay static.
     *
     * @param newText    The new text to display (String or Html.fromHtml() Spanned)
     * @param durationMs Animation duration in milliseconds
     */
    public void animateSlot(CharSequence newText, long durationMs) {
        animateSlotInternal(newText, durationMs, true);
    }

    /**
     * Like {@link #animateSlot} but the changed column slides DOWN (decreasing values,
     * countdown timers, available-storage decreasing, etc.).
     *
     * @param newText    The new text to display
     * @param durationMs Animation duration in milliseconds
     */
    public void animateSlotDown(CharSequence newText, long durationMs) {
        animateSlotInternal(newText, durationMs, false);
    }

    private void animateSlotInternal(CharSequence newText, long durationMs, boolean upward) {
        // Debounce rapid updates — if a new value arrives too quickly just set it.
        long now = System.currentTimeMillis();
        if (now - lastUpdateTime < DEBOUNCE_MS) {
            setText(newText);
            return;
        }
        lastUpdateTime = now;

        CharSequence oldText = getText();
        if (oldText == null) oldText = "";
        if (newText == null) newText = "";

        // Skip animation if plain-text content is unchanged.
        String oldStr = oldText.toString();
        String newStr = newText.toString();
        if (oldStr.equals(newStr)) {
            return;
        }

        // View not yet laid out — fall back to direct setText.
        if (getWidth() == 0 || getHeight() == 0) {
            setText(newText);
            return;
        }

        cancelSlotAnimation();

        TextPaint paint = getPaint();
        Layout.Alignment alignment = resolveLayoutAlignment();
        boolean includePad = getIncludeFontPadding();

        // ---- Differential diff: find unchanged prefix and suffix ----------------
        int prefixLen = commonPrefixLength(oldStr, newStr);
        int suffixLen = commonSuffixLength(oldStr, newStr, prefixLen);

        String prefix     = newStr.substring(0, prefixLen);
        String oldChanged = oldStr.substring(prefixLen, oldStr.length() - suffixLen);
        String newChanged = newStr.substring(prefixLen, newStr.length() - suffixLen);
        String suffix     = newStr.substring(newStr.length() - suffixLen);

        // Measure prefix/suffix widths using the view's TextPaint (no allocation).
        float prefixW       = prefixLen  > 0 ? paint.measureText(prefix)  : 0f;
        float oldChangedW   = oldChanged.isEmpty() ? 0f : paint.measureText(oldChanged);
        float newChangedW   = newChanged.isEmpty() ? 0f : paint.measureText(newChanged);
        // Animated column is wide enough for either the old or the new changed text.
        float columnW = Math.max(oldChangedW, newChangedW);

        int usableW = getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight();
        if (usableW < 1) usableW = 1;

        // Always build FULL-STRING layouts so every region (prefix, column, suffix) is
        // rendered by the same StaticLayout engine — identical baselines everywhere.
        // Using partial-string layouts for the column caused a per-pixel Y mismatch with
        // canvas.drawText() for the prefix/suffix due to StaticLayout includeFontPadding,
        // which manifested as the whole row jiggling during animation.
        slotOldLayout = StaticLayout.Builder
                .obtain(oldStr, 0, oldStr.length(), paint, usableW)
                .setAlignment(alignment)
                .setIncludePad(includePad)
                .build();
        slotNewLayout = StaticLayout.Builder
                .obtain(newStr, 0, newStr.length(), paint, usableW)
                .setAlignment(alignment)
                .setIncludePad(includePad)
                .build();

        boolean hasDiff = prefixLen > 0 || suffixLen > 0;
        if (hasDiff) {
            // Partial animation: only the column between prefix and suffix slides.
            slotPrefixWidth     = prefixW;
            slotColumnWidth     = columnW;       // max(oldChangedW, newChangedW)
            slotNewChangedWidth = newChangedW;   // suffix starts here inside new layout
        } else {
            // Full-string animation — no stable prefix or suffix.
            slotPrefixWidth     = 0f;
            slotColumnWidth     = usableW;
            slotNewChangedWidth = usableW;       // effectively no suffix
        }

        slotPendingFinalText = newText;
        slotProgress = 0f;
        slotUpward = upward;
        isSlotAnimating = true;

        slotAnimator = ValueAnimator.ofFloat(0f, 1f);
        slotAnimator.setDuration(durationMs);
        slotAnimator.setInterpolator(new DecelerateInterpolator(1.5f));
        slotAnimator.addUpdateListener(anim -> {
            slotProgress = (float) anim.getAnimatedValue();
            invalidate();
        });
        slotAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                finishSlotAnimation();
            }
        });
        slotAnimator.start();
    }

    // ---- Diff helpers -----------------------------------------------------------

    /** Returns the number of identical characters at the start of both strings. */
    private static int commonPrefixLength(String a, String b) {
        int minLen = Math.min(a.length(), b.length());
        int i = 0;
        while (i < minLen && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    /**
     * Returns the number of identical characters at the END of both strings,
     * but not overlapping the already-committed prefix of length {@code prefixLen}.
     */
    private static int commonSuffixLength(String a, String b, int prefixLen) {
        int maxSuffix = Math.min(a.length() - prefixLen, b.length() - prefixLen);
        int i = 0;
        while (i < maxSuffix
                && a.charAt(a.length() - 1 - i) == b.charAt(b.length() - 1 - i)) {
            i++;
        }
        return i;
    }

    // ---- Animation lifecycle ----------------------------------------------------

    private void finishSlotAnimation() {
        isSlotAnimating = false;
        slotProgress = 1f;
        slotOldLayout = null;
        slotNewLayout = null;
        slotAnimator = null;
        if (slotPendingFinalText != null) {
            setText(slotPendingFinalText);
            slotPendingFinalText = null;
        }
        invalidate();
    }

    private void cancelSlotAnimation() {
        if (slotAnimator != null) {
            slotAnimator.removeAllListeners();
            slotAnimator.cancel();
            slotAnimator = null;
        }
        isSlotAnimating = false;
        slotOldLayout = null;
        slotNewLayout = null;
        slotPendingFinalText = null;
    }

    // ---- Drawing ----------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        if (!isSlotAnimating || slotOldLayout == null || slotNewLayout == null) {
            super.onDraw(canvas);
            return;
        }

        int left   = getCompoundPaddingLeft();
        int top    = getExtendedPaddingTop();
        int right  = getWidth() - getCompoundPaddingRight();
        int bottom = getHeight() - getExtendedPaddingBottom();

        // Slide distance = height of the taller layout (keeps text fully visible during slide).
        int slideDistance = Math.max(slotOldLayout.getHeight(), slotNewLayout.getHeight());
        if (slideDistance == 0) slideDistance = bottom - top;

        // Compute Y positions for the two animating layers.
        float oldY, newY;
        if (slotUpward) {
            oldY = top - slideDistance * slotProgress;
            newY = top + slideDistance * (1f - slotProgress);
        } else {
            oldY = top + slideDistance * slotProgress;
            newY = top - slideDistance * (1f - slotProgress);
        }

        // Column bounds derived from prefix/changed-text widths.
        float colLeft     = left + slotPrefixWidth;
        float colRight    = colLeft + slotColumnWidth;
        // suffixStart is where newStr's suffix begins inside the full layout.
        float suffixStart = colLeft + slotNewChangedWidth;

        canvas.save();
        canvas.clipRect(left, top, right, bottom); // never bleed outside view bounds

        // 1. Static PREFIX — clip the new full-string layout to the prefix region.
        //    Same StaticLayout as the animated column → identical baseline, zero jitter.
        if (slotPrefixWidth > 0f) {
            canvas.save();
            canvas.clipRect(left, top, colLeft, bottom);
            canvas.translate(left, top);
            slotNewLayout.draw(canvas);
            canvas.restore();
        }

        // 2. Animated COLUMN — both full-string layouts clipped to the column bounds.
        canvas.save();
        canvas.clipRect(colLeft, top, colRight, bottom);

        canvas.save();
        canvas.translate(left, oldY);
        slotOldLayout.draw(canvas);
        canvas.restore();

        canvas.save();
        canvas.translate(left, newY);
        slotNewLayout.draw(canvas);
        canvas.restore();

        canvas.restore(); // end column clip

        // 3. Static SUFFIX — clip the new full-string layout from suffixStart onward.
        //    suffixStart = prefixWidth + newChangedWidth, matching the layout's own offsets.
        if (suffixStart < right) {
            canvas.save();
            canvas.clipRect(suffixStart, top, right, bottom);
            canvas.translate(left, top);
            slotNewLayout.draw(canvas);
            canvas.restore();
        }

        canvas.restore();
    }

    /** Maps view gravity to {@link Layout.Alignment} for StaticLayout. */
    private Layout.Alignment resolveLayoutAlignment() {
        int gravity = getGravity() & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK;
        if (gravity == Gravity.CENTER_HORIZONTAL) return Layout.Alignment.ALIGN_CENTER;
        if (gravity == Gravity.END)               return Layout.Alignment.ALIGN_OPPOSITE;
        return Layout.Alignment.ALIGN_NORMAL;
    }

    /** Cancel any running animation and reset state. */
    public void cancelAnimation() {
        cancelSlotAnimation();
        setScaleX(1f);
        setScaleY(1f);
        setAlpha(1f);
    }
}

