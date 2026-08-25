package com.fadcam.production;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;

/**
 * Slide-out producer cockpit. It is intentionally secondary to the normal
 * production room: a small cockpit button opens this drawer from the right.
 * The cockpit controls the same Preview/Program state and verified RTMP
 * service used by the production room; it does not emulate platform APIs.
 */
public final class ProductionCockpitDialog extends Dialog {
    private static final int BG = Color.rgb(8, 9, 10);
    private static final int PANEL = Color.rgb(24, 26, 29);
    private static final int PANEL_2 = Color.rgb(35, 38, 42);
    private static final int RED = Color.rgb(225, 38, 45);
    private static final int GREEN = Color.rgb(80, 220, 120);
    private static final int AMBER = Color.rgb(240, 185, 70);
    private static final int BLUE = Color.rgb(75, 165, 245);
    private static final int TEXT = Color.WHITE;
    private static final int MUTED = Color.rgb(165, 170, 178);

    private ProductionCockpitState cockpit;
    private ProductionControlState switcher;
    private TextView program;
    private TextView preview;
    private TextView tally;
    private TextView engine;
    private TextView masterValue;
    private MaterialButton liveButton;
    private final MaterialButton[] sceneButtons = new MaterialButton[ProductionScene.values().length];

    public ProductionCockpitDialog(@NonNull Context context) {
        super(context);
        switcher = ProductionControlState.load(context);
        cockpit = ProductionCockpitState.defaults(switcher.getProgramScene());
    }

    @Override protected void onCreate(@Nullable android.os.Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(buildContent());
        setCanceledOnTouchOutside(true);
    }

    @Override protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setDimAmount(0.45f);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.gravity = Gravity.END;
            lp.width = (int) (getContext().getResources().getDisplayMetrics().widthPixels * 0.88f);
            lp.height = WindowManager.LayoutParams.MATCH_PARENT;
            window.setAttributes(lp);
        }
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(getContext());
        LinearLayout root = column();
        root.setPadding(dp(12), dp(12), dp(12), dp(16));
        root.setBackground(round(BG, 18));

        LinearLayout header = row();
        TextView title = text("PRODUCER COCKPIT", 17, TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView mode = text("BROADCAST", 9, GREEN);
        mode.setGravity(Gravity.CENTER);
        header.addView(mode, new LinearLayout.LayoutParams(dp(78), dp(44)));
        MaterialButton close = button("×", false);
        close.setContentDescription("Close producer cockpit");
        close.setOnClickListener(v -> dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(header);

        LinearLayout status = row();
        status.setPadding(dp(10), dp(6), dp(10), dp(6));
        status.setBackground(round(PANEL, 12));
        engine = text("SWITCHER READY • LOW-LATENCY CUT BUS", 9, GREEN);
        status.addView(engine, new LinearLayout.LayoutParams(0, dp(32), 1));
        tally = text("PROGRAM", 9, RED);
        tally.setGravity(Gravity.CENTER);
        status.addView(tally, new LinearLayout.LayoutParams(dp(80), dp(32)));
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(44)));

        root.addView(section("PROGRAM / PREVIEW"));
        LinearLayout monitors = row();
        monitors.addView(monitor("PROGRAM", true), new LinearLayout.LayoutParams(0, dp(100), 1));
        monitors.addView(space(dp(8)), new LinearLayout.LayoutParams(dp(8), 1));
        monitors.addView(monitor("PREVIEW", false), new LinearLayout.LayoutParams(0, dp(100), 1));
        root.addView(monitors);

        root.addView(section("SCENE / SOURCE BANK"));
        GridLayout scenes = new GridLayout(getContext());
        scenes.setColumnCount(2);
        ProductionScene[] values = ProductionScene.values();
        for (int i = 0; i < values.length; i++) {
            ProductionScene scene = values[i];
            MaterialButton b = button(scene.getTitle().toUpperCase(), scene == cockpit.getPreview());
            b.setTextSize(9);
            b.setContentDescription("Preview " + scene.getTitle());
            b.setOnClickListener(v -> selectPreview(scene));
            sceneButtons[i] = b;
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = dp(48);
            lp.columnSpec = GridLayout.spec(i % 2, 1f);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            scenes.addView(b, lp);
        }
        root.addView(scenes);

        LinearLayout takeRow = row();
        MaterialButton cut = button("CUT • INSTANT", true);
        cut.setContentDescription("Instant program cut");
        cut.setOnClickListener(v -> take(true));
        takeRow.addView(cut, new LinearLayout.LayoutParams(0, dp(58), 1));
        takeRow.addView(space(dp(8)), new LinearLayout.LayoutParams(dp(8), 1));
        MaterialButton auto = button("AUTO", false);
        auto.setOnClickListener(v -> take(false));
        takeRow.addView(auto, new LinearLayout.LayoutParams(0, dp(58), 1));
        root.addView(takeRow);

        root.addView(section("TRANSITION BUS"));
        LinearLayout transitions = row();
        for (ProductionCockpitState.Transition t : ProductionCockpitState.Transition.values()) {
            MaterialButton b = button(t.name(), t == cockpit.getTransition());
            b.setTextSize(8);
            b.setOnClickListener(v -> setTransition(t));
            transitions.addView(b, new LinearLayout.LayoutParams(0, dp(42), 1));
        }
        root.addView(transitions);

        SeekBar speed = new SeekBar(getContext());
        speed.setMax(1000);
        speed.setProgress(cockpit.getTransitionMs());
        TextView speedValue = text("TRANSITION SPEED • " + cockpit.getTransitionMs() + " ms", 9, MUTED);
        root.addView(speedValue, new LinearLayout.LayoutParams(-1, dp(30)));
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean fromUser) {
                cockpit = cockpit.transition(cockpit.getTransition(), p);
                speedValue.setText("TRANSITION SPEED • " + p + " ms");
            }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {}
        });
        root.addView(speed, new LinearLayout.LayoutParams(-1, dp(40)));

        root.addView(section("VIRTUAL AUDIO MIXER"));
        root.addView(mixerRow("CAM 1", cockpit.getCamera1(), 0));
        root.addView(mixerRow("CAM 2", cockpit.getCamera2(), 1));
        root.addView(mixerRow("MEDIA", cockpit.getMedia(), 2));
        root.addView(mixerRow("MASTER", cockpit.getMaster(), 3));

        root.addView(section("BROADCAST OUTPUT"));
        LinearLayout liveRow = row();
        liveButton = button(ProductionStreamingController.isLive(getContext()) ? "● LIVE" : "LIVE / STREAM", ProductionStreamingController.isLive(getContext()));
        liveButton.setOnClickListener(v -> showStreaming());
        liveRow.addView(liveButton, new LinearLayout.LayoutParams(0, dp(54), 1));
        liveRow.addView(space(dp(8)), new LinearLayout.LayoutParams(dp(8), 1));
        MaterialButton stop = button("STOP", false);
        stop.setOnClickListener(v -> stopLive());
        liveRow.addView(stop, new LinearLayout.LayoutParams(0, dp(54), 1));
        root.addView(liveRow);
        TextView output = text("YouTube • Facebook • Twitch • Custom RTMP\nProtected stream key • RTMP/RTMPS validation • recording safety gate", 9, MUTED);
        output.setPadding(dp(4), dp(6), dp(4), dp(4));
        root.addView(output);

        root.addView(section("PRODUCER SAFETY / HEALTH"));
        root.addView(infoRow("Recording", ProductionStreamingController.hasRecording(getContext()) ? "ACTIVE" : "IDLE", ProductionStreamingController.hasRecording(getContext()) ? GREEN : AMBER));
        root.addView(infoRow("Program", cockpit.getProgram().getTitle(), RED));
        root.addView(infoRow("Destination", ProductionStreamingController.getPlatform(getContext()), BLUE));
        root.addView(infoRow("Switch path", "Preview → Program • synchronous CUT", GREEN));

        scroll.addView(root);
        return scroll;
    }

    private void selectPreview(ProductionScene scene) {
        cockpit = cockpit.preview(scene);
        switcher = switcher.withPreview(scene);
        switcher.save(getContext());
        refresh();
    }

    private void take(boolean instant) {
        if (instant || cockpit.getTransitionMs() == 0) {
            cockpit = cockpit.take();
            switcher = switcher.withPreview(cockpit.getPreview()).take();
            switcher.save(getContext());
            refresh();
            return;
        }
        engine.setText("AUTO TRANSITION • " + cockpit.getTransitionMs() + " ms");
        engine.setTextColor(AMBER);
        engine.postDelayed(() -> {
            if (!isShowing()) return;
            cockpit = cockpit.take();
            switcher = switcher.withPreview(cockpit.getPreview()).take();
            switcher.save(getContext());
            refresh();
        }, cockpit.getTransitionMs());
    }

    private void setTransition(ProductionCockpitState.Transition transition) {
        int duration = transition == ProductionCockpitState.Transition.CUT || transition == ProductionCockpitState.Transition.FLASH ? 0 : cockpit.getTransitionMs();
        cockpit = cockpit.transition(transition, duration);
        refresh();
    }

    private View mixerRow(String name, int initial, int channel) {
        LinearLayout row = row();
        TextView label = text(name, 9, TEXT);
        row.addView(label, new LinearLayout.LayoutParams(dp(58), dp(42)));
        SeekBar fader = new SeekBar(getContext());
        fader.setMax(100);
        fader.setProgress(initial);
        TextView value = text(initial + "%", 8, MUTED);
        value.setGravity(Gravity.CENTER);
        row.addView(fader, new LinearLayout.LayoutParams(0, dp(42), 1));
        row.addView(value, new LinearLayout.LayoutParams(dp(48), dp(42)));
        fader.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean fromUser) {
                value.setText(p + "%");
                int c1 = cockpit.getCamera1(), c2 = cockpit.getCamera2(), media = cockpit.getMedia(), master = cockpit.getMaster();
                if (channel == 0) c1 = p; else if (channel == 1) c2 = p; else if (channel == 2) media = p; else master = p;
                cockpit = cockpit.levels(c1, c2, media, master);
                if (channel == 3 && masterValue != null) masterValue.setText("MASTER " + p + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {}
        });
        return row;
    }

    private void showStreaming() {
        ProductionStreamingDialog dialog = new ProductionStreamingDialog(getContext(), () -> refreshLive());
        dialog.show();
    }

    private void refreshLive() {
        boolean live = ProductionStreamingController.isLive(getContext());
        liveButton.setText(live ? "● LIVE" : "LIVE / STREAM");
        liveButton.setBackground(round(live ? Color.rgb(30, 90, 45) : RED, 12));
        engine.setText(live ? "ON AIR • SOCIAL OUTPUT ACTIVE" : "SWITCHER READY • LOW-LATENCY CUT BUS");
        engine.setTextColor(live ? RED : GREEN);
    }

    private void stopLive() {
        ProductionStreamingController.stop(getContext());
        refreshLive();
        Toast.makeText(getContext(), "LIVE output stop requested", Toast.LENGTH_SHORT).show();
    }

    private View monitor(String label, boolean isProgram) {
        LinearLayout card = column();
        card.setPadding(dp(10), dp(8), dp(10), dp(8));
        card.setBackground(round(isProgram ? Color.rgb(68, 16, 19) : PANEL_2, 12));
        TextView l = text(label, 8, isProgram ? RED : BLUE);
        card.addView(l, new LinearLayout.LayoutParams(-1, dp(20)));
        TextView value = text(isProgram ? cockpit.getProgram().getTitle() : cockpit.getPreview().getTitle(), 15, TEXT);
        value.setTypeface(null, android.graphics.Typeface.BOLD);
        if (isProgram) program = value; else preview = value;
        card.addView(value, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView sub = text(isProgram ? "ON AIR • TALLY RED" : "READY • TALLY GREEN", 8, MUTED);
        card.addView(sub, new LinearLayout.LayoutParams(-1, dp(18)));
        return card;
    }

    private View infoRow(String label, String value, int color) {
        LinearLayout row = row();
        row.setPadding(dp(8), dp(3), dp(8), dp(3));
        row.setBackground(round(PANEL, 8));
        row.addView(text(label, 8, MUTED), new LinearLayout.LayoutParams(0, dp(30), 1));
        TextView v = text(value, 8, color);
        v.setGravity(Gravity.CENTER);
        row.addView(v, new LinearLayout.LayoutParams(dp(150), dp(30)));
        return row;
    }

    private TextView section(String title) {
        TextView v = text(title, 10, MUTED);
        v.setTypeface(null, android.graphics.Typeface.BOLD);
        v.setPadding(dp(4), dp(10), dp(4), dp(4));
        return v;
    }

    private void refresh() {
        if (program != null) program.setText(cockpit.getProgram().getTitle());
        if (preview != null) preview.setText(cockpit.getPreview().getTitle());
        if (tally != null) tally.setText("PROGRAM • " + cockpit.getProgram().getTitle());
        for (int i = 0; i < sceneButtons.length; i++) {
            if (sceneButtons[i] != null) sceneButtons[i].setBackground(round(i < ProductionScene.values().length && ProductionScene.values()[i] == cockpit.getPreview() ? RED : PANEL_2, 12));
        }
        if (engine != null) {
            engine.setText(ProductionStreamingController.isLive(getContext()) ? "ON AIR • SOCIAL OUTPUT ACTIVE" : "SWITCHER READY • LOW-LATENCY CUT BUS");
            engine.setTextColor(ProductionStreamingController.isLive(getContext()) ? RED : GREEN);
        }
    }

    private MaterialButton button(String title, boolean accent) {
        MaterialButton b = new MaterialButton(getContext());
        b.setText(title);
        b.setTextColor(TEXT);
        b.setTextSize(10);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setPadding(dp(5), 0, dp(5), 0);
        b.setBackground(round(accent ? RED : PANEL_2, 12));
        return b;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout column() {
        LinearLayout c = new LinearLayout(getContext());
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    private TextView text(String value, float size, int color) {
        TextView v = new TextView(getContext());
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    private View space(int width) {
        View v = new View(getContext());
        v.setLayoutParams(new LinearLayout.LayoutParams(width, 1));
        return v;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private int dp(int value) { return Math.round(value * getContext().getResources().getDisplayMetrics().density); }
}
