package com.fadcam.dualcam.pipeline;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;

import com.fadcam.dualcam.DualCameraConfig;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * OpenGL ES 2.0 compositor that combines two OES camera textures into a
 * single output frame with Picture-in-Picture layout.
 *
 * <h3>Rendering Pipeline</h3>
 * <ol>
 *   <li>Primary camera texture → drawn full-screen</li>
 *   <li>Secondary camera texture → drawn in PiP corner (with optional rounded corners + border)</li>
 * </ol>
 *
 * <p>The compositor owns its own EGL context bound to the encoder's input surface.
 * It creates two {@link SurfaceTexture}s — one per camera — each backed by a
 * separate OES texture. Camera frames arrive on the GL thread via
 * {@code onFrameAvailable} callbacks.
 *
 * <p>Thread safety: all GL calls must be made from the render thread that
 * initialised EGL. Frame-available flags use synchronised access.
 */
public class DualCameraCompositor {

    private static final String TAG = "DualCamCompositor";

    // ── EGL ────────────────────────────────────────────────────────────
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private EGLConfig eglConfig;

    // ── Primary camera (full-screen) ──────────────────────────────────
    private int primaryOesTextureId;
    private SurfaceTexture primarySurfaceTexture;
    private Surface primaryInputSurface;
    private volatile boolean primaryFrameAvailable = false;
    private final Object primaryFrameLock = new Object();

    // ── Secondary camera (PiP) ────────────────────────────────────────
    private int secondaryOesTextureId;
    private SurfaceTexture secondarySurfaceTexture;
    private Surface secondaryInputSurface;
    private volatile boolean secondaryFrameAvailable = false;
    private final Object secondaryFrameLock = new Object();

    // ── Shader programs ───────────────────────────────────────────────
    private int oesProgram;          // OES texture full-screen draw
    private int pipBorderProgram;    // Simple colour program for PiP border

    // ── Uniform / attribute locations (OES program) ───────────────────
    private int oesPositionHandle;
    private int oesTexCoordHandle;
    private int oesTextureHandle;
    private int oesMvpMatrixHandle;
    private int oesTexMatrixHandle;

    // ── Uniform / attribute locations (border program) ────────────────
    private int borderPositionHandle;
    private int borderColorHandle;

    // ── Geometry buffers ──────────────────────────────────────────────
    private FloatBuffer fullscreenVertexBuffer;
    private FloatBuffer fullscreenTexCoordBuffer;
    private FloatBuffer pipVertexBuffer;
    private FloatBuffer pipTexCoordBuffer;
    private FloatBuffer pipBorderVertexBuffer;

    // ── Configuration ─────────────────────────────────────────────────
    private DualCameraConfig config;
    private final int outputWidth;
    private final int outputHeight;

    /** When true, primary = camera1 (as opened). When false, they are swapped. */
    private volatile boolean swapped = false;

    // ── Matrices ──────────────────────────────────────────────────────
    private final float[] identityMatrix = new float[16];
    private final float[] primaryTexMatrix = new float[16];
    private final float[] secondaryTexMatrix = new float[16];
    private final float[] mvpMatrix = new float[16];

    // Full-screen quad vertices (NDC)
    private static final float[] FULLSCREEN_VERTICES = {
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
    };

    private static final float[] FULLSCREEN_TEXCOORDS = {
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
    };

    // ── OES vertex shader ─────────────────────────────────────────────
    private static final String OES_VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = uMVPMatrix * aPosition;\n" +
            "    vTexCoord = (uTexMatrix * aTexCoord).xy;\n" +
            "}\n";

    // ── OES fragment shader ───────────────────────────────────────────
    private static final String OES_FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform samplerExternalOES uTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
            "}\n";

    // ── PiP fragment shader with rounded corners ──────────────────────
    private static final String PIP_FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform samplerExternalOES uTexture;\n" +
            "uniform float uCornerRadius;\n" +
            "uniform vec2 uPipSize;\n" +
            "void main() {\n" +
            "    vec2 uv = vTexCoord;\n" +
            "    // Map texture coords to [0,1] PiP space\n" +
            "    vec2 pipPos = uv;\n" +
            "    // Distance from nearest corner in PiP-normalised coords\n" +
            "    vec2 halfSize = vec2(0.5);\n" +
            "    vec2 fromCenter = abs(pipPos - halfSize);\n" +
            "    vec2 cornerDist = fromCenter - (halfSize - vec2(uCornerRadius));\n" +
            "    if (cornerDist.x > 0.0 && cornerDist.y > 0.0) {\n" +
            "        float dist = length(cornerDist);\n" +
            "        if (dist > uCornerRadius) {\n" +
            "            discard;\n" +
            "        }\n" +
            "    }\n" +
            "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
            "}\n";

    // ── Simple colour shader for PiP border ───────────────────────────
    private static final String BORDER_VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "}\n";

    private static final String BORDER_FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "uniform vec4 uColor;\n" +
            "void main() {\n" +
            "    gl_FragColor = uColor;\n" +
            "}\n";

    // PiP shader extras
    private int pipProgram;
    private int pipPositionHandle;
    private int pipTexCoordHandle;
    private int pipTextureHandle;
    private int pipMvpMatrixHandle;
    private int pipTexMatrixHandle;
    private int pipCornerRadiusHandle;
    private int pipSizeHandle;

    // ════════════════════════════════════════════════════════════════════
    // CONSTRUCTION
    // ════════════════════════════════════════════════════════════════════

    /**
     * @param encoderSurface The encoder's input {@link Surface}.
     * @param outputWidth    Output video width in pixels.
     * @param outputHeight   Output video height in pixels.
     * @param config         PiP configuration.
     */
    public DualCameraCompositor(
            @NonNull Surface encoderSurface,
            int outputWidth, int outputHeight,
            @NonNull DualCameraConfig config) {
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
        this.config = config;
        Matrix.setIdentityM(identityMatrix, 0);

        setupEGL(encoderSurface);
        setupShaders();
        createOESTextures();
        buildGeometry();

        Log.d(TAG, "Compositor initialised: " + outputWidth + "x" + outputHeight);
    }

    // ════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ════════════════════════════════════════════════════════════════════

    /** Surface the primary camera should target. */
    @NonNull
    public Surface getPrimaryInputSurface() {
        return primaryInputSurface;
    }

    /** Surface the secondary camera should target. */
    @NonNull
    public Surface getSecondaryInputSurface() {
        return secondaryInputSurface;
    }

    /** Swap primary and secondary rendering without recreating textures. */
    public void swapCameras() {
        swapped = !swapped;
        Log.d(TAG, "Cameras swapped — swapped=" + swapped);
    }

    /** Live-update PiP layout (position, size, border). */
    public void updateConfig(@NonNull DualCameraConfig newConfig) {
        this.config = newConfig;
        rebuildPipGeometry();
        Log.d(TAG, "Config updated live");
    }

    /**
     * Renders one composited frame to the encoder surface.
     * Must be called on the GL render thread.
     *
     * @return {@code true} if at least one new frame was rendered.
     */
    public boolean renderFrame() {
        // Update textures from camera frames
        boolean hadPrimary = updateTexture(true);
        boolean hadSecondary = updateTexture(false);

        if (!hadPrimary && !hadSecondary) {
            return false; // No new frames
        }

        // Make encoder surface current
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "eglMakeCurrent failed");
            return false;
        }

        GLES20.glViewport(0, 0, outputWidth, outputHeight);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // Determine which texture is primary/secondary based on swap state
        int fullscreenTex = swapped ? secondaryOesTextureId : primaryOesTextureId;
        float[] fullscreenTexMat = swapped ? secondaryTexMatrix : primaryTexMatrix;
        int pipTex = swapped ? primaryOesTextureId : secondaryOesTextureId;
        float[] pipTexMat = swapped ? primaryTexMatrix : secondaryTexMatrix;

        // ── Draw full-screen (primary) camera ─────────────────────────
        drawOESTexture(oesProgram, fullscreenVertexBuffer, fullscreenTexCoordBuffer,
                fullscreenTex, fullscreenTexMat, identityMatrix);

        // ── Draw PiP border (if enabled) ──────────────────────────────
        if (config.isShowPipBorder() && pipBorderVertexBuffer != null) {
            drawPipBorder();
        }

        // ── Draw PiP (secondary) camera ───────────────────────────────
        if (config.isRoundPipCorners()) {
            drawPipWithRoundedCorners(pipTex, pipTexMat);
        } else {
            drawOESTexture(oesProgram, pipVertexBuffer, pipTexCoordBuffer,
                    pipTex, pipTexMat, identityMatrix);
        }

        // Swap to encoder
        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
        return true;
    }

    /**
     * Release all GL + EGL resources.
     * Call from the GL render thread.
     */
    public void release() {
        Log.d(TAG, "Releasing compositor resources");

        releaseSurfaceTexture(primarySurfaceTexture, primaryInputSurface);
        releaseSurfaceTexture(secondarySurfaceTexture, secondaryInputSurface);
        primarySurfaceTexture = null;
        secondarySurfaceTexture = null;
        primaryInputSurface = null;
        secondaryInputSurface = null;

        if (primaryOesTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[]{primaryOesTextureId}, 0);
            primaryOesTextureId = 0;
        }
        if (secondaryOesTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[]{secondaryOesTextureId}, 0);
            secondaryOesTextureId = 0;
        }

        deleteProgram(oesProgram);
        deleteProgram(pipProgram);
        deleteProgram(pipBorderProgram);
        oesProgram = 0;
        pipProgram = 0;
        pipBorderProgram = 0;

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
            }
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;

        Log.d(TAG, "Compositor released");
    }

    // ════════════════════════════════════════════════════════════════════
    // EGL SETUP
    // ════════════════════════════════════════════════════════════════════

    private void setupEGL(@NonNull Surface encoderSurface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY)
            throw new RuntimeException("eglGetDisplay failed");

        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1))
            throw new RuntimeException("eglInitialize failed");

        int[] configAttribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                0x3142, 1, // EGL_RECORDABLE_ANDROID
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0);
        if (numConfigs[0] == 0)
            throw new RuntimeException("eglChooseConfig: no suitable config");
        eglConfig = configs[0];

        int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig,
                EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT)
            throw new RuntimeException("eglCreateContext failed");

        int[] surfaceAttribs = {EGL14.EGL_NONE};
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig,
                encoderSurface, surfaceAttribs, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE)
            throw new RuntimeException("eglCreateWindowSurface failed");

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext))
            throw new RuntimeException("eglMakeCurrent failed");
    }

    // ════════════════════════════════════════════════════════════════════
    // SHADER SETUP
    // ════════════════════════════════════════════════════════════════════

    private void setupShaders() {
        // ── OES full-screen program ───────────────────────────────────
        oesProgram = createProgram(OES_VERTEX_SHADER, OES_FRAGMENT_SHADER);
        oesPositionHandle = GLES20.glGetAttribLocation(oesProgram, "aPosition");
        oesTexCoordHandle = GLES20.glGetAttribLocation(oesProgram, "aTexCoord");
        oesTextureHandle = GLES20.glGetUniformLocation(oesProgram, "uTexture");
        oesMvpMatrixHandle = GLES20.glGetUniformLocation(oesProgram, "uMVPMatrix");
        oesTexMatrixHandle = GLES20.glGetUniformLocation(oesProgram, "uTexMatrix");

        // ── PiP program (rounded corners) ─────────────────────────────
        pipProgram = createProgram(OES_VERTEX_SHADER, PIP_FRAGMENT_SHADER);
        pipPositionHandle = GLES20.glGetAttribLocation(pipProgram, "aPosition");
        pipTexCoordHandle = GLES20.glGetAttribLocation(pipProgram, "aTexCoord");
        pipTextureHandle = GLES20.glGetUniformLocation(pipProgram, "uTexture");
        pipMvpMatrixHandle = GLES20.glGetUniformLocation(pipProgram, "uMVPMatrix");
        pipTexMatrixHandle = GLES20.glGetUniformLocation(pipProgram, "uTexMatrix");
        pipCornerRadiusHandle = GLES20.glGetUniformLocation(pipProgram, "uCornerRadius");
        pipSizeHandle = GLES20.glGetUniformLocation(pipProgram, "uPipSize");

        // ── Border program ────────────────────────────────────────────
        pipBorderProgram = createProgram(BORDER_VERTEX_SHADER, BORDER_FRAGMENT_SHADER);
        borderPositionHandle = GLES20.glGetAttribLocation(pipBorderProgram, "aPosition");
        borderColorHandle = GLES20.glGetUniformLocation(pipBorderProgram, "uColor");
    }

    // ════════════════════════════════════════════════════════════════════
    // TEXTURE SETUP
    // ════════════════════════════════════════════════════════════════════

    private void createOESTextures() {
        int[] textures = new int[2];
        GLES20.glGenTextures(2, textures, 0);

        // Primary OES texture
        primaryOesTextureId = textures[0];
        configureOESTexture(primaryOesTextureId);
        primarySurfaceTexture = new SurfaceTexture(primaryOesTextureId);
        primarySurfaceTexture.setDefaultBufferSize(outputWidth, outputHeight);
        primaryInputSurface = new Surface(primarySurfaceTexture);
        primarySurfaceTexture.setOnFrameAvailableListener(st -> {
            synchronized (primaryFrameLock) {
                primaryFrameAvailable = true;
                primaryFrameLock.notifyAll();
            }
        });

        // Secondary OES texture
        secondaryOesTextureId = textures[1];
        configureOESTexture(secondaryOesTextureId);
        secondarySurfaceTexture = new SurfaceTexture(secondaryOesTextureId);
        secondarySurfaceTexture.setDefaultBufferSize(outputWidth, outputHeight);
        secondaryInputSurface = new Surface(secondarySurfaceTexture);
        secondarySurfaceTexture.setOnFrameAvailableListener(st -> {
            synchronized (secondaryFrameLock) {
                secondaryFrameAvailable = true;
                secondaryFrameLock.notifyAll();
            }
        });

        Log.d(TAG, "Created 2 OES textures: primary=" + primaryOesTextureId
                + ", secondary=" + secondaryOesTextureId);
    }

    private void configureOESTexture(int textureId) {
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    // ════════════════════════════════════════════════════════════════════
    // GEOMETRY
    // ════════════════════════════════════════════════════════════════════

    private void buildGeometry() {
        fullscreenVertexBuffer = createFloatBuffer(FULLSCREEN_VERTICES);
        fullscreenTexCoordBuffer = createFloatBuffer(FULLSCREEN_TEXCOORDS);
        rebuildPipGeometry();
    }

    /**
     * Recalculates PiP vertex positions and border geometry based on current config.
     */
    private void rebuildPipGeometry() {
        float pipRatio = config.getPipSize().ratio;

        // PiP dimensions in NDC [-1, 1]
        float pipW = pipRatio * 2f; // Width in NDC
        float pipAspect = (float) outputWidth / outputHeight;
        float pipH = pipW / pipAspect; // Maintain aspect ratio

        // Margin in NDC
        float marginNdc = (config.getPipMarginDp() / 360f) * 2f; // Approx conversion

        float left, bottom;
        switch (config.getPipPosition()) {
            case TOP_LEFT:
                left = -1f + marginNdc;
                bottom = 1f - marginNdc - pipH;
                break;
            case TOP_RIGHT:
                left = 1f - marginNdc - pipW;
                bottom = 1f - marginNdc - pipH;
                break;
            case BOTTOM_LEFT:
                left = -1f + marginNdc;
                bottom = -1f + marginNdc;
                break;
            case BOTTOM_RIGHT:
            default:
                left = 1f - marginNdc - pipW;
                bottom = -1f + marginNdc;
                break;
        }

        float right = left + pipW;
        float top = bottom + pipH;

        float[] pipVerts = {
                left,  bottom,
                right, bottom,
                left,  top,
                right, top
        };
        pipVertexBuffer = createFloatBuffer(pipVerts);
        pipTexCoordBuffer = createFloatBuffer(FULLSCREEN_TEXCOORDS);

        // Border (slightly larger quad behind PiP)
        if (config.isShowPipBorder()) {
            float borderPx = 3f; // 3dp border
            float borderNdc = (borderPx / 360f) * 2f;
            float[] borderVerts = {
                    left - borderNdc,  bottom - borderNdc,
                    right + borderNdc, bottom - borderNdc,
                    left - borderNdc,  top + borderNdc,
                    right + borderNdc, top + borderNdc
            };
            pipBorderVertexBuffer = createFloatBuffer(borderVerts);
        } else {
            pipBorderVertexBuffer = null;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // DRAWING
    // ════════════════════════════════════════════════════════════════════

    /** Updates a camera's SurfaceTexture if a new frame is available. */
    private boolean updateTexture(boolean isPrimary) {
        if (isPrimary) {
            synchronized (primaryFrameLock) {
                if (!primaryFrameAvailable) return false;
                primaryFrameAvailable = false;
            }
            try {
                primarySurfaceTexture.updateTexImage();
                primarySurfaceTexture.getTransformMatrix(primaryTexMatrix);
            } catch (Exception e) {
                Log.w(TAG, "Error updating primary texture", e);
                return false;
            }
            return true;
        } else {
            synchronized (secondaryFrameLock) {
                if (!secondaryFrameAvailable) return false;
                secondaryFrameAvailable = false;
            }
            try {
                secondarySurfaceTexture.updateTexImage();
                secondarySurfaceTexture.getTransformMatrix(secondaryTexMatrix);
            } catch (Exception e) {
                Log.w(TAG, "Error updating secondary texture", e);
                return false;
            }
            return true;
        }
    }

    /** Draw an OES texture with the given geometry. */
    private void drawOESTexture(int program,
                                FloatBuffer vertexBuf,
                                FloatBuffer texCoordBuf,
                                int textureId,
                                float[] texMatrix,
                                float[] mvp) {
        GLES20.glUseProgram(program);

        vertexBuf.position(0);
        GLES20.glEnableVertexAttribArray(oesPositionHandle);
        GLES20.glVertexAttribPointer(oesPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuf);

        texCoordBuf.position(0);
        GLES20.glEnableVertexAttribArray(oesTexCoordHandle);
        GLES20.glVertexAttribPointer(oesTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuf);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(oesTextureHandle, 0);

        GLES20.glUniformMatrix4fv(oesMvpMatrixHandle, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(oesTexMatrixHandle, 1, false, texMatrix, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(oesPositionHandle);
        GLES20.glDisableVertexAttribArray(oesTexCoordHandle);
    }

    /** Draw PiP using the rounded-corner fragment shader. */
    private void drawPipWithRoundedCorners(int textureId, float[] texMatrix) {
        GLES20.glUseProgram(pipProgram);

        pipVertexBuffer.position(0);
        GLES20.glEnableVertexAttribArray(pipPositionHandle);
        GLES20.glVertexAttribPointer(pipPositionHandle, 2, GLES20.GL_FLOAT, false, 0, pipVertexBuffer);

        pipTexCoordBuffer.position(0);
        GLES20.glEnableVertexAttribArray(pipTexCoordHandle);
        GLES20.glVertexAttribPointer(pipTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, pipTexCoordBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(pipTextureHandle, 0);

        GLES20.glUniformMatrix4fv(pipMvpMatrixHandle, 1, false, identityMatrix, 0);
        GLES20.glUniformMatrix4fv(pipTexMatrixHandle, 1, false, texMatrix, 0);

        // Corner radius in texture-coordinate space
        float cornerRadius = 0.08f; // ~8% of PiP size
        GLES20.glUniform1f(pipCornerRadiusHandle, cornerRadius);

        float pipW = config.getPipSize().ratio * 2f;
        float pipAspect = (float) outputWidth / outputHeight;
        float pipH = pipW / pipAspect;
        GLES20.glUniform2f(pipSizeHandle, pipW, pipH);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(pipPositionHandle);
        GLES20.glDisableVertexAttribArray(pipTexCoordHandle);
    }

    /** Draw a white border behind the PiP. */
    private void drawPipBorder() {
        GLES20.glUseProgram(pipBorderProgram);

        pipBorderVertexBuffer.position(0);
        GLES20.glEnableVertexAttribArray(borderPositionHandle);
        GLES20.glVertexAttribPointer(borderPositionHandle, 2, GLES20.GL_FLOAT, false, 0, pipBorderVertexBuffer);

        // White semi-transparent border
        GLES20.glUniform4f(borderColorHandle, 1f, 1f, 1f, 0.8f);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisable(GLES20.GL_BLEND);

        GLES20.glDisableVertexAttribArray(borderPositionHandle);
    }

    // ════════════════════════════════════════════════════════════════════
    // UTILITY
    // ════════════════════════════════════════════════════════════════════

    private static FloatBuffer createFloatBuffer(float[] data) {
        FloatBuffer buf = ByteBuffer.allocateDirect(data.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buf.put(data).position(0);
        return buf;
    }

    private static int createProgram(String vertexSrc, String fragmentSrc) {
        int vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexSrc);
        int fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            String info = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new RuntimeException("Program link failed: " + info);
        }

        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return program;
    }

    private static int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);

        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String info = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Shader compile failed: " + info);
        }
        return shader;
    }

    private static void deleteProgram(int program) {
        if (program != 0) {
            GLES20.glDeleteProgram(program);
        }
    }

    private void releaseSurfaceTexture(SurfaceTexture st, Surface surface) {
        if (surface != null) {
            try { surface.release(); } catch (Exception ignored) { }
        }
        if (st != null) {
            try { st.release(); } catch (Exception ignored) { }
        }
    }
}
