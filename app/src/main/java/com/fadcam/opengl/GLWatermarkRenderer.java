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
    private static final int WATERMARK_BITMAP_WIDTH = 1024;
    private static final int WATERMARK_BITMAP_HEIGHT = 128;

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

    // Preview EGL context/surface management
    private EGLDisplay previewEglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext previewEglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface previewEglSurface = EGL14.EGL_NO_SURFACE;
    private Surface currentPreviewSurface = null;
    private EGLConfig previewEglConfig = null;

    // OES shader and draw logic
    private int oesProgram;
    private int oesPositionHandle;
    private int oesTexCoordHandle;
    private int oesTextureHandle;
    private int oesMvpMatrixHandle;
    private int oesTexMatrixHandle;

    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;

    private static final float[] VERTICES = { -1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f };
    private static final float[] TEXCOORDS = { 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f };

    private volatile boolean frameAvailable = false;
    private final Object frameSyncObject = new Object();

    // Watermark rendering (simplified)
    private int simpleWatermarkProgram;
    private int simpleWatermarkPositionHandle;
    private FloatBuffer watermarkRectBuffer;
    private static final float[] WATERMARK_RECT_VERTICES = { 0.7f, -0.8f, 1.0f, -0.8f, 0.7f, -1.0f, 1.0f, -1.0f };


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

    public interface OnFrameAvailableListener {
        void onFrameAvailable();
    }
    private OnFrameAvailableListener externalFrameListener;

    // Add this field to track the FullFrameRect instance
    private com.fadcam.opengl.grafika.FullFrameRect mFullFrameBlit = null;

    public GLWatermarkRenderer(Context context, Surface outputSurface, String orientation, int sensorOrientation, int videoWidth, int videoHeight) {
        this.context = context;
        this.outputSurface = outputSurface;
        this.sensorOrientation = sensorOrientation;
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;

        watermarkPaint = new Paint();
        watermarkPaint.setTextSize(48);
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
        synchronized (renderLock) {
            if (!initialized || eglDisplay == EGL14.EGL_NO_DISPLAY) return;
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                Log.e(TAG, "eglMakeCurrent failed for encoding surface");
                return;
            }
            
            synchronized (frameSyncObject) {
                while (!frameAvailable) {
                    try {
                        frameSyncObject.wait(100);
                        if (!frameAvailable) {
                             Log.w(TAG, "renderFrame: frame wait timed out");
                            return;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                frameAvailable = false;
            }
            
            cameraSurfaceTexture.updateTexImage();
            cameraSurfaceTexture.getTransformMatrix(texMatrix);
            
            updateMatrices(); // Recalculate matrices on every frame
            
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, cameraSurfaceTexture.getTimestamp());
            
            // --- Render to Recording Surface ---
            drawOESTexture(recordingMvpMatrix, texMatrix);
            drawWatermark();
            EGL14.eglSwapBuffers(eglDisplay, eglSurface);

            // --- Render to Preview Surface ---
            renderToPreview();
        }
    }

    public void renderToPreview() {
        synchronized (previewRenderLock) {
            if (!initialized || currentPreviewSurface == null || !currentPreviewSurface.isValid() || previewEglDisplay == EGL14.EGL_NO_DISPLAY) return;
            if (!EGL14.eglMakeCurrent(previewEglDisplay, previewEglSurface, previewEglSurface, previewEglContext)) {
                Log.w(TAG, "renderToPreview: eglMakeCurrent failed");
                return;
            }
            
            // Texture is already updated from renderFrame(), just draw with the preview matrix.
            drawOESTexture(previewMvpMatrix, texMatrix);
            
            EGL14.eglSwapBuffers(previewEglDisplay, previewEglSurface);
        }
    }

    private void setupEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("eglGetDisplay failed");
        
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) throw new RuntimeException("eglInitialize failed");

        int[] attribList = { EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0);
        EGLConfig eglConfig = configs[0];
        previewEglConfig = eglConfig;

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

    private void updateWatermarkTexture() {
        if (watermarkBitmap == null) return;
        Canvas canvas = new Canvas(watermarkBitmap);
        canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
        canvas.drawText(watermarkText, 32, WATERMARK_BITMAP_HEIGHT / 2f + 20, watermarkPaint);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, watermarkTextureId);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, watermarkBitmap, 0);
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
            "void main() {\n" +
            "    gl_FragColor = texture2D(uTexture, vTextureCoord);\n" +
            "}\n";
            
        oesProgram = createProgram(vertexShaderCode, fragmentShaderCode);
        oesPositionHandle = GLES20.glGetAttribLocation(oesProgram, "aPosition");
        oesTexCoordHandle = GLES20.glGetAttribLocation(oesProgram, "aTextureCoord");
        oesTextureHandle = GLES20.glGetUniformLocation(oesProgram, "uTexture");
        oesMvpMatrixHandle = GLES20.glGetUniformLocation(oesProgram, "uMVPMatrix");
        oesTexMatrixHandle = GLES20.glGetUniformLocation(oesProgram, "uTexMatrix");
    }

    private void drawOESTexture(float[] mvpMatrix, float[] texMatrix) {
        if (mFullFrameBlit != null) {
            // Use Google's Grafika rendering mechanism for better texture handling
            // Apply the MVP matrix first - need to modify the program's matrix
            com.fadcam.opengl.grafika.Texture2dProgram program = mFullFrameBlit.getProgram();
            if (program != null) {
                // Save the current program
                int[] currentProgramArray = new int[1];
                GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, currentProgramArray, 0);
                int currentProgram = currentProgramArray[0];
                
                // Use the program and set the MVP matrix
                int programHandle = program.getProgramHandle();
                GLES20.glUseProgram(programHandle);
                int mvpLoc = GLES20.glGetUniformLocation(programHandle, "uMVPMatrix");
                if (mvpLoc >= 0) {
                    GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvpMatrix, 0);
                }
                
                // Restore previous program
                GLES20.glUseProgram(currentProgram);
            }
            
            // Now draw the frame with the texture matrix
            mFullFrameBlit.drawFrame(oesTextureId, texMatrix);
        } else {
            // Fallback to original method
            GLES20.glUseProgram(oesProgram);

            GLES20.glUniformMatrix4fv(oesMvpMatrixHandle, 1, false, mvpMatrix, 0);
            GLES20.glUniformMatrix4fv(oesTexMatrixHandle, 1, false, texMatrix, 0);

            GLES20.glEnableVertexAttribArray(oesPositionHandle);
            GLES20.glVertexAttribPointer(oesPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
            GLES20.glEnableVertexAttribArray(oesTexCoordHandle);
            GLES20.glVertexAttribPointer(oesTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);
            
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
            GLES20.glUniform1i(oesTextureHandle, 0);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glDisableVertexAttribArray(oesPositionHandle);
            GLES20.glDisableVertexAttribArray(oesTexCoordHandle);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
            GLES20.glUseProgram(0);
        }
    }

    private void setupSimpleWatermarkShader() {
        String vertexShaderCode = "attribute vec4 aPosition;\nvoid main() { gl_Position = aPosition; }";
        String fragmentShaderCode = "precision mediump float; \nvoid main() { gl_FragColor = vec4(1.0, 1.0, 1.0, 0.7); }";
        simpleWatermarkProgram = createProgram(vertexShaderCode, fragmentShaderCode);
        simpleWatermarkPositionHandle = GLES20.glGetAttribLocation(simpleWatermarkProgram, "aPosition");
    }

    private void drawWatermark() {
        GLES20.glUseProgram(simpleWatermarkProgram);
        GLES20.glEnableVertexAttribArray(simpleWatermarkPositionHandle);
        GLES20.glVertexAttribPointer(simpleWatermarkPositionHandle, 2, GLES20.GL_FLOAT, false, 0, watermarkRectBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(simpleWatermarkPositionHandle);
        GLES20.glUseProgram(0);
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
            
            releasePreviewEGL();
            currentPreviewSurface = previewSurface;

            if (previewSurface != null) {
                try {
                    previewEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
                    int[] version = new int[2];
                    EGL14.eglInitialize(previewEglDisplay, version, 0, version, 1);

                    int[] contextAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
                    previewEglContext = EGL14.eglCreateContext(previewEglDisplay, previewEglConfig, eglContext, contextAttribs, 0);
                    
                    int[] surfaceAttribs = { EGL14.EGL_NONE };
                    previewEglSurface = EGL14.eglCreateWindowSurface(previewEglDisplay, previewEglConfig, previewSurface, surfaceAttribs, 0);

                } catch(Exception e) {
                    Log.e(TAG, "Error creating preview EGL", e);
                    releasePreviewEGL();
                }
            }
        }
    }

    private void releasePreviewEGL() {
        if (previewEglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(previewEglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (previewEglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(previewEglDisplay, previewEglSurface);
            if (previewEglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(previewEglDisplay, previewEglContext);
            EGL14.eglTerminate(previewEglDisplay);
        }
        previewEglDisplay = EGL14.EGL_NO_DISPLAY;
        previewEglContext = EGL14.EGL_NO_CONTEXT;
        previewEglSurface = EGL14.EGL_NO_SURFACE;
        currentPreviewSurface = null;
    }
    
    public void release() {
        synchronized (renderLock) {
            synchronized (previewRenderLock) {
                releasePreviewEGL();
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    EGL14.eglDestroySurface(eglDisplay, eglSurface);
                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                    EGL14.eglTerminate(eglDisplay);
                }
                eglDisplay = EGL14.EGL_NO_DISPLAY;
                eglContext = EGL14.EGL_NO_CONTEXT;
                eglSurface = EGL14.EGL_NO_SURFACE;
                
                if (mFullFrameBlit != null) {
                    mFullFrameBlit.release();
                    mFullFrameBlit = null;
                }
                
                if (cameraInputSurface != null) cameraInputSurface.release();
                if (cameraSurfaceTexture != null) cameraSurfaceTexture.release();
                initialized = false;
            }
        }
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

    private void updateMatrices() {
        // Calculate correct rotation based on sensor orientation and device orientation
        int rotationDegrees = getRequiredRotation();
        Log.d(TAG, "updateMatrices: rotationDegrees=" + rotationDegrees + 
              ", deviceOrientation=" + deviceOrientation +
              ", sensorOrientation=" + sensorOrientation);

        // Recording matrix: only applies rotation to make the video upright
        Matrix.setIdentityM(recordingMvpMatrix, 0);
        Matrix.rotateM(recordingMvpMatrix, 0, rotationDegrees, 0, 0, 1);

        // Preview matrix: handles rotation, mirroring, and aspect ratio
        Matrix.setIdentityM(previewMvpMatrix, 0);
        Matrix.rotateM(previewMvpMatrix, 0, rotationDegrees, 0, 0, 1);
        
        // Mirror front camera preview properly (horizontally)
        if (isFrontCamera()) {
            Matrix.scaleM(previewMvpMatrix, 0, -1, 1, 1);
        }

        // Properly handle aspect ratio to avoid stretching/squeezing
        if (mSurfaceWidth <= 0 || mSurfaceHeight <= 0) {
            // Can't calculate aspect ratio yet, return early
            return;
        }

        // CRITICAL: For Grafika-style correct preview rendering, we need to:
        // 1. Determine the orientation-aware dimensions
        // 2. Calculate the correct aspect ratio
        // 3. Use letterboxing/pillarboxing to maintain the aspect ratio
        
        boolean isPortrait = (rotationDegrees % 180 != 0);
        int orientedVideoWidth, orientedVideoHeight;
        
        if (isPortrait) {
            // In portrait mode, swap the video dimensions
            orientedVideoWidth = videoHeight;
            orientedVideoHeight = videoWidth;
            Log.d(TAG, "Portrait orientation - swapping dimensions for calculation");
        } else {
            orientedVideoWidth = videoWidth;
            orientedVideoHeight = videoHeight;
            Log.d(TAG, "Landscape orientation - using original dimensions");
        }
        
        // Calculate correct aspect ratios
        float videoAspectRatio = (float) orientedVideoWidth / orientedVideoHeight;
        float previewAspectRatio = (float) mSurfaceWidth / mSurfaceHeight;
        
        Log.d(TAG, "Aspect ratios - video: " + videoAspectRatio + 
              ", preview: " + previewAspectRatio +
              ", orientedVideoWidth: " + orientedVideoWidth +
              ", orientedVideoHeight: " + orientedVideoHeight +
              ", surfaceWidth: " + mSurfaceWidth +
              ", surfaceHeight: " + mSurfaceHeight +
              ", isPortrait: " + isPortrait);

        // Apply Grafika-style letterbox/pillarbox to maintain aspect ratio
        float scaleX = 1.0f;
        float scaleY = 1.0f;
        
        if (Math.abs(previewAspectRatio - videoAspectRatio) > 0.01f) {
            if (previewAspectRatio > videoAspectRatio) {
                // Preview is wider than video: pillarbox (scale X down)
                scaleX = videoAspectRatio / previewAspectRatio;
                Log.d(TAG, "Applying pillarbox with scaleX = " + scaleX);
            } else {
                // Preview is taller than video: letterbox (scale Y down)
                scaleY = previewAspectRatio / videoAspectRatio;
                Log.d(TAG, "Applying letterbox with scaleY = " + scaleY);
            }
        }
        
        // Apply the scaling to maintain aspect ratio (this is the Grafika approach)
        Matrix.scaleM(previewMvpMatrix, 0, scaleX, scaleY, 1.0f);
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
        // Calculate rotation correctly based on sensor orientation and device orientation
        int displayRotation = getDisplayRotation();
        
        if (isFrontCamera()) {
            int rotation = (sensorOrientation + displayRotation) % 360;
            return (360 - rotation) % 360;  // Compensate for front camera mirroring
        } else {
            return (sensorOrientation - displayRotation + 360) % 360;
        }
    }

    private boolean isFrontCamera() {
        // Use a more reliable way to identify front camera if possible
        // For now, we'll go with the common case of front camera having 
        // a sensor orientation of 270 degrees
        return sensorOrientation == 270;
    }
}


