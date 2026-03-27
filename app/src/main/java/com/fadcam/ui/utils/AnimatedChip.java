package com.fadcam.ui.utils;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.animation.DecelerateInterpolator;
import com.google.android.material.chip.Chip;

/**
 * A {@link Chip} that animates text changes with the same slot-machine effect as
 * {@link AnimatedTextView}.  The chip background, icon, and checked-state styling
 * are handled by the parent {@link Chip}; only the text label is intercepted in
 * {@link #onDraw(Canvas)} to perform the sliding animation.
 *
 * <p>Call {@link #animateSlot(CharSequence, long)} or
 * {@link #animateSlotFull(CharSequence, long)} instead of {@link #setText} to
 * trigger the animation.
 */
public class AnimatedChip extends Chip {

    private static final long DEBOUNCE_MS = 100;

    private boolean isSlotAnimating = false;
    private float slotProgress = 1f;
    private boolean slotUpward = true;

    private StaticLayout slotOldLayout;
    private StaticLayout slotNewLayout;
    private CharSequence slotPendingFinalText;
    private ValueAnimator slotAnimator;
    private long lastUpdateTime = 0;
    // Saved text color restored after animation so we can set transparent during animation.
    private android.content.res.ColorStateList slotSavedTextColor;

    private float slotPrefixWidth;
    private float slotColumnWidth;

    public AnimatedChip(Context context) {
        super(context);
    }

    public AnimatedChip(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AnimatedChip(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    // ========== PUBLIC API ==========

    /** Animates the text change with a slot-machine slide UP (counts increasing). */
    public void animateSlot(CharSequence newText, long durationMs) {
        animateSlotInternal(newText, durationMs, true, false);
    }

    /** Animates the full string as a single unit sliding UP (no diff). */
    public void animateSlotFull(CharSequence newText, long durationMs) {
        animateSlotInternal(newText, durationMs, true, true);
    }

    // ========== INTERNAL ==========

    private void animateSlotInternal(CharSequence newText, long durationMs,
                                     boolean upward, boolean forceFull) {
        long now = System.currentTimeMillis();
        if (now - lastUpdateTime < DEBOUNCE_MS) {
            setText(newText);
            return;
        }
        lastUpdateTime = now;

        if (newText == null) newText = "";
        CharSequence oldText = getText();
        if (oldText == null) oldText = "";

        String oldStr = oldText.toString();
        String newStr = newText.toString();
        if (oldStr.equals(newStr)) return;

        if (getWidth() == 0 || getHeight() == 0) {
            setText(newText);
            return;
        }

        cancelSlotAnimation();

        // Copy the paint so that setTextColor(TRANSPARENT) below doesn't corrupt the
        // StaticLayout paint reference — StaticLayout stores a reference, not a copy.
        TextPaint paint = new TextPaint(getPaint());
        int usableW = getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight();
        if (usableW < 1) usableW = 1;

        // Ensure layout is wide enough to fit the wider string without wrapping.
        int maxNeededW = (int) Math.ceil(Math.max(
                oldStr.isEmpty() ? 0f : paint.measureText(oldStr),
                newStr.isEmpty() ? 0f : paint.measureText(newStr)));
        int layoutW = Math.max(usableW, maxNeededW);

        slotOldLayout = StaticLayout.Builder
                .obtain(oldStr, 0, oldStr.length(), paint, layoutW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(getIncludeFontPadding())
                .build();
        slotNewLayout = StaticLayout.Builder
                .obtain(newStr, 0, newStr.length(), paint, layoutW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(getIncludeFontPadding())
                .build();

        if (!forceFull) {
            // Differential: find unchanged prefix
            int prefixLen = commonPrefixLength(oldStr, newStr);
            int suffixLen = commonSuffixLength(oldStr, newStr, prefixLen);
            String prefix = newStr.substring(0, prefixLen);
            String oldChanged = oldStr.substring(prefixLen, oldStr.length() - suffixLen);
            String newChanged = newStr.substring(prefixLen, newStr.length() - suffixLen);
            float prefixW = prefixLen > 0 ? paint.measureText(prefix) : 0f;
            float oldChangedW = oldChanged.isEmpty() ? 0f : paint.measureText(oldChanged);
            float newChangedW = newChanged.isEmpty() ? 0f : paint.measureText(newChanged);
            slotPrefixWidth = prefixW;
            slotColumnWidth = Math.max(oldChangedW, newChangedW);
        } else {
            slotPrefixWidth = 0f;
            slotColumnWidth = layoutW;
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
        slotSavedTextColor = getTextColors();
        setTextColor(android.graphics.Color.TRANSPARENT);
    }

    private void finishSlotAnimation() {
        if (slotPendingFinalText != null) {
            isSlotAnimating = false;
            CharSequence pending = slotPendingFinalText;
            slotPendingFinalText = null;
            slotOldLayout = null;
            slotNewLayout = null;
            slotAnimator = null;
            slotProgress = 1f;
            if (slotSavedTextColor != null) {
                setTextColor(slotSavedTextColor);
                slotSavedTextColor = null;
            }
            setText(pending);
        } else {
            isSlotAnimating = false;
            slotProgress = 1f;
            slotOldLayout = null;
            slotNewLayout = null;
            slotAnimator = null;
            if (slotSavedTextColor != null) {
                setTextColor(slotSavedTextColor);
                slotSavedTextColor = null;
            }
            invalidate();
        }
    }

    private void cancelSlotAnimation() {
        if (slotAnimator != null) {
            slotAnimator.removeAllListeners();
            slotAnimator.cancel();
            slotAnimator = null;
        }
        if (slotSavedTextColor != null) {
            setTextColor(slotSavedTextColor);
            slotSavedTextColor = null;
        }
        isSlotAnimating = false;
        slotOldLayout = null;
        slotNewLayout = null;
        slotPendingFinalText = null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!isSlotAnimating || slotOldLayout == null || slotNewLayout == null) {
            super.onDraw(canvas);
            return;
        }

        // Text is transparent for the duration of the animation (set in animateSlotInternal),
        // so super draws only chip background, stroke and icon — no text visible.
        super.onDraw(canvas);

        // Now draw our animated text on top.
        // ChipDrawable centers text at (chipHeight - textH) / 2.  Mirror that here so
        // the last animated frame and the post-animation ChipDrawable frame are at the
        // same Y — no position snap.
        int left  = getCompoundPaddingLeft();
        int textH = Math.max(slotOldLayout.getHeight(), slotNewLayout.getHeight());
        int top   = Math.max(0, (getHeight() - textH) / 2);
        int bottom = top + textH;

        int slideDistance = textH > 0 ? textH : bottom - top;

        float oldY, newY;
        if (slotUpward) {
            oldY = top - slideDistance * slotProgress;
            newY = top + slideDistance * (1f - slotProgress);
        } else {
            oldY = top + slideDistance * slotProgress;
            newY = top - slideDistance * (1f - slotProgress);
        }

        float colLeft  = left + slotPrefixWidth;
        float colRight = colLeft + slotColumnWidth;

        int viewW = getWidth();

        canvas.save();
        canvas.clipRect(0, top, viewW, bottom);

        // Static prefix (differential mode only)
        if (slotPrefixWidth > 0f) {
            canvas.save();
            canvas.clipRect(left, top, colLeft, bottom);
            canvas.translate(left, top);
            slotNewLayout.draw(canvas);
            canvas.restore();
        }

        // Animated column
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

        // Suffix (differential mode only)
        if (slotPrefixWidth > 0f) {
            int suffixL = (int) colRight;
            int suffixR = viewW - getCompoundPaddingRight();
            if (suffixL < suffixR) {
                canvas.save();
                canvas.clipRect(suffixL, top, suffixR, bottom);
                canvas.translate(left, top);
                slotNewLayout.draw(canvas);
                canvas.restore();
            }
        }

        canvas.restore(); // end Y clip
    }

    private static int commonPrefixLength(String a, String b) {
        int minLen = Math.min(a.length(), b.length());
        int i = 0;
        while (i < minLen && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    private static int commonSuffixLength(String a, String b, int prefixLen) {
        int maxSuffix = Math.min(a.length() - prefixLen, b.length() - prefixLen);
        int i = 0;
        while (i < maxSuffix
                && a.charAt(a.length() - 1 - i) == b.charAt(b.length() - 1 - i)) {
            i++;
        }
        return i;
    }
}
