/**
 * Fadex Notification Constants
 * Central configuration for push notification polling, caching, and behavior
 */

const NotificationConstants = {
    // ============= POLLING & CACHE =============
    /**
     * Poll interval in milliseconds
     * DEVELOPMENT: 2 seconds (for fast testing)
     * PRODUCTION: 3600000 (60 minutes)
     * Change to 3600000 after implementation complete
     */
    POLL_INTERVAL_MS: 2000,

    /**
     * LocalStorage key for cached notification state
     */
    CACHE_KEY_NOTIFICATION: 'fadex_notification_state',

    /**
     * LocalStorage key for cached message version
     */
    CACHE_KEY_VERSION: 'fadex_notification_version',

    // ============= GITHUB RAW URL =============
    /**
     * GitHub raw content URL for pushnotification.jsonc
     * Points to master branch for production
     * When DEBUG_MODE is false, this URL is used for fetching notifications
     */
    GITHUB_NOTIFICATION_URL: 'https://raw.githubusercontent.com/anonfaded/FadCam/master/app/src/main/assets/web/fadex/pushnotification.jsonc',

    // ============= RETRY POLICY =============
    /**
     * Max retry attempts on network failure
     */
    MAX_RETRY_ATTEMPTS: 3,

    /**
     * Retry delay in milliseconds (exponential backoff)
     */
    RETRY_DELAY_MS: 5000,

    // ============= UI CONSTANTS =============
    /**
     * Animation duration for jiggle effect (milliseconds)
     */
    JIGGLE_ANIMATION_DURATION_MS: 500,

    /**
     * Notification modal animation duration
     */
    MODAL_ANIMATION_DURATION_MS: 300,

    // ============= NOTIFICATION BADGE =============
    /**
     * Badge selector for visual unread count
     */
    BADGE_SELECTOR: '#fadex-notification-badge',

    /**
     * Bell icon selector for jiggle animation
     */
    BELL_SELECTOR: '#fadex-notification-bell',

    /**
     * Notification modal ID
     */
    MODAL_ID: 'fadexNotificationModal',

    // ============= PRIORITY LEVELS =============
    PRIORITY: {
        INFO: 'info',
        WARNING: 'warning',
        CRITICAL: 'critical',
    },

    /**
     * Link parsing format: {text|url}
     * Example: "Check {this guide|https://example.com}" → clickable link
     */
    LINK_FORMAT_REGEX: /\{([^|]+)\|([^}]+)\}/g,

    // ============= LOCAL DEV FALLBACK =============
    /**
     * Local path to notification JSONC file (for development)
     * When DEBUG_MODE is true, client tries this path first
     * Falls back to GitHub URL if local file not found or on error
     * Path is relative to web root (app/src/main/assets/web/)
     */
    LOCAL_NOTIFICATION_PATH: '/fadex/pushnotification.jsonc',

    // ============= LOGGING =============
    /**
     * Enable DEBUG mode for faster development
     * When true:
     *   - Uses LOCAL_NOTIFICATION_PATH first (instant, no network)
     *   - Falls back to GITHUB_NOTIFICATION_URL if local fails
     *   - Shows console logs for debugging
     *   - POLL_INTERVAL_MS should be 10 seconds (quick testing)
     * When false:
     *   - Uses GITHUB_NOTIFICATION_URL only (production)
     *   - Hides console logs
     *   - POLL_INTERVAL_MS should be 3600000 (60 minutes)
     */
    DEBUG_MODE: false,

    /**
     * Log prefix for identification
     */
    LOG_PREFIX: '[Fadex Notification]',
};

// Export for use
if (typeof module !== 'undefined' && module.exports) {
    module.exports = NotificationConstants;
}
