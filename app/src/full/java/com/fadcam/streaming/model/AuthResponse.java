package com.fadcam.streaming.model;

/**
 * Response model for authentication endpoints.
 * Contains status, token, message, and additional metadata.
 */
public class AuthResponse {
    private final boolean success;
    private final String token;
    private final String message;
    private final long expiresAt;
    
    public AuthResponse(boolean success, String token, String message, long expiresAt) {
        this.success = success;
        this.token = token;
        this.message = message;
        this.expiresAt = expiresAt;
    }
    
    public AuthResponse(boolean success, String message) {
        this(success, null, message, 0);
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public String getToken() {
        return token;
    }
    
    public String getMessage() {
        return message;
    }
    
    public long getExpiresAt() {
        return expiresAt;
    }
    
    /**
     * Convert to JSON string for HTTP response
     */
    public String toJson() {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"success\":").append(success).append(",");
        json.append("\"message\":\"").append(escapeJson(message)).append("\"");
        
        if (token != null && !token.isEmpty()) {
            json.append(",\"token\":\"").append(token).append("\"");
            json.append(",\"expiresAt\":").append(expiresAt);
        }
        
        json.append("}");
        return json.toString();
    }
    
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                 .replace("\"", "\\\"")
                 .replace("\n", "\\n")
                 .replace("\r", "\\r");
    }
    
    /**
     * Factory methods for common responses
     */
    public static AuthResponse success(String token, long expiresAt) {
        return new AuthResponse(true, token, "Authentication successful", expiresAt);
    }
    
    public static AuthResponse failure(String message) {
        return new AuthResponse(false, message);
    }
    
    public static AuthResponse invalidCredentials() {
        return failure("Invalid password");
    }
    
    public static AuthResponse unauthorized() {
        return failure("Unauthorized - invalid or expired token");
    }
    
    public static AuthResponse authDisabled() {
        return new AuthResponse(true, null, "Authentication is disabled", 0);
    }
}
