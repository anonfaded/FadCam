package com.fadcam.ui.faditor.processors.opengl;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;
import android.view.Surface;

import com.fadcam.opengl.grafika.GlUtil;

/**
 * Manages OpenGL ES external textures for efficient video frame processing.
 * Handles SurfaceTexture creation and texture updates for video input.
 */
public class VideoTexture {
    
    private static final String TAG = "VideoTexture";
    
    private int textureId;
    private SurfaceTexture surfaceTexture;
    private Surface surface;
    private final float[] textureMatrix = new float[16];
    
    private boolean initialized = false;
    private final Object frameSyncObject = new Object();
    private boolean frameAvailable = false;
    
    /**
     * Initialize the video texture and create the input surface
     */
    public void initialize() {
        if (initialized) {
            Log.w(TAG, "VideoTexture already initialized");
            return;
        }
        
        // Generate external texture
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        
        // Bind and configure the external texture
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GlUtil.checkGlError("glBindTexture");
        
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GlUtil.checkGlError("texture parameter setup");
        
        // Create SurfaceTexture
        surfaceTexture = new SurfaceTexture(textureId);
        surfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                synchronized (frameSyncObject) {
                    frameAvailable = true;
                    frameSyncObject.notifyAll();
                }
            }
        });
        
        // Create Surface for MediaCodec decoder output
        surface = new Surface(surfaceTexture);
        
        initialized = true;
        Log.d(TAG, "VideoTexture initialized with texture ID: " + textureId);
    }
    
    /**
     * Get the Surface for MediaCodec decoder output
     */
    public Surface getSurface() {
        if (!initialized) {
            initialize();
        }
        return surface;
    }
    
    /**
     * Initialize without creating surface (for cases where surface is not needed)
     */
    public VideoTexture() {
        // Constructor - initialization happens lazily
    }
    
    /**
     * Get the OpenGL texture ID
     */
    public int getTextureId() {
        return textureId;
    }
    
    /**
     * Update the texture image from the SurfaceTexture
     * This should be called when a new frame is available
     */
    public void updateTexImage() {
        if (!initialized) {
            Log.w(TAG, "VideoTexture not initialized");
            return;
        }
        
        synchronized (frameSyncObject) {
            if (!frameAvailable) {
                return;
            }
            frameAvailable = false;
        }
        
        try {
            surfaceTexture.updateTexImage();
            surfaceTexture.getTransformMatrix(textureMatrix);
        } catch (RuntimeException e) {
            Log.e(TAG, "Error updating texture image", e);
        }
    }
    
    /**
     * Wait for a new frame to be available
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return true if a frame became available, false if timeout occurred
     */
    public boolean waitForNewFrame(long timeoutMs) {
        synchronized (frameSyncObject) {
            if (frameAvailable) {
                return true;
            }
            
            try {
                frameSyncObject.wait(timeoutMs);
                return frameAvailable;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
    
    /**
     * Get the current texture transformation matrix
     */
    public float[] getTextureMatrix() {
        return textureMatrix.clone();
    }
    
    /**
     * Copy the texture transformation matrix to the provided array
     */
    public void getTextureMatrix(float[] matrix) {
        if (matrix != null && matrix.length >= 16) {
            System.arraycopy(textureMatrix, 0, matrix, 0, 16);
        }
    }
    
    /**
     * Bind the texture for rendering
     */
    public void bindTexture() {
        if (!initialized) {
            Log.w(TAG, "VideoTexture not initialized");
            return;
        }
        
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GlUtil.checkGlError("bindTexture");
    }
    
    /**
     * Set the default buffer size for the SurfaceTexture
     */
    public void setDefaultBufferSize(int width, int height) {
        if (surfaceTexture != null) {
            surfaceTexture.setDefaultBufferSize(width, height);
            Log.d(TAG, "Set default buffer size: " + width + "x" + height);
        }
    }
    
    /**
     * Get the timestamp of the current frame
     */
    public long getTimestamp() {
        if (surfaceTexture != null) {
            return surfaceTexture.getTimestamp();
        }
        return 0;
    }
    
    /**
     * Check if the texture is initialized and ready for use
     */
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * Release all resources
     */
    public void release() {
        Log.d(TAG, "Releasing VideoTexture resources");
        
        if (surface != null) {
            surface.release();
            surface = null;
        }
        
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }
        
        if (textureId != 0) {
            int[] textures = new int[] { textureId };
            GLES20.glDeleteTextures(1, textures, 0);
            textureId = 0;
        }
        
        initialized = false;
    }
}