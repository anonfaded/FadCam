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
    
    // HLS Configuration - OPTIMIZED FOR STABILITY (YouTube-like approach)
    // Trade-off: ~10-15 second latency for smooth, stall-free playback
    // Based on Apple HLS Authoring Specification recommendations
    // Segments are 2 seconds each, 8 segments in playlist = 16 seconds window
    HLS_URL: '/live.m3u8',
    HLS_CONFIG: {
        debug: false,
        enableWorker: true,
        
        // === DISABLE LOW LATENCY MODE FOR STABILITY ===
        // Low latency mode causes stalls on poor connections.
        // YouTube/Twitch use 5-15 second latency for reliability.
        lowLatencyMode: false,
        
        // === LARGER BUFFERS = FEWER STALLS ===
        // With 2-second segments, buffer 20-30 seconds for stability
        maxBufferLength: 20,       // Buffer 20 seconds ahead
        maxMaxBufferLength: 60,    // Allow up to 60 seconds buffer
        maxBufferSize: 60 * 1000 * 1000, // 60MB buffer size
        backBufferLength: 60,      // Keep 60 seconds of back buffer
        
        // === LIVE SYNC - PRIORITIZE STABILITY OVER SPEED ===
        // With 2-second segments, liveSyncDurationCount=3 means 6 seconds behind live
        liveSyncDurationCount: 3,        // 3 segments (6 seconds) behind live
        liveMaxLatencyDurationCount: 8,  // Max 8 segments (16 seconds) behind
        
        // === DISABLE PLAYBACK RATE CATCH-UP ===
        // Speed changes cause visual stuttering. Better to stay behind than stutter.
        maxLiveSyncPlaybackRate: 1.0,    // Don't speed up (causes stuttering)
        liveSyncOnStallIncrease: 2,      // Add 2s latency on each stall
        liveSyncMode: 'buffered',        // Seek only if buffered (was 'edge')
        
        startPosition: -1,  // Start at live edge
        
        // === INITIAL BUFFERING ===
        // Wait for enough segments before starting playback
        initialLiveManifestSize: 3,  // Wait for 3 segments (6 seconds) before playing
        
        // === BUFFER GAP HANDLING - BE MORE TOLERANT ===
        // On poor connections, gaps are normal. Tolerate them.
        maxBufferHole: 0.5,      // Tolerate 0.5s gaps
        nudgeMaxRetry: 5,        // More retries before fatal error
        nudgeOffset: 0.1,
        nudgeOnVideoHole: true,
        
        // === STALL DETECTION - LONGER TOLERANCE ===
        // Don't trigger stall too quickly on slow connections
        highBufferWatchdogPeriod: 3,
        maxStarvationDelay: 8,   // Wait 8s before giving up on buffer
        maxLoadingDelay: 15,     // Wait 15s for fragment load (2s segments take longer)
        
        // === RETRY POLICIES - MORE AGGRESSIVE ===
        // Poor mobile connections need more retries
        fragLoadPolicy: {
            default: {
                maxTimeToFirstByteMs: 15000,  // 15s timeout
                maxLoadTimeMs: 60000,          // 60s max load time
                timeoutRetry: {
                    maxNumRetry: 4,
                    retryDelayMs: 1000,
                    maxRetryDelayMs: 8000
                },
                errorRetry: {
                    maxNumRetry: 6,
                    retryDelayMs: 1000,
                    maxRetryDelayMs: 16000,
                    backoff: 'exponential'
                }
            }
        },
        
        // === MANIFEST/PLAYLIST LOADING ===
        manifestLoadPolicy: {
            default: {
                maxTimeToFirstByteMs: 10000,
                maxLoadTimeMs: 30000,
                timeoutRetry: {
                    maxNumRetry: 3,
                    retryDelayMs: 500,
                    maxRetryDelayMs: 4000
                },
                errorRetry: {
                    maxNumRetry: 4,
                    retryDelayMs: 1000,
                    maxRetryDelayMs: 8000,
                    backoff: 'exponential'
                }
            }
        },
        
        // Other settings
        testOnBitrateFallback: false,
        cmcd: null,
        appendErrorMaxRetry: 5,    // Retry buffer append errors
        backoffMaxDelay: 64000,
        backoffMultiplier: 2
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
