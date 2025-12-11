/**
 * AuthService - Handles authentication for FadCam Remote
 * Manages session tokens, login/logout, and auth state
 */
class AuthService {
    constructor() {
        this.token = null;
        this.authEnabled = false;
        this.authenticated = false;
        this.rememberMe = false;
        this.onAuthStateChange = null;
        
        this.loadFromStorage();
    }
    
    /**
     * Load token and remember me preference from localStorage
     */
    loadFromStorage() {
        try {
            this.token = localStorage.getItem('fadcam_auth_token');
            this.rememberMe = localStorage.getItem('fadcam_remember_me') === 'true';
            
            if (this.token && !this.rememberMe) {
                // Token exists but remember me is off - clear it
                this.clearToken();
            }
        } catch (e) {
            console.error('[AuthService] Error loading from storage:', e);
        }
    }
    
    /**
     * Save token to localStorage
     */
    saveToken(token, rememberMe = false) {
        this.token = token;
        this.rememberMe = rememberMe;
        
        try {
            if (rememberMe) {
                localStorage.setItem('fadcam_auth_token', token);
                localStorage.setItem('fadcam_remember_me', 'true');
            } else {
                // Store in sessionStorage for current session only
                sessionStorage.setItem('fadcam_auth_token', token);
                localStorage.removeItem('fadcam_auth_token');
                localStorage.setItem('fadcam_remember_me', 'false');
            }
        } catch (e) {
            console.error('[AuthService] Error saving token:', e);
        }
    }
    
    /**
     * Clear stored token
     */
    clearToken() {
        this.token = null;
        this.authenticated = false;
        
        try {
            localStorage.removeItem('fadcam_auth_token');
            sessionStorage.removeItem('fadcam_auth_token');
            localStorage.removeItem('fadcam_remember_me');
        } catch (e) {
            console.error('[AuthService] Error clearing token:', e);
        }
    }
    
    /**
     * Check auth status with server
     */
    async checkAuthStatus() {
        try {
            const response = await fetch('/auth/check', {
                method: 'GET',
                headers: this.getAuthHeaders()
            });
            
            if (!response.ok) {
                console.warn('[AuthService] Auth check failed:', response.status);
                return { authEnabled: false, authenticated: false };
            }
            
            const data = await response.json();
            this.authEnabled = data.authEnabled || false;
            this.authenticated = data.authenticated || false;
            
            console.log('[AuthService] Auth status:', data);
            
            // If token is invalid but we had one, clear it
            if (this.token && !data.tokenValid) {
                console.log('[AuthService] Token invalid, clearing');
                this.clearToken();
            }
            
            return data;
        } catch (error) {
            console.error('[AuthService] Error checking auth:', error);
            return { authEnabled: false, authenticated: false };
        }
    }
    
    /**
     * Login with password
     */
    async login(password, rememberMe = false) {
        try {
            const response = await fetch('/auth/login', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({ password })
            });
            
            const data = await response.json();
            
            if (!response.ok || !data.success) {
                console.warn('[AuthService] Login failed:', data.message);
                return { success: false, message: data.message || 'Login failed' };
            }
            
            // If auth is disabled, no token needed
            if (!data.token) {
                console.log('[AuthService] Auth disabled, no token required');
                this.authenticated = true;
                this.authEnabled = false;
                this.notifyAuthStateChange();
                return { success: true, message: 'Authentication disabled' };
            }
            
            // Save token
            this.saveToken(data.token, rememberMe);
            this.authenticated = true;
            this.authEnabled = true;
            
            console.log('[AuthService] ✅ Login successful');
            this.notifyAuthStateChange();
            
            return { success: true, message: 'Login successful', token: data.token };
        } catch (error) {
            console.error('[AuthService] Login error:', error);
            return { success: false, message: 'Network error' };
        }
    }
    
    /**
     * Logout and invalidate session
     */
    async logout() {
        try {
            if (this.token) {
                await fetch('/auth/logout', {
                    method: 'POST',
                    headers: this.getAuthHeaders()
                });
            }
            
            this.clearToken();
            console.log('[AuthService] Logged out');
            this.notifyAuthStateChange();
            
            return { success: true };
        } catch (error) {
            console.error('[AuthService] Logout error:', error);
            // Clear token anyway even if request failed
            this.clearToken();
            this.notifyAuthStateChange();
            return { success: true };
        }
    }
    
    /**
     * Change password
     */
    async changePassword(oldPassword, newPassword) {
        try {
            const response = await fetch('/auth/changePassword', {
                method: 'POST',
                headers: {
                    ...this.getAuthHeaders(),
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({ oldPassword, newPassword })
            });
            
            const data = await response.json();
            
            if (!response.ok || !data.success) {
                return { success: false, message: data.message || 'Failed to change password' };
            }
            
            console.log('[AuthService] Password changed successfully');
            
            // Clear token since all sessions are invalidated
            this.clearToken();
            this.notifyAuthStateChange();
            
            return { success: true, message: data.message };
        } catch (error) {
            console.error('[AuthService] Change password error:', error);
            return { success: false, message: 'Network error' };
        }
    }
    
    /**
     * Get auth headers for API requests
     */
    getAuthHeaders() {
        const headers = {};
        
        // Try sessionStorage first (for non-remember-me sessions)
        let token = this.token;
        if (!token) {
            try {
                token = sessionStorage.getItem('fadcam_auth_token');
            } catch (e) {
                // Ignore
            }
        }
        
        if (token) {
            headers['Authorization'] = `Bearer ${token}`;
        }
        
        return headers;
    }
    
    /**
     * Check if user is authenticated
     */
    isAuthenticated() {
        return this.authenticated || !this.authEnabled;
    }
    
    /**
     * Check if auth is required
     */
    isAuthRequired() {
        return this.authEnabled && !this.authenticated;
    }
    
    /**
     * Notify listeners of auth state change
     */
    notifyAuthStateChange() {
        if (this.onAuthStateChange) {
            this.onAuthStateChange(this.isAuthenticated(), this.authEnabled);
        }
    }
    
    /**
     * Set auth state change listener
     */
    setAuthStateChangeListener(callback) {
        this.onAuthStateChange = callback;
    }
}

// Create global instance
const authService = new AuthService();
