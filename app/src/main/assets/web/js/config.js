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
        lowLatencyMode: true,  // Reduced latency for live streaming
        maxBufferLength: 6,    // Smaller buffer = less delay (was 10)
        maxMaxBufferLength: 8, // was 12
        maxBufferSize: 30 * 1000 * 1000, // 30MB max (was 60)
        
        // CRITICAL: Live edge synchronization - stay as close to live as possible
        liveSyncDurationCount: 2,        // Start 2 segments behind live edge
        liveMaxLatencyDurationCount: 5,  // If >5 segments behind, seek back to live (was 4)
        maxLiveSyncPlaybackRate: 1.2,    // Speed up to 1.2x to catch up if behind
        liveSyncOnStallIncrease: 0.5,    // Add 0.5s latency on each stall
        liveSyncMode: 'edge',            // Jump to live edge immediately if too far behind
        
        startPosition: -1,  // Start at live edge
        
        // Buffer gap handling - be aggressive about staying live
        maxBufferHole: 0.3,      // Tolerate only small gaps (was 0.5 in HlsService)
        nudgeMaxRetry: 3,        // Fewer retries before seeking
        nudgeOffset: 0.1,        // Small nudge offset
        nudgeOnVideoHole: true,  // Seek when buffer gaps detected
        
        // Manifest/Fragment request retry configuration
        testOnBitrateFallback: false,
        cmcd: null,
        backoffMaxDelay: 64000,  // Max 64s delay between retries
        backoffMultiplier: 2      // Exponential backoff
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
