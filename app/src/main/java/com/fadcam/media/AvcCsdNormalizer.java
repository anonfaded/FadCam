package com.fadcam.media;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Normalises AVC (H.264) codec-specific data into the shape the MP4 writer requires.
 *
 * <p>The fragmented MP4 muxer's avcC builder (ISO/IEC 14496-15 §5.3.3.1.2) requires
 * {@code csd-0} to contain exactly one Annex-B NAL — the SPS — and {@code csd-1} the PPS.
 * Encoders do not always honour that: the QCOM AVC family with prepend-sps-pps-to-idr returns
 * SPS and PPS concatenated inside csd-0, and some C2 encoders return length-prefixed (AVCC)
 * configuration data. Fed verbatim, the first trips "SPS data not found in csd0" in the box
 * writer and the second throws inside its NAL scan, and both abort track registration — which
 * kills the recording.
 *
 * <p>Pure Java on purpose: no Android dependencies, so the behaviour can be unit-tested.
 */
public final class AvcCsdNormalizer {

    private AvcCsdNormalizer() {
    }

    /** Copies a MediaFormat byte buffer without disturbing its position. */
    public static byte[] readBuffer(ByteBuffer buffer) {
        if (buffer == null || buffer.remaining() <= 0) {
            return null;
        }
        ByteBuffer copy = buffer.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }

    /**
     * Turns whatever AVC codec-specific data the encoder reported into the exact shape the
     * fragmented muxer's avcC box writer requires: {@code csd-0} = one Annex-B SPS NAL and
     * {@code csd-1} = one Annex-B PPS NAL.
     *
     * <p>Handles both container conventions seen in the wild:
     * <ul>
     *   <li>Annex-B (start-code delimited) — including the QCOM case where SPS and PPS arrive
     *       concatenated in a single csd-0;
     *   <li>length-prefixed (AVCC) config data emitted by some C2 encoders.
     * </ul>
     * Non-parameter-set NALs (SEI, slice data) are dropped: the avcC record only carries the SPS
     * and the PPS.
     *
     * @return {@code [sps, pps]} with 4-byte start codes, or {@code null} when no SPS or no PPS
     *     can be located (the caller then leaves the data untouched rather than inventing it).
     */
    public static byte[][] normalize(byte[] csd0, byte[] csd1) {
        java.util.List<byte[]> nalUnits = new java.util.ArrayList<>();
        collectAvcNalUnits(csd0, nalUnits);
        collectAvcNalUnits(csd1, nalUnits);

        byte[] sps = null;
        byte[] pps = null;
        for (byte[] nal : nalUnits) {
            if (nal == null || nal.length == 0) {
                continue;
            }
            int type = nal[0] & 0x1F;
            if (type == 7 && sps == null) {
                sps = nal;
            } else if (type == 8 && pps == null) {
                pps = nal;
            }
        }
        if (sps == null || pps == null) {
            return null;
        }
        return new byte[][] {withStartCode(sps), withStartCode(pps)};
    }

    static void collectAvcNalUnits(byte[] data, java.util.List<byte[]> out) {
        if (data == null || data.length == 0) {
            return;
        }
        // Annex-B: look for a start code anywhere in the buffer.
        int firstStart = findStartCode(data, 0);
        if (firstStart >= 0) {
            int index = firstStart;
            while (index < data.length) {
                int start = findStartCode(data, index);
                if (start < 0) {
                    break;
                }
                int payloadStart = start + (isFourByteStartCode(data, start) ? 4 : 3);
                int next = findStartCode(data, payloadStart);
                int payloadEnd = next < 0 ? data.length : next;
                // Trailing zeros belong to the next start code, not to this NAL.
                while (payloadEnd > payloadStart && data[payloadEnd - 1] == 0
                        && (next >= 0)) {
                    payloadEnd--;
                }
                if (payloadEnd > payloadStart) {
                    out.add(java.util.Arrays.copyOfRange(data, payloadStart, payloadEnd));
                }
                index = payloadEnd;
            }
            return;
        }
        // Length-prefixed (AVCC): 4-byte big-endian length per NAL.
        int pos = 0;
        boolean parsed = false;
        while (pos + 4 <= data.length) {
            int length = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                    | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            if (length <= 0 || pos + 4 + length > data.length) {
                break;
            }
            out.add(java.util.Arrays.copyOfRange(data, pos + 4, pos + 4 + length));
            pos += 4 + length;
            parsed = true;
        }
        if (!parsed && data.length > 0) {
            // Unknown framing: treat the whole buffer as one NAL and let the type check decide.
            out.add(data);
        }
    }

    static int findStartCode(byte[] data, int from) {
        for (int i = Math.max(0, from); i + 3 <= data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) {
                    return i;
                }
                if (data[i + 2] == 0 && i + 3 < data.length && data[i + 3] == 1) {
                    return i;
                }
            }
        }
        return -1;
    }

    static boolean isFourByteStartCode(byte[] data, int start) {
        return start + 3 < data.length && data[start] == 0 && data[start + 1] == 0
                && data[start + 2] == 0 && data[start + 3] == 1;
    }

    static byte[] withStartCode(byte[] nal) {
        byte[] framed = new byte[nal.length + 4];
        framed[3] = 1; // 00 00 00 01
        System.arraycopy(nal, 0, framed, 4, nal.length);
        return framed;
    }

}
