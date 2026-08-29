package com.fadcam.streaming;

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
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Android Keystore-backed vault for RTMP destination secrets. */
public final class RtmpCredentialVault {
    private static final String PREFS = "fadcam_rtmp_credentials_v1";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "fadcam_rtmp_aes_v1";
    private static final String PREFIX = "credential_";
    private static final String IV_SUFFIX = "_iv";
    private static final String VALUE_SUFFIX = "_value";
    private static final String DESTINATION_SUFFIX = "_destination";
    private static final String ACTIVE_ALIAS = "active_alias";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private final SharedPreferences preferences;

    public RtmpCredentialVault(@NonNull Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void put(@NonNull String alias, @NonNull RtmpDestination destination,
                    @NonNull String serverUrl, @NonNull String streamKey) {
        String normalizedAlias = normalizeAlias(alias);
        String normalizedServerUrl = serverUrl.trim();
        String normalizedStreamKey = streamKey.trim();
        if (normalizedServerUrl.isEmpty()) throw new IllegalArgumentException("RTMP server URL is required");
        if (normalizedStreamKey.isEmpty()) throw new IllegalArgumentException("RTMP stream key is required");
        try {
            // Validate the destination without retaining the generated endpoint.
            destination.buildEndpoint(normalizedServerUrl, normalizedStreamKey);
            byte[] iv = new byte[GCM_IV_BYTES];
            byte[] plaintext = (normalizedServerUrl + "\n" + normalizedStreamKey).getBytes(StandardCharsets.UTF_8);
            try {
                new SecureRandom().nextBytes(iv);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
                byte[] ciphertext = cipher.doFinal(plaintext);
                String prefix = PREFIX + normalizedAlias;
                if (!preferences.edit()
                        .putString(prefix + VALUE_SUFFIX, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                        .putString(prefix + IV_SUFFIX, Base64.encodeToString(iv, Base64.NO_WRAP))
                        .putString(prefix + DESTINATION_SUFFIX, destination.name())
                        .commit()) {
                    throw new IllegalStateException("Unable to persist RTMP credentials");
                }
            } finally {
                java.util.Arrays.fill(plaintext, (byte) 0);
                java.util.Arrays.fill(iv, (byte) 0);
            }
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception ignored) {
            // Never expose cryptographic/provider details or credential-derived exception text.
            throw new IllegalStateException("Unable to protect RTMP credentials");
        }
    }

    @Nullable
    public RtmpCredential get(@NonNull String alias) {
        String prefix = PREFIX + normalizeAlias(alias);
        String value = preferences.getString(prefix + VALUE_SUFFIX, null);
        String ivEncoded = preferences.getString(prefix + IV_SUFFIX, null);
        String destinationName = preferences.getString(prefix + DESTINATION_SUFFIX, null);
        if (value == null || ivEncoded == null || destinationName == null) return null;
        byte[] iv = null;
        byte[] ciphertext = null;
        byte[] plaintext = null;
        try {
            RtmpDestination destination = RtmpDestination.valueOf(destinationName);
            iv = Base64.decode(ivEncoded, Base64.DEFAULT);
            ciphertext = Base64.decode(value, Base64.DEFAULT);
            if (iv.length != GCM_IV_BYTES || ciphertext.length == 0) {
                throw new IllegalStateException("Invalid RTMP credential record");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            plaintext = cipher.doFinal(ciphertext);
            String[] parts = new String(plaintext, StandardCharsets.UTF_8).split("\\n", 2);
            if (parts.length != 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()) {
                throw new IllegalStateException("Invalid RTMP credential record");
            }
            return new RtmpCredential(destination, parts[0], parts[1]);
        } catch (Exception ignored) {
            // Do not surface authentication tags, ciphertext, URLs, keys, or provider errors.
            throw new IllegalStateException("Unable to retrieve RTMP credentials");
        } finally {
            if (iv != null) java.util.Arrays.fill(iv, (byte) 0);
            if (ciphertext != null) java.util.Arrays.fill(ciphertext, (byte) 0);
            if (plaintext != null) java.util.Arrays.fill(plaintext, (byte) 0);
        }
    }

    public void remove(@NonNull String alias) {
        String normalizedAlias = normalizeAlias(alias);
        String prefix = PREFIX + normalizedAlias;
        preferences.edit().remove(prefix + VALUE_SUFFIX).remove(prefix + IV_SUFFIX)
                .remove(prefix + DESTINATION_SUFFIX).apply();
        if (normalizedAlias.equals(getActiveAlias())) clearActiveAlias();
    }

    public boolean contains(@NonNull String alias) {
        return preferences.contains(PREFIX + normalizeAlias(alias) + VALUE_SUFFIX);
    }

    /** Stores only the non-secret profile name used to recover an active session after process restart. */
    public void setActiveAlias(@NonNull String alias) {
        preferences.edit().putString(ACTIVE_ALIAS, normalizeAlias(alias)).apply();
    }

    @Nullable public String getActiveAlias() { return preferences.getString(ACTIVE_ALIAS, null); }
    public void clearActiveAlias() { preferences.edit().remove(ACTIVE_ALIAS).apply(); }

    private static String normalizeAlias(String alias) {
        String value = alias.trim();
        if (!value.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid credential alias");
        return value;
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    public static final class RtmpCredential {
        private final RtmpDestination destination;
        private final String serverUrl;
        private final String streamKey;
        private RtmpCredential(RtmpDestination destination, String serverUrl, String streamKey) {
            this.destination = destination;
            this.serverUrl = serverUrl;
            this.streamKey = streamKey;
        }
        @NonNull public RtmpDestination getDestination() { return destination; }
        @NonNull public String getServerUrl() { return serverUrl; }
        @NonNull public String getStreamKey() { return streamKey; }
    }
}
