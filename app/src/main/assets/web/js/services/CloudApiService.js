/**
 * CloudApiService - Cloud API wrapper for FadCam Dashboard
 * 
 * Used when dashboard is accessed from web (fadseclab.com) instead of 
 * phone's local server. Connects to the relay server for status and
 * sends commands through the relay to reach the phone.
 * 
 * Architecture:
 * - Phone → Relay (uploads HLS + status via PUT/POST)
 * - Dashboard ← Relay (gets HLS + status via GET)
 * - Dashboard → Relay → Phone (commands)
 * 
 * Relay server: https://live.fadseclab.com:8443
 */
class CloudApiService {
    constructor() {
        this.relayUrl = 'https://live.fadseclab.com:8443';
        this.statusCache = null;
        this.lastFetchTime = 0;
        this.streamContext = null;
    }
    
    /**
     * Set stream context from FadCamRemote after auth
     * @param {Object} ctx - Stream context with deviceId, userId, etc.
     */
    setStreamContext(ctx) {
        this.streamContext = ctx;
        console.log('[CloudApiService] Stream context set:', ctx);
    }
    
    /**
     * Get stored session token for auth headers
     */
    getAuthToken() {
        try {
            const session = JSON.parse(localStorage.getItem('fadcam_session') || '{}');
            // The session has user_id which we can use, but we need actual JWT
            // For now, use the session hint data - proper JWT exchange comes in Step 6.7
            return session.user_id ? 'session-hint' : null;
        } catch (e) {
            return null;
        }
    }
    
    /**
     * Get headers for relay API calls
     */
    getHeaders() {
        const headers = {
            'Content-Type': 'application/json'
        };
        
        const token = this.getAuthToken();
        if (token && token !== 'session-hint') {
            headers['Authorization'] = `Bearer ${token}`;
        }
        
        if (this.streamContext?.deviceId) {
            headers['X-Device-Id'] = this.streamContext.deviceId;
        }
        
        if (this.streamContext?.userId) {
            headers['X-User-Id'] = this.streamContext.userId;
        }
        
        return headers;
    }
    
    /**
     * GET /status - Fetch status from relay
     * 
     * Note: Relay doesn't have status endpoint yet.
     * For now, returns mock status based on stream availability.
     * Real status endpoint will be added in Step 6.7.
     * 
     * @returns {Promise<Object>} Status data
     */
    async getStatus() {
        try {
            console.log('[CloudApiService] Getting status for device:', this.streamContext?.deviceId);
            
            // TODO: Replace with actual relay status endpoint in Step 6.7
            // For now, return connected status based on auth success
            if (!this.streamContext) {
                return this._getOfflineStatus('Not authenticated');
            }
            
            // Check if HLS playlist exists (indicates phone is uploading)
            const playlistAvailable = await this._checkPlaylistAvailable();
            
            if (playlistAvailable) {
                this.statusCache = this._getStreamingStatus();
            } else {
                this.statusCache = this._getWaitingStatus();
            }
            
            this.lastFetchTime = Date.now();
            return this.statusCache;
            
        } catch (error) {
            console.error('[CloudApiService] Failed to get status:', error);
            return this._getOfflineStatus(error.message);
        }
    }
    
    /**
     * Check if HLS playlist is available on relay
     * This indicates the phone is actively uploading
     */
    async _checkPlaylistAvailable() {
        if (!this.streamContext?.userId || !this.streamContext?.deviceId) {
            return false;
        }
        
        try {
            const playlistUrl = `${this.relayUrl}/stream/${this.streamContext.userId}/${this.streamContext.deviceId}/live.m3u8`;
            const response = await fetch(playlistUrl, {
                method: 'HEAD',
                headers: this.getHeaders()
            });
            
            console.log('[CloudApiService] Playlist check:', response.status);
            return response.ok;
        } catch (e) {
            console.log('[CloudApiService] Playlist check failed:', e.message);
            return false;
        }
    }
    
    /**
     * Get HLS stream URL for video player
     */
    getHlsUrl() {
        if (!this.streamContext?.userId || !this.streamContext?.deviceId) {
            return null;
        }
        return `${this.relayUrl}/stream/${this.streamContext.userId}/${this.streamContext.deviceId}/live.m3u8`;
    }
    
    /**
     * POST /torch/toggle - Toggle flashlight via relay
     * 
     * Note: Commands not implemented yet.
     * Will be added in Step 6.8 with command queue.
     * 
     * @returns {Promise<Object>} Response data
     */
    async toggleTorch() {
        console.log('[CloudApiService] toggleTorch - NOT IMPLEMENTED (Step 6.8)');
        // TODO: Implement command queue in Step 6.8
        throw new Error('Cloud commands not yet implemented. Coming soon!');
    }
    
    /**
     * Generic POST request (for commands)
     * @param {string} endpoint - API endpoint path
     * @param {Object} data - Request body data
     * @returns {Promise<Object>} Response data
     */
    async post(endpoint, data = {}) {
        console.log(`[CloudApiService] POST ${endpoint} - NOT IMPLEMENTED (Step 6.8)`);
        // TODO: Implement command queue in Step 6.8
        throw new Error('Cloud commands not yet implemented. Coming soon!');
    }
    
    /**
     * Get cached status (no network call)
     * @returns {Object|null} Cached status or null
     */
    getCachedStatus() {
        return this.statusCache;
    }
    
    /**
     * Check if cache is fresh (< 5 seconds old)
     * @returns {boolean} True if cache is fresh
     */
    isCacheFresh() {
        if (!this.statusCache || !this.lastFetchTime) return false;
        return (Date.now() - this.lastFetchTime) < 5000;
    }
    
    // =========================================================================
    // Status Response Builders
    // =========================================================================
    
    _getStreamingStatus() {
        return {
            state: 'streaming',
            streaming: true,
            recording: false,
            uptime: 0,
            totalConnectedClients: 1,
            torch: false,
            camera: 'back',
            quality: 'HD',
            cloudMode: true,
            deviceName: this.streamContext?.deviceName || 'Unknown Device',
            message: 'Connected to cloud stream'
        };
    }
    
    _getWaitingStatus() {
        return {
            state: 'waiting',
            streaming: false,
            recording: false,
            uptime: 0,
            totalConnectedClients: 0,
            torch: false,
            camera: 'back',
            quality: 'HD',
            cloudMode: true,
            deviceName: this.streamContext?.deviceName || 'Unknown Device',
            message: 'Waiting for device to start streaming...'
        };
    }
    
    _getOfflineStatus(reason) {
        return {
            state: 'offline',
            streaming: false,
            recording: false,
            uptime: 0,
            totalConnectedClients: 0,
            cloudMode: true,
            error: reason,
            message: `Offline: ${reason}`
        };
    }
}

// Global singleton (will be initialized when cloud mode is detected)
let cloudApiService = null;

/**
 * Initialize CloudApiService with stream context
 * Called from fadcam-remote.js after successful auth
 */
function initCloudApiService(streamContext) {
    cloudApiService = new CloudApiService();
    cloudApiService.setStreamContext(streamContext);
    console.log('[CloudApiService] Initialized for cloud mode');
    return cloudApiService;
}

/**
 * Get the appropriate API service based on mode
 * Returns cloudApiService if in cloud mode, apiService otherwise
 */
function getApiService() {
    // Check if we're in cloud streaming mode
    if (typeof FadCamRemote !== 'undefined' && FadCamRemote.isCloudMode && FadCamRemote.isCloudMode()) {
        if (!cloudApiService) {
            console.warn('[CloudApiService] Cloud mode but service not initialized');
        }
        return cloudApiService || apiService;
    }
    return apiService;
}
