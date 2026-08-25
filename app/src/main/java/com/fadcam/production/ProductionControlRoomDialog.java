package com.fadcam.production;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;

import java.util.Locale;

/**
 * Broadcast-style producer switcher.
 *
 * The PROGRAM monitor is the real Duet Studio canvas, not a text label. Preview
 * selection never changes Program; CUT/TAKE/AUTO are the only promotion paths.
 */
public final class ProductionControlRoomDialog extends Dialog {
    public interface Listener {
        void onStateChanged(@NonNull ProductionControlState state, boolean applyToProgram);
        void onCameraSlotChanged(int slot, @NonNull String streamUrl);
        void onCloseRequested();
    }

    private static final int BG = Color.rgb(9, 10, 11);
    private static final int PANEL = Color.rgb(27, 28, 30);
    private static final int PANEL_ALT = Color.rgb(40, 42, 45);
    private static final int RED = Color.rgb(220, 42, 48);
    private static final int RED_DARK = Color.rgb(74, 19, 21);
    private static final int TEXT = Color.WHITE;
    private static final int MUTED = Color.rgb(175, 178, 183);
    private static final int BLUE = Color.rgb(85, 165, 245);
    private static final int GREEN = Color.rgb(90, 220, 120);

    private final Listener listener;
    private ProductionControlState state;
    private final FrameLayout liveCanvas;
    private final ViewGroup originalParent;
    private final ViewGroup.LayoutParams originalLayoutParams;
    private final int originalIndex;

    private FrameLayout monitor;
    private TextView previewValue;
    private TextView previewDetail;
    private TextView tallyValue;
    private TextView transitionValue;
    private TextView durationValue;
    private MaterialButton autoButton;
    private MaterialButton takeButton;
    private MaterialButton liveButton;
    private boolean canvasAttached;

    public ProductionControlRoomDialog(@NonNull Context context,
                                        @NonNull ProductionControlState initialState,
                                        @Nullable FrameLayout liveCanvas,
                                        @Nullable ViewGroup originalParent,
                                        @NonNull Listener listener) {
        super(context);
        this.state = initialState;
        this.liveCanvas = liveCanvas;
        this.originalParent = originalParent;
        this.originalLayoutParams = liveCanvas == null ? null : liveCanvas.getLayoutParams();
        this.originalIndex = originalParent == null || liveCanvas == null
                ? -1 : originalParent.indexOfChild(liveCanvas);
        this.listener = listener;
    }

    @Override
    protected void onCreate(@Nullable android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(buildContent());
        setCanceledOnTouchOutside(false);
        attachLiveCanvas();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setDimAmount(0.78f);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        }
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        LinearLayout root = column(BG);
        root.setPadding(dp(10), dp(10), dp(10), dp(18));

        LinearLayout header = row();
        TextView title = text("PRODUCTION CONTROL ROOM", 17, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(46), 1));

        liveButton = button(ProductionStreamingController.isLive(getContext()) ? "ON AIR" : "LIVE", true);
        liveButton.setContentDescription("Toggle live output");
        liveButton.setOnClickListener(v -> toggleLive());
        header.addView(liveButton, new LinearLayout.LayoutParams(dp(72), dp(46)));

        MaterialButton cockpit = button("⋮", true);
        cockpit.setContentDescription("Open producer cockpit");
        cockpit.setOnClickListener(v -> new ProductionCockpitDialog(getContext()).show());
        header.addView(cockpit, new LinearLayout.LayoutParams(dp(50), dp(46)));

        TextView live = text("● LIVE CONTROL", 10, RED);
        live.setGravity(Gravity.CENTER);
        header.addView(live, new LinearLayout.LayoutParams(dp(92), dp(46)));

        MaterialButton close = button("×", true);
        close.setContentDescription("Close production control room");
        close.setOnClickListener(v -> {
            listener.onCloseRequested();
            dismiss();
        });
        header.addView(close, new LinearLayout.LayoutParams(dp(46), dp(46)));
        root.addView(header);

        LinearLayout status = row();
        status.setPadding(dp(8), dp(5), dp(8), dp(5));
        status.setBackground(round(PANEL, 12));
        tallyValue = text("ON AIR • " + programLabel(), 11, TEXT);
        status.addView(tallyValue, new LinearLayout.LayoutParams(0, dp(36), 1));
        TextView ready = text(ProductionStreamingController.isLive(getContext()) ? "LIVE" : "READY",
                10, ProductionStreamingController.isLive(getContext()) ? RED : GREEN);
        ready.setGravity(Gravity.CENTER);
        status.addView(ready, new LinearLayout.LayoutParams(dp(58), dp(36)));
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(46)));

        monitor = new FrameLayout(getContext());
        monitor.setBackground(round(Color.BLACK, 10));
        monitor.setContentDescription("Live Program monitor");
        root.addView(monitor, new LinearLayout.LayoutParams(-1, dp(230)));

        TextView monitorLabel = text("PROGRAM • LIVE OUTPUT", 9, RED);
        monitorLabel.setBackgroundColor(0xAA000000);
        monitorLabel.setPadding(dp(7), dp(3), dp(7), dp(3));
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(-2, dp(24), Gravity.TOP | Gravity.START);
        monitor.addView(monitorLabel, mlp);

        LinearLayout previewCard = row();
        previewCard.setPadding(dp(9), dp(6), dp(9), dp(6));
        previewCard.setBackground(round(RED_DARK, 12));
        LinearLayout previewText = column();
        TextView previewTag = text("PREVIEW", 9, BLUE);
        previewText.addView(previewTag);
        previewValue = text(previewLabel(), 16, TEXT);
        previewValue.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        previewText.addView(previewValue);
        previewDetail = text(previewDetailLabel(), 8, MUTED);
        previewText.addView(previewDetail);
        previewCard.addView(previewText, new LinearLayout.LayoutParams(0, dp(64), 1));
        MaterialButton previewTake = button("TAKE", true);
        previewTake.setOnClickListener(v -> takeNow());
        previewCard.addView(previewTake, new LinearLayout.LayoutParams(dp(82), dp(52)));
        root.addView(previewCard, new LinearLayout.LayoutParams(-1, dp(78)));

        TextView multiTitle = text("MULTIVIEW • REMOTE CAMERA SLOTS", 11, MUTED);
        multiTitle.setPadding(dp(4), dp(7), dp(4), dp(4));
        root.addView(multiTitle);
        root.addView(buildMultiview(), new LinearLayout.LayoutParams(-1, dp(230)));

        TextView sourceTitle = text("SCENE BANK • PREVIEW ONLY", 11, MUTED);
        sourceTitle.setPadding(dp(4), dp(7), dp(4), dp(4));
        root.addView(sourceTitle);
        root.addView(buildSceneBank(), new LinearLayout.LayoutParams(-1, dp(62)));

        LinearLayout switchRow = row();
        switchRow.setPadding(0, dp(7), 0, dp(4));
        MaterialButton cut = button("CUT", true);
        cut.setContentDescription("Cut preview to program instantly");
        cut.setOnClickListener(v -> cutNow());
        switchRow.addView(cut, new LinearLayout.LayoutParams(0, dp(52), 1));
        switchRow.addView(space(dp(7)));
        autoButton = button("AUTO", true);
        autoButton.setContentDescription("Auto transition preview to program");
        autoButton.setOnClickListener(v -> autoTake());
        switchRow.addView(autoButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        switchRow.addView(space(dp(7)));
        takeButton = button("TAKE", true);
        takeButton.setContentDescription("Take preview to program using selected transition");
        takeButton.setOnClickListener(v -> takeNow());
        switchRow.addView(takeButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(switchRow);

        LinearLayout transitionRow = row();
        transitionRow.setPadding(dp(4), dp(4), dp(4), dp(2));
        transitionValue = text("TRANSITION • " + state.getTransition().getTitle(), 10, TEXT);
        transitionRow.addView(transitionValue, new LinearLayout.LayoutParams(0, dp(38), 1));
        for (ProductionControlState.Transition transition : ProductionControlState.Transition.values()) {
            MaterialButton t = button(transition.getTitle().toUpperCase(Locale.US),
                    transition == state.getTransition());
            t.setTextSize(8);
            t.setOnClickListener(v -> {
                state = state.withTransition(transition);
                state.save(getContext());
                transitionValue.setText("TRANSITION • " + transition.getTitle());
            });
            transitionRow.addView(t, new LinearLayout.LayoutParams(dp(72), dp(38)));
        }
        root.addView(transitionRow);

        LinearLayout durationRow = row();
        durationValue = text("AUTO DURATION • " + state.getTransitionDurationMs() + " ms", 9, MUTED);
        durationRow.addView(durationValue, new LinearLayout.LayoutParams(dp(132), dp(40)));
        SeekBar duration = new SeekBar(getContext());
        duration.setMax(2900);
        duration.setProgress(state.getTransitionDurationMs() - 100);
        duration.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int ms = ProductionControlState.clampDuration(progress + 100);
                state = state.withDuration(ms);
                durationValue.setText("AUTO DURATION • " + ms + " ms");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) { state.save(getContext()); }
        });
        durationRow.addView(duration, new LinearLayout.LayoutParams(0, dp(40), 1));
        root.addView(durationRow);

        TextView hint = text(
                "PREVIEW never changes PROGRAM. CUT is instant. TAKE uses the selected transition. "
                        + "AUTO waits for the selected duration. Camera slots are visitor inputs; "
                        + "connect the guest's actual stream URL before taking a slot live.",
                9, MUTED);
        hint.setPadding(dp(6), dp(7), dp(6), 0);
        root.addView(hint);

        scroll.addView(root);
        return scroll;
    }

    private View buildMultiview() {
        LinearLayout grid = column();
        int[] slots = {1, 2, 3, 4, 5, 6};
        for (int row = 0; row < 3; row++) {
            LinearLayout line = row();
            for (int col = 0; col < 3; col++) {
                int index = row * 3 + col;
                TextView tile;
                if (index < slots.length) {
                    tile = cameraTile(ProductionCameraSlotManager.get(getContext(), slots[index]));
                } else {
                    String name = index == 6 ? "VIDEO" : index == 7 ? "PROGRAM" : "PREVIEW";
                    tile = sourceTile(name, index == 7);
                    if ("VIDEO".equals(name)) {
                        tile.setOnClickListener(v -> {
                            state = state.withPreview(ProductionScene.VIDEO);
                            state.save(getContext());
                            refreshValues();
                        });
                    }
                }
                line.addView(tile, new LinearLayout.LayoutParams(0, dp(68), 1));
                if (col < 2) line.addView(space(dp(5)));
            }
            grid.addView(line);
            if (row < 2) grid.addView(space(dp(5)));
        }
        return grid;
    }

    private TextView cameraTile(@NonNull ProductionCameraSlot camera) {
        String status = camera.getStatus().name();
        String name = camera.getGuestName().isEmpty() ? "VISITOR " + camera.getSlot() : camera.getGuestName();
        TextView tile = text("CAMERA " + camera.getSlot() + "\n" + name + "\n" + status, 8,
                camera.getStatus() == ProductionCameraSlot.Status.CONNECTED ? GREEN : TEXT);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(round(camera.getSlot() == state.getPreviewCameraSlot()
                ? RED_DARK : PANEL, 8));
        tile.setContentDescription("Camera slot " + camera.getSlot() + " " + status);
        tile.setOnClickListener(v -> {
            state = state.withPreviewCameraSlot(camera.getSlot());
            state.save(getContext());
            refreshValues();
        });
        tile.setOnLongClickListener(v -> {
            showCameraActions(camera);
            return true;
        });
        return tile;
    }

    private TextView sourceTile(String name, boolean program) {
        TextView tile = text(name, 9, TEXT);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(round(program ? RED_DARK : PANEL, 8));
        tile.setContentDescription("Multiview source " + name);
        return tile;
    }

    private View buildSceneBank() {
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout bank = row();
        for (ProductionScene scene : ProductionScene.values()) {
            MaterialButton source = button(scene.getTitle().toUpperCase(Locale.US),
                    scene == state.getPreviewScene());
            source.setTextSize(8);
            source.setContentDescription("Preview scene " + scene.getTitle());
            source.setOnClickListener(v -> {
                state = state.withPreview(scene);
                state.save(getContext());
                refreshValues();
            });
            bank.addView(source, new LinearLayout.LayoutParams(dp(110), dp(54)));
        }
        scroll.addView(bank);
        return scroll;
    }

    private void showCameraActions(@NonNull ProductionCameraSlot camera) {
        LinearLayout content = column();
        content.setPadding(dp(6), dp(2), dp(6), 0);
        EditText name = new EditText(getContext());
        name.setHint("Visitor name");
        name.setText(camera.getGuestName());
        content.addView(name);
        EditText url = new EditText(getContext());
        url.setHint("Visitor HLS/HTTP stream URL");
        url.setSingleLine(true);
        url.setText(camera.getStreamUrl());
        content.addView(url);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(getContext())
                .setTitle("CAMERA " + camera.getSlot())
                .setMessage("Invite a visitor, then connect the visitor's real stream URL to this slot.")
                .setView(content)
                .setPositiveButton("CONNECT", (d, which) -> {
                    String value = url.getText().toString().trim();
                    if (!value.isEmpty() && !(value.startsWith("http://") || value.startsWith("https://"))) {
                        Toast.makeText(getContext(), "Use an HTTP/HTTPS stream URL", Toast.LENGTH_LONG).show();
                        return;
                    }
                    ProductionCameraSlot updated = ProductionCameraSlotManager.connect(
                            getContext(), camera.getSlot(), value);
                    ProductionCameraSlotManager.markInvited(getContext(), camera.getSlot(),
                            name.getText().toString());
                    listener.onCameraSlotChanged(updated.getSlot(), updated.getStreamUrl());
                    refreshValues();
                })
                .setNeutralButton("INVITE", (d, which) -> shareInvite(
                        camera.getSlot(), name.getText().toString()))
                .setNegativeButton("CLEAR", (d, which) -> {
                    ProductionCameraSlotManager.clear(getContext(), camera.getSlot());
                    listener.onCameraSlotChanged(camera.getSlot(), "");
                    refreshValues();
                })
                .show();
    }

    private void shareInvite(int slot, String guestName) {
        ProductionCameraSlotManager.markInvited(getContext(), slot, guestName);
        String link = ProductionCameraSlotManager.inviteLink(getContext(), slot);
        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(
                ClipData.newPlainText("FadCam Camera " + slot, link));

        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT,
                "Join FadCam Production Camera " + slot + "\n" + link
                        + "\n\nAfter joining, send your camera stream URL to the producer.");
        try {
            getContext().startActivity(Intent.createChooser(share, "Invite Camera " + slot));
        } catch (Exception ignored) {
            Toast.makeText(getContext(), "Invite link copied: " + link, Toast.LENGTH_LONG).show();
        }
        refreshValues();
    }

    private void toggleLive() {
        try {
            if (ProductionStreamingController.isLive(getContext())) {
                ProductionStreamingController.stop(getContext());
            } else {
                ProductionStreamingController.start(getContext());
            }
            liveButton.setText(ProductionStreamingController.isLive(getContext()) ? "ON AIR" : "LIVE");
        } catch (Exception e) {
            Toast.makeText(getContext(), e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void cutNow() {
        state = state.take().withTransition(ProductionControlState.Transition.CUT);
        state.save(getContext());
        listener.onStateChanged(state, true);
        refreshValues();
    }

    private void takeNow() {
        state = state.take();
        state.save(getContext());
        listener.onStateChanged(state, true);
        refreshValues();
    }

    private void autoTake() {
        if (autoButton != null) autoButton.setEnabled(false);
        if (takeButton != null) takeButton.setEnabled(false);
        if (state.getTransition() == ProductionControlState.Transition.CUT) {
            takeNow();
            reEnableSwitchButtons();
            return;
        }
        int duration = state.getTransitionDurationMs();
        getWindow().getDecorView().postDelayed(() -> {
            if (!isShowing()) return;
            takeNow();
            reEnableSwitchButtons();
        }, duration);
    }

    private void reEnableSwitchButtons() {
        if (autoButton != null) autoButton.setEnabled(true);
        if (takeButton != null) takeButton.setEnabled(true);
    }

    private void refreshValues() {
        if (previewValue != null) previewValue.setText(previewLabel());
        if (previewDetail != null) previewDetail.setText(previewDetailLabel());
        if (tallyValue != null) tallyValue.setText("ON AIR • " + programLabel());
    }

    private String programLabel() {
        return state.getProgramScene() == ProductionScene.CAMERA
                ? "Camera " + state.getProgramCameraSlot()
                : state.getProgramScene().getTitle();
    }

    private String previewLabel() {
        return state.getPreviewScene() == ProductionScene.CAMERA
                ? "Camera " + state.getPreviewCameraSlot()
                : state.getPreviewScene().getTitle();
    }

    private String previewDetailLabel() {
        ProductionCameraSlot camera = ProductionCameraSlotManager.get(
                getContext(), state.getPreviewCameraSlot());
        if (state.getPreviewScene() == ProductionScene.CAMERA) {
            return camera.getStatus().name() + " • " + (camera.getGuestName().isEmpty()
                    ? "visitor slot" : camera.getGuestName());
        }
        return state.getPreviewScene().getDescription();
    }

    private void attachLiveCanvas() {
        if (liveCanvas == null || monitor == null || liveCanvas.getParent() == null) return;
        ViewGroup parent = (ViewGroup) liveCanvas.getParent();
        if (parent != originalParent) return;
        parent.removeView(liveCanvas);
        monitor.addView(liveCanvas, new FrameLayout.LayoutParams(-1, -1));
        canvasAttached = true;
    }

    private void restoreLiveCanvas() {
        if (!canvasAttached || liveCanvas == null || originalParent == null) return;
        if (liveCanvas.getParent() instanceof ViewGroup) {
            ((ViewGroup) liveCanvas.getParent()).removeView(liveCanvas);
        }
        int index = originalIndex < 0 ? originalParent.getChildCount()
                : Math.min(originalIndex, originalParent.getChildCount());
        originalParent.addView(liveCanvas, index, originalLayoutParams);
        canvasAttached = false;
    }

    @Override
    public void dismiss() {
        restoreLiveCanvas();
        super.dismiss();
    }

    private LinearLayout column() { return column(Color.TRANSPARENT); }

    private LinearLayout column(int background) {
        LinearLayout v = new LinearLayout(getContext());
        v.setOrientation(LinearLayout.VERTICAL);
        if (background != Color.TRANSPARENT) v.setBackgroundColor(background);
        return v;
    }

    private LinearLayout row() {
        LinearLayout v = new LinearLayout(getContext());
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private MaterialButton button(String title, boolean filled) {
        MaterialButton b = new MaterialButton(getContext());
        b.setText(title);
        b.setTextColor(TEXT);
        b.setTextSize(10);
        b.setMinHeight(0);
        b.setAllCaps(false);
        b.setBackground(filled ? round(RED, 12) : round(PANEL_ALT, 12));
        return b;
    }

    private TextView text(String title, float size, int color) {
        TextView v = new TextView(getContext());
        v.setText(title);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    private View space(int width) {
        View v = new View(getContext());
        v.setLayoutParams(new LinearLayout.LayoutParams(width, 1));
        return v;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }
}
