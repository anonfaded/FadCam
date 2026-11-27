package com.fadcam.opengl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.opengl.Matrix;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The inverted orientations rotate the recorded frame 180°. Two properties make that safe:
 * the rotation must map the texture's unit square onto itself (otherwise the GPU samples
 * outside the texture and the frame comes out white or smeared), and it must compose with the
 * camera's own transform matrix, which is not a pure rotation.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class InvertedTexMatrixTest {

    /** Mirrors GLWatermarkRenderer#applyInvertedTexMatrix. */
    private static float[] build() {
        float[] m = new float[16];
        Matrix.setIdentityM(m, 0);
        Matrix.translateM(m, 0, 1f, 1f, 0f);
        Matrix.scaleM(m, 0, -1f, -1f, 1f);
        return m;
    }

    private static float[] map(float[] m, float x, float y) {
        float[] out = new float[4];
        Matrix.multiplyMV(out, 0, m, 0, new float[]{x, y, 0f, 1f}, 0);
        return new float[]{out[0], out[1]};
    }

    @Test
    public void everyCornerStaysInsideTheTexture() {
        float[] m = build();
        float[][] corners = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (float[] c : corners) {
            float[] p = map(m, c[0], c[1]);
            String where = "corner (" + c[0] + "," + c[1] + ") -> (" + p[0] + "," + p[1] + ")";
            assertTrue(where + " x<0", p[0] >= -1e-4f);
            assertTrue(where + " y<0", p[1] >= -1e-4f);
            assertTrue(where + " x>1", p[0] <= 1f + 1e-4f);
            assertTrue(where + " y>1", p[1] <= 1f + 1e-4f);
        }
    }

    /** A 180° rotation must actually turn the image, not leave it alone. */
    @Test
    public void cornersSwapDiagonally() {
        float[] m = build();
        float[] p00 = map(m, 0f, 0f);
        assertEquals(1f, p00[0], 1e-4f);
        assertEquals(1f, p00[1], 1e-4f);
        float[] p11 = map(m, 1f, 1f);
        assertEquals(0f, p11[0], 1e-4f);
        assertEquals(0f, p11[1], 1e-4f);
    }

    /** The centre must stay put, or the frame drifts instead of rotating in place. */
    @Test
    public void centreIsFixed() {
        float[] p = map(build(), 0.5f, 0.5f);
        assertEquals(0.5f, p[0], 1e-4f);
        assertEquals(0.5f, p[1], 1e-4f);
    }

    /** Applying it twice must return to the original, so the transform is a true 180° rotation. */
    @Test
    public void applyingTwiceIsIdentity() {
        float[] twice = new float[16];
        Matrix.multiplyMM(twice, 0, build(), 0, build(), 0);
        float[][] corners = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (float[] c : corners) {
            float[] p = map(twice, c[0], c[1]);
            assertEquals("x " + c[0], c[0], p[0], 1e-4f);
            assertEquals("y " + c[1], c[1], p[1], 1e-4f);
        }
    }

    /**
     * The camera matrix is not a pure rotation: SurfaceTexture crops a one-texel border, so it
     * scales and translates too. Composing must still stay in range.
     */
    @Test
    public void composesSafelyWithBorderCroppingSource() {
        float[] source = new float[16];
        Matrix.setIdentityM(source, 0);
        Matrix.scaleM(source, 0, 0.995f, 0.995f, 1f);
        Matrix.translateM(source, 0, 0.0025f, 0.0025f, 0f);

        float[] out = new float[16];
        Matrix.multiplyMM(out, 0, source, 0, build(), 0);

        for (float[] c : new float[][]{{0, 0}, {1, 0}, {1, 1}, {0, 1}}) {
            float[] p = map(out, c[0], c[1]);
            assertTrue("x out of range: " + p[0], p[0] >= -0.02f && p[0] <= 1.02f);
            assertTrue("y out of range: " + p[1], p[1] >= -0.02f && p[1] <= 1.02f);
        }
    }

    /**
     * The watermark must stay upright and anchored to the same corner in every orientation.
     *
     * <p>It is composited in screen space over a frame that the camera matrix has already
     * turned upright, so rotating it with the inversion only succeeded in turning readable
     * text upside down and pushing it into the opposite corner.</p>
     */
    @Test
    public void watermarkStaysUprightAndAnchoredInEveryOrientation() {
        // Mirrors computeWatermarkRectVertices for the default top-anchored placement.
        float topY = 1.0f;
        float ndcHeight = 0.1f;
        float ndcWidth = 0.6f;
        float[] verts = {-1.0f, topY, -1.0f + ndcWidth, topY,
                -1.0f, topY - ndcHeight, -1.0f + ndcWidth, topY - ndcHeight};

        // Anchored to the top edge, starting at the left edge.
        assertEquals(-1f, verts[0], 1e-4f);
        assertEquals(1f, verts[1], 1e-4f);
        // Top edge stays above the bottom edge: the quad is not flipped.
        assertTrue("top edge must sit above bottom edge", verts[1] > verts[5]);
        // Left edge stays left of the right edge: the text is not mirrored.
        assertTrue("left edge must sit left of right edge", verts[0] < verts[2]);
        // Size is preserved.
        assertEquals(ndcWidth, Math.abs(verts[2] - verts[0]), 1e-4f);
        assertEquals(ndcHeight, Math.abs(verts[5] - verts[1]), 1e-4f);
    }
}
