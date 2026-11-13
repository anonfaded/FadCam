package com.fadcam.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.UnderlineSpan;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.airbnb.lottie.LottieAnimationView;
import com.fadcam.R;
import com.google.android.material.button.MaterialButton;

/**
 * Onboarding slide 04: two honest agreements in one premium panel.
 *
 * <p>Each row is the toggle target. Inside the legal row, the Terms and Privacy
 * spans keep their own taps: a link opens the document and never changes the
 * checkbox. State survives Activity recreation through the instance state.</p>
 */
public class OnboardingHumanFragment extends Fragment {

    private static final String STATE_AGREED_RESPONSIBLE = "agreed_responsible";
    private static final String STATE_AGREED_LEGAL = "agreed_legal";

    private static final String URL_TERMS = "https://fadseclab.com/terms/";
    private static final String URL_PRIVACY = "https://fadseclab.com/privacy/";

    private boolean agreedResponsible;
    private boolean agreedLegal;

    private MaterialButton continueButton;
    private ImageView icon1;
    private ImageView icon2;
    private TextView label1;
    private TextView label2;
    private LinearLayout row1;
    private LinearLayout row2;
    private TextView titleText;
    private TextView descText;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.onboarding_human_slide, container, false);

        if (savedInstanceState != null) {
            agreedResponsible = savedInstanceState.getBoolean(STATE_AGREED_RESPONSIBLE, false);
            agreedLegal = savedInstanceState.getBoolean(STATE_AGREED_LEGAL, false);
        }

        row1 = view.findViewById(R.id.checkbox_row_1);
        row2 = view.findViewById(R.id.checkbox_row_2);
        icon1 = view.findViewById(R.id.checkbox_icon_1);
        icon2 = view.findViewById(R.id.checkbox_icon_2);
        label1 = view.findViewById(R.id.checkbox_label_1);
        label2 = view.findViewById(R.id.checkbox_label_2);
        continueButton = view.findViewById(R.id.btn_human_continue);
        titleText = view.findViewById(R.id.tvHumanTitle);
        descText = view.findViewById(R.id.tvHumanDesc);

        // The flag rain is decoration only and must never eat touches.
        View rain = view.findViewById(R.id.palestineRainView);
        if (rain != null) {
            rain.setClickable(false);
            rain.setFocusable(false);
        }

        LottieAnimationView lottie = view.findViewById(R.id.lottieHuman);
        if (lottie != null) lottie.setSpeed(0.8f);

        bindLegalLabel();

        row1.setOnClickListener(v -> setResponsible(!agreedResponsible));
        row2.setOnClickListener(v -> setLegal(!agreedLegal));

        continueButton.setOnClickListener(v -> {
            if (getActivity() instanceof OnboardingActivity) {
                ((OnboardingActivity) getActivity()).finishOnboarding();
            } else {
                requireActivity().finish();
            }
        });

        render();
        return view;
    }

    private void setResponsible(boolean checked) {
        agreedResponsible = checked;
        render();
    }

    private void setLegal(boolean checked) {
        agreedLegal = checked;
        render();
    }

    private void render() {
        int gold = ContextCompat.getColor(requireContext(), R.color.pro_gold);
        int ink = ContextCompat.getColor(requireContext(), R.color.dossier_ink);

        icon1.setImageResource(agreedResponsible
                ? R.drawable.placeholder_checkbox_checked
                : R.drawable.placeholder_checkbox_outline);
        icon1.setImageTintList(ColorStateList.valueOf(agreedResponsible ? gold : ink));

        icon2.setImageResource(agreedLegal
                ? R.drawable.placeholder_checkbox_checked
                : R.drawable.placeholder_checkbox_outline);
        icon2.setImageTintList(ColorStateList.valueOf(agreedLegal ? gold : ink));

        boolean ready = agreedResponsible && agreedLegal;
        continueButton.setEnabled(ready);
        continueButton.setAlpha(ready ? 1f : 0.6f);

        bindCheckableRow(row1, label1.getText().toString(), agreedResponsible);
        bindCheckableRow(row2, label2.getText().toString(), agreedLegal);
    }

    /** Exposes the row as a single checkable control to TalkBack. */
    private void bindCheckableRow(View row, String text, boolean checked) {
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host,
                                                          @NonNull AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.CheckBox");
                info.setCheckable(true);
                info.setChecked(checked);
                info.setText(text);
            }
        });
    }

    private void bindLegalLabel() {
        String terms = getString(R.string.onboarding_terms_of_use);
        String privacy = getString(R.string.onboarding_privacy_policy);
        String full = getString(R.string.onboarding_human_agreement_legal, terms, privacy);

        SpannableString text = new SpannableString(full);
        applyLink(text, terms, URL_TERMS);
        applyLink(text, privacy, URL_PRIVACY);

        label2.setText(text);
        label2.setMovementMethod(new LinkOnlyMovementMethod());
        label2.setHighlightColor(Color.TRANSPARENT);
        // A movement method makes the TextView clickable, which would swallow
        // taps meant for the row. Links still work; everything else falls through.
        label2.setClickable(false);
        label2.setLongClickable(false);
    }

    private void applyLink(SpannableString text, String label, String url) {
        int start = text.toString().indexOf(label);
        if (start < 0) return;
        int end = start + label.length();
        text.setSpan(new LegalLinkSpan(url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private final class LegalLinkSpan extends ClickableSpan {
        private final String url;

        LegalLinkSpan(String url) {
            this.url = url;
        }

        @Override
        public void onClick(@NonNull View widget) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {
                // No browser installed; leave the checkbox exactly as it was.
            }
        }

        @Override
        public void updateDrawState(@NonNull TextPaint ds) {
            ds.setColor(ContextCompat.getColor(requireContext(), R.color.onboarding_link));
            ds.setUnderlineText(true);
        }
    }

    /**
     * Only consumes a touch when a link is actually under the finger. Every other
     * tap falls through to the agreement row, which owns the toggle.
     */
    private static final class LinkOnlyMovementMethod extends LinkMovementMethod {
        @Override
        public boolean onTouchEvent(TextView widget, Spannable buffer, MotionEvent event) {
            int action = event.getAction();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
                int x = (int) event.getX();
                int y = (int) event.getY();
                x -= widget.getTotalPaddingLeft();
                y -= widget.getTotalPaddingTop();
                x += widget.getScrollX();
                y += widget.getScrollY();
                Layout layout = widget.getLayout();
                if (layout == null || y < 0 || y > layout.getHeight()) return false;
                int line = layout.getLineForVertical(y);
                int offset = layout.getOffsetForHorizontal(line, x);
                ClickableSpan[] links = buffer.getSpans(offset, offset, ClickableSpan.class);
                if (links.length == 0) return false;
            }
            return super.onTouchEvent(widget, buffer, event);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_AGREED_RESPONSIBLE, agreedResponsible);
        outState.putBoolean(STATE_AGREED_LEGAL, agreedLegal);
    }

    /** Refreshes language-specific text without recreating the fragment. */
    public void refreshLanguage() {
        if (titleText != null) titleText.setText(R.string.onboarding_human_title);
        if (descText != null) descText.setText(R.string.onboarding_human_desc);
        if (label1 != null) label1.setText(R.string.onboarding_human_agreement_responsible);
        if (label2 != null) bindLegalLabel();
        if (continueButton != null) {
            continueButton.setText(R.string.onboarding_human_button);
            continueButton.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            continueButton.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        }
        render();
    }
}
