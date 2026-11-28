package com.fadcam.assets;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed {@code assets-manifest.json}, bundled inside the APK.
 *
 * <p>The manifest ships with the app rather than being fetched, so the host serving
 * the binary cannot also supply the checksum. This class is deliberately the only
 * place that knows the manifest schema.
 *
 * <p>Pure Java and free of Android APIs, so {@link AssetManifestTest} can exercise
 * it on the JVM.
 */
public final class AssetManifest {

    /** One file belonging to one asset, for one ABI. */
    public static final class Entry {
        /** Manifest id of the owning asset, e.g. {@code opencv}. */
        public final String assetId;
        /** ABI this file is built for, or {@code universal} for ABI-independent data. */
        public final String abi;
        /** Published file name, used to build the download URL. */
        public final String file;
        /** Lowercase hex SHA-256 of the published file. */
        public final String sha256;
        /** Exact size in bytes. */
        public final long size;
        /** SPDX license identifier. */
        public final String license;
        /** Human-readable component name. */
        public final String displayName;
        /** Component version string. */
        public final String version;
        /**
         * Files the archive expands to, on device. Null for a raw asset, where the
         * downloaded file is itself the artifact.
         *
         * <p>Only the total {@link #size} is tracked for a zip, not per-member sizes,
         * because the manifest is generated before unzipping. {@link AssetStore}
         * therefore validates a zipped asset by file presence plus a stored marker,
         * and a raw asset by exact size.
         */
        @Nullable
        public final List<String> members;
        /**
         * Bytes this entry occupies once installed, when known. For a raw asset that is the
         * download size; for an archive it is the sum of the unpacked members, which is much
         * larger than the compressed download. Zero when the manifest does not say.
         */
        public final long installedSize;

        Entry(String assetId, String abi, String file, String sha256, long size,
              String license, String displayName, String version,
              @Nullable List<String> members, long installedSize) {
            this.assetId = assetId;
            this.abi = abi;
            this.file = file;
            this.sha256 = sha256;
            this.size = size;
            this.license = license;
            this.displayName = displayName;
            this.version = version;
            this.members = members;
            this.installedSize = installedSize;
        }

        /** True when this entry is an archive that must be unpacked. */
        public boolean isArchive() {
            return members != null && !members.isEmpty();
        }
    }

    /** Compares two ABI names for preference. Lower index wins. */
    public interface AbiRank {
        int rankOf(String abi);
    }

    private final String assetSet;
    private final String baseUrl;
    private final Map<String, List<Entry>> byAsset;

    private AssetManifest(String assetSet, String baseUrl, Map<String, List<Entry>> byAsset) {
        this.assetSet = assetSet;
        this.baseUrl = baseUrl;
        this.byAsset = byAsset;
    }

    /** Asset set identifier, e.g. {@code assets-v1.0}. */
    @NonNull
    public String assetSet() {
        return assetSet;
    }

    /** Base download URL, always ending in a slash. */
    @NonNull
    public String baseUrl() {
        return baseUrl;
    }

    /** Every entry belonging to one asset, all ABIs. */
    @NonNull
    public List<Entry> entries(@NonNull Asset asset) {
        List<Entry> list = byAsset.get(asset.id);
        return list == null ? Collections.<Entry>emptyList() : Collections.unmodifiableList(list);
    }

    /**
     * Picks the best entry for this asset and ABI preference.
     *
     * <p>Prefers a match on the highest-ranked available ABI, then the first
     * {@code universal} entry, and returns null when neither exists. Choosing by
     * ranked list rather than a single ABI name is what makes a 64-bit phone that
     * also lists {@code armeabi-v7a} pick the arm64 file.
     *
     * @param asset the asset to resolve
     * @param rank  ABI preference order, index 0 most preferred
     */
    @Nullable
    public List<Entry> resolve(@NonNull Asset asset, @NonNull AbiRank rank) {
        List<Entry> all = entries(asset);
        if (all.isEmpty()) {
            return null;
        }
        // Group by ABI so a multi-file asset is selected as a whole unit.
        Map<String, List<Entry>> byAbi = new LinkedHashMap<>();
        for (Entry e : all) {
            List<Entry> group = byAbi.get(e.abi);
            if (group == null) {
                group = new ArrayList<>();
                byAbi.put(e.abi, group);
            }
            group.add(e);
        }
        String bestAbi = null;
        int bestRank = Integer.MAX_VALUE;
        for (String abi : byAbi.keySet()) {
            int r = rank.rankOf(abi);
            if (r >= 0 && r < bestRank) {
                bestRank = r;
                bestAbi = abi;
            }
        }
        if (bestAbi != null) {
            return byAbi.get(bestAbi);
        }
        List<Entry> universal = byAbi.get("universal");
        return universal == null ? null : universal;
    }

    /**
     * Total installed size in bytes, when the manifest records it. Falls back to 0 so the UI
     * can say "installed" without inventing a number.
     */
    public long totalInstalledSize(@Nullable List<Entry> entries) {
        if (entries == null) {
            return 0L;
        }
        long sum = 0L;
        for (Entry e : entries) {
            sum += e.installedSize > 0 ? e.installedSize : e.size;
        }
        return sum;
    }

    /** Total download size in bytes for the entries returned by resolve. */
    public long totalSize(@Nullable List<Entry> entries) {
        if (entries == null) {
            return 0L;
        }
        long sum = 0L;
        for (Entry e : entries) {
            sum += e.size;
        }
        return sum;
    }

    /**
     * Parses a manifest.
     *
     * @throws org.json.JSONException when the document is malformed
     */
    @NonNull
    public static AssetManifest parse(@NonNull String json) throws Exception {
        JSONObject root = new JSONObject(json);
        String assetSet = root.getString("assetSet");
        String baseUrl = root.getString("baseUrl");
        if (!baseUrl.endsWith("/")) {
            baseUrl = baseUrl + "/";
        }
        Map<String, List<Entry>> byAsset = new LinkedHashMap<>();
        JSONArray arr = root.getJSONArray("assets");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            List<String> members = null;
            JSONArray m = o.optJSONArray("members");
            if (m != null && m.length() > 0) {
                members = new ArrayList<>(m.length());
                for (int k = 0; k < m.length(); k++) {
                    members.add(m.getString(k));
                }
            }
            Entry e = new Entry(
                    o.getString("id"),
                    o.getString("abi"),
                    o.getString("file"),
                    o.getString("sha256").toLowerCase(java.util.Locale.US),
                    o.getLong("size"),
                    o.optString("license", ""),
                    o.optString("name", o.getString("file")),
                    o.optString("version", ""),
                    members,
                    o.optLong("installedSize", members == null ? 0L : 0L));
            List<Entry> list = byAsset.get(e.assetId);
            if (list == null) {
                list = new ArrayList<>();
                byAsset.put(e.assetId, list);
            }
            list.add(e);
        }
        return new AssetManifest(assetSet, baseUrl, byAsset);
    }
}
