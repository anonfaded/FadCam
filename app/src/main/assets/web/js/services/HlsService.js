/**
 * HlsService - HLS.js wrapper for video streaming
 */
class HlsService {
    constructor() {
        this.hls = null;
        this.videoElement = null;
        this.listeners = {};
        this.isReady = false;
    }
    
    /**
     * Load HLS stream
     * @param {string} url - M3U8 playlist URL
     * @param {HTMLVideoElement} videoElement - Video DOM element
     */
    load(url, videoElement) {
        this.videoElement = videoElement;
        
        if (Hls.isSupported()) {
            console.log('[HlsService] HLS.js supported, loading stream');
            this.hls = new Hls(CONFIG.HLS_CONFIG);
            
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
            console.log('[HlsService] Manifest parsed:', data.levels.length, 'levels');
            this.isReady = true;
            this.emit('ready', data);
        });
        
        // Fragment loaded
        this.hls.on(Hls.Events.FRAG_LOADED, (event, data) => {
            console.log('[HlsService] Fragment loaded:', data.frag.sn);
            this.emit('fragment', data);
        });
        
        // Level loaded (playlist refresh)
        this.hls.on(Hls.Events.LEVEL_LOADED, (event, data) => {
            this.emit('level', data);
        });
        
        // Errors
        this.hls.on(Hls.Events.ERROR, (event, data) => {
            if (data.fatal) {
                console.error('[HlsService] Fatal error:', data.type, data.details);
                this.handleFatalError(data);
            } else {
                console.warn('[HlsService] Non-fatal error:', data.type, data.details);
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
                console.log('[HlsService] Network error, attempting recovery');
                this.hls.startLoad();
                break;
            case Hls.ErrorTypes.MEDIA_ERROR:
                console.log('[HlsService] Media error, attempting recovery');
                this.hls.recoverMediaError();
                break;
            default:
                console.error('[HlsService] Unrecoverable error, destroying player');
                this.destroy();
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

// Global singleton
const hlsService = new HlsService();
