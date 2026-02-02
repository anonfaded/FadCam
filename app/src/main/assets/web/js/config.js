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
    // Trade-off: ~8-10 second latency for smooth, stall-free playback
    // Based on Apple HLS Authoring Specification recommendations
    HLS_URL: '/live.m3u8',
    HLS_CONFIG: {
        debug: false,
        enableWorker: true,
        
        // === DISABLE LOW LATENCY MODE FOR STABILITY ===
        // Low latency mode causes stalls on poor connections.
        // YouTube/Twitch use 5-15 second latency for reliability.
        lowLatencyMode: false,
        
        // === LARGER BUFFERS = FEWER STALLS ===
        // Apple recommends 6-second segments, 15+ minutes in playlist
        // We use 1-sec segments, so buffer 10-15 seconds for stability
        maxBufferLength: 15,       // Buffer 15 seconds ahead (was 6)
        maxMaxBufferLength: 30,    // Allow up to 30 seconds buffer (was 8)
        maxBufferSize: 60 * 1000 * 1000, // 60MB buffer size
        backBufferLength: 30,      // Keep 30 seconds of back buffer
        
        // === LIVE SYNC - PRIORITIZE STABILITY OVER SPEED ===
        // Start 4 segments (4 seconds) behind live edge for buffer room
        liveSyncDurationCount: 4,        // 4 segments behind live (was 2)
        liveMaxLatencyDurationCount: 10, // Allow up to 10 segments behind (was 5)
        
        // === DISABLE PLAYBACK RATE CATCH-UP ===
        // Speed changes cause visual stuttering. Better to stay behind than stutter.
        maxLiveSyncPlaybackRate: 1.0,    // Don't speed up (was 1.2)
        liveSyncOnStallIncrease: 2,      // Add 2s latency on each stall (was 0.5)
        liveSyncMode: 'buffered',        // Seek only if buffered (was 'edge')
        
        startPosition: -1,  // Start at live edge
        
        // === BUFFER GAP HANDLING - BE MORE TOLERANT ===
        // On poor connections, gaps are normal. Tolerate them.
        maxBufferHole: 0.5,      // Tolerate 0.5s gaps (was 0.3)
        nudgeMaxRetry: 5,        // More retries before fatal error (was 3)
        nudgeOffset: 0.1,
        nudgeOnVideoHole: true,
        
        // === STALL DETECTION - LONGER TOLERANCE ===
        // Don't trigger stall too quickly on slow connections
        highBufferWatchdogPeriod: 3,
        maxStarvationDelay: 6,   // Wait 6s before giving up on buffer
        maxLoadingDelay: 10,     // Wait 10s for fragment load
        
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
