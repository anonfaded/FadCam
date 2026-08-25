package com.fadcam.production;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.fadrec.services.ScreenRecordingService;
import com.fadcam.streaming.RemoteStreamManager;
import com.fadcam.streaming.RemoteStreamService;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.concurrent.ExecutionException;

/**
 * Mobile TV-production canvas. It composes a loaded video and live front camera
 * as one visible program feed. The existing MediaProjection/FadRec pipeline can
 * then capture that exact composite and, when Live is enabled, the existing
 * fragmented-MP4 streaming bridge publishes the same session.
 */
@OptIn(markerClass = UnstableApi.class)
public class DuetStudioFragment extends Fragment {
    private static final String TAG = "DuetStudio";

    private FrameLayout canvas;
    private PlayerView playerView;
    private PreviewView cameraPreview;
    private TextView lowerThird;
    private TextView status;
    private ExoPlayer player;
    private Preview cameraUseCase;
    private ProcessCameraProvider cameraProvider;
    private ProductionScene scene = ProductionScene.DUET_PIP;
    private ProductionControlState controlState;
    private ProductionAudioDucker audioDucker;
    private boolean recording;
    private boolean live;

    private ActivityResultLauncher<String[]> permissionLauncher;
    private ActivityResultLauncher<String> videoPicker;
    private ActivityResultLauncher<Intent> projectionLauncher;

    /** Adds the TV-production entry point to the existing recording quick-actions row. */
    public static void installEntryButton(@Nullable ViewGroup quickActionsRow, @NonNull Fragment host) {
        if (quickActionsRow == null || !host.isAdded()) return;
        if (quickActionsRow.findViewWithTag(TAG) != null) return;
        MaterialButton button = new MaterialButton(host.requireContext());
        button.setTag(TAG);
        button.setText("TV\nPROD");
        button.setTextSize(10);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setMinHeight(0);
        button.setPadding(8, 0, 8, 0);
        button.setOnClickListener(v -> {
            if (!host.isAdded()) return;
            ((com.fadcam.MainActivity) host.requireActivity()).showOverlayFragment(
                    new DuetStudioFragment(), "DuetStudio");
        });
        float density = host.getResources().getDisplayMetrics().density;
        quickActionsRow.addView(button, new ViewGroup.LayoutParams(
                dp(density, 78), dp(density, 58)));
    }

    private static int dp(float density, int value) { return Math.round(value * density); }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        controlState = ProductionControlState.load(requireContext());
        scene = controlState.getProgramScene();
        audioDucker = new ProductionAudioDucker();
        audioDucker.start();
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                    if (!isAdded()) return;
                    if (hasCameraPermission()) startCamera();
                    else showStatus("CAMERA PERMISSION REQUIRED");
                });
        videoPicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(), uri -> {
                    if (uri != null && isAdded()) loadVideo(uri);
                });
        projectionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (!isAdded()) return;
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        beginRecording(result.getResultCode(), result.getData());
                    } else {
                        live = false;
                        showStatus("SCREEN CAPTURE CANCELLED");
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout toolbar = new LinearLayout(requireContext());
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(6), dp(12), dp(6));
        toolbar.setBackgroundColor(Color.rgb(55, 18, 18));
        TextView title = label("TV PRODUCTION", 18, Color.WHITE);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        MaterialButton load = action("LOAD VIDEO");
        MaterialButton scenes = action("SCENES");
        MaterialButton close = action("CLOSE");
        toolbar.addView(load, wrap());
        toolbar.addView(scenes, wrap());
        toolbar.addView(close, wrap());
        root.addView(toolbar);

        canvas = new FrameLayout(requireContext());
        canvas.setBackgroundColor(Color.BLACK);
        root.addView(canvas, new LinearLayout.LayoutParams(-1, 0, 1));

        playerView = new PlayerView(requireContext());
        playerView.setUseController(true);
        playerView.setShutterBackgroundColor(Color.BLACK);
        cameraPreview = new PreviewView(requireContext());
        cameraPreview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        canvas.addView(playerView, match());
        canvas.addView(cameraPreview, pipParams());

        lowerThird = label("", 15, Color.WHITE);
        lowerThird.setGravity(Gravity.CENTER_VERTICAL);
        lowerThird.setPadding(dp(14), 0, dp(14), 0);
        lowerThird.setBackgroundColor(0xCC551111);
        FrameLayout.LayoutParams lower = new FrameLayout.LayoutParams(-1, dp(46), Gravity.BOTTOM);
        lower.setMargins(dp(10), 0, dp(10), dp(10));
        canvas.addView(lowerThird, lower);

        LinearLayout controls = new LinearLayout(requireContext());
        controls.setPadding(dp(6), dp(6), dp(6), dp(6));
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setBackgroundColor(Color.rgb(24, 24, 24));
        MaterialButton record = action("● RECORD");
        MaterialButton liveButton = action("LIVE + REC");
        MaterialButton mixer = action("AUDIO MIXER");
        MaterialButton brand = action("LOWER THIRD");
        controls.addView(record, new LinearLayout.LayoutParams(0, dp(50), 1));
        controls.addView(liveButton, new LinearLayout.LayoutParams(0, dp(50), 1));
        controls.addView(mixer, new LinearLayout.LayoutParams(0, dp(50), 1));
        controls.addView(brand, new LinearLayout.LayoutParams(0, dp(50), 1));
        root.addView(controls);

        status = label("READY • Load a video to begin a duet", 12, Color.LTGRAY);
        status.setPadding(dp(10), dp(4), dp(10), dp(7));
        root.addView(status);

        load.setOnClickListener(v -> videoPicker.launch("video/*"));
        scenes.setOnClickListener(v -> showScenes());
        close.setOnClickListener(v -> closeOverlay());
        brand.setOnClickListener(v -> editLowerThird());
        mixer.setOnClickListener(v -> showAudioMixer());
        record.setOnClickListener(v -> requestRecord(false));
        liveButton.setOnClickListener(v -> requestRecord(true));

        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        if (hasCameraPermission()) startCamera();
        else permissionLauncher.launch(new String[]{Manifest.permission.CAMERA});
        applyScene();
    }

    private void loadVideo(android.net.Uri uri) {
        if (audioDucker != null) audioDucker.detachPlayer();
        if (player != null) player.release();
        player = new ExoPlayer.Builder(requireContext()).build();
        player.setMediaItem(MediaItem.fromUri(uri));
        player.prepare();
        player.setPlayWhenReady(true);
        playerView.setPlayer(player);
        if (audioDucker != null) audioDucker.attach(player);
        showStatus("VIDEO LOADED • " + scene.getTitle() + " • VOICE DUCKING READY");
    }

    private void showAudioMixer() {
        if (audioDucker == null) return;
        ProductionAudioMixerDialog.show(requireContext(), audioDucker);
    }

    /** Opens the hidden expandable control room from the existing SCENES entry. */
    private void showScenes() {
        controlState = ProductionControlState.load(requireContext()).withProgram(scene);
        ProductionControlRoomDialog dialog = new ProductionControlRoomDialog(
                requireContext(), controlState, new ProductionControlRoomDialog.Listener() {
            @Override
            public void onStateChanged(@NonNull ProductionControlState state, boolean applyToProgram) {
                controlState = state;
                if (applyToProgram) {
                    scene = state.getProgramScene();
                    ProductionSceneManager.setScene(requireContext(), scene);
                    applyScene();
                }
                showStatus("PROGRAM • " + state.getProgramScene().getTitle()
                        + " • PREVIEW • " + state.getPreviewScene().getTitle());
            }

            @Override
            public void onCloseRequested() {
                showStatus("READY • " + scene.getTitle());
            }
        });
        dialog.show();
    }

    private void editLowerThird() {
        EditText input = new EditText(requireContext());
        input.setSingleLine(true);
        input.setHint("Producer / show / guest");
        input.setText(ProductionSceneManager.getLowerThird(requireContext()));
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Production Lower Third")
                .setView(input)
                .setPositiveButton("APPLY", (d, w) -> {
                    ProductionSceneManager.setLowerThird(requireContext(), input.getText().toString());
                    applyLowerThird();
                })
                .setNegativeButton("CLEAR", (d, w) -> {
                    ProductionSceneManager.setLowerThird(requireContext(), "");
                    applyLowerThird();
                })
                .show();
    }

    private void applyLowerThird() {
        if (lowerThird == null || !isAdded()) return;
        String text = ProductionSceneManager.getLowerThird(requireContext());
        lowerThird.setText(text);
        lowerThird.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void applyScene() {
        if (canvas == null || playerView == null || cameraPreview == null) return;
        FrameLayout.LayoutParams playerLp = match();
        FrameLayout.LayoutParams cameraLp;
        playerView.setVisibility(View.VISIBLE);
        cameraPreview.setVisibility(View.VISIBLE);
        switch (scene) {
            case CAMERA:
                playerView.setVisibility(View.GONE);
                cameraLp = match();
                break;
            case VIDEO:
                cameraPreview.setVisibility(View.GONE);
                cameraLp = pipParams();
                break;
            case DUET_SPLIT:
                int half = getResources().getDisplayMetrics().widthPixels / 2;
                playerLp = new FrameLayout.LayoutParams(half, -1, Gravity.START);
                cameraLp = new FrameLayout.LayoutParams(half, -1, Gravity.END);
                break;
            case COMMENTARY:
                cameraLp = new FrameLayout.LayoutParams(dp(190), dp(270), Gravity.TOP | Gravity.END);
                cameraLp.setMargins(0, dp(16), dp(16), 0);
                break;
            case INTERVIEW:
                cameraLp = new FrameLayout.LayoutParams(dp(230), dp(310), Gravity.BOTTOM | Gravity.END);
                cameraLp.setMargins(0, 0, dp(16), dp(70));
                break;
            case DUET_PIP:
            default:
                cameraLp = pipParams();
                break;
        }
        playerView.setLayoutParams(playerLp);
        cameraPreview.setLayoutParams(cameraLp);
        applyLowerThird();
        showStatus("SCENE • " + scene.getTitle() + " • " + scene.getDescription());
    }

    private void requestRecord(boolean requestLive) {
        if (recording) {
            stopRecording();
            return;
        }
        if (!hasCameraPermission()) {
            permissionLauncher.launch(new String[]{Manifest.permission.CAMERA});
            return;
        }
        if (requestLive && player == null) {
            Toast.makeText(requireContext(), "Load the video before going live.", Toast.LENGTH_SHORT).show();
            return;
        }
        live = requestLive;
        android.media.projection.MediaProjectionManager manager =
                (android.media.projection.MediaProjectionManager) requireContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            showStatus("MEDIA PROJECTION IS NOT AVAILABLE");
            return;
        }
        showStatus(requestLive ? "STARTING LIVE + RECORDING…" : "STARTING RECORDING…");
        projectionLauncher.launch(manager.createScreenCaptureIntent());
    }

    private void beginRecording(int resultCode, Intent permissionData) {
        recording = true;
        if (live) {
            RemoteStreamManager manager = RemoteStreamManager.getInstance();
            manager.setContext(requireContext());
            manager.setStreamingMode(SharedPreferencesManager.getInstance(requireContext()).getStreamingMode());
            ContextCompat.startForegroundService(requireContext(), new Intent(requireContext(), RemoteStreamService.class));
        }
        Intent record = new Intent(requireContext(), ScreenRecordingService.class);
        record.setAction(Constants.INTENT_ACTION_START_SCREEN_RECORDING);
        record.putExtra("resultCode", resultCode);
        record.putExtra("permissionData", permissionData);
        record.putExtra(Constants.EXTRA_SCREEN_RECORDING_FORCE_NO_AUDIO, false);
        ContextCompat.startForegroundService(requireContext(), record);
        showStatus(live ? "ON AIR • LIVE + RECORDING • " + scene.getTitle() : "RECORDING • " + scene.getTitle());
    }

    private void stopRecording() {
        if (!isAdded()) return;
        Intent stop = new Intent(requireContext(), ScreenRecordingService.class);
        stop.setAction(Constants.INTENT_ACTION_STOP_SCREEN_RECORDING);
        requireContext().startService(stop);
        if (live) requireContext().stopService(new Intent(requireContext(), RemoteStreamService.class));
        recording = false;
        live = false;
        showStatus("STOPPED • FadRec is finalizing the production file");
    }

    private boolean hasCameraPermission() {
        return isAdded() && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void startCamera() {
        if (!isAdded() || cameraPreview == null) return;
        final PreviewView target = cameraPreview;
        ProcessCameraProvider.getInstance(requireContext()).addListener(() -> {
            if (!isAdded() || target != cameraPreview || cameraPreview == null) return;
            try {
                cameraProvider = ProcessCameraProvider.getInstance(requireContext()).get();
                if (cameraUseCase != null) {
                    cameraProvider.unbind(cameraUseCase);
                }
                cameraUseCase = new Preview.Builder().build();
                cameraUseCase.setSurfaceProvider(cameraPreview.getSurfaceProvider());
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, cameraUseCase);
            } catch (ExecutionException | InterruptedException | IllegalArgumentException e) {
                if (isAdded()) showStatus("CAMERA ERROR • " + e.getClass().getSimpleName());
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    private void closeOverlay() {
        if (recording) stopRecording();
        if (getParentFragmentManager().getBackStackEntryCount() > 0) getParentFragmentManager().popBackStack();
    }

    private void showStatus(String text) { if (status != null) status.setText(text); }
    private MaterialButton action(String text) {
        MaterialButton b = new MaterialButton(requireContext());
        b.setText(text);
        b.setTextSize(10);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setMinHeight(0);
        return b;
    }
    private TextView label(String text, float size, int color) {
        TextView v = new TextView(requireContext());
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }
    private FrameLayout.LayoutParams match() { return new FrameLayout.LayoutParams(-1, -1); }
    private FrameLayout.LayoutParams pipParams() {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(190), dp(270), Gravity.BOTTOM | Gravity.END);
        p.setMargins(0, 0, dp(16), dp(16));
        return p;
    }
    private LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-2, dp(48)); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override
    public void onDestroyView() {
        if (recording) stopRecording();
        if (audioDucker != null) {
            audioDucker.stop();
            audioDucker.detachPlayer();
        }
        if (cameraProvider != null && cameraUseCase != null) {
            try { cameraProvider.unbind(cameraUseCase); } catch (Exception ignored) {}
        }
        cameraUseCase = null;
        cameraProvider = null;
        if (player != null) player.release();
        player = null;
        super.onDestroyView();
    }
}
