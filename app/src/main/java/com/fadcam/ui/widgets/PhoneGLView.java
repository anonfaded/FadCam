package com.fadcam.ui.widgets;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.AttributeSet;
import android.view.TextureView;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A real OpenGL ES phone: an actual 3D mesh (body, side buttons, protruding camera visor) with
 * directional lighting and the live screen/back content as textures. Rendered on a dedicated
 * GL thread, on demand only — no continuous rendering, so it stays fast and battery friendly.
 *
 * <p>GLES 2.0 is used deliberately: identical output for this scene and universal support.
 * The mesh is ~120 triangles; the cost is one textured draw per face group.
 */
public class PhoneGLView extends TextureView implements TextureView.SurfaceTextureListener {
    private static final float PHONE_ASPECT = 2.05f;   // height / width (matches the textures)
    private static final float DEPTH_RATIO = 0.105f;   // realistic phone thickness / width
    private static final float FRAME_INSET = 0.030f;   // glass inset (matches the surface rail+bezel)
    private static final float CHAMFER = 0.012f;       // beveled frame depth
    private static final float CORNER_RADIUS = 0.135f; // matches the texture's rounded silhouette
    private static final float FOV = 30f;              // a product-shot lens
    private static final float CAM_Z = 3.2f;
    // Key light mostly from the left, slightly high — reveals side edges and thickness.
    private static final float[] LIGHT_DIR = {-0.62f, 0.30f, 0.72f};

    // Button/visor placement (fractions of the phone, top-down; keep in sync with the surface).
    private static final float BTN_PROTRUSION = 0.016f;
    private static final float VOL_UP_CENTER = 0.270f;
    private static final float VOL_DOWN_CENTER = 0.365f;
    private static final float POWER_CENTER = 0.400f;
    private static final float VOL_HEIGHT = 0.065f;
    private static final float POWER_HEIGHT = 0.095f;
    // Camera island placement (fractions of the phone).
    private static final float VISOR_INSET = 0.075f;
    private static final float VISOR_TOP = 0.075f;
    private static final float VISOR_HEIGHT = 0.21f;
    private static final float VISOR_DEPTH = 0.030f;

    private HandlerThread glThread;
    private Handler glHandler;
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

    private int program;
    private int aPos, aNormal, aUV, uMVP, uModel, uTex, uUseTex, uColor, uSpec, uLightDir, uFade, uRim;
    private int uSweep, uSweepX, uSweepK, uAmbient, uBoost;
    private int uView, uBeam, uBeamBlur, uBeamPos, uBeamDir, uMask;

    private FloatBuffer bufFront, bufBack, bufMetal, bufVisor, bufLenses, bufFlash, bufGlint, bufCores;
    private FloatBuffer bufGlare, bufStreak;
    private float streakX, streakY, streakZ;
    private float ledY, ledZ;
    private final float[] flashWorld = new float[4];
    private int uFlashPos, uFlashK;
    private float flashK;
    private int streakTex;
    private int glareTex;
    private Bitmap glareBitmap;
    private FloatBuffer bufVolUp, bufVolDown, bufPower;
    private FloatBuffer bufPanel;
    private volatile int pressedButton = 0; // 1 vol up, 2 vol down, 3 power
    private volatile float pressProgress = 0f;
    private ValueAnimator pressAnim;
    private final float[] pressedMvp = new float[32];
    private int backTex, screenTex;
    private final int[] screenTexSize = new int[2];
    private final int[] backTexSize = new int[2];
    private Bitmap pendingBack, pendingScreen;
    private final Object texLock = new Object();
    private final Object renderLock = new Object();
    private boolean renderRequested;

    private volatile float angle = 0f;
    private volatile float extraScale = 1f;
    /** 0..1 camera-flash burst: the LED element blows to white when it fires. */
    private volatile float flashGlow = 0f;
    private volatile float sweepIntensity = 0f;
    private volatile float sweepX = 0f;
    private volatile float ambient = 1f;
    private volatile float beamIntensity = 0f;
    private volatile float beamPos = 0f;
    private volatile float beamDirX = 0f;
    private volatile float beamDirY = -1f;
    private volatile float beamTrail = 0.2f;
    private volatile float beamBlur = 0f;   // 0 = sharp core, 1 = maximum smear
    // Dark layer: fully open by default (fade band entirely below the view, no darkening).
    private volatile float maskStart = 2f;
    private volatile float maskEnd = 3f;
    private volatile float maskMin = 1f;
    private volatile Runnable frameListener;
    private volatile float cropFraction = 1f;
    private final float[] screenRect = {0f, 0f, 1f, 1f};
    private volatile boolean rectPending;
    private volatile float rectU0, rectV0, rectU1, rectV1;
    private FloatBuffer bufScreen;
    private volatile float fadePx = 0f;
    private volatile float overscan = 1f;

    private final float[] proj = new float[16];
    private final float[] viewM = new float[16];
    private final float[] model = new float[16];
    private final float[] tmp = new float[16];
    private final float[] mvp = new float[16];

    private volatile float hostTransX = 0f;
    private volatile float hostTransY = 0f;

    public PhoneGLView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setSurfaceTextureListener(this);
        setOpaque(false);
    }

    @Override
    public void setTranslationX(float translationX) {
        super.setTranslationX(translationX);
        hostTransX = translationX;
        requestRender();
    }

    @Override
    public void setTranslationY(float translationY) {
        super.setTranslationY(translationY);
        hostTransY = translationY;
        requestRender();
    }

    /**
     * Where the lens pair and the flash LED land, in host pixels (y down), while the phone
     * faces away at 180 degrees. {@code out} = {lensX, lensY, ledX, ledY}. Lets the host
     * time the beam to the exact moment it touches the glass. Mirrors {@link #drawFrame}.
     */
    public static void projectCameraIsland(float viewW, float viewH, float overscan,
                                           float extraScale, float transX, float transY,
                                           float[] out) {
        float midY = PHONE_ASPECT / 2f - VISOR_TOP - VISOR_HEIGHT / 2f;
        float zBack = -DEPTH_RATIO / 2f - VISOR_DEPTH;
        float halfH = (float) (Math.tan(Math.toRadians(FOV / 2.0)) * CAM_Z);
        float halfW = halfH * (viewW / viewH);
        float visHalfH = halfH / overscan;
        float visHalfW = halfW / overscan;
        float scale = Math.min(visHalfW / 0.5f, visHalfH / (PHONE_ASPECT / 2f)) * extraScale;
        float shiftY = visHalfH - (PHONE_ASPECT / 2f) * scale;
        projectBack(LENS_PAIR_X, midY, zBack - 0.013f, scale, shiftY, viewW, viewH,
                halfW, halfH, transX, transY, out, 0);
        projectBack(LED_X, midY, zBack - 0.007f, scale, shiftY, viewW, viewH,
                halfW, halfH, transX, transY, out, 2);
    }

    private static void projectBack(float mx, float my, float mz, float scale, float shiftY,
                                    float viewW, float viewH, float halfW, float halfH,
                                    float transX, float transY, float[] out, int at) {
        // Rotation by 180 degrees about Y mirrors x and z.
        float wx = -scale * mx;
        float wy = shiftY + scale * my;
        float wz = -scale * mz;
        float f = CAM_Z / (CAM_Z - wz);
        out[at] = viewW / 2f * (1f + wx * f / halfW) + transX;
        out[at + 1] = viewH / 2f * (1f - wy * f / halfH) + transY;
    }

    // Camera island anchors in model space (x runs toward the viewer's left from behind).
    private static final float LENS_PAIR_X = (0.5f - VISOR_INSET) - VISOR_HEIGHT * 1.13f;
    private static final float LED_X = (-0.5f + VISOR_INSET) + VISOR_HEIGHT * 0.75f;

    // ------------------------------------------------------------- public API

    /** The model's Y rotation in degrees (0 = front, 180 = back). */
    public void setAngle(float degrees) {
        angle = degrees;
        requestRender();
    }

    /** Optional global scale multiplier (used by the fullscreen inspector and the intro). */
    public void setExtraScale(float scale) {
        extraScale = scale;
        requestRender();
    }

    /** Fires the camera flash: 0 = off, 1 = full burst (the LED renders white). */
    public void setFlashGlow(float glow) {
        flashGlow = Math.max(0f, Math.min(1f, glow));
        requestRender();
    }

    /** A soft light band gliding across the product: intensity 0..1 at world x. */
    public void setSweep(float intensity, float worldX) {
        sweepIntensity = Math.max(0f, Math.min(1f, intensity));
        sweepX = worldX;
        requestRender();
    }

    /**
     * Studio exposure 0..1: scales the object's whole shaded response. At low values the
     * phone is a barely visible silhouette in the dark and only the traveling beam shows.
     */
    public void setAmbient(float value) {
        ambient = Math.max(0f, Math.min(1f, value));
        requestRender();
    }

    /**
     * A product-film light beam racing across the view in screen space. {@code pos} is the
     * beam front along its travel axis (view-height units, 0 = view centre), {@code angleDeg}
     * the travel direction (0 = right, -90 = down). Sharp leading edge, long soft trail; it
     * catches chamfered edges hardest and lights the surfaces it crosses.
     */
    public void setBeam(float intensity, float pos, float angleDeg, float trail) {
        beamIntensity = Math.max(0f, intensity);
        beamPos = pos;
        beamTrail = Math.max(0.01f, trail);
        double rad = Math.toRadians(angleDeg);
        beamDirX = (float) Math.cos(rad);
        beamDirY = (float) Math.sin(rad);
        requestRender();
    }

    /** 0..1 motion-blur amount: widens the beam's core along its travel axis. */
    public void setBeamBlur(float blur) {
        beamBlur = Math.max(0f, Math.min(1f, blur));
        requestRender();
    }

    /**
     * The dark layer: everything above {@code fadeStart} (fraction of the view height from
     * the top) is lit, everything below {@code fadeEnd} is dimmed to {@code minLevel}, with a
     * smooth gradient between. Pass (2, 3, 1) to open it completely.
     */
    public void setDarkMask(float fadeStart, float fadeEnd, float minLevel) {
        maskStart = fadeStart;
        maskEnd = Math.max(fadeEnd, fadeStart + 0.001f);
        maskMin = Math.max(0f, Math.min(1f, minLevel));
        requestRender();
    }

    /** Runs once, on the UI thread, after the next frame has actually been drawn. */
    public void awaitNextFrame(Runnable listener) {
        frameListener = listener;
        requestRender();
    }

    /** 1 = whole phone; 0.55 = only the top part, dissolving at the bottom of the view. */
    public void setCrop(float fraction, float fadeHeightPx) {
        cropFraction = fraction;
        fadePx = fadeHeightPx;
        requestRender();
    }

    /** Normalized glass rect (u0,v0,u1,v1) — the live screen layer sits exactly there. */
    public void setScreenRect(float u0, float v0, float u1, float v1) {
        rectU0 = u0;
        rectV0 = v0;
        rectU1 = u1;
        rectV1 = v1;
        rectPending = true;
        requestRender();
    }

    /**
     * Rebuilds the glass/panel quads for a rect. Runs on the GL thread, in the same pass as
     * the texture uploads: the quads' baked UVs and the face textures always change together,
     * so a quad can never sample the old texture through new UVs.
     */
    private void applyScreenRect(float u0, float v0, float u1, float v1) {
        screenRect[0] = u0;
        screenRect[1] = v0;
        screenRect[2] = u1;
        screenRect[3] = v1;
        float hw = 0.5f;
        float hh = PHONE_ASPECT / 2f;
        float hd = DEPTH_RATIO / 2f;
        float x0 = (u0 - 0.5f) * (2f * hw);
        float x1 = (u1 - 0.5f) * (2f * hw);
        float y1v = hh - v0 * PHONE_ASPECT;
        float y0v = hh - v1 * PHONE_ASPECT;
        float r = Math.max(0.012f, CORNER_RADIUS - FRAME_INSET);
        // Rounded geometry: the glass/panel silhouettes curve exactly like the frame.
        bufScreen = toBuffer(roundedFace(x0, x1, y0v, y1v, r, hd - 0.0018f, u0, v0, u1, v1));
        // The panel is only ever seen from behind (rotation 180 mirrors world X): flip U so
        // the flag and the wordmark read correctly instead of mirrored.
        bufPanel = toBuffer(roundedFace(x0, x1, y0v, y1v, r, -hd + 0.0018f, u1, v0, u0, v1));
    }

    /** A rounded-rect face as a triangle fan, UV-mapped from the given rect. */
    private List<Float> roundedFace(float x0, float x1, float y0, float y1,
                                    float r, float z, float u0, float v0, float u1, float v1) {
        List<Float> out = new ArrayList<>();
        List<float[]> pts = roundedRect(x0, x1, y0, y1, r, 30);
        float cx = (x0 + x1) / 2f;
        float cy = (y0 + y1) / 2f;
        float cu = (u0 + u1) / 2f;
        float cv = (v0 + v1) / 2f;
        float nz = z > 0 ? 1f : -1f;
        for (int i = 0; i < pts.size() - 1; i++) {
            float[] a = pts.get(i);
            float[] b = pts.get(i + 1);
            vertex(out, cx, cy, z, 0f, 0f, nz, cu, cv);
            vertex(out, a[0], a[1], z, 0f, 0f, nz, uOf(a[0], x0, x1, u0, u1), vOf(a[1], y1, y0, v0, v1));
            vertex(out, b[0], b[1], z, 0f, 0f, nz, uOf(b[0], x0, x1, u0, u1), vOf(b[1], y1, y0, v0, v1));
        }
        return out;
    }

    private static float uOf(float x, float x0, float x1, float u0, float u1) {
        return u0 + (x - x0) / (x1 - x0) * (u1 - u0);
    }

    private static float vOf(float y, float yTop, float yBottom, float v0, float v1) {
        return v0 + (yTop - y) / (yTop - yBottom) * (v1 - v0);
    }

    /** Realistic press: the button sinks into the rail while held, smoothly. */
    public void setPressedButton(int which) {
        if (which > 0) pressedButton = which;
        if (pressAnim != null) pressAnim.cancel();
        pressAnim = ValueAnimator.ofFloat(pressProgress, which > 0 ? 1f : 0f);
        pressAnim.setDuration(which > 0 ? 110 : 180);
        pressAnim.addUpdateListener(a -> {
            pressProgress = (float) a.getAnimatedValue();
            requestRender();
        });
        pressAnim.start();
    }

    public void setScreenBitmap(Bitmap bitmap) {
        synchronized (texLock) {
            pendingScreen = bitmap;
        }
        requestRender();
    }

    /** >1 gives the view headroom around the layout slot so rotation never clips. */
    public void setOverscan(float value) {
        overscan = value;
        requestRender();
    }

    /** Shared lock so the UI can render a face bitmap while the GL thread uploads safely. */
    public Object bitmapLock() {
        return texLock;
    }

    public void setBackBitmap(Bitmap bitmap) {
        synchronized (texLock) {
            pendingBack = bitmap;
        }
        requestRender();
    }

    public void requestRender() {
        Handler handler = glHandler;
        if (handler == null) return;
        synchronized (renderLock) {
            if (renderRequested) return;
            renderRequested = true;
        }
        handler.post(this::doRender);
    }

    // ------------------------------------------------------- surface lifecycle

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        glThread = new HandlerThread("PhoneGL");
        glThread.start();
        glHandler = new Handler(glThread.getLooper());
        glHandler.post(() -> {
            initEGL(surface);
            doRender();
        });
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        requestRender();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        Handler handler = glHandler;
        if (handler != null) {
            handler.post(this::releaseGL);
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    @Override
    protected void onDetachedFromWindow() {
        Handler handler = glHandler;
        if (handler != null) {
            handler.post(this::releaseGL);
        }
        super.onDetachedFromWindow();
    }

    // -------------------------------------------------------------------- GL

    private void initEGL(SurfaceTexture surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return;
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return;

        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0);
        if (num[0] == 0) return;

        int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        int[] surfAttribs = {EGL14.EGL_NONE};
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, surfAttribs, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) return;
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);

        buildProgram();
        buildBuffers();
    }

    private void releaseGL() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
        if (glThread != null) {
            glThread.quitSafely();
            glThread = null;
            glHandler = null;
        }
    }

    private void doRender() {
        synchronized (renderLock) {
            renderRequested = false;
        }
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return;
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return;

        synchronized (texLock) {
            if (pendingBack != null) {
                backTex = uploadBitmap(pendingBack, backTex, backTexSize);
                pendingBack = null;
            }
            if (pendingScreen != null) {
                screenTex = uploadBitmap(pendingScreen, screenTex, screenTexSize);
                pendingScreen = null;
            }
        }
        // Same pass as the uploads above: the quads' UVs and the textures change together.
        if (rectPending) {
            rectPending = false;
            applyScreenRect(rectU0, rectV0, rectU1, rectV1);
        }
        boolean drew = drawFrame();
        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
        Runnable listener = frameListener;
        if (drew && listener != null) {
            frameListener = null;
            post(listener);
        }
    }

    private boolean drawFrame() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return false;
        GLES20.glViewport(0, 0, w, h);
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);

        GLES20.glUseProgram(program);

        float viewAspect = (float) w / (float) h;
        Matrix.perspectiveM(proj, 0, FOV, viewAspect, 0.1f, 100f);
        Matrix.setLookAtM(viewM, 0, 0f, 0f, CAM_Z, 0f, 0f, 0f, 0f, 1f, 0f);

        float halfH = (float) (Math.tan(Math.toRadians(FOV / 2.0)) * CAM_Z);
        float halfW = halfH * viewAspect;
        float visHalfH = halfH / overscan;
        float visHalfW = halfW / overscan;
        // Exactly 1.0: the rendered phone must fill its layout slot so the touch zones
        // (computed in that same slot space) line up with what the user sees.
        float scaleW = visHalfW / 0.5f;
        float scaleH = visHalfH / (cropFraction * PHONE_ASPECT / 2f);
        float scale = Math.min(scaleW, scaleH) * extraScale;

        // Pin the phone's top to the visible slot's top and rotate around the visible centre.
        float shiftY = visHalfH - (PHONE_ASPECT / 2f) * scale;
        float pivotW = (PHONE_ASPECT / 2f) * (1f - cropFraction) * scale;
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, 0f, shiftY, 0f);
        Matrix.translateM(model, 0, 0f, -pivotW, 0f);
        Matrix.rotateM(model, 0, angle, 0f, 1f, 0f);
        Matrix.translateM(model, 0, 0f, pivotW, 0f);
        Matrix.scaleM(model, 0, scale, scale, scale);
        Matrix.multiplyMM(tmp, 0, viewM, 0, model, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0);

        float[] lp = {LED_X, ledY, ledZ, 1f};
        Matrix.multiplyMV(flashWorld, 0, model, 0, lp, 0);
        GLES20.glUniform4f(uFlashPos, flashWorld[0], flashWorld[1], flashWorld[2], scale);
        float facing = (float) -Math.cos(Math.toRadians(angle));
        float gate = Math.max(0f, Math.min(1f, (facing - 0.6f) / 0.35f));
        flashK = flashGlow * 7.5f * gate;
        GLES20.glUniform1f(uFlashK, flashK);

        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
        GLES20.glUniform3fv(uLightDir, 1, LIGHT_DIR, 0);
        float fadeAt = fadePx > 0.5f
                ? (getHeight() * (1f - 1f / overscan) / 2f + fadePx)
                : 0f;
        GLES20.glUniform1f(uFade, fadeAt);
        GLES20.glUniform1f(uSweep, sweepIntensity);
        GLES20.glUniform1f(uSweepX, sweepX);
        GLES20.glUniform1f(uAmbient, ambient);
        GLES20.glUniform1f(uBoost, 0f);
        GLES20.glUniform4f(uView, w, h, hostTransX, hostTransY);
        GLES20.glUniform1f(uBeam, beamIntensity);
        GLES20.glUniform1f(uBeamBlur, beamBlur);
        GLES20.glUniform1f(uBeamPos, beamPos);
        GLES20.glUniform3f(uBeamDir, beamDirX, beamDirY, beamTrail);
        GLES20.glUniform3f(uMask, maskStart, maskEnd, maskMin);
        if (glareTex == 0) {
            if (glareBitmap == null) glareBitmap = buildGlareBitmap();
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, glareBitmap, 0);
            glareTex = tex[0];
        }

        // Per-group sweep response: the frame carries the beam, glass is softer, the matte
        // visor slab stays dark, lens glass glints.
        if (bufScreen != null) drawGroup(bufScreen, screenTex, 0.22f, 0.06f, 0.10f, 0.11f, 0.13f, 1.0f);
        if (bufPanel != null) drawGroup(bufPanel, backTex, 0.05f, 0.10f, 0.10f, 0.11f, 0.13f, 0.8f);
        drawGroup(bufMetal, 0, 0.42f, 0.45f, 0.21f, 0.22f, 0.245f, 1.1f);
        // Inward direction: the left buttons sink toward +x, the right toward -x.
        drawButtonGroup(bufVolUp, 1, 1f);
        drawButtonGroup(bufVolDown, 2, 1f);
        drawButtonGroup(bufPower, 3, -1f);
        drawGroup(bufVisor, 0, 0.06f, 0.22f, 0.125f, 0.132f, 0.148f, 0.10f);
        drawGroup(bufLenses, 0, 0.55f, 0.08f, 0.045f, 0.05f, 0.065f, 0.4f);
        drawGroup(bufCores, 0, 0.45f, 0.08f, 0.09f, 0.085f, 0.15f, 0.4f);
        if (flashGlow > 0.01f) {
            // The LED itself fires (3D, on the camera island): warm tint washes out to pure
            // white and the rim term blooms it, like a real LED catching a lens. The LED is
            // exempt from the studio exposure: it is the light source.
            float g = flashGlow;
            drawGroup(bufFlash, 0, 0.35f, 0.08f + 1.4f * g,
                    0.93f + 0.07f * g, 0.90f + 0.10f * g, 0.70f + 0.30f * g, 1.0f, true);
        } else {
            drawGroup(bufFlash, 0, 0.35f, 0.08f, 0.93f, 0.90f, 0.70f, 1.0f);
        }
        drawGroup(bufGlint, 0, 0.30f, 0.05f, 0.28f, 0.32f, 0.42f, 0.4f);
        // Additive, unlit light sources pinned to the model: the beam must not re-light them.
        GLES20.glUniform1f(uBeam, 0f);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);
        // The LED halo: pinned to the LED — exactly on the flash.
        if (flashGlow > 0.01f && bufGlare != null && glareTex != 0) {
            GLES20.glUniform1f(uBoost, 1.15f * flashGlow);
            drawGroup(bufGlare, glareTex, 0f, 0f, 1f, 1f, 1f, 1f, true);
            GLES20.glUniform1f(uBoost, 0f);
        }
        float streak = streakLevel();
        if (streak > 0.01f && bufStreak != null) {
            if (streakTex == 0) streakTex = createStaticTexture(buildStreakBitmap());
            GLES20.glUniform1f(uBoost, 1.5f * streak);
            drawGroup(bufStreak, streakTex, 0f, 0f, 1f, 1f, 1f, 1f, true);
            GLES20.glUniform1f(uBoost, 0f);
        }
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        return true;
    }

    private float streakLevel() {
        float facing = (float) -Math.cos(Math.toRadians(angle));
        float gate = Math.max(0f, Math.min(1f, (facing - 0.6f) / 0.35f));
        return flashGlow * gate;
    }

    private int createStaticTexture(Bitmap bitmap) {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        return tex[0];
    }

    /**
     * The anamorphic streak texture: a hot round core, a thin cool-blue horizontal blade
     * that tapers to nothing at both ends (so no hard quad edge is ever visible).
     */
    private Bitmap buildStreakBitmap() {
        int w = 256;
        int h = 64;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            float v = ((y + 0.5f) / h) * 2f - 1f;
            for (int x = 0; x < w; x++) {
                float u = ((x + 0.5f) / w) * 2f - 1f;
                float au = Math.abs(u);
                float taper = 1f - Math.min(1f, Math.max(0f, (au - 0.55f) / 0.45f));
                taper = taper * taper * (3f - 2f * taper);
                float blade = (float) (Math.exp(-au * 4.2) * Math.exp(-v * v * 95f)) * taper;
                float halo = (float) Math.exp(-(u * u * 38f + v * v * 5.5f));
                float core = (float) Math.exp(-(u * u * 520f + v * v * 34f));
                float a = Math.min(1f, blade * 0.85f + halo * 0.30f + core);
                float whiteMix = Math.min(1f, core * 1.4f + blade * 0.25f);
                int r = Math.round(255f * (0.55f + 0.45f * whiteMix));
                int g = Math.round(255f * (0.74f + 0.26f * whiteMix));
                int b = 255;
                px[y * w + x] = (Math.round(255f * a) << 24) | (r << 16) | (g << 8) | b;
            }
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.setPixels(px, 0, w, 0, 0, w, h);
        return bmp;
    }

    /** Draws a button, sinking it along its axis while pressed. */
    private void drawButtonGroup(FloatBuffer buffer, int id, float dir) {
        if (buffer == null) return;
        if (pressedButton == id && pressProgress > 0.001f) {
            // Extra translation inside the model: the button sinks into the body.
            System.arraycopy(model, 0, pressedMvp, 0, 16);
            Matrix.translateM(pressedMvp, 0, dir * 0.009f * pressProgress, 0f, 0f);
            Matrix.multiplyMM(pressedMvp, 16, viewM, 0, pressedMvp, 0);
            Matrix.multiplyMM(pressedMvp, 16, proj, 0, pressedMvp, 16);
            GLES20.glUniformMatrix4fv(uMVP, 1, false, pressedMvp, 16);
            GLES20.glUniformMatrix4fv(uModel, 1, false, pressedMvp, 0);
            drawGroup(buffer, 0, 0.30f, 0.35f, 0.22f, 0.23f, 0.26f, 1.0f);
            GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
            GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
        } else {
            drawGroup(buffer, 0, 0.30f, 0.35f, 0.20f, 0.21f, 0.235f, 1.0f);
        }
    }

    private void drawGroup(FloatBuffer buffer, int texture, float specular,
                           float cr, float cg, float cb) {
        drawGroup(buffer, texture, specular, 0.05f, cr, cg, cb, 1f, false);
    }

    private void drawGroup(FloatBuffer buffer, int texture, float specular, float rim,
                           float cr, float cg, float cb) {
        drawGroup(buffer, texture, specular, rim, cr, cg, cb, 1f, false);
    }

    private void drawGroup(FloatBuffer buffer, int texture, float specular, float rim,
                           float cr, float cg, float cb, float sweepK) {
        drawGroup(buffer, texture, specular, rim, cr, cg, cb, sweepK, false);
    }

    private void drawGroup(FloatBuffer buffer, int texture, float specular, float rim,
                           float cr, float cg, float cb, float sweepK, boolean emissive) {
        if (buffer == null) return;
        buffer.position(0);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 32, buffer);
        buffer.position(3);
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 32, buffer);
        buffer.position(6);
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 32, buffer);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glEnableVertexAttribArray(aNormal);
        GLES20.glEnableVertexAttribArray(aUV);

        if (texture != 0) {
            GLES20.glUniform1f(uUseTex, 1f);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glUniform1i(uTex, 0);
        } else {
            GLES20.glUniform1f(uUseTex, 0f);
        }
        GLES20.glUniform4f(uColor, cr, cg, cb, 1f);
        GLES20.glUniform1f(uSpec, specular);
        GLES20.glUniform1f(uRim, rim);
        GLES20.glUniform1f(uSweepK, sweepK);
        // Emissive parts (the flash LED) ignore the studio exposure: they ARE the light.
        GLES20.glUniform1f(uAmbient, emissive ? 1f : ambient);
        GLES20.glUniform1f(uFlashK, emissive ? 0f : flashK);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, buffer.capacity() / 8);
        GLES20.glUniform1f(uAmbient, ambient);
        GLES20.glUniform1f(uFlashK, flashK);
    }

    private int uploadBitmap(Bitmap bitmap, int existing, int[] size) {
        if (bitmap == null) return existing;
        if (existing == 0) {
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            size[0] = bitmap.getWidth();
            size[1] = bitmap.getHeight();
            return tex[0];
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, existing);
        if (size[0] != bitmap.getWidth() || size[1] != bitmap.getHeight()) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            size[0] = bitmap.getWidth();
            size[1] = bitmap.getHeight();
        } else {
            // Same allocation: update in place. Reallocating every frame can visibly
            // "pop" the whole face on some drivers.
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap);
        }
        return existing;
    }

    // ---------------------------------------------------------------- program

    private void buildProgram() {
        int vs = compile(GLES20.GL_VERTEX_SHADER,
                "attribute vec3 aPos;\n" +
                "attribute vec3 aNormal;\n" +
                "attribute vec2 aUV;\n" +
                "uniform mat4 uMVP;\n" +
                "uniform mat4 uModel;\n" +
                "varying vec3 vNormal;\n" +
                "varying vec2 vUV;\n" +
                "varying vec3 vWorld;\n" +
                "void main() {\n" +
                "  vUV = aUV;\n" +
                "  vNormal = mat3(uModel) * aNormal;\n" +
                "  vWorld = (uModel * vec4(aPos, 1.0)).xyz;\n" +
                "  gl_Position = uMVP * vec4(aPos, 1.0);\n" +
                "}\n");
        int fs = compile(GLES20.GL_FRAGMENT_SHADER,
                "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
                "precision highp float;\n" +
                "#else\n" +
                "precision mediump float;\n" +
                "#endif\n" +
                "varying vec3 vNormal;\n" +
                "varying vec2 vUV;\n" +
                "varying vec3 vWorld;\n" +
                "uniform sampler2D uTex;\n" +
                "uniform float uUseTex;\n" +
                "uniform vec4 uColor;\n" +
                "uniform float uSpec;\n" +
                "uniform float uRim;\n" +
                "uniform vec3 uLightDir;\n" +
                "uniform float uFade;\n" +
                "uniform float uSweep;\n" +
                "uniform float uSweepX;\n" +
                "uniform float uSweepK;\n" +
                "uniform float uAmbient;\n" +
                "uniform float uBoost;\n" +
                "uniform vec4 uView;\n" +
                "uniform float uBeam;\n" +
                "uniform float uBeamBlur;\n" +
                "uniform float uBeamPos;\n" +
                "uniform vec3 uBeamDir;\n" +
                "uniform vec3 uMask;\n" +
                "uniform vec4 uFlashPos;\n" +
                "uniform float uFlashK;\n" +
                "void main() {\n" +
                "  vec3 n = normalize(vNormal);\n" +
                "  vec3 l = normalize(uLightDir);\n" +
                "  float diff = max(dot(n, l), 0.0);\n" +
                "  vec4 tex = texture2D(uTex, vUV);\n" +
                "  vec3 base = mix(uColor.rgb, tex.rgb, uUseTex);\n" +
                "  float alpha = mix(1.0, tex.a, uUseTex);\n" +
                "  vec3 viewDir = vec3(0.0, 0.0, 1.0);\n" +
                "  vec3 h = normalize(l + viewDir);\n" +
                "  float spec = pow(max(dot(n, h), 0.0), 48.0) * uSpec;\n" +
                "  float rim = pow(1.0 - abs(n.z), 3.0) * uRim;\n" +
                // uAmbient is the studio exposure: in the dark reveal only the traveling
                // beam is visible; the object emerges as the exposure rises.
                "  vec3 color = (base * (0.44 + 0.66 * diff) + vec3(spec + rim)) * uAmbient;\n" +
                // A strip light glides past the product. The response is a soft specular
                // lobe, so ONE coherent highlight slides across the real surfaces instead
                // of parts blinking independently. uSweepK tunes each material: the frame
                // catches it, the matte visor slab stays dark, the lens glass glints.
                "  vec3 slPos = vec3(uSweepX, 1.05, 2.30);\n" +
                "  vec3 slL = normalize(slPos - vWorld);\n" +
                "  vec3 slH = normalize(slL + viewDir);\n" +
                "  float strip = pow(max(dot(n, slH), 0.0), 8.0);\n" +
                "  float band = strip * (0.25 + uSpec * 1.2) * uSweep * uSweepK;\n" +
                "  color += vec3(band * 0.88, band * 0.87, band * 0.84);\n" +
                // The product-film beam: a screen-space light front racing across the scene.
                // A razor-sharp leading edge with a hot core and a long soft trail. It is
                // geometry-aware: chamfers, side walls and lens barrels (normals turned away
                // from the viewer) catch it hardest, flat glass only mirrors a faint sheet.
                // uView = (w, h, translationX, translationY): the beam and the dark layer live
                // in the HOST's space, while this surface is translated inside it.
                "  vec2 bp = (gl_FragCoord.xy - 0.5 * uView.xy + vec2(uView.z, -uView.w)) / uView.y;\n" +
                "  float bd = dot(bp, uBeamDir.xy) - uBeamPos;\n" +
                "  float trail = exp(min(bd, 0.0) / uBeamDir.z) * (1.0 - smoothstep(0.0, 0.012, bd));\n" +
                "  float core = exp(-bd * bd / (0.00012 * (1.0 + 9.0 * uBeamBlur)));\n" +
                "  float edgeK = 1.0 - smoothstep(0.45, 0.98, abs(n.z));\n" +
                "  float resp = 0.55 + uSpec * 1.5;\n" +
                "  float beamLight = uBeam * resp * (trail * (0.10 + 0.55 * edgeK) + core * (0.45 + 1.10 * edgeK) * (1.0 + 1.2 * uBeamBlur));\n" +
                // Unlit halo: the LED glare is a light source, not a shaded surface.
                "  color += vec3(uBoost) * tex.a;\n" +
                // The dark layer: a smooth vertical gradient — the top of the scene is lit,
                // the bottom sinks into black.
                "  float yTop = (uView.y - gl_FragCoord.y + uView.w) / uView.y;\n" +
                "  float lit = mix(1.0, uMask.z, smoothstep(uMask.x, uMask.y, yTop));\n" +
                "  color *= lit;\n" +
                "  vec3 fv = uFlashPos.xyz - vWorld;\n" +
                "  float fLen = max(length(fv), 1e-5);\n" +
                "  vec3 fl = fv / fLen;\n" +
                "  float fd = fLen / uFlashPos.w;\n" +
                "  float fNdl = max(dot(n, fl), 0.0);\n" +
                "  float fFall = 1.0 / (1.0 + 11.0 * fd * fd);\n" +
                "  vec3 fh = normalize(fl + viewDir);\n" +
                "  float fSpec = pow(max(dot(n, fh), 0.0), 60.0) * (0.10 + uSpec * 2.4);\n" +
                "  vec3 flashLit = (base * fNdl + vec3(fSpec)) * fFall * uFlashK;\n" +
                "  color += flashLit * vec3(1.0, 0.95, 0.84);\n" +
                // The beam is added AFTER the dark layer and only partly dimmed by it: it is
                // the light that cuts through the dark, so it still glints on the lower body.
                "  color += vec3(0.93, 0.96, 1.0) * beamLight * (0.40 + 0.60 * lit);\n" +
                "  float fade = uFade > 0.5 ? smoothstep(0.0, uFade, gl_FragCoord.y) : 1.0;\n" +
                "  gl_FragColor = vec4(color, alpha * fade);\n" +
                "}\n");
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aNormal = GLES20.glGetAttribLocation(program, "aNormal");
        aUV = GLES20.glGetAttribLocation(program, "aUV");
        uMVP = GLES20.glGetUniformLocation(program, "uMVP");
        uModel = GLES20.glGetUniformLocation(program, "uModel");
        uTex = GLES20.glGetUniformLocation(program, "uTex");
        uUseTex = GLES20.glGetUniformLocation(program, "uUseTex");
        uColor = GLES20.glGetUniformLocation(program, "uColor");
        uSpec = GLES20.glGetUniformLocation(program, "uSpec");
        uLightDir = GLES20.glGetUniformLocation(program, "uLightDir");
        uFade = GLES20.glGetUniformLocation(program, "uFade");
        uRim = GLES20.glGetUniformLocation(program, "uRim");
        uSweep = GLES20.glGetUniformLocation(program, "uSweep");
        uSweepX = GLES20.glGetUniformLocation(program, "uSweepX");
        uSweepK = GLES20.glGetUniformLocation(program, "uSweepK");
        uAmbient = GLES20.glGetUniformLocation(program, "uAmbient");
        uBoost = GLES20.glGetUniformLocation(program, "uBoost");
        uView = GLES20.glGetUniformLocation(program, "uView");
        uBeam = GLES20.glGetUniformLocation(program, "uBeam");
        uBeamBlur = GLES20.glGetUniformLocation(program, "uBeamBlur");
        uBeamPos = GLES20.glGetUniformLocation(program, "uBeamPos");
        uBeamDir = GLES20.glGetUniformLocation(program, "uBeamDir");
        uMask = GLES20.glGetUniformLocation(program, "uMask");
        uFlashPos = GLES20.glGetUniformLocation(program, "uFlashPos");
        uFlashK = GLES20.glGetUniformLocation(program, "uFlashK");
    }

    /** The LED halo texture: soft warm core with faint star rays. */
    private Bitmap buildGlareBitmap() {
        int size = 128;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cx = size / 2f;
        float cy = size / 2f;
        p.setShader(new RadialGradient(cx, cy, size * 0.5f,
                new int[]{0xFFFFFFFF, 0xCCFFF6E0, 0x33FFF6E0, 0x00FFF6E0},
                new float[]{0f, 0.16f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, size * 0.5f, p);
        p.setShader(null);
        for (int k = 0; k < 6; k++) {
            float ang = k * 60f + 12f;
            float len = size * 0.5f * (k % 2 == 0 ? 1f : 0.6f);
            c.save();
            c.rotate(ang, cx, cy);
            p.setShader(new LinearGradient(cx, cy, cx + len, cy,
                    new int[]{0x55FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            Path ray = new Path();
            ray.moveTo(cx, cy - size * 0.03f);
            ray.lineTo(cx + len, cy);
            ray.lineTo(cx, cy + size * 0.03f);
            ray.close();
            c.drawPath(ray, p);
            c.restore();
        }
        return bmp;
    }

    private int compile(int type, String src) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, src);
        GLES20.glCompileShader(shader);
        return shader;
    }

    // ------------------------------------------------------------------- mesh

    private void buildBuffers() {
        float hw = 0.5f;
        float hh = PHONE_ASPECT / 2f;
        float hd = DEPTH_RATIO / 2f;

        List<Float> metal = new ArrayList<>();

        // The whole chassis is ONE material: a beveled frame ring (front and back chamfers)
        // plus the side wall. The front/back textures only carry content.
        chassis(metal, hw, hh, hd, CORNER_RADIUS, FRAME_INSET, CHAMFER, 30);

        // Volume rocker (left), power (right): rounded slabs, never square.
        float bz = DEPTH_RATIO * 0.45f;
        List<Float> bUp = new ArrayList<>();
        List<Float> bDown = new ArrayList<>();
        List<Float> bPower = new ArrayList<>();
        buttonSlab(bUp, -hw, -hw - BTN_PROTRUSION, hh - VOL_UP_CENTER * PHONE_ASPECT,
                VOL_HEIGHT * PHONE_ASPECT, bz, -1);
        buttonSlab(bDown, -hw, -hw - BTN_PROTRUSION, hh - VOL_DOWN_CENTER * PHONE_ASPECT,
                VOL_HEIGHT * PHONE_ASPECT, bz, -1);
        buttonSlab(bPower, hw, hw + BTN_PROTRUSION, hh - POWER_CENTER * PHONE_ASPECT,
                POWER_HEIGHT * PHONE_ASPECT, bz, 1);
        bufVolUp = toBuffer(bUp);
        bufVolDown = toBuffer(bDown);
        bufPower = toBuffer(bPower);

        // Camera island in real 3D: a rounded titanium slab with protruding lens barrels,
        // a flash and a LiDAR sensor — lit and shaded like the rest of the device.
        List<Float> visor = new ArrayList<>();
        List<Float> lenses = new ArrayList<>();
        float vx0 = -hw + VISOR_INSET;
        float vx1 = hw - VISOR_INSET;
        float vy1 = hh - VISOR_TOP;
        float vy0 = vy1 - VISOR_HEIGHT;
        float zBack = -hd - VISOR_DEPTH;
        visorSlab(visor, zBack, -hd, vx0, vx1, vy0, vy1, VISOR_HEIGHT * 0.42f);
        float barH = VISOR_HEIGHT;
        float midY = (vy0 + vy1) / 2f;
        List<Float> flash = new ArrayList<>();
        // Viewed from the back, the model's +x is the viewer's left: the two lenses sit close
        // together there, the flash and LiDAR on the right — like the painted design.
        List<Float> glint = new ArrayList<>();
        List<Float> cores = new ArrayList<>();
        for (float lensX : new float[]{vx1 - barH * 0.80f, vx1 - barH * 1.46f}) {
            cylinder(visor, lensX, midY, zBack, zBack - 0.007f, barH * 0.28f, 44);
            cylinder(lenses, lensX, midY, zBack - 0.007f, zBack - 0.011f, barH * 0.21f, 44);
            cylinder(cores, lensX, midY, zBack - 0.011f, zBack - 0.013f, barH * 0.13f, 36);
            cylinder(glint, lensX - barH * 0.055f, midY + barH * 0.055f, zBack - 0.013f, zBack - 0.0145f,
                    barH * 0.030f, 20);
        }
        float ledX = LED_X;
        cylinder(flash, ledX, midY, zBack, zBack - 0.007f, barH * 0.125f, 28);
        // The LED halo: a glow quad pinned to the LED itself, so it rotates, scales and
        // projects exactly with the camera island — never a floating overlay.
        float gh = 0.085f;
        float gz = zBack - 0.008f;
        List<Float> glare = new ArrayList<>();
        vertex(glare, ledX - gh, midY + gh, gz, 0f, 0f, -1f, 0f, 0f);
        vertex(glare, ledX + gh, midY + gh, gz, 0f, 0f, -1f, 1f, 0f);
        vertex(glare, ledX + gh, midY - gh, gz, 0f, 0f, -1f, 1f, 1f);
        vertex(glare, ledX - gh, midY + gh, gz, 0f, 0f, -1f, 0f, 0f);
        vertex(glare, ledX + gh, midY - gh, gz, 0f, 0f, -1f, 1f, 1f);
        vertex(glare, ledX - gh, midY - gh, gz, 0f, 0f, -1f, 0f, 1f);
        bufGlare = toBuffer(glare);
        ledY = midY;
        ledZ = zBack - 0.012f - 0.16f;
        streakX = LED_X;
        streakY = midY;
        streakZ = zBack - 0.016f;
        float sw = 0.60f;
        float sh = 0.040f;
        List<Float> streak = new ArrayList<>();
        vertex(streak, streakX - sw, streakY + sh, streakZ, 0f, 0f, -1f, 0f, 0f);
        vertex(streak, streakX + sw, streakY + sh, streakZ, 0f, 0f, -1f, 1f, 0f);
        vertex(streak, streakX + sw, streakY - sh, streakZ, 0f, 0f, -1f, 1f, 1f);
        vertex(streak, streakX - sw, streakY + sh, streakZ, 0f, 0f, -1f, 0f, 0f);
        vertex(streak, streakX + sw, streakY - sh, streakZ, 0f, 0f, -1f, 1f, 1f);
        vertex(streak, streakX - sw, streakY - sh, streakZ, 0f, 0f, -1f, 0f, 1f);
        bufStreak = toBuffer(streak);
        cylinder(lenses, vx0 + barH * 1.22f, midY, zBack, zBack - 0.005f, barH * 0.10f, 28);
        // Front camera: a small reflective lens seated in the punch-hole, shaded like the rear.
        float camY = PHONE_ASPECT / 2f - 0.117f;
        cylinder(visor, 0f, camY, hd, hd + 0.0038f, 0.030f, 36);
        cylinder(lenses, 0f, camY, hd + 0.0038f, hd + 0.0056f, 0.023f, 36);
        cylinder(cores, 0f, camY, hd + 0.0056f, hd + 0.0070f, 0.014f, 28);
        cylinder(glint, -0.0075f, camY + 0.0075f, hd + 0.0070f, hd + 0.0080f, 0.0042f, 16);
        bufVisor = toBuffer(visor);
        bufLenses = toBuffer(lenses);
        bufFlash = toBuffer(flash);
        bufGlint = toBuffer(glint);
        bufCores = toBuffer(cores);

        bufMetal = toBuffer(metal);
    }

    /** Beveled frame: front chamfer, side wall, back chamfer — all one metal material. */
    private void chassis(List<Float> out, float hw, float hh, float hd,
                         float r, float f, float ch, int seg) {
        List<float[]> outer = roundedRect(-hw, hw, -hh, hh, r, seg);
        List<float[]> inner = roundedRect(-hw + f, hw - f, -hh + f, hh - f, Math.max(0.01f, r - f), seg);
        float cz = (float) (1.0 / Math.sqrt(2.0));
        int n = Math.min(outer.size(), inner.size());
        for (int i = 0; i < n - 1; i++) {
            float[] o0 = outer.get(i);
            float[] o1 = outer.get(i + 1);
            float[] i0 = inner.get(i);
            float[] i1 = inner.get(i + 1);
            float dx = o1[0] - o0[0];
            float dy = o1[1] - o0[1];
            float len = (float) Math.hypot(dx, dy);
            if (len < 1e-5f) continue;
            float nx = dy / len;
            float ny = -dx / len;
            // Front chamfer: glass edge (z=+hd) slopes out/down to the rail (z=+hd-ch).
            vertex(out, i0[0], i0[1], hd, nx * cz, ny * cz, cz, 0f, 0f);
            vertex(out, i1[0], i1[1], hd, nx * cz, ny * cz, cz, 0f, 0f);
            vertex(out, o1[0], o1[1], hd - ch, nx * cz, ny * cz, cz, 0f, 0f);
            vertex(out, i0[0], i0[1], hd, nx * cz, ny * cz, cz, 0f, 0f);
            vertex(out, o1[0], o1[1], hd - ch, nx * cz, ny * cz, cz, 0f, 0f);
            vertex(out, o0[0], o0[1], hd - ch, nx * cz, ny * cz, cz, 0f, 0f);
            // Side wall.
            vertex(out, o0[0], o0[1], hd - ch, nx, ny, 0f, 0f, 0f);
            vertex(out, o1[0], o1[1], hd - ch, nx, ny, 0f, 0f, 0f);
            vertex(out, o1[0], o1[1], -hd + ch, nx, ny, 0f, 0f, 0f);
            vertex(out, o0[0], o0[1], hd - ch, nx, ny, 0f, 0f, 0f);
            vertex(out, o1[0], o1[1], -hd + ch, nx, ny, 0f, 0f, 0f);
            vertex(out, o0[0], o0[1], -hd + ch, nx, ny, 0f, 0f, 0f);
            // Back chamfer.
            vertex(out, o0[0], o0[1], -hd + ch, nx * cz, ny * cz, -cz, 0f, 0f);
            vertex(out, o1[0], o1[1], -hd + ch, nx * cz, ny * cz, -cz, 0f, 0f);
            vertex(out, i1[0], i1[1], -hd, nx * cz, ny * cz, -cz, 0f, 0f);
            vertex(out, o0[0], o0[1], -hd + ch, nx * cz, ny * cz, -cz, 0f, 0f);
            vertex(out, i1[0], i1[1], -hd, nx * cz, ny * cz, -cz, 0f, 0f);
            vertex(out, i0[0], i0[1], -hd, nx * cz, ny * cz, -cz, 0f, 0f);
        }
    }

    /** Builds the side wall as a strip along the rounded-rectangle silhouette. */
    private void roundedWall(List<Float> out, float hw, float hh, float hd, float r, int seg) {
        List<float[]> pts = new ArrayList<>();
        float cx = hw - r;
        float cy = hh - r;
        pts.add(new float[]{hw, -cy});
        pts.add(new float[]{hw, cy});
        for (int i = 1; i <= seg; i++) {
            double a = i * (Math.PI / 2) / seg;
            pts.add(new float[]{(float) (cx + r * Math.cos(a)), (float) (cy + r * Math.sin(a))});
        }
        pts.add(new float[]{-cx, hh});
        for (int i = 1; i <= seg; i++) {
            double a = Math.PI / 2 + i * (Math.PI / 2) / seg;
            pts.add(new float[]{(float) (-cx + r * Math.cos(a)), (float) (cy + r * Math.sin(a))});
        }
        pts.add(new float[]{-hw, -cy});
        for (int i = 1; i <= seg; i++) {
            double a = Math.PI + i * (Math.PI / 2) / seg;
            pts.add(new float[]{(float) (-cx + r * Math.cos(a)), (float) (-cy + r * Math.sin(a))});
        }
        pts.add(new float[]{cx, -hh});
        for (int i = 1; i <= seg; i++) {
            double a = 3 * Math.PI / 2 + i * (Math.PI / 2) / seg;
            pts.add(new float[]{(float) (cx + r * Math.cos(a)), (float) (-cy + r * Math.sin(a))});
        }
        pts.add(new float[]{hw, -cy});

        for (int i = 0; i < pts.size() - 1; i++) {
            float[] a = pts.get(i);
            float[] b = pts.get(i + 1);
            float dx = b[0] - a[0];
            float dy = b[1] - a[1];
            float len = (float) Math.hypot(dx, dy);
            if (len < 1e-5f) continue;
            float nx = dy / len;
            float ny = -dx / len;
            vertex(out, a[0], a[1], hd, nx, ny, 0f, 0f, 0f);
            vertex(out, b[0], b[1], hd, nx, ny, 0f, 1f, 0f);
            vertex(out, b[0], b[1], -hd, nx, ny, 0f, 1f, 1f);
            vertex(out, a[0], a[1], hd, nx, ny, 0f, 0f, 0f);
            vertex(out, b[0], b[1], -hd, nx, ny, 0f, 1f, 1f);
            vertex(out, a[0], a[1], -hd, nx, ny, 0f, 0f, 1f);
        }
    }

    /** A rounded button: a rounded-rect cross-section extruded along x. */
    private void buttonSlab(List<Float> out, float xIn, float xOut, float cy,
                            float sy, float sz, int side) {
        float r = Math.min(sy, sz) * 0.35f;
        List<float[]> pts = roundedRect(cy - sy / 2f, cy + sy / 2f, -sz / 2f, sz / 2f, r, 14);
        // Wall.
        for (int i = 0; i < pts.size() - 1; i++) {
            float[] a = pts.get(i);
            float[] b = pts.get(i + 1);
            float dy = b[0] - a[0];
            float dz = b[1] - a[1];
            float len = (float) Math.hypot(dy, dz);
            if (len < 1e-5f) continue;
            float ny = dz / len;
            float nz = -dy / len;
            vertex(out, xIn, a[0], a[1], 0f, ny, nz, 0f, 0f);
            vertex(out, xOut, a[0], a[1], 0f, ny, nz, 0f, 0f);
            vertex(out, xOut, b[0], b[1], 0f, ny, nz, 0f, 0f);
            vertex(out, xIn, a[0], a[1], 0f, ny, nz, 0f, 0f);
            vertex(out, xOut, b[0], b[1], 0f, ny, nz, 0f, 0f);
            vertex(out, xIn, b[0], b[1], 0f, ny, nz, 0f, 0f);
        }
        // Outer cap (the inner cap is buried in the body).
        fan(out, pts, xOut, side, 0f, 0f, side);
    }

    /** The camera island: a rounded-rect cross-section in (x,y) extruded toward -z. */
    private void visorSlab(List<Float> out, float zFront, float zBack,
                           float x0, float x1, float y0, float y1, float r) {
        List<float[]> pts = roundedRect(x0, x1, y0, y1, r, 14);
        for (int i = 0; i < pts.size() - 1; i++) {
            float[] a = pts.get(i);
            float[] b = pts.get(i + 1);
            float dx = b[0] - a[0];
            float dy = b[1] - a[1];
            float len = (float) Math.hypot(dx, dy);
            if (len < 1e-5f) continue;
            float nx = dy / len;
            float ny = -dx / len;
            vertex(out, a[0], a[1], zBack, nx, ny, 0f, 0f, 0f);
            vertex(out, b[0], b[1], zBack, nx, ny, 0f, 0f, 0f);
            vertex(out, b[0], b[1], zFront, nx, ny, 0f, 0f, 0f);
            vertex(out, a[0], a[1], zBack, nx, ny, 0f, 0f, 0f);
            vertex(out, b[0], b[1], zFront, nx, ny, 0f, 0f, 0f);
            vertex(out, a[0], a[1], zFront, nx, ny, 0f, 0f, 0f);
        }
        // The outward face.
        for (int i = 0; i < pts.size() - 1; i++) {
            float[] a = pts.get(i);
            float[] b = pts.get(i + 1);
            vertex(out, a[0], a[1], zFront, 0f, 0f, -1f, 0f, 0f);
            vertex(out, b[0], b[1], zFront, 0f, 0f, -1f, 0f, 0f);
            vertex(out, (x0 + x1) / 2f, (y0 + y1) / 2f, zFront, 0f, 0f, -1f, 0f, 0f);
        }
    }

    /** A lens barrel: a cylinder along z with a visible cap. */
    private void cylinder(List<Float> out, float cx, float cy, float z0, float z1, float r, int seg) {
        for (int i = 0; i < seg; i++) {
            double a0 = 2 * Math.PI * i / seg;
            double a1 = 2 * Math.PI * (i + 1) / seg;
            float x0 = (float) (cx + r * Math.cos(a0));
            float y0 = (float) (cy + r * Math.sin(a0));
            float x1 = (float) (cx + r * Math.cos(a1));
            float y1 = (float) (cy + r * Math.sin(a1));
            float nx0 = (float) Math.cos(a0);
            float ny0 = (float) Math.sin(a0);
            float nx1 = (float) Math.cos(a1);
            float ny1 = (float) Math.sin(a1);
            vertex(out, x0, y0, z0, nx0, ny0, 0f, 0f, 0f);
            vertex(out, x1, y1, z0, nx1, ny1, 0f, 0f, 0f);
            vertex(out, x1, y1, z1, nx1, ny1, 0f, 0f, 0f);
            vertex(out, x0, y0, z0, nx0, ny0, 0f, 0f, 0f);
            vertex(out, x1, y1, z1, nx1, ny1, 0f, 0f, 0f);
            vertex(out, x0, y0, z1, nx0, ny0, 0f, 0f, 0f);
            // Cap.
            vertex(out, x0, y0, z1, 0f, 0f, -1f, 0f, 0f);
            vertex(out, x1, y1, z1, 0f, 0f, -1f, 0f, 0f);
            vertex(out, cx, cy, z1, 0f, 0f, -1f, 0f, 0f);
        }
    }

    /** Perimeter of a rounded rectangle in (u,v), closed: edges plus all four corner arcs. */
    private List<float[]> roundedRect(float u0, float u1, float v0, float v1, float r, int seg) {
        List<float[]> pts = new ArrayList<>();
        float cu0 = u0 + r, cu1 = u1 - r, cv0 = v0 + r, cv1 = v1 - r;
        pts.add(new float[]{u1, cv0});          // right edge, bottom
        pts.add(new float[]{u1, cv1});          // right edge, top
        for (int i = 1; i <= seg; i++) {        // top-right arc, 0..90
            double a = i * (Math.PI / 2) / seg;
            pts.add(new float[]{(float) (cu1 + r * Math.cos(a)), (float) (cv1 + r * Math.sin(a))});
        }
        pts.add(new float[]{cu0, v1});          // top edge, left end
        for (int i = 1; i <= seg; i++) {        // top-left arc, 90..180
            double a = Math.PI / 2 + i * (Math.PI / 2) / seg;
            pts.add(new float[]{(float) (cu0 + r * Math.cos(a)), (float) (cv1 + r * Math.sin(a))});
        }
        pts.add(new float[]{u0, cv0});          // left edge, bottom end
        for (int i = 1; i <= seg; i++) {        // bottom-left arc, 180..270
            double a = Math.PI + i * (Math.PI / 2) / seg;
            pts.add(new float[]{(float) (cu0 + r * Math.cos(a)), (float) (cv0 + r * Math.sin(a))});
        }
        pts.add(new float[]{cu1, v0});          // bottom edge, right end
        for (int i = 1; i <= seg; i++) {        // bottom-right arc, 270..360 (this was missing)
            double a = 3 * Math.PI / 2 + i * (Math.PI / 2) / seg;
            pts.add(new float[]{(float) (cu1 + r * Math.cos(a)), (float) (cv0 + r * Math.sin(a))});
        }
        pts.add(new float[]{u1, cv0});          // close
        return pts;
    }

    /** A triangle fan closing a rounded-rect perimeter at a fixed coordinate. */
    private void fan(List<Float> out, List<float[]> pts, float fixed, int side, float a, float b, int unused) {
        float cu = 0f, cv = 0f;
        for (float[] p : pts) {
            cu += p[0];
            cv += p[1];
        }
        cu /= pts.size();
        cv /= pts.size();
        for (int i = 0; i < pts.size() - 1; i++) {
            float[] p = pts.get(i);
            float[] q = pts.get(i + 1);
            vertex(out, fixed, p[0], p[1], side, 0f, 0f, 0f, 0f);
            vertex(out, fixed, q[0], q[1], side, 0f, 0f, 0f, 0f);
            vertex(out, fixed, cu, cv, side, 0f, 0f, 0f, 0f);
        }
    }

    private void quad(List<Float> out,
                      float x0, float y0, float z0, float x1, float y1, float z1,
                      float x2, float y2, float z2, float x3, float y3, float z3,
                      float nx, float ny, float nz,
                      float u0, float v0, float u1, float v1, float u2, float v2, float u3, float v3) {
        vertex(out, x0, y0, z0, nx, ny, nz, u0, v0);
        vertex(out, x1, y1, z1, nx, ny, nz, u1, v1);
        vertex(out, x2, y2, z2, nx, ny, nz, u2, v2);
        vertex(out, x0, y0, z0, nx, ny, nz, u0, v0);
        vertex(out, x2, y2, z2, nx, ny, nz, u2, v2);
        vertex(out, x3, y3, z3, nx, ny, nz, u3, v3);
    }

    /** A small box; face = -1 draws only its outward -x face plate (buttons are slabs). */
    private void box(List<Float> out, float cx, float cy, float cz,
                     float sx, float sy, float sz, int face) {
        float x0 = cx - sx / 2f, x1 = cx + sx / 2f;
        float y0 = cy - sy / 2f, y1 = cy + sy / 2f;
        float z0 = cz - sz / 2f, z1 = cz + sz / 2f;
        quad(out, x1, y1, z1, x0, y1, z1, x0, y0, z1, x1, y0, z1, 0, 0, 1, 0, 0, 1, 0, 1, 1, 0, 1);
        quad(out, x0, y1, z0, x1, y1, z0, x1, y0, z0, x0, y0, z0, 0, 0, -1, 0, 0, 1, 0, 1, 1, 0, 1);
        quad(out, x1, y1, z0, x1, y1, z1, x1, y0, z1, x1, y0, z0, 1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1);
        quad(out, x0, y1, z1, x0, y1, z0, x0, y0, z0, x0, y0, z1, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1);
        quad(out, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 0, 1);
        quad(out, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0, 0, 0, 1, 0, 1, 1, 0, 1);
    }

    private void vertex(List<Float> out, float x, float y, float z,
                        float nx, float ny, float nz, float u, float v) {
        out.add(x); out.add(y); out.add(z);
        out.add(nx); out.add(ny); out.add(nz);
        out.add(u); out.add(v);
    }

    private FloatBuffer toBuffer(List<Float> list) {
        ByteBuffer bb = ByteBuffer.allocateDirect(list.size() * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        for (float f : list) fb.put(f);
        fb.position(0);
        return fb;
    }
}
