package com.fadcam.ui;

import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.Toast;
import android.graphics.Color;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.forensics.ui.ForensicsEvidenceInfoBottomSheet;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

public class ImageViewerActivity extends AppCompatActivity {

    private static final int OVERLAY_ANIM_DURATION = 200;

    public static final String EXTRA_SOURCE_VIDEO_URI = "com.fadcam.extra.SOURCE_VIDEO_URI";
    public static final String EXTRA_OPEN_AT_MS = "com.fadcam.extra.OPEN_AT_MS";
    public static final String EXTRA_EVENT_TYPE = "com.fadcam.extra.EVENT_TYPE";
    public static final String EXTRA_CLASS_NAME = "com.fadcam.extra.CLASS_NAME";
    public static final String EXTRA_CONFIDENCE = "com.fadcam.extra.CONFIDENCE";
    public static final String EXTRA_CAPTURED_AT = "com.fadcam.extra.CAPTURED_AT";
    public static final String EXTRA_SOURCE_LABEL = "com.fadcam.extra.SOURCE_LABEL";

    @Nullable
    private View topDock;
    @Nullable
    private View bottomDock;
    private boolean overlayVisible = true;

    @Nullable
    private Uri sourceVideoUri;
    private long openAtMs = 0L;
    @Nullable
    private String eventType;
    @Nullable
    private String className;
    private float confidence = 0f;
    private long capturedAt = 0L;
    @Nullable
    private String sourceLabel;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        SharedPreferencesManager spm = SharedPreferencesManager.getInstance(this);
        String savedTheme = spm.sharedPreferences.getString(
                Constants.PREF_APP_THEME,
                Constants.DEFAULT_APP_THEME
        );
        if ("Crimson Bloom".equals(savedTheme)) {
            setTheme(R.style.Theme_FadCam_Red);
        } else if ("Faded Night".equals(savedTheme)) {
            setTheme(R.style.Theme_FadCam_Amoled);
        } else {
            setTheme(R.style.Base_Theme_FadCam);
        }
        super.onCreate(savedInstanceState);
        enforceBlackStatusBar();
        setContentView(R.layout.activity_image_viewer);

        ImageView back = findViewById(R.id.imageViewerBack);
        ImageView more = findViewById(R.id.imageViewerMore);
        ZoomableImageView imageView = findViewById(R.id.imageViewerImage);
        TextView metaView = findViewById(R.id.imageViewerMeta);
        topDock = findViewById(R.id.imageViewerTopDock);
        bottomDock = findViewById(R.id.imageViewerBottomDock);
        if (back != null) {
            back.setOnClickListener(v -> finish());
        }
        if (imageView != null) {
            imageView.setOnSingleTapListener(this::toggleOverlay);
        }

        Uri uri = getIntent() != null ? getIntent().getData() : null;
        if (uri == null) {
            Toast.makeText(this, R.string.photo_capture_failed, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        sourceVideoUri = parseUri(getIntent().getStringExtra(EXTRA_SOURCE_VIDEO_URI));
        openAtMs = Math.max(0L, getIntent().getLongExtra(EXTRA_OPEN_AT_MS, 0L));
        eventType = getIntent().getStringExtra(EXTRA_EVENT_TYPE);
        className = getIntent().getStringExtra(EXTRA_CLASS_NAME);
        confidence = getIntent().getFloatExtra(EXTRA_CONFIDENCE, 0f);
        capturedAt = getIntent().getLongExtra(EXTRA_CAPTURED_AT, 0L);
        sourceLabel = getIntent().getStringExtra(EXTRA_SOURCE_LABEL);

        if (metaView != null) {
            metaView.setText(buildInlineMeta());
        }
        if (more != null) {
            more.setOnClickListener(v -> showInfoSheet(uri));
        }

        Glide.with(this)
                .load(uri)
                .error(R.drawable.ic_video_placeholder)
                .listener(new RequestListener<android.graphics.drawable.Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<android.graphics.drawable.Drawable> target, boolean isFirstResource) {
                        Toast.makeText(ImageViewerActivity.this, R.string.photo_capture_failed, Toast.LENGTH_SHORT).show();
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model, Target<android.graphics.drawable.Drawable> target, com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                        return false;
                    }
                })
                .into(imageView);
    }

    /**
     * Toggles the visibility of the top and bottom overlay docks with a fade animation.
     * Called on single-tap of the image.
     */
    private void toggleOverlay() {
        overlayVisible = !overlayVisible;
        if (overlayVisible) {
            showDock(topDock);
            showDock(bottomDock);
        } else {
            hideDock(topDock);
            hideDock(bottomDock);
        }
    }

    private void showDock(@Nullable View dock) {
        if (dock == null) return;
        dock.setVisibility(View.VISIBLE);
        dock.setAlpha(0f);
        dock.animate()
                .alpha(1f)
                .setDuration(OVERLAY_ANIM_DURATION)
                .setInterpolator(new DecelerateInterpolator())
                .setListener(null)
                .start();
    }

    private void hideDock(@Nullable View dock) {
        if (dock == null) return;
        dock.animate()
                .alpha(0f)
                .setDuration(OVERLAY_ANIM_DURATION)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> dock.setVisibility(View.GONE))
                .start();
    }

    private void enforceBlackStatusBar() {
        Window window = getWindow();
        if (window == null) return;
        window.setStatusBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decor = window.getDecorView();
            int flags = decor.getSystemUiVisibility();
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            decor.setSystemUiVisibility(flags);
        }
    }

    @Nullable
    private Uri parseUri(@Nullable String value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        try {
            return Uri.parse(value);
        } catch (Throwable t) {
            return null;
        }
    }

    @NonNull
    private String buildInlineMeta() {
        String classLabel = !TextUtils.isEmpty(className)
                ? className
                : (!TextUtils.isEmpty(eventType) ? eventType : "Evidence");
        String when = capturedAt > 0L
                ? DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
                .format(new Date(capturedAt))
                : getString(R.string.forensics_unknown_time);
        return String.format(
                Locale.US,
                "%s • conf %.2f • %s",
                classLabel.toUpperCase(Locale.US),
                confidence,
                when
        );
    }

    private void showInfoSheet(@NonNull Uri snapshotUri) {
        ForensicsEvidenceInfoBottomSheet.newInstance(
                className,
                eventType,
                confidence,
                capturedAt,
                openAtMs,
                sourceLabel,
                sourceVideoUri != null ? sourceVideoUri.toString() : null,
                snapshotUri.toString()
        ).show(getSupportFragmentManager(), "ForensicsEvidenceInfoBottomSheet");
    }

}
