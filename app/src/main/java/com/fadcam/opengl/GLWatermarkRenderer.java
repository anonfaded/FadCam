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
            float[] texMatrix = new float[16];
            cameraSurfaceTexture.updateTexImage();
            cameraSurfaceTexture.getTransformMatrix(texMatrix);
            updateMatrices(); // Recalculate matrices on every frame
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, cameraSurfaceTexture.getTimestamp());
            boolean isLandscape = false;
            // Determine if current orientation is landscape
            // Prefer userOrientationSetting if available, else use deviceOrientation
            if (userOrientationSetting != null) {
                isLandscape = "landscape".equalsIgnoreCase(userOrientationSetting);
            } else {
                // Fallback: deviceOrientation 90 or 270 is landscape
                isLandscape = (deviceOrientation == 1 || deviceOrientation == 3); // Surface.ROTATION_90 or ROTATION_270
            }
            float[] encoderTexMatrix;
            if (isLandscape) {
                // ----- Apply vertical flip for landscape only -----
                float[] fixedTexMatrix = new float[16];
                Matrix.setIdentityM(fixedTexMatrix, 0);
                Matrix.scaleM(fixedTexMatrix, 0, 1f, -1f, 1f);
                Matrix.translateM(fixedTexMatrix, 0, 0f, -1f, 0f);
                Log.d("FAD-FINAL", "Landscape: Applying vertical flip to correct orientation");
                encoderTexMatrix = fixedTexMatrix;
            } else {
                // ----- Portrait: use original texMatrix -----
                Log.d("FAD-FINAL", "Portrait: Using original texMatrix (no flip)");
                encoderTexMatrix = texMatrix;
            }
            drawOESTexture(recordingMvpMatrix, encoderTexMatrix);
            drawWatermark();
            EGL14.eglSwapBuffers(eglDisplay, eglSurface);
            // Preview draw
            renderToPreviewWithMatrix(texMatrix);
        }
    }

    // Add a helper to draw preview with a given texMatrix
    private void renderToPreviewWithMatrix(float[] texMatrix) {
        synchronized (previewRenderLock) {
            if (!initialized || currentPreviewSurface == null || !currentPreviewSurface.isValid() || previewEglDisplay == EGL14.EGL_NO_DISPLAY) return;
            if (!EGL14.eglMakeCurrent(previewEglDisplay, previewEglSurface, previewEglSurface, previewEglContext)) {
                Log.w(TAG, "renderToPreview: eglMakeCurrent failed");
                return;
            }
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

    // Add a setter for encoder dimensions
    public void setEncoderDimensions(int width, int height) {
        this.encoderWidth = width;
        this.encoderHeight = height;
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
        Log.d(TAG, "updateMatrices: rotationDegrees=" + rotationDegrees + 
              ", deviceOrientation=" + deviceOrientation +
              ", sensorOrientation=" + sensorOrientation);
        Log.d("FAD-MATRIX", "Applying rotation: " + rotationDegrees);

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

        Log.d(TAG, "Aspect ratios - video: " + videoAspectRatio + 
              ", preview: " + previewAspectRatio +
              ", orientedVideoWidth: " + orientedVideoWidth +
              ", orientedVideoHeight: " + orientedVideoHeight +
              ", surfaceWidth: " + mSurfaceWidth +
              ", surfaceHeight: " + mSurfaceHeight);
        Log.d("FAD-MATRIX", "Surface: " + mSurfaceWidth + "x" + mSurfaceHeight);
        Log.d("FAD-MATRIX", "Encoder: " + orientedVideoWidth + "x" + orientedVideoHeight);

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
        Log.d("FAD-MATRIX", String.format("ScaleX: %.2f  ScaleY: %.2f", scaleX, scaleY));
        Matrix.scaleM(previewMvpMatrix, 0, scaleX, scaleY, 1.0f);
        Log.d("FAD-MATRIX", "recordingMvpMatrix: " + java.util.Arrays.toString(recordingMvpMatrix));
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
        Log.d("FAD-ROT", "Device: " + deviceOrientation + " Sensor: " + sensorOrientation + " ➜ Rotation = " + rotation);
        return rotation;
        // ----- Fix End: Standard camera app rotation logic -----
    }

    private boolean isFrontCamera() {
        // Use a more reliable way to identify front camera if possible
        // For now, we'll go with the common case of front camera having 
        // a sensor orientation of 270 degrees
        return sensorOrientation == 270;
    }
}


