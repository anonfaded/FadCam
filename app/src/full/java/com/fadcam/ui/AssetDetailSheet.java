package com.fadcam.ui;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.assets.Asset;
import com.fadcam.assets.AssetImporter;
import com.fadcam.assets.AssetManifest;
import com.fadcam.assets.AssetRegistry;
import com.fadcam.assets.AssetState;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.List;

/**
 * Detail sheet for one asset: what it is, what needs it, its size, licence, fingerprint, and
 * the actions.
 *
 * <p>Split out of the list so the list stays scannable. Actions are rendered as the app's own
 * picker rows rather than a bespoke design, so ripple, row height and text colours match every
 * other sheet in the app.
 */
public class AssetDetailSheet extends BottomSheetDialogFragment {

    private static final String ARG_ID = "asset_id";

    /** Lets the parent screen re-render after an action. */
    public interface Host {
        void onAssetDetailChanged(@NonNull Asset asset);

        void onAssetDetailDownload(@NonNull Asset asset);
    }

    private ActivityResultLauncher<String[]> filePicker;

    @NonNull
    public static AssetDetailSheet newInstance(@NonNull Asset asset) {
        AssetDetailSheet f = new AssetDetailSheet();
        Bundle b = new Bundle();
        b.putString(ARG_ID, asset.id);
        f.setArguments(b);
        return f;
    }

    @Override
    public int getTheme() {
        // The app's themed bottom sheet, matching every other sheet here.
        return R.style.CustomBottomSheetDialogTheme;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        filePicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onFilePicked);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.sheet_asset_detail, container, false);

        // Apply the app's sheet background explicitly, the same way PickerBottomSheetFragment
        // does. The style alone does not resolve the themed gradient attributes because
        // CustomBottomSheetDialogTheme is parented to Theme.MaterialComponents rather than the
        // app theme, which is what makes the sheet look flat and square instead of matching
        // every other sheet here.
        view.setBackgroundResource(R.drawable.picker_bottom_sheet_gradient_bg_dynamic);

        Asset asset = asset();
        if (asset == null) {
            return view;
        }
        AssetRegistry registry = AssetRegistry.get(requireContext());
        AssetState state = registry.state(asset);

        TextView title = view.findViewById(R.id.detail_title);
        TextView purpose = view.findViewById(R.id.detail_purpose);
        TextView usedBy = view.findViewById(R.id.detail_used_by);
        TextView meta = view.findViewById(R.id.detail_meta);
        TextView hash = view.findViewById(R.id.detail_hash);

        title.setText(asset.title);
        purpose.setText(asset.purpose);
        usedBy.setText(getString(R.string.assets_detail_used_by, join(asset.usedBy)));

        // Version, size and licence. The list shows only the download size; here the installed
        // size is added once it is on disk, because that is where the difference matters.
        long downloadSize = registry.downloadSize(asset);
        long installedSize = registry.installedSize(asset);
        String sizes = installedSize > downloadSize
                ? getString(R.string.assets_size_both, AssetImporter.mb(downloadSize),
                        AssetImporter.mb(installedSize))
                : getString(R.string.assets_size_download, AssetImporter.mb(downloadSize));
        String text = getString(R.string.assets_detail_meta, asset.version, sizes,
                asset.license);
        if (state == AssetState.READY || state == AssetState.BUNDLED) {
            text = text + "  \u00b7  " + getString(R.string.assets_detail_installed_size,
                    AssetImporter.mb(installedSize));
        }
        meta.setText(text);

        List<AssetManifest.Entry> entries = registry.entriesFor(asset);
        if (entries != null && !entries.isEmpty()) {
            // Full value: a truncated fingerprint cannot be compared with the published one,
            // which defeats showing it.
            hash.setText("SHA-256  " + entries.get(0).sha256);
            hash.setVisibility(View.VISIBLE);
        } else {
            hash.setVisibility(View.GONE);
        }

        renderActions(view, registry, asset, state);
        return view;
    }

    /** Builds the action rows from the app's shared picker item layout. */
    private void renderActions(@NonNull View view, @NonNull AssetRegistry registry,
                               @NonNull Asset asset, @NonNull AssetState state) {
        LinearLayout actions = view.findViewById(R.id.detail_actions);
        actions.removeAllViews();

        boolean usable = state.isUsable();
        if (!usable) {
            View row = actionRow(actions, "download", accentColor(),
                    getString(R.string.assets_download), null);
            row.setOnClickListener(v -> {
                Host host = host();
                if (host != null) {
                    host.onAssetDetailDownload(asset);
                }
                dismiss();
            });
            actions.addView(row);
        }

        // Offline path, always offered: this is how a component is added with no connection.
        View importRow = actionRow(actions, "folder_open", null,
                getString(R.string.assets_import), getString(R.string.assets_import_hint));
        importRow.setOnClickListener(v -> filePicker.launch(new String[]{"*/*"}));
        actions.addView(importRow);

        if (usable && !registry.isBundled(asset)) {
            View removeRow = actionRow(actions, "delete", 0xFFFF5252,
                    getString(R.string.assets_remove), null);
            removeRow.setOnClickListener(v -> confirmRemove(asset));
            actions.addView(removeRow);
        }
    }

    /**
     * One action row, inflated from {@code picker_bottom_sheet_item} so it inherits the app's
     * ripple and row styling instead of a second implementation of the same idea.
     */
    private View actionRow(@NonNull ViewGroup parent, @NonNull String glyph,
                           @Nullable Integer tint, @NonNull String title,
                           @Nullable String subtitle) {
        View row = LayoutInflater.from(requireContext())
                .inflate(R.layout.picker_bottom_sheet_item, parent, false);
        // The picker row's own background is exactly what every sheet here uses: each row is
        // its own card with the app ripple. Keep it.

        TextView leading = row.findViewById(R.id.picker_item_leading_symbol);
        TextView rowTitle = row.findViewById(R.id.picker_item_title);
        TextView rowSubtitle = row.findViewById(R.id.picker_item_subtitle);

        leading.setVisibility(View.VISIBLE);
        leading.setText(glyph);
        if (tint != null) {
            leading.setTextColor(tint);
        }
        rowTitle.setText(title);
        if (subtitle != null && rowSubtitle != null) {
            rowSubtitle.setVisibility(View.VISIBLE);
            rowSubtitle.setText(subtitle);
        }
        return row;
    }

    /**
     * Two-step removal. The confirmation names the features that will stop working, taken from
     * the same list this sheet shows, so the consequence is concrete.
     */
    private void confirmRemove(@NonNull Asset asset) {
        InputActionBottomSheetFragment sheet = InputActionBottomSheetFragment.newConfirm(
                getString(R.string.assets_remove_title, asset.title),
                getString(R.string.assets_remove),
                getString(R.string.assets_remove_body, join(asset.usedBy)),
                R.drawable.ic_delete_red);
        sheet.setCallbacks(new InputActionBottomSheetFragment.Callbacks() {
            @Override
            public void onResetConfirmed() {
                AssetRegistry.get(requireContext()).delete(asset);
                Host host = host();
                if (host != null) {
                    host.onAssetDetailChanged(asset);
                }
                dismiss();
            }

            @Override
            public void onImportConfirmed(org.json.JSONObject json) {
                // Not used in confirm mode.
            }
        });
        sheet.show(getParentFragmentManager(), "asset_remove_confirm");
    }

    private void onFilePicked(@Nullable Uri uri) {
        Asset asset = asset();
        if (uri == null || asset == null || !isAdded()) {
            return;
        }
        final android.content.Context appContext =
                requireContext().getApplicationContext();
        // Hashing a 48 MB file must not block the sheet, so it happens off the main thread.
        new Thread(() -> {
            AssetImporter.Result result = AssetImporter.importFrom(appContext, uri);
            if (getActivity() == null) {
                return;
            }
            getActivity().runOnUiThread(() -> {
                if (!isAdded()) {
                    return;
                }
                Toast.makeText(requireContext(),
                        AssetManagerFragment.messageForImport(requireContext(), result),
                        Toast.LENGTH_LONG).show();
                Host host = host();
                if (host != null) {
                    host.onAssetDetailChanged(asset);
                }
                dismiss();
            });
        }, "asset-import").start();
    }

    @NonNull
    private static String join(@NonNull String[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(values[i]);
        }
        return sb.toString();
    }

    private int accentColor() {
        android.util.TypedValue v = new android.util.TypedValue();
        return requireContext().getTheme().resolveAttribute(R.attr.homeRailAccent, v, true)
                ? v.data : 0xFFE8C77B;
    }

    @Nullable
    private Asset asset() {
        Bundle args = getArguments();
        return args == null ? null : Asset.fromId(args.getString(ARG_ID));
    }

    @Nullable
    private Host host() {
        return getParentFragment() instanceof Host ? (Host) getParentFragment() : null;
    }
}
