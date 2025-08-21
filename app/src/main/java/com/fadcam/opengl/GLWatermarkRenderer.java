package com.fadcam.opengl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;
import android.view.Surface;
import android.opengl.EGLExt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class GLWatermarkRenderer {
    private static final String TAG = "GLWatermarkRenderer";
    private static final int WATERMARK_BITMAP_WIDTH = 2400;
    private static final int WATERMARK_BITMAP_HEIGHT = 160;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private Surface outputSurface;
    private final Context context;

    private int oesTextureId;
    private SurfaceTexture cameraSurfaceTexture;
    private Surface cameraInputSurface;

    // Watermark
    private Bitmap watermarkBitmap;
    private int watermarkTextureId;
    private String watermarkText = "";
    private final Paint watermarkPaint;

    private boolean initialized = false;

    // Unified EGL config and preview surface (single display/context)
    private EGLConfig eglConfig; // store chosen config for creating surfaces
    private EGLSurface previewEglSurface = EGL14.EGL_NO_SURFACE;
    private Surface currentPreviewSurface = null;
    // Backoff for preview surface creation retries
    private long previewCreateRetryDeadlineNs = 0L;
    // Gate logging to avoid repeated warnings on frequent binds
    private boolean warnedNoExternalTexture = false;
    // Timestamp of last warning to avoid flooding logs (ms)
    private long lastNoExternalTextureWarnMs = 0L;
    // Global verbosity gate - set to false to suppress noisy GL warnings in normal runs
    private static boolean VERBOSE_GL_LOGS = false;

    // OES shader and draw logic
    private int oesProgram;
    private int oesPositionHandle;
    private int oesTexCoordHandle;
    private int oesTextureHandle;
    private int oesMvpMatrixHandle;
    private int oesTexMatrixHandle;
    private int oesExposureHandle; // Handle for exposure compensation uniform

    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;

    // Exposure compensation value (EV stops, e.g., -2.0 to +2.0)
    private float currentExposureCompensation = 0.0f;

    // Using matrices in real-time for the first time in this app!
    // These 2x4 matrices define the vertex coordinates and texture mapping
    private static final float[] VERTICES = {
        // 2D coordinate matrix for vertex positions
        -1.0f, -1.0f,    // Row 1: Bottom left vertex  (x1, y1)
         1.0f, -1.0f,    // Row 2: Bottom right vertex (x2, y2)
        -1.0f,  1.0f,    // Row 3: Top left vertex     (x3, y3)  
         1.0f,  1.0f     // Row 4: Top right vertex    (x4, y4)
    };
    private static final float[] TEXCOORDS = {
        // 2D coordinate matrix for texture mapping
         0.0f,  0.0f,    // Row 1: Bottom left UV  (u1, v1) 
         1.0f,  0.0f,    // Row 2: Bottom right UV (u2, v2)
         0.0f,  1.0f,    // Row 3: Top left UV     (u3, v3)
         1.0f,  1.0f     // Row 4: Top right UV    (u4, v4)
    };

    private volatile boolean frameAvailable = false;
    private final Object frameSyncObject = new Object();

    // Watermark rendering (simplified)
    private int simpleWatermarkProgram;
    private int simpleWatermarkPositionHandle;
    private FloatBuffer watermarkRectBuffer;
    private static final float[] WATERMARK_RECT_VERTICES = {
        -1.0f, 1.0f,    // Top left
        -0.5f, 1.0f,    // Top right (left half)
        -1.0f, 0.85f,   // Bottom left (top bar height)
        -0.5f, 0.85f    // Bottom right
    };

    // Add fields for watermark texture shader
    private int watermarkProgram;
    private int watermarkPositionHandle;
    private int watermarkTexCoordHandle;
    private int watermarkSamplerHandle;
    private FloatBuffer watermarkTexCoordBuffer;
    private static final float[] WATERMARK_TEXCOORDS = { 0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f };

    private final int sensorOrientation;
    private final int videoWidth;
    private final int videoHeight;

    // Transform matrices
    private final float[] recordingMvpMatrix = new float[16];
    private final float[] previewMvpMatrix = new float[16];
    private final float[] texMatrix = new float[16];

    private final Object renderLock = new Object();
    private final Object previewRenderLock = new Object();

    private int deviceOrientation = Surface.ROTATION_0;
    private int mSurfaceWidth = 0;
    private int mSurfaceHeight = 0;

    // Add fields for encoder dimensions
    private int encoderWidth;
    private int encoderHeight;

    public interface OnFrameAvailableListener {
        void onFrameAvailable();
    }
    private OnFrameAvailableListener externalFrameListener;

    // Add this field to track the FullFrameRect instance
    private com.fadcam.opengl.grafika.FullFrameRect mFullFrameBlit = null;

    // Add a field for user orientation setting
    private String userOrientationSetting = "portrait";
    // Add a setter for user orientation
    public void setUserOrientationSetting(String orientation) {
        this.userOrientationSetting = orientation;
    }
    
    /**
     * Sets the recording pipeline reference for timestamp synchronization.
     */
    public void setRecordingPipeline(GLRecordingPipeline pipeline) {
        this.recordingPipeline = pipeline;
    }

    private float[] computeWatermarkRectVertices(float ndcWidth, float ndcHeight) {
        // Top left corner at (-1, 1), width ndcWidth, height ndcHeight
        return new float[] {
            -1.0f, 1.0f,                 // Top left
            -1.0f + ndcWidth, 1.0f,      // Top right
            -1.0f, 1.0f - ndcHeight,     // Bottom left
            -1.0f + ndcWidth, 1.0f - ndcHeight // Bottom right
        };
    }

    private int dynamicBitmapWidth = 0;
    private int dynamicBitmapHeight = 48; // Fixed small height for dashcam style

    private boolean released = false;
    
    // Reference to recording pipeline for timestamp synchronization
    private GLRecordingPipeline recordingPipeline;

    public GLWatermarkRenderer(Context context, Surface outputSurface, String orientation, int sensorOrientation, int videoWidth, int videoHeight) {
        this.context = context;
        this.outputSurface = outputSurface;
        this.sensorOrientation = sensorOrientation;
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;

        watermarkPaint = new Paint();
        watermarkPaint.setTextSize(20);
        watermarkPaint.setAntiAlias(true);
        watermarkPaint.setARGB(255, 255, 255, 255);
        watermarkPaint.setShadowLayer(1f, 0f, 1f, Color.BLACK);

        vertexBuffer = ByteBuffer.allocateDirect(VERTICES.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(VERTICES).position(0);

        texCoordBuffer = ByteBuffer.allocateDirect(TEXCOORDS.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        texCoordBuffer.put(TEXCOORDS).position(0);

        watermarkRectBuffer = ByteBuffer.allocateDirect(WATERMARK_RECT_VERTICES.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        watermarkRectBuffer.put(WATERMARK_RECT_VERTICES).position(0);
    }

    public void setOnFrameAvailableListener(OnFrameAvailableListener listener) {
        this.externalFrameListener = listener;
    }

    public void renderFrame() {
        try {
            // First render to encoder (critical for recording)
            renderToEncoder();
            
            // Then try to render to preview (non-critical)
            try {
                renderToPreview();
            } catch (Exception e) {
                Log.w(TAG, "Preview rendering failed in renderFrame", e);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in renderFrame", e);
        }
    }

    /**
     * Renders only to the encoder surface for recording.
     * This method should be used for background recording when the app is not visible.
     */
    public void renderToEncoder() {
        synchronized (renderLock) {
            // Check if we need to initialize or reinitialize EGL
            if (!initialized || eglDisplay == EGL14.EGL_NO_DISPLAY) {
                Log.e(TAG, "EGL not initialized, attempting to reinitialize");
                try {
                    setupEGL();
                    Log.d(TAG, "Successfully reinitialized EGL");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to reinitialize EGL", e);
                    throw new IllegalStateException("EGL not initialized and reinitialization failed");
                }
            }
            
            // Check if surface is valid and recreate if needed
            if (eglSurface == EGL14.EGL_NO_SURFACE || outputSurface == null) {
                Log.e(TAG, "EGL surface is invalid, attempting to recreate");
                try {
                    if (eglSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(eglDisplay, eglSurface);
                        eglSurface = EGL14.EGL_NO_SURFACE;
                    }
                    
                    if (outputSurface != null) {
                        int[] surfaceAttribs = { EGL14.EGL_NONE };
                        if (eglConfig != null) {
                            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, outputSurface, surfaceAttribs, 0);
                            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                                throw new RuntimeException("Failed to create EGL surface");
                            }
                        } else {
                            throw new RuntimeException("EGL config not set");
                        }
                    } else {
                        throw new RuntimeException("Output surface is null");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to recreate EGL surface", e);
                    throw new IllegalStateException("Failed to recreate EGL surface");
                }
            }
            
            // Try to make the EGL context current, with retry mechanism
            boolean madeCurrentSuccessfully = false;
            for (int attempt = 0; attempt < 3 && !madeCurrentSuccessfully; attempt++) {
                try {
                    if (EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                        madeCurrentSuccessfully = true;
                    } else {
                        int error = EGL14.eglGetError();
                        Log.e(TAG, "eglMakeCurrent failed with error " + error + ", attempt " + (attempt + 1));
                        
                        // If we get EGL_BAD_SURFACE, try recreating the surface
                        if (error == EGL14.EGL_BAD_SURFACE) {
                            Log.d(TAG, "EGL_BAD_SURFACE detected, recreating surface");
                            try {
                                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                                    EGL14.eglDestroySurface(eglDisplay, eglSurface);
                                    eglSurface = EGL14.EGL_NO_SURFACE;
                                }
                                
                                if (outputSurface != null) {
                                    int[] surfaceAttribs = { EGL14.EGL_NONE };
                                    eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, outputSurface, surfaceAttribs, 0);
                                    if (eglSurface != EGL14.EGL_NO_SURFACE) {
                                        Log.d(TAG, "Successfully recreated EGL surface");
                                    } else {
                                        Log.e(TAG, "Failed to recreate EGL surface");
                                    }
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Error recreating surface", e);
            }
                        }
                        // For EGL_BAD_ACCESS, try recreating the entire context
                        else if (error == EGL14.EGL_BAD_ACCESS && attempt < 2) {
                            Log.d(TAG, "Trying to recover from EGL_BAD_ACCESS by releasing and recreating EGL");
                            try {
                                // Release current EGL resources
                                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                                    EGL14.eglDestroySurface(eglDisplay, eglSurface);
                                    eglSurface = EGL14.EGL_NO_SURFACE;
                                }
                                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                                    eglContext = EGL14.EGL_NO_CONTEXT;
                                }
                                
                                // Recreate EGL resources
                                int[] attribList = { 
                                    EGL14.EGL_RED_SIZE, 8, 
                                    EGL14.EGL_GREEN_SIZE, 8, 
                                    EGL14.EGL_BLUE_SIZE, 8, 
                                    EGL14.EGL_ALPHA_SIZE, 8, 
                                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 
                                    EGL14.EGL_NONE 
                                };
                                EGLConfig[] configs = new EGLConfig[1];
                                int[] numConfigs = new int[1];
                                EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0);
                                EGLConfig eglRecoveryConfig = configs[0];

                                int[] contextAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
                                eglContext = EGL14.eglCreateContext(eglDisplay, eglRecoveryConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
                                
                                int[] surfaceAttribs = { EGL14.EGL_NONE };
                                eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglRecoveryConfig, outputSurface, surfaceAttribs, 0);
                                // Keep stored config in sync with the recovered one
                                eglConfig = eglRecoveryConfig;
                                
                                // Try again with the new context
                                Thread.sleep(50); // Short delay to let things settle
                            } catch (Exception e) {
                                Log.e(TAG, "Error during EGL recovery", e);
                            }
                        } else {
                            // For other errors, just wait a bit and retry
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Exception during eglMakeCurrent", e);
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            
            if (!madeCurrentSuccessfully) {
                Log.e(TAG, "renderToEncoder: eglMakeCurrent ultimately failed; skipping this frame");
                return; // Abort this frame gracefully instead of throwing
            }
            
            // Check if cameraSurfaceTexture is still valid
            if (cameraSurfaceTexture == null) {
                throw new IllegalStateException("cameraSurfaceTexture is null");
            }
            
            // Wait for a new frame if needed
            synchronized (frameSyncObject) {
                while (!frameAvailable) {
                    try {
                        frameSyncObject.wait(100);
                        if (!frameAvailable) {
                            Log.w(TAG, "renderToEncoder: frame wait timed out");
                            return;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                frameAvailable = false;
            }
            
            // Update texture image
            float[] texMatrix = new float[16];
            try {
            cameraSurfaceTexture.updateTexImage();
            cameraSurfaceTexture.getTransformMatrix(texMatrix);
            } catch (Exception e) {
                Log.e(TAG, "Error updating texture image", e);
                return;
            }
            
            // Update matrices for correct orientation
            updateMatrices();
            
            // Set presentation time using synchronized timestamp
            try {
                long cameraTimestamp = cameraSurfaceTexture.getTimestamp();
                long presentationTimeNanos;
                

                // Get synchronized timestamp from recording pipeline if available
                if (recordingPipeline != null) {
                    // Convert synchronized timestamp (microseconds) to nanoseconds
                    presentationTimeNanos = recordingPipeline.getSynchronizedVideoTimestamp(cameraTimestamp) * 1000L;
                } else {
                    // Fallback to camera timestamp (already in nanoseconds)
                    presentationTimeNanos = cameraTimestamp;
                }
                
                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNanos);

            } catch (Exception e) {
                Log.e(TAG, "Error setting presentation time", e);
                // Continue anyway
            }
            
            // Determine correct texture matrix based on orientation
            boolean isLandscape = false;
            if (userOrientationSetting != null) {
                isLandscape = "landscape".equalsIgnoreCase(userOrientationSetting);
            } else {
                isLandscape = (deviceOrientation == 1 || deviceOrientation == 3);
            }
            
            float[] encoderTexMatrix;
            if (isLandscape) {
                // Apply vertical flip for landscape only
                float[] fixedTexMatrix = new float[16];
                Matrix.setIdentityM(fixedTexMatrix, 0);
                Matrix.scaleM(fixedTexMatrix, 0, 1f, -1f, 1f);
                Matrix.translateM(fixedTexMatrix, 0, 0f, -1f, 0f);
                encoderTexMatrix = fixedTexMatrix;
            } else {
                // Portrait: use original texMatrix
                encoderTexMatrix = texMatrix;
            }
            
            // Draw to encoder
            try {
            drawOESTexture(recordingMvpMatrix, encoderTexMatrix);
            drawWatermark();
                
                // Swap buffers to complete the frame
                if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                    int error = EGL14.eglGetError();
                    
                    // If we get EGL_BAD_SURFACE, mark the surface as invalid for next time
                    if (error == EGL14.EGL_BAD_SURFACE) {
                        Log.e(TAG, "eglSwapBuffers failed with EGL_BAD_SURFACE, marking surface for recreation");
                        if (eglSurface != EGL14.EGL_NO_SURFACE) {
                            try {
                                EGL14.eglDestroySurface(eglDisplay, eglSurface);
                            } catch (Exception e) {
                                Log.e(TAG, "Error destroying bad surface", e);
                            }
                            eglSurface = EGL14.EGL_NO_SURFACE;
                        }
                    } else {
                        Log.e(TAG, "eglSwapBuffers failed with error " + error);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error during drawing or buffer swap", e);
            }
        }
    }

    /**
     * Renders only to the preview surface.
     * This method can be called separately from renderToEncoder to update the preview
     * when the app is in the foreground.
     */
    public void renderToPreview() {
        // Use unified EGL context/display for preview
        if (!initialized || currentPreviewSurface == null || !currentPreviewSurface.isValid() || released) {
            return;
        }
        synchronized (previewRenderLock) {
            if (!initialized || currentPreviewSurface == null || !currentPreviewSurface.isValid() || released) {
                return;
            }
            // Backoff window to avoid repeated creation attempts if native window is currently connected elsewhere
            if (previewCreateRetryDeadlineNs > 0 && System.nanoTime() < previewCreateRetryDeadlineNs) {
                return;
            }
            if (previewEglSurface == EGL14.EGL_NO_SURFACE) {
                try {
                    int[] surfaceAttribs = { EGL14.EGL_NONE };
                    previewEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, currentPreviewSurface, surfaceAttribs, 0);
                    if (previewEglSurface == EGL14.EGL_NO_SURFACE) {
                        Log.e(TAG, "Failed to create EGL surface for preview");
                        // Backoff a bit before next attempt
                        previewCreateRetryDeadlineNs = System.nanoTime() + 200_000_000L; // 200ms
                        return;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Exception creating EGL surface for preview", e);
                    previewCreateRetryDeadlineNs = System.nanoTime() + 200_000_000L;
                    return;
                }
            }
            if (!EGL14.eglMakeCurrent(eglDisplay, previewEglSurface, previewEglSurface, eglContext)) {
                Log.w(TAG, "renderToPreview: eglMakeCurrent failed, releasing preview surface");
                releasePreviewEGL();
                previewCreateRetryDeadlineNs = System.nanoTime() + 200_000_000L;
                return;
            }
            // Success: clear retry gate
            previewCreateRetryDeadlineNs = 0L;
            float[] texMatrix = new float[16];
            if (cameraSurfaceTexture != null) {
                cameraSurfaceTexture.getTransformMatrix(texMatrix);
            } else {
                Matrix.setIdentityM(texMatrix, 0);
            }
            if (oesTextureId == 0) {
                Log.w(TAG, "OES texture ID is 0, skipping preview draw");
                return;
            }
            updateMatrices();
            drawOESTexture(previewMvpMatrix, texMatrix);
            boolean swapOk = EGL14.eglSwapBuffers(eglDisplay, previewEglSurface);
            if (!swapOk) {
                int error = EGL14.eglGetError();
                Log.w(TAG, "Preview eglSwapBuffers failed: 0x" + Integer.toHexString(error));
                if (error == EGL14.EGL_BAD_SURFACE) {
                    // Force recreate next time
                    try {
                        EGL14.eglDestroySurface(eglDisplay, previewEglSurface);
                    } catch (Exception e) {
                        Log.w(TAG, "Error destroying bad preview surface", e);
                    }
                    previewEglSurface = EGL14.EGL_NO_SURFACE;
                }
            }
            // Restore encoder surface as current
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
            }
        }
    }

    private void setupEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("eglGetDisplay failed");
        
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) throw new RuntimeException("eglInitialize failed");

        // Include EGL_RECORDABLE_ANDROID for better driver compatibility (e.g., Huawei)
        int[] attribList = {
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            android.opengl.EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0) || numConfigs[0] <= 0) {
            throw new RuntimeException("eglChooseConfig failed");
        }
        eglConfig = configs[0];

        int[] contextAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("eglCreateContext failed");
        
        int[] surfaceAttribs = { EGL14.EGL_NONE };
    eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, outputSurface, surfaceAttribs, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw new RuntimeException("eglCreateWindowSurface failed");
        
    if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) throw new RuntimeException("eglMakeCurrent failed");
    }
    
    public void initializeEGL() {
        setupEGL();
        setupOESShader();
        setupSimpleWatermarkShader();
        setupOESTexture();
        
        // Use grafika FullFrameRect for better texture rendering
        mFullFrameBlit = new com.fadcam.opengl.grafika.FullFrameRect(
            new com.fadcam.opengl.grafika.Texture2dProgram(
                com.fadcam.opengl.grafika.Texture2dProgram.ProgramType.TEXTURE_EXT));
        
        createCameraSurfaceTexture();
        setupWatermarkTexture();
        updateMatrices();
        initialized = true;
    }

    private void setupOESTexture() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        oesTextureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }
    
    private void createCameraSurfaceTexture() {
        cameraSurfaceTexture = new SurfaceTexture(oesTextureId);
        cameraSurfaceTexture.setDefaultBufferSize(videoWidth, videoHeight);
        cameraInputSurface = new Surface(cameraSurfaceTexture);
        cameraSurfaceTexture.setOnFrameAvailableListener(surfaceTexture -> {
            synchronized (frameSyncObject) {
                frameAvailable = true;
                frameSyncObject.notifyAll();
            }
            if (externalFrameListener != null) {
                externalFrameListener.onFrameAvailable();
            }
        });
    }

    private void setupWatermarkTexture() {
        watermarkBitmap = Bitmap.createBitmap(WATERMARK_BITMAP_WIDTH, WATERMARK_BITMAP_HEIGHT, Bitmap.Config.ARGB_8888);
        watermarkTextureId = createTexture();
        updateWatermarkTexture();
    }

    private int createTexture() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return textures[0];
    }
    
    public void setWatermarkText(String text) {
        this.watermarkText = text;
        updateWatermarkTexture();
    }

    // -------------- Fix Start for this method(updateWatermarkTextOnGlThread)-----------
    /**
     * Updates watermark text ensuring it runs on the GL thread with a current EGL context.
     * Call this from the render thread.
     */
    public void updateWatermarkTextOnGlThread(String text) {
        // Avoid unnecessary texture work if unchanged
        if (text == null) text = "";
        if (text.equals(this.watermarkText) && watermarkTextureId != 0 && watermarkBitmap != null) {
            return;
        }
        this.watermarkText = text;
        // Ensure EGL context is current for safe GL texture upload
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE && eglContext != EGL14.EGL_NO_CONTEXT) {
                if (!EGL14.eglGetCurrentContext().equals(eglContext)) {
                    EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to ensure EGL current for watermark update", e);
        }
        updateWatermarkTexture();
    }
    // -------------- Fix Ended for this method(updateWatermarkTextOnGlThread)-----------

    private void updateWatermarkTexture() {
        // Use a smaller font for dashcam style
        watermarkPaint.setTextSize(20);
        // Restore isPortrait for NDC scaling
        String orientationPref = com.fadcam.SharedPreferencesManager.getInstance(context).getVideoOrientation();
        boolean isPortrait = "portrait".equalsIgnoreCase(orientationPref);
        watermarkPaint.setAntiAlias(true);
        watermarkPaint.setARGB(255, 255, 255, 255);
        watermarkPaint.setShadowLayer(1.5f, 0f, 1.5f, Color.BLACK);
        watermarkPaint.setLetterSpacing(0.08f);
        // Set Ubuntu font for watermark text
        try {
            android.graphics.Typeface ubuntuTypeface = android.graphics.Typeface.createFromAsset(context.getAssets(), "ubuntu_regular.ttf");
            watermarkPaint.setTypeface(ubuntuTypeface);
        } catch (Exception e) {
            // Fallback to default bold if Ubuntu font is not found
            watermarkPaint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT_BOLD, android.graphics.Typeface.BOLD));
        }
        String text = watermarkText;
        int padding = 32;
        // Use a fixed bitmap width for all watermark options
        dynamicBitmapWidth = 800;
        android.text.TextPaint textPaint = new android.text.TextPaint(watermarkPaint);
        if (watermarkBitmap == null || watermarkBitmap.getWidth() != dynamicBitmapWidth || watermarkBitmap.getHeight() != dynamicBitmapHeight) {
            watermarkBitmap = Bitmap.createBitmap(dynamicBitmapWidth, dynamicBitmapHeight, Bitmap.Config.ARGB_8888);
        }
        Canvas canvas = new Canvas(watermarkBitmap);
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
        int maxWidth = dynamicBitmapWidth - padding;
        android.text.StaticLayout staticLayout = new android.text.StaticLayout(
            text, textPaint, maxWidth, android.text.Layout.Alignment.ALIGN_NORMAL, 1.0f, 0f, false);
        int textHeight = staticLayout.getHeight();
        int rectHeight = textHeight + 8; // 4px top/bottom padding
        if (rectHeight > dynamicBitmapHeight) rectHeight = dynamicBitmapHeight;
        // Draw text at top left with padding
        canvas.save();
        float textX = padding / 2f;
        float textY = 4; // 4px top padding
        canvas.translate(textX, textY);
        staticLayout.draw(canvas);
        canvas.restore();
        // Flip the bitmap vertically before uploading to OpenGL
        android.graphics.Matrix flipMatrix = new android.graphics.Matrix();
        flipMatrix.preScale(1.0f, -1.0f);
        Bitmap flippedBitmap = Bitmap.createBitmap(watermarkBitmap, 0, 0, watermarkBitmap.getWidth(), watermarkBitmap.getHeight(), flipMatrix, true);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, watermarkTextureId);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, flippedBitmap, 0);
        flippedBitmap.recycle();
        // Use orientation from SharedPreferencesManager
        float ndcWidth, ndcHeight;
        float bitmapAspect = (float)dynamicBitmapWidth / (float)dynamicBitmapHeight;
        if (isPortrait) {
            // Portrait: use previously working logic (scale by width)
            float targetFractionOfWidth = 0.8f;
            ndcWidth = targetFractionOfWidth * 2.0f;
            ndcHeight = ndcWidth / bitmapAspect;
            ndcHeight = ndcHeight / 1.7f; // Make height much smaller in portrait
        } else {
            // Landscape: scale by width, but use a smaller fraction (e.g., 0.7)
            float targetFractionOfWidth = 0.7f;
            ndcWidth = targetFractionOfWidth * 2.0f;
            ndcHeight = ndcWidth / bitmapAspect;
            ndcHeight = ndcHeight * 1.7f; // Make height much bigger in landscape
        }
        float[] rectVerts = computeWatermarkRectVertices(ndcWidth, ndcHeight);
        watermarkRectBuffer = ByteBuffer.allocateDirect(rectVerts.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        watermarkRectBuffer.put(rectVerts).position(0);
    }

    public Surface getCameraInputSurface() {
        return cameraInputSurface;
    }

    private void setupOESShader() {
        String vertexShaderCode = "uniform mat4 uMVPMatrix;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "    gl_Position = uMVPMatrix * aPosition;\n" +
            "    vTextureCoord = (uTexMatrix * aTextureCoord).xy;\n" +
            "}\n";
        String fragmentShaderCode = "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES uTexture;\n" +
            "uniform float uExposureCompensation;\n" +
            "void main() {\n" +
            "    vec4 color = texture2D(uTexture, vTextureCoord);\n" +
            "    // Apply exposure compensation as power of 2 (EV stops)\n" +
            "    float exposureFactor = pow(2.0, uExposureCompensation);\n" +
            "    color.rgb *= exposureFactor;\n" +
            "    gl_FragColor = color;\n" +
            "}\n";
            
        oesProgram = createProgram(vertexShaderCode, fragmentShaderCode);
        oesPositionHandle = GLES20.glGetAttribLocation(oesProgram, "aPosition");
        oesTexCoordHandle = GLES20.glGetAttribLocation(oesProgram, "aTextureCoord");
        oesTextureHandle = GLES20.glGetUniformLocation(oesProgram, "uTexture");
        oesMvpMatrixHandle = GLES20.glGetUniformLocation(oesProgram, "uMVPMatrix");
        oesTexMatrixHandle = GLES20.glGetUniformLocation(oesProgram, "uTexMatrix");
        oesExposureHandle = GLES20.glGetUniformLocation(oesProgram, "uExposureCompensation");
    }

    private void drawOESTexture(float[] mvpMatrix, float[] texMatrix) {
        // ----- Fix Start for this method(drawOESTexture)-----
        try {
            if (released || oesTextureId == 0) {
                Log.w(TAG, "drawOESTexture: Renderer released or OES texture invalid, skipping draw");
                return;
            }
            // Check if texture is valid before drawing
            int[] textureArray = new int[1];
            GLES20.glGetIntegerv(GLES11Ext.GL_TEXTURE_BINDING_EXTERNAL_OES, textureArray, 0);
            if (textureArray[0] == 0) {
                long now = System.currentTimeMillis();
                if (!warnedNoExternalTexture || now - lastNoExternalTextureWarnMs > 5000) {
                            long nowMs = System.currentTimeMillis();
                            // Rate-limit: log at most once every 2000ms unless verbose enabled
                            if (VERBOSE_GL_LOGS || nowMs - lastNoExternalTextureWarnMs > 2000) {
                                Log.w(TAG, "No external texture bound, attempting to rebind");
                                lastNoExternalTextureWarnMs = nowMs;
                            }
                    warnedNoExternalTexture = true;
                    lastNoExternalTextureWarnMs = now;
                }
                try {
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
                    // Clear any existing GL errors
                    int error = GLES20.glGetError();
                    if (error != GLES20.GL_NO_ERROR) {
                        Log.w(TAG, "Cleared GL error when binding texture: 0x" + Integer.toHexString(error));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error rebinding texture", e);
                    return;
                }
            } else {
                // Texture is bound; reset the warning gate
                warnedNoExternalTexture = false;
            }
            // Clear any existing GL errors before drawing
            int error = GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR) {
                Log.w(TAG, "Clearing GL error before drawing: 0x" + Integer.toHexString(error));
            }
            if (mFullFrameBlit != null) {
                try {
                    com.fadcam.opengl.grafika.Texture2dProgram program = mFullFrameBlit.getProgram();
                    if (program != null) {
                        try {
                            int[] currentProgramArray = new int[1];
                            GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, currentProgramArray, 0);
                            int currentProgram = currentProgramArray[0];
                            int programHandle = program.getProgramHandle();
                            GLES20.glUseProgram(programHandle);
                            
                            // Set exposure compensation uniform if available
                            int exposureLoc = program.getExposureCompensationLocation();
                            if (exposureLoc >= 0) {
                                GLES20.glUniform1f(exposureLoc, currentExposureCompensation);
                                Log.d(TAG, "Set exposure compensation to Grafika shader: " + currentExposureCompensation);
                            }
                            
                            int mvpLoc = GLES20.glGetUniformLocation(programHandle, "uMVPMatrix");
                            if (mvpLoc >= 0) {
                                GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvpMatrix, 0);
                            }
                            GLES20.glUseProgram(currentProgram);
                            error = GLES20.glGetError();
                            if (error != GLES20.GL_NO_ERROR) {
                                Log.w(TAG, "Cleared GL error after matrix setup: 0x" + Integer.toHexString(error));
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error setting MVP matrix", e);
                        }
                    }
                    try {
                        mFullFrameBlit.drawFrame(oesTextureId, texMatrix);
                    } catch (RuntimeException e) {
                        Log.e(TAG, "Error with mFullFrameBlit, trying fallback method", e);
                        drawWithFallbackMethod(mvpMatrix, texMatrix);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in mFullFrameBlit drawing", e);
                    drawWithFallbackMethod(mvpMatrix, texMatrix);
                }
            } else {
                drawWithFallbackMethod(mvpMatrix, texMatrix);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in drawOESTexture", e);
        }
        // ----- Fix Ended for this method(drawOESTexture)-----
    }
    
    /**
     * Fallback drawing method when the primary method fails
     */
    private void drawWithFallbackMethod(float[] mvpMatrix, float[] texMatrix) {
        // ----- Fix Start for this method(drawWithFallbackMethod)-----
        try {
            // Clear any existing errors
            int error = GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR) {
                Log.w(TAG, "Clearing GL error before fallback: 0x" + Integer.toHexString(error));
            }
            
            // Use the basic shader program
            GLES20.glUseProgram(oesProgram);

            // Set the matrices
            GLES20.glUniformMatrix4fv(oesMvpMatrixHandle, 1, false, mvpMatrix, 0);
            GLES20.glUniformMatrix4fv(oesTexMatrixHandle, 1, false, texMatrix, 0);

            // Set up vertex attributes
            GLES20.glEnableVertexAttribArray(oesPositionHandle);
            GLES20.glVertexAttribPointer(oesPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
            GLES20.glEnableVertexAttribArray(oesTexCoordHandle);
            GLES20.glVertexAttribPointer(oesTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);
            
            // Set up texture
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
            GLES20.glUniform1i(oesTextureHandle, 0);
            
            // Set exposure compensation uniform
            GLES20.glUniform1f(oesExposureHandle, currentExposureCompensation);

            // Draw
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            // Clean up
            GLES20.glDisableVertexAttribArray(oesPositionHandle);
            GLES20.glDisableVertexAttribArray(oesTexCoordHandle);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
            GLES20.glUseProgram(0);
        } catch (Exception e) {
            Log.e(TAG, "Error in fallback drawing method", e);
        }
        // ----- Fix Ended for this method(drawWithFallbackMethod)-----
    }

    private void setupSimpleWatermarkShader() {
        // Vertex and fragment shader for rendering watermark bitmap as a texture
        String vertexShaderCode =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "  gl_Position = aPosition;\n" +
            "  vTexCoord = aTexCoord;\n" +
            "}";
        String fragmentShaderCode =
            "precision mediump float;\n" +
            "uniform sampler2D uTexture;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "  vec4 color = texture2D(uTexture, vTexCoord);\n" +
            "  if (color.a < 0.01) discard;\n" +
            "  gl_FragColor = color;\n" +
            "}";
        watermarkProgram = createProgram(vertexShaderCode, fragmentShaderCode);
        watermarkPositionHandle = GLES20.glGetAttribLocation(watermarkProgram, "aPosition");
        watermarkTexCoordHandle = GLES20.glGetAttribLocation(watermarkProgram, "aTexCoord");
        watermarkSamplerHandle = GLES20.glGetUniformLocation(watermarkProgram, "uTexture");
        // Setup texcoord buffer
        watermarkTexCoordBuffer = ByteBuffer.allocateDirect(WATERMARK_TEXCOORDS.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        watermarkTexCoordBuffer.put(WATERMARK_TEXCOORDS).position(0);
    }

    private void drawWatermark() {
        if (watermarkText == null || watermarkText.isEmpty()) {
            // Do not draw any watermark if text is empty (no watermark option)
            return;
        }
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUseProgram(watermarkProgram);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, watermarkTextureId);
        GLES20.glEnableVertexAttribArray(watermarkPositionHandle);
        GLES20.glVertexAttribPointer(watermarkPositionHandle, 2, GLES20.GL_FLOAT, false, 0, watermarkRectBuffer);
        GLES20.glEnableVertexAttribArray(watermarkTexCoordHandle);
        GLES20.glVertexAttribPointer(watermarkTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, watermarkTexCoordBuffer);
        GLES20.glUniform1i(watermarkSamplerHandle, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(watermarkPositionHandle);
        GLES20.glDisableVertexAttribArray(watermarkTexCoordHandle);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glUseProgram(0);
        GLES20.glDisable(GLES20.GL_BLEND);
    }
    
    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        return program;
    }

    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        
        final int[] compileStatus = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
        if (compileStatus[0] == 0) {
            Log.e(TAG, "Error compiling shader: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            shader = 0;
        }
        
        return shader;
    }

    public void setPreviewSurface(Surface previewSurface) {
        synchronized (previewRenderLock) {
            if (currentPreviewSurface == previewSurface) return;
            // Destroy any existing preview EGLSurface tied to old Surface
            if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
                try {
            try { EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT); } catch (Exception ignore) {}
            EGL14.eglDestroySurface(eglDisplay, previewEglSurface);
                } catch (Exception e) {
                    Log.w(TAG, "Error destroying old preview EGLSurface", e);
                }
                previewEglSurface = EGL14.EGL_NO_SURFACE;
            }
            currentPreviewSurface = previewSurface;
        // Reset backoff on new surface
        previewCreateRetryDeadlineNs = 0L;
        }
    }

    public void releasePreviewEGL() {
        // Unified: only destroy the preview EGLSurface; keep main display/context
        synchronized (previewRenderLock) {
            if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
                try {
            try { EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT); } catch (Exception ignore) {}
            EGL14.eglDestroySurface(eglDisplay, previewEglSurface);
                } catch (Exception e) {
                    Log.w(TAG, "Error destroying preview EGLSurface", e);
                }
                previewEglSurface = EGL14.EGL_NO_SURFACE;
            }
            currentPreviewSurface = null;
        previewCreateRetryDeadlineNs = 0L;
        }
    }
    
    public void release() {
        // ----- Fix Start for this method(release)-----
        synchronized (renderLock) {
            if (released) {
                Log.d(TAG, "release called more than once; ignoring");
                return;
            }
            released = true;
            try {
                releaseEGLResources();
            } catch (Exception e) {
                Log.e(TAG, "Exception during releaseEGLResources", e);
            }
            try {
                releasePreviewEGL();
            } catch (Exception e) {
                Log.e(TAG, "Exception during releasePreviewEGL", e);
            }
            // Null out all references
            outputSurface = null;
            cameraInputSurface = null;
            cameraSurfaceTexture = null;
            watermarkBitmap = null;
            watermarkTextureId = 0;
            mFullFrameBlit = null;
            initialized = false;
        }
        // ----- Fix Ended for this method(release)-----
    }
    
    public void setDeviceOrientation(int orientation) {
        if (this.deviceOrientation == orientation) return;
        this.deviceOrientation = orientation;
        updateMatrices();
    }

    public void setSurfaceDimensions(int width, int height) {
        if (mSurfaceWidth != width || mSurfaceHeight != height) {
            Log.d(TAG, "Surface dimensions updated: " + width + "x" + height);
            mSurfaceWidth = width;
            mSurfaceHeight = height;
            updateMatrices();
        }
    }

    // Add a setter for encoder dimensions
    public void setEncoderDimensions(int width, int height) {
        this.encoderWidth = width;
        this.encoderHeight = height;
    }

    /**
     * Sets the exposure compensation value for the GL shader.
     * This method is thread-safe and can be called from any thread.
     *
     * @param evStops Exposure compensation in EV stops (e.g., -2.0 to +2.0)
     */
    public void setExposureCompensation(float evStops) {
        // Clamp to reasonable range to prevent shader overflow
        currentExposureCompensation = Math.max(-4.0f, Math.min(4.0f, evStops));
        Log.d(TAG, "GL exposure compensation set to " + currentExposureCompensation + " EV stops");
    }

    /**
     * Updates the encoder output surface after a segment rollover.
     * This method should be called when a new MediaCodec encoder is created during segment rollover.
     * 
     * @param newSurface The new encoder input surface from the MediaCodec
     */
    public void updateEncoderSurface(Surface newSurface) {
        if (newSurface == null) {
            Log.e(TAG, "Cannot update encoder surface with null surface");
            return;
        }
        
        synchronized (renderLock) {
            Log.d(TAG, "Updating encoder output surface");
            
            // Save the new surface
            this.outputSurface = newSurface;
            
            // We need to update our EGL surface to point to the new encoder surface
            if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT) {
                try {
                    // First make sure we're not using the current surface
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, eglContext);
                    
                    // Destroy the old surface if it exists
                    if (eglSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(eglDisplay, eglSurface);
                        eglSurface = EGL14.EGL_NO_SURFACE;
                    }
                    
                    // Create a new EGL surface with the new encoder surface using stored config
                    int[] surfaceAttribs = { EGL14.EGL_NONE };
                    eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, 
                                                             eglConfig, 
                                                             outputSurface, 
                                                             surfaceAttribs, 0);
                    
                    if (eglSurface == EGL14.EGL_NO_SURFACE) {
                        Log.e(TAG, "Failed to create new EGL surface for encoder");
                        return;
                    }
                    
                    // Make the new surface current
                    if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                        Log.e(TAG, "Failed to make new encoder surface current");
                        return;
                    }
                    
                    Log.d(TAG, "Successfully updated encoder EGL surface");
                } catch (Exception e) {
                    Log.e(TAG, "Error updating encoder surface", e);
                }
            } else {
                Log.d(TAG, "EGL not initialized yet, just updating surface reference");
            }
        }
    }
    
    // Helper method to get a compatible EGL config
    private EGLConfig getEglConfig(EGLDisplay eglDisplay) {
        int[] configAttribs = {
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        };
        
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
            Log.e(TAG, "eglChooseConfig failed");
            return null;
        }
        
        return configs[0];
    }

    private void updateMatrices() {
        int rotationDegrees = getRequiredRotation();
        boolean isPortrait = (videoHeight > videoWidth && (rotationDegrees == 90 || rotationDegrees == 270)) ||
                            (videoWidth > videoHeight && (rotationDegrees == 0 || rotationDegrees == 180));
        if (isPortrait) {
            dynamicBitmapWidth = 1200;
            dynamicBitmapHeight = 36;
        } else {
            dynamicBitmapWidth = 800;
            dynamicBitmapHeight = 48;
        }
        // Log.d(TAG, "updateMatrices: rotationDegrees=" + rotationDegrees + 
        //       ", deviceOrientation=" + deviceOrientation +
        //       ", sensorOrientation=" + sensorOrientation);
        // Log.d("FAD-MATRIX", "Applying rotation: " + rotationDegrees);

        // ----- Fix Start: Use required rotation for encoder output -----
        Matrix.setIdentityM(recordingMvpMatrix, 0);
        Matrix.rotateM(recordingMvpMatrix, 0, rotationDegrees, 0f, 0f, 1f);
        // ----- Fix End: Use required rotation for encoder output -----

        // Preview matrix: handles rotation, mirroring, and aspect ratio
        Matrix.setIdentityM(previewMvpMatrix, 0);
        Matrix.rotateM(previewMvpMatrix, 0, rotationDegrees, 0, 0, 1);
        if (isFrontCamera()) {
            Matrix.scaleM(previewMvpMatrix, 0, -1, 1, 1);
        }
        // Properly handle aspect ratio to avoid stretching/squeezing
        if (mSurfaceWidth <= 0 || mSurfaceHeight <= 0) {
            // Can't calculate aspect ratio yet, return early
            return;
        }

        // Use actual encoder dimensions for aspect ratio
        int orientedVideoWidth = encoderWidth > 0 ? encoderWidth : videoWidth;
        int orientedVideoHeight = encoderHeight > 0 ? encoderHeight : videoHeight;

        float videoAspectRatio = (float) orientedVideoWidth / orientedVideoHeight;
        float previewAspectRatio = (float) mSurfaceWidth / mSurfaceHeight;

        // Log.d(TAG, "Aspect ratios - video: " + videoAspectRatio + 
        //       ", preview: " + previewAspectRatio +
        //       ", orientedVideoWidth: " + orientedVideoWidth +
        //       ", orientedVideoHeight: " + orientedVideoHeight +
        //       ", surfaceWidth: " + mSurfaceWidth +
        //       ", surfaceHeight: " + mSurfaceHeight);
        // Log.d("FAD-MATRIX", "Surface: " + mSurfaceWidth + "x" + mSurfaceHeight);
        // Log.d("FAD-MATRIX", "Encoder: " + orientedVideoWidth + "x" + orientedVideoHeight);

        float scaleX = 1.0f;
        float scaleY = 1.0f;

        if (Math.abs(previewAspectRatio - videoAspectRatio) > 0.01f) {
            if (previewAspectRatio > videoAspectRatio) {
                // Preview is wider than video: pillarbox (scale X down)
                scaleX = videoAspectRatio / previewAspectRatio;
                // Log.d(TAG, "Applying pillarbox with scaleX = " + scaleX);
            } else {
                // Preview is taller than video: letterbox (scale Y down)
                scaleY = previewAspectRatio / videoAspectRatio;
                // Log.d(TAG, "Applying letterbox with scaleY = " + scaleY);
            }
        }
        // Log.d("FAD-MATRIX", String.format("ScaleX: %.2f  ScaleY: %.2f", scaleX, scaleY));
        Matrix.scaleM(previewMvpMatrix, 0, scaleX, scaleY, 1.0f);
        // Log.d("FAD-MATRIX", "recordingMvpMatrix: " + java.util.Arrays.toString(recordingMvpMatrix));
    }

    private int getDisplayRotation() {
        switch (deviceOrientation) {
            case Surface.ROTATION_0: return 0;
            case Surface.ROTATION_90: return 90;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_270: return 270;
            default: return 0;
        }
    }

    private int getRequiredRotation() {
        // ----- Fix Start: Standard camera app rotation logic -----
        int rotation = (sensorOrientation - deviceOrientation + 360) % 360;
        if (isFrontCamera()) {
            rotation = (360 - rotation) % 360;
        }
        // Log.d("FAD-ROT", "Device: " + deviceOrientation + " Sensor: " + sensorOrientation + " ➜ Rotation = " + rotation);
        return rotation;
        // ----- Fix End: Standard camera app rotation logic -----
    }

    private boolean isFrontCamera() {
        // Use a more reliable way to identify front camera if possible
        // For now, we'll go with the common case of front camera having 
        // a sensor orientation of 270 degrees
        return sensorOrientation == 270;
    }

    /**
     * Initializes only the preview EGL context with a dummy surface.
     * This is used when starting recording without a preview surface.
     * The dummy surface is not used for actual rendering.
     * 
     * @param dummySurface A temporary surface to use for EGL initialization
     */
    public void initializePreviewSurfaceOnly(Surface dummySurface) {
        synchronized (previewRenderLock) {
            if (dummySurface == null || !dummySurface.isValid()) {
                Log.e(TAG, "Cannot initialize with invalid dummy surface");
                return;
            }
            // Unified EGL: just create and destroy a temporary preview EGLSurface to warm up drivers
            try {
                int[] surfaceAttribs = { EGL14.EGL_NONE };
                EGLSurface temp = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, dummySurface, surfaceAttribs, 0);
                if (temp != EGL14.EGL_NO_SURFACE) {
                    // Optionally make current briefly
                    EGL14.eglMakeCurrent(eglDisplay, temp, temp, eglContext);
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    EGL14.eglDestroySurface(eglDisplay, temp);
                }
            } catch (Exception e) {
                Log.w(TAG, "initializePreviewSurfaceOnly: temp surface warm-up failed", e);
            }
        }
    }

    /**
     * Renders a black frame to the encoder surface.
     * Used when camera input is unavailable but we want to keep recording.
     */
    public void renderBlackFrame() {
        synchronized (renderLock) {
            if (released) {
                Log.d(TAG, "renderBlackFrame called after release; ignoring");
                return;
            }
            // Don't try to reinitialize if existing EGL resources are lost
            // Just use the existing context if it's valid
            if (!initialized || eglDisplay == EGL14.EGL_NO_DISPLAY || 
                eglContext == EGL14.EGL_NO_CONTEXT || eglSurface == EGL14.EGL_NO_SURFACE) {
                Log.d(TAG, "Cannot render black frame - EGL not initialized and we won't reinitialize");
                return;
            }
            boolean contextMadeCurrent = false;
            try {
                if (EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    contextMadeCurrent = true;
                    GLES20.glViewport(0, 0, encoderWidth > 0 ? encoderWidth : videoWidth, 
                                     encoderHeight > 0 ? encoderHeight : videoHeight);
                    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                    EGL14.eglSwapBuffers(eglDisplay, eglSurface);
                    return;
                } else {
                    int error = EGL14.eglGetError();
                    Log.d(TAG, "Could not make EGL context current: 0x" + Integer.toHexString(error) + 
                             " - this is expected during camera disconnection");
                }
            } catch (Exception e) {
                Log.d(TAG, "Exception rendering black frame - this is expected during camera disconnection");
            } finally {
                if (!contextMadeCurrent) {
                    Log.d(TAG, "Black frame was not rendered - will try again on next frame");
                }
            }
        }
    }
    
    /**
     * Helper method to safely release EGL resources
     */
    private void releaseEGLResources() {
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                try {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing current context", e);
                }
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    try {
                        EGL14.eglDestroySurface(eglDisplay, eglSurface);
                    } catch (Exception e) {
                        Log.w(TAG, "Error destroying surface", e);
                    }
                    eglSurface = EGL14.EGL_NO_SURFACE;
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    try {
                        EGL14.eglDestroyContext(eglDisplay, eglContext);
                    } catch (Exception e) {
                        Log.w(TAG, "Error destroying context", e);
                    }
                    eglContext = EGL14.EGL_NO_CONTEXT;
                }
                try {
                    EGL14.eglTerminate(eglDisplay);
                } catch (Exception e) {
                    Log.w(TAG, "Error terminating display", e);
                }
                eglDisplay = EGL14.EGL_NO_DISPLAY;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error releasing EGL resources", e);
        }
    }
}


