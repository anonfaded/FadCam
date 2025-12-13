/**
 * Fadex Notification Manager
 * Handles polling, caching, parsing, and display of push notifications from GitHub
 * Architecture: Single responsibility - manages notification lifecycle only
 */

class FadexNotificationManager {
    /**
     * Constructor
     * Initializes notification manager with constants
     */
    constructor() {
        if (!NotificationConstants) {
            console.error('[FadexNotificationManager] NotificationConstants not loaded');
            return;
        }

        this.constants = NotificationConstants;
        this.pollIntervalId = null;
        this.retryCount = 0;
        // Initialize as empty - will load from cache after setup
        this.notificationHistory = [];
        this.isJiggling = false;
        this.currentModalNotification = null; // Track which notification is currently in the modal

        this.log('Manager initialized', { cachedNotifications: 0 });
        
        // Clean up old cache on init (older than 30 days)
        this.cleanOldCache();
    }

    /**
     * Start notification polling
     * Loads initial state and sets up recurring poll
     */
    async start() {
        // In production mode, GitHub URL is required
        if (!this.constants.DEBUG_MODE && !this.constants.GITHUB_NOTIFICATION_URL) {
            return;
        }

        // Load cached notifications from localStorage
        this.notificationHistory = this.loadFromCache();

        // Show badge if there are unread cached notifications
        this.updateNotificationBadge();

        // Initial fetch
        await this.checkForUpdates();

        // Set up polling interval
        this.pollIntervalId = setInterval(async () => {
            await this.checkForUpdates();
        }, this.constants.POLL_INTERVAL_MS);
    }

    /**
     * Stop polling
     */
    stop() {
        if (this.pollIntervalId) {
            clearInterval(this.pollIntervalId);
            this.pollIntervalId = null;
            this.log('Polling stopped');
        }
    }

    /**
     * Check for notification updates
     * Compares remote version with latest cached version
     * If new version: adds to history and triggers appropriate UI
     */
    async checkForUpdates() {
        try {
            const remoteNotification = await this.fetchNotification();

            if (!remoteNotification) {
                this.retryCount = 0;
                return;
            }

            // Get latest notification from history
            const latestVersion = this.notificationHistory.length > 0 
                ? Math.max(...this.notificationHistory.map(n => n.version || 0))
                : 0;

            // Compare versions
            if (remoteNotification.version > latestVersion) {
                // NEW: Only clear OLD versions of the SAME notification ID
                // Keep other notifications (e.g., intro + main can coexist)
                const sourceId = remoteNotification.id; // This comes from the JSON key (intro, main, etc)
                console.log(`📝 [UPDATE] New version detected for "${sourceId}": v${remoteNotification.version} > v${latestVersion}`);
                
                // Remove OLD cached entries of this SAME notification
                const oldCount = this.notificationHistory.length;
                this.notificationHistory = this.notificationHistory.filter(n => {
                    // Keep notifications from OTHER sources, remove old versions of THIS source
                    return !n.title || !remoteNotification.title || n.title !== remoteNotification.title;
                });
                const removedCount = oldCount - this.notificationHistory.length;
                
                if (removedCount > 0) {
                    console.log(`📝 [UPDATE] Removed ${removedCount} old version(s) of "${sourceId}"`);
                }
                
                // Add the new notification
                const notification = {
                    ...remoteNotification,
                    sourceId: sourceId, // Store the source ID for future comparisons
                    timestamp: Date.now(),
                    isRead: false,
                    id: `notif-${Date.now()}`,
                };

                console.log(`📝 [ADD-HISTORY] Adding notification:`, {
                    sourceId: sourceId,
                    title: notification.title,
                    version: notification.version,
                    expiry: notification.expiry,
                    priority: notification.priority,
                    draft: notification.draft
                });
                
                console.log(`📝 [ADD-HISTORY] Full notification object:`, notification);

                this.notificationHistory.push(notification);
                this.saveToCache(this.notificationHistory);

                // Update UI based on priority
                this.updateNotificationBadge();
                
                // Normalize priority to lowercase for comparison
                const priority = (notification.priority || '').toLowerCase();
                if (priority === 'warning' || priority === 'critical') {
                    // Show modal for warning/critical
                    // Mark as read since user is seeing it
                    notification.isRead = true;
                    this.saveToCache(this.notificationHistory);
                    
                    this.startJiggleAnimation();
                    this.showNotificationPopup(notification);
                } else {
                    // Info: just jiggle for 3 seconds
                    this.startJiggleAnimation(3000);
                }
            }

            this.retryCount = 0;
        } catch (error) {
            this.handleNetworkError(error);
        }
    }

    /**
     * Fetch notification from GitHub only
     * @returns {Promise<Object|null>} Parsed notification object or null
     */
    async fetchNotification() {
        return await this.fetchNotificationFromGitHub();
    }

    /**
     * Fetch notification from local file (development only)
     * Silent fail - returns null on error
     *
     * @returns {Promise<Object|null>} Parsed notification object or null
     */
    async fetchLocalNotification() {
        try {
            // Add cache-buster to force fresh fetch
            const cacheBuster = `t=${Date.now()}`;
            const response = await fetch(`${this.constants.LOCAL_NOTIFICATION_PATH}?${cacheBuster}`, {
                method: 'GET',
                headers: {
                    'Cache-Control': 'no-cache, no-store, must-revalidate',
                    'Pragma': 'no-cache',
                    'Expires': '0'
                },
            });

            if (!response.ok) {
                this.log('ℹ️ Local file not available', { status: response.status });
                return null;
            }

            const text = await response.text();
            const json = this.parseJSONС(text);

            // Handle new structure: notifications object with multiple entries
            if (!json.notifications || typeof json.notifications !== 'object') {
                throw new Error('Invalid notification schema: missing notifications object');
            }

            return this.filterAndGetNotifications(json.notifications);
        } catch (error) {
            this.log('ℹ️ Local file fetch failed', { error: error.message });
            return null;
        }
    }

    /**
     * Filter notifications: exclude draft, check expiry, return most recent
     * @param {Object} notifications - Object with notification IDs as keys
     * @returns {Object|null} Most recent active notification or null
     */
    filterAndGetNotifications(notifications) {
        const activeNotifications = [];
        console.log(`📋 [FILTER] Processing ${Object.keys(notifications).length} notifications`);
        console.log(`📋 [FILTER] Full notifications object:`, notifications);

        for (const [id, notification] of Object.entries(notifications)) {
            console.log(`🔍 [FILTER] Checking notification: ${id}`);
            console.log(`   Fields present:`, Object.keys(notification));
            console.log(`   expiry field value: ${notification.expiry}`);
            
            // Skip if draft
            if (notification.draft === true) {
                console.log(`  ❌ [DRAFT] Skipped: ${id} (draft=true)`);
                this.log(`ℹ️ Skipping draft notification: ${id}`);
                continue;
            }

            // Validate required fields FIRST
            if (!notification.version || !notification.title) {
                console.log(`  ⚠️ [INVALID] Missing required fields in: ${id}`);
                this.log(`⚠️ Invalid notification schema for ${id}: missing version or title`);
                continue;
            }

            // Check expiry (default 30 days if not specified)
            // Special values: -1 = forever, 0 = delete immediately, >0 = days to keep
            const expiry = notification.expiry !== undefined ? notification.expiry : 30;
            console.log(`  ⏰ [EXPIRY] ${id}: raw value=${notification.expiry}, applied value=${expiry}`);
            
            if (expiry === -1) {
                // Expiry=-1 means keep forever (no time-based expiry)
                console.log(`  ♾️ [EXPIRY=-1] Keep forever: ${id}`);
                this.log(`♾️ Keeping notification forever: ${id} (expiry=-1)`);
            } else if (expiry === 0) {
                // Expiry=0 means skip showing but still include for cache cleanup later
                console.log(`  🗑️ [EXPIRY=0] Will delete from cache: ${id}`);
                this.log(`🗑️ Notification marked for immediate deletion: ${id} (expiry=0)`);
                continue; // Skip displaying notifications with expiry=0
            } else {
                console.log(`  📅 [EXPIRY] Keep for ${expiry} days: ${id}`);
            }

            console.log(`  ✅ [ACCEPTED] ${id} (v${notification.version})`);
            // IMPORTANT: Preserve all fields including expiry!
            const notifToPush = { id, ...notification };
            console.log(`   Pushing to active with expiry:`, notifToPush.expiry);
            activeNotifications.push(notifToPush);
        }

        // Return most recent by version (highest version number)
        if (activeNotifications.length === 0) {
            console.log(`📋 [FILTER] No active notifications to return`);
            return null;
        }
        const result = activeNotifications.reduce((max, notif) => 
            notif.version > max.version ? notif : max
        );
        console.log(`📋 [FILTER] Returning:`, { id: result.id, version: result.version, expiry: result.expiry });
        return result;
    }

    /**
     * Fetch notification JSON from GitHub raw content URL
     * Sources directly from master branch for consistent updates
     *
     * @returns {Promise<Object|null>} Parsed notification object or null
     */
    async fetchNotificationFromGitHub() {
        try {
            console.log(`🌐 [FETCH] Getting notifications from backend endpoint...`);
            
            // Use backend endpoint which handles GitHub fetch server-side
            // Avoids browser CORS issues since backend fetches from GitHub directly
            const response = await fetch('/api/github/notification');

            if (!response.ok) {
                console.log(`❌ [FETCH] HTTP ${response.status}`);
                throw new Error(`HTTP ${response.status}`);
            }

            const text = await response.text();
            console.log(`📄 [RESPONSE] Received from backend`);
            
            const json = JSON.parse(text); // Backend returns clean JSON (comments stripped)
            console.log(`✅ [PARSED] JSON:`, json);
            
            if (!json.notifications || typeof json.notifications !== 'object') {
                throw new Error('Invalid schema: missing notifications object');
            }

            console.log(`📋 [NOTIFICATIONS] Found ${Object.keys(json.notifications).length} notifications`);
            return this.filterAndGetNotifications(json.notifications);
        } catch (error) {
            console.log(`❌ [ERROR] ${error.message}`);
            this.log('ℹ️ Notification fetch failed', { error: error.message });
            return null;
        }
    }

    /**
     * Parse JSONC (JSON with comments)
     * Strips single-line and multi-line comments
     *
     * @param {string} text Raw JSONC text
     * @returns {Object} Parsed JSON object
     */
    parseJSONС(text) {
        // Remove multi-line comments first (/* ... */)
        let cleaned = text.replace(/\/\*[\s\S]*?\*\//g, '');

        // Remove single-line comments (//) but NOT inside strings
        // Split into lines and process each line
        cleaned = cleaned.split('\n').map(line => {
            // Find comment marker, but skip if it's inside a string
            let inString = false;
            let escaped = false;
            
            for (let i = 0; i < line.length; i++) {
                const char = line[i];
                
                if (escaped) {
                    escaped = false;
                    continue;
                }
                
                if (char === '\\') {
                    escaped = true;
                    continue;
                }
                
                if (char === '"' && !escaped) {
                    inString = !inString;
                }
                
                // If we find // outside a string, remove from here
                if (!inString && i < line.length - 1 && char === '/' && line[i + 1] === '/') {
                    return line.substring(0, i);
                }
            }
            
            return line;
        }).join('\n');

        // Remove trailing commas (common in JSONC)
        cleaned = cleaned.replace(/,(\s*[}\]])/g, '$1');

        return JSON.parse(cleaned);
    }

    /**
     * Handle network errors with retry logic
     *
     * @param {Error} error
     */
    handleNetworkError(error) {
        this.retryCount++;
        this.log('⚠️ Network error', {
            error: error.message,
            retryCount: this.retryCount,
            maxRetries: this.constants.MAX_RETRY_ATTEMPTS,
        });

        if (this.retryCount >= this.constants.MAX_RETRY_ATTEMPTS) {
            this.log('Max retries reached. Will retry at next poll cycle.', { severity: 'warning' });
            this.retryCount = 0;
        }
    }

    /**
     * Update notification badge on bell icon
     */
    updateNotificationBadge() {
        const badge = document.querySelector(this.constants.BADGE_SELECTOR);
        if (!badge) {
            this.log('Badge element not found', { selector: this.constants.BADGE_SELECTOR });
            return;
        }

        // Count unread notifications
        const unreadCount = this.notificationHistory.filter(n => !n.isRead).length;
        
        if (unreadCount > 0) {
            badge.textContent = unreadCount > 99 ? '99+' : unreadCount;
            badge.style.display = 'flex';
            this.log('Badge updated', { unreadCount });
        } else {
            badge.style.display = 'none';
        }
    }

    /**
     * Clear notification badge
     */
    clearNotificationBadge() {
        const badge = document.querySelector(this.constants.BADGE_SELECTOR);
        if (badge) {
            badge.style.display = 'none';
            badge.textContent = '';
            this.log('Badge cleared');
        }
    }

    /**
     * Start jiggle animation on bell icon
     * Continues until user clicks notification
     */
    /**
     * Start jiggle animation
     * @param {number} duration - Optional duration in ms (defaults to infinite)
     */
    startJiggleAnimation(duration = null) {
        const bell = document.querySelector(this.constants.BELL_SELECTOR);
        if (!bell) {
            this.log('Bell element not found', { selector: this.constants.BELL_SELECTOR });
            return;
        }

        if (this.isJiggling) {
            this.log('Jiggle animation already running');
            return;
        }

        this.isJiggling = true;
        
        if (duration) {
            // Temporary jiggle with auto-stop
            bell.classList.add('jiggle');
            setTimeout(() => {
                this.stopJiggleAnimation();
            }, duration);
            this.log('Jiggle animation started (temporary)', { duration });
        } else {
            // Infinite jiggle (until dismissed)
            bell.classList.add('jiggle-infinite');
            this.log('Jiggle animation started (infinite)');
        }
    }

    /**
     * Stop jiggle animation
     */
    stopJiggleAnimation() {
        const bell = document.querySelector(this.constants.BELL_SELECTOR);
        if (bell) {
            bell.classList.remove('jiggle-infinite');
            this.isJiggling = false;
            this.log('Jiggle animation stopped');
        }
    }

    /**
     * Show notification popup modal
     * Modal persists until user dismisses
     *
     * @param {Object} notification
     */
    showNotificationPopup(notification) {
        const modal = document.getElementById(this.constants.MODAL_ID);
        if (!modal) {
            this.log('Notification modal not found', { id: this.constants.MODAL_ID });
            return;
        }

        // Store the current notification being displayed
        this.currentModalNotification = notification;

        // Populate modal content
        const titleEl = modal.querySelector('#fadex-notification-title');
        const subtitleEl = modal.querySelector('#fadex-notification-subtitle');
        const descriptionEl = modal.querySelector('#fadex-notification-description');

        if (titleEl) titleEl.textContent = notification.title || '';
        if (subtitleEl) subtitleEl.textContent = notification.subtitle || '';

        // Parse description and handle links
        if (descriptionEl) {
            descriptionEl.innerHTML = this.parseNotificationDescription(notification.description || '');
        }

        // Add priority class for styling (normalize to lowercase)
        const priority = (notification.priority || this.constants.PRIORITY.INFO).toLowerCase();
        modal.classList.remove('priority-info', 'priority-warning', 'priority-critical');
        modal.classList.add(`priority-${priority}`);

        // Show modal with animation
        modal.classList.add('visible');

        // Setup close handler
        const closeBtn = modal.querySelector('.notification-close-btn');
        if (closeBtn) {
            closeBtn.onclick = () => this.dismissNotification();
        }

        this.log('Notification popup shown', { title: notification.title, priority: priority });
    }

    /**
     * Parse notification description with link support
     * Format: {Link Text|https://url}
     * Also handles plain URLs starting with http/https
     *
     * @param {string} description Raw description text
     * @returns {string} HTML with parsed links
     */
    parseNotificationDescription(description) {
        if (!description) return '';

        // Just preserve line breaks - don't parse URLs
        let html = description.replace(/\n/g, '<br>');
        return html;
    }

    /**
     * Dismiss notification
     * Marks as read, stops jiggle, closes modal, persists state
     */
    dismissNotification() {
        // Only mark the current notification as read (not all)
        if (this.currentModalNotification) {
            // Find it in history and mark as read
            const notif = this.notificationHistory.find(n => n.id === this.currentModalNotification.id);
            if (notif) {
                notif.isRead = true;
                this.saveToCache(this.notificationHistory);
            }
        }

        // Stop animations
        this.stopJiggleAnimation();
        this.updateNotificationBadge();

        // Hide modal
        const modal = document.getElementById(this.constants.MODAL_ID);
        if (modal) {
            modal.classList.remove('visible');
        }
        
        // Clear current notification reference
        this.currentModalNotification = null;
    }

    /**
     * Mark notification as read
     * User clicked on notification icon to view details
     */
    markAsRead() {
        if (this.notificationState) {
            this.notificationState.isRead = true;
            this.saveToCache(this.notificationState);
            this.log('Notification marked as read');
        }
    }

    /**
     * Load notification history from localStorage
     * Persists across page reloads for 30 days
     *
     * @returns {Array} Cached notification array
     */
    loadFromCache() {
        try {
            const cached = localStorage.getItem(this.constants.CACHE_KEY_NOTIFICATION);
            if (cached) {
                return JSON.parse(cached);
            }
        } catch (error) {
            console.error('[Fadex] Error loading from cache:', error);
        }
        return [];
    }

    /**
     * Save notification history to localStorage
     * Persists across page reloads for 30 days
     *
     * @param {Array} notifications
     */
    saveToCache(notifications) {
        try {
            // Save to localStorage for persistence across reloads
            localStorage.setItem(this.constants.CACHE_KEY_NOTIFICATION, JSON.stringify(notifications));
            this.notificationHistory = notifications;
        } catch (error) {
            console.error('[Fadex] Error saving to cache:', error);
            // Fallback to in-memory if localStorage fails
            this.notificationHistory = notifications;
        }
    }

    /**
     * Clean up old notifications based on their expiry setting
     * expiry: -1 = forever (never delete)
     * expiry: 0 = delete immediately
     * expiry: >0 = delete after N days
     */
    cleanOldCache() {
        const now = Date.now();
        const originalCount = this.notificationHistory.length;
        console.log(`🧹 [CACHE-CLEANUP] Starting cleanup of ${originalCount} cached notifications`);
        
        this.notificationHistory = this.notificationHistory.filter(n => {
            const expiry = n.expiry !== undefined ? n.expiry : 30;  // Default 30 days
            const title = n.title || n.id || 'unknown';
            
            // expiry=-1 means keep forever
            if (expiry === -1) {
                console.log(`  ♾️ [KEEP] ${title} (expiry=-1, forever)`);
                return true;
            }
            
            // expiry=0 means delete immediately
            if (expiry === 0) {
                console.log(`  🗑️ [DELETE] ${title} (expiry=0, immediate)`);
                return false;
            }
            
            // expiry>0 means delete after N days
            const expiryMs = expiry * 24 * 60 * 60 * 1000;
            const age = now - (n.timestamp || now);  // If no timestamp, treat as current
            const ageInDays = (age / (24 * 60 * 60 * 1000)).toFixed(1);
            
            if (age < expiryMs) {
                console.log(`  ✅ [KEEP] ${title} (age: ${ageInDays}d, expiry: ${expiry}d)`);
                return true;
            } else {
                console.log(`  🗑️ [DELETE] ${title} (age: ${ageInDays}d > expiry: ${expiry}d)`);
                return false;
            }
        });
        
        const removedCount = originalCount - this.notificationHistory.length;
        console.log(`🧹 [CACHE-CLEANUP] Complete: removed=${removedCount}, remaining=${this.notificationHistory.length}`);
        if (removedCount > 0) {
            this.saveToCache(this.notificationHistory);
            this.log('Cache cleaned', { removed: removedCount, remaining: this.notificationHistory.length });
        }
    }

    /**
     * Get current notification state
     *
     * @returns {Object|null}
     */
    getState() {
        return this.notificationState;
    }

    /**
     * Open notification details
     * Called when user clicks bell icon
     */
    /**
     * Toggle notification panel (history)
     */
    toggleNotificationPanel() {
        console.log('[Bell Panel] toggleNotificationPanel called');
        const panel = document.getElementById('fadexNotificationPanel');
        if (!panel) {
            this.log('Notification panel not found');
            console.error('[Bell Panel] Panel element NOT found!');
            return;
        }

        console.log('[Bell Panel] Panel found:', panel);
        const isVisible = panel.classList.contains('visible');
        console.log('[Bell Panel] Current state - isVisible:', isVisible);
        console.log('[Bell Panel] Current classes:', panel.className);
        console.log('[Bell Panel] Computed display:', window.getComputedStyle(panel).display);
        console.log('[Bell Panel] Computed position:', window.getComputedStyle(panel).position);
        console.log('[Bell Panel] Computed z-index:', window.getComputedStyle(panel).zIndex);
        
        if (isVisible) {
            console.log('[Bell Panel] Hiding panel');
            panel.classList.remove('visible');
        } else {
            console.log('[Bell Panel] Showing panel');
            // Show panel with all notifications
            this.renderNotificationHistory();
            panel.classList.add('visible');
        }
        
        console.log('[Bell Panel] After toggle - classes:', panel.className);
        console.log('[Bell Panel] After toggle - display:', window.getComputedStyle(panel).display);
    }

    /**
     * Render notification history in the panel
     */
    renderNotificationHistory() {
        const listEl = document.getElementById('fadexPanelList');
        if (!listEl) return;

        if (this.notificationHistory.length === 0) {
            listEl.innerHTML = '<div style="padding: 20px; text-align: center; color: #8b949e;">No notifications</div>';
            return;
        }

        // Sort by timestamp (newest first)
        const sorted = [...this.notificationHistory].sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0));

        listEl.innerHTML = sorted.map((notif, index) => {
            const date = new Date(notif.timestamp || Date.now());
            const timeStr = date.toLocaleTimeString();
            const unreadClass = notif.isRead ? '' : 'unread';
            const priorityClass = `priority-${notif.priority || 'info'}`;

            return `
                <div class="fadex-panel-item ${unreadClass} ${priorityClass}" onclick="event.stopPropagation(); window.fadexManager && window.fadexManager.markNotificationAsRead(${index})">
                    <div class="fadex-panel-item-header">
                        <span class="fadex-panel-title">${notif.title || 'Notification'}</span>
                        <span class="fadex-panel-time">${timeStr}</span>
                    </div>
                    <div class="fadex-panel-subtitle">${notif.subtitle || ''}</div>
                </div>
            `;
        }).join('');
    }

    /**
     * Mark specific notification as read and show details in modal
     */
    markNotificationAsRead(index) {
        if (index >= 0 && index < this.notificationHistory.length) {
            const notif = this.notificationHistory[index];
            notif.isRead = true;
            this.saveToCache(this.notificationHistory);
            this.updateNotificationBadge();
            this.renderNotificationHistory();
            
            // Show the notification details in modal
            this.showNotificationModal(notif);
            
            this.log('Notification marked as read and displayed', { index });
        }
    }

    /**
     * Show notification details in modal
     */
    showNotificationModal(notification) {
        const modal = document.getElementById('fadexNotificationModal');
        if (!modal) return;

        const titleEl = document.getElementById('fadex-notification-title');
        const subtitleEl = document.getElementById('fadex-notification-subtitle');
        const descriptionEl = document.getElementById('fadex-notification-description');

        if (titleEl) titleEl.textContent = notification.title || 'Notification';
        if (subtitleEl) subtitleEl.textContent = notification.subtitle || '';
        if (descriptionEl) {
            descriptionEl.innerHTML = this.parseNotificationDescription(notification.description || '');
        }

        modal.classList.add('visible');
        this.log('Notification modal displayed', { version: notification.version });
    }

    /**
     * Open notification details (legacy)
     */
    openNotificationDetails() {
        this.toggleNotificationPanel();
    }

    /**
     * Utility logging function
     * Only logs errors to console for production debugging
     *
     * @param {string} message
     * @param {Object} data Optional data object
     * @param {string} level 'info', 'warn', 'error'
     */
    log(message, data = {}, level = 'info') {
        // Only log errors and warnings
        if (level === 'error') {
            console.error('[Fadex Notification]', message, data);
        } else if (level === 'warning') {
            console.warn('[Fadex Notification]', message, data);
        }
        // Info logs are silent in production
    }
}

// Create singleton instance
let fadexNotificationManager = null;

/**
 * Get or initialize notification manager
 *
 * @returns {FadexNotificationManager}
 */
function initFadexNotificationManager() {
    if (!fadexNotificationManager) {
        fadexNotificationManager = new FadexNotificationManager();
    }
    return fadexNotificationManager;
}
