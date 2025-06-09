package com.fadcam.opengl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f
    };
    private static final float[] TEXCOORDS = {
        0.0f, 1.0f,
        1.0f, 1.0f,
        0.0f, 0.0f,
        1.0f, 0.0f
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
        this.outputSurface = outputSurface;
        this.orientation = orientation;
        this.sensorOrientation = sensorOrientation;
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        watermarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        watermarkPaint.setColor(0xFFFFFFFF);
        watermarkPaint.setTextSize(48f);
        watermarkPaint.setShadowLayer(4f, 2f, 2f, 0xFF000000);
        // Do NOT call any GLES20 function here!
        setupOESTexture();
    }

    private void setupEGL() {
        // 1. Get EGL display
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "Unable to get EGL14 display");
            return;
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e(TAG, "Unable to initialize EGL14");
            return;
        }
        // 2. Choose EGL config
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
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0)) {
            Log.e(TAG, "Unable to choose EGL config");
            return;
        }
        EGLConfig eglConfig = configs[0];
        // 3. Create EGL context
        int[] contextAttribs = {
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "Unable to create EGL context");
            return;
        }
        // 4. Create EGL window surface
        int[] surfaceAttribs = {
            EGL14.EGL_NONE
        };
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, outputSurface, surfaceAttribs, 0);
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "Unable to create EGL window surface");
            return;
        }
        // 5. Make context current
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "Unable to make EGL context current");
            return;
        }
        // After eglMakeCurrent, log OpenGL version and setup shaders
        Log.d(TAG, "OpenGL version: " + GLES20.glGetString(GLES20.GL_VERSION));
        setupSimpleWatermarkShader();
        setupOESShader();
    }

    private void setupOESTexture() {
        // Generate OES texture
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        oesTextureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // Create SurfaceTexture and Surface for camera input
        cameraSurfaceTexture = new SurfaceTexture(oesTextureId);
        cameraInputSurface = new Surface(cameraSurfaceTexture);
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
        if (!initialized) {
            setupEGL();
            initialized = true;
        }
        try {
            // Make EGL context current for encoder
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                Log.e(TAG, "eglMakeCurrent failed: " + EGL14.eglGetError());
                return;
            }
            // Wait for a new frame
            synchronized (frameSyncObject) {
                while (!frameAvailable) {
                    try {
                        frameSyncObject.wait(250); // Timeout to avoid deadlock
                    } catch (InterruptedException e) {
                        Log.e(TAG, "Frame wait interrupted", e);
                        return;
                    }
                }
                frameAvailable = false;
            }
            // Update camera frame
            if (cameraSurfaceTexture != null) {
                try {
                    cameraSurfaceTexture.updateTexImage();
                    long ts = cameraSurfaceTexture.getTimestamp();
                    Log.d(TAG, "updateTexImage: timestamp=" + ts);
                } catch (Exception e) {
                    Log.e(TAG, "SurfaceTexture.updateTexImage() failed", e);
                    return;
                }
            }
            GLES20.glClearColor(0, 0, 0, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            // Draw OES camera frame
            drawOESTexture();
            // Draw watermark overlay
            drawWatermarkTexture();
            int error = GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR) {
                Log.e(TAG, "GL error after draw: " + error);
            }
            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                Log.e(TAG, "eglSwapBuffers failed: " + EGL14.eglGetError());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error rendering frame", e);
        }
    }

    /**
     * Set or update the preview surface. Handles EGL context/surface lifecycle.
     */
    public void setPreviewSurface(Surface previewSurface) {
        if (currentPreviewSurface == previewSurface) return;
        releasePreviewEGL();
        currentPreviewSurface = previewSurface;
        if (previewSurface != null) {
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
            previewEglContext = EGL14.eglCreateContext(previewEglDisplay, previewEglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
            if (previewEglContext == null || previewEglContext == EGL14.EGL_NO_CONTEXT) {
                Log.e(TAG, "Unable to create EGL context for preview");
                return;
            }
            int[] surfaceAttribs = { EGL14.EGL_NONE };
            previewEglSurface = EGL14.eglCreateWindowSurface(previewEglDisplay, previewEglConfig, previewSurface, surfaceAttribs, 0);
            if (previewEglSurface == null || previewEglSurface == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "Unable to create EGL window surface for preview");
                return;
            }
        }
    }

    /**
     * Render the current watermarked frame to the preview surface (if valid).
     * Should be called from the render thread.
     */
    public void renderToPreview() {
        if (previewEglDisplay == null || previewEglSurface == null || previewEglContext == null) return;
        try {
            if (!EGL14.eglMakeCurrent(previewEglDisplay, previewEglSurface, previewEglSurface, previewEglContext)) {
                Log.e(TAG, "eglMakeCurrent (preview) failed: " + EGL14.eglGetError());
                return;
            }
            synchronized (frameSyncObject) {
                while (!frameAvailable) {
                    try {
                        frameSyncObject.wait(250);
                    } catch (InterruptedException e) {
                        Log.e(TAG, "Frame wait interrupted (preview)", e);
                        return;
                    }
                }
                frameAvailable = false;
            }
            if (cameraSurfaceTexture != null) {
                try {
                    cameraSurfaceTexture.updateTexImage();
                } catch (Exception e) {
                    Log.e(TAG, "SurfaceTexture.updateTexImage() failed (preview)", e);
                    return;
                }
            }
            GLES20.glClearColor(0, 0, 0, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            drawOESTexture();
            drawWatermarkTexture();
            int error = GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR) {
                Log.e(TAG, "GL error after draw (preview): " + error);
            }
            if (!EGL14.eglSwapBuffers(previewEglDisplay, previewEglSurface)) {
                Log.e(TAG, "eglSwapBuffers (preview) failed: " + EGL14.eglGetError());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error rendering preview frame", e);
        }
    }

    /**
     * Release preview EGL context/surface if they exist.
     */
    private void releasePreviewEGL() {
        if (previewEglDisplay != null) {
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

    /**
     * Releases all OpenGL and EGL resources.
     */
    public void release() {
        releasePreviewEGL();
        if (watermarkBitmap != null) {
            watermarkBitmap.recycle();
            watermarkBitmap = null;
        }
        // Release EGL/GL resources for encoder
        if (eglDisplay != null && eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface);
            eglSurface = null;
        }
        if (eglDisplay != null && eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext);
            eglContext = null;
        }
        if (eglDisplay != null) {
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
                "precision mediump float;\n" +
                "varying vec2 vTexCoord;\n" +
                "uniform samplerExternalOES uTexture;\n" +
                "void main() {\n" +
                "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
                "}";
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
        if (oesPositionHandle == -1 || oesTexCoordHandle == -1 || oesTextureHandle == -1 || oesRotationHandle == -1) {
            Log.e(TAG, "OES shader attribute/uniform location not found");
        }
        vertexBuffer = ByteBuffer.allocateDirect(VERTICES.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(VERTICES).position(0);
        texCoordBuffer = ByteBuffer.allocateDirect(TEXCOORDS.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        texCoordBuffer.put(TEXCOORDS).position(0);
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

    private void drawOESTexture() {
        GLES20.glUseProgram(oesProgram);
        float scaleX = 1.0f, scaleY = 1.0f;
        float[] scaledVertices = {
            -scaleX, -scaleY,
             scaleX, -scaleY,
            -scaleX,  scaleY,
             scaleX,  scaleY
        };
        vertexBuffer.clear();
        vertexBuffer.put(scaledVertices).position(0);
        float[] texCoords;
        if ("portrait".equals(orientation)) {
            texCoords = new float[] {
                1.0f, 1.0f,
                0.0f, 1.0f,
                1.0f, 0.0f,
                0.0f, 0.0f
            };
        } else {
            texCoords = new float[] {
                0.0f, 1.0f,
                1.0f, 1.0f,
                0.0f, 0.0f,
                1.0f, 0.0f
            };
        }
        texCoordBuffer.clear();
        texCoordBuffer.put(texCoords).position(0);
        vertexBuffer.position(0);
        texCoordBuffer.position(0);
        GLES20.glEnableVertexAttribArray(oesPositionHandle);
        GLES20.glVertexAttribPointer(oesPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(oesTexCoordHandle);
        GLES20.glVertexAttribPointer(oesTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES20.glUniform1i(oesTextureHandle, 0);
        float[] rotationMatrix = new float[16];
        Matrix.setIdentityM(rotationMatrix, 0);
        if ("portrait".equals(orientation)) {
            // Rotate 90 degrees counterclockwise for portrait
            Matrix.rotateM(rotationMatrix, 0, 90f, 0f, 0f, 1f);
        }
        GLES20.glUniformMatrix4fv(oesRotationHandle, 1, false, rotationMatrix, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(oesPositionHandle);
        GLES20.glDisableVertexAttribArray(oesTexCoordHandle);
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
} 