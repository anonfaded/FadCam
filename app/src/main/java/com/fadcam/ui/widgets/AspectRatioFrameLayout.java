package com.fadcam.ui.widgets;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import com.fadcam.R;

/**
 * A slot whose height always follows its measured width, so a fluid (weighted) column keeps an
 * exact aspect ratio instead of a hardcoded dp box.
 *
 * <p>The phone slot needs this: the face textures are rendered at the slot's size and mapped
 * 1:1 onto the model's front face, so a slot that is wider or narrower than the model's aspect
 * stretches the whole device and offsets every touch zone inside it. The ratio is therefore
 * read from the renderer ({@link PhoneGLView#PHONE_ASPECT}), not chosen by the layout.
 */
public class AspectRatioFrameLayout extends FrameLayout {
    /** Height / width of the slot. */
    private float aspectRatio = PhoneGLView.PHONE_ASPECT;

    public AspectRatioFrameLayout(Context context) {
        this(context, null);
    }

    public AspectRatioFrameLayout(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AspectRatioFrameLayout(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.AspectRatioFrameLayout);
            aspectRatio = a.getFloat(R.styleable.AspectRatioFrameLayout_aspectRatio, aspectRatio);
            a.recycle();
        }
        if (aspectRatio <= 0f) aspectRatio = PhoneGLView.PHONE_ASPECT;
    }

    public void setAspectRatio(float ratio) {
        if (ratio <= 0f || ratio == aspectRatio) return;
        aspectRatio = ratio;
        requestLayout();
    }

    public float getAspectRatio() {
        return aspectRatio;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        // Only an exact width can lock the height; an AT_MOST/UNSPECIFIED pass is the parent
        // still negotiating, so it is left to measure normally.
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY && width > 0) {
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(
                    Math.round(width * aspectRatio), MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
