package com.fadcam.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.R;
import com.fadcam.assets.Asset;
import com.fadcam.assets.AssetDownloadService;
import com.fadcam.assets.AssetException;
import com.fadcam.assets.AssetImporter;
import com.fadcam.assets.AssetRegistry;
import com.fadcam.assets.AssetState;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Download manager for the optional engines and models.
 *
 * <p>The list is deliberately spare: icon, name, version and size, and a state label. All the
 * explanation, the licence, the fingerprint and the actions live in {@link AssetDetailSheet},
 * so the list stays scannable instead of turning into a wall of text.
 *
 * <p>Full builds only.
 */
public class AssetManagerFragment extends BaseFragment
        implements AssetDownloadService.Listener, AssetDetailSheet.Host {

    private static final String TAG = "AssetManager";

    @Nullable
    private LinearLayout rowsContainer;
    @Nullable
    private TextView totalLabel;

    /** Live download percentage per asset, cleared once it settles. */
    private final Map<Asset, Integer> progress = new ConcurrentHashMap<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_asset_manager, container, false);
        rowsContainer = view.findViewById(R.id.asset_rows);
        totalLabel = view.findViewById(R.id.asset_total);

        TextView intro = view.findViewById(R.id.assets_intro);
        if (intro != null) {
            renderLinkedText(intro, getString(R.string.assets_intro));
        }
        View back = view.findViewById(R.id.back_button);
        if (back != null) {
            back.setOnClickListener(v -> OverlayNavUtil.dismiss(getActivity()));
        }
        View source = view.findViewById(R.id.row_asset_source);
        if (source != null) {
            source.setOnClickListener(v -> openPublishedPage());
        }
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        AssetDownloadService.setListener(this);
        render();
    }

    @Override
    public void onPause() {
        AssetDownloadService.setListener(null);
        super.onPause();
    }

    @Override
    public void onAssetStateChanged(@NonNull Asset asset) {
        if (isAdded()) {
            requireActivity().runOnUiThread(this::render);
        }
    }

    @Override
    public void onAssetProgress(@NonNull Asset asset, int percent) {
        progress.put(asset, percent);
        if (isAdded()) {
            requireActivity().runOnUiThread(this::render);
        }
    }

    // Called by the detail sheet. The sheet invokes this from its Download row, which is a
    // click listener, so a download still only ever follows a user tap.
    @Override
    public void onAssetDetailChanged(@NonNull Asset asset) {
        render();
    }

    /**
     * Invoked by {@link AssetDetailSheet} from its Download row's click listener.
     *
     * <p>Tagged {@code download-entry-point} so the audit rule can tell this apart from an
     * automatic call: the matching test proves a click listener invokes it.
     */
    // download-entry-point
    @Override
    public void onAssetDetailDownload(@NonNull Asset asset) {
        // Reached only from AssetDetailSheet's Download row, which attaches the tap.
        AssetDownloadService.start(requireContext(), asset);
        Toast.makeText(requireContext(),
                getString(R.string.assets_toast_started, asset.title),
                Toast.LENGTH_SHORT).show();
        render();
    }

    /** Renders text that may contain Markdown links, using the app's shared helper. */
    private void renderLinkedText(@NonNull TextView target, @NonNull String text) {
        int linkColor = attrColor(R.attr.homeRailAccent, 0xFFE8C77B);
        com.fadcam.utils.LinkText.apply(target, text, linkColor);
    }

    // ------------------------------------------------------------------ render

    private void render() {
        if (!isAdded() || rowsContainer == null) {
            return;
        }
        AssetRegistry registry = AssetRegistry.get(requireContext());
        rowsContainer.removeAllViews();
        long total = 0L;

        Asset[] assets = Asset.values();
        for (int i = 0; i < assets.length; i++) {
            Asset asset = assets[i];
            AssetState state = registry.state(asset);
            if (state != AssetState.DOWNLOADING) {
                progress.remove(asset);
            }
            if (state.isUsable()) {
                total += installedBytes(registry, asset);
            }
            if (i > 0) {
                View divider = new View(requireContext(), null, 0, R.style.SettingsDivider);
                rowsContainer.addView(divider);
            }
            View row = buildRow(registry, asset, state);
            row.setOnClickListener(v -> openDetail(asset));
            rowsContainer.addView(row);
        }

        if (totalLabel != null) {
            totalLabel.setText(total > 0L
                    ? getString(R.string.assets_total, AssetImporter.mb(total))
                    : getString(R.string.assets_total_none));
        }
    }

    private static long installedBytes(@NonNull AssetRegistry registry, @NonNull Asset asset) {
        List<com.fadcam.assets.AssetManifest.Entry> entries = registry.entriesFor(asset);
        if (entries == null) {
            return 0L;
        }
        long onDisk = registry.store().installedSize(asset, entries);
        if (onDisk > 0L) {
            return onDisk;
        }
        return registry.isBundled(asset) ? registry.installedSize(asset) : 0L;
    }

    private View buildRow(@NonNull AssetRegistry registry, @NonNull Asset asset,
                          @NonNull AssetState state) {
        View row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_asset_row, rowsContainer, false);

        TextView title = row.findViewById(R.id.asset_title);
        TextView subtitle = row.findViewById(R.id.asset_subtitle);
        TextView action = row.findViewById(R.id.asset_action);
        TextView icon = row.findViewById(R.id.asset_icon);

        title.setText(asset.title);
        icon.setText(iconLigature(asset));

        // Size here is the download size only: this is a download manager, and that is the
        // number that matters. What it occupies once installed is on the detail sheet, where
        // the difference is worth understanding rather than confusing the list.
        subtitle.setText(getString(R.string.assets_row_meta, asset.version,
                getString(R.string.assets_size_download, AssetImporter.mb(registry.downloadSize(asset))),
                asset.license));

        // Colour-coded state. Green check when installed, accent ring while downloading,
        // accent word when a download is available, amber for repair. Glyph and colour agree,
        // so the state reads without parsing the text.
        com.google.android.material.progressindicator.CircularProgressIndicator ring =
                row.findViewById(R.id.asset_progress);
        int green = requireContext().getResources().getColor(R.color.green,
                requireContext().getTheme());
        int accent = attrColor(R.attr.homeRailAccent, 0xFFE8C77B);
        int amber = 0xFFE8A33D;
        int muted = requireContext().getResources().getColor(R.color.pro_text_secondary,
                requireContext().getTheme());

        ring.setVisibility(View.GONE);
        action.setVisibility(View.VISIBLE);

        switch (state) {
            case READY:
                action.setText(R.string.assets_ready);
                action.setTextColor(green);
                icon.setTextColor(green);
                icon.setText("check_circle");
                break;
            case BUNDLED:
                action.setText(R.string.assets_in_apk);
                action.setTextColor(muted);
                icon.setTextColor(muted);
                break;
            case DOWNLOADING: {
                Integer pct = progress.get(asset);
                // Ring replaces the word while it runs; percentage sits beside it in the
                // subtitle so the row keeps one line of text.
                ring.setVisibility(View.VISIBLE);
                ring.setIndeterminate(pct == null);
                if (pct != null) {
                    ring.setProgressCompat(pct, true);
                    action.setText(getString(R.string.assets_pending_progress, pct));
                    action.setTextColor(accent);
                } else {
                    action.setText(R.string.assets_installing);
                    action.setTextColor(accent);
                }
                icon.setTextColor(accent);
                break;
            }
            case CORRUPT:
                // Files are missing or damaged. There is no repair routine, so the honest
                // action is to fetch it again; that is labelled as such and it works.
                action.setText(R.string.assets_repair);
                action.setTextColor(amber);
                icon.setTextColor(amber);
                icon.setText("error");
                break;
            case FAILED:
                action.setText(R.string.assets_retry);
                action.setTextColor(amber);
                icon.setTextColor(amber);
                icon.setText("error");
                break;
            default:
                action.setText(R.string.assets_download);
                action.setTextColor(accent);
                icon.setTextColor(accent);
                break;
        }

        return row;
    }

    /** Resolves a theme attribute colour so light and dark both work. */
    private int attrColor(int attr, int fallback) {
        android.util.TypedValue value = new android.util.TypedValue();
        if (requireContext().getTheme().resolveAttribute(attr, value, true)) {
            return value.data;
        }
        return fallback;
    }

    /** Material icon ligature for this component, so rows are identifiable at a glance. */
    private static String iconLigature(@NonNull Asset asset) {
        switch (asset) {
            case OPENCV:
                return "directions_walk";
            case FFMPEG:
                return "movie";
            default:
                return "center_focus_strong";
        }
    }

    private void openDetail(@NonNull Asset asset) {
        AssetDetailSheet sheet = AssetDetailSheet.newInstance(asset);
        sheet.show(getChildFragmentManager(), "asset_detail");
    }

    // ----------------------------------------------------------------- actions

    private void openPublishedPage() {
        String url = AssetImporter.publishedUrl(requireContext());
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(requireContext(), url, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Human sentence for an import attempt. Shared with the detail sheet so both surfaces say
     * the same thing.
     */
    @NonNull
    static String messageForImport(@NonNull Context context,
                                   @NonNull AssetImporter.Result result) {
        switch (result.outcome) {
            case INSTALLED:
                return context.getString(R.string.assets_import_installed,
                        result.asset != null ? result.asset.title : "");
            case WRONG_ABI:
                return context.getString(R.string.assets_import_wrong_abi,
                        String.valueOf(result.detectedAbi),
                        android.os.Build.SUPPORTED_ABIS[0]);
            case NO_MATCH:
                return context.getString(R.string.assets_import_no_match);
            case TOO_LARGE:
                return context.getString(R.string.assets_import_too_large);
            case UNREADABLE:
                return context.getString(R.string.assets_import_unreadable);
            default:
                return context.getString(R.string.assets_import_failed,
                        result.detail == null ? "" : result.detail);
        }
    }
}
