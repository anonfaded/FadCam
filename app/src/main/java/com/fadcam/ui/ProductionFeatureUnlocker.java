package com.fadcam.ui;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.Locale;

/** Removes dead Coming Soon affordances and wires each tool to the functional production hub. */
public final class ProductionFeatureUnlocker {
    private static final int WIRED_TAG = 0x7f0f0a71;
    private ProductionFeatureUnlocker() {}

    public static void install(View root) {
        if (root == null) return;
        walk(root, root.getContext());
    }

    private static void walk(View view, Context context) {
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            String text = tv.getText() == null ? "" : tv.getText().toString().trim();
            String lower = text.toLowerCase(Locale.US);

            if (lower.equals("time left")) tv.setText("Storage time available");

            String key = keyFor(text);
            if (key != null && tv.getTag(WIRED_TAG) == null) {
                tv.setTag(WIRED_TAG, Boolean.TRUE);
                View card = nearestCard(tv);
                View.OnClickListener listener = v -> {
                    if (context instanceof Activity) {
                        ProductionFeatureHubDialog.show((Activity) context, key);
                    }
                };
                if (card != null) {
                    card.setClickable(true);
                    card.setFocusable(true);
                    card.setOnClickListener(listener);
                    card.setOnLongClickListener(v -> { listener.onClick(v); return true; });
                } else {
                    tv.setOnClickListener(listener);
                }
            }

            // These labels are UI gates, not capability checks. The actual tools are now available.
            if (lower.equals("coming soon") || lower.equals("soon")) tv.setVisibility(View.GONE);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), context);
        }
    }

    private static View nearestCard(View start) {
        View current = start;
        for (int depth = 0; depth < 5 && current.getParent() instanceof View; depth++) {
            current = (View) current.getParent();
            if (current instanceof androidx.cardview.widget.CardView
                    || current instanceof com.google.android.material.card.MaterialCardView) return current;
            if (current instanceof ViewGroup && current.isClickable()) return current;
        }
        return start.getParent() instanceof View ? (View) start.getParent() : start;
    }

    private static String keyFor(String raw) {
        String s = raw.toLowerCase(Locale.US);
        if (s.contains("thermal guardian")) return "thermal";
        if (s.contains("audio vision")) return "audio";
        if (s.contains("scheduled recording")) return "schedule";
        if (s.equals("profiles") || s.startsWith("profiles ")) return "profiles";
        if (s.contains("sound meter")) return "sound";
        if (s.contains("sensor dashboard")) return "sensors";
        if (s.contains("speedometer")) return "speed";
        if (s.contains("clinometer")) return "clinometer";
        if (s.equals("compass")) return "compass";
        if (s.contains("pedometer")) return "pedometer";
        if (s.contains("metal detector")) return "metal";
        if (s.contains("parking marker")) return "parking";
        if (s.contains("qr generator")) return "qr";
        return null;
    }
}
