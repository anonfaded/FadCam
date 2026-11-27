package com.fadcam.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

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
import java.util.Locale;

/**
 * Detail sheet for one asset: what it does, which features use it, its licence, its
 * fingerprint, and the actions.
 *
 * <p>Split out of the list so the list stays scannable. The plan expects per-surface banners
 * to link here rather than duplicating the explanation.
 */
public class AssetDetailSheet extends BottomSheetDialogFragment {

    private static final String ARG_ID = "asset_id";

    /** Requests the parent screen to re-render after an action. */
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
        View download = view.findViewById(R.id.detail_download);
        View importRow = view.findViewById(R.id.detail_import);
        View removeRow = view.findViewById(R.id.detail_remove);

        title.setText(asset.title);
        purpose.setText(asset.purpose);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < asset.usedBy.length; i++) {
            if (i > 0) {
                sb.append(" \u00b7 ");
            }
            sb.append(asset.usedBy[i]);
        }
        usedBy.setText(getString(R.string.assets_detail_used_by, sb.toString()));

        // Version, both sizes and licence, each labelled. No bare numbers that could be read
        // as the wrong one: the archive is much smaller than what it becomes on disk.
        long downloadSize = registry.downloadSize(asset);
        long installedSize = registry.installedSize(asset);
        String sizes = installedSize > downloadSize
                ? getString(R.string.assets_size_both, AssetImporter.mb(downloadSize),
                        AssetImporter.mb(installedSize))
                : getString(R.string.assets_size_download, AssetImporter.mb(downloadSize));
        meta.setText(getString(R.string.assets_detail_meta,
                asset.version, sizes, asset.license));

        List<AssetManifest.Entry> entries = registry.entriesFor(asset);
        if (entries != null && !entries.isEmpty()) {
            hash.setText("SHA-256  " + entries.get(0).sha256);
            hash.setVisibility(View.VISIBLE);
        } else {
            hash.setVisibility(View.GONE);
        }

        // Actions: only what makes sense for this state.
        boolean usable = state.isUsable();
        download.setVisibility(usable ? View.GONE : View.VISIBLE);
        removeRow.setVisibility(usable && !registry.isBundled(asset)
                ? View.VISIBLE : View.GONE);

        download.setOnClickListener(v -> {
            Host host = host();
            if (host != null) {
                host.onAssetDetailDownload(asset);
            }
            dismiss();
        });

        importRow.setOnClickListener(v -> {
            // Any MIME type: zips, .tflite, or files the picker cannot name.
            filePicker.launch(new String[]{"*/*"});
        });

        removeRow.setOnClickListener(v -> {
            AssetRegistry.get(requireContext()).delete(asset);
            Host host = host();
            if (host != null) {
                host.onAssetDetailChanged(asset);
            }
            dismiss();
        });

        return view;
    }

    private void onFilePicked(@Nullable Uri uri) {
        Asset asset = asset();
        if (uri == null || asset == null || !isAdded()) {
            return;
        }
        // Hashing a 48 MB file must not block the sheet.
        new Thread(() -> {
            AssetImporter.Result result =
                    AssetImporter.importFrom(requireContext().getApplicationContext(), uri);
            if (getActivity() == null) {
                return;
            }
            getActivity().runOnUiThread(() -> {
                if (!isAdded()) {
                    return;
                }
                android.widget.Toast.makeText(requireContext(),
                        AssetManagerFragment.messageForImport(requireContext(), result),
                        android.widget.Toast.LENGTH_LONG).show();
                Host host = host();
                if (host != null) {
                    host.onAssetDetailChanged(asset);
                }
                dismiss();
            });
        }, "asset-import").start();
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
