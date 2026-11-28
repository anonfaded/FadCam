package com.fadcam.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.assets.Asset;
import com.fadcam.assets.AssetDownloadService;
import com.fadcam.assets.AssetRegistry;

import java.util.Locale;

/**
 * Inline banner shown inside a feature that cannot run because its engine is missing.
 *
 * <p>Built in code rather than XML so any screen can drop it in without a shared layout: the
 * plan explicitly calls for each surface to own its banner rather than one layout doing it
 * for everyone.
 *
 * <p>Two actions, per the spec: download now, or open the manager. It never starts a download
 * on its own.
 */
public final class AssetBanner {

    /** Called when the user asks for the download, after the service has been started. */
    public interface OnDownloadRequested {
        void onDownloadRequested(@NonNull Asset asset);
    }

    private AssetBanner() {
    }

    /**
     * Builds a banner for this asset, or returns a view that hides itself when the asset is
     * already usable.
     *
     * @param context  any context
     * @param asset    the asset the feature needs
     * @param onOpened optional callback fired after a download is requested
     */
    @NonNull
    public static View create(@NonNull Context context, @NonNull Asset asset,
                              @Nullable OnDownloadRequested onOpened) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 14);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundResource(R.drawable.settings_group_card_bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, 10);
        lp.bottomMargin = dp(context, 10);
        root.setLayoutParams(lp);

        AssetRegistry registry = AssetRegistry.get(context);
        if (registry.isUsable(asset)) {
            // Nothing to say: the feature can run.
            root.setVisibility(View.GONE);
            return root;
        }

        // Title row: icon + "needs a one-time download".
        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.ic_download_small);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                dp(context, 20), dp(context, 20));
        iconLp.rightMargin = dp(context, 10);
        icon.setLayoutParams(iconLp);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        titleRow.addView(icon);

        TextView title = new TextView(context);
        title.setText(context.getString(R.string.assets_banner_title, asset.title));
        title.setTextSize(14f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(attrColor(context, android.R.attr.textColorPrimary));
        titleRow.addView(title);
        root.addView(titleRow);

        // Component, size and licence.
        long size = registry.downloadSize(asset);
        TextView detail = new TextView(context);
        detail.setText(context.getString(R.string.assets_banner_meta,
                String.format(Locale.US, "%.1f", size / 1048576.0), asset.license));
        detail.setTextSize(12f);
        detail.setTextColor(attrColor(context, android.R.attr.textColorSecondary));
        detail.setPadding(0, dp(context, 4), 0, 0);
        root.addView(detail);

        // Where it comes from, so nobody is surprised the app talks to GitHub.
        TextView source = new TextView(context);
        source.setText(asset.purpose);
        source.setTextSize(11f);
        source.setTextColor(attrColor(context, android.R.attr.textColorSecondary));
        source.setPadding(0, dp(context, 2), 0, dp(context, 10));
        root.addView(source);

        // Actions.
        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button download = new Button(context);
        download.setText(R.string.assets_download);
        download.setAllCaps(false);
        download.setOnClickListener(v -> {
            AssetDownloadService.start(context, asset);
            Toast.makeText(context,
                    context.getString(R.string.assets_toast_started, asset.title),
                    Toast.LENGTH_SHORT).show();
            if (onOpened != null) {
                onOpened.onDownloadRequested(asset);
            }
        });
        actions.addView(download);

        Button manage = new Button(context);
        manage.setText(R.string.assets_banner_manage);
        manage.setAllCaps(false);
        manage.setOnClickListener(v -> {
            // Open the manager through the same navigation the settings rows use.
            try {
                Object fragment = Class.forName("com.fadcam.ui.AssetManagerFragment")
                        .getDeclaredConstructor().newInstance();
                if (!(context instanceof androidx.fragment.app.FragmentActivity)) {
                    throw new IllegalStateException("need a FragmentActivity to navigate");
                }
                OverlayNavUtil.show((androidx.fragment.app.FragmentActivity) context,
                        (androidx.fragment.app.Fragment) fragment, "AssetManager");
            } catch (Exception e) {
                Toast.makeText(context, R.string.assets_title,
                        Toast.LENGTH_SHORT).show();
            }
        });
        actions.addView(manage);
        root.addView(actions);

        return root;
    }

    /** Resolves a theme attribute colour, so this follows light and dark automatically. */
    private static int attrColor(@NonNull Context context, int attr) {
        android.util.TypedValue value = new android.util.TypedValue();
        if (context.getTheme().resolveAttribute(attr, value, true)) {
            return value.data;
        }
        return android.graphics.Color.GRAY;
    }

    private static int dp(@NonNull Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
