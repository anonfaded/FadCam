package com.fadcam.ui.faditor.components;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.processors.opengl.VideoRenderer;
import com.fadcam.ui.faditor.processors.opengl.VideoTexture;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * OpenGL-accelerated video player component for fast preview rendering.
 * Uses OpenGL ES for hardware-accelerated video playback and rendering.
 */
public class OpenGLVideoPlayerComponent extends FrameLayout implements GLSurfaceView.Renderer {

    private static final String TAG = "OpenGLVideoPlayer";

    public interface OpenGLVideoPlayerListener {
        void onPlaybackStateChanged(boolean isPlaying);
        void onPositionChanged(long positionMs);
        void onVideoLoaded(long durationMs);
        void onVideoError(String error);
        void onFrameRendered();
    }

    private OpenGLVideoPlayerListener listener;
    private GLSurfaceView glSurfaceView;
    private MediaPlayer mediaPlayer;
    private SurfaceTexture surfaceTexture;
    private VideoRenderer videoRenderer;
    private VideoTexture videoTexture;

    private Handler mainHandler;
    private Runnable positionUpdateRunnable;
    private boolean isInitialized = false;
    private boolean pendingPlaybackStart = false;
    private boolean surfaceTextureReady = false;
    private boolean isPlaying = false;
    private long videoDuration = 0;

    // OpenGL matrices
    private final float[] mvpMatrix = new float[16];
    private final float[] projectionMatrix = new float[16];
    private final float[] viewMatrix = new float[16];
    private final float[] modelMatrix = new float[16];

    // Vertex shader
    private static final String VERTEX_SHADER =
        "uniform mat4 uMVPMatrix;" +
        "attribute vec4 aPosition;" +
        "attribute vec2 aTexCoord;" +
        "varying vec2 vTexCoord;" +
        "void main() {" +
        "  gl_Position = uMVPMatrix * aPosition;" +
        "  vTexCoord = aTexCoord;" +
        "}";

    // Fragment shader
    private static final String FRAGMENT_SHADER =
        "precision mediump float;" +
        "uniform sampler2D uTexture;" +
        "varying vec2 vTexCoord;" +
        "void main() {" +
        "  gl_FragColor = texture2D(uTexture, vTexCoord);" +
        "}";

    // Quad vertices
    private static final float[] QUAD_VERTICES = {
        -1.0f,  1.0f, 0.0f,   // top left
         1.0f,  1.0f, 0.0f,   // top right
        -1.0f, -1.0f, 0.0f,   // bottom left
         1.0f, -1.0f, 0.0f    // bottom right
    };

    // Texture coordinates
    private static final float[] TEXTURE_COORDS = {
        0.0f, 0.0f,   // top left
        1.0f, 0.0f,   // top right
        0.0f, 1.0f,   // bottom left
        1.0f, 1.0f    // bottom right
    };

    private FloatBuffer vertexBuffer;
    private FloatBuffer textureBuffer;
    private int program;
    private int positionHandle;
    private int texCoordHandle;
    private int mvpMatrixHandle;
    private int textureHandle;

    public OpenGLVideoPlayerComponent(@NonNull Context context) {
        super(context);
        init();
    }

    public OpenGLVideoPlayerComponent(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        Log.d(TAG, "Initializing OpenGL Video Player Component");

        // Initialize buffers
        vertexBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        vertexBuffer.put(QUAD_VERTICES);
        vertexBuffer.position(0);

        textureBuffer = ByteBuffer.allocateDirect(TEXTURE_COORDS.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        textureBuffer.put(TEXTURE_COORDS);
        textureBuffer.position(0);

        // Initialize matrices
        Matrix.setIdentityM(modelMatrix, 0);
        Matrix.setIdentityM(viewMatrix, 0);
        Matrix.setIdentityM(projectionMatrix, 0);
        Matrix.setIdentityM(mvpMatrix, 0);

        // Create GLSurfaceView
        glSurfaceView = new GLSurfaceView(getContext());
        glSurfaceView.setEGLContextClientVersion(2);
        glSurfaceView.setRenderer(this);
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        // Add to layout
        addView(glSurfaceView);

        // Initialize MediaPlayer
        mediaPlayer = new MediaPlayer();
        setupMediaPlayer();

        mainHandler = new Handler(Looper.getMainLooper());
        positionUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    if (listener != null) {
                        listener.onPositionChanged(mediaPlayer.getCurrentPosition());
                    }
                    mainHandler.postDelayed(this, 100);
                }
            }
        };

        Log.d(TAG, "OpenGL Video Player Component initialized");
    }

    private void setupMediaPlayer() {
        mediaPlayer.setOnPreparedListener(mp -> {
            Log.d(TAG, "MediaPlayer prepared, duration: " + mp.getDuration() + "ms");
            videoDuration = mp.getDuration();
            isInitialized = true;

            if (listener != null) {
                listener.onVideoLoaded(videoDuration);
            }
        });

        mediaPlayer.setOnCompletionListener(mp -> {
            Log.d(TAG, "MediaPlayer playback completed");
            isPlaying = false;
            if (listener != null) {
                listener.onPlaybackStateChanged(false);
            }
        });

        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            Log.e(TAG, "MediaPlayer error: what=" + what + ", extra=" + extra);

            // Reset player state on error
            isInitialized = false;
            isPlaying = false;

            // Try to reset the MediaPlayer
            try {
                if (mp != null) {
                    mp.reset();
                }
            } catch (Exception e) {
                Log.w(TAG, "Error resetting MediaPlayer after error", e);
            }

            if (listener != null) {
                String errorMsg = getMediaPlayerErrorString(what, extra);
                listener.onVideoError("MediaPlayer error: " + errorMsg);
            }
            return true; // We've handled the error
        });

        mediaPlayer.setOnVideoSizeChangedListener((mp, width, height) -> {
            Log.d(TAG, "Video size changed: " + width + "x" + height);
            glSurfaceView.requestRender();
        });

        mediaPlayer.setOnSeekCompleteListener(mp -> {
            Log.d(TAG, "Seek completed");
            if (listener != null) {
                listener.onPositionChanged(mp.getCurrentPosition());
            }
        });
    }

    /**
     * Get human-readable error message for MediaPlayer errors
     */
    private String getMediaPlayerErrorString(int what, int extra) {
        String whatString = "";
        String extraString = "";

        switch (what) {
            case MediaPlayer.MEDIA_ERROR_UNKNOWN:
                whatString = "Unknown error";
                break;
            case MediaPlayer.MEDIA_ERROR_SERVER_DIED:
                whatString = "Server died";
                break;
            case MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK:
                whatString = "Not valid for progressive playback";
                break;
            case MediaPlayer.MEDIA_ERROR_IO:
                whatString = "I/O error";
                break;
            case MediaPlayer.MEDIA_ERROR_MALFORMED:
                whatString = "Malformed media";
                break;
            case MediaPlayer.MEDIA_ERROR_UNSUPPORTED:
                whatString = "Unsupported format";
                break;
            case MediaPlayer.MEDIA_ERROR_TIMED_OUT:
                whatString = "Timed out";
                break;
            default:
                whatString = "Unknown error (" + what + ")";
                break;
        }

        switch (extra) {
            case MediaPlayer.MEDIA_ERROR_IO:
                extraString = "I/O error";
                break;
            case MediaPlayer.MEDIA_ERROR_MALFORMED:
                extraString = "Malformed";
                break;
            case MediaPlayer.MEDIA_ERROR_UNSUPPORTED:
                extraString = "Unsupported";
                break;
            case MediaPlayer.MEDIA_ERROR_TIMED_OUT:
                extraString = "Timed out";
                break;
            case -2147483648: // 0x80000000
                extraString = "Permission denied or URI not accessible";
                break;
            default:
                if (extra != 0) {
                    extraString = "Extra: " + extra;
                }
                break;
        }

        if (extraString.isEmpty()) {
            return whatString;
        } else {
            return whatString + " (" + extraString + ")";
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        Log.d(TAG, "OpenGL surface created");

        // Set clear color to black
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        // Enable blending
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        // Create shader program
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (program == 0) {
            Log.e(TAG, "Failed to create shader program");
            return;
        }

        // Get attribute locations
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord");
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");
        textureHandle = GLES20.glGetUniformLocation(program, "uTexture");

        // Create SurfaceTexture for video
        int[] textureIds = new int[1];
        GLES20.glGenTextures(1, textureIds, 0);
        surfaceTexture = new SurfaceTexture(textureIds[0]);

        // Set up texture
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // Create surface for MediaPlayer - but don't attach yet
        // We'll attach it when SurfaceTexture is ready
        final Surface surface = new Surface(surfaceTexture);

        // Set up SurfaceTexture callback
        surfaceTexture.setOnFrameAvailableListener(surfaceTexture -> {
            // Mark SurfaceTexture as ready when first frame arrives
            if (!surfaceTextureReady) {
                surfaceTextureReady = true;
                Log.d(TAG, "SurfaceTexture ready - first frame available");

                // Now it's safe to attach MediaPlayer to the surface
                try {
                    mediaPlayer.setSurface(surface);
                    surface.release(); // MediaPlayer now owns the surface
                    isInitialized = true;

                    if (listener != null) {
                        listener.onVideoLoaded(videoDuration);
                    }

                    // If playback was requested while waiting, start it now
                    if (pendingPlaybackStart) {
                        pendingPlaybackStart = false;
                        startPlaybackInternal();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error attaching MediaPlayer to surface: " + e.getMessage());
                    if (listener != null) {
                        listener.onVideoError("Failed to attach video to surface: " + e.getMessage());
                    }
                }
            }
            glSurfaceView.requestRender();
        });

        Log.d(TAG, "OpenGL setup completed");
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        Log.d(TAG, "OpenGL surface changed: " + width + "x" + height);

        GLES20.glViewport(0, 0, width, height);

        // Update projection matrix
        float ratio = (float) width / height;
        Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1, 1, 3, 7);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        // Clear screen
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        if (!isInitialized || surfaceTexture == null) {
            return;
        }

        // Only update texture if SurfaceTexture is ready
        if (surfaceTextureReady) {
            // Update texture with error handling
            try {
                surfaceTexture.updateTexImage();
            } catch (Exception e) {
                Log.e(TAG, "Error updating SurfaceTexture: " + e.getMessage());
                // Don't crash, just skip this frame
                return;
            }
        }

        // Use shader program
        GLES20.glUseProgram(program);

        // Set up vertex attributes
        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer);

        GLES20.glEnableVertexAttribArray(texCoordHandle);
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, textureBuffer);

        // Set up matrices
        Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, modelMatrix, 0);
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvpMatrix, 0);
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0);

        // Bind texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0); // Use texture from SurfaceTexture
        GLES20.glUniform1i(textureHandle, 0);

        // Draw quad
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // Disable vertex attributes
        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(texCoordHandle);

        // Notify listener that frame was rendered
        if (listener != null) {
            listener.onFrameRendered();
        }
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);

        if (vertexShader == 0 || fragmentShader == 0) {
            return 0;
        }

        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Could not link program: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }

        return program;
    }

    private int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);

        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Could not compile shader " + type + ": " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }

        return shader;
    }

    public void setVideoPlayerListener(OpenGLVideoPlayerListener listener) {
        this.listener = listener;
    }

    public void loadVideo(Uri videoUri) {
        if (mediaPlayer == null || videoUri == null) {
            Log.e(TAG, "Cannot load video: MediaPlayer not initialized or URI is null");
            if (listener != null) {
                listener.onVideoError("MediaPlayer not initialized or invalid video URI");
            }
            return;
        }

        try {
            Log.d(TAG, "Loading video: " + videoUri.toString());

            // Reset initialization state
            isInitialized = false;
            isPlaying = false;

            // Reset MediaPlayer to clean state
            mediaPlayer.reset();

            // Validate URI before setting data source
            if (!isUriValidForMediaPlayer(videoUri)) {
                Log.e(TAG, "URI validation failed: " + videoUri);
                if (listener != null) {
                    listener.onVideoError("Invalid or inaccessible video URI");
                }
                return;
            }

            // Set data source with error handling
            try {
                mediaPlayer.setDataSource(getContext(), videoUri);
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception setting data source: " + videoUri, e);
                if (listener != null) {
                    listener.onVideoError("Permission denied accessing video file");
                }
                return;
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Invalid URI format: " + videoUri, e);
                if (listener != null) {
                    listener.onVideoError("Invalid video file format");
                }
                return;
            } catch (IOException e) {
                Log.e(TAG, "I/O error setting data source: " + videoUri, e);
                if (listener != null) {
                    listener.onVideoError("Cannot access video file: " + e.getMessage());
                }
                return;
            }

            // Prepare asynchronously
            mediaPlayer.prepareAsync();

            Log.d(TAG, "Video loading started");

        } catch (Exception e) {
            Log.e(TAG, "Failed to load video", e);
            // Reset MediaPlayer state on any error
            try {
                if (mediaPlayer != null) {
                    mediaPlayer.reset();
                }
            } catch (Exception resetException) {
                Log.w(TAG, "Error resetting MediaPlayer after load failure", resetException);
            }

            if (listener != null) {
                listener.onVideoError("Failed to load video: " + e.getMessage());
            }
        }
    }

    /**
     * Validate if URI is accessible to MediaPlayer
     */
    private boolean isUriValidForMediaPlayer(Uri uri) {
        if (uri == null) return false;

        try {
            // Quick scheme check
            String scheme = uri.getScheme();
            if (scheme == null) {
                Log.w(TAG, "URI has no scheme: " + uri);
                return false;
            }

            // For content URIs, do a quick accessibility check
            if ("content".equals(scheme)) {
                try {
                    getContext().getContentResolver().openInputStream(uri).close();
                    return true;
                } catch (Exception e) {
                    Log.w(TAG, "Content URI not accessible: " + uri, e);
                    return false;
                }
            }

            // For file URIs, check if file exists and is readable
            if ("file".equals(scheme)) {
                String path = uri.getPath();
                if (path != null) {
                    java.io.File file = new java.io.File(path);
                    return file.exists() && file.canRead();
                }
                return false;
            }

            // For other schemes, assume valid for now
            return true;

        } catch (Exception e) {
            Log.w(TAG, "Error validating URI: " + uri, e);
            return false;
        }
    }

    public void play() {
        if (mediaPlayer != null) {
            if (surfaceTextureReady && isInitialized) {
                // SurfaceTexture is ready, start immediately
                startPlaybackInternal();
            } else {
                // Wait for SurfaceTexture to be ready
                Log.d(TAG, "Waiting for SurfaceTexture to be ready before starting playback");
                pendingPlaybackStart = true;
            }
        } else {
            Log.w(TAG, "Cannot play: MediaPlayer not initialized");
        }
    }

    private void startPlaybackInternal() {
        try {
            Log.d(TAG, "Starting playback");
            mediaPlayer.start();
            isPlaying = true;

            if (listener != null) {
                listener.onPlaybackStateChanged(true);
            }

            // Start position updates
            mainHandler.post(positionUpdateRunnable);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Illegal state exception during play", e);
            isPlaying = false;
            if (listener != null) {
                listener.onVideoError("Cannot start playback: invalid player state");
            }
        }
    }

    public void pause() {
        if (mediaPlayer != null && isInitialized) {
            try {
                Log.d(TAG, "Pausing playback");
                mediaPlayer.pause();
                isPlaying = false;

                if (listener != null) {
                    listener.onPlaybackStateChanged(false);
                }

                // Stop position updates
                mainHandler.removeCallbacks(positionUpdateRunnable);
            } catch (IllegalStateException e) {
                Log.e(TAG, "Illegal state exception during pause", e);
                isPlaying = false;
                if (listener != null) {
                    listener.onVideoError("Cannot pause playback: invalid player state");
                }
            }
        } else {
            Log.w(TAG, "Cannot pause: MediaPlayer not initialized or not ready");
        }
    }

    public void seekTo(long positionMs) {
        if (mediaPlayer != null && isInitialized) {
            try {
                Log.d(TAG, "Seeking to position: " + positionMs + "ms");
                mediaPlayer.seekTo((int) positionMs);

                if (listener != null) {
                    listener.onPositionChanged(positionMs);
                }
            } catch (IllegalStateException e) {
                Log.e(TAG, "Illegal state exception during seek", e);
                if (listener != null) {
                    listener.onVideoError("Cannot seek: invalid player state");
                }
            }
        } else {
            Log.w(TAG, "Cannot seek: MediaPlayer not initialized or not ready");
        }
    }

    public long getCurrentPosition() {
        if (mediaPlayer != null && isInitialized) {
            return mediaPlayer.getCurrentPosition();
        }
        return 0;
    }

    public long getDuration() {
        return videoDuration;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public void release() {
        Log.d(TAG, "Releasing OpenGL Video Player");

        mainHandler.removeCallbacks(positionUpdateRunnable);

        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }

        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }

        if (glSurfaceView != null) {
            glSurfaceView.onPause();
        }

        isInitialized = false;
        isPlaying = false;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        release();
    }
}
