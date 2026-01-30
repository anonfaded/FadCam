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
        if (this.isCloudMode()) {
            if (this.streamContext) {
                headers['X-Device-Id'] = this.streamContext.deviceId;
                headers['X-User-Id'] = this.streamContext.userId;
            }
            // Add stream access token for relay API calls
            const streamToken = this._getStreamToken();
            if (streamToken) {
                headers['Authorization'] = `Bearer ${streamToken}`;
            }
        }
        
        return headers;
    }
    
    /**
     * Get stream access token from FadCamRemote
     */
    _getStreamToken() {
        if (typeof FadCamRemote !== 'undefined' && typeof FadCamRemote.getStreamToken === 'function') {
            return FadCamRemote.getStreamToken();
        }
        return null;
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
     * Calls GET /api/status/{user_uuid}/{device_id}
     */
    async _getCloudStatus() {
        try {
            console.log(`☁️ [/status] Checking cloud status for device:`, this.streamContext?.deviceId);
            
            if (!this.streamContext) {
                return this._getOfflineStatus('Not authenticated');
            }
            
            const { userId, deviceId } = this.streamContext;
            if (!userId || !deviceId) {
                return this._getOfflineStatus('Missing user or device ID');
            }
            
            // Try to fetch status from relay
            const statusUrl = `${this.relayBaseUrl}/api/status/${userId}/${deviceId}`;
            console.log(`☁️ [/status] Fetching from relay: ${statusUrl}`);
            
            try {
                const response = await fetch(statusUrl, {
                    method: 'GET',
                    headers: this.getHeaders()
                });
                
                if (response.ok) {
                    // Phone has pushed status to relay
                    this.statusCache = await response.json();
                    this.statusCache.cloudMode = true;
                    this.lastFetchTime = Date.now();
                    console.log(`✅ [/status] ☁️ Cloud: state=${this.statusCache.state}, streaming=${this.statusCache.streaming}`);
                    return this.statusCache;
                } else if (response.status === 404) {
                    // Status file doesn't exist yet - phone hasn't pushed
                    console.log(`☁️ [/status] No status from phone yet, checking playlist...`);
                } else if (response.status === 401 || response.status === 403) {
                    console.error(`☁️ [/status] Auth failed: ${response.status}`);
                    return this._getOfflineStatus('Authentication failed');
                }
            } catch (fetchError) {
                console.warn(`☁️ [/status] Relay fetch failed:`, fetchError.message);
            }
            
            // Fallback: Check if HLS playlist exists (indicates phone is uploading)
            const playlistAvailable = await this._checkPlaylistAvailable();
            
            if (playlistAvailable) {
                this.statusCache = this._getStreamingStatus();
            } else {
                this.statusCache = this._getWaitingStatus();
            }
            
            this.lastFetchTime = Date.now();
            console.log(`✅ [/status] ☁️ Cloud (fallback): state=${this.statusCache.state}, message=${this.statusCache.message}`);
            
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
     * Cloud mode: Send command through Supabase Realtime for instant delivery.
     * Falls back to HTTP relay if Realtime is not available.
     * 
     * NEW: Uses Supabase Realtime broadcast for <200ms latency.
     * OLD: Used HTTP polling with 3 second delay.
     */
    async _sendCloudCommand(endpoint, data) {
        console.log(`☁️ [COMMAND] ${endpoint} - Sending via cloud`);
        
        if (!this.streamContext) {
            throw new Error('Not authenticated for cloud commands');
        }
        
        const { userId, deviceId } = this.streamContext;
        if (!userId || !deviceId) {
            throw new Error('Missing user or device ID');
        }
        
        // Convert endpoint to action (e.g., "torch/toggle" -> "torch_toggle")
        const action = endpoint.replace(/\//g, '_').replace(/^_/, '');
        
        // Try Supabase Realtime first (instant delivery)
        if (typeof realtimeCommandService !== 'undefined' && realtimeCommandService.isReady()) {
            try {
                const result = await realtimeCommandService.sendCommand(action, data);
                if (result.success) {
                    console.log(`☁️ [COMMAND] ⚡ INSTANT: ${action} (${result.latency}ms)`);
                    return {
                        success: true,
                        command_id: Date.now(),
                        instant: true,
                        latency: result.latency,
                        message: `Instant command delivered (${result.latency}ms)`
                    };
                }
                console.warn(`☁️ [COMMAND] Realtime failed, falling back to HTTP:`, result.error);
            } catch (e) {
                console.warn(`☁️ [COMMAND] Realtime error, falling back to HTTP:`, e.message);
            }
        } else {
            console.log(`☁️ [COMMAND] Realtime not ready, using HTTP relay`);
            
            // Initialize Realtime if not done yet
            if (typeof realtimeCommandService !== 'undefined' && !realtimeCommandService.isReady()) {
                realtimeCommandService.initialize(deviceId, userId).then(success => {
                    if (success) {
                        console.log(`☁️ [COMMAND] Realtime initialized for future commands`);
                    }
                });
            }
        }
        
        // Fallback: Send via HTTP relay (polling-based, 3 second delay)
        return this._sendCloudCommandViaRelay(endpoint, data, userId, deviceId);
    }
    
    /**
     * Fallback: Send command through HTTP relay.
     * Uses PUT /api/command/{user_uuid}/{device_id}/{cmd_id}
     * Phone polls this endpoint every 3 seconds.
     */
    async _sendCloudCommandViaRelay(endpoint, data, userId, deviceId) {
        // Generate unique command ID (millisecond timestamp)
        const cmdId = Date.now();
        
        // Build command payload
        const command = {
            action: endpoint.replace(/\//g, '_').replace(/^_/, ''),
            params: data,
            timestamp: cmdId,
            source: 'dashboard'
        };
        
        // Send command to relay
        const commandUrl = `${this.relayBaseUrl}/api/command/${userId}/${deviceId}/${cmdId}`;
        console.log(`☁️ [COMMAND] PUT ${commandUrl}`, command);
        
        try {
            const response = await fetch(commandUrl, {
                method: 'PUT',
                headers: this.getHeaders(),
                body: JSON.stringify(command)
            });
            
            if (!response.ok) {
                const errorText = await response.text();
                throw new Error(`HTTP ${response.status}: ${errorText}`);
            }
            
            console.log(`✅ [COMMAND] ${endpoint} queued successfully (id: ${cmdId})`);
            
            return {
                success: true,
                command_id: cmdId,
                instant: false,
                message: 'Command queued. Phone will execute on next poll (~3s).'
            };
            
        } catch (error) {
            console.error(`❌ [COMMAND] ${endpoint} failed:`, error);
            throw error;
        }
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
