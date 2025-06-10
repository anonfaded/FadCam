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
 * GLWatermarkRenderer handles OpenGL rendering of camera frames with a dynamic watermark overlay.
 * It is designed for off-screen video recording with real-time watermarking.
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
    private static final float[] VERTICES = {
        -1.0f, -1.0f,  // bottom left
         1.0f, -1.0f,  // bottom right
        -1.0f,  1.0f,  // top left
         1.0f,  1.0f   // top right
    };
    private static final float[] TEXCOORDS = {
        0.0f, 1.0f,  // bottom left
        1.0f, 1.0f,  // bottom right
        0.0f, 0.0f,  // top left
        1.0f, 0.0f   // top right
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

    // Surface dimensions for aspect ratio calculations
    private int mSurfaceWidth = 0;
    private int mSurfaceHeight = 0;

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
        
        // Initialize deviceOrientation based on requested orientation
        // Default to portrait (0) if "portrait", landscape (1) otherwise
        this.deviceOrientation = "portrait".equalsIgnoreCase(orientation) ? 0 : 1;
        
        // Always store the width and height in the intended final output orientation
        // This ensures consistent dimensions regardless of phone rotation
        if ("portrait".equalsIgnoreCase(orientation) && videoWidth > videoHeight) {
            // Swap dimensions for portrait mode if width is larger than height
            this.videoWidth = videoHeight;
            this.videoHeight = videoWidth;
        } else {
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        }
        
        // Calculate target aspect ratio based on the final output dimensions
        this.targetAspectRatio = determineTargetAspectRatio(this.videoWidth, this.videoHeight);
        
        // Initialize default watermark paint
        watermarkPaint = new Paint();
        watermarkPaint.setTextSize(48);
        watermarkPaint.setAntiAlias(true);
        watermarkPaint.setARGB(255, 255, 255, 255);
        watermarkPaint.setShadowLayer(1f, 0f, 1f, Color.BLACK);
        
        // Initialize vertex and texture coordinate buffers with default values
        // These will be updated later in setupTextureCoordinates
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
                    GLES20.glGetError();
                try {
                    cameraSurfaceTexture.updateTexImage();
                } catch (Exception e) {
                        Log.e(TAG, "Error updating texture image", e);
                    return;
                    }
                    int error = GLES20.glGetError();
                    if (error != GLES20.GL_NO_ERROR) {
                        Log.e(TAG, "GL error after updateTexImage: 0x" + Integer.toHexString(error));
                    }
                }
                GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                GLES20.glViewport(0, 0, videoWidth, videoHeight);
                float[] rotationMatrix = new float[16];
                Matrix.setIdentityM(rotationMatrix, 0); // No rotation, no orientation logic
                drawOESTexture(rotationMatrix);
                if (watermarkEnabled) {
            drawWatermarkTexture();
                }
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
     * Should be called from the render thread.
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
                GLES20.glViewport(0, 0, videoWidth, videoHeight);
                float[] rotationMatrix = new float[16];
                Matrix.setIdentityM(rotationMatrix, 0); // No rotation, no orientation logic
                drawOESTexture(rotationMatrix);
                if (watermarkEnabled) {
                    drawWatermarkTexture();
                }
                GLES20.glFinish();
                try {
                    if (!EGL14.eglSwapBuffers(previewEglDisplay, previewEglSurface)) {
                        Log.e(TAG, "eglSwapBuffers (preview) failed: " + EGL14.EGL_NO_DISPLAY);
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
        releasePreviewEGL();
                
        if (watermarkBitmap != null) {
            watermarkBitmap.recycle();
            watermarkBitmap = null;
        }
                
        // Release EGL/GL resources for encoder
                if (eglDisplay != null) {
                    // Make sure we're not using the context
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, 
                            EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                            
                    if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface);
            eglSurface = null;
        }
                    if (eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext);
            eglContext = null;
        }
            EGL14.eglTerminate(eglDisplay);
            eglDisplay = null;
        }
                
        if (cameraSurfaceTexture != null) {
            cameraSurfaceTexture.release();
            cameraSurfaceTexture = null;
        }
                
        if (cameraInputSurface != null) {
            cameraInputSurface.release();
            cameraInputSurface = null;
                }
                
                Log.d(TAG, "GLWatermarkRenderer resources fully released");
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
        String vertexShaderCode =
                "attribute vec4 aPosition;\n" +
                "attribute vec2 aTexCoord;\n" +
                "uniform mat4 uRotation;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "    gl_Position = uRotation * aPosition;\n" +
                "    vTexCoord = aTexCoord;\n" +
                "}";

        String fragmentShaderCode =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision highp float;\n" +
                "varying vec2 vTexCoord;\n" +
                "uniform samplerExternalOES uTexture;\n" +
                "void main() {\n" +
                "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
                "}\n";

        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode);
        oesProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(oesProgram, vertexShader);
        GLES20.glAttachShader(oesProgram, fragmentShader);
        GLES20.glLinkProgram(oesProgram);
        oesPositionHandle = GLES20.glGetAttribLocation(oesProgram, "aPosition");
        oesTexCoordHandle = GLES20.glGetAttribLocation(oesProgram, "aTexCoord");
        oesTextureHandle = GLES20.glGetUniformLocation(oesProgram, "uTexture");
        oesRotationHandle = GLES20.glGetUniformLocation(oesProgram, "uRotation");
        vertexBuffer = ByteBuffer.allocateDirect(VERTICES.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(VERTICES).position(0);
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

        // Apply rotation matrix for orientation
        float[] finalMatrix = new float[16];
        System.arraycopy(rotationMatrix, 0, finalMatrix, 0, 16);

        // Use the default vertex positions - a full screen quad
        vertexBuffer.position(0);
        GLES20.glVertexAttribPointer(oesPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(oesPositionHandle);

        // Make sure we've updated the texture coordinates for the current camera
        // and orientation before rendering
        setupTextureCoordinates();
        
        // Apply texture coordinates
        texCoordBuffer.position(0);
        GLES20.glVertexAttribPointer(oesTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);
        GLES20.glEnableVertexAttribArray(oesTexCoordHandle);

        // Apply rotation matrix for orientation
        GLES20.glUniformMatrix4fv(oesRotationHandle, 1, false, finalMatrix, 0);

        // Bind and draw texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES20.glUniform1i(oesTextureHandle, 0);

        // Draw the texture to the screen
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        
        // Check for errors after drawing
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "GL error after drawing: 0x" + Integer.toHexString(error));
        }
        
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
            } else if (sensorOrientation == 270) { // Front camera
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
        if (!initialized) {
            // Step 1: Set up EGL first
            setupEGL();
            
            // Step 2: Set up shaders after EGL is initialized
            setupOESShader();
            setupSimpleWatermarkShader();
            
            // Step 3: Set up texture coordinates
            setupTextureCoordinates();
            
            // Step 4: Create and set up textures
            setupOESTexture();
            
            // Step 5: Now create camera surface texture using the OES texture
            createCameraSurfaceTexture();
            
            // Step 6: Set up watermark textures after camera is initialized
            setupWatermarkTexture();
            setWatermarkText(watermarkText);
            
            // Mark renderer as initialized
            initialized = true;
            
            // Set filtering parameters for smoother preview
            if (oesTextureId != 0) {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
                // Use NEAREST filter instead of LINEAR for better performance
                GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            }
        }
    }

    /**
     * Create the camera surface texture from the OES texture ID
     */
    private void createCameraSurfaceTexture() {
        try {
            if (oesTextureId == 0) {
                Log.e(TAG, "Cannot create SurfaceTexture, oesTextureId is 0");
                return;
            }

            // Make sure EGL context is current
            if (eglDisplay != null && eglContext != null && eglSurface != null) {
                if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    Log.e(TAG, "Failed to make EGL context current for SurfaceTexture creation: " + 
                        EGL14.eglGetError());
                    return;
                }
            } else {
                Log.e(TAG, "Cannot create SurfaceTexture - EGL not initialized");
                return;
            }

            // Create the SurfaceTexture and initialize it
            cameraSurfaceTexture = new SurfaceTexture(oesTextureId);
            
            // Always use the selected resolution for buffer size, lock orientation and aspect ratio
            cameraSurfaceTexture.setDefaultBufferSize(videoWidth, videoHeight);
            
            // Set up texture coordinates based on orientation
            setupTextureCoordinates();
            
            // Create the input surface for the camera
            cameraInputSurface = new Surface(cameraSurfaceTexture);
            
            // Set the frame listener to notify when new frames are available
            cameraSurfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                @Override
                public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                    synchronized (frameSyncObject) {
                        if (!frameAvailable) {
                            frameAvailable = true;
                            frameSyncObject.notifyAll();
                        }
                    }
                    
                    // Also notify any external listener
                    if (externalFrameListener != null) {
                        externalFrameListener.onFrameAvailable();
                    }
                }
            });
            
            Log.d(TAG, "SurfaceTexture created successfully with buffer size: " + videoWidth + "x" + videoHeight);
        } catch (Exception e) {
            Log.e(TAG, "Error creating SurfaceTexture", e);
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
        // Always map the camera buffer directly, no orientation logic
        float[] adjustedTexCoords = new float[] {
            0.0f, 1.0f,  // Bottom left
            1.0f, 1.0f,  // Bottom right
            0.0f, 0.0f,  // Top left
            1.0f, 0.0f   // Top right
        };
        texCoordBuffer = ByteBuffer.allocateDirect(adjustedTexCoords.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        texCoordBuffer.put(adjustedTexCoords).position(0);
        Log.d(TAG, "Updated texture coordinates: direct mapping, no orientation logic");
    }

    /**
     * Sets the current device orientation
     * @param orientation 0=portrait, 1=landscape, 2=reverse portrait, 3=reverse landscape
     */
    public void setDeviceOrientation(int orientation) {
        if (orientation >= 0 && orientation <= 3) {
            this.deviceOrientation = orientation;
            Log.d(TAG, "Device orientation set to: " + orientation);
        }
    }

    /**
     * Sets the surface dimensions for aspect ratio calculations
     * @param width Width of the target surface
     * @param height Height of the target surface
     */
    public void setSurfaceDimensions(int width, int height) {
        if (width > 0 && height > 0) {
            this.mSurfaceWidth = width;
            this.mSurfaceHeight = height;
            Log.d(TAG, "Surface dimensions set to: " + width + "x" + height);
        }
    }
} 