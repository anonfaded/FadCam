package com.fadcam.opengl;

import android.graphics.Bitmap;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Renders a texture using OpenGL ES 2.0.
 * Handles both the camera texture (using GL_TEXTURE_EXTERNAL_OES) and a standard 2D texture (for the watermark).
 * <p>
 * Based on Grafika's Texture2dProgram and various OpenGL ES examples.
 */
public class TextureRenderer {
    private static final String TAG = "TextureRenderer";

    private static final int FLOAT_SIZE_BYTES = 4;
    private static final int TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES;
    private static final int TRIANGLE_VERTICES_DATA_POS_OFFSET = 0;
    private static final int TRIANGLE_VERTICES_DATA_UV_OFFSET = 3;

    // Simple vertex shader for drawing a textured quad.
    private static final String VERTEX_SHADER =
            "precision mediump float;\n" + // Required for compatibility on some devices
            "uniform mat4 uMVPMatrix;\n" +
            "uniform mat4 uSTMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "    gl_Position = uMVPMatrix * aPosition;\n" +
            "    vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
            "}\n";

    // Fragment shader for the camera texture (OES).
    private static final String FRAGMENT_SHADER_OES =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n";

    // Fragment shader for a standard 2D texture (watermark).
    private static final String FRAGMENT_SHADER_2D =
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform sampler2D sTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n";

    // Vertices for a simple quad covering the screen.
    private static final float[] TRIANGLE_VERTICES_DATA = {
            // X, Y, Z, U, V
            -1.0f, -1.0f, 0.0f, 0.0f, 0.0f,
             1.0f, -1.0f, 0.0f, 1.0f, 0.0f,
            -1.0f,  1.0f, 0.0f, 0.0f, 1.0f,
             1.0f,  1.0f, 0.0f, 1.0f, 1.0f,
    };

    private FloatBuffer mTriangleVertices;

    private int mProgramOES; // Program for OES texture (camera)
    private int mProgram2D;  // Program for 2D texture (watermark)

    private int muMVPMatrixHandleOES;
    private int muSTMatrixHandleOES;
    private int maPositionHandleOES;
    private int maTextureHandleOES;
    private int muTextureSamplerHandleOES;

    private int muMVPMatrixHandle2D;
    private int muSTMatrixHandle2D; // Not strictly needed for 2D, but keep for consistency
    private int maPositionHandle2D;
    private int maTextureHandle2D;
    private int muTextureSamplerHandle2D;

    private int mTextureIdOES = -1; // Texture ID for the camera output
    private int mTextureId2D = -1;  // Texture ID for the watermark

    private float[] mMVPMatrix = new float[16];
    private float[] mSTMatrix = new float[16]; // For camera texture transformation

    public TextureRenderer() {
        mTriangleVertices = ByteBuffer.allocateDirect(TRIANGLE_VERTICES_DATA.length * FLOAT_SIZE_BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mTriangleVertices.put(TRIANGLE_VERTICES_DATA).position(0);

        // Initialize matrices
        android.opengl.Matrix.setIdentityM(mSTMatrix, 0);
        android.opengl.Matrix.setIdentityM(mMVPMatrix, 0);
    }

    /**
     * Creates the OpenGL ES shaders and programs.
     * Must be called on the GL thread after the EGL context is current.
     */
    public void createPrograms() {
        mProgramOES = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_OES);
        if (mProgramOES == 0) {
            throw new RuntimeException("failed creating OES program");
        }
        maPositionHandleOES = GLES20.glGetAttribLocation(mProgramOES, "aPosition");
        checkGlError("glGetAttribLocation aPosition OES");
        if (maPositionHandleOES == -1) {
            throw new RuntimeException("Could not get attrib location for aPosition OES");
        }
        maTextureHandleOES = GLES20.glGetAttribLocation(mProgramOES, "aTextureCoord");
        checkGlError("glGetAttribLocation aTextureCoord OES");
        if (maTextureHandleOES == -1) {
            throw new RuntimeException("Could not get attrib location for aTextureCoord OES");
        }
        muMVPMatrixHandleOES = GLES20.glGetUniformLocation(mProgramOES, "uMVPMatrix");
        checkGlError("glGetUniformLocation uMVPMatrix OES");
        if (muMVPMatrixHandleOES == -1) {
            throw new RuntimeException("Could not get uniform location for uMVPMatrix OES");
        }
        muSTMatrixHandleOES = GLES20.glGetUniformLocation(mProgramOES, "uSTMatrix");
        checkGlError("glGetUniformLocation uSTMatrix OES");
        if (muSTMatrixHandleOES == -1) {
            throw new RuntimeException("Could not get uniform location for uSTMatrix OES");
        }
        muTextureSamplerHandleOES = GLES20.glGetUniformLocation(mProgramOES, "sTexture");
        checkGlError("glGetUniformLocation sTexture OES");
        if (muTextureSamplerHandleOES == -1) {
            throw new RuntimeException("Could not get uniform location for sTexture OES");
        }
        Log.d(TAG, "OES program created.");

        mProgram2D = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_2D);
        if (mProgram2D == 0) {
            throw new RuntimeException("failed creating 2D program");
        }
        maPositionHandle2D = GLES20.glGetAttribLocation(mProgram2D, "aPosition");
        checkGlError("glGetAttribLocation aPosition 2D");
        if (maPositionHandle2D == -1) {
            throw new RuntimeException("Could not get attrib location for aPosition 2D");
        }
        maTextureHandle2D = GLES20.glGetAttribLocation(mProgram2D, "aTextureCoord");
        checkGlError("glGetAttribLocation aTextureCoord 2D");
        if (maTextureHandle2D == -1) {
            throw new RuntimeException("Could not get attrib location for aTextureCoord 2D");
        }
        muMVPMatrixHandle2D = GLES20.glGetUniformLocation(mProgram2D, "uMVPMatrix");
        checkGlError("glGetUniformLocation uMVPMatrix 2D");
        if (muMVPMatrixHandle2D == -1) {
            throw new RuntimeException("Could not get uniform location for uMVPMatrix 2D");
        }
        muSTMatrixHandle2D = GLES20.glGetUniformLocation(mProgram2D, "uSTMatrix"); // Use same uniform name
        checkGlError("glGetUniformLocation uSTMatrix 2D");
        if (muSTMatrixHandle2D == -1) {
            throw new RuntimeException("Could not get uniform location for uSTMatrix 2D");
        }
        muTextureSamplerHandle2D = GLES20.glGetUniformLocation(mProgram2D, "sTexture");
        checkGlError("glGetUniformLocation sTexture 2D");
        if (muTextureSamplerHandle2D == -1) {
            throw new RuntimeException("Could not get uniform location for sTexture 2D");
        }
        Log.d(TAG, "2D program created.");
    }

    /**
     * Creates the external texture for camera input.
     * Must be called on the GL thread.
     * @return The texture ID.
     */
    public int createCameraTexture() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        checkGlError("glGenTextures");

        mTextureIdOES = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureIdOES);
        checkGlError("glBindTexture " + mTextureIdOES);

        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        checkGlError("glTexParameter");

        Log.d(TAG, "Camera texture created with ID: " + mTextureIdOES);
        return mTextureIdOES;
    }

    /**
     * Creates or updates the 2D texture for the watermark.
     * Must be called on the GL thread.
     * @param bitmap The bitmap containing the watermark text.
     */
    public void updateWatermarkTexture(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            Log.e(TAG, "Watermark bitmap is null or recycled, cannot update texture.");
            return;
        }

        if (mTextureId2D == -1) {
            // Create the texture if it doesn't exist
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            checkGlError("glGenTextures (watermark)");
            mTextureId2D = textures[0];
            Log.d(TAG, "Watermark texture created with ID: " + mTextureId2D);

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureId2D);
            checkGlError("glBindTexture (watermark) " + mTextureId2D);

            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            checkGlError("glTexParameter (watermark)");

            // Upload the initial bitmap
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            checkGlError("texImage2D (watermark)");

        } else {
            // Update the existing texture
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureId2D);
            checkGlError("glBindTexture (watermark update) " + mTextureId2D);

            // Upload the new bitmap data
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap);
            checkGlError("texSubImage2D (watermark)");
        }

        // Unbind texture
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    /**
     * Draws the camera frame and the watermark onto the current EGL surface.
     * Must be called on the GL thread.
     * @param cameraTextureId The ID of the camera texture (from createCameraTexture).
     * @param watermarkTextureId The ID of the watermark texture (from updateWatermarkTexture).
     * @param cameraTransform The transformation matrix for the camera texture (from SurfaceTexture.getTransformMatrix).
     * @param watermarkMatrix The model-view-projection matrix for the watermark (to position and scale it).
     */
    public void drawFrame(int cameraTextureId, int watermarkTextureId, float[] cameraTransform, float[] watermarkMatrix) {
        checkGlError("onDrawFrame start");

        // Clear the screen (optional, but good practice)
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);

        // --- Draw Camera Frame ---
        GLES20.glUseProgram(mProgramOES);
        checkGlError("glUseProgram OES");

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        GLES20.glUniform1i(muTextureSamplerHandleOES, 0);

        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET);
        GLES20.glVertexAttribPointer(maPositionHandleOES, 3, GLES20.GL_FLOAT, false,
                TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
        checkGlError("glVertexAttribPointer aPosition OES");
        GLES20.glEnableVertexAttribArray(maPositionHandleOES);
        checkGlError("glEnableVertexAttribArray aPosition OES");

        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET);
        GLES20.glVertexAttribPointer(maTextureHandleOES, 2, GLES20.GL_FLOAT, false,
                TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
        checkGlError("glVertexAttribPointer aTextureCoord OES");
        GLES20.glEnableVertexAttribArray(maTextureHandleOES);
        checkGlError("glEnableVertexAttribArray aTextureCoord OES");

        android.opengl.Matrix.setIdentityM(mMVPMatrix, 0); // Use identity for camera frame (covers full screen)
        GLES20.glUniformMatrix4fv(muMVPMatrixHandleOES, 1, false, mMVPMatrix, 0);
        GLES20.glUniformMatrix4fv(muSTMatrixHandleOES, 1, false, cameraTransform, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        checkGlError("glDrawArrays OES");

        GLES20.glDisableVertexAttribArray(maPositionHandleOES);
        GLES20.glDisableVertexAttribArray(maTextureHandleOES);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0); // Unbind camera texture

        // --- Draw Watermark ---
        if (watermarkTextureId != -1) {
            GLES20.glUseProgram(mProgram2D);
            checkGlError("glUseProgram 2D");

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1); // Use a different texture unit
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, watermarkTextureId);
            GLES20.glUniform1i(muTextureSamplerHandle2D, 1); // Link to texture unit 1

            mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET);
            GLES20.glVertexAttribPointer(maPositionHandle2D, 3, GLES20.GL_FLOAT, false,
                    TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
            checkGlError("glVertexAttribPointer aPosition 2D");
            GLES20.glEnableVertexAttribArray(maPositionHandle2D);
            checkGlError("glEnableVertexAttribArray aPosition 2D");

            mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET);
            GLES20.glVertexAttribPointer(maTextureHandle2D, 2, GLES20.GL_FLOAT, false,
                    TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
            checkGlError("glVertexAttribPointer aTextureCoord 2D");
            GLES20.glEnableVertexAttribArray(maTextureHandle2D);
            checkGlError("glEnableVertexAttribArray aTextureCoord 2D");

            // Use the provided watermark matrix for positioning and scaling
            GLES20.glUniformMatrix4fv(muMVPMatrixHandle2D, 1, false, watermarkMatrix, 0);
            android.opengl.Matrix.setIdentityM(mSTMatrix, 0); // Identity for 2D texture coords
            GLES20.glUniformMatrix4fv(muSTMatrixHandle2D, 1, false, mSTMatrix, 0);


            // Enable blending for transparency
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA); // Standard alpha blending

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            checkGlError("glDrawArrays 2D");

            GLES20.glDisable(GLES20.GL_BLEND); // Disable blending after drawing watermark
            GLES20.glDisableVertexAttribArray(maPositionHandle2D);
            GLES20.glDisableVertexAttribArray(maTextureHandle2D);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0); // Unbind watermark texture
        }

        // Unbind program
        GLES20.glUseProgram(0);
    }

    /**
     * Releases the OpenGL ES programs and textures.
     * Must be called on the GL thread.
     */
    public void release() {
        if (mProgramOES != 0) {
            GLES20.glDeleteProgram(mProgramOES);
            mProgramOES = 0;
        }
        if (mProgram2D != 0) {
            GLES20.glDeleteProgram(mProgram2D);
            mProgram2D = 0;
        }
        if (mTextureIdOES != -1) {
            GLES20.glDeleteTextures(1, new int[]{mTextureIdOES}, 0);
            mTextureIdOES = -1;
        }
        if (mTextureId2D != -1) {
            GLES20.glDeleteTextures(1, new int[]{mTextureId2D}, 0);
            mTextureId2D = -1;
        }
        Log.d(TAG, "TextureRenderer resources released.");
    }

    public int getWatermarkTextureId() {
        return mTextureId2D;
    }

    private int loadShader(int shaderType, String source) {
        int shader = GLES20.glCreateShader(shaderType);
        checkGlError("glCreateShader type=" + shaderType);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        String shaderTypeStr = (shaderType == GLES20.GL_VERTEX_SHADER) ? "VERTEX" : "FRAGMENT";
        String infoLog = GLES20.glGetShaderInfoLog(shader);
        if (!infoLog.isEmpty()) {
            Log.e(TAG, "Shader compile log (" + shaderTypeStr + "):\n" + infoLog);
        }
        if (compiled[0] == 0) {
            Log.e(TAG, "Could not compile " + shaderTypeStr + " shader. Source:\n" + source);
            GLES20.glDeleteShader(shader);
            shader = 0;
        }
        return shader;
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        if (vertexShader == 0) {
            return 0;
        }
        int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (pixelShader == 0) {
            return 0;
        }
        int program = GLES20.glCreateProgram();
        checkGlError("glCreateProgram");
        if (program == 0) {
            Log.e(TAG, "Could not create program");
        }
        GLES20.glAttachShader(program, vertexShader);
        checkGlError("glAttachShader");
        GLES20.glAttachShader(program, pixelShader);
        checkGlError("glAttachShader");
        GLES20.glLinkProgram(program);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Could not link program: ");
            Log.e(TAG, GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            program = 0;
        }
        return program;
    }

    private void checkGlError(String op) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            Log.e(TAG, op + ": glError " + error);
            //throw new RuntimeException(op + ": glError " + error); // uncomment to stop on error
        }
    }
}
