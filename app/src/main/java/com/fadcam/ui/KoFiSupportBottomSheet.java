package com.fadcam.ui;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.widget.ImageView;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.app.Dialog;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import android.animation.AnimatorSet;
import android.animation.AnimatorListenerAdapter;
import android.animation.Animator;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import androidx.appcompat.content.res.AppCompatResources;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.fadcam.R;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ImageSpan;

public class KoFiSupportBottomSheet extends BottomSheetDialogFragment {
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottomsheet_kofi_support, container, false);

        // Animate the coffee cup icon (ivKoFiCup) with wiggle/rotation
        ImageView ivKoFiCup = view.findViewById(R.id.ivKoFiCup);
        if (ivKoFiCup != null) {
            final float moveDistance = 10f; // pixels to move left/right
            final float rotateAngle = 15f;  // degrees to rotate left/right
            final long moveDuration = 160;  // ms for each move
            final long pauseDuration = 1500; // ms pause at center
            final Runnable[] startWiggle = new Runnable[1];
            startWiggle[0] = new Runnable() {
                @Override
                public void run() {
                    ObjectAnimator moveRight = ObjectAnimator.ofFloat(ivKoFiCup, "translationX", 0f, moveDistance);
                    ObjectAnimator rotateRight = ObjectAnimator.ofFloat(ivKoFiCup, "rotation", 0f, rotateAngle);
                    AnimatorSet rightSet = new AnimatorSet();
                    rightSet.playTogether(moveRight, rotateRight);
                    rightSet.setDuration(moveDuration);

                    ObjectAnimator moveLeft = ObjectAnimator.ofFloat(ivKoFiCup, "translationX", moveDistance, -moveDistance);
                    ObjectAnimator rotateLeft = ObjectAnimator.ofFloat(ivKoFiCup, "rotation", rotateAngle, -rotateAngle);
                    AnimatorSet leftSet = new AnimatorSet();
                    leftSet.playTogether(moveLeft, rotateLeft);
                    leftSet.setDuration(moveDuration * 2);

                    ObjectAnimator moveCenter = ObjectAnimator.ofFloat(ivKoFiCup, "translationX", -moveDistance, 0f);
                    ObjectAnimator rotateCenter = ObjectAnimator.ofFloat(ivKoFiCup, "rotation", -rotateAngle, 0f);
                    AnimatorSet centerSet = new AnimatorSet();
                    centerSet.playTogether(moveCenter, rotateCenter);
                    centerSet.setDuration(moveDuration);

                    AnimatorSet wiggleSet = new AnimatorSet();
                    wiggleSet.playSequentially(rightSet, leftSet, centerSet);
                    wiggleSet.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            ivKoFiCup.postDelayed(startWiggle[0], pauseDuration);
                        }
                    });
                    wiggleSet.start();
                }
            };
            ivKoFiCup.post(startWiggle[0]);
        }
        // Set click listener on the whole button row
        View layoutKoFiButtonRow = view.findViewById(R.id.layoutKoFiButtonRow);
        if (layoutKoFiButtonRow != null) {
            layoutKoFiButtonRow.setOnClickListener(v -> {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/fadedx"));
                startActivity(browserIntent);
            });
        }

        // Styled description (left-aligned, only keywords bold, project names red/clickable)
        TextView tvKoFiDescription = view.findViewById(R.id.tvKoFiDescription);
        if (tvKoFiDescription != null) {
            String desc = "Built and maintained by the developer behind FadCam, FadCrypt, and other projects at FadSec Lab — a community-driven initiative focused on privacy-first, ad-free, tracker-free, and fully open-source tools, crafted over hundreds of hours of effort.\n\nIf you find value in this work and want to support the vision, you're welcome to buy the maintainer a coffee. ☕";
            android.text.SpannableString spannable = new android.text.SpannableString(desc);
            // Make FadCam red and clickable
            int fadCamStart = desc.indexOf("FadCam");
            int fadCamEnd = fadCamStart + "FadCam".length();
            if (fadCamStart >= 0) {
                spannable.setSpan(new android.text.style.ForegroundColorSpan(Color.parseColor("#E43C3C")), fadCamStart, fadCamEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                spannable.setSpan(new android.text.style.ClickableSpan() {
                    @Override
                    public void onClick(@NonNull View widget) {
                        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/anonfaded/FadCam"));
                        widget.getContext().startActivity(browserIntent);
                    }
                    @Override
                    public void updateDrawState(android.text.TextPaint ds) {
                        super.updateDrawState(ds);
                        ds.setColor(Color.parseColor("#E43C3C"));
                        ds.setUnderlineText(false);
                    }
                }, fadCamStart, fadCamEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            // Make FadCrypt red and clickable
            int fadCryptStart = desc.indexOf("FadCrypt");
            int fadCryptEnd = fadCryptStart + "FadCrypt".length();
            if (fadCryptStart >= 0) {
                spannable.setSpan(new android.text.style.ForegroundColorSpan(Color.parseColor("#E43C3C")), fadCryptStart, fadCryptEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                spannable.setSpan(new android.text.style.ClickableSpan() {
                    @Override
                    public void onClick(@NonNull View widget) {
                        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/anonfaded/FadCrypt"));
                        widget.getContext().startActivity(browserIntent);
                    }
                    @Override
                    public void updateDrawState(android.text.TextPaint ds) {
                        super.updateDrawState(ds);
                        ds.setColor(Color.parseColor("#E43C3C"));
                        ds.setUnderlineText(false);
                    }
                }, fadCryptStart, fadCryptEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            // Make FadSec Lab red and clickable
            int fadSecLabStart = desc.indexOf("FadSec Lab");
            int fadSecLabEnd = fadSecLabStart + "FadSec Lab".length();
            if (fadSecLabStart >= 0) {
                spannable.setSpan(new android.text.style.ForegroundColorSpan(Color.parseColor("#E43C3C")), fadSecLabStart, fadSecLabEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                spannable.setSpan(new android.text.style.ClickableSpan() {
                    @Override
                    public void onClick(@NonNull View widget) {
                        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/fadsec-lab"));
                        widget.getContext().startActivity(browserIntent);
                    }
                    @Override
                    public void updateDrawState(android.text.TextPaint ds) {
                        super.updateDrawState(ds);
                        ds.setColor(Color.parseColor("#E43C3C"));
                        ds.setUnderlineText(false);
                    }
                }, fadSecLabStart, fadSecLabEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            // Bold only the keywords
            String[] keywords = {"privacy-first", "ad-free", "tracker-free", "fully open-source"};
            for (String keyword : keywords) {
                int kStart = desc.indexOf(keyword);
                if (kStart >= 0) {
                    spannable.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), kStart, kStart + keyword.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            tvKoFiDescription.setText(spannable);
            tvKoFiDescription.setTextColor(Color.WHITE);
            tvKoFiDescription.setMovementMethod(LinkMovementMethod.getInstance());
            tvKoFiDescription.setGravity(android.view.Gravity.START);
        }

        // Footer: Made with Palestine at FadSec Lab in Pakistan here
        TextView tvKoFiFooterFull = view.findViewById(R.id.tvKoFiFooterFull);
        if (tvKoFiFooterFull != null) {
            String footer = "Made with Palestine at FadSec Lab in Pakistan";
            SpannableString spannable = new SpannableString(footer);
            // Palestine flag image
            Drawable palestine = AppCompatResources.getDrawable(requireContext(), R.drawable.palestine);
            if (palestine != null) {
                int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18, requireContext().getResources().getDisplayMetrics());
                palestine.setBounds(0, 0, size, size);
                ImageSpan palestineSpan = new ImageSpan(palestine, ImageSpan.ALIGN_BOTTOM);
                int palestineIndex = footer.indexOf("Palestine");
                if (palestineIndex != -1) {
                    spannable.setSpan(palestineSpan, palestineIndex, palestineIndex + "Palestine".length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            // Pakistan flag image
            Drawable pakistan = AppCompatResources.getDrawable(requireContext(), R.drawable.pakistan);
            if (pakistan != null) {
                int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18, requireContext().getResources().getDisplayMetrics());
                pakistan.setBounds(0, 0, size, size);
                ImageSpan pakistanSpan = new ImageSpan(pakistan, ImageSpan.ALIGN_BOTTOM);
                int pakistanIndex = footer.indexOf("Pakistan");
                if (pakistanIndex != -1) {
                    spannable.setSpan(pakistanSpan, pakistanIndex, pakistanIndex + "Pakistan".length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            // FadSec Lab clickable, bold, and red
            int fadSecLabStart = footer.indexOf("FadSec Lab");
            int fadSecLabEnd = fadSecLabStart + "FadSec Lab".length();
            if (fadSecLabStart >= 0) {
                spannable.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), fadSecLabStart, fadSecLabEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                spannable.setSpan(new android.text.style.ClickableSpan() {
                    @Override
                    public void onClick(@NonNull View widget) {
                        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/fadsec-lab"));
                        widget.getContext().startActivity(browserIntent);
                    }
                    @Override
                    public void updateDrawState(android.text.TextPaint ds) {
                        super.updateDrawState(ds);
                        ds.setColor(Color.parseColor("#E43C3C")); // Red color
                        ds.setUnderlineText(false); // No underline
                    }
                }, fadSecLabStart, fadSecLabEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            tvKoFiFooterFull.setText(spannable);
            tvKoFiFooterFull.setTextColor(Color.WHITE);
            tvKoFiFooterFull.setMovementMethod(LinkMovementMethod.getInstance());
        }

        return view;
    }

    @Override
    public int getTheme() {
        return R.style.CustomBottomSheetDialogTheme;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((BottomSheetDialog) dialog).findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackgroundResource(R.drawable.gradient_background);
            }
        });
        return dialog;
    }
} 