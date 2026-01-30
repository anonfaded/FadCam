/**
 * RealtimeCommandService - Supabase Realtime for instant command delivery
 * 
 * In cloud mode, commands are sent via Supabase Realtime broadcast instead of 
 * HTTP polling. This provides <200ms latency vs 3 second polling delay.
 * 
 * Architecture:
 * - Dashboard connects to Supabase Realtime channel: "device:{device_id}"
 * - Commands are broadcast with event type "command"
 * - Phone receives instantly via WebSocket subscription
 * - Fallback to HTTP relay if Realtime fails
 * 
 * Usage:
 * - Call realtimeCommandService.initialize(deviceId, userId) in cloud mode
 * - Call realtimeCommandService.sendCommand(action, params) for instant delivery
 */
class RealtimeCommandService {
    constructor() {
        // Supabase configuration
        this.SUPABASE_URL = 'https://vfhehknmxxedvesdvpew.supabase.co';
        this.SUPABASE_ANON_KEY = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InZmaGVoa25teHhlZHZlc2R2cGV3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjY3NzgxMjYsImV4cCI6MjA4MjM1NDEyNn0.IRTO3qW5SpseCxrirsQRnFJ38IFj47dOfJxlHG2n9aI';
        
        this.supabase = null;
        this.channel = null;
        this.isConnected = false;
        this.deviceId = null;
        this.userId = null;
        this.commandsSent = 0;
    }
    
    /**
     * Initialize Realtime connection for cloud mode.
     * 
     * @param {string} deviceId - Target device ID
     * @param {string} userId - User UUID
     */
    async initialize(deviceId, userId) {
        if (!deviceId || !userId) {
            console.warn('[RealtimeCommand] ❌ Missing deviceId or userId');
            return false;
        }
        
        this.deviceId = deviceId;
        this.userId = userId;
        
        try {
            // Check if Supabase JS is available
            if (typeof supabase === 'undefined' || !supabase.createClient) {
                console.warn('[RealtimeCommand] ❌ Supabase JS not loaded');
                return false;
            }
            
            // Create Supabase client
            this.supabase = supabase.createClient(this.SUPABASE_URL, this.SUPABASE_ANON_KEY);
            
            // Create channel for this device
            const channelName = `device:${deviceId}`;
            console.log(`[RealtimeCommand] 📡 Connecting to channel: ${channelName}`);
            
            this.channel = this.supabase.channel(channelName, {
                config: {
                    broadcast: { ack: true, self: false }
                }
            });
            
            // Subscribe to channel
            this.channel.subscribe((status) => {
                console.log(`[RealtimeCommand] 📡 Channel status: ${status}`);
                
                if (status === 'SUBSCRIBED') {
                    this.isConnected = true;
                    console.log('[RealtimeCommand] ✅ Connected to Supabase Realtime');
                } else if (status === 'CHANNEL_ERROR') {
                    this.isConnected = false;
                    console.error('[RealtimeCommand] ❌ Channel error');
                } else if (status === 'CLOSED') {
                    this.isConnected = false;
                    console.log('[RealtimeCommand] 📡 Channel closed');
                }
            });
            
            return true;
            
        } catch (error) {
            console.error('[RealtimeCommand] ❌ Failed to initialize:', error);
            return false;
        }
    }
    
    /**
     * Send command via Supabase Realtime broadcast.
     * 
     * @param {string} action - Command action (e.g., "torch_toggle")
     * @param {Object} params - Command parameters
     * @returns {Promise<Object>} Result with success status
     */
    async sendCommand(action, params = {}) {
        const startTime = performance.now();
        
        if (!this.isConnected || !this.channel) {
            console.warn('[RealtimeCommand] ❌ Not connected, cannot send command');
            return { success: false, error: 'Not connected to Realtime' };
        }
        
        try {
            this.commandsSent++;
            console.log(`[RealtimeCommand] ⚡ Sending INSTANT command #${this.commandsSent}: ${action}`);
            
            const response = await this.channel.send({
                type: 'broadcast',
                event: 'command',
                payload: {
                    action: action,
                    params: params,
                    timestamp: Date.now(),
                    source: 'dashboard'
                }
            });
            
            const latency = (performance.now() - startTime).toFixed(0);
            
            if (response === 'ok') {
                console.log(`[RealtimeCommand] ✅ Command sent in ${latency}ms: ${action}`);
                return { 
                    success: true, 
                    latency: parseInt(latency),
                    message: `Instant command sent (${latency}ms)`
                };
            } else {
                console.error(`[RealtimeCommand] ❌ Command failed: ${response}`);
                return { success: false, error: response };
            }
            
        } catch (error) {
            console.error('[RealtimeCommand] ❌ Send failed:', error);
            return { success: false, error: error.message };
        }
    }
    
    /**
     * Check if Realtime is connected and ready.
     */
    isReady() {
        return this.isConnected && this.channel !== null;
    }
    
    /**
     * Disconnect from Supabase Realtime.
     */
    disconnect() {
        if (this.channel) {
            this.channel.unsubscribe();
            this.channel = null;
        }
        this.isConnected = false;
        console.log(`[RealtimeCommand] 📡 Disconnected (sent ${this.commandsSent} commands)`);
    }
}

// Global singleton instance
const realtimeCommandService = new RealtimeCommandService();
