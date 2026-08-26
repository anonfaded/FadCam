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
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;

/**
 * Compact broadcast producer cockpit.
 *
 * The cockpit is deliberately a control surface, not a fake broadcast engine:
 * PROGRAM/PREVIEW are backed by ProductionControlState, output is backed by
 * the verified RTMP service, and the microphone meter is fed by the existing
 * recorder-owned AudioRecord through ProductionAudioDuckingBus. Media/master
 * faders also feed the playback-side ProductionAudioMixBus.
 */
public final class ProductionCockpitDialog extends Dialog {
    private static final int BG = Color.rgb(8, 9, 10);
    private static final int PANEL = Color.rgb(24, 26, 29);
    private static final int PANEL_2 = Color.rgb(35, 38, 42);
    private static final int PANEL_3 = Color.rgb(48, 51, 56);
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
    private TextView micState;
    private ProgressBar micMeter;
    private MaterialButton liveButton;
    private final MaterialButton[] sceneButtons = new MaterialButton[ProductionScene.values().length];
    private final MaterialButton[] cameraButtons = new MaterialButton[6];
    private final boolean[] muted = new boolean[4];
    private final int[] restoreLevels = {75, 75, 65, 85};

    private final ProductionAudioDuckingBus.Listener micListener = new ProductionAudioDuckingBus.Listener() {
        @Override public void onMicLevel(float normalizedLevel) {
            int progress = Math.max(0, Math.min(100, Math.round(normalizedLevel * 100f)));
            if (micMeter != null) micMeter.post(() -> micMeter.setProgress(progress));
        }

        @Override public void onCaptureState(boolean active) {
            if (micState != null) {
                micState.post(() -> micState.setText(active
                        ? "VOICE BUS • MIC ACTIVE • DUCKING TELEMETRY"
                        : "VOICE BUS • WAITING FOR RECORDER"));
            }
        }
    };

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
        ProductionAudioDuckingBus.subscribe(micListener);
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
            int width = getContext().getResources().getDisplayMetrics().widthPixels;
            float density = getContext().getResources().getDisplayMetrics().density;
            int max = Math.round(520 * density);
            lp.width = Math.min(Math.round(width * 0.94f), max);
            lp.height = WindowManager.LayoutParams.MATCH_PARENT;
            window.setAttributes(lp);
        }
    }

    @Override protected void onStop() {
        ProductionAudioDuckingBus.unsubscribe(micListener);
        super.onStop();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);

        LinearLayout root = column();
        root.setPadding(dp(12), dp(10), dp(12), dp(18));
        root.setBackground(round(BG, 18));

        LinearLayout header = row();
        TextView title = text("PRODUCER COCKPIT", 17, TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(46), 1));
        TextView mode = text("ON AIR READY", 8, GREEN);
        mode.setGravity(Gravity.CENTER);
        mode.setBackground(round(PANEL, 10));
        header.addView(mode, new LinearLayout.LayoutParams(dp(92), dp(38)));
        MaterialButton close = button("×", false);
        close.setContentDescription("Close producer cockpit");
        close.setOnClickListener(v -> dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(header);

        LinearLayout status = row();
        status.setPadding(dp(10), dp(5), dp(10), dp(5));
        status.setBackground(round(PANEL, 12));
        engine = text("SWITCHER READY • CUT BUS ARMED", 9, GREEN);
        status.addView(engine, new LinearLayout.LayoutParams(0, dp(34), 1));
        tally = text("PROGRAM", 8, RED);
        tally.setGravity(Gravity.CENTER);
        tally.setBackground(round(Color.rgb(62, 16, 19), 8));
        status.addView(tally, new LinearLayout.LayoutParams(dp(122), dp(30)));
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(44)));

        root.addView(section("PROGRAM / PREVIEW"));
        LinearLayout monitors = row();
        monitors.addView(monitor("PROGRAM", true), new LinearLayout.LayoutParams(0, dp(112), 1));
        monitors.addView(space(dp(8)), new LinearLayout.LayoutParams(dp(8), 1));
        monitors.addView(monitor("PREVIEW", false), new LinearLayout.LayoutParams(0, dp(112), 1));
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
            lp.height = dp(50);
            lp.columnSpec = GridLayout.spec(i % 2, 1f);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            scenes.addView(b, lp);
        }
        root.addView(scenes);

        root.addView(text("CAMERA SLOT", 8, MUTED));
        HorizontalScrollView cameraScroll = new HorizontalScrollView(getContext());
        cameraScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout cameraBank = row();
        for (int i = 0; i < 6; i++) {
            final int slot = i + 1;
            MaterialButton b = button("CAM " + slot, slot == switcher.getPreviewCameraSlot());
            b.setTextSize(8);
            b.setOnClickListener(v -> selectCameraSlot(slot));
            cameraButtons[i] = b;
            cameraBank.addView(b, new LinearLayout.LayoutParams(dp(72), dp(44)));
            if (i < 5) cameraBank.addView(space(dp(5)), new LinearLayout.LayoutParams(dp(5), 1));
        }
        cameraScroll.addView(cameraBank);
        root.addView(cameraScroll, new LinearLayout.LayoutParams(-1, dp(50)));

        LinearLayout takeRow = row();
        MaterialButton cut = button("CUT • INSTANT", true);
        cut.setContentDescription("Instant program cut");
        cut.setOnClickListener(v -> take(true));
        takeRow.addView(cut, new LinearLayout.LayoutParams(0, dp(60), 1));
        takeRow.addView(space(dp(8)), new LinearLayout.LayoutParams(dp(8), 1));
        MaterialButton auto = button("AUTO TAKE", false);
        auto.setContentDescription("Automatic timed transition");
        auto.setOnClickListener(v -> take(false));
        takeRow.addView(auto, new LinearLayout.LayoutParams(0, dp(60), 1));
        root.addView(takeRow);

        root.addView(section("TRANSITION BUS"));
        HorizontalScrollView transitionScroll = new HorizontalScrollView(getContext());
        transitionScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout transitions = row();
        for (ProductionCockpitState.Transition t : ProductionCockpitState.Transition.values()) {
            MaterialButton b = button(t.name(), t == cockpit.getTransition());
            b.setTextSize(8);
            b.setOnClickListener(v -> setTransition(t));
            transitions.addView(b, new LinearLayout.LayoutParams(dp(82), dp(44)));
            transitions.addView(space(dp(5)), new LinearLayout.LayoutParams(dp(5), 1));
        }
        transitionScroll.addView(transitions);
        root.addView(transitionScroll, new LinearLayout.LayoutParams(-1, dp(50)));

        SeekBar speed = new SeekBar(getContext());
        speed.setMax(1000);
        speed.setProgress(cockpit.getTransitionMs());
        TextView speedValue = text("TRANSITION SPEED • " + cockpit.getTransitionMs() + " ms", 9, MUTED);
        root.addView(speedValue, new LinearLayout.LayoutParams(-1, dp(28)));
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean fromUser) {
                cockpit = cockpit.transition(cockpit.getTransition(), p);
                speedValue.setText("TRANSITION SPEED • " + p + " ms");
                if (p > 0) {
                    switcher = switcher.withDuration(Math.max(100, p));
                    switcher.save(getContext());
                }
            }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {}
        });
        root.addView(speed, new LinearLayout.LayoutParams(-1, dp(38)));

        root.addView(section("VIRTUAL AUDIO MIXER"));
        root.addView(mixerRow("CAM 1", cockpit.getCamera1(), ProductionAudioMixBus.CAMERA_1));
        root.addView(mixerRow("CAM 2", cockpit.getCamera2(), ProductionAudioMixBus.CAMERA_2));
        root.addView(mixerRow("MEDIA", cockpit.getMedia(), ProductionAudioMixBus.MEDIA));
        root.addView(mixerRow("MASTER", cockpit.getMaster(), ProductionAudioMixBus.MASTER));

        LinearLayout voice = row();
        voice.setPadding(dp(8), dp(5), dp(8), dp(5));
        voice.setBackground(round(PANEL, 10));
        LinearLayout voiceText = column();
        micState = text("VOICE BUS • WAITING FOR RECORDER", 8, MUTED);
        voiceText.addView(micState, new LinearLayout.LayoutParams(-1, dp(22)));
        TextView note = text("Mic telemetry uses the existing recorder AudioRecord", 7, MUTED);
        voiceText.addView(note, new LinearLayout.LayoutParams(-1, dp(18)));
        voice.addView(voiceText, new LinearLayout.LayoutParams(0, dp(48), 1));
        micMeter = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
        micMeter.setMax(100);
        micMeter.setProgress(0);
        voice.addView(micMeter, new LinearLayout.LayoutParams(dp(90), dp(24)));
        root.addView(voice);

        root.addView(section("BROADCAST OUTPUT"));
        LinearLayout liveRow = row();
        boolean live = ProductionStreamingController.isLive(getContext());
        liveButton = button(live ? "● LIVE" : "LIVE / STREAM", live);
        liveButton.setOnClickListener(v -> showStreaming());
        liveRow.addView(liveButton, new LinearLayout.LayoutParams(0, dp(54), 1));
        liveRow.addView(space(dp(8)), new LinearLayout.LayoutParams(dp(8), 1));
        MaterialButton destination = button("DESTINATION", false);
        destination.setContentDescription("Open verified social destination settings");
        destination.setOnClickListener(v -> showStreaming());
        liveRow.addView(destination, new LinearLayout.LayoutParams(0, dp(54), 1));
        liveRow.addView(space(dp(8)), new LinearLayout.LayoutParams(dp(8), 1));
        MaterialButton stop = button("STOP", false);
        stop.setOnClickListener(v -> stopLive());
        liveRow.addView(stop, new LinearLayout.LayoutParams(0, dp(54), 1));
        root.addView(liveRow);
        TextView output = text("Verified RTMP/RTMPS • protected stream key • recording safety gate", 8, MUTED);
        output.setPadding(dp(4), dp(5), dp(4), dp(4));
        root.addView(output);

        root.addView(section("PRODUCER SAFETY / HEALTH"));
        root.addView(infoRow("Recording", ProductionStreamingController.hasRecording(getContext()) ? "ACTIVE" : "IDLE", ProductionStreamingController.hasRecording(getContext()) ? GREEN : AMBER));
        root.addView(infoRow("Program", sourceLabel(cockpit.getProgram(), false), RED));
        root.addView(infoRow("Destination", healthDestination(), ProductionStreamingController.hasStreamKey(getContext()) ? BLUE : AMBER));
        root.addView(infoRow("Switch path", "Preview → Program • " + cockpit.getTransition().name(), GREEN));

        scroll.addView(root);
        return scroll;
    }

    private String healthDestination() {
        if (!ProductionStreamingController.hasStreamKey(getContext())) return "NOT CONFIGURED";
        String server = ProductionStreamingController.getServer(getContext());
        return ProductionStreamingController.isRtmpServer(server) ? ProductionStreamingController.getPlatform(getContext()) : "INVALID RTMP";
    }

    private String sourceLabel(ProductionScene scene, boolean previewSource) {
        if (scene == ProductionScene.CAMERA) {
            int slot = previewSource ? switcher.getPreviewCameraSlot() : switcher.getProgramCameraSlot();
            return "Camera " + slot;
        }
        return scene.getTitle();
    }

    private void selectPreview(ProductionScene scene) {
        cockpit = cockpit.preview(scene);
        switcher = switcher.withPreview(scene);
        switcher.save(getContext());
        refresh();
    }

    private void selectCameraSlot(int slot) {
        switcher = switcher.withPreviewCameraSlot(slot);
        switcher.save(getContext());
        if (cockpit.getPreview() == ProductionScene.CAMERA) cockpit = cockpit.preview(ProductionScene.CAMERA);
        for (int i = 0; i < cameraButtons.length; i++) {
            if (cameraButtons[i] != null) cameraButtons[i].setBackground(round(i + 1 == slot ? RED : PANEL_2, 12));
        }
        refresh();
    }

    private void take(boolean instant) {
        if (instant || cockpit.getTransitionMs() == 0) {
            promoteProgram();
            return;
        }
        engine.setText("AUTO TAKE • " + cockpit.getTransitionMs() + " ms");
        engine.setTextColor(AMBER);
        engine.postDelayed(() -> {
            if (isShowing()) promoteProgram();
        }, cockpit.getTransitionMs());
    }

    private void promoteProgram() {
        cockpit = cockpit.take();
        switcher = switcher.withPreview(cockpit.getPreview()).take();
        switcher = switcher.withTransition(mapTransition(cockpit.getTransition())).withDuration(Math.max(100, cockpit.getTransitionMs()));
        switcher.save(getContext());
        refresh();
    }

    private ProductionControlState.Transition mapTransition(ProductionCockpitState.Transition transition) {
        switch (transition) {
            case MIX: return ProductionControlState.Transition.DISSOLVE;
            case FADE: return ProductionControlState.Transition.FADE;
            case WIPE: return ProductionControlState.Transition.WIPE;
            case FLASH: return ProductionControlState.Transition.FADE;
            case CUT:
            default: return ProductionControlState.Transition.CUT;
        }
    }

    private void setTransition(ProductionCockpitState.Transition transition) {
        int duration = transition == ProductionCockpitState.Transition.CUT || transition == ProductionCockpitState.Transition.FLASH
                ? 0 : Math.max(100, cockpit.getTransitionMs());
        cockpit = cockpit.transition(transition, duration);
        switcher = switcher.withTransition(mapTransition(transition)).withDuration(Math.max(100, duration));
        switcher.save(getContext());
        refresh();
    }

    private View mixerRow(String name, int initial, int channel) {
        LinearLayout row = row();
        TextView label = text(name, 9, TEXT);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(label, new LinearLayout.LayoutParams(dp(58), dp(44)));

        SeekBar fader = new SeekBar(getContext());
        fader.setMax(100);
        fader.setProgress(initial);
        TextView value = text(initial + "%", 8, MUTED);
        value.setGravity(Gravity.CENTER);
        row.addView(fader, new LinearLayout.LayoutParams(0, dp(44), 1));
        row.addView(value, new LinearLayout.LayoutParams(dp(44), dp(44)));

        MaterialButton mute = button("M", false);
        mute.setTextSize(8);
        mute.setContentDescription("Mute " + name);
        mute.setOnClickListener(v -> {
            muted[channel] = !muted[channel];
            if (muted[channel]) {
                restoreLevels[channel] = fader.getProgress();
                fader.setProgress(0);
                mute.setBackground(round(RED, 10));
            } else {
                fader.setProgress(Math.max(restoreLevels[channel], 0));
                mute.setBackground(round(PANEL_2, 10));
            }
        });
        row.addView(mute, new LinearLayout.LayoutParams(dp(44), dp(40)));

        fader.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean fromUser) {
                value.setText(p + "%");
                int c1 = cockpit.getCamera1(), c2 = cockpit.getCamera2(), media = cockpit.getMedia(), master = cockpit.getMaster();
                if (channel == ProductionAudioMixBus.CAMERA_1) c1 = p;
                else if (channel == ProductionAudioMixBus.CAMERA_2) c2 = p;
                else if (channel == ProductionAudioMixBus.MEDIA) media = p;
                else master = p;
                ProductionAudioMixBus.setLevel(channel, p);
                cockpit = cockpit.levels(c1, c2, media, master);
                if (fromUser && !muted[channel]) restoreLevels[channel] = p;
            }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {}
        });
        return row;
    }

    private void showStreaming() {
        ProductionStreamingDialog dialog = new ProductionStreamingDialog(getContext(), this::refreshLive);
        dialog.show();
    }

    private void refreshLive() {
        boolean live = ProductionStreamingController.isLive(getContext());
        if (liveButton != null) {
            liveButton.setText(live ? "● LIVE" : "LIVE / STREAM");
            liveButton.setBackground(round(live ? Color.rgb(30, 90, 45) : RED, 12));
        }
        if (engine != null) {
            engine.setText(live ? "ON AIR • SOCIAL OUTPUT ACTIVE" : "SWITCHER READY • CUT BUS ARMED");
            engine.setTextColor(live ? RED : GREEN);
        }
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
        TextView l = text(label + (isProgram ? " • TALLY" : " • READY"), 8, isProgram ? RED : GREEN);
        card.addView(l, new LinearLayout.LayoutParams(-1, dp(22)));
        ProductionScene scene = isProgram ? cockpit.getProgram() : cockpit.getPreview();
        TextView value = text(sourceLabel(scene, !isProgram), 15, TEXT);
        value.setTypeface(null, android.graphics.Typeface.BOLD);
        if (isProgram) program = value; else preview = value;
        card.addView(value, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView sub = text(scene.getDescription(), 7, MUTED);
        card.addView(sub, new LinearLayout.LayoutParams(-1, dp(26)));
        return card;
    }

    private View infoRow(String label, String value, int color) {
        LinearLayout row = row();
        row.setPadding(dp(8), dp(3), dp(8), dp(3));
        row.setBackground(round(PANEL, 8));
        row.addView(text(label, 8, MUTED), new LinearLayout.LayoutParams(0, dp(30), 1));
        TextView v = text(value, 8, color);
        v.setGravity(Gravity.CENTER);
        row.addView(v, new LinearLayout.LayoutParams(dp(170), dp(30)));
        return row;
    }

    private TextView section(String title) {
        TextView v = text(title, 10, MUTED);
        v.setTypeface(null, android.graphics.Typeface.BOLD);
        v.setPadding(dp(4), dp(10), dp(4), dp(4));
        return v;
    }

    private void refresh() {
        if (program != null) program.setText(sourceLabel(cockpit.getProgram(), false));
        if (preview != null) preview.setText(sourceLabel(cockpit.getPreview(), true));
        if (tally != null) tally.setText("PROGRAM • " + sourceLabel(cockpit.getProgram(), false));
        for (int i = 0; i < sceneButtons.length; i++) {
            if (sceneButtons[i] != null) sceneButtons[i].setBackground(round(ProductionScene.values()[i] == cockpit.getPreview() ? RED : PANEL_2, 12));
        }
        for (int i = 0; i < cameraButtons.length; i++) {
            if (cameraButtons[i] != null) cameraButtons[i].setBackground(round(i + 1 == switcher.getPreviewCameraSlot() ? RED : PANEL_2, 12));
        }
        if (engine != null) {
            boolean live = ProductionStreamingController.isLive(getContext());
            engine.setText(live ? "ON AIR • SOCIAL OUTPUT ACTIVE" : "SWITCHER READY • CUT BUS ARMED");
            engine.setTextColor(live ? RED : GREEN);
        }
    }

    private MaterialButton button(String title, boolean accent) {
        MaterialButton b = new MaterialButton(getContext());
        b.setText(title);
        b.setTextColor(TEXT);
        b.setTextSize(10);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setPadding(dp(5), 0, dp(5), 0);
        b.setBackground(round(accent ? RED : PANEL_2, 12));
        b.setRippleColor(android.content.res.ColorStateList.valueOf(PANEL_3));
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
