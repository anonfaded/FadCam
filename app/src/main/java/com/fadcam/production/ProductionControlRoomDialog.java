package com.fadcam.production;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;

/** Expandable professional TV production control room. */
public final class ProductionControlRoomDialog extends Dialog {
    public interface Listener {
        void onStateChanged(@NonNull ProductionControlState state, boolean applyToProgram);
        void onCloseRequested();
    }

    private static final int BG = Color.rgb(10, 10, 10);
    private static final int PANEL = Color.rgb(28, 28, 28);
    private static final int PANEL_ALT = Color.rgb(42, 42, 42);
    private static final int ACCENT = Color.rgb(220, 42, 48);
    private static final int TEXT = Color.WHITE;
    private static final int MUTED = Color.rgb(180, 180, 180);

    private final Listener listener;
    private ProductionControlState state;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView programValue;
    private TextView previewValue;
    private TextView transitionValue;
    private TextView durationValue;
    private TextView onAirValue;
    private MaterialButton takeButton;
    private MaterialButton autoButton;
    private MaterialButton streamButton;

    public ProductionControlRoomDialog(@NonNull Context context,
                                       @NonNull ProductionControlState initialState,
                                       @NonNull Listener listener) {
        super(context);
        this.state = initialState;
        this.listener = listener;
    }

    @Override
    protected void onCreate(@Nullable android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(buildContent());
        setCanceledOnTouchOutside(false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setDimAmount(0.72f);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }
    }

    @Override
    protected void onStop() {
        handler.removeCallbacksAndMessages(null);
        super.onStop();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.setBackground(round(BG, 18));

        LinearLayout header = row();
        TextView title = text("PRODUCTION CONTROL ROOM", 17, TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(46), 1));

        streamButton = button("LIVE", ProductionStreamingController.isLive(getContext()));
        streamButton.setContentDescription("Open live streaming destinations");
        streamButton.setOnClickListener(v -> showStreamingPanel());
        header.addView(streamButton, new LinearLayout.LayoutParams(dp(70), dp(46)));

        TextView live = text("● LIVE CONTROL", 10, ACCENT);
        live.setGravity(Gravity.CENTER);
        header.addView(live, new LinearLayout.LayoutParams(dp(92), dp(46)));

        MaterialButton close = button("×", false);
        close.setContentDescription("Close production control room");
        close.setOnClickListener(v -> {
            listener.onCloseRequested();
            dismiss();
        });
        header.addView(close, new LinearLayout.LayoutParams(dp(46), dp(46)));
        root.addView(header);

        LinearLayout status = row();
        status.setPadding(dp(8), dp(6), dp(8), dp(6));
        status.setBackground(round(PANEL, 12));
        onAirValue = text("ON AIR • " + state.getProgramScene().getTitle(), 11, TEXT);
        status.addView(onAirValue, new LinearLayout.LayoutParams(0, dp(34), 1));
        TextView ready = text(ProductionStreamingController.isLive(getContext()) ? "STREAMING" : "READY", 10,
                ProductionStreamingController.isLive(getContext()) ? Color.rgb(100, 230, 130) : Color.rgb(90, 220, 120));
        ready.setGravity(Gravity.CENTER);
        status.addView(ready, new LinearLayout.LayoutParams(dp(78), dp(34)));
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(46)));

        LinearLayout monitorRow = row();
        monitorRow.setPadding(0, dp(8), 0, dp(8));
        monitorRow.addView(monitorCard("PROGRAM", true), new LinearLayout.LayoutParams(0, dp(92), 1));
        monitorRow.addView(space(dp(8)));
        monitorRow.addView(monitorCard("PREVIEW", false), new LinearLayout.LayoutParams(0, dp(92), 1));
        root.addView(monitorRow);

        TextView multiTitle = text("MULTIVIEW", 11, MUTED);
        multiTitle.setPadding(dp(4), dp(2), dp(4), dp(4));
        root.addView(multiTitle);
        root.addView(buildMultiview(), new LinearLayout.LayoutParams(-1, dp(172)));

        TextView sourceTitle = text("SCENE BANK • PREVIEW", 11, MUTED);
        sourceTitle.setPadding(dp(4), dp(8), dp(4), dp(4));
        root.addView(sourceTitle);
        root.addView(buildSceneBank(), new LinearLayout.LayoutParams(-1, dp(62)));

        LinearLayout switchRow = row();
        switchRow.setPadding(0, dp(8), 0, dp(4));
        MaterialButton cut = button("CUT", true);
        cut.setOnClickListener(v -> takeNow());
        switchRow.addView(cut, new LinearLayout.LayoutParams(0, dp(52), 1));
        switchRow.addView(space(dp(8)));
        autoButton = button("AUTO", false);
        autoButton.setOnClickListener(v -> autoTake());
        switchRow.addView(autoButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        switchRow.addView(space(dp(8)));
        takeButton = button("TAKE", true);
        takeButton.setOnClickListener(v -> takeNow());
        switchRow.addView(takeButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(switchRow);

        LinearLayout transitionRow = row();
        transitionRow.setPadding(dp(4), dp(4), dp(4), dp(4));
        transitionValue = text("TRANSITION • " + state.getTransition().getTitle(), 11, TEXT);
        transitionRow.addView(transitionValue, new LinearLayout.LayoutParams(0, dp(38), 1));
        for (ProductionControlState.Transition transition : ProductionControlState.Transition.values()) {
            MaterialButton t = button(transition.getTitle().toUpperCase(), transition == state.getTransition());
            t.setTextSize(9);
            t.setOnClickListener(v -> {
                state = state.withTransition(transition);
                state.save(getContext());
                transitionValue.setText("TRANSITION • " + transition.getTitle());
            });
            transitionRow.addView(t, new LinearLayout.LayoutParams(dp(76), dp(38)));
        }
        root.addView(transitionRow);

        LinearLayout durationRow = row();
        durationRow.setPadding(dp(4), 0, dp(4), dp(2));
        durationValue = text("AUTO DURATION • " + state.getTransitionDurationMs() + " ms", 10, MUTED);
        durationRow.addView(durationValue, new LinearLayout.LayoutParams(dp(135), dp(40)));
        SeekBar duration = new SeekBar(getContext());
        duration.setMax(2900);
        duration.setProgress(state.getTransitionDurationMs() - 100);
        duration.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int ms = ProductionControlState.clampDuration(progress + 100);
                state = state.withDuration(ms);
                durationValue.setText("AUTO DURATION • " + ms + " ms");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) { state.save(getContext()); }
        });
        durationRow.addView(duration, new LinearLayout.LayoutParams(0, dp(40), 1));
        root.addView(durationRow);

        TextView hint = text("Select a scene to PREVIEW it. CUT/TAKE makes PREVIEW the live PROGRAM. AUTO respects the selected duration. LIVE opens the social output panel.", 9, MUTED);
        hint.setPadding(dp(6), dp(6), dp(6), dp(2));
        root.addView(hint);

        scroll.addView(root);
        return scroll;
    }

    private void showStreamingPanel() {
        ProductionStreamingDialog dialog = new ProductionStreamingDialog(getContext(), () -> refreshStreamingState());
        dialog.show();
    }

    private void refreshStreamingState() {
        boolean live = ProductionStreamingController.isLive(getContext());
        if (streamButton != null) {
            streamButton.setText(live ? "LIVE" : "STREAM");
            streamButton.setBackground(live ? round(Color.rgb(25, 90, 45), 12) : round(ACCENT, 12));
        }
        listener.onStateChanged(state, false);
    }

    private View monitorCard(String label, boolean program) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));
        card.setBackground(round(program ? Color.rgb(65, 18, 20) : PANEL_ALT, 12));
        TextView tag = text(label, 9, program ? ACCENT : Color.rgb(90, 180, 255));
        card.addView(tag, new LinearLayout.LayoutParams(-1, dp(22)));
        TextView value = text(program ? state.getProgramScene().getTitle() : state.getPreviewScene().getTitle(), 16, TEXT);
        value.setTypeface(null, android.graphics.Typeface.BOLD);
        if (program) programValue = value; else previewValue = value;
        card.addView(value, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView detail = text(program ? "ON AIR" : "READY TO TAKE", 8, MUTED);
        card.addView(detail, new LinearLayout.LayoutParams(-1, dp(18)));
        return card;
    }

    private View buildMultiview() {
        GridLayout grid = new GridLayout(getContext());
        grid.setColumnCount(3);
        grid.setRowCount(2);
        String[] names = {"CAMERA 1", "CAMERA 2", "VIDEO", "GRAPHICS", "PROGRAM", "PREVIEW"};
        for (String name : names) {
            TextView tile = text(name, 9, TEXT);
            tile.setGravity(Gravity.CENTER);
            tile.setBackground(round(name.equals("PROGRAM") ? Color.rgb(70, 18, 18) : PANEL, 8));
            tile.setContentDescription("Multiview source " + name);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = dp(80);
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            grid.addView(tile, lp);
        }
        return grid;
    }

    private View buildSceneBank() {
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout bank = row();
        for (ProductionScene scene : ProductionScene.values()) {
            MaterialButton source = button(scene.getTitle().toUpperCase(), scene == state.getPreviewScene());
            source.setTextSize(9);
            source.setContentDescription("Preview scene " + scene.getTitle());
            source.setOnClickListener(v -> {
                state = state.withPreview(scene);
                state.save(getContext());
                refreshValues();
            });
            bank.addView(source, new LinearLayout.LayoutParams(dp(112), dp(54)));
        }
        scroll.addView(bank);
        return scroll;
    }

    private void takeNow() {
        handler.removeCallbacksAndMessages(null);
        state = state.take();
        state.save(getContext());
        listener.onStateChanged(state, true);
        refreshValues();
        if (autoButton != null) autoButton.setEnabled(true);
        if (takeButton != null) takeButton.setEnabled(true);
    }

    private void autoTake() {
        if (autoButton != null) autoButton.setEnabled(false);
        if (takeButton != null) takeButton.setEnabled(false);
        int duration = state.getTransitionDurationMs();
        handler.postDelayed(() -> {
            if (!isShowing()) return;
            takeNow();
        }, duration);
    }

    private void refreshValues() {
        if (programValue != null) programValue.setText(state.getProgramScene().getTitle());
        if (previewValue != null) previewValue.setText(state.getPreviewScene().getTitle());
        if (onAirValue != null) onAirValue.setText("ON AIR • " + state.getProgramScene().getTitle());
        if (transitionValue != null) transitionValue.setText("TRANSITION • " + state.getTransition().getTitle());
    }

    private MaterialButton button(String title, boolean accent) {
        MaterialButton b = new MaterialButton(getContext());
        b.setText(title);
        b.setTextColor(TEXT);
        b.setTextSize(10);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setPadding(dp(6), 0, dp(6), 0);
        b.setBackground(accent ? round(ACCENT, 12) : round(PANEL_ALT, 12));
        return b;
    }

    private TextView text(String value, float size, int color) {
        TextView v = new TextView(getContext());
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private View space(int width) {
        View v = new View(getContext());
        v.setLayoutParams(new LinearLayout.LayoutParams(width, 1));
        return v;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }
}
