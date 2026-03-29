package com.fadcam.security;

import com.fadcam.FLog;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * StreamKeyManager — derives and securely stores the E2E stream encryption master key.
 *
 * <p>Key derivation chain:
 * <pre>
 *   password + user_uuid
 *       ↓  PBKDF2-SHA256, 600 000 iterations, 512-bit
 *   master_key (64 bytes)
 *       ↓  HKDF-SHA256  ("fadcam-stream-v1" || deviceId, 32 bytes)
 *   device_key  →  used by SegmentEncryptor for AES-256-GCM
 * </pre>
 *
 * <p>Storage: the raw master_key bytes are AES-256-GCM encrypted with a key that lives
 * in the Android Keystore (alias {@value #KEYSTORE_ALIAS}), then stored in a private
 * SharedPreferences file. The Keystore key never leaves the Keystore hardware/TEE.
 */
public class StreamKeyManager {

    private static final String TAG = "StreamKeyManager";
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String KEYSTORE_ALIAS    = "fadcam_stream_e2e_wrap";
    private static final String PREFS_NAME        = "FadCamE2EPrefs";
    private static final String KEY_BLOB          = "e2e_master_key_enc"; // Base64 iv||ciphertext||tag
    private static final int    GCM_IV_LEN        = 12;
    private static final int    GCM_TAG_BITS      = 128;

    private static volatile StreamKeyManager sInstance;

    private final Context mContext;

    private StreamKeyManager(@NonNull Context context) {
        mContext = context.getApplicationContext();
    }

    /** Thread-safe singleton. */
    public static StreamKeyManager getInstance(@NonNull Context context) {
        if (sInstance == null) {
            synchronized (StreamKeyManager.class) {
                if (sInstance == null) {
                    sInstance = new StreamKeyManager(context);
                }
            }
        }
        return sInstance;
    }

    // ─── Public API ──────────────────────────────────────────────────────────────

    /**
     * Derive the master key from {@code password} + {@code userUuid}, verify it against
     * {@code expectedVerifyTag}, then persist it.
     *
     * <p>The verify_tag is {@code HMAC-SHA256(master_key, "fadcam-e2e-v1")} stored in
     * {@code public.users.e2e_verify_tag}. Providing it ensures the password is correct
     * before the key is ever stored — a wrong password throws {@link SecurityException}.
     *
     * @param password          The user's FadSec ID password.
     * @param userUuid          The user's UUID (PBKDF2 salt).
     * @param expectedVerifyTag 64-char hex tag fetched from Supabase, or {@code null} to skip
     *                          verification (not recommended; fails-open).
     * @throws SecurityException if verify_tag comparison fails (wrong password).
     * @throws Exception         on crypto or Keystore failure.
     */
    public void initFromPassword(@NonNull String password,
                                 @NonNull String userUuid,
                                 @Nullable String expectedVerifyTag) throws Exception {
        FLog.i(TAG, "Deriving E2E master key…");
        byte[] masterKey = deriveMasterKey(password, userUuid);

        if (expectedVerifyTag != null && !expectedVerifyTag.isEmpty()) {
            String computed = hmacHex(masterKey, "fadcam-e2e-v1");
            FLog.d(TAG, "Verifying E2E password against stored verify_tag…");
            if (!computed.equals(expectedVerifyTag)) {
                FLog.e(TAG, "E2E password verification FAILED — verify_tag mismatch");
                throw new SecurityException("Incorrect password");
            }
            FLog.i(TAG, "E2E password verified successfully");
        } else {
            FLog.w(TAG, "No verify_tag provided — skipping server-side password verification");
        }

        storeMasterKey(masterKey);
        FLog.i(TAG, "E2E master key derived and stored successfully");
    }

    /**
     * Convenience overload — same as {@link #initFromPassword(String, String, String)} with
     * no verify_tag. Prefer the 3-arg version when the verify_tag is available.
     */
    public void initFromPassword(@NonNull String password, @NonNull String userUuid) throws Exception {
        initFromPassword(password, userUuid, null);
    }

    /**
     * Returns {@code true} if the master key has been stored (i.e. E2E is ready to use).
     */
    public boolean isInitialized() {
        SharedPreferences prefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.contains(KEY_BLOB);
    }

    /**
     * Derive a 32-byte AES-256 device key from the stored master key.
     *
     * @param deviceId The device ID string (included in the HKDF info field).
     * @return 32-byte raw key bytes for AES-256-GCM.
     * @throws Exception if the master key is not stored or crypto fails.
     */
    @NonNull
    public byte[] deriveDeviceKey(@NonNull String deviceId) throws Exception {
        byte[] masterKey = loadMasterKey();
        if (masterKey == null) {
            throw new IllegalStateException("E2E master key is not initialised. Call initFromPassword() first.");
        }
        return hkdfExpand(masterKey, "fadcam-stream-v1" + deviceId, 32);
    }

    /**
     * Compute HMAC-SHA256(master_key, "fadcam-e2e-v1") and return as lower-case hex.
     * This is identical to the verify_tag stored in the Supabase {@code public.users} table.
     * Use it to verify that the user entered the correct password before storing.
     *
     * @param password The candidate password to verify.
     * @param userUuid The user's UUID.
     * @return 64-char lower-case hex verify tag.
     * @throws Exception on crypto failure.
     */
    @NonNull
    public String computeVerifyTag(@NonNull String password, @NonNull String userUuid) throws Exception {
        byte[] masterKey = deriveMasterKey(password, userUuid);
        return hmacHex(masterKey, "fadcam-e2e-v1");
    }

    /**
     * Delete the stored master key. Call on account sign-out.
     */
    public void clear() {
        SharedPreferences prefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().remove(KEY_BLOB).apply();
        FLog.i(TAG, "E2E master key cleared");
    }

    // ─── Key Derivation ──────────────────────────────────────────────────────────

    /**
     * PBKDF2-SHA256(password, userUuid, 600 000 iterations) → 64-byte master key.
     */
    @NonNull
    private byte[] deriveMasterKey(@NonNull String password, @NonNull String userUuid) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(
                password.toCharArray(),
                userUuid.getBytes(StandardCharsets.UTF_8),
                600_000,
                512 // bits = 64 bytes
        );
        try {
            byte[] masterKey = factory.generateSecret(spec).getEncoded();
            if (masterKey.length != 64) {
                throw new IllegalStateException("PBKDF2 returned " + masterKey.length + " bytes, expected 64");
            }
            return masterKey;
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * HKDF-SHA256 "expand" step.
     *
     * <p>We use zero-byte salt (HKDF RFC 5869 §2.2: default salt = zero bytes of hash length).
     * The {@code info} string provides domain separation per stream/device.
     *
     * @param prk         Pseudo-random key (the 64-byte master key used directly as PRK).
     * @param info        Context string ("fadcam-stream-v1" + deviceId).
     * @param outputBytes Number of bytes to output (≤ 32 for a single HMAC-SHA256 round).
     */
    @NonNull
    private byte[] hkdfExpand(@NonNull byte[] prk, @NonNull String info, int outputBytes) throws Exception {
        if (outputBytes > 32) throw new IllegalArgumentException("HKDF single-round is limited to 32 bytes");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] infoBytes = info.getBytes(StandardCharsets.UTF_8);
        // T(1) = HMAC-SHA256(PRK, info || 0x01)
        mac.update(infoBytes);
        mac.update((byte) 0x01);
        byte[] t1 = mac.doFinal();
        if (outputBytes == 32) return t1;
        byte[] out = new byte[outputBytes];
        System.arraycopy(t1, 0, out, 0, outputBytes);
        return out;
    }

    /**
     * HMAC-SHA256(key, message) → lower-case hex string.
     */
    @NonNull
    private String hmacHex(@NonNull byte[] key, @NonNull String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(64);
        for (byte b : digest) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    // ─── Keystore-backed Storage ─────────────────────────────────────────────────

    /**
     * Wrap and persist {@code masterKey} using an Android-Keystore-backed AES key.
     */
    private void storeMasterKey(@NonNull byte[] masterKey) throws Exception {
        SecretKey wrapKey = getOrCreateWrapKey();
        byte[] iv = new byte[GCM_IV_LEN];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] cipherblob = cipher.doFinal(masterKey); // includes 16-byte GCM tag appended by JCA

        // Store as Base64(iv || cipherblob)
        byte[] stored = new byte[GCM_IV_LEN + cipherblob.length];
        System.arraycopy(iv, 0, stored, 0, GCM_IV_LEN);
        System.arraycopy(cipherblob, 0, stored, GCM_IV_LEN, cipherblob.length);

        SharedPreferences prefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_BLOB, Base64.encodeToString(stored, Base64.NO_WRAP)).apply();
        FLog.d(TAG, "Master key stored (" + stored.length + " bytes wrapped)");
    }

    /**
     * Load and unwrap the master key from SharedPreferences.
     *
     * @return Master key bytes, or {@code null} if not stored.
     */
    @Nullable
    private byte[] loadMasterKey() throws Exception {
        SharedPreferences prefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String blob = prefs.getString(KEY_BLOB, null);
        if (blob == null) return null;

        byte[] stored = Base64.decode(blob, Base64.NO_WRAP);
        if (stored.length <= GCM_IV_LEN) {
            FLog.e(TAG, "Stored key blob too short, clearing");
            prefs.edit().remove(KEY_BLOB).apply();
            return null;
        }

        byte[] iv        = new byte[GCM_IV_LEN];
        byte[] cipherblob = new byte[stored.length - GCM_IV_LEN];
        System.arraycopy(stored, 0, iv, 0, GCM_IV_LEN);
        System.arraycopy(stored, GCM_IV_LEN, cipherblob, 0, cipherblob.length);

        SecretKey wrapKey = getOrCreateWrapKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, wrapKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(cipherblob);
    }

    /**
     * Retrieve the AES-256 wrapping key from Android Keystore, creating it if absent.
     * The key is hardware-backed (where available) and never exported.
     */
    @NonNull
    private SecretKey getOrCreateWrapKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
        ks.load(null);

        if (ks.containsAlias(KEYSTORE_ALIAS)) {
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) ks.getEntry(KEYSTORE_ALIAS, null);
            if (entry != null) return entry.getSecretKey();
            // Alias exists but entry is null — regenerate
            ks.deleteEntry(KEYSTORE_ALIAS);
        }

        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
        kg.init(new KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(false) // we supply our own IV
                .build());
        FLog.i(TAG, "Created new Keystore wrap key: " + KEYSTORE_ALIAS);
        return kg.generateKey();
    }
}
