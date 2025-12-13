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
     * Fetches all active notifications and updates history
     * Handles multiple notification types (intro, main, etc)
     * Also handles expiry=0 (immediate deletion from cache)
     */
    async checkForUpdates() {
        console.log(`🔄 [CHECK-UPDATES] Starting notification update check...`);
        try {
            // Fetch raw data (before filtering)
            const rawData = await this.fetchNotificationFromGitHub();
            console.log(`🔄 [CHECK-UPDATES] Received raw data:`, rawData ? Object.keys(rawData) : null);
            
            if (!rawData || !rawData.notifications) {
                console.log(`⚠️ [CHECK-UPDATES] No raw data or notifications property found`);
                this.retryCount = 0;
                return;
            }

            // Build map of all remote notifications with their sourceIds
            const allRemoteNotifications = Object.entries(rawData.notifications).map(([sourceId, notif]) => ({
                sourceId,  // This is the unique ID (intro, main, etc)
                ...notif
            }));
            console.log(`🔄 [CHECK-UPDATES] All remote notifications:`, allRemoteNotifications.map(n => ({ sourceId: n.sourceId, title: n.title, expiry: n.expiry })));

            let hasUpdates = false;

            // STEP 1: Handle deletions - remove notifications with expiry=0 OR draft=true from cache
            console.log(`\n📝 [STEP-1-DELETE] Checking for notifications marked with expiry=0 or draft=true...`);
            for (const remoteNotif of allRemoteNotifications) {
                const shouldDelete = remoteNotif.expiry === 0 || remoteNotif.draft === true;
                
                if (shouldDelete) {
                    const reason = remoteNotif.expiry === 0 ? 'expiry=0' : 'draft=true';
                    console.log(`  🗑️ Remote notification "${remoteNotif.sourceId}" (${remoteNotif.title}) marked for deletion (${reason})`);
                    console.log(`     Current cache before deletion:`, this.notificationHistory.map(n => ({ sourceId: n.sourceId, title: n.title })));
                    
                    // Remove from cache by matching sourceId
                    const oldCount = this.notificationHistory.length;
                    this.notificationHistory = this.notificationHistory.filter(n => 
                        n.sourceId !== remoteNotif.sourceId
                    );
                    const removedCount = oldCount - this.notificationHistory.length;
                    
                    if (removedCount > 0) {
                        console.log(`  ✅ Deleted ${removedCount} item(s) with sourceId="${remoteNotif.sourceId}" from cache (${reason})`);
                        hasUpdates = true;
                    } else {
                        console.log(`  ℹ️ sourceId="${remoteNotif.sourceId}" not found in cache`);
                    }
                }
            }

            // STEP 2: Get filtered active notifications (excludes expiry=0 and drafts)
            const remoteNotifications = this.filterAndGetNotifications(rawData.notifications);
            console.log(`\n📝 [STEP-2-UPDATE] Processing ${remoteNotifications.length} active (non-deleted) notifications...`);

            // STEP 3: Process active notifications for updates
            for (const remoteNotification of remoteNotifications) {
                const sourceId = remoteNotification.id;  // This is the unique key from JSON
                console.log(`\n  📊 Processing sourceId="${sourceId}" (${remoteNotification.title})`);
                
                // Find cached versions of this SAME notification (by sourceId)
                const existingVersions = this.notificationHistory.filter(n => 
                    n.sourceId === sourceId
                );
                const latestVersion = existingVersions.length > 0 
                    ? Math.max(...existingVersions.map(n => n.version || 0))
                    : 0;
                
                console.log(`     Version check: remote v${remoteNotification.version} vs cached v${latestVersion}`);

                // Check if this notification has a new version
                if (remoteNotification.version > latestVersion) {
                    console.log(`     ✅ NEW VERSION DETECTED`);
                    hasUpdates = true;
                    
                    // Remove OLD versions of this SAME notification (by sourceId)
                    const oldCount = this.notificationHistory.length;
                    this.notificationHistory = this.notificationHistory.filter(n => 
                        n.sourceId !== sourceId
                    );
                    const removedCount = oldCount - this.notificationHistory.length;
                    
                    if (removedCount > 0) {
                        console.log(`     Removed ${removedCount} old version(s)`);
                    }
                    
                    // Add the new notification with sourceId
                    const notification = {
                        ...remoteNotification,
                        sourceId: sourceId,  // Store the unique ID for future matching
                        timestamp: Date.now(),
                        isRead: false,
                        id: `notif-${Date.now()}`,  // Unique instance ID for DOM
                    };

                    console.log(`     Adding new version:`, {
                        sourceId: sourceId,
                        title: notification.title,
                        version: notification.version,
                        expiry: notification.expiry,
                    });

                    this.notificationHistory.push(notification);
                } else {
                    console.log(`     ℹ️ No update (same or older version)`);
                }
            }

            // STEP 4: Save and render if there were changes
            if (hasUpdates) {
                console.log(`\n💾 [STEP-3-SAVE] Saving cache with ${this.notificationHistory.length} notification(s)`);
                console.log(`     Final cache:`, this.notificationHistory.map(n => ({ sourceId: n.sourceId, title: n.title, version: n.version })));
                
                this.saveToCache(this.notificationHistory);
                this.updateNotificationBadge();
                this.renderNotificationHistory();
                this.startJiggleAnimation(3000);
            } else {
                console.log(`\nℹ️ [NO-UPDATES] No notification changes detected`);
            }

            this.retryCount = 0;
        } catch (error) {
            console.error(`❌ [CHECK-UPDATES] Error:`, error);
            this.handleNetworkError(error);
        }
    }

    /**
     * Fetch all active notifications from GitHub
     * @returns {Promise<Array>} Array of active notifications or empty array
     */
    async fetchNotification() {
        const remoteData = await this.fetchNotificationFromGitHub();
        if (!remoteData || !remoteData.notifications) return [];
        return this.filterAndGetNotifications(remoteData.notifications);
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

        // Return all active notifications (not just highest version)
        // Each notification type (intro, main, etc) can have its own version
        if (activeNotifications.length === 0) {
            console.log(`📋 [FILTER] No active notifications to return`);
            return [];
        }
        console.log(`📋 [FILTER] Returning ${activeNotifications.length} active notification(s):`, 
            activeNotifications.map(n => ({ id: n.id, version: n.version, expiry: n.expiry }))
        );
        return activeNotifications;
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
            // Return raw data (including expiry=0 notifications for deletion)
            return json;
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
                const parsed = JSON.parse(cached);
                console.log(`📥 [CACHE-LOAD] Loaded ${parsed.length} notification(s) from localStorage`);
                parsed.forEach(n => console.log(`  - ${n.title} (expiry: ${n.expiry})`));
                return parsed;
            }
            console.log(`📥 [CACHE-LOAD] No cached notifications found`);
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

        listEl.innerHTML = sorted.map((notif) => {
            const date = new Date(notif.timestamp || Date.now());
            const timeStr = date.toLocaleTimeString();
            const unreadClass = notif.isRead ? '' : 'unread';
            const priorityClass = `priority-${notif.priority || 'info'}`;

            return `
                <div class="fadex-panel-item ${unreadClass} ${priorityClass}" onclick="event.stopPropagation(); window.fadexManager && window.fadexManager.markNotificationAsReadById('${notif.id}')">
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
     * Mark specific notification as read by ID and show details in modal
     */
    markNotificationAsReadById(notifId) {
        const notif = this.notificationHistory.find(n => n.id === notifId);
        if (notif) {
            notif.isRead = true;
            this.saveToCache(this.notificationHistory);
            this.updateNotificationBadge();
            this.renderNotificationHistory();
            
            // Show the notification details in modal
            this.showNotificationModal(notif);
            
            this.log('Notification marked as read and displayed', { id: notifId });
        }
    }

    /**
     * Mark specific notification as read and show details in modal
     * @deprecated Use markNotificationAsReadById instead
     */
    markNotificationAsRead(index) {
        if (index >= 0 && index < this.notificationHistory.length) {
            const notif = this.notificationHistory[index];
            this.markNotificationAsReadById(notif.id);
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
