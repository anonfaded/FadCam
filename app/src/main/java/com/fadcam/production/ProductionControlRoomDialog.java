package com.fadcam.production;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Broadcast-style producer switcher. Preview is isolated from Program; CUT/TAKE/AUTO
 * are the only promotion paths. Camera tiles generate one-use FadCam guest links and
 * can immediately be used as real WebRTC program sources. VIDEO opens the device's
 * indexed video library and loads the selected clip into the real production canvas.
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
    private final android.widget.FrameLayout liveCanvas;
    private final ViewGroup originalParent;
    private final ViewGroup.LayoutParams originalLayoutParams;
    private final int originalIndex;
    private ProductionGuestWebView guestReceiver;
    private ExoPlayer videoPlayer;
    private ProductionAudioDucker videoDucker;

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
                                       @Nullable android.widget.FrameLayout liveCanvas,
                                       @Nullable ViewGroup originalParent,
                                       @NonNull Listener listener) {
        super(context);
        this.state = initialState;
        this.liveCanvas = liveCanvas;
        this.originalParent = originalParent;
        this.originalLayoutParams = liveCanvas == null ? null : liveCanvas.getLayoutParams();
        this.originalIndex = originalParent == null || liveCanvas == null ? -1 : originalParent.indexOfChild(liveCanvas);
        this.listener = listener;
    }

    @Override protected void onCreate(@Nullable android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(buildContent());
        setCanceledOnTouchOutside(false);
        attachLiveCanvas();
        syncGuestReceiver();
    }

    @Override protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setDimAmount(0.78f);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
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
        liveButton.setOnClickListener(v -> toggleLive());
        header.addView(liveButton, new LinearLayout.LayoutParams(dp(78), dp(46)));
        MaterialButton cockpit = button("⋮", true);
        cockpit.setOnClickListener(v -> new ProductionCockpitDialog(getContext()).show());
        header.addView(cockpit, new LinearLayout.LayoutParams(dp(50), dp(46)));
        TextView live = text("● LIVE CONTROL", 10, RED);
        live.setGravity(Gravity.CENTER);
        header.addView(live, new LinearLayout.LayoutParams(dp(92), dp(46)));
        MaterialButton close = button("×", true);
        close.setOnClickListener(v -> { listener.onCloseRequested(); dismiss(); });
        header.addView(close, new LinearLayout.LayoutParams(dp(46), dp(46)));
        root.addView(header);

        LinearLayout status = row();
        status.setPadding(dp(8), dp(5), dp(8), dp(5));
        status.setBackground(round(PANEL, 12));
        tallyValue = text("ON AIR • " + programLabel(), 11, TEXT);
        status.addView(tallyValue, new LinearLayout.LayoutParams(0, dp(36), 1));
        TextView ready = text(ProductionStreamingController.isLive(getContext()) ? "LIVE" : "READY", 10,
                ProductionStreamingController.isLive(getContext()) ? RED : GREEN);
        ready.setGravity(Gravity.CENTER);
        status.addView(ready, new LinearLayout.LayoutParams(dp(58), dp(36)));
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(46)));

        root.addView(section("PROGRAM / PREVIEW"));
        LinearLayout monitors = row();
        monitors.addView(monitor("PROGRAM", true), new LinearLayout.LayoutParams(0, dp(92), 1));
        monitors.addView(space(dp(8)), new LinearLayout.LayoutParams(dp(8), 1));
        monitors.addView(monitor("PREVIEW", false), new LinearLayout.LayoutParams(0, dp(92), 1));
        root.addView(monitors);

        root.addView(section("MULTIVIEW • REMOTE CAMERA SLOTS"));
        root.addView(buildMultiview(), new LinearLayout.LayoutParams(-1, dp(230)));

        root.addView(section("SCENE BANK • PREVIEW ONLY"));
        root.addView(buildSceneBank(), new LinearLayout.LayoutParams(-1, dp(62)));

        LinearLayout switchRow = row();
        switchRow.setPadding(0, dp(7), 0, dp(4));
        MaterialButton cut = button("CUT • INSTANT", true);
        cut.setOnClickListener(v -> cutNow());
        switchRow.addView(cut, new LinearLayout.LayoutParams(0, dp(54), 1));
        switchRow.addView(space(dp(7)));
        autoButton = button("AUTO", true);
        autoButton.setOnClickListener(v -> autoTake());
        switchRow.addView(autoButton, new LinearLayout.LayoutParams(0, dp(54), 1));
        switchRow.addView(space(dp(7)));
        takeButton = button("TAKE", true);
        takeButton.setOnClickListener(v -> takeNow());
        switchRow.addView(takeButton, new LinearLayout.LayoutParams(0, dp(54), 1));
        root.addView(switchRow);

        LinearLayout transitionRow = row();
        transitionValue = text("TRANSITION • " + state.getTransition().getTitle(), 9, TEXT);
        transitionRow.addView(transitionValue, new LinearLayout.LayoutParams(0, dp(38), 1));
        for (ProductionControlState.Transition transition : ProductionControlState.Transition.values()) {
            MaterialButton t = button(transition.getTitle().toUpperCase(Locale.US), transition == state.getTransition());
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
        duration.setProgress(Math.max(0, state.getTransitionDurationMs() - 100));
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

        TextView hint = text("PREVIEW never changes PROGRAM. Camera invites are unique and one-use. "
                + "VIDEO loads real local media into the production canvas; CUT/TAKE/AUTO are the promotion paths.", 8, MUTED);
        hint.setPadding(dp(6), dp(7), dp(6), 0);
        root.addView(hint);
        scroll.addView(root);
        return scroll;
    }

    private View buildMultiview() {
        GridLayout grid = new GridLayout(getContext());
        grid.setColumnCount(3);
        for (int i = 1; i <= 6; i++) {
            final ProductionCameraSlot camera = ProductionCameraSlotManager.get(getContext(), i);
            TextView tile = cameraTile(camera);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0; lp.height = dp(68); lp.columnSpec = GridLayout.spec((i - 1) % 3, 1f);
            lp.rowSpec = GridLayout.spec((i - 1) / 3, 1f);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            grid.addView(tile, lp);
        }
        TextView video = sourceTile("VIDEO\nLOAD FROM STORAGE", false);
        video.setOnClickListener(v -> showVideoPicker());
        GridLayout.LayoutParams vp = new GridLayout.LayoutParams();
        vp.width = 0; vp.height = dp(68); vp.columnSpec = GridLayout.spec(0, 1f); vp.rowSpec = GridLayout.spec(2, 1f);
        vp.setMargins(dp(3), dp(3), dp(3), dp(3));
        grid.addView(video, vp);
        TextView program = sourceTile("PROGRAM", true);
        GridLayout.LayoutParams pp = new GridLayout.LayoutParams();
        pp.width = 0; pp.height = dp(68); pp.columnSpec = GridLayout.spec(1, 1f); pp.rowSpec = GridLayout.spec(2, 1f);
        pp.setMargins(dp(3), dp(3), dp(3), dp(3)); grid.addView(program, pp);
        TextView preview = sourceTile("PREVIEW", false);
        GridLayout.LayoutParams pr = new GridLayout.LayoutParams();
        pr.width = 0; pr.height = dp(68); pr.columnSpec = GridLayout.spec(2, 1f); pr.rowSpec = GridLayout.spec(2, 1f);
        pr.setMargins(dp(3), dp(3), dp(3), dp(3)); grid.addView(preview, pr);
        return grid;
    }

    private TextView cameraTile(@NonNull ProductionCameraSlot camera) {
        String name = camera.getGuestName().isEmpty() ? "VISITOR " + camera.getSlot() : camera.getGuestName();
        String stateText = camera.getStatus().name();
        TextView tile = text("CAMERA " + camera.getSlot() + "\n" + name + "\n" + stateText, 8,
                camera.getStatus() == ProductionCameraSlot.Status.CONNECTED ? GREEN : TEXT);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(round(camera.getSlot() == state.getPreviewCameraSlot() && state.getPreviewScene() == ProductionScene.CAMERA
                ? RED_DARK : PANEL, 8));
        tile.setContentDescription("Camera " + camera.getSlot() + " guest invite and preview controls");
        tile.setOnClickListener(v -> showCameraInvite(camera.getSlot()));
        return tile;
    }

    private TextView sourceTile(String name, boolean program) {
        TextView tile = text(name, 9, TEXT);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(round(program ? RED_DARK : PANEL, 8));
        return tile;
    }

    private View buildSceneBank() {
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout bank = row();
        for (ProductionScene scene : ProductionScene.values()) {
            MaterialButton source = button(scene.getTitle().toUpperCase(Locale.US), scene == state.getPreviewScene());
            source.setTextSize(8);
            source.setOnClickListener(v -> {
                state = state.withPreview(scene);
                state.save(getContext());
                syncGuestReceiver();
                refreshValues();
            });
            bank.addView(source, new LinearLayout.LayoutParams(dp(110), dp(54)));
        }
        scroll.addView(bank);
        return scroll;
    }

    private void showCameraInvite(int slot) {
        ProductionCameraSlot camera = ProductionCameraSlotManager.createInvite(getContext(), slot, "");
        String link = ProductionCameraSlotManager.inviteLink(getContext(), slot);
        String fallback = ProductionCameraSlotManager.vdoPushLink(getContext(), slot);
        LinearLayout content = column();
        content.setPadding(dp(6), 0, dp(6), 0);
        EditTextShim name = new EditTextShim(getContext());
        name.setHint("Guest / visitor name (optional)");
        name.setText(camera.getGuestName());
        content.addView(name);
        TextView linkView = text(link, 9, MUTED);
        linkView.setPadding(0, dp(8), 0, dp(8));
        content.addView(linkView);
        new MaterialAlertDialogBuilder(getContext())
                .setTitle("CAMERA " + slot + " • GUEST INVITE")
                .setMessage("One-use FadCam app link. If the guest has FadCam installed, it opens Camera " + slot + " directly. The fallback is the real VDO.Ninja push link for guests without the app.")
                .setView(content)
                .setPositiveButton("SHARE LINK", (d, w) -> shareInvite(slot, name.getText().toString(), link, fallback))
                .setNeutralButton("USE AS PREVIEW", (d, w) -> {
                    ProductionCameraSlotManager.markInvited(getContext(), slot, name.getText().toString());
                    state = state.withPreviewCameraSlot(slot);
                    state.save(getContext());
                    listener.onCameraSlotChanged(slot, "");
                    refreshValues();
                })
                .setNegativeButton("CLOSE", null)
                .show();
    }

    private void shareInvite(int slot, String guestName, String link, String fallback) {
        ProductionCameraSlotManager.markInvited(getContext(), slot, guestName);
        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("FadCam Camera " + slot, link));
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, "Join FadCam Production Camera " + slot + "\n\n" + link
                + "\n\nFallback browser camera link:\n" + fallback);
        try { getContext().startActivity(Intent.createChooser(share, "Invite Camera " + slot)); }
        catch (Exception ignored) { Toast.makeText(getContext(), "Guest link copied", Toast.LENGTH_SHORT).show(); }
        refreshValues();
    }

    private void showVideoPicker() {
        String[] projection = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION};
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        List<Uri> uris = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        try (Cursor cursor = getContext().getContentResolver().query(collection, projection, null, null,
                MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (cursor != null) {
                int count = 0;
                while (cursor.moveToNext() && count++ < 30) {
                    long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID));
                    String name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME));
                    long duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION));
                    uris.add(Uri.withAppendedPath(collection, String.valueOf(id)));
                    labels.add(name + "  •  " + formatDuration(duration));
                }
            }
        } catch (Exception e) {
            Toast.makeText(getContext(), "Could not read device videos", Toast.LENGTH_LONG).show();
            return;
        }
        if (uris.isEmpty()) {
            Toast.makeText(getContext(), "No videos found in device storage", Toast.LENGTH_LONG).show();
            return;
        }
        new MaterialAlertDialogBuilder(getContext())
                .setTitle("LOAD VIDEO • STORAGE")
                .setItems(labels.toArray(new String[0]), (d, which) -> loadVideo(uris.get(which)))
                .setNegativeButton("CANCEL", null).show();
    }

    private void loadVideo(@NonNull Uri uri) {
        PlayerView playerView = findPlayerView(liveCanvas);
        if (playerView == null) {
            Toast.makeText(getContext(), "Production video canvas is unavailable", Toast.LENGTH_LONG).show();
            return;
        }
        if (videoDucker == null) videoDucker = new ProductionAudioDucker();
        if (videoPlayer != null) videoPlayer.release();
        videoPlayer = new ExoPlayer.Builder(getContext()).build();
        videoPlayer.setMediaItem(MediaItem.fromUri(uri));
        videoPlayer.prepare();
        videoPlayer.setPlayWhenReady(true);
        playerView.setPlayer(videoPlayer);
        videoDucker.start();
        videoDucker.attach(videoPlayer);
        state = state.withPreview(ProductionScene.VIDEO);
        state.save(getContext());
        syncGuestReceiver();
        refreshValues();
        Toast.makeText(getContext(), "VIDEO LOADED • READY FOR TAKE", Toast.LENGTH_SHORT).show();
    }

    private PlayerView findPlayerView(View view) {
        if (view instanceof PlayerView) return (PlayerView) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            PlayerView found = findPlayerView(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private String formatDuration(long ms) {
        long total = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60);
    }

    private void syncGuestReceiver() {
        if (liveCanvas == null) return;
        boolean needsGuest = state.getProgramScene() == ProductionScene.CAMERA
                || state.getProgramScene() == ProductionScene.DUET_PIP
                || state.getProgramScene() == ProductionScene.DUET_SPLIT
                || state.getProgramScene() == ProductionScene.COMMENTARY
                || state.getProgramScene() == ProductionScene.INTERVIEW;
        if (!needsGuest) {
            if (guestReceiver != null) guestReceiver.hide();
            return;
        }
        if (guestReceiver == null) guestReceiver = ProductionGuestWebView.obtain(getContext(), liveCanvas);
        guestReceiver.applyScene(state.getProgramScene());
        guestReceiver.loadSlot(state.getProgramCameraSlot());
    }

    private void toggleLive() {
        try {
            if (ProductionStreamingController.isLive(getContext())) ProductionStreamingController.stop(getContext());
            else ProductionStreamingController.start(getContext());
            liveButton.setText(ProductionStreamingController.isLive(getContext()) ? "ON AIR" : "LIVE");
        } catch (Exception e) { Toast.makeText(getContext(), e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private void cutNow() {
        state = state.take().withTransition(ProductionControlState.Transition.CUT);
        state.save(getContext());
        syncGuestReceiver();
        listener.onStateChanged(state, true);
        refreshValues();
    }

    private void takeNow() {
        state = state.take();
        state.save(getContext());
        syncGuestReceiver();
        listener.onStateChanged(state, true);
        refreshValues();
    }

    private void autoTake() {
        if (autoButton != null) autoButton.setEnabled(false);
        if (takeButton != null) takeButton.setEnabled(false);
        if (state.getTransition() == ProductionControlState.Transition.CUT) {
            takeNow(); reEnableSwitchButtons(); return;
        }
        getWindow().getDecorView().postDelayed(() -> {
            if (!isShowing()) return;
            takeNow(); reEnableSwitchButtons();
        }, state.getTransitionDurationMs());
    }

    private void reEnableSwitchButtons() {
        if (autoButton != null) autoButton.setEnabled(true);
        if (takeButton != null) takeButton.setEnabled(true);
    }

    private String programLabel() {
        return state.getProgramScene() == ProductionScene.CAMERA ? "Camera " + state.getProgramCameraSlot() : state.getProgramScene().getTitle();
    }

    private String previewLabel() {
        return state.getPreviewScene() == ProductionScene.CAMERA ? "Camera " + state.getPreviewCameraSlot() : state.getPreviewScene().getTitle();
    }

    private String previewDetailLabel() {
        ProductionCameraSlot camera = ProductionCameraSlotManager.get(getContext(), state.getPreviewCameraSlot());
        return state.getPreviewScene() == ProductionScene.CAMERA
                ? camera.getStatus().name() + " • " + (camera.getGuestName().isEmpty() ? "visitor slot" : camera.getGuestName())
                : state.getPreviewScene().getDescription();
    }

    private View monitor(String label, boolean isProgram) {
        LinearLayout card = column();
        card.setPadding(dp(9), dp(7), dp(9), dp(7));
        card.setBackground(round(isProgram ? RED_DARK : PANEL_ALT, 12));
        card.addView(text(label + (isProgram ? " • TALLY" : " • READY"), 8, isProgram ? RED : GREEN));
        TextView value = text(isProgram ? programLabel() : previewLabel(), 15, TEXT);
        value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        if (isProgram) tallyValue = value; else previewValue = value;
        card.addView(value, new LinearLayout.LayoutParams(-1, 0, 1));
        if (!isProgram) {
            previewDetail = text(previewDetailLabel(), 8, MUTED);
            card.addView(previewDetail);
        } else card.addView(text("LIVE PROGRAM PATH", 7, MUTED));
        return card;
    }

    private void refreshValues() {
        if (previewValue != null) previewValue.setText(previewLabel());
        if (previewDetail != null) previewDetail.setText(previewDetailLabel());
        if (tallyValue != null) tallyValue.setText("ON AIR • " + programLabel());
    }

    private void attachLiveCanvas() {
        if (liveCanvas == null || originalParent == null || liveCanvas.getParent() != originalParent) return;
        originalParent.removeView(liveCanvas);
        ViewGroup monitor = findMonitorContainer((ViewGroup) getWindow().getDecorView());
        if (monitor != null) monitor.addView(liveCanvas, new android.widget.FrameLayout.LayoutParams(-1, -1));
        canvasAttached = monitor != null;
    }

    private ViewGroup findMonitorContainer(View root) {
        if (root instanceof ViewGroup && "Live Program monitor".contentEquals(root.getContentDescription())) return (ViewGroup) root;
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            ViewGroup found = findMonitorContainer(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    @Override public void dismiss() {
        if (guestReceiver != null) guestReceiver.hide();
        restoreLiveCanvas();
        super.dismiss();
    }

    private void restoreLiveCanvas() {
        if (!canvasAttached || liveCanvas == null || originalParent == null) return;
        if (liveCanvas.getParent() instanceof ViewGroup) ((ViewGroup) liveCanvas.getParent()).removeView(liveCanvas);
        int index = originalIndex < 0 ? originalParent.getChildCount() : Math.min(originalIndex, originalParent.getChildCount());
        originalParent.addView(liveCanvas, index, originalLayoutParams);
        canvasAttached = false;
    }

    private LinearLayout column() { return column(Color.TRANSPARENT); }
    private LinearLayout column(int background) {
        LinearLayout v = new LinearLayout(getContext()); v.setOrientation(LinearLayout.VERTICAL);
        if (background != Color.TRANSPARENT) v.setBackgroundColor(background); return v;
    }
    private LinearLayout row() { LinearLayout v = new LinearLayout(getContext()); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private MaterialButton button(String title, boolean filled) {
        MaterialButton b = new MaterialButton(getContext()); b.setText(title); b.setTextColor(TEXT); b.setTextSize(10); b.setMinHeight(0); b.setAllCaps(false); b.setBackground(filled ? round(RED, 12) : round(PANEL_ALT, 12)); return b;
    }
    private TextView text(String title, float size, int color) { TextView v = new TextView(getContext()); v.setText(title); v.setTextSize(size); v.setTextColor(color); return v; }
    private View space(int width) { View v = new View(getContext()); v.setLayoutParams(new LinearLayout.LayoutParams(width, 1)); return v; }
    private TextView section(String title) { TextView v = text(title, 10, MUTED); v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); v.setPadding(dp(4), dp(8), dp(4), dp(4)); return v; }
    private GradientDrawable round(int color, int radiusDp) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radiusDp)); return d; }
    private int dp(int value) { return Math.round(value * getContext().getResources().getDisplayMetrics().density); }

    /** Small EditText subclass keeps the dialog implementation dependency-free. */
    private static final class EditTextShim extends androidx.appcompat.widget.AppCompatEditText {
        EditTextShim(Context context) { super(context); }
    }
}
