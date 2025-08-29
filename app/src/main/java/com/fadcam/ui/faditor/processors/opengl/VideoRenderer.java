package com.fadcam.ui.faditor.processors.opengl;

import android.content.Context;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.util.Log;
import android.view.Surface;

import com.fadcam.opengl.grafika.GlUtil;
import com.fadcam.ui.faditor.utils.PerformanceMonitor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * OpenGL ES video frame renderer for hardware-accelerated video processing.
 * Handles EGL context management and frame rendering operations.
 */
public class VideoRenderer {
    
    private static final String TAG = "VideoRenderer";
    
    // Vertex shader for video frame rendering
    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "    gl_Position = uMVPMatrix * aPosition;\n" +
            "    vTextureCoord = (uTexMatrix * aTextureCoord).xy;\n" +
            "}\n";
    
    // Fragment shader for video frame rendering
    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n";
    
    private final Context context;
    private Surface outputSurface;
    
    // EGL components
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private EGLConfig eglConfig;
    
    // OpenGL program and attributes
    private int shaderProgram;
    private int aPositionHandle;
    private int aTextureCoordHandle;
    private int uMVPMatrixHandle;
    private int uTexMatrixHandle;
    private int uTextureHandle;
    
    // Vertex data
    private FloatBuffer vertexBuffer;
    private FloatBuffer textureCoordBuffer;
    
    // Transformation matrices
    private final float[] mvpMatrix = new float[16];
    private final float[] texMatrix = new float[16];
    
    // Vertex coordinates for a full-screen quad
    private static final float[] VERTICES = {
        -1.0f, -1.0f,  // Bottom left
         1.0f, -1.0f,  // Bottom right
        -1.0f,  1.0f,  // Top left
         1.0f,  1.0f   // Top right
    };
    
    // Texture coordinates
    private static final float[] TEXTURE_COORDS = {
        0.0f, 0.0f,  // Bottom left
        1.0f, 0.0f,  // Bottom right
        0.0f, 1.0f,  // Top left
        1.0f, 1.0f   // Top right
    };
    
    private boolean initialized = false;
    
    // Performance monitoring
    private final PerformanceMonitor performanceMonitor;
    
    public VideoRenderer(Context context) {
        this.context = context;
        this.performanceMonitor = PerformanceMonitor.getInstance();
        initializeBuffers();
    }
    
    private void initializeBuffers() {
        // Initialize vertex buffer
        ByteBuffer bb = ByteBuffer.allocateDirect(VERTICES.length * 4);
        bb.order(ByteOrder.nativeOrder());
        vertexBuffer = bb.asFloatBuffer();
        vertexBuffer.put(VERTICES);
        vertexBuffer.position(0);
        
        // Initialize texture coordinate buffer
        bb = ByteBuffer.allocateDirect(TEXTURE_COORDS.length * 4);
        bb.order(ByteOrder.nativeOrder());
        textureCoordBuffer = bb.asFloatBuffer();
        textureCoordBuffer.put(TEXTURE_COORDS);
        textureCoordBuffer.position(0);
        
        // Initialize matrices
        Matrix.setIdentityM(mvpMatrix, 0);
        Matrix.setIdentityM(texMatrix, 0);
    }
    
    /**
     * Initialize the renderer with the output surface
     */
    public void initialize(Surface outputSurface) throws RuntimeException {
        this.outputSurface = outputSurface;
        
        setupEGL();
        setupShaders();
        
        initialized = true;
        Log.d(TAG, "VideoRenderer initialized successfully");
    }
    
    private void setupEGL() throws RuntimeException {
        // Get EGL display
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("Unable to get EGL14 display");
        }
        
        // Initialize EGL
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new RuntimeException("Unable to initialize EGL14");
        }
        
        // Configure EGL
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
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0)) {
            throw new RuntimeException("Unable to find a suitable EGLConfig");
        }
        eglConfig = configs[0];
        
        // Create EGL context
        int[] contextAttribs = {
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw new RuntimeException("Failed to create EGL context");
        }
        
        // Create window surface
        int[] surfaceAttribs = {
            EGL14.EGL_NONE
        };
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, outputSurface, surfaceAttribs, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw new RuntimeException("Failed to create window surface");
        }
        
        // Make context current
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new RuntimeException("Failed to make EGL context current");
        }
    }
    
    private void setupShaders() throws RuntimeException {
        // Create shader program
        shaderProgram = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (shaderProgram == 0) {
            throw new RuntimeException("Failed to create shader program");
        }
        
        // Get attribute and uniform locations
        aPositionHandle = GLES20.glGetAttribLocation(shaderProgram, "aPosition");
        GlUtil.checkLocation(aPositionHandle, "aPosition");
        
        aTextureCoordHandle = GLES20.glGetAttribLocation(shaderProgram, "aTextureCoord");
        GlUtil.checkLocation(aTextureCoordHandle, "aTextureCoord");
        
        uMVPMatrixHandle = GLES20.glGetUniformLocation(shaderProgram, "uMVPMatrix");
        GlUtil.checkLocation(uMVPMatrixHandle, "uMVPMatrix");
        
        uTexMatrixHandle = GLES20.glGetUniformLocation(shaderProgram, "uTexMatrix");
        GlUtil.checkLocation(uTexMatrixHandle, "uTexMatrix");
        
        uTextureHandle = GLES20.glGetUniformLocation(shaderProgram, "sTexture");
        GlUtil.checkLocation(uTextureHandle, "sTexture");
        
        Log.d(TAG, "Shader program setup complete");
    }
    
    /**
     * Render a video frame with the given presentation time
     */
    public void renderFrame(long presentationTimeUs) {
        if (!initialized) {
            Log.w(TAG, "Renderer not initialized, skipping frame");
            return;
        }
        
        performanceMonitor.startOperation("frame_render");
        
        // Make sure EGL context is current
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "Failed to make EGL context current");
            performanceMonitor.endOperation("frame_render");
            return;
        }
        
        // Clear the screen
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        
        // Use shader program
        GLES20.glUseProgram(shaderProgram);
        GlUtil.checkGlError("glUseProgram");
        
        // Enable vertex attributes
        GLES20.glEnableVertexAttribArray(aPositionHandle);
        GLES20.glEnableVertexAttribArray(aTextureCoordHandle);
        
        // Set vertex data
        GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glVertexAttribPointer(aTextureCoordHandle, 2, GLES20.GL_FLOAT, false, 0, textureCoordBuffer);
        
        // Set matrices
        GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, mvpMatrix, 0);
        GLES20.glUniformMatrix4fv(uTexMatrixHandle, 1, false, texMatrix, 0);
        
        // Set texture unit
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glUniform1i(uTextureHandle, 0);
        
        // Draw the quad
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GlUtil.checkGlError("glDrawArrays");
        
        // Disable vertex attributes
        GLES20.glDisableVertexAttribArray(aPositionHandle);
        GLES20.glDisableVertexAttribArray(aTextureCoordHandle);
        
        // Set presentation time for the frame
        android.opengl.EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeUs * 1000);
        
        // Swap buffers
        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            Log.e(TAG, "Failed to swap EGL buffers");
        }
        
        // Record frame performance and end timing
        performanceMonitor.recordFrameTime();
        performanceMonitor.endOperation("frame_render");
    }
    
    /**
     * Update the texture transformation matrix
     */
    public void updateTextureMatrix(float[] textureMatrix) {
        if (textureMatrix != null && textureMatrix.length >= 16) {
            System.arraycopy(textureMatrix, 0, texMatrix, 0, 16);
        }
    }
    
    /**
     * Update the model-view-projection matrix
     */
    public void updateMVPMatrix(float[] mvpMatrix) {
        if (mvpMatrix != null && mvpMatrix.length >= 16) {
            System.arraycopy(mvpMatrix, 0, this.mvpMatrix, 0, 16);
        }
    }
    
    /**
     * Release all resources
     */
    public void release() {
        Log.d(TAG, "Releasing VideoRenderer resources");
        
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
                eglSurface = EGL14.EGL_NO_SURFACE;
            }
            
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                eglContext = EGL14.EGL_NO_CONTEXT;
            }
            
            EGL14.eglTerminate(eglDisplay);
            eglDisplay = EGL14.EGL_NO_DISPLAY;
        }
        
        if (shaderProgram != 0) {
            GLES20.glDeleteProgram(shaderProgram);
            shaderProgram = 0;
        }
        
        initialized = false;
    }
}