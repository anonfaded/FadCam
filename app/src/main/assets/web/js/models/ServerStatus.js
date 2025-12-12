/**
 * ServerStatus - Data model for /status endpoint response
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
        // Core status
        this.state = data.state || 'offline';
        this.mode = data.mode || 'disabled';
        this.message = data.message || '';
        this.isRecording = data.is_recording || false;
        this.streaming = data.streaming || false;
        
        // Uptime details (nested object)
        this.uptimeDetails = data.uptime_details || {};
        this.uptimeSeconds = this.uptimeDetails.seconds || data.uptime_seconds || 0;
        
        // Format uptime from the details if available
        if (!this.uptimeDetails.formatted_uptime && this.uptimeDetails.formatted) {
            this.uptimeDetails.formatted_uptime = this.uptimeDetails.formatted;
        }
        if (!this.uptimeDetails.session_started_time && this.uptimeDetails.start_time) {
            this.uptimeDetails.session_started_time = this.uptimeDetails.start_time;
        }
        if (!this.uptimeDetails.session_started_date && this.uptimeDetails.start_timestamp) {
            const date = new Date(this.uptimeDetails.start_timestamp);
            this.uptimeDetails.session_started_date = date.toLocaleDateString();
        }
        
        // Stream quality (nested object)
        if (data.stream_quality && typeof data.stream_quality === 'object') {
            this.qualityPreset = (data.stream_quality.preset || 'high').toUpperCase();
            this.bitrate = data.stream_quality.bitrate || '0 Mbps';
            this.fps = data.stream_quality.fps_cap || 0;
        } else {
            this.qualityPreset = 'HIGH';
            this.bitrate = '0 Mbps';
            this.fps = 0;
        }
        
        // Resolution from message or construct from width/height
        this.resolution = data.resolution || '0x0';
        this.videoCodec = data.video_codec || 'unknown';
        this.isHevcCodec = this.videoCodec && this.videoCodec.toUpperCase().includes('HEVC');
        
        // Buffer info
        this.fragmentsBuffered = data.fragments_buffered || 0;
        this.bufferSizeMb = data.buffer_size_mb || 0;
        
        // Network (handle both object and string)
        if (data.network_health && typeof data.network_health === 'object') {
            this.networkHealth = data.network_health.status || 'unknown';
            this.networkDownloadMbps = data.network_health.download_mbps ?? null;
            this.networkUploadMbps = data.network_health.upload_mbps ?? null;
            this.networkLatencyMs = data.network_health.latency_ms ?? null;
            this.networkLastMeasurementMs = data.network_health.last_measurement_ms ?? 0;
        } else {
            this.networkHealth = data.network_health || 'unknown';
            this.networkDownloadMbps = null;
            this.networkUploadMbps = null;
            this.networkLatencyMs = null;
            this.networkLastMeasurementMs = 0;
        }
        
        // Battery (nested object)
        if (data.battery_details && typeof data.battery_details === 'object') {
            this.battery = {
                percent: data.battery_details.percent ?? -1,
                status: data.battery_details.status || 'unknown',
                consumed: data.battery_details.consumed !== undefined && data.battery_details.consumed !== null ? data.battery_details.consumed + '' : 'N/A',
                remainingHours: data.battery_details.remaining_hours ?? -1,
                warning: data.battery_details.warning || false,
                warning_threshold: data.battery_details.warning_threshold || 20
            };
        } else {
            this.battery = {
                percent: -1,
                status: 'unknown',
                consumed: 'N/A',
                remainingHours: -1,
                warning: false,
                warning_threshold: 20
            };
        }
        
        // Connections
        this.activeConnections = data.active_connections || 0;
        this.clientIps = data.clients ? data.clients.map(c => c.ip) : [];
        
        // Store full clients array for modal display
        this.clients = data.clients || [];
        
        // Events/logs
        this.events = data.events || [];
        
        // Segments/buffer info
        this.hasInitSegment = data.has_init_segment || false;
        
        // Torch state
        this.torchOn = data.torch_state || false;
        
        // Volume state
        this.volume = data.volume ?? 0;
        this.max_volume = data.max_volume ?? 15;
        this.volume_percentage = data.volume_percentage ?? 0;
        
        // Alarm state (nested object)
        if (data.alarm && typeof data.alarm === 'object') {
            this.alarm = {
                is_ringing: data.alarm.is_ringing || false,
                sound: data.alarm.sound || 'office_phone.mp3',
                duration_ms: data.alarm.duration_ms ?? -1,
                remaining_ms: data.alarm.remaining_ms ?? 0
            };
        } else {
            this.alarm = {
                is_ringing: false,
                sound: 'office_phone.mp3',
                duration_ms: -1,
                remaining_ms: 0
            };
        }
        
        // Stats
        this.dataTransferredMb = data.total_data_transferred_mb || 0;
        this.uptimeSeconds = data.uptime_seconds || 0;
        
        // Parse uptime details (nested object)
        if (data.uptime_details && typeof data.uptime_details === 'object') {
            this.uptimeFormatted = data.uptime_details.formatted || '0s';
            this.uptimeStartTime = data.uptime_details.start_time || 'Not started';
            this.uptimeStartDate = data.uptime_details.start_date || 'Not started';
            this.uptimeStartTimestamp = data.uptime_details.start_timestamp || 0;
        } else {
            this.uptimeFormatted = '0s';
            this.uptimeStartTime = 'Not started';
            this.uptimeStartDate = 'Not started';
            this.uptimeStartTimestamp = 0;
        }
        
        // Authentication state
        this.authEnabled = data.auth_enabled || false;
        this.authTimeoutMs = data.auth_timeout_ms || 0;  // 0 means never auto-lock
        this.authSessionsCount = data.auth_sessions_count || 0;
        this.authSessionsCleared = data.auth_sessions_cleared || false;  // Flag for logout all
        
        // Parse memory and storage from strings
        // Memory format from backend: "75% (1024/1366 MB)"
        if (data.memory_usage) {
            // Extract percentage: "75% (1024/1366 MB)" → 75
            const percentMatch = data.memory_usage.match(/(\d+)%/);
            this.memoryPercent = percentMatch ? parseInt(percentMatch[1]) : 0;
            
            // Extract used/total: "75% (1024/1366 MB)" → [1024, 1366]
            const memMatch = data.memory_usage.match(/\((\d+)\/(\d+)\s*MB\)/);
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
        this.memory = data.memory_usage || '—';
        
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
     * Get formatted uptime
     * @returns {string}
     */
    getFormattedUptime() {
        return Formatter.formatUptime(this.uptimeSeconds);
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
            uptimeSeconds: this.uptimeSeconds,
            memoryUsageMb: this.memoryUsageMb,
            storageAvailableGb: this.storageAvailableGb,
            lastUpdate: this.lastUpdate
        };
    }
}
