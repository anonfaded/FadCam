/**
 * Configuration constants for FadCam Server Room Dashboard
 */
const CONFIG = {
    // API Configuration
    BASE_URL: window.location.origin,
    
    // API Endpoints
    ENDPOINTS: {
        STATUS: '/status',
        TORCH_TOGGLE: '/torch/toggle',
        HLS: '/live.m3u8'
    },
    
    // Polling intervals (milliseconds)
    STATUS_POLL_INTERVAL: 2000,  // 2 seconds
    
    // HLS Configuration
    HLS_URL: '/live.m3u8',
    HLS_CONFIG: {
        debug: false,
        enableWorker: true,
        lowLatencyMode: false,
        maxBufferLength: 12,
        maxMaxBufferLength: 15,
        maxBufferSize: 60 * 1000 * 1000,
        liveSyncDurationCount: 2,
        liveMaxLatencyDurationCount: 6,
        startPosition: -1
    },
    
    // UI Configuration
    ANIMATION_DURATION: 300,  // milliseconds
    TOAST_DURATION: 3000,     // milliseconds
    
    // Status states
    STATES: {
        READY: 'ready',
        INITIALIZING: 'initializing',
        BUFFERING: 'buffering',
        DISABLED: 'disabled',
        NOT_RECORDING: 'not_recording'
    }
};
