package com.fadcam.opengl;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.media.Image; // Import android.media.Image
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.EGLSurface;
import android.opengl.GLES20; // Use GLES20 for wider compatibility
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import com.fadcam.SharedPreferencesManager;
import com.fadcam.ui.LocationHelper;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

/**
 * Manages the OpenGL ES rendering pipeline for video recording using ImageReader input.
 * Handles the GL thread, receives camera frames via Images, uploads them (YUV assumed),
 * renders them with a watermark, and outputs to the MediaRecorder surface and optionally a preview surface.
 */
// ** REMOVED SurfaceTexture.OnFrameAvailableListener implementation **
public class RecordingPipeline extends HandlerThread {
    private static final String TAG = "OpenGLPipeline"; // Changed TAG slightly
    private static final int MSG_START_RECORDING = 0;
    private static final int MSG_STOP_RECORDING = 1;
    // Removed MSG_FRAME_AVAILABLE
    private static final int MSG_UPDATE_WATERMARK = 3;
    private static final int MSG_SET_PREVIEW_SURFACE = 4;
    private static final int MSG_RELEASE = 5;
    private static final int MSG_PROCESS_NEW_IMAGE = 6; // New message type

    private EGLSurface mDummySurface = null;
    private Handler mHandler;
    private EglCore mEglCore;
    private TextureRenderer mTextureRenderer; // Needs updates for YUV/Texture2D
    private WatermarkGenerator mWatermarkGenerator;

    // --- Input From ImageReader ---
    private final Object frameSyncObject = new Object(); // For synchronizing access to pendingImage
    private Image pendingImage = null; // Holds the latest image from ImageReader
    private boolean newFrameAvailable = false; // Flag set by queueNewFrame

    // --- YUV Texture Handling (Assuming YUV_420_888 input) ---
    private int yTextureId = -1;
    private int uTextureId = -1;
    private int vTextureId = -1;
    private int frameWidth = 0;  // Width of incoming frames
    private int frameHeight = 0; // Height of incoming frames
    private ByteBuffer yBuffer, uBuffer, vBuffer; // Buffers for accessing plane data

    // --- Output Surfaces ---
    private EGLSurface mEncoderSurface; // EGL Surface for MediaRecorder's input Surface
    private EGLSurface mPreviewSurface;  // EGL Surface for UI's preview Surface

    private WeakReference<RecordingPipelineCallbacks> mCallbacksRef;

    // --- Watermark Related ---
    private Bitmap mWatermarkBitmap; // Cached bitmap to avoid regeneration if text is same
    private int mWatermarkTextureId = -1;
    private float[] mWatermarkMatrix = new float[16];
    private int mBitmapWidth = -1;
    private int mBitmapHeight = -1;

    // Dimensions of the target video output (might differ from frame dimensions slightly)
    private int mVideoWidth;
    private int mVideoHeight;


    // Callbacks for RecordingService
    public interface RecordingPipelineCallbacks {
        // void onSurfaceTextureCreated(SurfaceTexture surfaceTexture); // REMOVED callback
        void onInputSurfaceReady(); // ** NEW: Signal maybe needed if service waits? Or implicitly ready.**
        void onRecordingStarted(); // Signaled AFTER GL rendering to Encoder surface starts
        void onRecordingStopped(); // Signaled AFTER GL rendering to Encoder surface stops
        void onError(String message);
    }

    // Constructor takes video size (for watermark matrix) and callbacks
    public RecordingPipeline(Context context, int videoWidth, int videoHeight, RecordingPipelineCallbacks callbacks) {
        super("OpenGLPipelineThread");
        mVideoWidth = videoWidth;
        mVideoHeight = videoHeight;
        mCallbacksRef = new WeakReference<>(callbacks);

        // Watermark generation setup (can stay on main thread or here)
        SharedPreferencesManager spm = SharedPreferencesManager.getInstance(context);
        LocationHelper locHelper = new LocationHelper(context); // Ensure this doesn't block/needs context correctly
        mWatermarkGenerator = new WatermarkGenerator(context, spm, locHelper);

        // Initial watermark matrix setup (assuming top-left)
        android.opengl.Matrix.setIdentityM(mWatermarkMatrix, 0);
        recalculateWatermarkMatrix(); // Calculate based on initial generator state
    }

    /**
     * Calculates the watermark transformation matrix based on current video
     * dimensions and watermark bitmap dimensions.
     * Should be called when video size or watermark size changes.
     */
    private void recalculateWatermarkMatrix() {
        if (mVideoWidth <= 0 || mVideoHeight <= 0 || mBitmapWidth <= 0 || mBitmapHeight <= 0) return; // Need valid sizes
        float aspectVideo = (float)mVideoWidth / mVideoHeight;
        float aspectWatermark = (float)mBitmapWidth / mBitmapHeight;

        float scaleX = (float) mBitmapWidth / mVideoWidth;
        float scaleY = (float) mBitmapHeight / mVideoHeight;

        // Adjust scale based on aspect ratio if needed to fit, simpler scale for now:
        scaleX = Math.min(scaleX, scaleX * (aspectWatermark/aspectVideo) ); // Basic fit adjustment attempt
        scaleY = Math.min(scaleY, scaleY * (aspectVideo/aspectWatermark) );

        android.opengl.Matrix.setIdentityM(mWatermarkMatrix, 0);

        // Scale watermark first (OpenGL coords are -1 to 1, so target size is 2x2)
        // We want watermark size relative to the screen size
        // android.opengl.Matrix.scaleM(mWatermarkMatrix, 0, watermarkViewWidth/2f, watermarkViewHeight/2f, 1.0f);

        // Translate to desired corner (e.g., top left)
        // OpenGL: -1,-1 is bottom-left, 1,1 is top-right
        float tx = -1.0f + scaleX; // Translate to left edge + half its scaled width
        float ty = 1.0f - scaleY;  // Translate to top edge - half its scaled height
        // android.opengl.Matrix.translateM(mWatermarkMatrix, 0, tx, ty, 0.0f);
        Log.d(TAG, "Recalculated Watermark Matrix: Scale="+scaleX+"x"+scaleY+", Translate="+tx+"x"+ty);

        // *** Alternative Simplified Positioning (Render quad covering whole screen, rely on texture coords) ***
        // Most shaders use standard texture coords (0,0 to 1,1). Let's use an MVP matrix to scale/position a quad.
        android.opengl.Matrix.setIdentityM(mWatermarkMatrix, 0); // Reset

        // Want watermark to occupy a portion (e.g. 1/4 width, proportional height) in top-left
        float targetWatermarkScreenW = 0.25f; // Occupy 25% of screen width
        float watermarkAspect = (float) mBitmapWidth / mBitmapHeight;
        float targetWatermarkScreenH = targetWatermarkScreenW / watermarkAspect;

        // Scale the identity quad (size 2x2 in clip space) to this size
        android.opengl.Matrix.scaleM(mWatermarkMatrix, 0, targetWatermarkScreenW, targetWatermarkScreenH, 1f);

        // Translate the scaled quad to top-left corner
        float translateX = -1f + targetWatermarkScreenW; // Move left edge to -1
        float translateY = 1f - targetWatermarkScreenH;  // Move top edge to 1
        android.opengl.Matrix.translateM(mWatermarkMatrix, 0, translateX, translateY, 0f);
        Log.d(TAG, "Recalculated Watermark Matrix MVP: Target "+(targetWatermarkScreenW*100)+"% width. Translate=" + translateX+","+translateY);

    }

    /**
     * Sets the target output size for the pipeline (video output size).
     * This updates the internal video width/height and recalculates the watermark matrix.
     * @param size The new target output size.
     */
    public void setTargetOutputSize(Size size) {
        if (size == null) return;
        mVideoWidth = size.getWidth();
        mVideoHeight = size.getHeight();
        recalculateWatermarkMatrix();
        Log.d(TAG, "setTargetOutputSize: Updated to " + mVideoWidth + "x" + mVideoHeight);
    }

    @Override
    protected void onLooperPrepared() {
        mHandler = new Handler(getLooper()) {
            @Override
            public void handleMessage(android.os.Message msg) {
                RecordingPipeline pipeline = RecordingPipeline.this; // Avoid leakage
                if(pipeline == null) return; // Check if released

                switch (msg.what) {
                    case MSG_START_RECORDING:
                        Surface recorderSurface = (Surface) msg.obj;
                        pipeline.handleStartRecording(recorderSurface);
                        break;
                    case MSG_STOP_RECORDING:
                        pipeline.handleStopRecording();
                        break;
                    // Removed MSG_FRAME_AVAILABLE
                    case MSG_PROCESS_NEW_IMAGE: // Handle new message
                        pipeline.handleProcessNewImage();
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
                        getLooper().quitSafely(); // Quit looper after release message processed
                        break;
                    default:
                        super.handleMessage(msg);
                }
            }
        };

        // Initial setup on the GL thread
        mHandler.post(() -> {
            try {
                setupEglAndGl(); // Set up EGL/GL, including dummy surface
                // *** No longer creating camera surface texture here ***
                Log.d(TAG,"GL setup complete. Waiting for start command.");
                startWatermarkUpdateTimer(); // Start periodic watermark generation
                // Optionally notify service that input surface *conceptually* ready (though ImageReader drives it now)
                // if(mCallbacksRef.get() != null) mCallbacksRef.get().onInputSurfaceReady();

            } catch (Exception e) {
                Log.e(TAG, "Fatal error during initial GL setup", e);
                RecordingPipelineCallbacks callbacks = mCallbacksRef.get();
                if (callbacks != null) {
                    callbacks.onError("Failed GL setup: " + e.getMessage());
                }
                // Ensure cleanup if setup fails
                handleRelease();
                getLooper().quit(); // Quit looper immediately on fatal setup error
            }
        });
    }

    // ** REMOVED onFrameAvailable listener implementation **


    /**
     * Queues a new Image frame received from the ImageReader.
     * This method can be called from any thread (typically the ImageReader callback thread).
     * It replaces the previous pending frame if not yet processed.
     * @param image The new Image frame.
     */
    public void queueNewFrame(Image image) {
        if(mHandler == null) { // Don't queue if GL thread isn't running
            Log.w(TAG,"queueNewFrame: Handler null, closing image immediately.");
            image.close(); return;
        }
        synchronized (frameSyncObject) {
            if (pendingImage != null) {
                Log.v(TAG, "queueNewFrame: Overwriting previous frame."); // Verbose log
                pendingImage.close(); // Close previous if it's still there
            }
            pendingImage = image; // Store the new one
            newFrameAvailable = true; // Mark flag
        }
        // Signal the GL thread to process it
        mHandler.sendEmptyMessage(MSG_PROCESS_NEW_IMAGE);
    }


    // --- Methods called by RecordingService ---

    /**
     * Sets up the EGL surface for rendering to the MediaRecorder's input surface.
     * Called from the GL thread via message handler.
     */
    public void startRecording(Surface recorderSurface) {
        if (mHandler != null) {
            mHandler.obtainMessage(MSG_START_RECORDING, recorderSurface).sendToTarget();
        } else { Log.e(TAG,"startRecording: Handler is null!"); }
    }

    /** Stops rendering to the MediaRecorder surface. */
    public void stopRecording() {
        if (mHandler != null) {
            mHandler.sendEmptyMessage(MSG_STOP_RECORDING);
        } else { Log.e(TAG,"stopRecording: Handler is null!"); }
    }

    /** Sets or clears the UI preview surface. */
    public void setPreviewSurface(Surface previewSurface) {
        if (mHandler != null) {
            mHandler.obtainMessage(MSG_SET_PREVIEW_SURFACE, previewSurface).sendToTarget();
        } else { Log.e(TAG,"setPreviewSurface: Handler is null!"); }
    }

    /** Releases all OpenGL/EGL resources. */
    public void release() {
        if (mHandler != null) {
            mHandler.sendEmptyMessage(MSG_RELEASE);
        } else { Log.e(TAG,"release: Handler is null!"); }
    }


    // --- Handler Methods (Executed on GL Thread) ---

    /** Sets up EGL, GL context, dummy surface, and TextureRenderer programs. */
    private void setupEglAndGl() {
        Log.d(TAG, "setupEglAndGl: Setting up EGL/GL...");
        try {
            mEglCore = new EglCore(null, 0); // Force GLES2 focus

            // Create and make current a temporary 1x1 pbuffer surface for setup
            mDummySurface = mEglCore.createOffscreenSurface(1, 1);
            if (mDummySurface == null || mDummySurface == EGL14.EGL_NO_SURFACE) {
                throw new RuntimeException("Failed to create dummy EGL surface.");
            }
            mEglCore.makeCurrent(mDummySurface);
            Log.d(TAG, "setupEglAndGl: Made dummy 1x1 surface current.");

            // Create the TextureRenderer (assuming it now handles TEXTURE_2D and YUV->RGB shaders)
            mTextureRenderer = new TextureRenderer();
            mTextureRenderer.createPrograms(); // **TextureRenderer needs internal updates**
            Log.d(TAG, "setupEglAndGl: TextureRenderer programs created.");

            // Optionally create YUV textures here if size is known? Better to create in handleProcessNewImage first time.

            Log.i(TAG, "setupEglAndGl: EGL/GL setup successful.");
        } catch (Exception e) {
            Log.e(TAG, "Error during EGL/GL setup", e);
            // Clean up partially created resources
            if (mTextureRenderer != null) { try{mTextureRenderer.release();}catch(Exception ignore){} mTextureRenderer = null; }
            if (mDummySurface != null && mEglCore != null) { try { mEglCore.releaseSurface(mDummySurface); } catch (Exception ignore) {} mDummySurface = null; }
            if (mEglCore != null) { try { mEglCore.release(); } catch (Exception ignore) {} mEglCore = null; }
            throw e; // Re-throw to be caught in onLooperPrepared
        }
    }


    private void handleStartRecording(Surface recorderSurface) {
        if (mEglCore == null) { Log.e(TAG, "handleStartRecording: EglCore is null!"); return; }
        Log.d(TAG, "handleStartRecording: Creating EGL surface for MediaRecorder.");
        try {
            // Release previous encoder surface if any (e.g., restart without full release)
            if(mEncoderSurface != null && mEncoderSurface != EGL14.EGL_NO_SURFACE) {
                mEglCore.releaseSurface(mEncoderSurface);
            }
            mEncoderSurface = mEglCore.createWindowSurface(recorderSurface); // Create EGL wrapper for MR surface
            if (mEncoderSurface == null || mEncoderSurface == EGL14.EGL_NO_SURFACE){ throw new RuntimeException("Failed to create encoder EGL surface."); }

            mEglCore.makeCurrent(mEncoderSurface); // Make it current for initial watermark upload
            Log.d(TAG,"handleStartRecording: Made encoder surface current.");
            handleUpdateWatermark(); // Draw initial watermark texture

            RecordingPipelineCallbacks callbacks = mCallbacksRef.get();
            if (callbacks != null) { callbacks.onRecordingStarted(); }
            Log.i(TAG, "handleStartRecording: Pipeline ready for encoder output.");
        } catch (Exception e) {
            Log.e(TAG, "Error starting recording pipeline (EGL surface creation/callback)", e);
            RecordingPipelineCallbacks callbacks = mCallbacksRef.get();
            if (callbacks != null) { callbacks.onError("Failed start pipeline: " + e.getMessage()); }
            handleRelease(); // Critical error, release all resources
        }
    }

    private void handleStopRecording() {
        if (mEglCore == null) { Log.e(TAG, "handleStopRecording: EglCore is null!"); return; }
        Log.d(TAG, "handleStopRecording: Releasing encoder EGL surface.");
        stopWatermarkUpdateTimer(); // Stop watermark timer

        if (mEncoderSurface != null && mEncoderSurface != EGL14.EGL_NO_SURFACE) {
            mEglCore.releaseSurface(mEncoderSurface);
            mEncoderSurface = null;
        } else { Log.w(TAG,"handleStopRecording: Encoder surface was already null/invalid.");}

        // Keep preview surface alive unless explicitly removed

        RecordingPipelineCallbacks callbacks = mCallbacksRef.get();
        if (callbacks != null) { callbacks.onRecordingStopped(); }
        Log.i(TAG, "handleStopRecording: Encoder rendering stopped.");
        // Note: Core GL resources released only in handleRelease()
    }

    // --- NEW: Handles processing an Image from the queue ---
    @SuppressLint("SyntheticAccessor") // Suppress accessor warning for inner class callback access
    private void handleProcessNewImage() {
        Image imageToProcess = null;
        long timestampNanos = 0; // Get timestamp from image
        boolean frameNeedsDrawing = false; // Did we get a valid frame to draw?

        // --- Get latest image from queue ---
        synchronized (frameSyncObject) {
            if (pendingImage != null) { // Only proceed if there's an image
                imageToProcess = pendingImage;
                pendingImage = null; // Consume it
                newFrameAvailable = false; // Reset flag
                timestampNanos = imageToProcess.getTimestamp(); // Get timestamp before closing
                frameNeedsDrawing = true; // Got an image, needs drawing
            } else {
                // Log.v(TAG, "handleProcessNewImage: No pending image found."); // Too noisy usually
                return; // Nothing to process
            }
        }

        // --- Upload Image Data to Texture (CPU intensive part, YUV assumed) ---
        if (frameNeedsDrawing && imageToProcess != null) {
            try {
                updateYuvTextures(imageToProcess); // ** CRITICAL: This needs correct implementation **
            } catch (Exception e) {
                Log.e(TAG, "Error updating YUV textures", e);
                frameNeedsDrawing = false; // Don't draw if texture update failed
                // Signal error?
                if(mCallbacksRef.get() != null) mCallbacksRef.get().onError("Texture update failed");
            } finally {
                imageToProcess.close(); // *** Ensure image is ALWAYS closed ***
            }
        }

        // --- Render if a valid frame was processed ---
        if (frameNeedsDrawing && mEglCore != null && mTextureRenderer != null) {
            // --- Render to Encoder Surface ---
            if (mEncoderSurface != null && mEncoderSurface != EGL14.EGL_NO_SURFACE) {
                try {
                    mEglCore.makeCurrent(mEncoderSurface);
                    // *** CALL DRAW FRAME - Assumes TextureRenderer modified for YUV ***
                    // It might take y/u/v texture IDs or just draw its internal textures
                    mTextureRenderer.drawFrameYUV(yTextureId, uTextureId, vTextureId, mWatermarkTextureId, mWatermarkMatrix); // Placeholder method name

                    mEglCore.setPresentationTime(mEncoderSurface, timestampNanos); // Use image timestamp
                    mEglCore.swapBuffers(mEncoderSurface);
                } catch (Exception e) {
                    Log.e(TAG, "Error rendering to encoder surface", e);
                    if (mCallbacksRef.get() != null) { mCallbacksRef.get().onError("Render->Encoder fail: "+e.getMessage()); }
                    // Should we stop recording here? Potentially.
                }
            }

            // --- Render to Preview Surface ---
            if (mPreviewSurface != null && mPreviewSurface != EGL14.EGL_NO_SURFACE) {
                try {
                    mEglCore.makeCurrent(mPreviewSurface);
                    // *** CALL DRAW FRAME - Assumes TextureRenderer modified for YUV ***
                    mTextureRenderer.drawFrameYUV(yTextureId, uTextureId, vTextureId, mWatermarkTextureId, null); // No special matrix for preview?
                    mEglCore.swapBuffers(mPreviewSurface);
                } catch (Exception e) {
                    Log.e(TAG, "Error rendering to preview surface", e);
                    // Don't usually stop recording for preview errors
                }
            }
            // Make nothing current after drawing
            try { mEglCore.makeNothingCurrent(); } catch(Exception ignore) {}
        }
    }


    // --- TODO: Implement YUV Texture Upload Logic ---
    /**
     * Updates the Y, U, V textures from the given YUV_420_888 Image.
     * NOTE: This is a placeholder structure. Actual implementation requires
     * careful handling of OpenGL texture creation, buffer access (direct vs. copy),
     * row strides, pixel strides, and potentially texture recreation if dimensions change.
     * Assumes textures (yTextureId, uTextureId, vTextureId) have been created.
     */
    private void updateYuvTextures(Image image) {
        if (mTextureRenderer == null) return;

        int newWidth = image.getWidth();
        int newHeight = image.getHeight();

        // --- Check if texture recreation is needed (Size change) ---
        if (newWidth != frameWidth || newHeight != frameHeight || yTextureId == -1) {
            frameWidth = newWidth; frameHeight = newHeight;
            Log.d(TAG, "Frame dimensions changed/init. Recreating YUV textures: "+frameWidth+"x"+frameHeight);
            createOrUpdateYuvTextures(frameWidth, frameHeight);
        }

        Image.Plane[] planes = image.getPlanes();
        if (planes.length < 3) { Log.e(TAG,"Invalid YUV Plane count: "+planes.length); return;}

        // --- Y plane ---
        ByteBuffer yBuf = planes[0].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int yPixStride = planes[0].getPixelStride();
        if (yRowStride == frameWidth && yPixStride == 1) {
            yBuf.position(0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yTextureId);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, frameWidth, frameHeight, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, yBuf);
            checkGlError("Update Y texture");
        } else {
            // Copy row by row if stride != width
            ByteBuffer tmp = ByteBuffer.allocateDirect(frameWidth * frameHeight);
            for (int row = 0; row < frameHeight; row++) {
                int offset = row * yRowStride;
                yBuf.position(offset);
                yBuf.limit(offset + frameWidth);
                tmp.put(yBuf);
            }
            tmp.position(0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yTextureId);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, frameWidth, frameHeight, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, tmp);
            checkGlError("Update Y texture (copy)");
        }

        // --- U and V planes ---
        int uvWidth = frameWidth / 2;
        int uvHeight = frameHeight / 2;
        for (int plane = 1; plane <= 2; plane++) {
            ByteBuffer uvBuf = planes[plane].getBuffer();
            int uvRowStride = planes[plane].getRowStride();
            int uvPixStride = planes[plane].getPixelStride();
            int texId = (plane == 1) ? uTextureId : vTextureId;
            if (uvRowStride == uvWidth && uvPixStride == 1) {
                uvBuf.position(0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, uvWidth, uvHeight, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, uvBuf);
                checkGlError(plane == 1 ? "Update U texture" : "Update V texture");
            } else {
                // Copy row by row, handling pixel stride
                ByteBuffer tmp = ByteBuffer.allocateDirect(uvWidth * uvHeight);
                for (int row = 0; row < uvHeight; row++) {
                    int offset = row * uvRowStride;
                    uvBuf.position(offset);
                    for (int col = 0; col < uvWidth; col++) {
                        tmp.put(uvBuf.get(col * uvPixStride));
                    }
                }
                tmp.position(0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, uvWidth, uvHeight, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, tmp);
                checkGlError(plane == 1 ? "Update U texture (copy)" : "Update V texture (copy)");
            }
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    /**
     * Creates or re-creates the Y, U, V textures if needed.
     * Needs to be called on the GL thread.
     */
    private void createOrUpdateYuvTextures(int width, int height) {
        if (mTextureRenderer == null) { Log.e(TAG,"Cannot create YUV textures, renderer null"); return; }
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1); // Important for non-RGBA textures

        // Delete old textures if they exist
        int[] oldTextures = { yTextureId, uTextureId, vTextureId, mWatermarkTextureId };
        GLES20.glDeleteTextures(oldTextures.length, oldTextures, 0); // Safe to call even with -1

        // Y Texture (Luminance)
        yTextureId = createGlTexture(width, height, GLES20.GL_LUMINANCE);
        // U/V Textures (Assuming width/2, height/2). Format depends on shader. GL_LUMINANCE often used.
        // If using a shader that expects combined UV (NV21/NV12 format like), use GL_LUMINANCE_ALPHA for UV plane tex.
        int uvWidth = width / 2;
        int uvHeight = height / 2;
        uTextureId = createGlTexture(uvWidth, uvHeight, GLES20.GL_LUMINANCE); // Or GL_LUMINANCE_ALPHA
        vTextureId = createGlTexture(uvWidth, uvHeight, GLES20.GL_LUMINANCE); // Or GL_LUMINANCE_ALPHA
        Log.d(TAG, "Created/Recreated YUV Textures: Y=" + yTextureId + ", U=" + uTextureId + ", V=" + vTextureId);

        // Recreate Watermark Texture ID (-1 initially, created in handleUpdateWatermark)
        mWatermarkTextureId = -1; // Force recreation on next watermark update
        handleUpdateWatermark(); // Create/Update watermark texture now
    }

    /** Helper to create a basic GL Texture */
    private int createGlTexture(int width, int height, int format) {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        checkGlError("glGenTextures");
        int textureId = textures[0];

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        checkGlError("glBindTexture " + textureId);

        // Set filtering
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // Allocate texture storage (important!) - Data can be null initially
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, format, width, height, 0,
                format, GLES20.GL_UNSIGNED_BYTE, null);
        checkGlError("glTexImage2D");

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0); // Unbind
        return textureId;
    }


    // --- Watermark Update Handling (Largely unchanged, uses mTextureRenderer) ---
    private void handleUpdateWatermark() {
        if (mEglCore == null || mTextureRenderer == null || mWatermarkGenerator == null) { return; }

        Bitmap newBitmap = mWatermarkGenerator.generateWatermarkBitmap();
        if (newBitmap != null) {
            // Check if dimensions changed significantly requiring matrix update
            if (mWatermarkGenerator.getBitmapWidth() != mBitmapWidth || mWatermarkGenerator.getBitmapHeight() != mBitmapHeight) {
                mBitmapWidth = mWatermarkGenerator.getBitmapWidth();
                mBitmapHeight = mWatermarkGenerator.getBitmapHeight();
                recalculateWatermarkMatrix(); // Update transformation
                Log.d(TAG, "Watermark size changed ("+mBitmapWidth+"x"+mBitmapHeight+"), recalc matrix.");
                mWatermarkTextureId = -1; // Force texture recreation/reupload if size changes
            }

            try {
                // Ensure a surface is current (dummy, preview, or encoder)
                EGLSurface currentSurf = mEglCore.getCurrentSurface(EGL14.EGL_DRAW); // Find which surf is current
                EGLSurface surfaceToMakeCurrent = null;
                if (mEncoderSurface != null && mEncoderSurface != EGL14.EGL_NO_SURFACE) surfaceToMakeCurrent = mEncoderSurface;
                else if (mPreviewSurface != null && mPreviewSurface != EGL14.EGL_NO_SURFACE) surfaceToMakeCurrent = mPreviewSurface;
                else if (mDummySurface != null && mDummySurface != EGL14.EGL_NO_SURFACE) surfaceToMakeCurrent = mDummySurface;

                if(surfaceToMakeCurrent != null) {
                    if (currentSurf != surfaceToMakeCurrent) mEglCore.makeCurrent(surfaceToMakeCurrent); // Make current if not already

                    // Use TextureRenderer to create/update the 2D watermark texture
                    // mTextureRenderer needs updateWatermarkTexture method & to store/return mTextureId2D
                    mTextureRenderer.updateWatermarkTexture(newBitmap);
                    mWatermarkTextureId = mTextureRenderer.getWatermarkTextureId(); // Get ID from renderer
                    Log.d(TAG,"Watermark texture updated/created ID: "+mWatermarkTextureId);

                    // If we switched to dummy, make nothing current after
                    if (surfaceToMakeCurrent == mDummySurface && currentSurf != mDummySurface) {
                        mEglCore.makeNothingCurrent();
                    } else if (currentSurf != surfaceToMakeCurrent && currentSurf != EGL14.EGL_NO_SURFACE) {
                        // Switch back if we borrowed encoder/preview and it wasn't the original current
                        mEglCore.makeCurrent(currentSurf);
                    }
                } else { Log.e(TAG,"Could not find suitable surface to make current for watermark update.");}

            } catch (Exception e) { Log.e(TAG, "Error updating watermark texture", e); }
            finally { newBitmap.recycle(); }
        } else {
            // No watermark needed, potentially delete old texture
            if (mWatermarkTextureId != -1) {
                GLES20.glDeleteTextures(1, new int[]{mWatermarkTextureId}, 0);
                mWatermarkTextureId = -1;
                Log.d(TAG,"Deleted old watermark texture.");
            }
        }
        // Schedule next update timer (unless stopping)
        if (mHandler != null && !mHandler.hasMessages(MSG_RELEASE)) {
            startWatermarkUpdateTimer();
        }
    }


    // --- Preview Surface Handling (Unchanged logic) ---
    private void handleSetPreviewSurface(Surface previewSurface) {
        if(mEglCore == null) { Log.e(TAG,"SetPreview: EGLCore null"); return; }
        Log.d(TAG, "handleSetPreviewSurface: Setting preview. New surface valid: " + (previewSurface != null));
        if (mPreviewSurface != null && mPreviewSurface != EGL14.EGL_NO_SURFACE) {
            mEglCore.releaseSurface(mPreviewSurface); // Release old EGLSurface
        }
        if (previewSurface != null) {
            try {
                mPreviewSurface = mEglCore.createWindowSurface(previewSurface); // Create EGLSurface for new Surface
                Log.d(TAG, "handleSetPreviewSurface: New EGL Preview surface created: " + mPreviewSurface);
            } catch (Exception e) { Log.e(TAG, "Error creating preview EGL surface", e); mPreviewSurface = null; }
        } else {
            mPreviewSurface = null; // Clear if null provided
            Log.d(TAG, "handleSetPreviewSurface: Preview surface cleared.");
        }
    }

    // --- Release Method ---
    private void handleRelease() {
        Log.w(TAG, "handleRelease: Releasing OpenGL Pipeline resources...");
        stopWatermarkUpdateTimer();

        // --- Stop using pending image queue ---
        synchronized (frameSyncObject) {
            if (pendingImage != null) {
                pendingImage.close(); pendingImage = null;
            }
        }
        mHandler.removeMessages(MSG_PROCESS_NEW_IMAGE); // Clear pending process messages


        // Release GL Textures
        int[] textures = { yTextureId, uTextureId, vTextureId, mWatermarkTextureId };
        GLES20.glDeleteTextures(textures.length, textures, 0); // Safe for -1
        Log.d(TAG,"Deleted GL textures.");
        yTextureId = uTextureId = vTextureId = mWatermarkTextureId = -1;


        if (mEglCore != null) {
            Log.d(TAG,"Releasing EGLCore and Surfaces...");
            try { mEglCore.makeNothingCurrent(); } catch (Exception ignore) {}
            // Release surfaces using EglCore before releasing core itself
            if (mEncoderSurface != null && mEncoderSurface != EGL14.EGL_NO_SURFACE) {
                try {
                    mEglCore.makeCurrent(mEncoderSurface);
                    // *** ENSURE THIS CALL MATCHES THE METHOD NAME ***
                    mTextureRenderer.drawFrameYUV(yTextureId, uTextureId, vTextureId, mWatermarkTextureId, mWatermarkMatrix);
                } finally {
                    // No cleanup needed, just to satisfy Java syntax
                }
            }
            if (mPreviewSurface != null && mPreviewSurface != EGL14.EGL_NO_SURFACE) {
                try {
                    mEglCore.makeCurrent(mPreviewSurface);
                    // *** ENSURE THIS CALL MATCHES THE METHOD NAME ***
                    // Passing mWatermarkMatrix, change to identity/null if preview needs different positioning
                    mTextureRenderer.drawFrameYUV(yTextureId, uTextureId, vTextureId, mWatermarkTextureId, mWatermarkMatrix);
                } finally {
                    // No cleanup needed, just to satisfy Java syntax
                }
            }
            if (mDummySurface != null && mDummySurface != EGL14.EGL_NO_SURFACE) { mEglCore.releaseSurface(mDummySurface); }

            mEglCore.release(); // Release EGL context and display
            mEglCore = null;
            Log.d(TAG,"EGLCore released.");
        } else { Log.d(TAG,"EGLCore was already null."); }

        mEncoderSurface = mPreviewSurface = mDummySurface = null; // Clear EGLSurface references


        // Release TextureRenderer AFTER EGLCore is likely finished using its programs/state
        if (mTextureRenderer != null) {
            // TextureRenderer.release() likely just calls glDeleteProgram
            mTextureRenderer.release();
            mTextureRenderer = null;
            Log.d(TAG,"TextureRenderer released.");
        }


        if (mWatermarkBitmap != null && !mWatermarkBitmap.isRecycled()) {
            mWatermarkBitmap.recycle();
            mWatermarkBitmap = null;
            Log.d(TAG,"Watermark bitmap recycled.");
        }
        mCallbacksRef.clear(); // Clear callback ref

        Log.i(TAG, "handleRelease: Pipeline resources released.");
        // Looper quit is handled in onLooperPrepared message handler after this msg is processed
    }


    // --- Watermark Update Timer ---
    private static final long WATERMARK_UPDATE_INTERVAL_MS = 1000; // Update every 1 second

    private void startWatermarkUpdateTimer() {
        if (mHandler != null) {
            mHandler.removeMessages(MSG_UPDATE_WATERMARK); // Remove pending
            mHandler.sendEmptyMessageDelayed(MSG_UPDATE_WATERMARK, WATERMARK_UPDATE_INTERVAL_MS); // Schedule next
        }
    }

    private void stopWatermarkUpdateTimer() {
        if (mHandler != null) {
            mHandler.removeMessages(MSG_UPDATE_WATERMARK);
        }
    }


    /** Basic GL error checking */
    private void checkGlError(String op) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            Log.e(TAG, op + ": glError " + error);
            // Optionally throw exception in debug builds
            // throw new RuntimeException(op + ": glError " + error);
        }
    }
}