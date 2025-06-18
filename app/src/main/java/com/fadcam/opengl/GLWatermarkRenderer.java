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
import android.util.Log;
import android.view.Surface;
import android.opengl.Matrix;
import android.opengl.EGLExt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * GLWatermarkRenderer handles OpenGL rendering for video recording and preview.
 *
 * This renderer always uses the fixed videoWidth/videoHeight for recording output,
 * and ignores device rotation. Only the preview may use letterboxing for user experience.
 */
public class GLWatermarkRenderer {
    private static final String TAG = "GLWatermarkRenderer";
    private static final int WATERMARK_BITMAP_WIDTH = 1024;
    private static final int WATERMARK_BITMAP_HEIGHT = 128;

    // EGL/GL resources for encoder
    private EGLDisplay eglDisplay;
    private EGLContext eglContext;
    private EGLSurface eglSurface;
    private Surface outputSurface;
    private final Context context;

    // OES texture and camera input
    private int oesTextureId;
    private SurfaceTexture cameraSurfaceTexture;
    private Surface cameraInputSurface;

    // Watermark
    private Bitmap watermarkBitmap;
    private int watermarkTextureId;
    private String watermarkText = "";
    private final Paint watermarkPaint;

    private boolean initialized = false;

    // Preview EGL context/surface management
    private EGLDisplay previewEglDisplay = null;
    private EGLContext previewEglContext = null;
    private EGLSurface previewEglSurface = null;
    private Surface currentPreviewSurface = null;
    private EGLConfig previewEglConfig = null;

    // OES shader and draw logic
    private int oesProgram = 0;
    private int oesPositionHandle = 0;
    private int oesTexCoordHandle = 0;
    private int oesTextureHandle = 0;
    private int oesRotationHandle = -1;
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;
    // Full screen quad vertices - fixed coordinates to ensure aspect ratio is maintained
    private static final float[] VERTICES = {
        // Positions      // Texture Coords
        -1.0f, -1.0f,     0.0f, 0.0f,  // bottom left
         1.0f, -1.0f,     1.0f, 0.0f,  // bottom right
        -1.0f,  1.0f,     0.0f, 1.0f,  // top left
         1.0f,  1.0f,     1.0f, 1.0f   // top right
    };
    // Standard OpenGL texture coordinates (normalized)
    private static final float[] TEXCOORDS = {
        0.0f, 0.0f,  // bottom left
        1.0f, 0.0f,  // bottom right
        0.0f, 1.0f,  // top left
        1.0f, 1.0f   // top right
    };

    private volatile boolean frameAvailable = false;
    private final Object frameSyncObject = new Object();

    // Replace watermark shader and draw logic with a simple white rectangle overlay
    private int simpleWatermarkProgram = 0;
    private int simpleWatermarkPositionHandle = 0;

    // Vertices for a small rectangle in the bottom right corner (x, y, z, w)
    private static final float[] WATERMARK_RECT_VERTICES = {
        0.7f, -1.0f, 0.0f, 1.0f,   // bottom right
        1.0f, -1.0f, 0.0f, 1.0f,   // bottom right outer
        0.7f, -0.8f, 0.0f, 1.0f,   // top right
        1.0f, -0.8f, 0.0f, 1.0f    // top right outer
    };
    private FloatBuffer watermarkRectBuffer;

    private final String orientation;
    private final int sensorOrientation;
    private final int videoWidth;
    private final int videoHeight;
    private final float targetAspectRatio;  // The desired aspect ratio based on selected resolution

    // Transform matrix for texture coordinates
    private final float[] transformMatrix = new float[16];
    private final float[] combinedTransformMatrix = new float[16];
    private boolean watermarkEnabled = true; // Default to true since watermark is a core feature

    // Add lock objects for synchronization
    private final Object renderLock = new Object();
    private final Object previewRenderLock = new Object();
    private volatile boolean previewRenderActive = false;

    // Add shared EGL context flag
    private boolean useSharedEglContext = true;

    private static final int PREVIEW_RENDER_INTERVAL_MS = 33; // Increase to 30fps for smoother preview (was 100)

    // Device orientation: 0=portrait, 1=landscape, 2=reverse portrait, 3=reverse landscape
    private int deviceOrientation = 0; // Default to portrait

    // Surface dimensions for aspect ratio calculations and letterboxing
    private int mSurfaceWidth = 0;
    private int mSurfaceHeight = 0;

    // Add new texture matrix handle
    private int texMatrixHandle = -1;

    public interface OnFrameAvailableListener {
        void onFrameAvailable();
    }
    private OnFrameAvailableListener externalFrameListener;
    public void setOnFrameAvailableListener(OnFrameAvailableListener listener) {
        this.externalFrameListener = listener;
        if (cameraSurfaceTexture != null) {
            cameraSurfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                @Override
                public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                    synchronized (frameSyncObject) {
                        frameAvailable = true;
                        frameSyncObject.notifyAll();
                    }
                    if (externalFrameListener != null) {
                        externalFrameListener.onFrameAvailable();
                    }
                }
            });
        }
    }

    /**
     * Constructor.
     * @param context Android context
     * @param outputSurface EGL window surface for encoder
     * @param orientation Orientation of the device
     * @param sensorOrientation Sensor orientation of the device
     * @param videoWidth Width of the video
     * @param videoHeight Height of the video
     */
    public GLWatermarkRenderer(Context context, Surface outputSurface, String orientation, int sensorOrientation, int videoWidth, int videoHeight) {
        this.context = context;
        this.outputSurface = outputSurface;
        this.orientation = orientation;
        this.sensorOrientation = sensorOrientation;
        
        // Store width and height as they are - no swapping regardless of orientation
        // This ensures consistent dimensions regardless of phone rotation
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        
        // Initialize surface dimensions with video dimensions as default
        this.mSurfaceWidth = videoWidth;
        this.mSurfaceHeight = videoHeight;
        
        Log.d(TAG, "GLWatermarkRenderer initialized with fixed dimensions: " + videoWidth + "x" + videoHeight +
              " in " + orientation + " orientation (sensorOrientation=" + sensorOrientation + ")");
        
        // Calculate target aspect ratio based on the fixed output dimensions
        this.targetAspectRatio = (float) videoWidth / videoHeight;
        
        // Initialize default watermark paint
        watermarkPaint = new Paint();
        watermarkPaint.setTextSize(48);
        watermarkPaint.setAntiAlias(true);
        watermarkPaint.setARGB(255, 255, 255, 255);
        watermarkPaint.setShadowLayer(1f, 0f, 1f, Color.BLACK);
        
        // Initialize vertex and texture coordinate buffers with default values
        vertexBuffer = ByteBuffer.allocateDirect(VERTICES.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(VERTICES).position(0);
        
        texCoordBuffer = ByteBuffer.allocateDirect(TEXCOORDS.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        texCoordBuffer.put(TEXCOORDS).position(0);
    }

    /**
     * Determines the target aspect ratio based on resolution dimensions
     * @param width Width of selected resolution
     * @param height Height of selected resolution
     * @return Target aspect ratio (width/height)
     */
    private float determineTargetAspectRatio(int width, int height) {
        // Simply return the exact aspect ratio of the selected resolution
        // We're not trying to fit it into any standard ratio anymore
        return (float) width / height;
    }

    private void setupEGL() {
        try {
        // 1. Get EGL display
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "Unable to get EGL14 display");
            return;
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                Log.e(TAG, "Unable to initialize EGL14: " + EGL14.eglGetError());
            return;
        }
            Log.d(TAG, "EGL initialized, version " + version[0] + "." + version[1]);

            // 2. Choose EGL config with RGB888 and proper buffer settings
        int[] attribList = {
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                // Add buffer configuration
                EGL14.EGL_BUFFER_SIZE, 32,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_SAMPLE_BUFFERS, 0,
                // Ensure direct rendering to avoid intermediate copies
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        };

        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0)) {
                Log.e(TAG, "Unable to choose EGL config: " + EGL14.eglGetError());
            return;
        }
            
            if (numConfigs[0] <= 0 || configs[0] == null) {
                Log.e(TAG, "No suitable EGL configs found");
                return;
            }
            
        EGLConfig eglConfig = configs[0];

            // 3. Create EGL context with proper flags - IMPORTANT: Add EGL_CONTEXT_CLIENT_VERSION
        int[] contextAttribs = {
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) {
                Log.e(TAG, "Unable to create EGL context: " + EGL14.eglGetError());
            return;
        }

            // 4. Create EGL window surface with proper attributes
        int[] surfaceAttribs = {
                // Ensure proper buffer behavior
                EGL14.EGL_RENDER_BUFFER, EGL14.EGL_BACK_BUFFER,
            EGL14.EGL_NONE
        };
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, outputSurface, surfaceAttribs, 0);
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "Unable to create EGL window surface: " + EGL14.eglGetError());
            return;
        }

            // 5. Make context current and verify configuration
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                Log.e(TAG, "Unable to make EGL context current: " + EGL14.eglGetError());
            return;
        }

            // Verify the configuration
            int[] value = new int[1];
            EGL14.eglQueryContext(eglDisplay, eglContext, EGL14.EGL_CONTEXT_CLIENT_VERSION, value, 0);
            Log.d(TAG, "EGL Context Client Version: " + value[0]);
            
            // Check for any OpenGL errors
            int glError = GLES20.glGetError();
            if (glError != GLES20.GL_NO_ERROR) {
                Log.e(TAG, "OpenGL error after EGL setup: 0x" + Integer.toHexString(glError));
            } else {
                Log.d(TAG, "EGL setup completed successfully");
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception during EGL setup", e);
        }
    }

    private void setupOESTexture() {
        try {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        oesTextureId = textures[0];
            
            if (oesTextureId == 0) {
                Log.e(TAG, "Failed to generate OES texture");
                return;
            }
            
            // Set proper texture settings for the OES texture
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
            
            // Use bilinear filtering for smoother video texture 
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            
            // Essential for camera textures to avoid artifacts at edges
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            // Check for errors
            int error = GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR) {
                Log.e(TAG, "Error setting up OES texture parameters: 0x" + Integer.toHexString(error));
            } else {
                Log.d(TAG, "OES texture created successfully: " + oesTextureId);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating OES texture", e);
        }
    }

    private void setupWatermarkTexture() {
        // Create initial watermark bitmap and texture
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

    /**
     * Updates the watermark text to be rendered on each frame.
     */
    public void setWatermarkText(String text) {
        this.watermarkText = text;
        updateWatermarkTexture();
    }

    private void updateWatermarkTexture() {
        if (watermarkBitmap == null) return;
        Canvas canvas = new Canvas(watermarkBitmap);
        canvas.drawColor(0x00000000, android.graphics.PorterDuff.Mode.CLEAR);
        canvas.drawText(watermarkText, 32, WATERMARK_BITMAP_HEIGHT / 2, watermarkPaint);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, watermarkTextureId);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, watermarkBitmap, 0);
    }

    /**
     * Renders a frame: draws the camera frame and overlays the watermark.
     */
    public void renderFrame() {
        synchronized (renderLock) {
            if (!initialized) {
                setupEGL();
                setupOESShader();
                setupSimpleWatermarkShader();
                createCameraSurfaceTexture();
                initialized = true;
            }

            try {
                if (eglDisplay == null || eglSurface == null || eglContext == null) {
                    Log.d(TAG, "Skipping renderFrame - EGL resources are null");
                    return;
                }
                if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    Log.e(TAG, "eglMakeCurrent failed: " + EGL14.eglGetError());
                    return;
                }
                
                synchronized (frameSyncObject) {
                    while (!frameAvailable) {
                        try {
                            frameSyncObject.wait(1000000 / 30);
                            if (!frameAvailable) {
                                Log.w(TAG, "Frame wait timed out");
                                return;
                            }
                        } catch (InterruptedException ie) {
                            throw new RuntimeException(ie);
                        }
                    }
                    frameAvailable = false;
                }
                
                if (cameraSurfaceTexture != null) {
                    GLES20.glGetError(); // Clear any previous errors
                    try {
                        cameraSurfaceTexture.updateTexImage();
                    } catch (Exception e) {
                        Log.e(TAG, "Error updating texture image", e);
                        return;
                    }
                }
                
                // Minimal rendering for recording
                renderMinimal();
                
                GLES20.glFinish();
                
                if (eglDisplay != null && eglDisplay != EGL14.EGL_NO_DISPLAY &&
                    eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
                    try {
                        long timestamp = cameraSurfaceTexture != null ?
                            cameraSurfaceTexture.getTimestamp() : System.nanoTime();
                        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestamp);
                        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                            Log.e(TAG, "eglSwapBuffers failed: " + EGL14.eglGetError());
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Exception in eglPresentationTime or eglSwapBuffers", e);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception in renderFrame", e);
            }
        }
    }

    /**
     * Render the current watermarked frame to the preview surface (if valid).
     * For preview, you may rotate or letterbox for user experience, but do not affect recording.
     */
    public void renderToPreview() {
        if (previewEglDisplay == null || previewEglSurface == null || 
            previewEglContext == null || currentPreviewSurface == null || 
            !currentPreviewSurface.isValid()) {
                return;
            }
        synchronized (previewRenderLock) {
            if (previewEglDisplay == null || previewEglSurface == null || 
                previewEglContext == null || currentPreviewSurface == null || 
                !currentPreviewSurface.isValid()) {
                return;
            }
            previewRenderActive = true;
            try {
                boolean result = EGL14.eglMakeCurrent(previewEglDisplay, previewEglSurface, 
                        previewEglSurface, previewEglContext);
                if (!result) {
                    int error = EGL14.eglGetError();
                    Log.e(TAG, "eglMakeCurrent (preview) failed: " + error);
                    previewRenderActive = false;
                    return;
                }
                
                // Clear the whole surface first
                GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                
                // For preview, you may apply rotation/letterboxing for user experience if desired
                int[] viewport = calculatePreservedViewport(mSurfaceWidth, mSurfaceHeight);
                GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
                renderMinimal();
                
                GLES20.glFinish();
                try {
                    if (!EGL14.eglSwapBuffers(previewEglDisplay, previewEglSurface)) {
                        Log.e(TAG, "eglSwapBuffers (preview) failed: " + EGL14.eglGetError());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Exception in eglSwapBuffers (preview)", e);
                }
            } finally {
                previewRenderActive = false;
            }
        }
    }

    /**
     * Release preview EGL context/surface if they exist.
     */
    private void releasePreviewEGL() {
        synchronized (previewRenderLock) {
            // Wait for any active rendering to complete
            while (previewRenderActive) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            
        if (previewEglDisplay != null) {
                // Make sure we're not using the context
                EGL14.eglMakeCurrent(previewEglDisplay, EGL14.EGL_NO_SURFACE, 
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                
            if (previewEglSurface != null && previewEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(previewEglDisplay, previewEglSurface);
                previewEglSurface = null;
            }
            if (previewEglContext != null && previewEglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(previewEglDisplay, previewEglContext);
                previewEglContext = null;
            }
            EGL14.eglTerminate(previewEglDisplay);
            previewEglDisplay = null;
        }
        currentPreviewSurface = null;
        previewEglConfig = null;
        }
    }

    /**
     * Releases all OpenGL and EGL resources.
     */
    public void release() {
        Log.d(TAG, "GLWatermarkRenderer release() called");
        synchronized (renderLock) {
            synchronized (previewRenderLock) {
                // First ensure we're not in the middle of preview rendering
                previewRenderActive = false;
                
                try {
                    // First release preview EGL resources
                    releasePreviewEGL();
                    
                    // Release watermark bitmap
                    if (watermarkBitmap != null) {
                        watermarkBitmap.recycle();
                        watermarkBitmap = null;
                    }
                    
                    // Release Surface and SurfaceTexture in the correct order
                    if (cameraInputSurface != null) {
                        try {
                            Log.d(TAG, "Releasing camera input surface");
                            cameraInputSurface.release();
                        } catch (Exception e) {
                            Log.e(TAG, "Error releasing camera input surface", e);
                        } finally {
                            cameraInputSurface = null;
                        }
                    }
                    
                    // Wait a bit before releasing SurfaceTexture
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    
                    // Release SurfaceTexture
                    if (cameraSurfaceTexture != null) {
                        try {
                            Log.d(TAG, "Releasing camera surface texture");
                            cameraSurfaceTexture.release();
                        } catch (Exception e) {
                            Log.e(TAG, "Error releasing camera surface texture", e);
                        } finally {
                            cameraSurfaceTexture = null;
                        }
                    }
                    
                    // Release EGL resources for encoder
                    if (eglDisplay != null) {
                        try {
                            // Make sure we're not using the context
                            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, 
                                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                                    
                            // Destroy surface first
                            if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
                                EGL14.eglDestroySurface(eglDisplay, eglSurface);
                                eglSurface = null;
                            }
                            
                            // Then destroy context
                            if (eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) {
                                EGL14.eglDestroyContext(eglDisplay, eglContext);
                                eglContext = null;
                            }
                            
                            // Finally terminate display
                            EGL14.eglTerminate(eglDisplay);
                            eglDisplay = null;
                        } catch (Exception e) {
                            Log.e(TAG, "Error releasing EGL resources", e);
                        }
                    }
                    
                    // Reset initialization flag
                    initialized = false;
                    
                    Log.d(TAG, "GLWatermarkRenderer resources fully released");
                } catch (Exception e) {
                    Log.e(TAG, "Error during GLWatermarkRenderer release", e);
                }
            }
        }
    }

    /**
     * Returns the Surface to be used as the camera output target.
     */
    public Surface getCameraInputSurface() {
        return cameraInputSurface;
    }

    private void setupOESShader() {
        // Define the absolute minimal vertex shader
        String vertexShaderCode =
                "attribute vec4 aPosition;\n" +
                "attribute vec2 aTexCoord;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "    gl_Position = aPosition;\n" +
                "    vTexCoord = aTexCoord;\n" +
                "}\n";

        // Define the absolute minimal fragment shader
        String fragmentShaderCode =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTexCoord;\n" +
                "uniform samplerExternalOES uTexture;\n" +
                "void main() {\n" +
                "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
                "}\n";

        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode);
        
        if (vertexShader == 0 || fragmentShader == 0) {
            Log.e(TAG, "Failed to compile shaders");
            return;
        }
        
        oesProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(oesProgram, vertexShader);
        GLES20.glAttachShader(oesProgram, fragmentShader);
        GLES20.glLinkProgram(oesProgram);
        
        // Check link status
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(oesProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Error linking program: " + GLES20.glGetProgramInfoLog(oesProgram));
            GLES20.glDeleteProgram(oesProgram);
            return;
        }
        
        // Get attribute and uniform locations - only the absolute essentials
        oesPositionHandle = GLES20.glGetAttribLocation(oesProgram, "aPosition");
        oesTexCoordHandle = GLES20.glGetAttribLocation(oesProgram, "aTexCoord");
        oesTextureHandle = GLES20.glGetUniformLocation(oesProgram, "uTexture");
        
        Log.d(TAG, "Using absolute minimal shader - no transformations");
    }

    private void setupVertexBuffer() {
        // Create and bind vertex buffer with positions and texture coordinates interleaved
        ByteBuffer bb = ByteBuffer.allocateDirect(VERTICES.length * 4);
        bb.order(ByteOrder.nativeOrder());
        vertexBuffer = bb.asFloatBuffer();
        vertexBuffer.put(VERTICES);
        vertexBuffer.position(0);
        
        Log.d(TAG, "Vertex buffer created with " + VERTICES.length + " floats");
    }

    private int loadShader(int type, String shaderCode) {
        String typeStr = (type == GLES20.GL_VERTEX_SHADER) ? "VERTEX" : "FRAGMENT";
        Log.d(TAG, "Compiling " + typeStr + " shader:\n" + shaderCode);
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String error = GLES20.glGetShaderInfoLog(shader);
            Log.e(TAG, "Shader compile error (" + typeStr + "): " + error + "\nSource:\n" + shaderCode);
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private void drawOESTexture(float[] rotationMatrix) {
        GLES20.glUseProgram(oesProgram);

        // Clear any GL errors before proceeding
        int clearError = GLES20.glGetError();
        if (clearError != GLES20.GL_NO_ERROR) {
            Log.w(TAG, "GL error before drawing: 0x" + Integer.toHexString(clearError));
        }

        // Use vertex buffer for position - we use a fixed quad regardless of orientation
        vertexBuffer.position(0);
        GLES20.glVertexAttribPointer(oesPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(oesPositionHandle);

        // Apply texture coordinates - these stay fixed to ensure consistent mapping
        texCoordBuffer.position(0);
        GLES20.glVertexAttribPointer(oesTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);
        GLES20.glEnableVertexAttribArray(oesTexCoordHandle);

        // Apply rotation matrix to maintain proper orientation
        if (oesRotationHandle != -1) {
            GLES20.glUniformMatrix4fv(oesRotationHandle, 1, false, rotationMatrix, 0);
        } else {
            Log.e(TAG, "Rotation matrix uniform location not found");
        }
        
        // Apply texture transform matrix for proper orientation
        if (texMatrixHandle != -1) {
            // Calculate the combined transform matrix that accounts for both
            // the SurfaceTexture transform and our desired orientation
            float[] combinedMatrix = calculateCombinedTransformMatrix();
            GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, combinedMatrix, 0);
        } else {
            Log.e(TAG, "Texture matrix uniform location not found");
        }

        // Set texture parameters for better visual quality
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glUniform1i(oesTextureHandle, 0);

        // Draw the texture as a full-screen quad
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        
        // Check for errors after drawing
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "GL error after drawing: 0x" + Integer.toHexString(error));
        }
        
        // Disable vertex attributes
        GLES20.glDisableVertexAttribArray(oesPositionHandle);
        GLES20.glDisableVertexAttribArray(oesTexCoordHandle);
    }

    private void drawOESTexture() {
        // Use identity rotation matrix for preview rendering
        float[] identityMatrix = new float[16];
        Matrix.setIdentityM(identityMatrix, 0);
        
        // For preview, apply a rotation based on the orientation just like we do for recording
        if ("portrait".equalsIgnoreCase(orientation)) {
            if (sensorOrientation == 90) { // Back camera
                Matrix.rotateM(identityMatrix, 0, 180, 0, 0, 1.0f); // 180 degrees for back camera portrait
            } else if (sensorOrientation == 270) { // Front camera
                Matrix.rotateM(identityMatrix, 0, 0, 0, 0, 1.0f); // No rotation for front camera portrait
            }
        } 
        // For landscape videos
        else {
            if (sensorOrientation == 90) { // Back camera
                Matrix.rotateM(identityMatrix, 0, 90, 0, 0, 1.0f); // 90 for landscape back
            } else if (sensorOrientation == 270) { // Front camera in landscape mode
                Matrix.rotateM(identityMatrix, 0, 270, 0, 0, 1.0f); // 270 for landscape front
            }
        }
        
        drawOESTexture(identityMatrix);
    }

    private void setupSimpleWatermarkShader() {
        String vertexShaderCode =
                "attribute vec4 aPosition;\n" +
                "void main() {\n" +
                "    gl_Position = aPosition;\n" +
                "}";
        String fragmentShaderCode =
                "precision mediump float;\n" +
                "void main() {\n" +
                "    gl_FragColor = vec4(1.0, 1.0, 1.0, 0.7);\n" + // semi-transparent white
                "}";
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode);
        simpleWatermarkProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(simpleWatermarkProgram, vertexShader);
        GLES20.glAttachShader(simpleWatermarkProgram, fragmentShader);
        GLES20.glLinkProgram(simpleWatermarkProgram);
        simpleWatermarkPositionHandle = GLES20.glGetAttribLocation(simpleWatermarkProgram, "aPosition");
        if (simpleWatermarkPositionHandle == -1) {
            Log.e(TAG, "Simple watermark shader attribute location not found");
        }
        watermarkRectBuffer = ByteBuffer.allocateDirect(WATERMARK_RECT_VERTICES.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        watermarkRectBuffer.put(WATERMARK_RECT_VERTICES).position(0);
    }

    private void drawWatermarkTexture() {
        GLES20.glUseProgram(simpleWatermarkProgram);
        watermarkRectBuffer.position(0);
        GLES20.glEnableVertexAttribArray(simpleWatermarkPositionHandle);
        GLES20.glVertexAttribPointer(simpleWatermarkPositionHandle, 4, GLES20.GL_FLOAT, false, 0, watermarkRectBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(simpleWatermarkPositionHandle);
    }

    /**
     * Initialize EGL context, textures, and shaders.
     * This must be called before any rendering operations.
     */
    public void initializeEGL() {
        Log.d(TAG, "Initializing EGL context and resources");
        
        // First release any existing resources
        releasePreviewEGL();
        
        // Setup main EGL for encoder
        setupEGL();
        
        // Setup OES texture for camera input
        setupOESTexture();
        
        // Create the SurfaceTexture that will feed the encoder
        createCameraSurfaceTexture();
        
        // Setup shaders for rendering
        setupOESShader();
        setupSimpleWatermarkShader();
        
        // Setup vertex buffers
        setupVertexBuffer();
        
        // Setup texture coordinates
        setupTextureCoordinates();
        
        // Create watermark texture
        setupWatermarkTexture();
        
        // Mark as initialized
        initialized = true;
        
        Log.d(TAG, "EGL context and resources initialized successfully");
    }

    /**
     * Updates the camera's SurfaceTexture buffer size to match our fixed 
     * video dimensions, regardless of screen rotation or device orientation.
     */
    private void updateCameraBufferSize() {
        if (cameraSurfaceTexture != null) {
            // Always use the fixed videoWidth and videoHeight without any rotation adjustments
            cameraSurfaceTexture.setDefaultBufferSize(videoWidth, videoHeight);
            
            // Verify the buffer size was set correctly
            Log.d(TAG, "Fixed camera buffer size to " + videoWidth + "x" + videoHeight + 
                  " regardless of device rotation");
        }
    }

    /**
     * Create the camera surface texture from the OES texture ID
     */
    private void createCameraSurfaceTexture() {
        Log.d(TAG, "Creating SurfaceTexture for camera input");
        
        // First ensure we don't have any existing SurfaceTexture
        if (cameraSurfaceTexture != null) {
            try {
                Log.d(TAG, "Releasing existing SurfaceTexture before creating a new one");
                cameraSurfaceTexture.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing existing SurfaceTexture", e);
            } finally {
                cameraSurfaceTexture = null;
            }
        }
        
        // Also release any existing Surface to avoid leaks
        if (cameraInputSurface != null) {
            try {
                Log.d(TAG, "Releasing existing camera input Surface before creating a new one");
                cameraInputSurface.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing existing camera input Surface", e);
            } finally {
                cameraInputSurface = null;
            }
        }
        
        try {
            // Create new SurfaceTexture with our OES texture
            cameraSurfaceTexture = new SurfaceTexture(oesTextureId);
            
            // Set default buffer size to match our recording dimensions
            cameraSurfaceTexture.setDefaultBufferSize(videoWidth, videoHeight);
            
            // Create Surface from the SurfaceTexture
            cameraInputSurface = new Surface(cameraSurfaceTexture);
            
            // Set up frame available listener
            cameraSurfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                @Override
                public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                    synchronized (frameSyncObject) {
                        frameAvailable = true;
                        frameSyncObject.notifyAll();
                    }
                    
                    if (externalFrameListener != null) {
                        externalFrameListener.onFrameAvailable();
                    }
                }
            });
            
            Log.d(TAG, "Successfully created SurfaceTexture and Surface for camera input");
        } catch (Exception e) {
            Log.e(TAG, "Error creating SurfaceTexture", e);
            if (cameraSurfaceTexture != null) {
                try {
                    cameraSurfaceTexture.release();
                } catch (Exception ex) {
                    // Ignore
                }
                cameraSurfaceTexture = null;
            }
            if (cameraInputSurface != null) {
                cameraInputSurface.release();
                cameraInputSurface = null;
            }
            throw new RuntimeException("Failed to create camera SurfaceTexture", e);
        }
    }

    /**
     * Verifies that the camera buffer size is set correctly.
     * This is a best-effort check as not all devices provide this information.
     */
    private void verifyBufferSize() {
        // This is mostly for logging purposes as we can't directly query the buffer size
        Log.d(TAG, "Verifying camera buffer size: requested " + videoWidth + "x" + videoHeight);
        
        // Force the buffer size again to be sure
        if (cameraSurfaceTexture != null) {
            cameraSurfaceTexture.setDefaultBufferSize(videoWidth, videoHeight);
        }
    }

    /**
     * Set or update the preview surface. Handles EGL context/surface lifecycle.
     */
    public void setPreviewSurface(Surface previewSurface) {
        // Exit early if trying to set the same surface
        if (currentPreviewSurface == previewSurface) return;
        
        synchronized (previewRenderLock) {
            // Wait for any rendering to complete before changing surface
            int waitAttempts = 0;
            while (previewRenderActive && waitAttempts < 5) {
                try {
                    Log.d(TAG, "Waiting for render to complete before changing surface");
                    previewRenderLock.wait(10);
                    waitAttempts++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            
            // First, release any existing EGL resources
            releasePreviewEGL();
            
            // Then assign the new surface
            currentPreviewSurface = previewSurface;
            
            if (previewSurface == null) {
                // No new surface, just clean up
                return;
            }
            
            if (!previewSurface.isValid()) {
                Log.e(TAG, "Invalid preview surface provided");
                currentPreviewSurface = null;
                return;
            }
            
            // Make sure camera buffer size matches our fixed dimensions regardless of surface change
            updateCameraBufferSize();
            
            try {
                // Create EGL context/surface for preview
                previewEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
                if (previewEglDisplay == EGL14.EGL_NO_DISPLAY) {
                    Log.e(TAG, "Unable to get EGL14 display for preview");
                    return;
                }
                
                int[] version = new int[2];
                if (!EGL14.eglInitialize(previewEglDisplay, version, 0, version, 1)) {
                    Log.e(TAG, "Unable to initialize EGL14 for preview");
                    return;
                }
                
                int[] attribList = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    // Add proper buffer configuration for preview
                    EGL14.EGL_BUFFER_SIZE, 32,
                    EGL14.EGL_DEPTH_SIZE, 16,
                    EGL14.EGL_STENCIL_SIZE, 0,
                    EGL14.EGL_NONE
                };
                
                EGLConfig[] configs = new EGLConfig[1];
                int[] numConfigs = new int[1];
                if (!EGL14.eglChooseConfig(previewEglDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0)) {
                    Log.e(TAG, "Unable to choose EGL config for preview");
                    return;
                }
                
                previewEglConfig = configs[0];
                int[] contextAttribs = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
                };
                
                // IMPORTANT CHANGE: Use shared context from main renderer
                if (eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT && useSharedEglContext) {
                    previewEglContext = EGL14.eglCreateContext(previewEglDisplay, previewEglConfig, eglContext, contextAttribs, 0);
                } else {
                    previewEglContext = EGL14.eglCreateContext(previewEglDisplay, previewEglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
                }
                
                if (previewEglContext == null || previewEglContext == EGL14.EGL_NO_CONTEXT) {
                    Log.e(TAG, "Unable to create EGL context for preview");
                    return;
                }
                
                int[] surfaceAttribs = { 
                    EGL14.EGL_NONE 
                };
                
                previewEglSurface = EGL14.eglCreateWindowSurface(previewEglDisplay, previewEglConfig, 
                        previewSurface, surfaceAttribs, 0);
                
                if (previewEglSurface == null || previewEglSurface == EGL14.EGL_NO_SURFACE) {
                    Log.e(TAG, "Unable to create EGL window surface for preview: " + EGL14.eglGetError());
                    return;
                }
                
                // Set up preview context 
                boolean result = EGL14.eglMakeCurrent(previewEglDisplay, previewEglSurface, 
                        previewEglSurface, previewEglContext);
                
                if (!result) {
                    Log.e(TAG, "eglMakeCurrent (preview setup) failed: " + EGL14.eglGetError());
                    return;
                }
                
                // If using shared context, we don't need to recreate shaders
                // but if not shared, initialize shaders for this context
                if (!useSharedEglContext) {
                    setupOESShader();
                    setupSimpleWatermarkShader();
                }
                
                // Clear to black to initialize surface
                GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                
                // Apply preserved aspect ratio viewport
                int[] viewport = calculatePreservedViewport(mSurfaceWidth, mSurfaceHeight);
                GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
                
                try {
                    if (!EGL14.eglSwapBuffers(previewEglDisplay, previewEglSurface)) {
                        Log.e(TAG, "eglSwapBuffers (preview setup) failed: " + EGL14.eglGetError());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Exception in eglSwapBuffers during setup", e);
                }
                
                // Important: release the current context after setup
                EGL14.eglMakeCurrent(previewEglDisplay, EGL14.EGL_NO_SURFACE, 
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                
            } catch (Exception e) {
                Log.e(TAG, "Error setting up preview surface", e);
                releasePreviewEGL(); // Clean up on error
            }
        }
    }

    private void setupTextureCoordinates() {
        // Use standard normalized texture coordinates that map to the entire texture
        // These coordinates are the foundation for proper rendering regardless of device orientation
        float[] texCoords = {
            0.0f, 0.0f,  // Bottom left 
            1.0f, 0.0f,  // Bottom right
            0.0f, 1.0f,  // Top left
            1.0f, 1.0f   // Top right
        };
        
        // Log the coordinates for debugging
        Log.d(TAG, "Using fixed texture coordinates: [" +
            texCoords[0] + "," + texCoords[1] + "], [" +
            texCoords[2] + "," + texCoords[3] + "], [" +
            texCoords[4] + "," + texCoords[5] + "], [" +
            texCoords[6] + "," + texCoords[7] + "]");
        
        // Create buffer for the texture coordinates
        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        texCoordBuffer.put(texCoords).position(0);
        
        Log.d(TAG, "Fixed normalized texture coordinates established for orientation " + orientation);
    }

    /**
     * Forces a surface dimensions update based on the current video dimensions.
     * Ensures we maintain proper aspect ratio even after orientation changes.
     *
     * NOTE: This is only for preview/letterboxing. Recording output always uses fixed dimensions.
     */
    private void forceFixedDimensions() {
        // Re-impose our fixed dimensions
        if (cameraSurfaceTexture != null) {
            cameraSurfaceTexture.setDefaultBufferSize(videoWidth, videoHeight);
        }
        
        // Recalculate aspect ratio viewport
        int[] viewport = calculatePreservedViewport(mSurfaceWidth, mSurfaceHeight);
        Log.d(TAG, "Forcing fixed dimensions: " + videoWidth + "x" + videoHeight + 
              ", viewport: [" + viewport[0] + "," + viewport[1] + "," + 
              viewport[2] + "," + viewport[3] + "]");
    }

    /**
     * Sets the current device orientation.
     * @param orientation Device orientation (0=portrait, 1=landscape, 2=reverse portrait, 3=reverse landscape)
     */
    public void setDeviceOrientation(int orientation) {
        // ----- Fix Start: Ignore device orientation for recording output -----
        // COMPLETELY IGNORE ORIENTATION CHANGES FOR RECORDING OUTPUT
        // Only the preview may use letterboxing for user experience.
        Log.d(TAG, "Device orientation changed to: " + orientation + " - IGNORED for recording");
        // ----- Fix End: Ignore device orientation for recording output -----
    }

    /**
     * Sets the surface dimensions for calculating letterboxing viewport.
     * This DOES NOT change our fixed recording dimensions.
     * 
     * @param width Width of the target surface
     * @param height Height of the target surface
     */
    public void setSurfaceDimensions(int width, int height) {
        if (width > 0 && height > 0) {
            Log.d(TAG, "Surface dimensions changed from " + mSurfaceWidth + "x" + mSurfaceHeight + 
                  " to " + width + "x" + height);
            
            this.mSurfaceWidth = width;
            this.mSurfaceHeight = height;
            
            // Recalculate the letterboxing viewport
            int[] viewport = calculatePreservedViewport(width, height);
            
            Log.d(TAG, "Recording dimensions remain fixed at: " + videoWidth + "x" + videoHeight + 
                  " (ratio: " + ((float)videoWidth/videoHeight) + 
                  "), will use letterboxing for display");
        }
    }

    /**
     * Calculates a viewport that preserves the desired aspect ratio
     * regardless of the surface dimensions. This applies proper letterboxing
     * to maintain the original aspect ratio.
     *
     * @param surfaceWidth Width of the current surface
     * @param surfaceHeight Height of the current surface
     * @return int[] array with {x, y, width, height} for the viewport
     */
    private int[] calculatePreservedViewport(int surfaceWidth, int surfaceHeight) {
        // Always use our fixed resolution as the target aspect ratio
        // videoWidth/videoHeight is our target regardless of orientation
        float targetAspectRatio = (float)videoWidth / videoHeight;
        
        // Default to full surface
        int x = 0;
        int y = 0;
        int viewportWidth = surfaceWidth;
        int viewportHeight = surfaceHeight;
        
        // Current surface aspect ratio
        float surfaceAspectRatio = (float)surfaceWidth / surfaceHeight;
        
        if (Math.abs(surfaceAspectRatio - targetAspectRatio) > 0.01f) {
            // Aspect ratios don't match - need letterboxing
            if (surfaceAspectRatio > targetAspectRatio) {
                // Surface is wider than target - letterbox on sides
                viewportWidth = (int)(surfaceHeight * targetAspectRatio);
                x = (surfaceWidth - viewportWidth) / 2;
            } else {
                // Surface is taller than target - letterbox top/bottom
                viewportHeight = (int)(surfaceWidth / targetAspectRatio);
                y = (surfaceHeight - viewportHeight) / 2;
            }
        }
        
        Log.d(TAG, "Letterboxed viewport: [" + x + "," + y + "," + 
              viewportWidth + "," + viewportHeight + "] from surface " + 
              surfaceWidth + "x" + surfaceHeight + ", target ratio: " + targetAspectRatio + 
              ", surface ratio: " + surfaceAspectRatio);
              
        return new int[] { x, y, viewportWidth, viewportHeight };
    }

    /**
     * Calculates a combined transform matrix that accounts for both the SurfaceTexture transform
     * and the desired fixed orientation. This is crucial for proper video orientation.
     *
     * @return The combined transform matrix for texture coordinates
     */
    private float[] calculateCombinedTransformMatrix() {
        float[] surfaceTransform = new float[16];
        float[] finalTransform = new float[16];
        
        // Get the actual transform from SurfaceTexture
        if (cameraSurfaceTexture != null) {
            cameraSurfaceTexture.getTransformMatrix(surfaceTransform);
        } else {
            Matrix.setIdentityM(surfaceTransform, 0);
        }
        
        // Create desired rotation matrix
        float[] desiredRotation = new float[16];
        Matrix.setIdentityM(desiredRotation, 0);
        
        // Calculate rotation needed to achieve desired orientation
        int rotationDegrees = calculateRequiredRotation();
        Matrix.rotateM(desiredRotation, 0, rotationDegrees, 0, 0, 1.0f);
        
        // Combine: finalTransform = desiredRotation * surfaceTransform
        Matrix.multiplyMM(finalTransform, 0, desiredRotation, 0, surfaceTransform, 0);
        
        return finalTransform;
    }
    
    /**
     * Calculates the required rotation based on camera sensor orientation and desired output orientation.
     *
     * @return Rotation angle in degrees
     */
    private int calculateRequiredRotation() {
        boolean isFrontCamera = sensorOrientation == 270;
        
        if ("portrait".equalsIgnoreCase(orientation)) {
            if (isFrontCamera) {
                return (sensorOrientation == 270) ? 0 : 180;
            } else {
                return (sensorOrientation == 90) ? 0 : 180;
            }
        } else { // landscape
            if (isFrontCamera) {
                return (sensorOrientation == 270) ? 90 : 270;
            } else {
                return (sensorOrientation == 90) ? 270 : 90;
            }
        }
    }

    /**
     * Minimal rendering for recording: always use camera's output width/height as provided.
     */
    private void renderMinimal() {
        // Use the camera's output width/height as provided
        GLES20.glViewport(0, 0, videoWidth, videoHeight);
        
        // Clear the screen
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        
        // Use the shader program
        GLES20.glUseProgram(oesProgram);
        
        // Standard full screen quad
        float[] vertices = {
            -1.0f, -1.0f,  // bottom left
             1.0f, -1.0f,  // bottom right
            -1.0f,  1.0f,  // top left
             1.0f,  1.0f   // top right
        };
        float[] texCoords = {
            0.0f, 0.0f,  // bottom left
            1.0f, 0.0f,  // bottom right
            0.0f, 1.0f,  // top left
            1.0f, 1.0f   // top right
        };
        
        FloatBuffer vertexBuffer = ByteBuffer.allocateDirect(vertices.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(vertices).position(0);
        FloatBuffer texCoordBuffer = ByteBuffer.allocateDirect(texCoords.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        texCoordBuffer.put(texCoords).position(0);
        
        GLES20.glVertexAttribPointer(oesPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(oesPositionHandle);
        GLES20.glVertexAttribPointer(oesTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);
        GLES20.glEnableVertexAttribArray(oesTexCoordHandle);
        
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES20.glUniform1i(oesTextureHandle, 0);
        
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        
        GLES20.glDisableVertexAttribArray(oesPositionHandle);
        GLES20.glDisableVertexAttribArray(oesTexCoordHandle);
    }
} 