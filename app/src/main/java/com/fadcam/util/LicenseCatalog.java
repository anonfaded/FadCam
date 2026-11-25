package com.fadcam.util;

import android.content.Context;
import android.text.TextUtils;

import com.fadcam.FLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads the build-generated attribution data and groups it for display.
 *
 * <p>The asset is produced by the license report plugin at build time (see
 * {@code prepareLicenseAsset} in {@code app/build.gradle.kts}); nothing is fetched
 * at runtime. Parsing lives here rather than in the fragment so it can be tested
 * without inflating a view.
 */
public final class LicenseCatalog {

    private static final String TAG = "LicenseCatalog";
    public static final String ASSET = "open_source_licenses.json";

    /**
     * Display order: the licenses that actually dominate this app first, then the
     * long tail, with unknowns last so they are easy to spot.
     */
    private static final String[] ORDER = {
            "Apache License 2.0",
            "BSD 3-Clause",
            "BSD 2-Clause",
            "MIT",
            "The Unlicense",
            "Android SDK License",
            "Play Core SDK Terms",
            "GPL-3.0",
            "LGPL-3.0",
            "Unknown",
    };

    private LicenseCatalog() {
    }

    /**
     * POMs disagree wildly on naming; a real build arrived with seven different
     * spellings of Apache-2.0 alone. Left raw the screen shows duplicate sections,
     * so names collapse to a stable set before grouping.
     */
    static String normalize(String raw) {
        if (raw == null) {
            return "Unknown";
        }
        String k = raw.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
        if (k.isEmpty()) {
            return "Unknown";
        }
        if (k.contains("apache") && k.contains("2")) return "Apache License 2.0";
        if (k.contains("bsd") && k.contains("3")) return "BSD 3-Clause";
        if (k.contains("bsd") && k.contains("2")) return "BSD 2-Clause";
        // Bare "BSD License" is the 3-clause text in practice (Glide and others
        // publish it that way), so it resolves to the same bundled notice instead of
        // becoming a separate group with no text.
        if (k.contains("bsd")) return "BSD 3-Clause";
        if (k.contains("mit")) return "MIT";
        if (k.contains("lesser") || k.contains("lgpl")) return "LGPL-3.0";
        if (k.contains("gpl") || k.startsWith("gnugeneralpubliclicense")) return "GPL-3.0";
        if (k.contains("playcore")) return "Play Core SDK Terms";
        if (k.contains("androidsdk") || k.contains("androidsoftwaredevelopmentkit")) {
            return "Android SDK License";
        }
        if (k.contains("unlicense")) return "The Unlicense";
        return raw.trim();
    }

    /** Bundled text for a license, or null when the terms are not redistributable. */
    public static String assetFor(String license) {
        switch (license) {
            case "Apache License 2.0":
                return "licenses/apache-2.0.txt";
            case "BSD 3-Clause":
                return "licenses/bsd-3-clause.txt";
            case "BSD 2-Clause":
                return "licenses/bsd-2-clause.txt";
            case "GPL-3.0":
                return "licenses/gpl-3.0.txt";
            default:
                return null;
        }
    }

    /**
     * @return license name to the libraries under it, in {@link #ORDER} first then
     *         alphabetically. Empty when the asset is absent, which means the build
     *         did not run the license report.
     */
    public static Map<String, List<String[]>> load(Context context) {
        Map<String, List<String[]>> byLicense = new TreeMap<>();
        try (InputStream in = context.getAssets().open(ASSET)) {
            JSONArray libs = new JSONArray(readAll(in));

            for (int i = 0; i < libs.length(); i++) {
                JSONObject row = libs.getJSONObject(i);
                String name = row.optString("project", "");
                String dependency = row.optString("dependency", "");
                String license = normalize(firstLicenseName(row));
                String licenseUrl = firstLicenseUrl(row);
                if (TextUtils.isEmpty(name) && TextUtils.isEmpty(dependency)) {
                    continue;
                }
                byLicense.computeIfAbsent(license, k -> new ArrayList<>())
                        .add(new String[]{name, dependency, licenseUrl});
            }
        } catch (Exception e) {
            FLog.w(TAG, "Could not read " + ASSET, e);
            return new LinkedHashMap<>();
        }

        // The AppLock library is compiled from app/libs/AppLockLibrary instead of
        // being resolved as a Gradle dependency, so the generated report cannot see
        // it. Credit its Apache-2.0 license and copyright explicitly. Kept out of
        // the empty-report path so a missing asset still reads as a packaging bug.
        if (!byLicense.isEmpty()) {
            boolean hasAppLock = false;
            for (List<String[]> rows : byLicense.values()) {
                for (String[] row : rows) {
                    if (row.length > 1 && row[1] != null
                            && row[1].toLowerCase(Locale.US).contains("applock")) {
                        hasAppLock = true;
                        break;
                    }
                }
                if (hasAppLock) break;
            }
            if (!hasAppLock) {
                byLicense.computeIfAbsent("Apache License 2.0", k -> new ArrayList<>())
                        .add(new String[]{
                                "AppLock (Guardanis)",
                                "com.guardanis:applock:3.0.2 · Copyright 2016 Matthew Silber",
                                "http://www.apache.org/licenses/LICENSE-2.0"
                        });
            }
        }

        Map<String, List<String[]>> ordered = new LinkedHashMap<>();
        for (String license : ORDER) {
            List<String[]> items = byLicense.remove(license);
            if (items != null && !items.isEmpty()) {
                ordered.put(license, items);
            }
        }
        ordered.putAll(byLicense);
        return ordered;
    }

    /**
     * A library may declare several licenses (Glide declares BSD and Apache-2.0 and
     * is used under BSD). Only the first is shown, because listing a library twice
     * would double the row count for no gain, but the order POMs use puts the
     * governing license first.
     */
    /** Terms URL for a license, used when its text cannot be bundled. */
    public static String termsUrl(Map<String, List<String[]>> groups, String license) {
        List<String[]> items = groups.get(license);
        if (items == null) {
            return "";
        }
        for (String[] row : items) {
            if (row.length > 2 && row[2] != null && !row[2].isEmpty()) {
                return row[2];
            }
        }
        return "";
    }

    private static String firstLicenseUrl(JSONObject row) {
        JSONArray licenses = row.optJSONArray("licenses");
        if (licenses == null || licenses.length() == 0) {
            return "";
        }
        JSONObject first = licenses.optJSONObject(0);
        return first == null ? "" : first.optString("license_url", "");
    }

    private static String firstLicenseName(JSONObject row) {
        JSONArray licenses = row.optJSONArray("licenses");
        if (licenses == null || licenses.length() == 0) {
            return "";
        }
        JSONObject first = licenses.optJSONObject(0);
        return first == null ? "" : first.optString("license", "");
    }

    public static String readAsset(Context context, String path) {
        try (InputStream in = context.getAssets().open(path)) {
            return readAll(in);
        } catch (Exception e) {
            FLog.w(TAG, "Could not read " + path, e);
            return "";
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buf.write(chunk, 0, read);
        }
        return buf.toString("UTF-8");
    }
}
