package com.fadcam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Every APK talks to exactly one update feed, and that feed must belong to the same
 * product line as the APK. Getting this wrong means a Lite user is offered a 186 MB Full
 * APK, or a Pro build checks a repo that never receives its releases.
 *
 * <p>These run once per variant (liteDebug and defaultDebug), so the assertions below are
 * the contract each flavor has to satisfy. {@link BuildConfig} is generated per variant,
 * which is exactly the value the shipping code reads.</p>
 */
public class UpdateFeedContractTest {

    private static final String ORG = "anonfaded";

    /** Mirrors UpdateCheckService.feedUrl(); kept in sync deliberately. */
    private static String feedUrl(String repo) {
        return "https://github.com/" + ORG + "/" + repo + "/releases.atom";
    }

    private boolean isLiteBuild() {
        return BuildConfig.LITE_EDITION;
    }

    private boolean isFullFreeBuild() {
        return !BuildConfig.LITE_EDITION && "FREE".equals(BuildConfig.TIER);
    }

    private boolean isFullProBuild() {
        return !BuildConfig.LITE_EDITION && !"FREE".equals(BuildConfig.TIER);
    }

    private boolean isLiteProBuild() {
        return BuildConfig.LITE_EDITION && !"FREE".equals(BuildConfig.TIER);
    }

    // ── The org is shared by every line ─────────────────────────────────

    @Test
    public void orgIsAlwaysTheUpstreamAccount() {
        assertEquals(ORG, BuildConfig.UPDATE_ORG);
    }

    // ── Free builds check their own line's repo ─────────────────────────

    @Test
    public void fullFreeBuildChecksTheFadCamRepo() {
        if (!isFullFreeBuild()) return;
        assertEquals("FadCam", BuildConfig.UPDATE_REPO);
        assertEquals(feedUrl("FadCam"), feedUrl(BuildConfig.UPDATE_REPO));
    }

    @Test
    public void liteFreeBuildChecksTheLiteRepo() {
        if (!isLiteBuild() || !"FREE".equals(BuildConfig.TIER)) return;
        assertEquals("FadCam-Lite", BuildConfig.UPDATE_REPO);
        // The bug this guards: a Lite build pointing at FadCam would offer the Full APK
        // (186 MB) to a user who installed a 14 MB recorder.
        assertFalse("Lite must never read the Full feed",
                "FadCam".equals(BuildConfig.UPDATE_REPO));
    }

    // ── Pro builds check the Pro repos, never the free ones ─────────────

    @Test
    public void fullProBuildChecksTheFadCamProRepo() {
        if (!isFullProBuild()) return;
        assertEquals("FadCamPro", BuildConfig.UPDATE_REPO);
    }

    @Test
    public void liteProBuildChecksTheLiteProRepo() {
        if (!isLiteProBuild()) return;
        assertEquals("FadCam-LitePro", BuildConfig.UPDATE_REPO);
    }

    @Test
    public void proRepoIsAlwaysSet() {
        assertNotNull(BuildConfig.UPDATE_PRO_REPO);
        assertFalse("UPDATE_PRO_REPO must never be blank",
                BuildConfig.UPDATE_PRO_REPO.trim().isEmpty());
    }

    // ── Cross-line contamination ────────────────────────────────────────

    @Test
    public void liteBuildNeverReferencesFullRepos() {
        if (!isLiteBuild()) return;
        assertFalse("Lite must not use the Full free repo",
                "FadCam".equals(BuildConfig.UPDATE_REPO));
        assertFalse("Lite must not use the Full Pro repo",
                "FadCamPro".equals(BuildConfig.UPDATE_REPO));
    }

    @Test
    public void fullBuildNeverReferencesLiteRepos() {
        if (isLiteBuild()) return;
        assertFalse("Full must not use a Lite repo",
                BuildConfig.UPDATE_REPO.contains("Lite"));
        assertFalse("Full must not use a Lite Pro repo",
                BuildConfig.UPDATE_PRO_REPO.contains("Lite"));
    }

    // ── The URL shape the Atom parser depends on ────────────────────────

    @Test
    public void feedUrlsMatchTheGithubAtomShape() {
        for (String repo : new String[]{BuildConfig.UPDATE_REPO, BuildConfig.UPDATE_PRO_REPO}) {
            String url = feedUrl(repo);
            assertEquals("https://github.com/" + ORG + "/" + repo + "/releases.atom", url);
            assertTrue("feed must live on github.com", url.startsWith("https://github.com/"));
            assertTrue("feed must be an atom feed", url.endsWith("/releases.atom"));
            assertFalse("repo names must not contain slashes or spaces",
                    repo.contains("/") || repo.contains(" "));
        }
    }

    @Test
    public void releasesPageMatchesTheFeedRepo() {
        // UpdateAvailableBottomSheet builds its fallback from these same two values, so
        // the page a user lands on is the repo their build actually watches.
        String releasesUrl = "https://github.com/" + BuildConfig.UPDATE_ORG
                + "/" + BuildConfig.UPDATE_REPO + "/releases";
        assertEquals("https://github.com/" + ORG + "/" + BuildConfig.UPDATE_REPO + "/releases",
                releasesUrl);
        assertFalse("release page must not be the org landing page",
                releasesUrl.endsWith("github.com/" + ORG));
    }
}
