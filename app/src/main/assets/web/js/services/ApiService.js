/**
 * ApiService - Unified HTTP API wrapper for FadCam Dashboard
 * 
 * Supports two modes (single source of truth):
 * - Local Mode: Calls phone's HTTP server directly (192.168.x.x)
 * - Cloud Mode: Calls relay server for status, commands go through relay queue
 * 
 * The mode is automatically detected based on FadCamRemote.isCloudMode()
 * 
 * Architecture:
 * ┌─────────────────────────────────────────────────────────────────┐
 * │                         Dashboard                               │
 * │                      (DashboardViewModel)                       │
 * │                            ↓                                    │
 * │                       ApiService                                │
 * │                     (mode detection)                            │
 * │                    ↙            ↘                              │
 * │   [Local Mode]                  [Cloud Mode]                   │
 * │   Phone HTTP                    Relay Server                   │
 * │   192.168.x.x                   live.fadseclab.com:8443        │
 * └─────────────────────────────────────────────────────────────────┘
 */
class ApiService {
    constructor(baseUrl) {
        this.localBaseUrl = baseUrl || CONFIG.BASE_URL;
        this.relayBaseUrl = 'https://live.fadseclab.com:8443';
        this.statusCache = null;
        this.lastFetchTime = 0;
        this.streamContext = null; // Set from FadCamRemote
    }
    
    // =========================================================================
    // Mode Detection
    // =========================================================================
    
    /**
     * Check if we're in cloud mode
     * Cloud mode = accessed via web (fadseclab.com) with valid stream context
     */
    isCloudMode() {
        return typeof FadCamRemote !== 'undefined' && 
               typeof FadCamRemote.isCloudMode === 'function' && 
               FadCamRemote.isCloudMode();
    }
    
    /**
     * Get the appropriate base URL for current mode
     */
    getBaseUrl() {
        return this.isCloudMode() ? this.relayBaseUrl : this.localBaseUrl;
    }
    
    /**
     * Set stream context (called from FadCamRemote after auth)
     */
    setStreamContext(ctx) {
        this.streamContext = ctx;
        console.log('[ApiService] Stream context set:', ctx);
    }
    
    /**
     * Get mode label for logging
     */
    getModeLabel() {
        return this.isCloudMode() ? '☁️' : '📱';
    }
    
    // =========================================================================
    // Headers
    // =========================================================================
    
    /**
     * Get headers with auth token injected
     */
    getHeaders() {
        const headers = {
            'Content-Type': 'application/json'
        };
        
        // Inject auth token if available (local mode)
        if (typeof authService !== 'undefined') {
            const authHeaders = authService.getAuthHeaders();
            Object.assign(headers, authHeaders);
        }
        
        // Add cloud headers if in cloud mode
        if (this.isCloudMode() && this.streamContext) {
            headers['X-Device-Id'] = this.streamContext.deviceId;
            headers['X-User-Id'] = this.streamContext.userId;
        }
        
        return headers;
    }
    
    // =========================================================================
    // Status API
    // =========================================================================
    
    /**
     * GET /status - Fetch server status
     * In cloud mode, returns cached status from relay (or mock status if relay endpoint not ready)
     * @returns {Promise<Object>} Status data
     */
    async getStatus() {
        if (this.isCloudMode()) {
            return this._getCloudStatus();
        }
        return this._getLocalStatus();
    }
    
    /**
     * Local mode: Fetch status from phone HTTP server
     */
    async _getLocalStatus() {
        try {
            const startTime = performance.now();
            console.log(`📱 [/status] Dashboard sending request to ${this.localBaseUrl}/status`);
            
            const response = await fetch(`${this.localBaseUrl}/status`, {
                method: 'GET',
                headers: this.getHeaders()
            });
            
            const fetchTime = performance.now() - startTime;
            console.log(`📡 [/status] Response received in ${fetchTime.toFixed(2)}ms, status: ${response.status}`);
            
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }
            
            this.statusCache = await response.json();
            this.statusCache.cloudMode = false;
            this.lastFetchTime = Date.now();
            
            console.log(`✅ [/status] 📱 Local: state=${this.statusCache.streaming ? 'streaming' : 'idle'}, clients=${this.statusCache.totalConnectedClients}`);
            
            return this.statusCache;
        } catch (error) {
            console.error('❌ [/status] Failed to fetch local status:', error);
            throw error;
        }
    }
    
    /**
     * Cloud mode: Fetch status from relay server
     * TODO: Implement actual relay /api/status/{device_id} endpoint
     * For now, returns mock status based on stream availability
     */
    async _getCloudStatus() {
        try {
            console.log(`☁️ [/status] Checking cloud status for device:`, this.streamContext?.deviceId);
            
            if (!this.streamContext) {
                return this._getOfflineStatus('Not authenticated');
            }
            
            // TODO: Replace with actual relay status endpoint when available
            // const response = await fetch(`${this.relayBaseUrl}/api/status/${this.streamContext.deviceId}`, ...);
            
            // For now, check if HLS playlist exists (indicates phone is uploading)
            const playlistAvailable = await this._checkPlaylistAvailable();
            
            if (playlistAvailable) {
                this.statusCache = this._getStreamingStatus();
            } else {
                this.statusCache = this._getWaitingStatus();
            }
            
            this.lastFetchTime = Date.now();
            console.log(`✅ [/status] ☁️ Cloud: state=${this.statusCache.state}, message=${this.statusCache.message}`);
            
            return this.statusCache;
            
        } catch (error) {
            console.error('❌ [/status] Failed to get cloud status:', error);
            return this._getOfflineStatus(error.message);
        }
    }
    
    /**
     * Check if HLS playlist is available on relay
     */
    async _checkPlaylistAvailable() {
        if (!this.streamContext?.userId || !this.streamContext?.deviceId) {
            return false;
        }
        
        try {
            const playlistUrl = `${this.relayBaseUrl}/stream/${this.streamContext.userId}/${this.streamContext.deviceId}/live.m3u8`;
            const response = await fetch(playlistUrl, {
                method: 'HEAD',
                headers: this.getHeaders()
            });
            return response.ok;
        } catch (e) {
            return false;
        }
    }
    
    // =========================================================================
    // Command APIs
    // =========================================================================
    
    /**
     * POST /torch/toggle - Toggle flashlight
     * @returns {Promise<Object>} Response data
     */
    async toggleTorch() {
        if (this.isCloudMode()) {
            return this._sendCloudCommand('torch/toggle', {});
        }
        
        try {
            const response = await fetch(`${this.localBaseUrl}/torch/toggle`, {
                method: 'POST',
                headers: this.getHeaders()
            });
            
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }
            
            return await response.json();
        } catch (error) {
            console.error('Failed to toggle torch:', error);
            throw error;
        }
    }
    
    /**
     * Generic POST request
     * @param {string} endpoint - API endpoint path
     * @param {Object} data - Request body data
     * @returns {Promise<Object>} Response data
     */
    async post(endpoint, data = {}) {
        if (this.isCloudMode()) {
            return this._sendCloudCommand(endpoint, data);
        }
        
        try {
            console.log(`📱 [POST] ${endpoint}`, data);
            const response = await fetch(`${this.localBaseUrl}${endpoint}`, {
                method: 'POST',
                headers: this.getHeaders(),
                body: JSON.stringify(data)
            });
            
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }
            
            const responseData = await response.json();
            console.log(`✅ [POST] ${endpoint} response:`, responseData);
            return responseData;
        } catch (error) {
            console.error(`❌ [POST] ${endpoint} failed:`, error);
            throw error;
        }
    }
    
    /**
     * Cloud mode: Send command through relay to phone
     * TODO: Implement actual relay command queue
     */
    async _sendCloudCommand(endpoint, data) {
        console.warn(`☁️ [COMMAND] ${endpoint} - Cloud commands not yet implemented`);
        
        // TODO: When relay command endpoint is ready:
        // const response = await fetch(`${this.relayBaseUrl}/api/command/${this.streamContext.deviceId}`, {
        //     method: 'POST',
        //     headers: this.getHeaders(),
        //     body: JSON.stringify({ endpoint, data })
        // });
        
        throw new Error('Cloud commands coming soon! This feature is not yet available.');
    }
    
    // =========================================================================
    // Cloud Status Helpers
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
    
    // =========================================================================
    // HLS URL
    // =========================================================================
    
    /**
     * Get HLS stream URL for video player
     */
    getHlsUrl() {
        if (this.isCloudMode()) {
            if (!this.streamContext?.userId || !this.streamContext?.deviceId) {
                return null;
            }
            return `${this.relayBaseUrl}/stream/${this.streamContext.userId}/${this.streamContext.deviceId}/live.m3u8`;
        }
        return `${this.localBaseUrl}${CONFIG.ENDPOINTS.HLS}`;
    }
    
    // =========================================================================
    // Cache
    // =========================================================================
    
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
}

// Global singleton
const apiService = new ApiService();

/**
 * Initialize ApiService stream context
 * Called from FadCamRemote after successful auth
 */
function initCloudApiService(streamContext) {
    apiService.setStreamContext(streamContext);
    console.log('[ApiService] Initialized cloud mode with stream context');
    return apiService;
}

/**
 * Get the API service (for backward compatibility)
 * Now just returns apiService since it handles both modes
 */
function getApiService() {
    return apiService;
}
