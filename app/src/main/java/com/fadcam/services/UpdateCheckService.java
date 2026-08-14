package com.fadcam.services;

import com.fadcam.FLog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdateCheckService {

    private static final String TAG = "UpdateCheckService";
    private static final String ORG = "anonfaded";
    private static final String FREE_REPO = "FadCam";
    private static final String PRO_REPO = "FadCamPro";
    private static final String FREE_FEED = feedUrl(FREE_REPO);
    private static final String PRO_FEED = feedUrl(PRO_REPO);

    private static String feedUrl(String repo) {
        return "https://github.com/" + ORG + "/" + repo + "/releases.atom";
    }

    private static UpdateCheckResult lastResult;

    // ── Result class ─────────────────────────────────────────────────

    public static class UpdateCheckResult {
        public final String stableVersion, stableUrl;
        public final String betaVersion, betaUrl;
        public final String proVersion, proUrl;
        public final boolean hasStable, hasBeta, hasPro;
        public final boolean errorOccurred;

        UpdateCheckResult(String sv, String su, String bv, String bu,
                          String pv, String pu,
                          boolean hs, boolean hb, boolean hp, boolean err) {
            this.stableVersion = sv; this.stableUrl = su;
            this.betaVersion = bv; this.betaUrl = bu;
            this.proVersion = pv; this.proUrl = pu;
            this.hasStable = hs; this.hasBeta = hb; this.hasPro = hp;
            this.errorOccurred = err;
        }

        public boolean hasAnyUpdate() { return hasStable || hasBeta || hasPro; }
    }

    public static UpdateCheckResult getLastResult() { return lastResult; }

    // ── Public API ───────────────────────────────────────────────────

    public static UpdateCheckResult checkForUpdate(String currentVersion) {
        FLog.d(TAG, "Checking updates — current: " + currentVersion);

        // Fetch both feeds in parallel on a small thread pool
        ExecutorService pool = Executors.newFixedThreadPool(2);
        final String[] freeXml = {null}, proXml = {null};
        try {
            pool.execute(() -> freeXml[0] = fetchFeed(FREE_FEED, FREE_REPO));
            pool.execute(() -> proXml[0] = fetchFeed(PRO_FEED, PRO_REPO));
            pool.shutdown();
            pool.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Parse free repo
        ReleaseInfo freeLatest = parseFeed(freeXml[0], FREE_REPO);
        ReleaseInfo proLatest = parseFeed(proXml[0], PRO_REPO);

        // Compare
        boolean hasStable = freeLatest.stableVer != null
                && isNewerThan(currentVersion, freeLatest.stableVer);
        boolean hasBeta = freeLatest.betaVer != null
                && isNewerThan(currentVersion, freeLatest.betaVer);
        // Pro: show when pro stable exists and is newer than free stable
        boolean hasPro = proLatest.stableVer != null
                && (freeLatest.stableVer == null
                    || isNewerThan(freeLatest.stableVer, proLatest.stableVer));

        UpdateCheckResult result = new UpdateCheckResult(
                freeLatest.stableVer, freeLatest.stableUrl,
                freeLatest.betaVer, freeLatest.betaUrl,
                proLatest.stableVer, proLatest.stableUrl,
                hasStable, hasBeta, hasPro, false);
        lastResult = result;

        FLog.d(TAG, "Result: stable=" + hasStable + "(" + freeLatest.stableVer
                + ") beta=" + hasBeta + "(" + freeLatest.betaVer
                + ") pro=" + hasPro + "(" + proLatest.stableVer + ")");
        return result;
    }

    // ── HTTP ─────────────────────────────────────────────────────────

    private static String fetchFeed(String feedUrl, String repo) {
        try {
            URL url = URI.create(feedUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Accept", "application/atom+xml, text/xml");
            try {
                int code = conn.getResponseCode();
                if (code != 200) {
                    FLog.w(TAG, repo + " feed HTTP " + code);
                    return null;
                }
                InputStream is = conn.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                is.close();
                String xml = bos.toString("UTF-8");
                FLog.d(TAG, repo + " feed: " + xml.length() + " chars");
                return xml;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            FLog.w(TAG, repo + " feed fetch failed: " + e.getMessage());
            return null;
        }
    }

    // ── Parse ────────────────────────────────────────────────────────

    private static class ReleaseInfo {
        String stableVer, stableUrl, betaVer, betaUrl;
    }

    private static ReleaseInfo parseFeed(String xml, String repo) {
        ReleaseInfo info = new ReleaseInfo();
        if (xml == null) return info;

        Pattern tagP = Pattern.compile("<id>[^<]*/(v[^<]+)</id>");
        Matcher m = tagP.matcher(xml);

        while (m.find()) {
            String tag = m.group(1).trim();
            String url = "https://github.com/" + ORG + "/" + repo + "/releases/tag/" + tag;
            String ver = tag.startsWith("v") ? tag.substring(1) : tag;

            if (ver.contains("beta")) {
                if (info.betaVer == null) { info.betaVer = ver; info.betaUrl = url; }
            } else {
                if (info.stableVer == null) { info.stableVer = ver; info.stableUrl = url; }
            }
            if (info.stableVer != null && info.betaVer != null) break;
        }
        FLog.d(TAG, repo + " parsed: stable=" + info.stableVer + " beta=" + info.betaVer);
        return info;
    }

    // ── Version comparison ──────────────────────────────────────────

    public static boolean isNewerThan(String current, String latest) {
        if (current == null || latest == null) return false;
        Version cv = parse(current), lv = parse(latest);
        if (lv.major != cv.major) return lv.major > cv.major;
        if (lv.minor != cv.minor) return lv.minor > cv.minor;
        if (lv.patch != cv.patch) return lv.patch > cv.patch;
        // Beta handling: betaSeries == null means a STABLE release (newer than any
        // beta of the same version). Dotted betas (e.g. "-beta10.4") compare the
        // series first, then the patch — so beta10.10 correctly beats beta10.9.
        if (cv.betaSeries == null && lv.betaSeries == null) return false;
        if (cv.betaSeries == null && lv.betaSeries != null) return false; // stable current, beta latest
        if (cv.betaSeries != null && lv.betaSeries == null) return true;  // beta current, stable latest
        if (lv.betaSeries != cv.betaSeries) return lv.betaSeries > cv.betaSeries;
        return lv.betaPatch > cv.betaPatch;
    }

    static Version parse(String raw) {
        if (raw == null) return new Version(0,0,0,null,0);
        // Full: 4.0.0-beta10 / 4.0.0-beta10.3 (dotted patch supported)
        Matcher m = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)-beta(\\d+)(?:\\.(\\d+))?$", Pattern.CASE_INSENSITIVE).matcher(raw);
        if (m.find()) return new Version(i(m,1), i(m,2), i(m,3), i(m,4), m.group(5) != null ? i(m,5) : 0);
        m = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)-beta$", Pattern.CASE_INSENSITIVE).matcher(raw);
        if (m.find()) return new Version(i(m,1), i(m,2), i(m,3), 1, 0);
        m = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)").matcher(raw);
        if (m.find()) return new Version(i(m,1), i(m,2), i(m,3), null, 0);
        return new Version(0,0,0,null,0);
    }

    private static int i(Matcher m, int g) { return Integer.parseInt(m.group(g)); }

    static class Version {
        final int major, minor, patch; final Integer betaSeries; final int betaPatch;
        Version(int a, int b, int c, Integer s, int p) { major=a; minor=b; patch=c; betaSeries=s; betaPatch=p; }
    }
}
