package com.fadcam.streaming;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.fadcam.Constants;
import com.fadcam.FLog;
import com.fadcam.streaming.model.SessionToken;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Manages authentication for FadCam Remote access.
 * Passwords are stored only as salted, stretched verifiers. Bearer sessions are
 * deliberately process-memory-only and are never persisted in SharedPreferences.
 */
public final class RemoteAuthManager {
    private static final String TAG = "RemoteAuthManager";
    private static final String PBKDF2_PREFIX = "v2$";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final int PBKDF2_ITERATIONS = 120_000;

    private static RemoteAuthManager instance;
    private final Context context;
    private final SharedPreferences prefs;
    private final Map<String, SessionToken> activeSessions;
    private final SecureRandom secureRandom;
    private volatile boolean sessionsJustCleared = false;

    private RemoteAuthManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        this.activeSessions = new ConcurrentHashMap<>();
        this.secureRandom = new SecureRandom();
    }

    public static synchronized RemoteAuthManager getInstance(Context context) {
        if (instance == null) instance = new RemoteAuthManager(context);
        return instance;
    }

    public boolean isAuthEnabled() {
        return prefs.getBoolean(Constants.PREF_REMOTE_AUTH_ENABLED, false);
    }

    public void setAuthEnabled(boolean enabled) {
        prefs.edit().putBoolean(Constants.PREF_REMOTE_AUTH_ENABLED, enabled).apply();
        clearAllSessions();
        FLog.i(TAG, "Authentication " + (enabled ? "enabled" : "disabled"));
    }

    /** Set or replace the local password verifier; plaintext is never persisted or logged. */
    public boolean setPassword(String password) {
        if (password == null) return false;
        String normalized = password.trim();
        if (normalized.length() < Constants.REMOTE_AUTH_MIN_PASSWORD_LENGTH
                || normalized.length() > Constants.REMOTE_AUTH_MAX_PASSWORD_LENGTH) return false;

        char[] passwordChars = normalized.toCharArray();
        byte[] salt = new byte[SALT_BYTES];
        byte[] hash = null;
        try {
            secureRandom.nextBytes(salt);
            hash = derivePasswordHash(passwordChars, salt);
            String encoded = PBKDF2_PREFIX
                    + Base64.encodeToString(salt, Base64.NO_WRAP)
                    + "$"
                    + Base64.encodeToString(hash, Base64.NO_WRAP);
            if (!prefs.edit().putString(Constants.PREF_REMOTE_AUTH_PASSWORD_HASH, encoded).commit()) return false;
            clearAllSessions();
            FLog.i(TAG, "Password updated successfully");
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            Arrays.fill(passwordChars, '\0');
            Arrays.fill(salt, (byte) 0);
            if (hash != null) Arrays.fill(hash, (byte) 0);
        }
    }

    public boolean verifyPassword(String password) {
        if (password == null) return false;
        char[] passwordChars = password.trim().toCharArray();
        String stored = prefs.getString(Constants.PREF_REMOTE_AUTH_PASSWORD_HASH, null);
        if (stored == null || stored.isEmpty()) {
            Arrays.fill(passwordChars, '\0');
            return false;
        }
        try {
            if (stored.startsWith(PBKDF2_PREFIX)) return verifyPbkdf2(passwordChars, stored);
            if (stored.matches("[0-9a-fA-F]{64}")) {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(new String(passwordChars).getBytes(StandardCharsets.UTF_8));
                byte[] expected = hexToBytes(stored);
                boolean valid = MessageDigest.isEqual(digest, expected);
                Arrays.fill(digest, (byte) 0);
                Arrays.fill(expected, (byte) 0);
                if (valid) upgradeLegacyPassword(passwordChars);
                return valid;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        } finally {
            Arrays.fill(passwordChars, '\0');
        }
    }

    public boolean hasPassword() {
        String stored = prefs.getString(Constants.PREF_REMOTE_AUTH_PASSWORD_HASH, null);
        return stored != null && !stored.isEmpty();
    }

    /** Creates a bearer session in memory. The token is never written to disk or logs. */
    public SessionToken createSession(String deviceInfo) {
        String token = generateToken();
        long now = System.currentTimeMillis();
        SessionToken session = new SessionToken(token, now,
                now + Constants.REMOTE_AUTH_TOKEN_EXPIRY_MS,
                deviceInfo == null ? "unknown" : deviceInfo);
        activeSessions.put(token, session);
        FLog.i(TAG, "New remote session created (expires in 24h)");
        return session;
    }

    public SessionToken validateToken(String token) {
        if (token == null || token.isEmpty()) return null;
        SessionToken session = activeSessions.get(token);
        if (session == null) return null;
        if (session.isExpired()) {
            activeSessions.remove(token);
            return null;
        }
        return session;
    }

    public void revokeSession(String token) {
        if (token == null || token.isEmpty()) return;
        if (activeSessions.remove(token) != null) FLog.i(TAG, "Remote session revoked");
    }

    public void clearAllSessions() {
        int count = activeSessions.size();
        activeSessions.clear();
        sessionsJustCleared = true;
        if (count > 0) FLog.i(TAG, "Cleared " + count + " remote session(s)");
    }

    public boolean checkAndResetSessionsClearedFlag() {
        boolean result = sessionsJustCleared;
        sessionsJustCleared = false;
        return result;
    }

    public Map<String, SessionToken> getActiveSessions() {
        activeSessions.entrySet().removeIf(entry -> entry.getValue().isExpired());
        return new ConcurrentHashMap<>(activeSessions);
    }

    public int getAutoLockTimeout() {
        return prefs.getInt(Constants.PREF_REMOTE_AUTH_AUTO_LOCK_TIMEOUT, 0);
    }

    public void setAutoLockTimeout(int minutes) {
        int safeMinutes = Math.max(0, Math.min(minutes, 24 * 60));
        prefs.edit().putInt(Constants.PREF_REMOTE_AUTH_AUTO_LOCK_TIMEOUT, safeMinutes).apply();
        FLog.i(TAG, "Auto-lock timeout updated");
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.encodeToString(bytes, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
        Arrays.fill(bytes, (byte) 0);
        return token;
    }

    private byte[] derivePasswordHash(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private boolean verifyPbkdf2(char[] password, String stored) throws Exception {
        String[] parts = stored.split("\\$", -1);
        if (parts.length != 3 || !"v2".equals(parts[0])) return false;
        byte[] salt = Base64.decode(parts[1], Base64.NO_WRAP);
        byte[] expected = Base64.decode(parts[2], Base64.NO_WRAP);
        byte[] actual = null;
        try {
            if (salt.length != SALT_BYTES || expected.length != HASH_BITS / 8) return false;
            actual = derivePasswordHash(password, salt);
            return MessageDigest.isEqual(actual, expected);
        } finally {
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(expected, (byte) 0);
            if (actual != null) Arrays.fill(actual, (byte) 0);
        }
    }

    private void upgradeLegacyPassword(char[] password) {
        setPassword(new String(password));
    }

    private static byte[] hexToBytes(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }
}
