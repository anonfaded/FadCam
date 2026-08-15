package com.fadcam.ui;

import android.content.ClipData;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.DragEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.fadcam.FLog;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.Utils;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import java.util.ArrayList;
import java.util.List;

/**
 * MiniAppsReorderBottomSheet lets the user pick which mini apps appear in the
 * home sidebar, in which order, and how many of them are shown (default 3).
 * Every change is persisted immediately (no confirm button); the sidebar is
 * notified via FragmentResult when this sheet closes so it re-renders live.
 */
public class MiniAppsReorderBottomSheet extends BottomSheetDialogFragment {

    /** Result key delivered to the sidebar so it re-applies visibility in real time. */
    public static final String RESULT_KEY = "mini_apps_reorder_result";

    /** Source of truth for every mini app id (order here = default sidebar order). */
    public static final String[] ALL_MINI_APP_IDS = {
        "torch", "qr_scanner", "compass", "sound_meter", "sensor_dashboard",
        "speedometer", "clinometer", "pedometer", "metal_detector",
        "parking_marker", "qr_generator"
    };

    private static final String[] ALL_MINI_APP_ICONS = {
        "flashlight_on", "qr_code_scanner", "explore", "graphic_eq", "sensors",
        "speed", "architecture", "directions_walk", "travel_explore",
        "location_on", "qr_code_2"
    };

    private static final int MOVE_ANIM_MS = 240;

    private final List<String> order = new ArrayList<>();
    private final List<View> rowViews = new ArrayList<>();
    private int count = 3;
    private int draggedIndex = -1;
    private boolean isAnimating = false;
    private int touchSlop = 0;
    private float dragStartX = 0f;
    private float dragStartY = 0f;

    private TextView countValue;
    private TextView countMinus;
    private TextView countPlus;
    private LinearLayout listContainer;

    public static MiniAppsReorderBottomSheet newInstance() {
        return new MiniAppsReorderBottomSheet();
    }

    @Override
    public android.app.Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        android.app.Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((BottomSheetDialog) dialog)
                    .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackground(new ColorDrawable(Color.TRANSPARENT));
            }
        });
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottomsheet_mini_apps_reorder, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Apply dynamic gradient background
        View root = view.findViewById(R.id.picker_root);
        if (root != null) {
            root.setBackgroundResource(R.drawable.picker_bottom_sheet_gradient_bg_dynamic);
        }

        // Setup close button
        View closeBtn = view.findViewById(R.id.picker_close_btn);
        if (closeBtn != null) {
            closeBtn.setOnClickListener(v -> dismiss());
        }

        countValue = view.findViewById(R.id.reorder_count_value);
        countMinus = view.findViewById(R.id.reorder_count_minus);
        countPlus = view.findViewById(R.id.reorder_count_plus);
        listContainer = view.findViewById(R.id.reorder_list);

        touchSlop = ViewConfiguration.get(requireContext()).getScaledTouchSlop();

        countMinus.setOnClickListener(v -> {
            if (count <= 1) return;
            Utils.vibrateSliderTick(requireContext());
            count--;
            persist();
            render();
        });
        countPlus.setOnClickListener(v -> {
            if (count >= order.size()) return;
            Utils.vibrateSliderTick(requireContext());
            count++;
            persist();
            render();
        });

        setupDragAndDrop();

        loadState();
        render();
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        // Tell the sidebar (still open behind this sheet) to re-apply visibility now.
        // Direct static call is the reliable path; FragmentResult is a fallback.
        try {
            HomeSidebarFragment.refreshMiniApps();
        } catch (Exception e) {
            FLog.w("MiniAppsReorder", "Failed to refresh sidebar", e);
        }
        try {
            getParentFragmentManager().setFragmentResult(RESULT_KEY, new Bundle());
        } catch (Exception e) {
            FLog.w("MiniAppsReorder", "Failed to notify sidebar", e);
        }
    }

    /** Live drag-to-reorder: rows follow the finger and the order persists on every move. */
    private void setupDragAndDrop() {
        if (listContainer == null) return;
        listContainer.setOnDragListener((v, event) -> {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    // Keep the sheet from sliding/peeking while a row is being dragged
                    setSheetDraggable(false);
                    return true;
                case DragEvent.ACTION_DRAG_LOCATION: {
                    if (draggedIndex < 0) return true;
                    int target = indexForY(event.getY());
                    if (target >= 0 && target != draggedIndex) {
                        String id = order.remove(draggedIndex);
                        order.add(target, id);
                        draggedIndex = target;
                        Utils.vibrateSliderTick(requireContext());
                        persist(); // real time: write on every row change
                        render();
                    }
                    return true;
                }
                case DragEvent.ACTION_DROP:
                    persist();
                    return true;
                case DragEvent.ACTION_DRAG_ENDED:
                    // Safety net: persist even if the drop happened outside the list
                    persist();
                    setSheetDraggable(true);
                    draggedIndex = -1;
                    return true;
                default:
                    return false;
            }
        });
    }

    private void setSheetDraggable(boolean draggable) {
        try {
            if (getDialog() instanceof BottomSheetDialog) {
                View sheet = ((BottomSheetDialog) getDialog())
                        .findViewById(com.google.android.material.R.id.design_bottom_sheet);
                if (sheet != null) {
                    BottomSheetBehavior<?> behavior = BottomSheetBehavior.from(sheet);
                    behavior.setDraggable(draggable);
                }
            }
        } catch (Exception e) {
            FLog.w("MiniAppsReorder", "Failed to toggle sheet draggable", e);
        }
    }

    private int indexForY(float y) {
        int n = rowViews.size();
        if (n == 0) return 0;
        for (int i = 0; i < n; i++) {
            if (y < rowViews.get(i).getBottom()) return i;
        }
        return n - 1;
    }

    private void loadState() {
        try {
            SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(requireContext());
            String orderStr = prefs.getSidebarMiniAppsOrder();
            order.clear();
            if (orderStr != null) {
                for (String id : orderStr.split("\\|")) {
                    if (!id.isEmpty() && !order.contains(id)) order.add(id);
                }
            }
            // Safety: append any known ids missing from the stored order
            for (String id : ALL_MINI_APP_IDS) {
                if (!order.contains(id)) order.add(id);
            }
            count = prefs.getSidebarMiniAppsCount();
            count = Math.max(1, Math.min(count, order.size()));
        } catch (Exception e) {
            FLog.w("MiniAppsReorder", "Failed to load state", e);
            order.clear();
            for (String id : ALL_MINI_APP_IDS) order.add(id);
            count = 3;
        }
    }

    private void persist() {
        try {
            SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(requireContext());
            prefs.setSidebarMiniAppsOrder(String.join("|", order));
            prefs.setSidebarMiniAppsCount(count);
        } catch (Exception e) {
            FLog.w("MiniAppsReorder", "Failed to persist state", e);
        }
    }

    private void render() {
        if (countValue == null || listContainer == null) return;

        countValue.setText(String.valueOf(count));
        countMinus.setEnabled(count > 1);
        countMinus.setAlpha(count > 1 ? 1f : 0.35f);
        countPlus.setEnabled(count < order.size());
        countPlus.setAlpha(count < order.size() ? 1f : 0.35f);

        listContainer.removeAllViews();
        rowViews.clear();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (int i = 0; i < order.size(); i++) {
            final int index = i;
            final String appId = order.get(i);
            View row = inflater.inflate(R.layout.item_mini_app_reorder_row, listContainer, false);

            TextView handle = row.findViewById(R.id.reorder_row_drag_handle);
            TextView number = row.findViewById(R.id.reorder_row_number);
            TextView icon = row.findViewById(R.id.reorder_row_icon);
            TextView title = row.findViewById(R.id.reorder_row_title);
            TextView soonBadge = row.findViewById(R.id.reorder_row_soon_badge);
            TextView badge = row.findViewById(R.id.reorder_row_badge);
            TextView up = row.findViewById(R.id.reorder_row_up);
            TextView down = row.findViewById(R.id.reorder_row_down);

            number.setText(String.valueOf(i + 1));
            icon.setText(iconFor(appId));
            title.setText(titleFor(appId));
            boolean visible = i < count;
            // "Soon" badge: app is not implemented yet (torch + qr_scanner are ready)
            soonBadge.setVisibility(isReady(appId) ? View.GONE : View.VISIBLE);
            badge.setVisibility(visible ? View.GONE : View.VISIBLE);
            title.setAlpha(visible ? 1f : 0.55f);
            icon.setAlpha(visible ? 1f : 0.55f);

            up.setEnabled(i > 0);
            up.setAlpha(i > 0 ? 1f : 0.35f);
            down.setEnabled(i < order.size() - 1);
            down.setAlpha(i < order.size() - 1 ? 1f : 0.35f);

            up.setOnClickListener(v -> {
                Utils.vibrateSliderTick(requireContext());
                animateMove(index, index - 1);
            });
            down.setOnClickListener(v -> {
                Utils.vibrateSliderTick(requireContext());
                animateMove(index, index + 1);
            });

            // Simple drag on the handle: no long-press needed, the whole row moves.
            handle.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        // Stop the parent ScrollView from stealing the gesture
                        listContainer.requestDisallowInterceptTouchEvent(true);
                        dragStartX = event.getRawX();
                        dragStartY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - dragStartX;
                        float dy = event.getRawY() - dragStartY;
                        if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                            draggedIndex = index;
                            Utils.vibrateSliderTick(requireContext());
                            row.startDragAndDrop(
                                    ClipData.newPlainText("", ""),
                                    createRowShadow(row, handle),
                                    null,
                                    0);
                            return true;
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            });

            listContainer.addView(row);
            rowViews.add(row);

            // Divider between rows (matches the sidebar's divider color)
            if (i < order.size() - 1) {
                View divider = new View(requireContext());
                divider.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1));
                divider.setBackgroundColor(0xFF262626);
                listContainer.addView(divider);
            }
        }
    }

    /**
     * Shadow of the whole row (background + icon + title + serial + arrows),
     * with the touch point anchored at the drag handle so the handle stays
     * under the finger while dragging.
     */
    private View.DragShadowBuilder createRowShadow(View row, View handle) {
        return new View.DragShadowBuilder(row) {
            @Override
            public void onProvideShadowMetrics(@NonNull Point shadowSize,
                    @NonNull Point shadowTouchPoint) {
                shadowSize.set(row.getWidth(), row.getHeight());
                shadowTouchPoint.set(
                        handle.getLeft() + handle.getWidth() / 2,
                        handle.getTop() + handle.getHeight() / 2);
            }
        };
    }

    /**
     * Smoothly slides the row at fromIndex to toIndex (and shifts the rows in
     * between), then commits the new order. Used by the up/down arrows.
     */
    private void animateMove(int fromIndex, int toIndex) {
        if (fromIndex == toIndex || isAnimating) return;
        if (fromIndex < 0 || fromIndex >= rowViews.size()) return;
        if (toIndex < 0 || toIndex >= rowViews.size()) return;

        isAnimating = true;
        View moving = rowViews.get(fromIndex);
        int height = moving.getHeight();
        if (height <= 0) {
            height = Math.round(52f * getResources().getDisplayMetrics().density);
        }
        int delta = toIndex - fromIndex;
        int direction = delta > 0 ? 1 : -1;
        int distance = Math.abs(delta) * height;

        // The moving row slides to its destination
        moving.animate()
                .translationY(direction * distance)
                .setDuration(MOVE_ANIM_MS)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    // Commit: reorder views in the container (row i lives at container index 2*i)
                    listContainer.removeView(moving);
                    listContainer.addView(moving, 2 * toIndex);
                    moving.setTranslationY(0);
                    for (View v : rowViews) v.setTranslationY(0);
                    // Commit: update the order list + persist + rebuild
                    String id = order.remove(fromIndex);
                    order.add(toIndex, id);
                    isAnimating = false;
                    persist();
                    render();
                });

        // Rows in between shift one slot in the opposite direction
        int step = direction;
        for (int i = fromIndex + step; i != toIndex + step; i += step) {
            View v = rowViews.get(i);
            v.animate()
                    .translationY(-direction * height)
                    .setDuration(MOVE_ANIM_MS)
                    .setInterpolator(new DecelerateInterpolator());
        }
    }

    private String iconFor(String appId) {
        for (int i = 0; i < ALL_MINI_APP_IDS.length; i++) {
            if (ALL_MINI_APP_IDS[i].equals(appId)) return ALL_MINI_APP_ICONS[i];
        }
        return "apps";
    }

    /** Apps that are fully implemented; everything else shows a "Soon" badge. */
    private boolean isReady(String appId) {
        return "torch".equals(appId) || "qr_scanner".equals(appId);
    }

    private String titleFor(String appId) {
        switch (appId) {
            case "torch": return getString(R.string.mini_app_torch_title);
            case "qr_scanner": return getString(R.string.mini_app_qr_scanner_title);
            case "compass": return getString(R.string.mini_app_compass_title);
            case "sound_meter": return getString(R.string.mini_app_sound_meter_title);
            case "sensor_dashboard": return getString(R.string.mini_app_sensor_dashboard_title);
            case "speedometer": return getString(R.string.mini_app_speedometer_title);
            case "clinometer": return getString(R.string.mini_app_clinometer_title);
            case "pedometer": return getString(R.string.mini_app_pedometer_title);
            case "metal_detector": return getString(R.string.mini_app_metal_detector_title);
            case "parking_marker": return getString(R.string.mini_app_parking_marker_title);
            case "qr_generator": return getString(R.string.mini_app_qr_generator_title);
            default: return appId;
        }
    }
}