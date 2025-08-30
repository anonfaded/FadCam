package com.fadcam.ui.faditor.processors.opengl;

import android.content.Context;
import android.content.Context;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.Log;
import android.view.Surface;

import com.fadcam.opengl.grafika.GlUtil;
import com.fadcam.ui.faditor.utils.PerformanceMonitor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * OpenGL ES video frame renderer implementing GLSurfaceView.Renderer for frame display.
 * Handles video texture rendering with support for different color formats and transformations.
 * Requirements: 13.3, 13.5, 14.1, 14.4
 */
public class VideoRenderer implements GLSurfaceView.Renderer {
    
    private static final String TAG = "VideoRenderer";
    
    private final Context context;
    private Surface outputSurface;
    
    // Rendering components
    private ShaderManager shaderManager;
    private TextureManager textureManager;
    private FrameCache frameCache;
    private int currentVideoTexture = 0;
    private long currentFrameNumber = -1;
    
    // Viewport dimensions
    private int viewportWidth = 0;
    private int viewportHeight = 0;
    private int videoWidth = 0;
    private int videoHeight = 0;
    
    // Vertex data
    private FloatBuffer vertexBuffer;
    private FloatBuffer textureCoordBuffer;
    
    // Transformation matrices
    private final float[] mvpMatrix = new float[16];
    private final float[] texMatrix = new float[16];
    private final float[] projectionMatrix = new float[16];
    private final float[] viewMatrix = new float[16];
    
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
    private boolean hasVideoTexture = false;
    
    // Performance monitoring
    private final PerformanceMonitor performanceMonitor;
    
    public VideoRenderer(Context context) {
        this.context = context;
        this.performanceMonitor = PerformanceMonitor.getInstance();
        initializeBuffers();
        initializeMatrices();
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
        
    }
    
    private void initializeMatrices() {
        Matrix.setIdentityM(mvpMatrix, 0);
        Matrix.setIdentityM(texMatrix, 0);
        Matrix.setIdentityM(projectionMatrix, 0);
        Matrix.setIdentityM(viewMatrix, 0);
    }
    
    // GLSurfaceView.Renderer implementation
    
    @Override
    public void onSurfaceCreated(GL10 gl, javax.microedition.khronos.egl.EGLConfig config) {
        Log.d(TAG, "onSurfaceCreated");
        
        // Initialize OpenGL components
        shaderManager = new ShaderManager();
        textureManager = new TextureManager();
        frameCache = new FrameCache(textureManager);
        
        // Set clear color to black
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        
        // Enable blending for transparency support
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        
        initialized = true;
        Log.d(TAG, "VideoRenderer surface created successfully");
    }
    
    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        Log.d(TAG, "onSurfaceChanged: " + width + "x" + height);
        
        viewportWidth = width;
        viewportHeight = height;
        
        // Set viewport
        GLES20.glViewport(0, 0, width, height);
        
        // Update projection matrix for aspect ratio
        updateProjectionMatrix();
    }
    
    @Override
    public void onDrawFrame(GL10 gl) {
        if (!initialized) {
            return;
        }
        
        performanceMonitor.startOperation("frame_render");
        
        // Clear the screen
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        
        // Render video frame if available
        if (hasVideoTexture && currentVideoTexture != 0) {
            renderVideoFrame();
        }
        
        performanceMonitor.recordFrameTime();
        performanceMonitor.endOperation("frame_render");
    }
    
    /**
     * Set the video texture to render
     */
    public void setVideoTexture(int textureId) {
        currentVideoTexture = textureId;
        hasVideoTexture = (textureId != 0);
        Log.d(TAG, "Video texture set: " + textureId);
    }
    
    /**
     * Set the current frame for optimized rendering
     */
    public void setCurrentFrame(long frameNumber, long timestampUs) {
        currentFrameNumber = frameNumber;
        
        // Try to get cached frame first for smooth timeline scrubbing
        FrameCache.CachedFrame cachedFrame = frameCache.getCachedFrame(frameNumber);
        if (cachedFrame != null) {
            currentVideoTexture = cachedFrame.textureId;
            hasVideoTexture = true;
            Log.v(TAG, "Using cached frame: " + frameNumber);
        }
        
        // Prefetch nearby frames for smooth scrubbing
        frameCache.prefetchFrames(frameNumber, 5); // 5 frames in each direction
    }
    
    /**
     * Update video dimensions for proper aspect ratio rendering
     */
    public void setVideoSize(int width, int height) {
        videoWidth = width;
        videoHeight = height;
        
        // Configure frame cache for video dimensions
        frameCache.setVideoProperties(width, height);
        
        updateProjectionMatrix();
        Log.d(TAG, "Video size set: " + width + "x" + height + 
                  (width >= 3840 || height >= 2160 ? " (4K)" : ""));
    }
    
    /**
     * Update projection matrix based on video and viewport dimensions
     */
    private void updateProjectionMatrix() {
        if (viewportWidth == 0 || viewportHeight == 0 || videoWidth == 0 || videoHeight == 0) {
            Matrix.setIdentityM(projectionMatrix, 0);
            Matrix.setIdentityM(mvpMatrix, 0);
            return;
        }
        
        // Calculate aspect ratios
        float videoAspect = (float) videoWidth / videoHeight;
        float viewportAspect = (float) viewportWidth / viewportHeight;
        
        // Set up orthographic projection with proper aspect ratio
        if (videoAspect > viewportAspect) {
            // Video is wider - fit width, letterbox height
            float scale = viewportAspect / videoAspect;
            Matrix.orthoM(projectionMatrix, 0, -1f, 1f, -scale, scale, -1f, 1f);
        } else {
            // Video is taller - fit height, pillarbox width
            float scale = videoAspect / viewportAspect;
            Matrix.orthoM(projectionMatrix, 0, -scale, scale, -1f, 1f, -1f, 1f);
        }
        
        // Combine view and projection matrices
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0);
    }
    
    /**
     * Render the current video frame
     */
    private void renderVideoFrame() {
        if (!hasVideoTexture || currentVideoTexture == 0) {
            return;
        }
        
        // Use video shader program
        int shaderProgram = shaderManager.getVideoShaderProgram();
        GLES20.glUseProgram(shaderProgram);
        GlUtil.checkGlError("glUseProgram");
        
        // Get attribute and uniform locations
        int aPositionHandle = shaderManager.getAttributeLocation(shaderProgram, "aPosition");
        int aTextureCoordHandle = shaderManager.getAttributeLocation(shaderProgram, "aTextureCoord");
        int uMVPMatrixHandle = shaderManager.getUniformLocation(shaderProgram, "uMVPMatrix");
        int uTexMatrixHandle = shaderManager.getUniformLocation(shaderProgram, "uTexMatrix");
        int uTextureHandle = shaderManager.getUniformLocation(shaderProgram, "sTexture");
        
        // Enable vertex attributes
        GLES20.glEnableVertexAttribArray(aPositionHandle);
        GLES20.glEnableVertexAttribArray(aTextureCoordHandle);
        
        // Set vertex data
        GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glVertexAttribPointer(aTextureCoordHandle, 2, GLES20.GL_FLOAT, false, 0, textureCoordBuffer);
        
        // Set matrices
        GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, mvpMatrix, 0);
        GLES20.glUniformMatrix4fv(uTexMatrixHandle, 1, false, texMatrix, 0);
        
        // Bind video texture
        textureManager.bindTexture(currentVideoTexture);
        GLES20.glUniform1i(uTextureHandle, 0);
        
        // Draw the quad
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GlUtil.checkGlError("glDrawArrays");
        
        // Disable vertex attributes
        GLES20.glDisableVertexAttribArray(aPositionHandle);
        GLES20.glDisableVertexAttribArray(aTextureCoordHandle);
    }
    
    /**
     * Update frame for rendering (called by GLSurfaceView automatically)
     */
    public void updateFrame() {
        // This method can be called to trigger a redraw
        // The actual rendering happens in onDrawFrame()
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
     * Apply transformation matrix for video rotation/scaling
     */
    public void applyTransformation(float[] transformMatrix) {
        if (transformMatrix != null && transformMatrix.length >= 16) {
            // Apply transformation to view matrix
            Matrix.multiplyMM(viewMatrix, 0, transformMatrix, 0, GlUtil.IDENTITY_MATRIX, 0);
            // Update MVP matrix
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0);
        }
    }
    
    /**
     * Set display size for proper rendering
     */
    public void setDisplaySize(int width, int height) {
        // This is handled by onSurfaceChanged callback
        Log.d(TAG, "Display size set: " + width + "x" + height);
    }
    
    // Backward compatibility methods for existing OpenGLVideoProcessor
    
    /**
     * Initialize the renderer with output surface (backward compatibility)
     * @deprecated Use GLSurfaceView.Renderer interface instead
     */
    @Deprecated
    public void initialize(Surface outputSurface) throws RuntimeException {
        this.outputSurface = outputSurface;
        Log.d(TAG, "VideoRenderer initialized with surface (deprecated method)");
    }
    
    /**
     * Render frame with presentation time (backward compatibility)
     * @deprecated Use GLSurfaceView.Renderer interface instead
     */
    @Deprecated
    public void renderFrame(long presentationTimeUs) {
        Log.d(TAG, "renderFrame called (deprecated method) - use GLSurfaceView instead");
        // This method is deprecated - rendering should happen through GLSurfaceView.Renderer
    }
    
    /**
     * Cache current frame for timeline scrubbing
     */
    public void cacheCurrentFrame(long frameNumber, long timestampUs) {
        if (currentVideoTexture != 0 && frameNumber >= 0) {
            frameCache.cacheFrame(frameNumber, timestampUs, currentVideoTexture);
        }
    }
    
    /**
     * Clear frame cache (e.g., when loading new video)
     */
    public void clearFrameCache() {
        frameCache.clearCache();
    }
    
    /**
     * Get frame cache statistics
     */
    public FrameCache.CacheStats getFrameCacheStats() {
        return frameCache.getCacheStats();
    }
    
    /**
     * Get texture manager statistics
     */
    public TextureManager.TextureManagerStats getTextureStats() {
        return textureManager.getStats();
    }
    
    /**
     * Release all resources
     */
    public void release() {
        Log.d(TAG, "Releasing VideoRenderer resources");
        
        if (frameCache != null) {
            frameCache.release();
            frameCache = null;
        }
        
        if (shaderManager != null) {
            shaderManager.release();
            shaderManager = null;
        }
        
        if (textureManager != null) {
            textureManager.release();
            textureManager = null;
        }
        
        currentVideoTexture = 0;
        hasVideoTexture = false;
        initialized = false;
        currentFrameNumber = -1;
    }
}