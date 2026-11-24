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
    private boolean suppressLayoutRequest = false;  // Suppress requestLayout during animation completion

    // Differential animation: column bounds (full-string layouts used for all regions)
    private float slotPrefixWidth;     // px offset from view-left to column start
    private float slotColumnWidth;     // px width of animated column = max(oldChangedW, newChangedW)
    private float slotOldChangedWidth; // px width of old changed text (stable suffix anchor)
    private float slotNewChangedWidth; // px width of new changed text (marks where suffix starts)
    // Visual (post-bidi / post-shaping) bounds of the changed column, in layout
    // coordinates. measureText(prefix) is a LOGICAL width: Arabic joins change glyph
    // advances and bidi reorders the runs, so the measured prefix does not match where
    // the changed glyphs actually land and the clip window drifts frame to frame.
    private float slotNewChangedLeft;
    private float slotNewChangedRight;
    private float slotOldChangedLeft;
    private float slotOldChangedRight;
    private float slotDrawBaseX;       // draw origin for the animated layouts
    private int slotLayoutWidth;       // width of the built layouts (may exceed the view)
    // Vertical window the slide is confined to: the real text line, not the view box.
    // The view box includes font padding, so confining to it let the incoming/outgoing
    // glyphs paint above the line and overlap the row above ("rows jumping").
    private float slotWindowTop;
    private float slotWindowBottom;
    private float slotBaselineY;
    private boolean slotHasDifferentialRegions;


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
        animateSlotInternal(newText, durationMs, false, false);
    }

    /**
     * Like {@link #animateSlot} but always animates the full string as a single unit
     * (no differential prefix/suffix detection).  Useful when the strings share a
     * common suffix in their character sequence but differ enough in rendered width
     * that partial animation would cause visible overlap, e.g. "Front Camera" →
     * "Rear Camera".
     *
     * @param newText    The new text to display
     * @param durationMs Animation duration in milliseconds
     */
    public void animateSlotFull(CharSequence newText, long durationMs) {
        animateSlotInternal(newText, durationMs, true, true);
    }

    /**
     * Like {@link #animateSlotFull} but slides DOWN.
     */
    public void animateSlotFullDown(CharSequence newText, long durationMs) {
        animateSlotInternal(newText, durationMs, false, true);
    }

    /**
     * Simple alpha cross-fade without any sliding. Useful for views where the
     * slot-machine translation distance would be unusually large (e.g. small
     * icon views with Material-icon ligature text wider than the view itself).
     *
     * @param newText    The new text to display
     * @param durationMs Half-cycle duration (fade-out takes durationMs, fade-in takes durationMs)
     */
    public void animateCrossfade(CharSequence newText, long durationMs) {
        if (newText == null) newText = "";
        CharSequence current = getText();
        if (current != null && current.toString().equals(newText.toString())) return;
        cancelAnimation();
        final CharSequence finalText = newText;
        animate().cancel();
        animate()
                .alpha(0f)
                .setDuration(durationMs / 2)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    setText(finalText);
                    setAlpha(0f);
                    animate()
                            .alpha(1f)
                            .setDuration(durationMs / 2)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .start();
                })
                .start();
    }

    private void animateSlotInternal(CharSequence newText, long durationMs, boolean upward) {
        animateSlotInternal(newText, durationMs, upward, false);
    }

    private void animateSlotInternal(CharSequence newText, long durationMs, boolean upward, boolean forceFull) {
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
        boolean includePad = getIncludeFontPadding();

        // ---- Differential diff: find unchanged prefix and suffix ----------------
        int prefixLen = commonPrefixLength(oldStr, newStr);
        int suffixLen = commonSuffixLength(oldStr, newStr, prefixLen);
        prefixLen = adjustPrefixForNumericUnit(oldStr, newStr, prefixLen, suffixLen);
        boolean hasDiff = !forceFull && (prefixLen > 0 || suffixLen > 0);

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

        // Compute a layout width that fits both texts on a single line.
        // Using usableW alone causes the new (wider) text to wrap during animation when the
        // view is wrap_content-sized for the old shorter text — e.g. animating
        // "Back Camera" → "Front Camera" wraps "Camera" to line 2 so it appears late.
        int maxNeededW = (int) Math.ceil(Math.max(
                oldStr.isEmpty() ? 0f : paint.measureText(oldStr),
                newStr.isEmpty() ? 0f : paint.measureText(newStr)));

        // A view with a fixed width (match_parent, a weight column) owns the wrap: forcing a
        // single-line layout there draws one long clipped line first and then reflows to the
        // real multi-line layout when the animation ends. Only wrap_content views need the
        // widened layout, because they are still sized to the old, shorter text.
        android.view.ViewGroup.LayoutParams lp = getLayoutParams();
        boolean wrapContent = lp != null
                && lp.width == android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
        if (!wrapContent) maxNeededW = usableW;
        int layoutW = Math.max(usableW, maxNeededW);
        boolean centered = (getGravity() & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
                == Gravity.CENTER_HORIZONTAL;

        // Always build FULL-STRING layouts so every region (prefix, column, suffix) is
        // rendered by the same StaticLayout engine — identical baselines everywhere.
        // Using partial-string layouts for the column caused a per-pixel Y mismatch with
        // canvas.drawText() for the prefix/suffix due to StaticLayout includeFontPadding,
        // which manifested as the whole row jiggling during animation.
        // Keep the view's normal alignment in BOTH modes. Forcing ALIGN_NORMAL for the
        // differential case left-aligned a centred (or RTL start-aligned) label during the
        // animation and snapped it back afterwards — the "label jumping around" effect.
        Layout.Alignment alignment = centered
                ? Layout.Alignment.ALIGN_CENTER
                : Layout.Alignment.ALIGN_NORMAL;

        // Match the TextView's own bidi resolution. TextView lays the text out with the
        // direction set by android:textDirection, but StaticLayout defaults to
        // FIRSTSTRONG_LTR, which resolves from the first strong character instead. On an
        // Arabic value (letters + Arabic-Indic digits) that produced a DIFFERENT glyph order
        // than the resting text, so each animated frame disagreed with the final frame and
        // the label appeared to jiggle and flicker.
        android.text.TextDirectionHeuristic heuristic = textDirectionHeuristic();
        slotOldLayout = StaticLayout.Builder
                .obtain(oldStr, 0, oldStr.length(), paint, layoutW)
                .setAlignment(alignment)
                .setTextDirection(heuristic)
                .setIncludePad(includePad)
                .build();
        slotNewLayout = StaticLayout.Builder
                .obtain(newStr, 0, newStr.length(), paint, layoutW)
                .setAlignment(alignment)
                .setTextDirection(heuristic)
                .setIncludePad(includePad)
                .build();

        // Resolve the changed column from the built layouts rather than from measured
        // logical widths. This is bidi-safe: for Arabic (letters plus Arabic-Indic digits)
        // the run order is resolved by the layout, and the returned span already reflects
        // that, so the clip window stays pinned to the glyphs actually being animated.
        float[] newBounds = changedBounds(
                slotNewLayout, prefixLen, prefixLen + newChanged.length());
        float[] oldBounds = changedBounds(
                slotOldLayout, prefixLen, prefixLen + oldChanged.length());
        boolean boundsUsable = newBounds != null && oldBounds != null;
        if (hasDiff && !boundsUsable) {
            // Could not resolve a safe single-run column (unexpected bidi reordering) —
            // slide the whole string instead of clipping at a guessed position.
            hasDiff = false;
        }

        // Window = the line the text actually occupies at rest (same origin TextView uses).
        Layout resting = getLayout();
        boolean singleLine = resting != null
                && resting.getLineCount() == 1
                && slotOldLayout.getLineCount() == 1
                && slotNewLayout.getLineCount() == 1;
        // Resting baseline of the text, exactly as TextView.onDraw() would place it.
        int boxH = Math.max(0, getHeight() - getExtendedPaddingTop()
                - getExtendedPaddingBottom());
        int vOffset = 0;
        int vGravity = getGravity() & Gravity.VERTICAL_GRAVITY_MASK;
        if (vGravity == Gravity.CENTER_VERTICAL) {
            vOffset = Math.max(0, (boxH - resting.getHeight()) / 2);
        } else if (vGravity == Gravity.BOTTOM) {
            vOffset = Math.max(0, boxH - resting.getHeight());
        }
        slotBaselineY = getExtendedPaddingTop() + vOffset + resting.getLineBaseline(0);

        // Clip to the union of both strings' real ink extents. Font metrics are not usable:
        // Arabic glyphs (ث, ج, ي) overshoot the nominal descent, and the view box includes
        // font padding — either one leaves the outgoing digit visible above the line, which
        // is what made the row look like it was jumping.
        android.graphics.Rect oldInk = new android.graphics.Rect();
        android.graphics.Rect newInk = new android.graphics.Rect();
        paint.getTextBounds(oldStr, 0, oldStr.length(), oldInk);
        paint.getTextBounds(newStr, 0, newStr.length(), newInk);
        slotWindowTop = slotBaselineY + Math.min(oldInk.top, newInk.top);
        slotWindowBottom = slotBaselineY + Math.max(oldInk.bottom, newInk.bottom) + 1;

        slotHasDifferentialRegions = hasDiff;
        slotLayoutWidth = layoutW;
        // ALIGN_CENTER already centres inside the layout box, and a wrap_content box hugs
        // the text, so only fixed-width start/end-aligned views need an extra offset.
        float restedTextW = newStr.isEmpty() ? 0f : paint.measureText(newStr);
        slotDrawBaseX = getCompoundPaddingLeft();
        if (!wrapContent && !centered) {
            slotDrawBaseX += computeHorizontalOffset(usableW, restedTextW);
        }

        // ALIGN_NORMAL anchors text to the START edge of the *layout* box. When the incoming
        // string is wider, layoutW exceeds the view, so in RTL the whole run was shifted right
        // by (layoutW - usableW) for the duration of the slide and back again afterwards.
        // That is the horizontal "jumping" of the label. Shift the draw origin by the same
        // overflow so the anchored edge stays exactly where the resting text has it.
        int overflowW = layoutW - usableW;
        if (overflowW > 0) {
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            boolean anchoredRight = centered
                    ? true
                    : (alignment == Layout.Alignment.ALIGN_OPPOSITE) != rtl;
            if (anchoredRight) {
                slotDrawBaseX -= overflowW;
            }
        }
        if (hasDiff) {
            // Partial animation: only the column between prefix and suffix slides.
            slotNewChangedLeft  = newBounds[0];
            slotNewChangedRight = newBounds[1];
            slotOldChangedLeft  = oldBounds[0];
            slotOldChangedRight = oldBounds[1];
            // Union of both columns keeps the moving glyphs fully visible while they slide.
            slotPrefixWidth = Math.min(slotNewChangedLeft, slotOldChangedLeft);
            slotColumnWidth = Math.max(slotNewChangedRight, slotOldChangedRight) - slotPrefixWidth;
            slotOldChangedWidth = slotOldChangedRight - slotOldChangedLeft;
            slotNewChangedWidth = slotNewChangedRight - slotNewChangedLeft;
        } else {
            // Full-string animation — no stable prefix or suffix.
            slotPrefixWidth     = 0f;
            slotColumnWidth     = layoutW;       // cover the full rendered width
            slotOldChangedWidth = layoutW;
            slotNewChangedWidth = layoutW;       // effectively no suffix
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

    /**
     * Keep units (like "s", "m", "h") static but animate the full numeric chunk before them.
     * This avoids razor-thin clip seams inside multi-digit values such as "10s" -> "11s".
     */
    private static int adjustPrefixForNumericUnit(
            String oldText,
            String newText,
            int prefixLen,
            int suffixLen
    ) {
        if (suffixLen <= 0) {
            return prefixLen;
        }

        int newSuffixStart = newText.length() - suffixLen;
        int oldSuffixStart = oldText.length() - suffixLen;
        if (newSuffixStart <= 0 || oldSuffixStart <= 0) {
            return prefixLen;
        }

        char suffixHead = newText.charAt(newSuffixStart);
        if (!Character.isLetter(suffixHead)) {
            return prefixLen;
        }

        int newNumEnd = newSuffixStart - 1;
        int oldNumEnd = oldSuffixStart - 1;
        if (newNumEnd < 0 || oldNumEnd < 0) {
            return prefixLen;
        }
        if (!Character.isDigit(newText.charAt(newNumEnd)) || !Character.isDigit(oldText.charAt(oldNumEnd))) {
            return prefixLen;
        }

        int newRunStart = newNumEnd;
        while (newRunStart > 0 && Character.isDigit(newText.charAt(newRunStart - 1))) {
            newRunStart--;
        }

        int oldRunStart = oldNumEnd;
        while (oldRunStart > 0 && Character.isDigit(oldText.charAt(oldRunStart - 1))) {
            oldRunStart--;
        }

        int newRunLen = newSuffixStart - newRunStart;
        int oldRunLen = oldSuffixStart - oldRunStart;
        if (newRunLen == oldRunLen) {
            // Keep normal differential behavior for equal-length numbers:
            // 40s -> 41s should animate only the changed digit.
            return prefixLen;
        }

        // For length changes (9s -> 10s), animate the full numeric run to avoid seam artifacts.
        return Math.min(prefixLen, Math.min(newRunStart, oldRunStart));
    }

    // ---- Animation lifecycle ----------------------------------------------------

    private void finishSlotAnimation() {
        // Set the text silently before clearing animation state so the view's
        // own renderer shows the correct final string in the very same invalidate pass.
        // We must NOT call requestLayout here — the new text was already painted by
        // the last animated frame at progress=1, so there is zero visual change.
        if (slotPendingFinalText != null) {
            isSlotAnimating = false;
            CharSequence pending = slotPendingFinalText;
            slotPendingFinalText = null;
            slotOldLayout = null;
            slotNewLayout = null;
            slotAnimator = null;
            slotProgress = 1f;
            // Let the view remeasure: setText() nulls the internal Layout, and a
            // wrap_content box must grow/shrink to the new string or the final frame keeps
            // the old width and clips the trailing glyphs.
            setText(pending);
            // setText already schedules a redraw; no extra invalidate needed.
        } else {
            isSlotAnimating = false;
            slotProgress = 1f;
            slotOldLayout = null;
            slotNewLayout = null;
            slotAnimator = null;
            invalidate();
        }
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
        slotHasDifferentialRegions = false;
    }

    // ---- Drawing ----------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        if (!isSlotAnimating || slotOldLayout == null || slotNewLayout == null) {
            super.onDraw(canvas);
            return;
        }

        int left   = getCompoundPaddingLeft();
        int right  = getWidth() - getCompoundPaddingRight();
        int top    = getExtendedPaddingTop();
        int bottom = getHeight() - getExtendedPaddingBottom();

        int oldHeight = slotOldLayout.getHeight();
        int newHeight = slotNewLayout.getHeight();
        // Both layers are drawn so their own baseline lands on the resting baseline, which
        // keeps the glyphs pixel-identical before, during and after the slide.
        float oldRest = slotBaselineY - slotOldLayout.getLineBaseline(0);
        float newRest = slotBaselineY - slotNewLayout.getLineBaseline(0);

        // Slide distance = the text line height; the window clips it to a single line.
        int slideDistance = Math.round(Math.max(1f, slotWindowBottom - slotWindowTop));

        // Compute Y positions for the two animating layers around their own rest origins.
        float oldY, newY;
        if (slotUpward) {
            oldY = oldRest - slideDistance * slotProgress;
            newY = newRest + slideDistance * (1f - slotProgress);
        } else {
            oldY = oldRest + slideDistance * slotProgress;
            newY = newRest - slideDistance * (1f - slotProgress);
        }

        // Column bounds derived from prefix/changed-text widths.
        float colLeft     = slotDrawBaseX + slotPrefixWidth;
        float colRight    = colLeft + slotColumnWidth;
        // Suffix starts at the right edge of the union column so nothing to its right moves.
        float suffixStart = slotDrawBaseX + Math.max(slotOldChangedRight, slotNewChangedRight);

        // Clip Y to view bounds (never bleed above/below), but do NOT restrict X to
        // the view's current measured width — the new text may be wider than the current
        // measured width (wrap_content sized to old shorter string), and clamping to
        // 'right' would truncate the incoming text until the animation ends and the
        // view remeasures itself.
        // Outer Y-only clip — keep all drawing within the text row.
        // Use actual pixel boundaries; never use Integer.MIN/MAX_VALUE on a HW canvas.
        canvas.save();
        // Cover the full layout, not just getWidth(): a wrap_content view is still sized to
        // the OLD (narrower) string while the new one animates in, and clamping to getWidth()
        // sliced the trailing unit letter off for the whole slide — the value appeared to
        // flicker and drop its last glyph every second.
        int drawRight = Math.max(getWidth(), (int) Math.ceil(slotDrawBaseX + slotLayoutWidth) + 2);
        int clipTop = (int) Math.floor(slotWindowTop);
        int clipBottom = (int) Math.ceil(slotWindowBottom);
        canvas.clipRect(0, clipTop, drawRight, clipBottom);

        // 1. Static text outside the animated column (differential mode only).
        //    Clipping "everything except the column" is direction-agnostic: in LTR the
        //    unchanged prefix sits left of the digits, while in RTL the unchanged Arabic
        //    units sit to their LEFT. Slicing a left prefix and a right suffix dropped the
        //    units in RTL, which is what made the value flicker/disappear each second.
        if (slotHasDifferentialRegions) {
            canvas.save();
            canvas.clipRect(0, top, colLeft, bottom);
            canvas.translate(slotDrawBaseX, newRest);
            slotNewLayout.draw(canvas);
            canvas.restore();
        }

        // 2. Animated COLUMN
        canvas.save();
        canvas.clipRect(colLeft, top, colRight, bottom);

        canvas.save();
        canvas.translate(slotDrawBaseX, oldY);
        slotOldLayout.draw(canvas);
        canvas.restore();

        canvas.save();
        canvas.translate(slotDrawBaseX, newY);
        slotNewLayout.draw(canvas);
        canvas.restore();

        canvas.restore(); // end column clip

        // 3. Static text to the right of the column (differential mode only)
        if (slotHasDifferentialRegions) {
            canvas.save();
            canvas.clipRect(colRight, top, drawRight, bottom);
            canvas.translate(slotDrawBaseX, newRest);
            slotNewLayout.draw(canvas);
            canvas.restore();
        }

        canvas.restore(); // end Y clip
    }

    /**
     * Visual x-range [left, right] of {@code [start, end)} inside {@code layout}.
     * Returns null when the range is degenerate or cannot be resolved to one run.
     */
    private static float[] changedBounds(Layout layout, int start, int end) {
        if (layout == null || end <= start) {
            return null;
        }
        int length = layout.getText().length();
        if (start < 0 || end > length) {
            return null;
        }
        try {
            // getSelectionPath() is the same primitive text selection uses, so it returns the
            // true visual rectangle of the range after bidi reordering. Measuring from
            // getPrimaryHorizontal(start/end) instead is wrong across a bidi boundary: with
            // "١٠٧٫٧٩ غيغابايت" one changed digit reported a 75px span because the two logical
            // offsets sit on opposite sides of the Arabic run. That over-wide column then
            // clipped the unit letters away and the value flickered every tick.
            android.graphics.Path path = new android.graphics.Path();
            layout.getSelectionPath(start, end, path);
            android.graphics.RectF bounds = new android.graphics.RectF();
            path.computeBounds(bounds, true);
            if (!Float.isFinite(bounds.left) || !Float.isFinite(bounds.right)
                    || bounds.right - bounds.left < 0.5f) {
                return null;
            }
            return new float[] {bounds.left, bounds.right};
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Maps view gravity to {@link Layout.Alignment} for StaticLayout. */
    private Layout.Alignment resolveLayoutAlignment() {
        int gravity = getGravity() & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK;
        if (gravity == Gravity.CENTER_HORIZONTAL) return Layout.Alignment.ALIGN_CENTER;
        if (gravity == Gravity.END)               return Layout.Alignment.ALIGN_OPPOSITE;
        return Layout.Alignment.ALIGN_NORMAL;
    }

    /** Mirrors the direction the TextView itself uses for this view's text. */
    private android.text.TextDirectionHeuristic textDirectionHeuristic() {
        switch (getTextDirection()) {
            case TEXT_DIRECTION_LTR:
                return android.text.TextDirectionHeuristics.LTR;
            case TEXT_DIRECTION_RTL:
                return android.text.TextDirectionHeuristics.RTL;
            case TEXT_DIRECTION_LOCALE:
                return android.text.TextDirectionHeuristics.LOCALE;
            case TEXT_DIRECTION_FIRST_STRONG:
                return android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR;
            case TEXT_DIRECTION_ANY_RTL:
                return android.text.TextDirectionHeuristics.ANYRTL_LTR;
            default:
                return android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR;
        }
    }

    private float computeHorizontalOffset(int availableWidth, float textWidth) {
        int gravity = getGravity() & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        boolean alignToRight;
        if (gravity == Gravity.CENTER_HORIZONTAL) {
            return Math.max(0f, (availableWidth - textWidth) / 2f);
        } else if (gravity == Gravity.RIGHT) {
            alignToRight = true;
        } else if (gravity == Gravity.LEFT) {
            alignToRight = false;
        } else if (gravity == Gravity.END) {
            // END is on the right in LTR and on the left in RTL.
            alignToRight = !rtl;
        } else {
            // START (and the default): right edge in RTL, left edge in LTR.
            alignToRight = rtl;
        }
        return alignToRight ? Math.max(0f, availableWidth - textWidth) : 0f;
    }

    /** Cancel any running animation and reset state. */
    public void cancelAnimation() {
        cancelSlotAnimation();
        setScaleX(1f);
        setScaleY(1f);
        setAlpha(1f);
    }
}
