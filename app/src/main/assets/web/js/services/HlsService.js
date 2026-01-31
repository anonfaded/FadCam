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
    }
    
    /**
     * Get HLS config with xhrSetup for cloud mode auth
     * Uses URL token approach (standard for HLS authentication)
     * @returns {Object} HLS.js configuration object
     */
    _getHlsConfig() {
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
                        const authUrl = `${url}${separator}token=${encodeURIComponent(streamToken)}`;
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
        this.retryCount = 0; // Reset retry counter on new load
        
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
     * Handle fatal HLS errors
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
                console.warn('[HlsService] Media error (codec/decode issue):', data.details);
                console.warn('[HlsService] This usually means video codec incompatibility or corrupted segment');
                // Don't call recoverMediaError() - it can cause more problems
                // Let HLS.js handle it or user will need to refresh
                break;
            default:
                console.error('[HlsService] Unrecoverable error:', data.type, data.details);
                break;
        }
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
     */
    destroy() {
        if (this.hls) {
            this.hls.destroy();
            this.hls = null;
        }
        this.isReady = false;
        this.listeners = {};
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
