/**
 * DashboardViewModel - Business logic for dashboard page
 * 
 * Supports two modes (handled by ApiService internally):
 * - Local mode: Phone's HTTP server (192.168.x.x)
 * - Cloud mode: Relay server (live.fadseclab.com:8443)
 * 
 * ApiService automatically detects the mode based on FadCamRemote.isCloudMode()
 * 
 * Multi-tab support (Step 6.11.5.4):
 * Uses BroadcastChannel to coordinate polling across tabs. Only the active/visible
 * tab polls the server. Background tabs receive status updates via broadcast.
 */
class DashboardViewModel {
    constructor() {
        this.statusModel = new ServerStatus();
        this.pollInterval = null;
        this.isPolling = false;
        
        // Multi-tab coordination (Step 6.11.5.4)
        this.tabId = Math.random().toString(36).substring(2, 10);
        this.isLeaderTab = false;
        this.broadcastChannel = null;
        this._initTabCoordination();
    }
    
    /**
     * Initialize tab coordination via BroadcastChannel
     * Only one tab (the "leader") will poll the server
     */
    _initTabCoordination() {
        // BroadcastChannel is not supported in all browsers (Safari < 15.4)
        if (typeof BroadcastChannel === 'undefined') {
            console.log('[TabSync] BroadcastChannel not supported - each tab polls independently');
            this.isLeaderTab = true; // Fallback: every tab is a leader
            return;
        }
        
        try {
            this.broadcastChannel = new BroadcastChannel('fadcam_dashboard_sync');
            
            // Listen for messages from other tabs
            this.broadcastChannel.onmessage = (event) => {
                const { type, tabId, status, timestamp } = event.data;
                
                if (type === 'status-update' && tabId !== this.tabId) {
                    // Another tab pushed a status update - use it instead of polling
                    console.log(`[TabSync] 📥 Received status from tab ${tabId}`);
                    if (status) {
                        this.statusModel.update(status);
                        eventBus.emit('status-updated', this.statusModel);
                    }
                } else if (type === 'leader-claim' && tabId !== this.tabId) {
                    // Another tab claimed leadership (e.g., became visible)
                    console.log(`[TabSync] 🏳️ Tab ${tabId} claimed leadership`);
                    this.isLeaderTab = false;
                    this.stopPolling();
                } else if (type === 'leader-release' && tabId !== this.tabId) {
                    // Leader tab closed or backgrounded - we can claim if visible
                    console.log(`[TabSync] 🎯 Tab ${tabId} released leadership`);
                    if (!document.hidden) {
                        this._claimLeadership();
                    }
                }
            };
            
            // Claim leadership if we're the visible tab
            if (!document.hidden) {
                this._claimLeadership();
            }
            
            // Release leadership when tab is backgrounded or closed
            document.addEventListener('visibilitychange', () => {
                if (document.hidden) {
                    this._releaseLeadership();
                } else {
                    this._claimLeadership();
                }
            });
            
            window.addEventListener('beforeunload', () => {
                this._releaseLeadership();
            });
            
            console.log(`[TabSync] Initialized tab ${this.tabId}, leader: ${this.isLeaderTab}`);
            
        } catch (e) {
            console.warn('[TabSync] Failed to init BroadcastChannel:', e.message);
            this.isLeaderTab = true; // Fallback
        }
    }
    
    /**
     * Claim leadership for this tab (start polling)
     */
    _claimLeadership() {
        this.isLeaderTab = true;
        if (this.broadcastChannel) {
            this.broadcastChannel.postMessage({ type: 'leader-claim', tabId: this.tabId });
        }
        console.log(`[TabSync] 👑 Tab ${this.tabId} is now leader`);
        this.startPolling();
    }
    
    /**
     * Release leadership (stop polling, let another tab take over)
     */
    _releaseLeadership() {
        if (this.isLeaderTab) {
            this.isLeaderTab = false;
            if (this.broadcastChannel) {
                this.broadcastChannel.postMessage({ type: 'leader-release', tabId: this.tabId });
            }
            console.log(`[TabSync] 🏳️ Tab ${this.tabId} released leadership`);
            this.stopPolling();
        }
    }
    
    /**
     * Broadcast status to other tabs
     */
    _broadcastStatus(status) {
        if (this.broadcastChannel && this.isLeaderTab) {
            this.broadcastChannel.postMessage({ 
                type: 'status-update', 
                tabId: this.tabId, 
                status: status,
                timestamp: Date.now()
            });
        }
    }
    
    /**
     * Initialize ViewModel - start status polling
     */
    async initialize() {
        console.log('[DashboardViewModel] Initializing...');
        
        // Listen for cloud mode ready event (FadCamRemote sets up stream context)
        if (typeof eventBus !== 'undefined') {
            eventBus.on('cloud-mode-ready', (ctx) => {
                console.log('[DashboardViewModel] ☁️ Cloud mode ready, reinitializing...', ctx);
                this.updateStatus(); // Force refresh with updated context
            });
        }
        
        // Initial fetch
        await this.updateStatus();
        
        // Start polling
        this.startPolling();
    }
    
    /**
     * Update status from API
     * ApiService automatically handles local vs cloud mode
     */
    async updateStatus() {
        try {
            const data = await apiService.getStatus();
            this.statusModel.update(data);
            
            // Emit event for views to update
            eventBus.emit('status-updated', this.statusModel);
            
            // Broadcast to other tabs (Step 6.11.5.4)
            this._broadcastStatus(data);
            
            const modeLabel = data.cloudMode ? '☁️' : '📱';
            console.log(`✅ [DashboardViewModel] ${modeLabel} Status updated: ${data.state}, streaming: ${data.streaming}, clients: ${data.totalConnectedClients || 0}`);
        } catch (error) {
            console.error('❌ [DashboardViewModel] Failed to update status:', error.message);
            
            // Determine if we're in cloud mode (web access)
            const isWebMode = typeof FadCamRemote !== 'undefined' && 
                              typeof FadCamRemote.isWebAccess === 'function' && 
                              FadCamRemote.isWebAccess();
            
            // Emit offline status with cloudMode flag for proper staleness detection
            this.statusModel.update({ state: 'offline', cloudMode: isWebMode });
            eventBus.emit('status-updated', this.statusModel);
        }
    }
    
    /**
     * Safe emit with error handling
     */
    safeEmit() {
        try {
            eventBus.emit('status-updated', this.statusModel);
        } catch (error) {
            console.error('[DashboardViewModel] Error emitting status:', error);
        }
    }
    
    /**
     * Start polling for status updates
     * Only the leader tab polls; other tabs receive updates via broadcast
     */
    startPolling() {
        if (this.isPolling) return;
        
        // Only poll if we're the leader tab (Step 6.11.5.4)
        if (!this.isLeaderTab) {
            console.log('[DashboardViewModel] Not leader tab - skipping polling (will receive updates via broadcast)');
            return;
        }
        
        this.isPolling = true;
        this.pollInterval = setInterval(() => {
            this.updateStatus();
        }, CONFIG.STATUS_POLL_INTERVAL);
        
        console.log('[DashboardViewModel] 👑 Leader tab - started polling every', CONFIG.STATUS_POLL_INTERVAL / 1000, 'seconds');
    }
    
    /**
     * Stop polling
     */
    stopPolling() {
        if (this.pollInterval) {
            clearInterval(this.pollInterval);
            this.pollInterval = null;
        }
        this.isPolling = false;
        console.log('[DashboardViewModel] Stopped polling');
    }
    
    /**
     * Pause polling (for lock screen)
     */
    pausePolling() {
        this._releaseLeadership();
        console.log('[DashboardViewModel] Paused polling for auth lock');
    }
    
    /**
     * Resume polling (after unlock)
     */
    resumePolling() {
        if (!this.isPolling && !document.hidden) {
            this._claimLeadership();
            console.log('[DashboardViewModel] Resumed polling after unlock');
        }
    }
    
    /**
     * Toggle torch (flashlight)
     */
    async toggleTorch() {
        try {
            const result = await apiService.toggleTorch();
            console.log('[DashboardViewModel] Torch toggled:', result);
            
            // Emit event
            eventBus.emit('torch-toggled', result);
            
            // Force status update to get new state
            await this.updateStatus();
            
            return result;
        } catch (error) {
            console.error('[DashboardViewModel] Failed to toggle torch:', error);
            throw error;
        }
    }

    /**
     * Toggle recording on/off
     */
    async toggleRecording() {
        try {
            // Check if streaming is enabled AND codec is HEVC
            if (this.statusModel.streaming && this.statusModel.isHevcCodec) {
                // H.265/HEVC is not browser-compatible for HLS streaming
                // Show Material Dialog with option to switch to AVC
                return new Promise((resolve, reject) => {
                    showCodecCompatibilityDialog(async () => {
                        try {
                            // User confirmed - switch codec to AVC first
                            console.log('[DashboardViewModel] Switching codec to AVC...');
                            await apiService.post('/config/videoCodec', { codec: 'AVC' });
                            console.log('[DashboardViewModel] Codec switched successfully');
                            
                            // Now proceed with recording
                            this._proceedWithRecordingToggle()
                                .then(resolve)
                                .catch(reject);
                        } catch (error) {
                            console.error('[DashboardViewModel] Failed to switch codec:', error);
                            // Still try to proceed with recording even if codec switch failed
                            this._proceedWithRecordingToggle()
                                .then(resolve)
                                .catch(reject);
                        }
                    }, () => {
                        // User cancelled
                        reject(new Error('User cancelled recording due to codec check'));
                    });
                });
            }
            
            // No streaming or codec is already H.264, just proceed normally
            return await this._proceedWithRecordingToggle();
        } catch (error) {
            console.error('[DashboardViewModel] Failed to toggle recording:', error);
            throw error;
        }
    }
    
    /**
     * Actually proceed with recording toggle (after codec checks)
     */
    async _proceedWithRecordingToggle() {
        try {
            const result = await apiService.post('/recording/toggle', {});
            console.log('[DashboardViewModel] Recording toggled:', result);
            
            // Emit event
            eventBus.emit('recording-toggled', result);
            
            // Force status update to get new state
            await this.updateStatus();
            
            return result;
        } catch (error) {
            console.error('[DashboardViewModel] Failed to toggle recording:', error);
            throw error;
        }
    }

    /**
     * Pause active recording
     * @returns {Promise<Object>} Response with {status, action, isRecording, isPaused}
     */
    async pauseRecording() {
        try {
            const result = await apiService.pauseRecording();
            console.log('[DashboardViewModel] Recording paused:', result);
            eventBus.emit('recording-paused', result);
            await this.updateStatus();
            return result;
        } catch (error) {
            console.error('[DashboardViewModel] Failed to pause recording:', error);
            throw error;
        }
    }

    /**
     * Resume paused recording
     * @returns {Promise<Object>} Response with {status, action, isRecording, isPaused}
     */
    async resumeRecording() {
        try {
            const result = await apiService.resumeRecording();
            console.log('[DashboardViewModel] Recording resumed:', result);
            eventBus.emit('recording-resumed', result);
            await this.updateStatus();
            return result;
        } catch (error) {
            console.error('[DashboardViewModel] Failed to resume recording:', error);
            throw error;
        }
    }

    /**
     * Toggle server on/off
     */
    async toggleServer() {
        try {
            const result = await apiService.post('/server/toggle', {});
            console.log('[DashboardViewModel] Server toggled:', result);
            await this.updateStatus();
            return result;
        } catch (error) {
            console.error('[DashboardViewModel] Failed to toggle server:', error);
            throw error;
        }
    }

    /**
     * Set recording mode (stream_only, record_only, stream_and_record)
     */
    async setRecordingMode(mode) {
        try {
            const result = await apiService.post('/config/recordingMode', { mode });
            console.log('[DashboardViewModel] Recording mode set to:', mode);
            await this.updateStatus();
            return result;
        } catch (error) {
            console.error('[DashboardViewModel] Failed to set recording mode:', error);
            throw error;
        }
    }

    /**
     * Set stream quality (low, medium, high)
     */
    async setStreamQuality(quality) {
        try {
            const result = await apiService.post('/config/streamQuality', { quality });
            console.log('[DashboardViewModel] Stream quality set to:', quality);
            await this.updateStatus();
            return result;
        } catch (error) {
            console.error('[DashboardViewModel] Failed to set stream quality:', error);
            throw error;
        }
    }

    /**
     * Set battery warning threshold
     */
    async setBatteryWarning(threshold) {
        try {
            const result = await apiService.post('/config/batteryWarning', { threshold: parseInt(threshold) });
            console.log('[DashboardViewModel] Battery warning threshold set to:', threshold);
            await this.updateStatus();
            return result;
        } catch (error) {
            console.error('[DashboardViewModel] Failed to set battery warning:', error);
            throw error;
        }
    }
    
    /**
     * Get current status model
     * @returns {ServerStatus}
     */
    getStatus() {
        return this.statusModel;
    }
    
    /**
     * Cleanup resources
     */
    destroy() {
        this.stopPolling();
        eventBus.clear('status-updated');
        eventBus.clear('torch-toggled');
    }
}
