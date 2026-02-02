/**
 * HlsService - HLS.js wrapper for video streaming
 * 
 * Supports both local mode (no auth) and cloud mode (auth token required).
 * In cloud mode, the token is appended to URLs via xhrSetup callback.
 * This is the standard approach to avoid CORS issues with custom headers.
 */
class HlsService {
    constructor() {
        this.hls = null;
        this.videoElement = null;
        this.listeners = {};
        this.isReady = false;
        this.retryCount = 0;
        this.maxRetries = 5;
        this.retryDelay = 1000; // Start with 1 second
        
        // Media error recovery tracking
        this.mediaErrorRecoveryAttempted = null; // Timestamp of last recovery attempt
        this.mediaErrorRecoveryCount = 0;
        this.maxMediaErrorRecoveries = 3; // Max recovery attempts before giving up
        this.currentStreamUrl = null; // Track for reload
    }
    
    /**
     * Get HLS config with xhrSetup for cloud mode auth
     * Uses URL token approach (standard for HLS authentication)
     * @returns {Object} HLS.js configuration object
     */
    _getHlsConfig() {
        // Use config from CONFIG.HLS_CONFIG directly - it's already optimized for stability
        const baseConfig = { ...CONFIG.HLS_CONFIG };
        
        // Check if we're in cloud mode and have a stream token
        const isCloudMode = typeof FadCamRemote !== 'undefined' && 
                           typeof FadCamRemote.isCloudMode === 'function' && 
                           FadCamRemote.isCloudMode();
        
        if (isCloudMode) {
            const streamToken = FadCamRemote.getStreamToken();
            if (streamToken) {
                console.log('[HlsService] ☁️ Cloud mode detected, using URL token auth');
                
                // Standard xhrSetup approach - modify URL before request is sent
                // This is the recommended way per hls.js documentation
                baseConfig.xhrSetup = function(xhr, url) {
                    // Only modify cloud stream URLs
                    if (url.includes('/stream/') || url.includes('live.fadseclab.com')) {
                        const separator = url.includes('?') ? '&' : '?';
                        // Add cache-busting timestamp for playlist files to prevent stale segments
                        const cacheBust = url.includes('.m3u8') ? `&_t=${Date.now()}` : '';
                        const authUrl = `${url}${separator}token=${encodeURIComponent(streamToken)}${cacheBust}`;
                        // Re-open with the new URL (must call open before setRequestHeader)
                        xhr.open('GET', authUrl, true);
                    }
                };
            } else {
                console.warn('[HlsService] ☁️ Cloud mode but no stream token available!');
            }
        } else {
            console.log('[HlsService] 📱 Local mode, no auth needed');
        }
        
        return baseConfig;
    }
    
    /**
     * Load HLS stream
     * @param {string} url - M3U8 playlist URL
     * @param {HTMLVideoElement} videoElement - Video DOM element
     */
    load(url, videoElement) {
        this.videoElement = videoElement;
        this.currentStreamUrl = url; // Store for potential reload
        this.retryCount = 0; // Reset retry counter on new load
        this.mediaErrorRecoveryCount = 0; // Reset media error recovery count
        this.mediaErrorRecoveryAttempted = null;
        
        if (Hls.isSupported()) {
            console.log('[HlsService] HLS.js supported, loading stream');
            
            // Get config with auth headers if in cloud mode
            const hlsConfig = this._getHlsConfig();
            this.hls = new Hls(hlsConfig);
            
            this.setupHlsListeners();
            this.hls.loadSource(url);
            this.hls.attachMedia(videoElement);
        } else if (videoElement.canPlayType('application/vnd.apple.mpegurl')) {
            console.log('[HlsService] Native HLS support detected');
            videoElement.src = url;
            this.isReady = true;
            this.emit('ready');
        } else {
            console.error('[HlsService] HLS not supported in this browser');
            this.emit('error', { message: 'HLS not supported' });
        }
    }
    
    /**
     * Setup HLS.js event listeners
     */
    setupHlsListeners() {
        if (!this.hls) return;
        
        // Manifest parsed - stream ready
        this.hls.on(Hls.Events.MANIFEST_PARSED, (event, data) => {
            console.log('[HlsService] ✅ Manifest parsed successfully');
            console.log('[HlsService] Levels available:', data.levels.length);
            data.levels.forEach((level, idx) => {
                console.log(`  Level ${idx}:`, {
                    bitrate: level.bitrate,
                    width: level.width,
                    height: level.height,
                    codecs: level.codecs
                });
            });
            this.isReady = true;
            this.emit('ready', data);
        });
        
        // Init segment loaded - CRITICAL for fMP4
        this.hls.on(Hls.Events.INIT_SEGMENT, (event, data) => {
            console.log('[HlsService] 🎬 INIT SEGMENT LOADED - fMP4 container ready');
            console.log('[HlsService]   Fragment type:', data.frag?.type);
            console.log('[HlsService]   Fragment duration:', data.frag?.duration);
        });
        
        // Fragment loaded
        this.hls.on(Hls.Events.FRAG_LOADED, (event, data) => {
            console.log(`[HlsService] 📦 Fragment ${data.frag.sn} loaded (${data.frag.duration?.toFixed(2)}s)`);
            this.emit('fragment', data);
        });
        
        // Level loaded (playlist refresh)
        this.hls.on(Hls.Events.LEVEL_LOADED, (event, data) => {
            console.log('[HlsService] 📋 Level/Playlist loaded, fragments:', data.details?.fragments?.length);
            
            // Log live latency information if available (less frequently)
            if (this.hls && data.details?.live) {
                const latency = this.hls.latency;
                const targetLatency = this.hls.targetLatency;
                const maxLatency = this.hls.maxLatency;
                const liveSyncPosition = this.hls.liveSyncPosition;
                
                // Only log every 10th playlist refresh to reduce noise
                this._playlistLoadCount = (this._playlistLoadCount || 0) + 1;
                if (this._playlistLoadCount % 10 === 1) {
                    console.log('[HlsService] 📊 Live latency:', {
                        currentLatency: latency?.toFixed(2) + 's',
                        targetLatency: targetLatency?.toFixed(2) + 's',
                        maxLatency: maxLatency?.toFixed(2) + 's',
                        liveSyncPosition: liveSyncPosition?.toFixed(2) + 's',
                        playbackRate: this.videoElement?.playbackRate
                    });
                }
                
                // === CRITICAL: Detect ancient segments ===
                // Only force seek if we're MORE than 60 seconds behind
                // This catches the "35 minute old segment" scenario without being too aggressive
                // For smaller latencies, let HLS.js handle it naturally
                if (latency > 60 && liveSyncPosition !== null) {
                    console.warn('[HlsService] ⚠️ EXTREMELY behind live edge! (' + latency.toFixed(1) + 's) - Force seeking to live');
                    this.videoElement.currentTime = liveSyncPosition;
                    this.emit('seekToLive', { latency, liveSyncPosition });
                }
            }
            
            this.emit('level', data);
        });
        
        // Buffer appending
        this.hls.on(Hls.Events.BUFFER_APPENDING, (event, data) => {
            console.log('[HlsService] 📥 Buffer appending:', {
                frag: data.frag?.sn,
                type: data.type
            });
        });
        
        // Buffer appended
        this.hls.on(Hls.Events.BUFFER_APPENDED, (event, data) => {
            console.log('[HlsService] ✅ Buffer appended successfully, buffered:', data.timeRanges?.length);
        });
        
        // Errors
        this.hls.on(Hls.Events.ERROR, (event, data) => {
            if (data.fatal) {
                console.error('[HlsService] ❌ FATAL error:', {
                    type: data.type,
                    details: data.details,
                    errorDetails: data.error
                });
                this.handleFatalError(data);
            } else {
                console.warn('[HlsService] ⚠️  Non-fatal error:', {
                    type: data.type,
                    details: data.details
                });
            }
            this.emit('error', data);
        });
    }
    
    /**
     * Handle fatal HLS errors with automatic recovery
     * @param {Object} data - Error data
     */
    handleFatalError(data) {
        switch(data.type) {
            case Hls.ErrorTypes.NETWORK_ERROR:
                console.log('[HlsService] Network error detected:', data.details);
                // Network errors are temporary; HLS.js will retry automatically
                if (data.response?.status === 503) {
                    console.log('[HlsService] 503 Service Unavailable - stream still initializing');
                } else {
                    console.log('[HlsService] Network error - HLS.js will retry automatically');
                }
                break;
                
            case Hls.ErrorTypes.MEDIA_ERROR:
                console.warn('[HlsService] 🔧 Media error (codec/decode issue):', data.details);
                this.attemptMediaErrorRecovery(data);
                break;
                
            default:
                console.error('[HlsService] Unrecoverable error:', data.type, data.details);
                break;
        }
    }
    
    /**
     * Attempt to recover from media errors using HLS.js built-in recovery.
     * Based on HLS.js documentation: https://github.com/video-dev/hls.js/blob/master/docs/API.md
     * @param {Object} data - Error data
     */
    attemptMediaErrorRecovery(data) {
        const now = Date.now();
        
        // Only attempt recovery if enough time has passed since last attempt (5s cooldown)
        const recoveryDelay = this.mediaErrorRecoveryAttempted 
            ? (now - this.mediaErrorRecoveryAttempted) 
            : Infinity;
        
        if (recoveryDelay < 5000) {
            console.warn('[HlsService] ⏳ Skipping recovery (only ' + recoveryDelay + 'ms since last attempt)');
            return;
        }
        
        // Check if we've exceeded max recovery attempts
        if (this.mediaErrorRecoveryCount >= this.maxMediaErrorRecoveries) {
            console.error('[HlsService] ❌ Max media error recoveries exceeded (' + 
                this.maxMediaErrorRecoveries + '), reloading stream...');
            this.reloadStream();
            return;
        }
        
        // Update recovery tracking
        this.mediaErrorRecoveryAttempted = now;
        this.mediaErrorRecoveryCount++;
        
        console.log('[HlsService] 🔄 Attempting media error recovery (attempt ' + 
            this.mediaErrorRecoveryCount + '/' + this.maxMediaErrorRecoveries + ')');
        
        // Use HLS.js built-in recovery method
        // This resets the MediaSource and restarts streaming from the last known position
        try {
            this.hls.recoverMediaError();
            console.log('[HlsService] ✅ recoverMediaError() called successfully');
            this.emit('recovery', { 
                type: 'media_error', 
                attempt: this.mediaErrorRecoveryCount,
                details: data.details 
            });
        } catch (e) {
            console.error('[HlsService] ❌ recoverMediaError() failed:', e);
            // If recovery fails, try reloading the stream
            this.reloadStream();
        }
    }
    
    /**
     * Reload the stream completely (destroy and recreate HLS instance)
     */
    reloadStream() {
        if (!this.currentStreamUrl || !this.videoElement) {
            console.error('[HlsService] Cannot reload: missing URL or video element');
            return;
        }
        
        console.log('[HlsService] 🔃 Reloading stream completely...');
        
        // Store references
        const url = this.currentStreamUrl;
        const videoElement = this.videoElement;
        const savedListeners = { ...this.listeners }; // Preserve listeners
        
        // Emit reload event BEFORE destroy so UI can show message
        this.emit('reload', { url });
        
        // Destroy current HLS instance (but not videoElement reference)
        if (this.hls) {
            this.hls.destroy();
            this.hls = null;
        }
        this.isReady = false;
        
        // Restore listeners for the new instance
        this.listeners = savedListeners;
        
        // Wait a bit then reload
        setTimeout(() => {
            console.log('[HlsService] 🎬 Creating new HLS instance...');
            this.load(url, videoElement);
        }, 1000);
    }
    
    /**
     * Seek to live edge.
     * Call this to jump to the most recent live position.
     * @returns {boolean} True if seek was successful
     */
    seekToLive() {
        if (!this.hls || !this.videoElement) {
            console.warn('[HlsService] Cannot seek to live: HLS not initialized');
            return false;
        }
        
        const liveSyncPosition = this.hls.liveSyncPosition;
        if (liveSyncPosition !== null && liveSyncPosition !== undefined) {
            const currentTime = this.videoElement.currentTime;
            const latency = liveSyncPosition - currentTime;
            
            console.log('[HlsService] 🎯 Seeking to live edge:', {
                from: currentTime.toFixed(2) + 's',
                to: liveSyncPosition.toFixed(2) + 's',
                latency: latency.toFixed(2) + 's'
            });
            
            this.videoElement.currentTime = liveSyncPosition;
            this.emit('seekToLive', { 
                from: currentTime, 
                to: liveSyncPosition, 
                latency 
            });
            return true;
        }
        
        console.warn('[HlsService] Cannot seek: liveSyncPosition not available');
        return false;
    }
    
    /**
     * Get current live latency information
     * @returns {Object|null} Latency info or null if not available
     */
    getLatencyInfo() {
        if (!this.hls || !this.videoElement) {
            return null;
        }
        
        return {
            currentLatency: this.hls.latency,
            targetLatency: this.hls.targetLatency,
            maxLatency: this.hls.maxLatency,
            liveSyncPosition: this.hls.liveSyncPosition,
            currentTime: this.videoElement.currentTime,
            playbackRate: this.videoElement.playbackRate,
            drift: this.hls.drift
        };
    }
    
    /**
     * Register event listener
     * @param {string} event - Event name
     * @param {Function} callback - Callback function
     */
    on(event, callback) {
        if (!this.listeners[event]) {
            this.listeners[event] = [];
        }
        this.listeners[event].push(callback);
    }
    
    /**
     * Emit event to listeners
     * @param {string} event - Event name
     * @param {*} data - Event data
     */
    emit(event, data) {
        if (!this.listeners[event]) return;
        this.listeners[event].forEach(callback => callback(data));
    }
    
    /**
     * Destroy HLS instance
     * @param {boolean} full - If true, also clear video element and listeners
     */
    destroy(full = true) {
        if (this.hls) {
            this.hls.destroy();
            this.hls = null;
        }
        this.isReady = false;
        
        if (full) {
            this.videoElement = null;
            this.currentStreamUrl = null;
            this.listeners = {};
            this.mediaErrorRecoveryCount = 0;
            this.mediaErrorRecoveryAttempted = null;
        }
    }
    
    /**
     * Play video
     */
    play() {
        if (this.videoElement) {
            return this.videoElement.play();
        }
    }
    
    /**
     * Pause video
     */
    pause() {
        if (this.videoElement) {
            this.videoElement.pause();
        }
    }
}
