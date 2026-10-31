package com.fadcam.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;

import com.fadcam.BuildConfig;
import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/** Release details shared by the Full and Lite Pro product lines. */
public class ProUpdateBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_VERSION = "version";
    private static final String ARG_URL = "url";
    private static final String ARG_CURRENT = "current";

    public static ProUpdateBottomSheet newInstance(String version, String url, String current) {
        ProUpdateBottomSheet sheet = new ProUpdateBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_VERSION, version);
        args.putString(ARG_URL, url);
        args.putString(ARG_CURRENT, current);
        sheet.setArguments(args);
        return sheet;
    }

    @Override
    public int getTheme() {
        return R.style.ProUpdateDialogTheme;
    }

    @Override
    public void onStart() {
        super.onStart();
        BottomSheetDialog dialog = (BottomSheetDialog) getDialog();
        if (dialog == null) return;
        BottomSheetBehavior<?> behavior = dialog.getBehavior();
        // A compact sheet at normal font sizes; long copy remains natively scrollable.
        behavior.setMaxHeight(Math.round(getResources().getDisplayMetrics().heightPixels * 0.92f));
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottomsheet_pro_update, container, false);
        Bundle args = getArguments();
        String version = argument(args, ARG_VERSION);
        String current = argument(args, ARG_CURRENT);
        String url = argument(args, ARG_URL);
        boolean owner = "PRO".equals(BuildConfig.TIER) || "PRO_PLUS".equals(BuildConfig.TIER);
        String product = getString(BuildConfig.LITE_EDITION
                ? R.string.pro_update_product_lite : R.string.pro_update_product_full);

        TextView title = view.findViewById(R.id.pro_update_title);
        title.setText(getString(R.string.pro_update_title, product));
        ViewCompat.setAccessibilityHeading(title, true);
        TextView description = view.findViewById(R.id.pro_update_description);
        description.setText(getString(owner ? R.string.pro_update_owner_description
                : R.string.pro_update_free_description, product));

        bindVersion(view, R.id.pro_update_current_row, R.id.pro_update_current_version, current);
        bindVersion(view, R.id.pro_update_latest_row, R.id.tv_pro_sheet_version, version);
        if (current.isEmpty() && version.isEmpty()) {
            view.findViewById(R.id.pro_update_versions).setVisibility(View.GONE);
        }

        view.findViewById(R.id.picker_close_btn).setOnClickListener(v -> dismiss());
        view.findViewById(R.id.btn_pro_dismiss).setOnClickListener(v -> dismiss());
        view.findViewById(R.id.btn_pro_get).setOnClickListener(v -> openRelease(view, url));
        return view;
    }

    private static String argument(@Nullable Bundle args, String key) {
        String value = args == null ? null : args.getString(key);
        return value == null ? "" : value.trim();
    }

    private static void bindVersion(View view, int rowId, int valueId, String version) {
        view.findViewById(rowId).setVisibility(version.isEmpty() ? View.GONE : View.VISIBLE);
        ((TextView) view.findViewById(valueId)).setText(version);
    }

    private void openRelease(View view, String url) {
        TextView error = view.findViewById(R.id.pro_update_error);
        Uri uri = Uri.parse(url);
        if ((!"https".equalsIgnoreCase(uri.getScheme())
                && !"http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getHost().isEmpty()) {
            error.setText(R.string.pro_update_link_unavailable);
            error.setVisibility(View.VISIBLE);
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
            dismiss();
        } catch (ActivityNotFoundException | SecurityException exception) {
            error.setText(R.string.pro_update_browser_unavailable);
            error.setVisibility(View.VISIBLE);
        }
    }
}
