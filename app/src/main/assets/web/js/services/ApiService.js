/**
 * ApiService - HTTP API wrapper for FadCam Server
 */
class ApiService {
    constructor(baseUrl) {
        this.baseUrl = baseUrl || CONFIG.BASE_URL;
        this.statusCache = null;
        this.lastFetchTime = 0;
    }
    
    /**
     * Get headers with auth token injected
     */
    getHeaders() {
        const headers = {
            'Content-Type': 'application/json'
        };
        
        // Inject auth token if available
        if (typeof authService !== 'undefined') {
            const authHeaders = authService.getAuthHeaders();
            Object.assign(headers, authHeaders);
        }
        
        return headers;
    }
    
    /**
     * GET /status - Fetch server status
     * @returns {Promise<Object>} Status data
     */
    async getStatus() {
        try {
            const startTime = performance.now();
            console.log(`🌐 [/status] Dashboard sending request to ${this.baseUrl}/status`);
            
            const response = await fetch(`${this.baseUrl}/status`, {
                method: 'GET',
                headers: this.getHeaders()
            });
            
            const fetchTime = performance.now() - startTime;
            console.log(`📡 [/status] Response received in ${fetchTime.toFixed(2)}ms, status: ${response.status}, size: ${response.headers.get('content-length') || 'unknown'} bytes`);
            
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }
            
            const parseStart = performance.now();
            this.statusCache = await response.json();
            const parseTime = performance.now() - parseStart;
            
            this.lastFetchTime = Date.now();
            console.log(`✅ [/status] JSON parsed in ${parseTime.toFixed(2)}ms, state: ${this.statusCache.streaming ? 'streaming' : 'idle'}, clients: ${this.statusCache.totalConnectedClients}`);
            
            return this.statusCache;
        } catch (error) {
            console.error('❌ [/status] Failed to fetch status:', error);
            throw error;
        }
    }
    
    /**
     * POST /torch/toggle - Toggle flashlight
     * @returns {Promise<Object>} Response data
     */
    async toggleTorch() {
        try {
            const response = await fetch(`${this.baseUrl}/torch/toggle`, {
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
        try {
            console.log(`[ApiService] POST ${endpoint} with data:`, data);
            const response = await fetch(`${this.baseUrl}${endpoint}`, {
                method: 'POST',
                headers: this.getHeaders(),
                body: JSON.stringify(data)
            });
            
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }
            
            const responseData = await response.json();
            console.log(`[ApiService] POST ${endpoint} response:`, responseData);
            return responseData;
        } catch (error) {
            console.error(`POST ${endpoint} failed:`, error);
            throw error;
        }
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
}

// Global singleton
const apiService = new ApiService();
