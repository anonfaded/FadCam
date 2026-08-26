package com.fadcam.production;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Compact field mixer for the duet playback bus. */
public final class ProductionAudioMixerDialog {
    private ProductionAudioMixerDialog() {}

    public static void show(@NonNull Context context, @NonNull ProductionAudioDucker ducker) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(context, 8), dp(context, 2), dp(context, 8), 0);

        Switch enabled = new Switch(context);
        enabled.setText("VOICE DUCKING");
        enabled.setTextColor(Color.WHITE);
        enabled.setChecked(ducker.isEnabled());
        root.addView(enabled, new LinearLayout.LayoutParams(-1, dp(context, 48)));

        TextView help = text(context, "When your microphone level crosses the speech threshold, the loaded video is smoothly lowered. When you stop, it returns gradually.");
        help.setTextColor(Color.LTGRAY);
        help.setTextSize(12);
        help.setPadding(0, 0, 0, dp(context, 8));
        root.addView(help);

        TextView duckLabel = text(context, "DUCK LEVEL  •  " + percent(ducker.getDuckVolume()));
        root.addView(duckLabel);
        SeekBar duckBar = new SeekBar(context);
        duckBar.setMax(80);
        duckBar.setProgress(Math.round(ducker.getDuckVolume() * 100f));
        root.addView(duckBar, new LinearLayout.LayoutParams(-1, dp(context, 44)));

        TextView thresholdLabel = text(context, "VOICE THRESHOLD  •  " + percent(ducker.getThreshold()));
        root.addView(thresholdLabel);
        SeekBar thresholdBar = new SeekBar(context);
        thresholdBar.setMax(50);
        thresholdBar.setProgress(Math.round(ducker.getThreshold() * 100f));
        root.addView(thresholdBar, new LinearLayout.LayoutParams(-1, dp(context, 44)));

        TextView normalLabel = text(context, "NORMAL VIDEO LEVEL  •  " + percent(ducker.getNormalVolume()));
        root.addView(normalLabel);
        SeekBar normalBar = new SeekBar(context);
        normalBar.setMax(100);
        normalBar.setProgress(Math.round(ducker.getNormalVolume() * 100f));
        root.addView(normalBar, new LinearLayout.LayoutParams(-1, dp(context, 44)));

        enabled.setOnCheckedChangeListener((button, checked) -> ducker.setEnabled(checked));
        duckBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                float level = Math.max(0.05f, Math.min(0.80f, value / 100f));
                ducker.setDuckVolume(level);
                duckLabel.setText("DUCK LEVEL  •  " + percent(level));
            }
        });
        thresholdBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                float level = Math.max(0.03f, Math.min(0.50f, value / 100f));
                ducker.setThreshold(level);
                thresholdLabel.setText("VOICE THRESHOLD  •  " + percent(level));
            }
        });
        normalBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                float level = Math.max(0.10f, Math.min(1f, value / 100f));
                ducker.setNormalVolume(level);
                normalLabel.setText("NORMAL VIDEO LEVEL  •  " + percent(level));
            }
        });

        new MaterialAlertDialogBuilder(context)
                .setTitle("AUDIO MIXER • VOICE DUCKING")
                .setMessage("Mic → voice detector → duck loaded-video audio → smooth release")
                .setView(root)
                .setPositiveButton("DONE", null)
                .show();
    }

    private static TextView text(Context context, String value) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(Color.WHITE);
        view.setTextSize(12);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private static String percent(float value) { return Math.round(value * 100f) + "%"; }
    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar bar) {}
        @Override public void onStopTrackingTouch(SeekBar bar) {}
    }
}
