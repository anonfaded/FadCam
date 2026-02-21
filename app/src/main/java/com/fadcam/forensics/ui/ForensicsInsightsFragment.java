package com.fadcam.forensics.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.fadcam.R;
import com.fadcam.forensics.data.local.ForensicsDatabase;
import com.fadcam.forensics.data.local.entity.AiEventEntity;
import com.fadcam.ui.OverlayNavUtil;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ForensicsInsightsFragment extends Fragment {
    private static final String ARG_EMBEDDED = "arg_embedded";

    private TextView summary;
    private ImageView heatmap;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean embeddedMode;
    private boolean dataLoaded;
    private boolean isLoading;

    public static ForensicsInsightsFragment newEmbeddedInstance() {
        ForensicsInsightsFragment fragment = new ForensicsInsightsFragment();
        Bundle args = new Bundle();
        args.putBoolean(ARG_EMBEDDED, true);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_forensics_insights, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        embeddedMode = getArguments() != null && getArguments().getBoolean(ARG_EMBEDDED, false);
        View header = view.findViewById(R.id.header_bar);
        if (header != null && embeddedMode) {
            header.setVisibility(View.GONE);
        }
        View backButton = view.findViewById(R.id.back_button);
        if (backButton != null) {
            if (embeddedMode) {
                backButton.setVisibility(View.GONE);
            } else {
                backButton.setOnClickListener(v -> OverlayNavUtil.popLevel(requireActivity()));
            }
        }
        summary = view.findViewById(R.id.text_summary);
        heatmap = view.findViewById(R.id.image_heatmap);
        loadInsights();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (isAdded() && !dataLoaded && !isLoading) {
            loadInsights();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        executor.shutdownNow();
    }

    private void loadInsights() {
        if (isLoading) return;
        isLoading = true;
        long since = System.currentTimeMillis() - (24L * 60L * 60L * 1000L);
        executor.execute(() -> {
            ForensicsDatabase db = ForensicsDatabase.getInstance(requireContext());
            int total = db.aiEventDao().countSince(since);
            int people = db.aiEventDao().countByTypeSince("PERSON", since);
            int vehicle = db.aiEventDao().countByTypeSince("VEHICLE", since);
            int pet = db.aiEventDao().countByTypeSince("PET", since);
            int object = db.aiEventDao().countByTypeSince("OBJECT", since);
            List<AiEventEntity> heatRows = db.aiEventDao().getRecentForHeatmap(since, 500);
            Bitmap map = renderHeatmap(heatRows, 900, 420);

            if (!isAdded()) {
                isLoading = false;
                return;
            }
            requireActivity().runOnUiThread(() -> {
                summary.setText(String.format(Locale.US,
                        "Last 24h\n• Total events: %d\n• Person: %d\n• Vehicle: %d\n• Pet: %d\n• Object: %d",
                        total, people, vehicle, pet, object));
                heatmap.setImageBitmap(map);
                isLoading = false;
                dataLoaded = true;
            });
        });
    }

    private Bitmap renderHeatmap(List<AiEventEntity> rows, int width, int height) {
        int canvasColor = resolveThemeColor(R.attr.colorDialog);
        int gridColor = adjustAlpha(resolveThemeColor(R.attr.colorToggle), 80);
        int hotColor = resolveThemeColor(R.attr.colorButton);
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(canvasColor);

        Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        grid.setColor(gridColor);
        grid.setStrokeWidth(1f);
        for (int i = 1; i < 6; i++) {
            float x = (width / 6f) * i;
            float y = (height / 6f) * i;
            canvas.drawLine(x, 0, x, height, grid);
            canvas.drawLine(0, y, width, y, grid);
        }

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(hotColor);
        for (AiEventEntity e : rows) {
            float[] bbox = parseBbox(e.bboxNorm);
            float cx = bbox[0];
            float cy = bbox[1];
            float radius = Math.max(5f, Math.min(14f, 4f + (bbox[2] * 12f) + (Math.max(0, e.priority) * 1.5f)));
            float x = cx * width;
            float y = cy * height;
            p.setAlpha(Math.min(170, 55 + (Math.max(0, e.priority) * 30)));
            canvas.drawCircle(x, y, radius, p);
        }
        return bmp;
    }

    private float[] parseBbox(String raw) {
        float[] def = new float[]{0.5f, 0.5f, 0.08f, 0.08f};
        if (raw == null || raw.isEmpty()) return def;
        try {
            String[] parts = raw.split(",");
            if (parts.length < 4) return def;
            float cx = clamp01(Float.parseFloat(parts[0]));
            float cy = clamp01(Float.parseFloat(parts[1]));
            float w = clamp01(Float.parseFloat(parts[2]));
            float h = clamp01(Float.parseFloat(parts[3]));
            return new float[]{cx, cy, w, h};
        } catch (Exception ignored) {
            return def;
        }
    }

    private float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private int resolveThemeColor(int attr) {
        TypedValue typedValue = new TypedValue();
        requireContext().getTheme().resolveAttribute(attr, typedValue, true);
        return typedValue.data;
    }

    private int adjustAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)), Color.red(color), Color.green(color), Color.blue(color));
    }
}
