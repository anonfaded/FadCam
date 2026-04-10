package com.fadcam.ui.utils;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import androidx.annotation.Nullable;
import com.google.android.material.button.MaterialButton;

/**
 * A {@link MaterialButton} that animates text changes with the same slot-machine effect
 * as {@link AnimatedTextView}.  The button background, icon, and ripple are rendered by
 * the parent {@link MaterialButton}; only the text label is intercepted in
 * {@link #onDraw(Canvas)} to perform the sliding animation.
 *
 * <p>Keep the button's {@code android:text} set to the starting label.  Call
 * {@link #animateSlotFull(CharSequence, long)} or {@link #animateSlotFullDown(CharSequence, long)}
 * instead of {@link #setText} to trigger the animation.  The button's own text is
 * hidden during the animation by temporarily setting {@code textPaint.alpha = 0}
 * before delegating to {@link MaterialButton#onDraw(Canvas)}, then the animated slot
 * text layers are drawn on top.
 */
public class AnimatedMaterialButton extends MaterialButton {

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
    private float slotOldChangedWidth;
    private float slotNewChangedWidth;
    private float slotDrawBaseX;
    private boolean slotHasDifferentialRegions;

    public AnimatedMaterialButton(Context context) {
        super(context);
    }

    public AnimatedMaterialButton(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AnimatedMaterialButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    // ========== PUBLIC API ==========

    /** Animates the text change with a slot-machine slide UP (e.g. Start → Stop). */
    public void animateSlotFull(CharSequence newText, long durationMs) {
        animateSlotInternal(newText, durationMs, true, true);
    }

    /** Animates the text change with a slot-machine slide DOWN (e.g. Stop → Start). */
    public void animateSlotFullDown(CharSequence newText, long durationMs) {
        animateSlotInternal(newText, durationMs, false, true);
    }

    /** Differential slot (shared-prefix stays static, only changed part animates UP). */
    public void animateSlot(CharSequence newText, long durationMs) {
        animateSlotInternal(newText, durationMs, true, false);
    }

    /**
     * Crossfades the button icon from the current icon to {@code newIcon}.
     * Fades the current icon out, swaps drawables, then fades the new icon in.
     *
     * @param newIcon    The new icon drawable (may be null to clear the icon)
     * @param durationMs Total duration; each half-cycle takes durationMs/2 ms
     */
    public void animateIcon(@Nullable Drawable newIcon, long durationMs) {
        Drawable current = getIcon();
        if (current == null || newIcon == null) {
            setIcon(newIcon);
            return;
        }
        ValueAnimator fadeOut = ValueAnimator.ofInt(255, 0);
        fadeOut.setDuration(durationMs / 2);
        fadeOut.setInterpolator(new AccelerateInterpolator());
        fadeOut.addUpdateListener(a -> {
            current.setAlpha((int) a.getAnimatedValue());
            invalidate();
        });
        fadeOut.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                // Prepare the new drawable at alpha=0 BEFORE installing it so the very
                // first rendered frame after setIcon() starts from invisible, not full alpha.
                Drawable fresh = newIcon.mutate();
                fresh.setAlpha(0);
                setIcon(fresh);
                // 'current' lost its Drawable.Callback after setIcon(), so this reset
                // does NOT trigger a redraw of the old icon — no blink.
                current.setAlpha(255);
                ValueAnimator fadeIn = ValueAnimator.ofInt(0, 255);
                fadeIn.setDuration(durationMs / 2);
                fadeIn.setInterpolator(new DecelerateInterpolator());
                fadeIn.addUpdateListener(a2 -> {
                    fresh.setAlpha((int) a2.getAnimatedValue());
                    invalidate();
                });
                fadeIn.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator animation) {
                        fresh.setAlpha(255); // ensure full alpha on completion
                    }
                });
                fadeIn.start();
            }
        });
        fadeOut.start();
    }

    /** Cancel any running animation and reset state. */
    public void cancelAnimation() {
        cancelSlotAnimation();
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
        int maxNeededW = (int) Math.ceil(Math.max(
                oldStr.isEmpty() ? 0f : paint.measureText(oldStr),
                newStr.isEmpty() ? 0f : paint.measureText(newStr)));
        int layoutW = Math.max(usableW, maxNeededW);

        int prefixLen = 0;
        int suffixLen = 0;
        boolean hasDiff = false;
        if (!forceFull) {
            prefixLen = commonPrefixLength(oldStr, newStr);
            suffixLen = commonSuffixLength(oldStr, newStr, prefixLen);
            prefixLen = adjustPrefixForNumericUnit(oldStr, newStr, prefixLen, suffixLen);
            hasDiff = (prefixLen > 0 || suffixLen > 0);
        }

        Layout.Alignment alignment = hasDiff
                ? Layout.Alignment.ALIGN_NORMAL
                : resolveLayoutAlignment();

        slotOldLayout = StaticLayout.Builder
                .obtain(oldStr, 0, oldStr.length(), paint, layoutW)
                .setAlignment(alignment)
                .setIncludePad(getIncludeFontPadding())
                .build();
        slotNewLayout = StaticLayout.Builder
                .obtain(newStr, 0, newStr.length(), paint, layoutW)
                .setAlignment(alignment)
                .setIncludePad(getIncludeFontPadding())
                .build();

        if (!forceFull) {
            String prefix = newStr.substring(0, prefixLen);
            float prefixW = prefixLen > 0 ? paint.measureText(prefix) : 0f;
            String oldChanged = oldStr.substring(prefixLen, oldStr.length() - suffixLen);
            String newChanged = newStr.substring(prefixLen, newStr.length() - suffixLen);
            float oldChangedW = oldChanged.isEmpty() ? 0f : paint.measureText(oldChanged);
            float newChangedW = newChanged.isEmpty() ? 0f : paint.measureText(newChanged);
            slotPrefixWidth = prefixW;
            slotColumnWidth = Math.max(oldChangedW, newChangedW);
            slotOldChangedWidth = oldChangedW;
            slotNewChangedWidth = newChangedW;
            slotHasDifferentialRegions = hasDiff;
            slotDrawBaseX = getCompoundPaddingLeft() + computeHorizontalOffset(usableW,
                    oldStr.isEmpty() ? 0f : paint.measureText(oldStr));
        } else {
            slotPrefixWidth = 0f;
            slotColumnWidth = layoutW;
            slotOldChangedWidth = layoutW;
            slotNewChangedWidth = layoutW;
            slotHasDifferentialRegions = false;
            slotDrawBaseX = getCompoundPaddingLeft();
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
        // Make the button's own text invisible so super.onDraw doesn't draw it on top
        // of our animated layers.  setColor() inside TextView.onDraw() would override
        // paint.setAlpha(0), so we must do this at the ColorStateList level instead.
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
            // Restore color BEFORE setText so the text appears with the correct color.
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
        slotHasDifferentialRegions = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!isSlotAnimating || slotOldLayout == null || slotNewLayout == null) {
            super.onDraw(canvas);
            return;
        }

        // Text is transparent for the duration of the animation (set in animateSlotInternal),
        // so super draws only background, ripple and icon — no text visible.
        super.onDraw(canvas);

        // Use the same Y origin as TextView.onDraw() for a CENTER_VERTICAL button:
        // extendedPaddingTop + (available - textH) / 2.  This accounts for the extra
        // height that Material buttons gain from insetTop/insetBottom touch-target sizing
        // and minHeight — without it, text would animate above the true center.
        int left     = getCompoundPaddingLeft();
        int textH    = Math.max(slotOldLayout.getHeight(), slotNewLayout.getHeight());
        int extTop   = getExtendedPaddingTop();
        int extBot   = getExtendedPaddingBottom();
        int available = Math.max(0, getHeight() - extTop - extBot);
        int top      = extTop + Math.max(0, (available - textH) / 2);
        int bottom   = top + textH;

        int slideDistance = textH > 0 ? textH : bottom - top;

        float oldY, newY;
        if (slotUpward) {
            oldY = top - slideDistance * slotProgress;
            newY = top + slideDistance * (1f - slotProgress);
        } else {
            oldY = top + slideDistance * slotProgress;
            newY = top - slideDistance * (1f - slotProgress);
        }

        float colLeft  = slotDrawBaseX + slotPrefixWidth;
        float colRight = colLeft + slotColumnWidth;

        int viewW = getWidth();

        canvas.save();
        canvas.clipRect(0, top, viewW, bottom);

        // Static prefix (differential mode only)
        if (slotHasDifferentialRegions && slotPrefixWidth > 0f) {
            canvas.save();
            canvas.clipRect(slotDrawBaseX, top, colLeft, bottom);
            canvas.translate(slotDrawBaseX, top);
            slotNewLayout.draw(canvas);
            canvas.restore();
        }

        // Animated column
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

        // Static suffix (differential mode only)
        if (slotHasDifferentialRegions) {
            int suffixL = (int) Math.ceil(colLeft + slotOldChangedWidth);
            int suffixR = viewW - getCompoundPaddingRight();
            if (suffixL < suffixR) {
                canvas.save();
                canvas.clipRect(suffixL, top, suffixR, bottom);
                canvas.translate(slotDrawBaseX, top);
                slotNewLayout.draw(canvas);
                canvas.restore();
            }
        }

        canvas.restore(); // end Y clip
    }

    // ---- Diff helpers ----------------------------------------------------------

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

    private Layout.Alignment resolveLayoutAlignment() {
        int gravity = getGravity() & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK;
        if (gravity == Gravity.CENTER_HORIZONTAL) return Layout.Alignment.ALIGN_CENTER;
        if (gravity == Gravity.END) return Layout.Alignment.ALIGN_OPPOSITE;
        return Layout.Alignment.ALIGN_NORMAL;
    }

    private float computeHorizontalOffset(int availableWidth, float textWidth) {
        int gravity = getGravity() & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK;
        if (gravity == Gravity.CENTER_HORIZONTAL) {
            return Math.max(0f, (availableWidth - textWidth) / 2f);
        }
        if (gravity == Gravity.END) {
            return Math.max(0f, availableWidth - textWidth);
        }
        return 0f;
    }

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
            return prefixLen;
        }

        return Math.min(prefixLen, Math.min(newRunStart, oldRunStart));
    }
}
