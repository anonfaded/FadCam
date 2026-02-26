/**
 * ServerStatus - Data model for /status endpoint response
 * 
 * Includes staleness detection (Step 6.11) via lastUpdated timestamp
 */
class ServerStatus {
    constructor(data = {}) {
        this.update(data);
    }
    
    /**
     * Update model with new data
     * @param {Object} data - Raw status data from API
     */
    update(data) {
        // Timestamps for staleness detection (Step 6.11)
        this.lastUpdated = data.lastUpdated || 0;  // Unix timestamp from phone
        this.serverVersion = data.serverVersion || '1.0.0';  // For compatibility checks
        this.receivedAt = Date.now();  // When dashboard received this status
        this.cloudMode = data.cloudMode || false;  // Whether this is from relay/cloud
        
        // Core status
        this.state = data.state || 'offline';
        this.mode = data.mode || 'disabled';
        this.message = data.message || '';
        this.isRecording = data.isRecording || false;
        this.isPaused = data.isPaused || false;
        this.streaming = data.streaming || false;
        
        // Uptime details (nested object)
        this.uptimeDetails = data.uptimeDetails || {};
        this.uptimeSeconds = this.uptimeDetails.seconds || data.uptimeSeconds || 0;
        
        // Format uptime from the details if available
        if (!this.uptimeDetails.formattedUptime && this.uptimeDetails.formatted) {
            this.uptimeDetails.formattedUptime = this.uptimeDetails.formatted;
        }
        if (!this.uptimeDetails.sessionStartedTime && this.uptimeDetails.startTime) {
            this.uptimeDetails.sessionStartedTime = this.uptimeDetails.startTime;
        }
        if (!this.uptimeDetails.sessionStartedDate && this.uptimeDetails.startTimestamp) {
            const date = new Date(this.uptimeDetails.startTimestamp);
            this.uptimeDetails.sessionStartedDate = date.toLocaleDateString();
        }
        
        // Stream quality (nested object)
        if (data.streamQuality && typeof data.streamQuality === 'object') {
            this.qualityPreset = (data.streamQuality.preset || 'high').toUpperCase();
            this.bitrate = data.streamQuality.bitrate || '0 Mbps';
            this.fps = data.streamQuality.fpsCap || 0;
        } else {
            this.qualityPreset = 'HIGH';
            this.bitrate = '0 Mbps';
            this.fps = 0;
        }
        
        // Resolution from message or construct from width/height
        this.resolution = data.resolution || '0x0';
        this.videoCodec = data.videoCodec || 'unknown';
        this.isHevcCodec = this.videoCodec && this.videoCodec.toUpperCase().includes('HEVC');
        
        // Buffer info
        this.fragmentsBuffered = data.fragmentsBuffered || 0;
        this.bufferSizeMb = data.bufferSizeMb || 0;
        
        // Network (handle both object and string)
        if (data.networkHealth && typeof data.networkHealth === 'object') {
            this.networkHealth = data.networkHealth.status || 'unknown';
            this.networkDownloadMbps = data.networkHealth.downloadMbps ?? null;
            this.networkUploadMbps = data.networkHealth.uploadMbps ?? null;
            this.networkLatencyMs = data.networkHealth.latencyMs ?? null;
            this.networkLastMeasurementMs = data.networkHealth.lastMeasurementMs ?? 0;
        } else {
            this.networkHealth = data.networkHealth || 'unknown';
            this.networkDownloadMbps = null;
            this.networkUploadMbps = null;
            this.networkLatencyMs = null;
            this.networkLastMeasurementMs = 0;
        }
        
        // Battery (nested object)
        if (data.batteryDetails && typeof data.batteryDetails === 'object') {
            this.battery = {
                percent: data.batteryDetails.percent ?? -1,
                status: data.batteryDetails.status || 'unknown',
                consumed: data.batteryDetails.consumed !== undefined && data.batteryDetails.consumed !== null ? data.batteryDetails.consumed + '' : 'N/A',
                remainingHours: data.batteryDetails.remainingHours ?? -1,
                warning: data.batteryDetails.warning || false,
                warningThreshold: data.batteryDetails.warningThreshold || 20
            };
        } else {
            this.battery = {
                percent: -1,
                status: 'unknown',
                consumed: 'N/A',
                remainingHours: -1,
                warning: false,
                warningThreshold: 20
            };
        }
        
        // Connections
        this.activeConnections = data.activeConnections || 0;
        this.clientIps = data.clients ? data.clients.map(c => c.ip) : [];
        
        // Cloud viewers (viewers connected via relay, not directly to phone)
        // This is separate from activeConnections which counts direct connections
        this.cloudViewers = data.cloudViewers || 0;
        
        // Total viewers = local clients + cloud viewers
        this.totalConnectedClients = this.activeConnections + this.cloudViewers;
        
        // Store full clients array for modal display
        this.clients = data.clients || [];
        
        // Events/logs
        this.events = data.events || [];
        
        // Segments/buffer info
        this.hasInitSegment = data.hasInitSegment || false;
        
        // Torch state
        this.torchOn = data.torchState || false;
        
        // Volume state
        this.volume = data.volume ?? 0;
        this.maxVolume = data.maxVolume ?? 15;
        this.volumePercentage = data.volumePercentage ?? 0;
        
        // Alarm state (nested object)
        if (data.alarm && typeof data.alarm === 'object') {
            this.alarm = {
                isRinging: data.alarm.isRinging || false,
                sound: data.alarm.sound || 'office_phone.mp3',
                durationMs: data.alarm.durationMs ?? -1,
                remainingMs: data.alarm.remainingMs ?? 0
            };
        } else {
            this.alarm = {
                isRinging: false,
                sound: 'office_phone.mp3',
                durationMs: -1,
                remainingMs: 0
            };
        }
        
        // Stats
        this.dataTransferredMb = data.totalDataTransferredMb || 0;
        this.uptimeSeconds = data.uptimeSeconds || 0;
        
        // Parse uptime details (nested object)
        if (data.uptimeDetails && typeof data.uptimeDetails === 'object') {
            this.uptimeFormatted = data.uptimeDetails.formatted || '0s';
            this.uptimeStartTime = data.uptimeDetails.startTime || 'Not started';
            this.uptimeStartDate = data.uptimeDetails.startDate || 'Not started';
            this.uptimeStartTimestamp = data.uptimeDetails.startTimestamp || 0;
        } else {
            this.uptimeFormatted = '0s';
            this.uptimeStartTime = 'Not started';
            this.uptimeStartDate = 'Not started';
            this.uptimeStartTimestamp = 0;
        }
        
        // Authentication state
        this.authEnabled = data.authEnabled || false;
        this.authTimeoutMs = data.authTimeoutMs || 0;  // 0 means never auto-lock
        this.authSessionsCount = data.authSessionsCount || 0;
        this.authSessionsCleared = data.authSessionsCleared || false;  // Flag for logout all
        
        // Parse memory and storage from strings
        // Memory format from backend: "75% (1024/1366 MB)"
        if (data.memoryUsage) {
            // Extract percentage: "75% (1024/1366 MB)" → 75
            const percentMatch = data.memoryUsage.match(/(\d+)%/);
            this.memoryPercent = percentMatch ? parseInt(percentMatch[1]) : 0;
            
            // Extract used/total: "75% (1024/1366 MB)" → [1024, 1366]
            const memMatch = data.memoryUsage.match(/\((\d+)\/(\d+)\s*MB\)/);
            if (memMatch) {
                this.memoryUsedMb = parseInt(memMatch[1]);
                this.memoryTotalMb = parseInt(memMatch[2]);
            } else {
                this.memoryUsedMb = 0;
                this.memoryTotalMb = 0;
            }
        } else {
            this.memoryPercent = 0;
            this.memoryUsedMb = 0;
            this.memoryTotalMb = 0;
        }
        
        if (data.storage) {
            // Parse "1.4/50.3 GB" format
            const parts = data.storage.split('/');
            if (parts.length === 2) {
                this.storageUsedGb = parseFloat(parts[0].trim());
                this.storageTotalGb = parseFloat(parts[1].trim());
            } else {
                this.storageUsedGb = 0;
                this.storageTotalGb = 0;
            }
        } else {
            this.storageUsedGb = 0;
            this.storageTotalGb = 0;
        }
        
        // Store raw memory string for display
        this.memory = data.memoryUsage || '—';
        
        // Timestamp
        this.lastUpdate = Date.now();
    }
    
    /**
     * Check if status is ready for streaming
     * @returns {boolean}
     */
    isReady() {
        return this.state === CONFIG.STATES.READY;
    }
    
    /**
     * Check if status indicates buffering/initializing
     * @returns {boolean}
     */
    isLoading() {
        return this.state === CONFIG.STATES.BUFFERING || 
               this.state === CONFIG.STATES.INITIALIZING;
    }
    
    /**
     * Get formatted uptime - calculates real-time uptime from startTimestamp
     * This eliminates the delay between phone status push and dashboard display
     * @returns {string}
     */
    getFormattedUptime() {
        // Use startTimestamp for real-time uptime calculation
        // This fixes the 30-60 second delay between phone and dashboard
        if (this.uptimeStartTimestamp && this.uptimeStartTimestamp > 0) {
            const realtimeSeconds = Math.floor((Date.now() - this.uptimeStartTimestamp) / 1000);
            return Formatter.formatUptime(realtimeSeconds);
        }
        // Fallback to server-provided uptime if no timestamp available
        return Formatter.formatUptime(this.uptimeSeconds);
    }
    
    /**
     * Get real-time uptime in seconds (calculated from startTimestamp)
     * @returns {number}
     */
    getRealtimeUptimeSeconds() {
        if (this.uptimeStartTimestamp && this.uptimeStartTimestamp > 0) {
            return Math.floor((Date.now() - this.uptimeStartTimestamp) / 1000);
        }
        return this.uptimeSeconds;
    }
    
    /**
     * Get formatted data transferred
     * @returns {string}
     */
    getFormattedDataTransferred() {
        return Formatter.formatBytes(this.dataTransferredMb * 1024 * 1024);
    }
    
    /**
     * Get formatted storage (used/total)
     * @returns {string}
     */
    getFormattedStorage() {
        if (this.storageUsedGb > 0 && this.storageTotalGb > 0) {
            return `${this.storageUsedGb.toFixed(1)}/${this.storageTotalGb.toFixed(1)} GB`;
        }
        return '—';
    }
    
    /**
     * Get formatted memory usage
     * @returns {string}
     */
    getFormattedMemory() {
        if (this.memoryTotalMb > 0) {
            const usedGb = this.memoryUsedMb / 1024;
            const totalGb = this.memoryTotalMb / 1024;
            return `${usedGb.toFixed(1)}/${totalGb.toFixed(1)} GB`;
        }
        return '—';
    }
    
    /**
     * Get battery percentage formatted
     * @returns {string}
     */
    getFormattedBattery() {
        if (this.battery.percent < 0) return 'N/A';
        
        // Show warning indicator if battery is low
        if (this.battery.warning && this.battery.warning.length > 0) {
            return '⚠️ ' + this.battery.percent + '%';
        }
        
        return this.battery.percent + '%';
    }
    
    /**
     * Get status CSS class for styling
     * @returns {string}
     */
    getStatusClass() {
        const stateMap = {
            'ready': 'ready',
            'initializing': 'initializing',
            'buffering': 'buffering',
            'disabled': 'disabled',
            'not_recording': 'offline'
        };
        return stateMap[this.state] || 'offline';
    }
    
    // =========================================================================
    // Staleness Detection (Step 6.11)
    // =========================================================================
    
    /**
     * Staleness thresholds in milliseconds
     */
    static get STALENESS_THRESHOLDS() {
        return {
            FRESH: 5000,      // < 5s = green (fresh)
            DELAYED: 15000,   // 5-15s = yellow (delayed)
            STALE: 30000,     // 15-30s = orange (stale)
            OFFLINE: 30000    // > 30s = red (offline)
        };
    }
    
    /**
     * Get age of status in milliseconds (time since phone generated it)
     * @returns {number} Age in ms
     */
    getStatusAge() {
        if (!this.lastUpdated || this.lastUpdated === 0) {
            // No lastUpdated timestamp - check if this is cloud mode
            if (this.cloudMode) {
                // Cloud mode with no lastUpdated = legacy status from relay
                // This is stale data - return OFFLINE threshold + 1 to mark as offline
                return ServerStatus.STALENESS_THRESHOLDS.OFFLINE + 1;
            }
            // Local mode - use receivedAt for freshness (phone connection is live)
            return this.receivedAt ? (Date.now() - this.receivedAt) : 0;
        }
        return Date.now() - this.lastUpdated;
    }
    
    /**
     * Get staleness state for UI indicators
     * @returns {'fresh'|'delayed'|'stale'|'offline'}
     */
    getStalenessState() {
        const age = this.getStatusAge();
        const t = ServerStatus.STALENESS_THRESHOLDS;
        
        if (age < t.FRESH) return 'fresh';
        if (age < t.DELAYED) return 'delayed';
        if (age < t.STALE) return 'stale';
        return 'offline';
    }
    
    /**
     * Check if status is stale (> 30 seconds old)
     * @returns {boolean}
     */
    isStale() {
        return this.getStatusAge() > ServerStatus.STALENESS_THRESHOLDS.OFFLINE;
    }
    
    /**
     * Check if status is considered online (< 30 seconds old)
     * @returns {boolean}
     */
    isOnline() {
        return !this.isStale();
    }
    
    /**
     * Get formatted age string for tooltips
     * @returns {string} e.g., "2s ago", "45s ago", "2m ago"
     */
    getFormattedAge() {
        const ageMs = this.getStatusAge();
        const ageSec = Math.floor(ageMs / 1000);
        
        if (ageSec < 60) return `${ageSec}s ago`;
        if (ageSec < 3600) return `${Math.floor(ageSec / 60)}m ago`;
        return `${Math.floor(ageSec / 3600)}h ago`;
    }
    
    /**
     * Get LED color class based on staleness
     * @returns {string} CSS class name
     */
    getStalenessLedClass() {
        const state = this.getStalenessState();
        const classMap = {
            'fresh': 'led-green',
            'delayed': 'led-yellow', 
            'stale': 'led-orange',
            'offline': 'led-red'
        };
        return classMap[state] || 'led-red';
    }
    
    /**
     * Get staleness status text for UI
     * @returns {string}
     */
    getStalenessText() {
        const state = this.getStalenessState();
        const textMap = {
            'fresh': 'Online',
            'delayed': 'Syncing...',
            'stale': 'Connection unstable',
            'offline': 'Server offline'
        };
        return textMap[state] || 'Offline';
    }
    
    /**
     * Convert to JSON
     * @returns {Object}
     */
    toJSON() {
        return {
            state: this.state,
            mode: this.mode,
            qualityPreset: this.qualityPreset,
            fps: this.fps,
            resolution: this.resolution,
            bitrate: this.bitrate,
            codec: this.codec,
            fragmentsBuffered: this.fragmentsBuffered,
            bufferSizeMb: this.bufferSizeMb,
            networkHealth: this.networkHealth,
            battery: this.battery,
            activeConnections: this.activeConnections,
            clientIps: this.clientIps,
            dataTransferredMb: this.dataTransferredMb,
            uptimeSeconds: this.getRealtimeUptimeSeconds(), // Use real-time uptime
            uptimeStartTimestamp: this.uptimeStartTimestamp, // Include for debugging
            memoryUsageMb: this.memoryUsageMb,
            storageAvailableGb: this.storageAvailableGb,
            lastUpdate: this.lastUpdate,
            // Staleness info (Step 6.11)
            lastUpdated: this.lastUpdated,
            serverVersion: this.serverVersion,
            statusAge: this.getStatusAge(),
            stalenessState: this.getStalenessState()
        };
    }
}
