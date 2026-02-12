package com.fadcam.ui;

import android.net.Uri;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.fadcam.R;
import com.google.android.material.appbar.MaterialToolbar;

public class ImageViewerActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_viewer);

        MaterialToolbar toolbar = findViewById(R.id.imageViewerToolbar);
        ImageView imageView = findViewById(R.id.imageViewerImage);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        Uri uri = getIntent() != null ? getIntent().getData() : null;
        if (uri == null) {
            Toast.makeText(this, R.string.photo_capture_failed, Toast.LENGTH_SHORT).show();
            finish();
            return;
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
}
