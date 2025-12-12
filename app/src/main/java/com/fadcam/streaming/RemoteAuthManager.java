package com.fadcam.streaming;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.fadcam.Constants;
import com.fadcam.streaming.model.SessionToken;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages authentication sessions for FadCam Remote access.
 * Handles token generation, validation, storage, and session lifecycle.
 * Thread-safe singleton implementation.
 */
public class RemoteAuthManager {
    private static final String TAG = "RemoteAuthManager";
    private static RemoteAuthManager instance;
    
    private final Context context;
    private final SharedPreferences prefs;
    private final Map<String, SessionToken> activeSessions;
    private final SecureRandom secureRandom;
    private volatile boolean sessionsJustCleared = false;
    
    private RemoteAuthManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        this.activeSessions = new ConcurrentHashMap<>();
        this.secureRandom = new SecureRandom();
        loadSessionsFromStorage();
    }
    
    public static synchronized RemoteAuthManager getInstance(Context context) {
        if (instance == null) {
            instance = new RemoteAuthManager(context);
        }
        return instance;
    }
    
    /**
     * Check if authentication is enabled
     */
    public boolean isAuthEnabled() {
        return prefs.getBoolean(Constants.PREF_REMOTE_AUTH_ENABLED, false);
    }
    
    /**
     * Enable or disable authentication
     */
    public void setAuthEnabled(boolean enabled) {
        prefs.edit().putBoolean(Constants.PREF_REMOTE_AUTH_ENABLED, enabled).apply();
        if (!enabled) {
            clearAllSessions();
        }
        Log.i(TAG, "Authentication " + (enabled ? "enabled" : "disabled"));
    }
    
    /**
     * Set password (stores SHA-256 hash)
     */
    public boolean setPassword(String password) {
        if (password == null) {
            Log.w(TAG, "Password is null");
            return false;
        }
        
        // Trim whitespace
        password = password.trim();
        
        if (password.length() < Constants.REMOTE_AUTH_MIN_PASSWORD_LENGTH ||
            password.length() > Constants.REMOTE_AUTH_MAX_PASSWORD_LENGTH) {
            Log.w(TAG, "Invalid password length: " + password.length());
            return false;
        }
        
        String hash = hashPassword(password);
        if (hash == null) {
            return false;
        }
        
        prefs.edit().putString(Constants.PREF_REMOTE_AUTH_PASSWORD_HASH, hash).apply();
        Log.i(TAG, "Password updated successfully");
        
        // Invalidate all existing sessions when password changes
        clearAllSessions();
        return true;
    }
    
    /**
     * Verify password against stored hash
     */
    public boolean verifyPassword(String password) {
        if (password == null) {
            Log.w(TAG, "Password is null");
            return false;
        }
        
        // Trim whitespace
        password = password.trim();
        
        String storedHash = prefs.getString(Constants.PREF_REMOTE_AUTH_PASSWORD_HASH, null);
        if (storedHash == null) {
            Log.w(TAG, "No password set");
            return false;
        }
        
        String inputHash = hashPassword(password);
        boolean isValid = storedHash.equals(inputHash);
        
        if (!isValid) {
            Log.d(TAG, "Password verification failed: hash mismatch");
        }
        
        return isValid;
    }
    
    /**
     * Check if password is set
     */
    public boolean hasPassword() {
        String storedHash = prefs.getString(Constants.PREF_REMOTE_AUTH_PASSWORD_HASH, null);
        return storedHash != null && !storedHash.isEmpty();
    }
    
    /**
     * Generate new session token
     */
    public SessionToken createSession(String deviceInfo) {
        String token = generateToken();
        long now = System.currentTimeMillis();
        long expiresAt = now + Constants.REMOTE_AUTH_TOKEN_EXPIRY_MS;
        
        SessionToken session = new SessionToken(token, now, expiresAt, deviceInfo);
        activeSessions.put(token, session);
        saveSessionsToStorage();
        
        Log.i(TAG, "New session created: " + token.substring(0, 8) + "... (expires in 24h)");
        return session;
    }
    
    /**
     * Validate token and return session if valid
     */
    public SessionToken validateToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        
        SessionToken session = activeSessions.get(token);
        if (session == null) {
            Log.d(TAG, "Token not found in active sessions");
            return null;
        }
        
        if (session.isExpired()) {
            Log.d(TAG, "Token expired");
            activeSessions.remove(token);
            saveSessionsToStorage();
            return null;
        }
        
        return session;
    }
    
    /**
     * Invalidate specific session
     */
    public void revokeSession(String token) {
        if (activeSessions.remove(token) != null) {
            saveSessionsToStorage();
            Log.i(TAG, "Session revoked: " + token.substring(0, 8) + "...");
        }
    }
    
    /**
     * Clear all active sessions
     */
    public void clearAllSessions() {
        int count = activeSessions.size();
        activeSessions.clear();
        saveSessionsToStorage();
        sessionsJustCleared = true;  // Set flag for real-time detection
        Log.i(TAG, "Cleared " + count + " session(s)");
    }
    
    /**
     * Check if sessions were just cleared and reset the flag
     */
    public boolean checkAndResetSessionsClearedFlag() {
        boolean wasClearedJustNow = sessionsJustCleared;
        sessionsJustCleared = false;
        return wasClearedJustNow;
    }
    
    /**
     * Get all active sessions
     */
    public Map<String, SessionToken> getActiveSessions() {
        // Remove expired sessions
        activeSessions.entrySet().removeIf(entry -> entry.getValue().isExpired());
        saveSessionsToStorage();
        return new ConcurrentHashMap<>(activeSessions);
    }
    
    /**
     * Get auto-lock timeout in minutes (0 = never)
     */
    public int getAutoLockTimeout() {
        return prefs.getInt(Constants.PREF_REMOTE_AUTH_AUTO_LOCK_TIMEOUT, 0);
    }
    
    /**
     * Set auto-lock timeout in minutes (0 = never)
     */
    public void setAutoLockTimeout(int minutes) {
        prefs.edit().putInt(Constants.PREF_REMOTE_AUTH_AUTO_LOCK_TIMEOUT, minutes).apply();
        Log.i(TAG, "Auto-lock timeout set to: " + (minutes == 0 ? "Never" : minutes + " minutes"));
    }
    
    /**
     * Generate cryptographically secure random token
     */
    private String generateToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }
    
    /**
     * Hash password using SHA-256
     */
    private String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes());
            
            // Convert to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "SHA-256 not available", e);
            return null;
        }
    }
    
    /**
     * Save active sessions to SharedPreferences
     */
    private void saveSessionsToStorage() {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        
        for (SessionToken session : activeSessions.values()) {
            if (!first) json.append(",");
            json.append(session.toJson());
            first = false;
        }
        
        json.append("]");
        prefs.edit().putString(Constants.PREF_REMOTE_AUTH_SESSIONS, json.toString()).apply();
    }
    
    /**
     * Load sessions from SharedPreferences
     */
    private void loadSessionsFromStorage() {
        String json = prefs.getString(Constants.PREF_REMOTE_AUTH_SESSIONS, "[]");
        
        if (json.equals("[]")) {
            return;
        }
        
        try {
            // Simple JSON array parsing
            json = json.substring(1, json.length() - 1); // Remove [ and ]
            
            if (json.trim().isEmpty()) {
                return;
            }
            
            // Split by },{
            String[] sessionJsons = json.split("\\},\\{");
            
            for (String sessionJson : sessionJsons) {
                // Add back braces if removed by split
                if (!sessionJson.startsWith("{")) sessionJson = "{" + sessionJson;
                if (!sessionJson.endsWith("}")) sessionJson = sessionJson + "}";
                
                SessionToken session = SessionToken.fromJson(sessionJson);
                if (session != null && session.isValid()) {
                    activeSessions.put(session.getToken(), session);
                }
            }
            
            Log.i(TAG, "Loaded " + activeSessions.size() + " active session(s)");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load sessions from storage", e);
            activeSessions.clear();
        }
    }
}
