package com.fadcam.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.fadcam.Constants;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * The internal-storage fingerprint decides whether a delta scan runs at all: if it is
 * unchanged, {@code VideoIndexRepository.isIndexFresh()} short-circuits and the records
 * list never re-reads the disk.
 *
 * <p>These tests pin the rules that matter for that decision, especially that <b>files</b>
 * count, not just directories. Hashing only subdirectories meant a recording or FadShot
 * written straight into {@code Camera/} or {@code FadShot/} produced an identical
 * fingerprint, so the new file stayed invisible until the next cold start.</p>
 */
public class FastFileScannerSignatureTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File base;

    /** Mirrors the real layout: FadCam/ with the seven category directories. */
    private void makeTree() throws IOException {
        base = tmp.newFolder(Constants.RECORDING_DIRECTORY);
        for (String category : new String[]{
                Constants.RECORDING_SUBDIR_CAMERA,
                Constants.RECORDING_SUBDIR_DUAL,
                Constants.RECORDING_SUBDIR_SCREEN,
                Constants.RECORDING_SUBDIR_FADITOR,
                Constants.RECORDING_SUBDIR_STREAM,
                Constants.RECORDING_SUBDIR_SHOT,
                Constants.RECORDING_SUBDIR_MINIAPPS}) {
            File dir = new File(base, category);
            assertTrue("could not create " + category, dir.mkdirs());
        }
    }

    private File writeFile(File dir, String name, int bytes) throws IOException {
        File f = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[bytes]);
        }
        // Some filesystems have 1s mtime granularity; make the change observable.
        assertTrue(f.setLastModified(System.currentTimeMillis() + sizeBump++));
        return f;
    }

    private int sizeBump = 0;

    // ── Core regression: files must change the fingerprint ───────────────

    @Test
    public void newFileInCategoryRootChangesSignature() throws IOException {
        makeTree();
        long before = FastFileScanner.computeDirectorySignature(base);

        writeFile(new File(base, Constants.RECORDING_SUBDIR_CAMERA), "recording.mp4", 1024);

        long after = FastFileScanner.computeDirectorySignature(base);
        assertNotEquals("a new recording in Camera/ must invalidate the signature", before, after);
    }

    @Test
    public void newFadShotInShotRootChangesSignature() throws IOException {
        makeTree();
        long before = FastFileScanner.computeDirectorySignature(base);

        // This is the exact reported bug: FadShot saves into FadShot/<Back|Selfie>/,
        // so creating a file with no new directory must still flip the signature.
        writeFile(new File(base, Constants.RECORDING_SUBDIR_SHOT), "FadShot_1.jpg", 512);

        long after = FastFileScanner.computeDirectorySignature(base);
        assertNotEquals("a new FadShot must invalidate the signature", before, after);
    }

    @Test
    public void newFileInNestedSubdirectoryChangesSignature() throws IOException {
        makeTree();
        File back = new File(base, Constants.RECORDING_SUBDIR_CAMERA
                + File.separator + Constants.RECORDING_SUBDIR_CAMERA_BACK);
        assertTrue(back.mkdirs());
        long before = FastFileScanner.computeDirectorySignature(base);

        writeFile(back, "recording_back.mp4", 2048);

        long after = FastFileScanner.computeDirectorySignature(base);
        assertNotEquals("a file in Camera/Back must invalidate the signature", before, after);
    }

    @Test
    public void newFileInShotSelfieChangesSignature() throws IOException {
        makeTree();
        File selfie = new File(base, Constants.RECORDING_SUBDIR_SHOT
                + File.separator + Constants.RECORDING_SUBDIR_SHOT_SELFIE);
        assertTrue(selfie.mkdirs());
        long before = FastFileScanner.computeDirectorySignature(base);

        writeFile(selfie, "selfie.jpg", 256);

        assertNotEquals(FastFileScanner.computeDirectorySignature(base), before);
    }

    // ── Each category is covered, so no dir is silently ignored ──────────

    @Test
    public void everyCategoryDirectoryAffectsSignature() throws IOException {
        String[] categories = {
                Constants.RECORDING_SUBDIR_CAMERA,
                Constants.RECORDING_SUBDIR_DUAL,
                Constants.RECORDING_SUBDIR_SCREEN,
                Constants.RECORDING_SUBDIR_FADITOR,
                Constants.RECORDING_SUBDIR_STREAM,
                Constants.RECORDING_SUBDIR_SHOT,
                Constants.RECORDING_SUBDIR_MINIAPPS
        };
        for (String category : categories) {
            makeTree();
            long before = FastFileScanner.computeDirectorySignature(base);
            writeFile(new File(base, category), "file_" + category + ".mp4", 64);
            long after = FastFileScanner.computeDirectorySignature(base);
            assertNotEquals("category " + category + " is not covered by the signature",
                    before, after);
            tmp.delete();
        }
    }

    // ── Stability: no change means no change ────────────────────────────

    @Test
    public void unchangedTreeIsStable() throws IOException {
        makeTree();
        writeFile(new File(base, Constants.RECORDING_SUBDIR_CAMERA), "a.mp4", 100);
        writeFile(new File(base, Constants.RECORDING_SUBDIR_SHOT), "b.jpg", 200);

        long first = FastFileScanner.computeDirectorySignature(base);
        long second = FastFileScanner.computeDirectorySignature(base);
        long third = FastFileScanner.computeDirectorySignature(base);

        assertEquals("repeated calls must be identical or every scan re-runs", first, second);
        assertEquals(second, third);
    }

    @Test
    public void deletedFileChangesSignature() throws IOException {
        makeTree();
        File f = writeFile(new File(base, Constants.RECORDING_SUBDIR_CAMERA), "gone.mp4", 1024);
        long before = FastFileScanner.computeDirectorySignature(base);

        assertTrue(f.delete());

        assertNotEquals("a deletion must invalidate the signature",
                before, FastFileScanner.computeDirectorySignature(base));
    }

    @Test
    public void fileResizeChangesSignature() throws IOException {
        makeTree();
        File f = writeFile(new File(base, Constants.RECORDING_SUBDIR_CAMERA), "grow.mp4", 10);
        long before = FastFileScanner.computeDirectorySignature(base);

        try (FileOutputStream out = new FileOutputStream(f, true)) {
            out.write(new byte[4096]);
        }

        assertNotEquals("a rewritten file must invalidate the signature",
                before, FastFileScanner.computeDirectorySignature(base));
    }

    // ── Edge cases ──────────────────────────────────────────────────────

    @Test
    public void missingCategoryDirectoriesAreSafe() throws IOException {
        // Fresh install: only the root exists, no category dirs yet.
        base = tmp.newFolder(Constants.RECORDING_DIRECTORY);
        long sig = FastFileScanner.computeDirectorySignature(base);
        assertNotEquals("must never return the unsafe 0 sentinel", 0L, sig);
    }

    @Test
    public void emptyRootIsStable() throws IOException {
        makeTree();
        assertEquals(FastFileScanner.computeDirectorySignature(base),
                FastFileScanner.computeDirectorySignature(base));
    }

    @Test
    public void neverReturnsZeroSentinel() throws IOException {
        // 0 is the "cannot fingerprint" value; callers treat it as "force full scan".
        // Returning it for a valid tree would disable the fast path forever.
        makeTree();
        assertNotEquals(0L, FastFileScanner.computeDirectorySignature(base));
    }

    @Test
    public void nestedDepthTwoIsCovered() throws IOException {
        // Camera/Back and FadShot/Selfie are two levels down; make sure a file there counts.
        makeTree();
        File dual = new File(base, Constants.RECORDING_SUBDIR_CAMERA
                + File.separator + Constants.RECORDING_SUBDIR_CAMERA_DUAL);
        assertTrue(dual.mkdirs());
        long before = FastFileScanner.computeDirectorySignature(base);
        writeFile(dual, "dual.mp4", 128);
        assertNotEquals(before, FastFileScanner.computeDirectorySignature(base));
    }

    @Test
    public void twoDifferentFilesDoNotCollide() throws IOException {
        // Two separate trees, since makeTree() creates FadCam/ inside the same temp root.
        makeTree();
        writeFile(new File(base, Constants.RECORDING_SUBDIR_CAMERA), "one.mp4", 100);
        long a = FastFileScanner.computeDirectorySignature(base);

        base = tmp.newFolder(Constants.RECORDING_DIRECTORY + "_second");
        for (String category : new String[]{
                Constants.RECORDING_SUBDIR_CAMERA,
                Constants.RECORDING_SUBDIR_DUAL,
                Constants.RECORDING_SUBDIR_SCREEN,
                Constants.RECORDING_SUBDIR_FADITOR,
                Constants.RECORDING_SUBDIR_STREAM,
                Constants.RECORDING_SUBDIR_SHOT,
                Constants.RECORDING_SUBDIR_MINIAPPS}) {
            assertTrue(new File(base, category).mkdirs());
        }
        writeFile(new File(base, Constants.RECORDING_SUBDIR_CAMERA), "two.mp4", 100);
        long b = FastFileScanner.computeDirectorySignature(base);

        assertNotEquals("different names must produce different signatures", a, b);
    }
}
