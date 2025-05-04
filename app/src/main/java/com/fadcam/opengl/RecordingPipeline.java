package com.fadcam.opengl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.EGLContext;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import com.fadcam.SharedPreferencesManager;
import com.fadcam.ui.LocationHelper;

import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * Manages the OpenGL ES rendering pipeline for video recording.
 * Handles the GL thread, receives camera frames, renders them with a watermark,
 * and outputs to the MediaRecorder surface and optionally a preview surface.
 */
public class RecordingPipeline extends HandlerThread implements SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "RecordingPipeline";
    private static final int MSG_START_RECORDING = 0;
    private static final int MSG_STOP_RECORDING = 1;
    private static final int MSG_FRAME_AVAILABLE = 2;
    private static final int MSG_UPDATE_WATERMARK = 3;
    private static final int MSG_SET_PREVIEW_SURFACE = 4;
    private static final int MSG_RELEASE = 5;

    private Handler mHandler;
    private EglCore mEglCore;
    private TextureRenderer mTextureRenderer;
    private WatermarkGenerator mWatermarkGenerator;

    private SurfaceTexture mCameraSurfaceTexture;
    private int mCameraTextureId;
    private float[] mCameraTransform = new float[16];

    private EGLSurface mEncoderSurface; // Surface for MediaRecorder
    private EGLSurface mPreviewSurface; // Surface for UI preview (optional)

    private WeakReference<RecordingPipelineCallbacks> mCallbacksRef;

    // Watermark related
    private Bitmap mWatermarkBitmap;
    private int mWatermarkTextureId = -1;
    private float[] mWatermarkMatrix = new float[16]; // Matrix for positioning watermark
    private int mBitmapWidth = -1;
    private int mBitmapHeight = -1;

    // Frame timing
    private long mLastTimestampNanos = 0;

    // Dimensions
    private int mVideoWidth;
    private int mVideoHeight;

    public interface RecordingPipelineCallbacks {
        void onSurfaceTextureCreated(SurfaceTexture surfaceTexture);
        void onRecordingStarted();
        void onRecordingStopped();
        void onError(String message);
    }

    public RecordingPipeline(Context context, int videoWidth, int videoHeight, RecordingPipelineCallbacks callbacks) {
        super("RecordingPipelineThread");
        mVideoWidth = videoWidth;
        mVideoHeight = videoHeight;
        mCallbacksRef = new WeakReference<>(callbacks);

        // Initialize watermark generator (can be done on main thread)
        SharedPreferencesManager sharedPreferencesManager = SharedPreferencesManager.getInstance(context);
        LocationHelper locationHelper = new LocationHelper(context); // Assuming LocationHelper is safe to create here
        mWatermarkGenerator = new WatermarkGenerator(context, sharedPreferencesManager, locationHelper);

        // Calculate initial watermark matrix (can be refined later)
        android.opengl.Matrix.setIdentityM(mWatermarkMatrix, 0);
        // Example: Scale and translate to top-left corner
        float watermarkScaleX = (float) mWatermarkGenerator.getBitmapWidth() / mVideoWidth;
        float watermarkScaleY = (float) mWatermarkGenerator.getBitmapHeight() / mVideoHeight;
        android.opengl.Matrix.scaleM(mWatermarkMatrix, 0, watermarkScaleX, watermarkScaleY, 1.0f);
        // Translate to top-left (adjust based on desired padding/position)
        android.opengl.Matrix.translateM(mWatermarkMatrix, 0, -1.0f, 1.0f - watermarkScaleY * 2.0f, 0.0f); // Example: top-left
    }

    @Override
    protected void onLooperPrepared() {
        mHandler = new Handler(getLooper()) {
            @Override
            public void handleMessage(android.os.Message msg) {
                RecordingPipeline pipeline = RecordingPipeline.this;
                switch (msg.what) {
                    case MSG_START_RECORDING:
                        Surface recorderSurface = (Surface) msg.obj;
                        pipeline.handleStartRecording(recorderSurface);
                        break;
                    case MSG_STOP_RECORDING:
                        pipeline.handleStopRecording();
                        break;
                    case MSG_FRAME_AVAILABLE:
                        pipeline.handleFrameAvailable();
                        break;
                    case MSG_UPDATE_WATERMARK:
                        pipeline.handleUpdateWatermark();
                        break;
                    case MSG_SET_PREVIEW_SURFACE:
                        Surface previewSurface = (Surface) msg.obj;
                        pipeline.handleSetPreviewSurface(previewSurface);
                        break;
                    case MSG_RELEASE:
                        pipeline.handleRelease();
                        break;
                    default:
                        super.handleMessage(msg);
                }
            }
        };

        // Initial setup on the GL thread
        mHandler.post(() -> {
            try {
                setupEglAndGl();
                // Create camera texture and SurfaceTexture
                mCameraTextureId = mTextureRenderer.createCameraTexture();
                mCameraSurfaceTexture = new SurfaceTexture(mCameraTextureId);
                mCameraSurfaceTexture.setOnFrameAvailableListener(this);

                // Notify callbacks that SurfaceTexture is ready for camera output
                RecordingPipelineCallbacks callbacks = mCallbacksRef.get();
                if (callbacks != null) {
                    callbacks.onSurfaceTextureCreated(mCameraSurfaceTexture);
                }

                // Start watermark update timer
                startWatermarkUpdateTimer();

            } catch (Exception e) {
                Log.e(TAG, "Error during initial GL setup", e);
                RecordingPipelineCallbacks callbacks = mCallbacksRef.get();
                if (callbacks != null) {
                    callbacks.onError("Failed to set up recording pipeline: " + e.getMessage());
                }
                handleRelease(); // Clean up on error
            }
        });
    }

    /**
     * Called when a new camera frame is available.
     * This happens on an arbitrary thread, so we post a message to the GL thread.
     */
    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        if (mHandler != null) {
            mHandler.sendEmptyMessage(MSG_FRAME_AVAILABLE);
        }
    }

    /**
     * Starts the recording pipeline.
     * @param recorderSurface The Surface provided by MediaRecorder.
     */
    public void startRecording(Surface recorderSurface) {
        if (mHandler != null) {
            mHandler.obtainMessage(MSG_START_RECORDING, recorderSurface).sendToTarget();
        }
    }

    /**
     * Stops the recording pipeline.
     */
    public void stopRecording() {
        if (mHandler != null) {
            mHandler.sendEmptyMessage(MSG_STOP_RECORDING);
        }
    }

    /**
     * Sets or updates the preview surface for rendering to the UI.
     * @param previewSurface The Surface from the TextureView, or null to remove preview.
     */
    public void setPreviewSurface(Surface previewSurface) {
        if (mHandler != null) {
            mHandler.obtainMessage(MSG_SET_PREVIEW_SURFACE, previewSurface).sendToTarget();
        }
    }

    /**
     * Releases all resources held by the pipeline.
     */
    public void release() {
        if (mHandler != null) {
            mHandler.sendEmptyMessage(MSG_RELEASE);
        }
    }

    // --- Handler Methods (Executed on GL Thread) ---

    private void setupEglAndGl() {
        Log.d(TAG, "setupEglAndGl: Setting up EGL and GL context.");
        mEglCore = new EglCore(null, EglCore.FLAG_RECORDABLE); // Force GLES2 only for maximum compatibility
        mTextureRenderer = new TextureRenderer();
        mTextureRenderer.createPrograms(); // Create shaders/programs
        Log.d(TAG, "setupEglAndGl: EGL and GL setup complete.");
    }

    private void handleStartRecording(Surface recorderSurface) {
        Log.d(TAG, "handleStartRecording: Creating encoder surface.");
        try {
            mEncoderSurface = mEglCore.createWindowSurface(recorderSurface);
            mEglCore.makeCurrent(mEncoderSurface); // Make encoder surface current for initial setup

            // Initial watermark update
            handleUpdateWatermark();

            // Notify callbacks that recording has started (MediaRecorder should be started after this)
            RecordingPipelineCallbacks callbacks = mCallbacksRef.get();
            if (callbacks != null) {
                callbacks.onRecordingStarted();
            }

            Log.d(TAG, "handleStartRecording: Encoder surface created and made current.");
        } catch (Exception e) {
            Log.e(TAG, "Error starting recording pipeline", e);
            RecordingPipelineCallbacks callbacks = mCallbacksRef.get();
            if (callbacks != null) {
                callbacks.onError("Failed to start recording pipeline: " + e.getMessage());
            }
            handleRelease(); // Clean up on error
        }
    }

    private void handleStopRecording() {
        Log.d(TAG, "handleStopRecording: Stopping recording pipeline.");
        // Stop watermark update timer
        stopWatermarkUpdateTimer();

        // Release surfaces
        if (mEncoderSurface != null) {
            mEglCore.releaseSurface(mEncoderSurface);
            mEncoderSurface = null;
        }
        if (mPreviewSurface != null) {
            mEglCore.releaseSurface(mPreviewSurface);
            mPreviewSurface = null;
        }

        // Notify callbacks that recording has stopped
        RecordingPipelineCallbacks callbacks = mCallbacksRef.get();
        if (callbacks != null) {
            callbacks.onRecordingStopped();
        }

        // Note: EglCore and TextureRenderer are released in handleRelease()
        Log.d(TAG, "handleStopRecording: Recording pipeline stopped.");
    }

    private void handleFrameAvailable() {
        if (mEglCore == null || mCameraSurfaceTexture == null || mTextureRenderer == null) {
            Log.w(TAG, "handleFrameAvailable: Pipeline not ready.");
            return;
        }

        // Update the camera texture with the latest frame
        try {
            mCameraSurfaceTexture.updateTexImage();
            mCameraSurfaceTexture.getTransformMatrix(mCameraTransform);
        } catch (Exception e) {
            Log.e(TAG, "Error updating camera texture", e);
            // Continue rendering with the old frame or handle error
        }

        // Get the timestamp of the frame
        long timestampNanos = mCameraSurfaceTexture.getTimestamp();
        if (timestampNanos == mLastTimestampNanos) {
            // Frame timestamp hasn't changed, skip rendering
            // Log.v(TAG, "Skipping frame, timestamp not updated.");
            return;
        }
        mLastTimestampNanos = timestampNanos;

        // --- Render to Encoder Surface ---
        if (mEncoderSurface != null) {
            try {
                mEglCore.makeCurrent(mEncoderSurface);
                mTextureRenderer.drawFrame(mCameraTextureId, mWatermarkTextureId, mCameraTransform, mWatermarkMatrix);
                mEglCore.setPresentationTime(mEncoderSurface, timestampNanos);
                mEglCore.swapBuffers(mEncoderSurface);
            } catch (Exception e) {
                Log.e(TAG, "Error rendering to encoder surface", e);
                RecordingPipelineCallbacks callbacks = mCallbacksRef.get();
                if (callbacks != null) {
                    callbacks.onError("Rendering error to encoder: " + e.getMessage());
                }
                // Continue, but rendering might be broken
            }
        }

        // --- Render to Preview Surface (if available) ---
        if (mPreviewSurface != null) {
            try {
                mEglCore.makeCurrent(mPreviewSurface);
                // Use identity matrix for preview if you want it to fill the TextureView
                float[] identityMatrix = new float[16];
                android.opengl.Matrix.setIdentityM(identityMatrix, 0);
                mTextureRenderer.drawFrame(mCameraTextureId, mWatermarkTextureId, mCameraTransform, identityMatrix); // Use identity for preview MVP
                // No presentation time needed for preview
                mEglCore.swapBuffers(mPreviewSurface);
            } catch (Exception e) {
                Log.e(TAG, "Error rendering to preview surface", e);
                // Continue, but preview might be broken
            }
        }

        // Make nothing current after rendering
        mEglCore.makeNothingCurrent();
    }

    private void handleUpdateWatermark() {
        if (mEglCore == null || mTextureRenderer == null || mWatermarkGenerator == null) {
            Log.w(TAG, "handleUpdateWatermark: Pipeline not ready.");
            return;
        }

        // Generate the latest watermark bitmap
        Bitmap newBitmap = mWatermarkGenerator.generateWatermarkBitmap();

        if (newBitmap != null) {
            try {
                // Make a surface current to upload the texture
                // We can use the encoder surface if recording, or create a temporary offscreen surface
                EGLSurface currentSurface = null;
                if (mEncoderSurface != null) {
                    currentSurface = mEncoderSurface;
                } else if (mPreviewSurface != null) {
                    currentSurface = mPreviewSurface;
                } else {
                    // If neither is available, create a temporary offscreen surface just for texture upload
                    currentSurface = mEglCore.createOffscreenSurface(1, 1); // Minimal size
                }

                if (currentSurface != null) {
                    mEglCore.makeCurrent(currentSurface);
                    // Update the OpenGL texture
                    mTextureRenderer.updateWatermarkTexture(newBitmap);
                    mWatermarkTextureId = mTextureRenderer.getWatermarkTextureId(); // Get the updated texture ID

                    // Recalculate watermark matrix if bitmap size changed
                    if (mWatermarkGenerator.getBitmapWidth() != mBitmapWidth || mWatermarkGenerator.getBitmapHeight() != mBitmapHeight) {
                        mBitmapWidth = mWatermarkGenerator.getBitmapWidth();
                        mBitmapHeight = mWatermarkGenerator.getBitmapHeight();
                        android.opengl.Matrix.setIdentityM(mWatermarkMatrix, 0);
                        float watermarkScaleX = (float) mBitmapWidth / mVideoWidth;
                        float watermarkScaleY = (float) mBitmapHeight / mVideoHeight;
                        android.opengl.Matrix.scaleM(mWatermarkMatrix, 0, watermarkScaleX, watermarkScaleY, 1.0f);
                        android.opengl.Matrix.translateM(mWatermarkMatrix, 0, -1.0f, 1.0f - watermarkScaleY * 2.0f, 0.0f); // Example: top-left
                        Log.d(TAG, "Watermark bitmap size changed, recalculating matrix.");
                    }

                    // Make nothing current if we used a temporary surface
                    if (currentSurface != mEncoderSurface && currentSurface != mPreviewSurface) {
                        mEglCore.makeNothingCurrent();
                        mEglCore.releaseSurface(currentSurface); // Release the temporary surface
                    }

                } else {
                    Log.e(TAG, "Could not get a surface to make current for watermark update.");
                }

            } catch (Exception e) {
                Log.e(TAG, "Error updating watermark texture", e);
            } finally {
                // Recycle the bitmap after uploading to GPU
                newBitmap.recycle();
            }
        } else {
            // No watermark needed, ensure texture ID is reset
            mWatermarkTextureId = -1;
        }

        // Schedule the next watermark update (e.g., every second)
        startWatermarkUpdateTimer();
    }

    private void handleSetPreviewSurface(Surface previewSurface) {
        Log.d(TAG, "handleSetPreviewSurface: Setting preview surface.");
        if (mPreviewSurface != null) {
            mEglCore.releaseSurface(mPreviewSurface);
        }
        if (previewSurface != null) {
            try {
                mPreviewSurface = mEglCore.createWindowSurface(previewSurface);
                Log.d(TAG, "handleSetPreviewSurface: Preview surface created.");
            } catch (Exception e) {
                Log.e(TAG, "Error creating preview surface", e);
                mPreviewSurface = null;
            }
        } else {
            mPreviewSurface = null;
            Log.d(TAG, "handleSetPreviewSurface: Preview surface set to null.");
        }
    }


    private void handleRelease() {
        Log.d(TAG, "handleRelease: Releasing pipeline resources.");
        // Stop watermark update timer
        stopWatermarkUpdateTimer();

        if (mCameraSurfaceTexture != null) {
            mCameraSurfaceTexture.release();
            mCameraSurfaceTexture = null;
        }
        if (mTextureRenderer != null) {
            mTextureRenderer.release();
            mTextureRenderer = null;
        }
        if (mEglCore != null) {
            mEglCore.release();
            mEglCore = null;
        }
        // Release watermark bitmap if it exists
        if (mWatermarkBitmap != null && !mWatermarkBitmap.isRecycled()) {
            mWatermarkBitmap.recycle();
            mWatermarkBitmap = null;
        }

        Log.d(TAG, "handleRelease: Resources released. Quitting looper.");
        getLooper().quitSafely();
    }

    // --- Watermark Update Timer ---
    private static final long WATERMARK_UPDATE_INTERVAL_MS = 1000; // Update every 1 second

    private void startWatermarkUpdateTimer() {
        if (mHandler != null) {
            mHandler.removeMessages(MSG_UPDATE_WATERMARK); // Remove any pending messages
            mHandler.sendEmptyMessageDelayed(MSG_UPDATE_WATERMARK, WATERMARK_UPDATE_INTERVAL_MS);
        }
    }

    private void stopWatermarkUpdateTimer() {
        if (mHandler != null) {
            mHandler.removeMessages(MSG_UPDATE_WATERMARK);
        }
    }
}
