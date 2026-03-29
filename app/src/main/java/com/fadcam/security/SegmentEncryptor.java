package com.fadcam.security;

import com.fadcam.FLog;

import androidx.annotation.NonNull;

import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * SegmentEncryptor — AES-256-GCM encryption for HLS media segments.
 *
 * <h3>Wire format</h3>
 * <pre>
 *   [ magic 4 B: 0xFADCE245 ][ nonce 12 B ][ GCM ciphertext + 16-B auth tag ]
 * </pre>
 *
 * <ul>
 *   <li>The magic bytes are a strict format version tag — any segment missing them
 *       is invalid and must be rejected (no fallback to plaintext).</li>
 *   <li>The 128-bit GCM authentication tag is appended automatically by
 *       {@code Cipher.doFinal()} when using {@code AES/GCM/NoPadding}.</li>
 *   <li>{@code init.mp4} (initialization segments) are passed through
 *       <strong>unencrypted</strong> — only {@code seg-N.m4s} media segments
 *       are encrypted.</li>
 * </ul>
 */
public final class SegmentEncryptor {

    private static final String TAG = "SegmentEncryptor";

    // Magic bytes: 0xFADCE245 (not a valid ISOBMFF box size — deliberately chosen)
    private static final byte[] MAGIC = {(byte) 0xFA, (byte) 0xDC, (byte) 0xE2, (byte) 0x45};

    private static final int    GCM_IV_LEN   = 12;
    private static final int    GCM_TAG_BITS = 128;
    private static final String AES_GCM      = "AES/GCM/NoPadding";

    // Enforce static-only usage
    private SegmentEncryptor() {}

    /**
     * Encrypt one HLS media segment.
     *
     * @param plaintext      Raw {@code seg-N.m4s} bytes from the MediaMuxer.
     * @param deviceKeyBytes 32-byte AES-256 device key (from {@link StreamKeyManager#deriveDeviceKey}).
     * @return Encrypted payload in the FadCam E2E wire format.
     * @throws Exception on AES-GCM or JCA failure.
     */
    @NonNull
    public static byte[] encrypt(@NonNull byte[] plaintext, @NonNull byte[] deviceKeyBytes) throws Exception {
        if (deviceKeyBytes.length != 32) {
            throw new IllegalArgumentException("Device key must be 32 bytes, got " + deviceKeyBytes.length);
        }

        // Generate a random 12-byte nonce (IV) per segment — never reuse
        byte[] iv = new byte[GCM_IV_LEN];
        new SecureRandom().nextBytes(iv);

        // AES-256-GCM encrypt: Cipher.doFinal appends the 16-byte auth tag
        Cipher cipher = Cipher.getInstance(AES_GCM);
        cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(deviceKeyBytes, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ciphertextWithTag = cipher.doFinal(plaintext);

        // Assemble: [magic 4B][iv 12B][ciphertext+tag]
        int totalLen = MAGIC.length + GCM_IV_LEN + ciphertextWithTag.length;
        byte[] out = new byte[totalLen];

        System.arraycopy(MAGIC,            0, out,                    0, MAGIC.length);
        System.arraycopy(iv,               0, out, MAGIC.length,         GCM_IV_LEN);
        System.arraycopy(ciphertextWithTag, 0, out, MAGIC.length + GCM_IV_LEN, ciphertextWithTag.length);

        FLog.d(TAG, "Segment encrypted: " + plaintext.length + "B → " + out.length + "B (+" +
                (out.length - plaintext.length) + "B overhead)");
        return out;
    }

    /**
     * Check whether the given bytes start with the FadCam E2E magic bytes.
     *
     * @param data Bytes to inspect (may be any length ≥ 4).
     * @return {@code true} if the first 4 bytes equal {@code 0xFADCE245}.
     */
    public static boolean hasE2EMagic(@NonNull byte[] data) {
        if (data.length < MAGIC.length) return false;
        for (int i = 0; i < MAGIC.length; i++) {
            if (data[i] != MAGIC[i]) return false;
        }
        return true;
    }
}
