package com.fadcam.ui.widgets;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.google.android.material.button.MaterialButton;

/**
 * One reusable control button for the home Start row: glass MaterialButton,
 * optional centre icon, and a tiny ON/OFF-style status label in the corner.
 *
 * <p>The label keeps a consistent bottom-right inset so short labels such as
 * "ON" do not hug the rounded edge. This is shared by the torch, mirror and
 * FadRec mute buttons instead of three copies of the same FrameLayout +
 * TextView + MaterialButton stack.
 */
public class StatusControlButton extends FrameLayout {

    private MaterialButton button;
    private TextView statusLabel;
    private TextView centerIcon;

    public StatusControlButton(@NonNull Context context) {
        this(context, null);
    }

    public StatusControlButton(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StatusControlButton(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        LayoutInflater.from(context).inflate(R.layout.view_status_control_button, this, true);

        button = findViewById(R.id.status_control_button);
        statusLabel = findViewById(R.id.status_control_label);
        centerIcon = findViewById(R.id.status_control_center_icon);

        setClipChildren(false);
        setClipToPadding(false);

        // MaterialButton applies a theme background tint over the glass drawable;
        // strip it so the gradient/ripple backgrounds render exactly as designed.
        button.setSupportBackgroundTintList(null);

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(
                    attrs,
                    R.styleable.StatusControlButton
            );
            try {
                applyButtonAttributes(a);
                applyStatusAttributes(a);
                applyCenterIconAttributes(a);
            } finally {
                a.recycle();
            }
        }
    }

    private void applyButtonAttributes(TypedArray a) {
        int buttonId = a.getResourceId(
                R.styleable.StatusControlButton_scb_buttonId,
                View.NO_ID
        );
        if (buttonId != View.NO_ID) {
            button.setId(buttonId);
        }

        int iconRes = a.getResourceId(R.styleable.StatusControlButton_scb_icon, 0);
        if (iconRes != 0) {
            button.setIconResource(iconRes);
        }

        float iconSize = a.getDimension(R.styleable.StatusControlButton_scb_iconSize, -1f);
        if (iconSize > 0f) {
            button.setIconSize(Math.round(iconSize));
        }

        ColorStateList tint = a.getColorStateList(R.styleable.StatusControlButton_scb_iconTint);
        if (tint != null) {
            button.setIconTint(tint);
        }

        int background = a.getResourceId(
                R.styleable.StatusControlButton_scb_background,
                R.drawable.control_button_bg
        );
        button.setBackgroundResource(background);
        button.setSupportBackgroundTintList(null);

        if (a.getBoolean(R.styleable.StatusControlButton_scb_buttonGone, false)) {
            button.setVisibility(View.GONE);
        }

        int nextFocusUp = a.getResourceId(
                R.styleable.StatusControlButton_scb_nextFocusUp,
                View.NO_ID
        );
        if (nextFocusUp != View.NO_ID) {
            button.setNextFocusUpId(nextFocusUp);
        }
        int nextFocusDown = a.getResourceId(
                R.styleable.StatusControlButton_scb_nextFocusDown,
                View.NO_ID
        );
        if (nextFocusDown != View.NO_ID) {
            button.setNextFocusDownId(nextFocusDown);
        }
    }

    private void applyStatusAttributes(TypedArray a) {
        int statusId = a.getResourceId(
                R.styleable.StatusControlButton_scb_statusId,
                View.NO_ID
        );
        if (statusId != View.NO_ID) {
            statusLabel.setId(statusId);
        }

        String statusText = a.getString(R.styleable.StatusControlButton_scb_statusText);
        if (statusText != null) {
            statusLabel.setText(statusText);
        }

        float statusSize = a.getDimension(
                R.styleable.StatusControlButton_scb_statusTextSize,
                -1f
        );
        if (statusSize > 0f) {
            statusLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, statusSize);
        }

        if (a.hasValue(R.styleable.StatusControlButton_scb_statusTextColor)) {
            statusLabel.setTextColor(
                    a.getColor(R.styleable.StatusControlButton_scb_statusTextColor, Color.WHITE)
            );
        }

        float marginBottom = a.getDimension(
                R.styleable.StatusControlButton_scb_statusMarginBottom,
                -1f
        );
        float marginEnd = a.getDimension(
                R.styleable.StatusControlButton_scb_statusMarginEnd,
                -1f
        );
        if (marginBottom >= 0f || marginEnd >= 0f) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) statusLabel
                    .getLayoutParams();
            if (marginBottom >= 0f) {
                lp.bottomMargin = Math.round(marginBottom);
            }
            if (marginEnd >= 0f) {
                lp.setMarginEnd(Math.round(marginEnd));
            }
            statusLabel.setLayoutParams(lp);
        }
    }

    private void applyCenterIconAttributes(TypedArray a) {
        int centerIconId = a.getResourceId(
                R.styleable.StatusControlButton_scb_centerIconId,
                View.NO_ID
        );
        if (centerIconId != View.NO_ID) {
            centerIcon.setId(centerIconId);
        }

        String centerText = a.getString(R.styleable.StatusControlButton_scb_centerIconText);
        if (centerText != null && !centerText.isEmpty()) {
            centerIcon.setText(centerText);
            centerIcon.setVisibility(View.VISIBLE);
        }

        float centerSize = a.getDimension(
                R.styleable.StatusControlButton_scb_centerIconSize,
                -1f
        );
        if (centerSize > 0f) {
            centerIcon.setTextSize(TypedValue.COMPLEX_UNIT_PX, centerSize);
        }

        if (a.hasValue(R.styleable.StatusControlButton_scb_centerIconColor)) {
            centerIcon.setTextColor(
                    a.getColor(
                            R.styleable.StatusControlButton_scb_centerIconColor,
                            Color.WHITE
                    )
            );
        }
    }

    public MaterialButton getButton() {
        return button;
    }

    public TextView getStatusLabel() {
        return statusLabel;
    }

    public TextView getCenterIcon() {
        return centerIcon;
    }

    /**
     * Convenience setter for callers that want the shared reveal animation.
     */
    public void setStatus(CharSequence text, @ColorInt int color, boolean animate) {
        if (!statusLabel.getText().toString().contentEquals(text)) {
            statusLabel.setText(text);
            statusLabel.setTextColor(color);
            if (animate) {
                statusLabel.animate().cancel();
                statusLabel.setAlpha(0f);
                statusLabel.setScaleX(0.4f);
                statusLabel.setScaleY(0.4f);
                statusLabel
                        .animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(220)
                        .setInterpolator(
                                new android.view.animation.OvershootInterpolator(1.2f)
                        )
                        .start();
            }
        } else {
            statusLabel.setTextColor(color);
        }
    }
}
